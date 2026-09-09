# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Wandering Table** is a Spring Boot 4.0.3 + Kotlin REST API backend for a tabletop club: a catalogue of board games, a hierarchy of club events (regular games, tournaments, championships, leagues), per-player statistics, news, and JWT authentication with refresh-token rotation.

## Commands

```bash
# Build
./gradlew build
./gradlew clean build

# Run
docker-compose up -d wandering_table_db   # PostgreSQL 15 on 127.0.0.1:5432
./gradlew bootRun                          # App on port 8050

# Test — requires a running Docker daemon (Testcontainers starts PostgreSQL 15)
./gradlew test
./gradlew test --tests '*Concurrent*'      # race-condition tests only
```

The daemon JVM is pinned to 17 in `gradle/gradle-daemon-jvm.properties` — same version as the `java.toolchain` this project compiles with and as the `Dockerfile` build stage, so an image build needs no extra JDK, and the foojay resolver in `settings.gradle.kts` lets Gradle download it when the machine has none — so the wrapper works even when the `java` on `PATH` is older, and `JAVA_HOME` need not be set. Regenerate the pin with `./gradlew updateDaemonJvm --jvm-version=<n>` rather than editing the file by hand.

Integration tests run against Testcontainers, so Docker must be running; without it all of them fail at startup with "Could not find a valid Docker environment".

## Architecture

**Stack:** Kotlin 2.2.21, Spring Boot 4.0.3 (Spring Framework 7, **Jackson 3** — group `tools.jackson`, not `com.fasterxml`), Spring Security, Spring Data JPA, PostgreSQL, JJWT.

**Package root:** `ru.gohasoft.wanderingtable`

**Layers:**
- `controllers/` — REST endpoints: `AuthController`, `UserController`, `GameController`, `EventController`, `TournamentController`, `NewsNoteController`, `StatusController`
- `controllers/dto/` — request/response DTOs and their mappers (not nested in controllers)
- `service/` — business rules and authorization (`@PreAuthorize` lives here, not on controllers)
- `service/strategy/` — pluggable tournament algorithms plus their registries
- `security/` — `JwtAuthFilter` → `JwtService` → `AuthService`, `SecurityConfig`, `HashEncoder`, `SlidingWindowRateLimiter` (+ `RegisterRateLimiter`, `PasswordResetRateLimiter`), `ClubManagerBootstrap`
- `mail/` — `PasswordResetMailer` и две его реализации; какая поднимется, решает `MailConfig`
- `database/model/`, `database/model/event/`, `database/model/game/`, `database/model/result/` — JPA entities
- `database/repository/` — Spring Data repositories
- `GlobalExceptionHandler` — the single place mapping exceptions to HTTP status codes

## Domain model

Two-branch JPA inheritance (`InheritanceType.JOINED`) rooted at `Event`:

```
Event                  (events)
├── GameEvent          (game_events)          — a played match; the source of statistics
│   ├── RegularGame        (regular_games)        — open "looking for players" request
│   └── TournamentGame     (tournament_games)     — a match inside a competition
└── TournamentEvent    (tournament_events)     — a competition container
    ├── SingleTournament   (single_tournaments)
    ├── Championship       (championships)        — long-running, with elimination
    └── League             (leagues)              — long-running, points-based
```

`Game` (`games`) is the **catalogue** entry ("Carcassonne", "Munchkin") — not a match. Every event references one.

`GameResult` (`game_results`) is another JOINED hierarchy: `WinLossResult`, `PointsResult`, `PlacementResult`. `Game.resultType` decides which subclass is accepted for its matches.

Supporting entities: `EventParticipant`, `LeagueStanding`, `ChampionshipStanding`, `User`, `UserDevice`, `NewsNote`, `RefreshToken`, `PasswordResetToken`.

## Key Design Decisions

**Entities in inheritance hierarchies declare properties in the class body, not the constructor.** The Kotlin noarg plugin does not generate a no-arg constructor for `abstract` classes, so subclasses inherited no callable `super()` and Hibernate failed with "No default constructor for entity". A class with no constructor parameters gets one for free. Construct these entities with `RegularGame().apply { … }`.

**State changes on events use native SQL, not JPQL.** `Event` is the root of a JOINED hierarchy, and Hibernate rewrites a JPQL bulk update against it into a two-phase temp-table operation whose predicate is evaluated by an unlocked `SELECT`. That silently destroys the concurrency guarantee: a native single-statement `UPDATE … WHERE participants_count < max_participants` takes a row lock and is the only form that actually works. See `EventRepository`.

**Concurrency control is in the database, not in service-layer checks.** Read-then-write checks are kept only as fast paths; correctness comes from unique indexes (`users.email`, `(event_id, user_id)`, `(game_event_id, user_id)`, `games.name`, `refresh_tokens.hashed_token`), conditional single-statement `UPDATE`s (seat reservation, status transitions), atomic increments (`league_standings.points`), and `INSERT … ON CONFLICT DO NOTHING` for idempotent row creation.

**Refresh-token rotation** uses a bulk `DELETE … WHERE` returning the affected-row count (`RefreshTokenRepository.consume`). A derived `deleteBy…` would load-then-delete and report the number of *loaded* rows, letting two concurrent refreshes both succeed. Tokens carry a unique `jti`; without it two logins in the same second produced byte-identical JWTs.

**Password reset is a two-step, single-use exchange.** `POST /auth/forgot-password` emails a six-digit code and always answers 204 — a different status for an unknown address would turn the endpoint into a membership oracle. `POST /auth/verify-reset-code` swaps the code for an opaque `resetToken`, `POST /auth/reset-password` spends that token and **deletes every refresh token of the user**, so a stolen old password cannot survive the change through an already-issued session. Both artefacts are rows in `password_reset_tokens` (one table, a `stage` discriminator) rather than JWTs, because they must be revocable and single-use; each is consumed by a delete-by-PK returning the affected row count, so the loser of a race is rejected. The code is hashed with the user id as salt — a bare SHA-256 over a 10^6 keyspace is a rainbow table. Guessing is bounded only by `PasswordResetRateLimiter`: there is no per-row attempt counter, the row is spent on the first check.

**Email is optional.** `spring.mail.*` is deliberately absent from `application.properties`: a present-but-empty `spring.mail.host` would satisfy `@ConditionalOnProperty` and select SMTP with no server. With the host unset (env var `SPRING_MAIL_HOST`), `MailConfig` falls back to `LoggingPasswordResetMailer`, which prints the code — so the app runs, and the whole flow is testable, without SMTP. `docker-compose` ships Mailpit for local dev (web UI on http://localhost:8025).

**Roles** are a `Set<Role>` (`@ElementCollection` + `@Enumerated(STRING)`) — `CLUB_MANAGER`, `PLAYER`, `NEWS_CREATOR`, `GAME_CREATOR`, `TOURNAMENT_CREATOR`. `PLAYER` is granted at registration and can never be removed. Only `CLUB_MANAGER` grants roles; the first one is bootstrapped from `app.bootstrap.club-manager-email`. `JwtAuthFilter` loads roles **from the database on every request** so revocation takes effect immediately rather than after the access token expires.

**Statistics are computed on read**, never stored on `User`: aggregates over `game_results`. `GameResult.outcome` lives on the base class (so no query needs to UNION the subtables) but is a derived cache — it is written only by `GameEventService.finish`, which calls `resolveOutcome` on each result with the full set of the match's results.

**Tournament algorithms are Strategy beans** resolved through registries keyed by an enum stored on the event: `BracketStrategy` (`RoundRobinBracketStrategy`), `LeagueScoringStrategy` (`LevelWeightedScoringStrategy`), `EliminationStrategy` (`LoserEliminationStrategy`). Adding a variant means one new class plus one enum constant.

**Timestamps use `nowTruncated()`** (microsecond precision) because PostgreSQL `timestamp` drops nanoseconds — otherwise the value in a create response differs from the value read back.

**Security config:** CSRF disabled (API only). Public routes: `GET /`, `/auth/**`, `GET /notes`. Everything else requires a JWT. `@EnableMethodSecurity` is on the application class — without it every `@PreAuthorize` silently passes.

**Database:** schema auto-updated via `spring.jpa.hibernate.ddl-auto=update`; no Flyway. Constraints and indexes are declared on the entities so Hibernate creates them.

## Configuration

| Setting | Value |
|---|---|
| Server port | 8050 |
| DB URL | `jdbc:postgresql://localhost:5432/postgres` |
| DB user/pass | `postgres` / `pass` |
| JWT secret env var | `JWT_SECRET_BASE64` (Base64, ≥32 bytes) |
| First club manager | `CLUB_MANAGER_EMAIL` → `app.bootstrap.club-manager-email` |
| Register rate limit | `app.rate-limit.register.*` (per-instance, in-memory) |
| Password reset rate limit | `app.rate-limit.password-reset.*` (per IP and per email) |
| Reset code / token TTL | `app.password-reset.code-ttl-minutes` (15) / `.token-ttl-minutes` (10) |
| SMTP | env `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`; sender `MAIL_FROM` |
| Access / refresh TTL | 15 minutes / 30 days |

## Testing

`IntegrationTestBase` (in `support/`) starts a singleton PostgreSQL 15 Testcontainer and truncates every table before each test; `ClubFixtures` adds domain helpers. Tests are **not** `@Transactional` — the concurrency tests need real commits. H2 is deliberately not used: it differs from PostgreSQL exactly where these tests probe (row locking, constraint behaviour).

`runConcurrently(threads) { … }` in `support/Concurrency.kt` releases all threads from a barrier simultaneously; without it the first request finishes before the others start and no race is reproduced.

## API documentation

`ANDROID_CLIENT_API.md` documents every endpoint for the mobile client. Keep it in sync when changing controllers or DTOs.
