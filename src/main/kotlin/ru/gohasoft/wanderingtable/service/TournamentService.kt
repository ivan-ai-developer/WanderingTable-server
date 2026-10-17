package ru.gohasoft.wanderingtable.service

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import ru.gohasoft.wanderingtable.database.model.ObjectId
import ru.gohasoft.wanderingtable.database.model.event.Championship
import ru.gohasoft.wanderingtable.database.model.event.EventParticipant
import ru.gohasoft.wanderingtable.database.model.event.EventStatus
import ru.gohasoft.wanderingtable.database.model.event.League
import ru.gohasoft.wanderingtable.database.model.event.SingleTournament
import ru.gohasoft.wanderingtable.database.model.event.SkillLevel
import ru.gohasoft.wanderingtable.database.model.event.TournamentEvent
import ru.gohasoft.wanderingtable.database.model.event.TournamentGame
import ru.gohasoft.wanderingtable.database.model.game.Game
import ru.gohasoft.wanderingtable.database.repository.EventParticipantRepository
import ru.gohasoft.wanderingtable.database.repository.TournamentEventRepository
import ru.gohasoft.wanderingtable.database.repository.TournamentGameRepository
import ru.gohasoft.wanderingtable.service.strategy.bracket.BracketStrategyRegistry
import ru.gohasoft.wanderingtable.service.strategy.bracket.BracketStrategyType
import ru.gohasoft.wanderingtable.service.strategy.elimination.EliminationStrategyType
import ru.gohasoft.wanderingtable.service.strategy.league.scoring.LeagueScoringStrategyType
import java.math.BigDecimal
import java.time.Instant
import kotlin.jvm.optionals.getOrNull

/** Какой подтип турнирного события создаётся. */
enum class TournamentKind { SINGLE, CHAMPIONSHIP, LEAGUE }

@Service
class TournamentService(
    private val tournamentEventRepository: TournamentEventRepository,
    private val tournamentGameRepository: TournamentGameRepository,
    private val eventParticipantRepository: EventParticipantRepository,
    private val bracketStrategies: BracketStrategyRegistry,
    private val championshipService: ChampionshipService,
    private val leagueService: LeagueService,
    private val eventService: EventService,
    private val gameService: GameService,
    private val eventPushNotificationPublisher: EventPushNotificationPublisher,
) {

    fun requireById(tournamentId: ObjectId): TournamentEvent =
        tournamentEventRepository.findById(tournamentId.value).getOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Tournament event not found.")

    fun list(status: EventStatus?, gameId: String?, pageable: Pageable): Page<TournamentEvent> =
        when {
            status != null && gameId != null ->
                tournamentEventRepository.findByStatusAndGameId(status, gameId, pageable)
            status != null -> tournamentEventRepository.findByStatus(status, pageable)
            gameId != null -> tournamentEventRepository.findByGameId(gameId, pageable)
            else -> tournamentEventRepository.findAll(pageable)
        }

    fun gamesOf(tournamentId: ObjectId): List<TournamentGame> =
        tournamentGameRepository.findByTournamentEventIdOrderByRoundAsc(tournamentId.value)

    /**
     * Создание турнира, чемпионата или лиги. Все три — подтипы [TournamentEvent],
     * поэтому создаются одним запросом с дискриминатором [TournamentKind].
     */
    @PreAuthorize("hasRole('TOURNAMENT_CREATOR')")
    @Transactional
    fun create(
        creatorId: ObjectId,
        kind: TournamentKind,
        gameId: ObjectId,
        title: String,
        description: String,
        startsAt: Instant,
        endsAt: Instant?,
        entryFee: BigDecimal,
        expectedSkillLevel: SkillLevel,
        minParticipants: Int,
        maxParticipants: Int,
        bracketStrategy: BracketStrategyType,
        eliminationStrategy: EliminationStrategyType,
        scoringStrategy: LeagueScoringStrategyType,
        seasonStart: Instant?,
        seasonEnd: Instant?
    ): TournamentEvent {
        gameService.requireById(gameId)
        if (maxParticipants < minParticipants) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "maxParticipants must be greater than or equal to minParticipants."
            )
        }
        if (endsAt != null && endsAt.isBefore(startsAt)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "endsAt must not precede startsAt.")
        }

        val event: TournamentEvent = when (kind) {
            TournamentKind.SINGLE -> SingleTournament().apply {
                this.bracketStrategy = bracketStrategy
            }
            TournamentKind.CHAMPIONSHIP -> Championship().apply {
                this.bracketStrategy = bracketStrategy
                this.eliminationStrategy = eliminationStrategy
            }
            TournamentKind.LEAGUE -> League().apply {
                this.scoringStrategy = scoringStrategy
                this.seasonStart = seasonStart ?: startsAt
                this.seasonEnd = seasonEnd ?: endsAt
            }
        }

        event.apply {
            this.title = title.trim()
            this.description = description.trim()
            this.gameId = gameId
            this.creatorId = creatorId
            this.startsAt = startsAt
            this.endsAt = endsAt
            this.entryFee = entryFee
            this.expectedSkillLevel = expectedSkillLevel
            this.minParticipants = minParticipants
            this.maxParticipants = maxParticipants
            this.participantsCount = 1
        }

        val saved = tournamentEventRepository.save(event)
        eventParticipantRepository.save(EventParticipant(eventId = saved.id, userId = creatorId))
        eventPushNotificationPublisher.publishCreated(saved)
        return saved
    }

    /**
     * Порождает сетку партий по стратегии турнира.
     *
     * Партии создаются сразу с составом участников и статусом PLANNED — дальше они живут
     * обычным жизненным циклом `GameEvent` (start / finish), что и позволяет турнирным
     * партиям попадать в статистику игрока.
     */
    @Transactional
    fun generateBracket(actorId: ObjectId, tournamentId: ObjectId): List<TournamentGame> {
        val tournament = requireById(tournamentId)
        eventService.requireCreatorOrClubManager(actorId, tournament)

        if (tournamentGameRepository.countByTournamentEventId(tournamentId.value) > 0) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Bracket has already been generated for this tournament."
            )
        }

        val participantIds = eventParticipantRepository.findUserIdsByEventId(tournamentId.value)
        if (participantIds.size < tournament.minParticipants) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Not enough participants: ${participantIds.size} of ${tournament.minParticipants}."
            )
        }

        val game = gameService.requireById(tournament.gameId)
        requirePairwisePlayable(game)

        val strategyType = when (tournament) {
            is SingleTournament -> tournament.bracketStrategy
            is Championship -> tournament.bracketStrategy
            // Лига не имеет фиксированной сетки: партии заводятся свободными парами.
            else -> throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Bracket generation is not applicable to ${tournament.eventType}."
            )
        }

        val pairings = bracketStrategies.resolve(strategyType)
            .generate(participantIds.map(::ObjectId))

        val created = pairings.map { pairing ->
            val gameEvent = TournamentGame().apply {
                this.title = "${tournament.title} — раунд ${pairing.round}"
                this.description = ""
                this.gameId = tournament.gameId
                this.creatorId = tournament.creatorId
                this.tournamentEventId = tournament.id
                this.round = pairing.round
                this.startsAt = tournament.startsAt
                this.minParticipants = pairing.participants.size
                this.maxParticipants = pairing.participants.size
                this.participantsCount = pairing.participants.size
            }
            val savedGame = tournamentGameRepository.save(gameEvent)
            pairing.participants.forEach { userId ->
                eventParticipantRepository.save(
                    EventParticipant(eventId = savedGame.id, userId = userId)
                )
            }
            savedGame
        }

        if (tournament is Championship) {
            championshipService.initStandings(tournament, participantIds)
        }
        return created
    }

    /**
     * Заводит партию лиги произвольной парой её участников.
     *
     * У лиги нет фиксированной сетки — партии играются свободными составами, поэтому
     * создать её может любой участник лиги, а не только организатор. Все игроки обязаны
     * состоять в лиге: иначе в её зачёт попали бы результаты посторонних.
     */
    @Transactional
    fun createLeagueGame(
        actorId: ObjectId,
        leagueId: ObjectId,
        participantIds: List<String>,
        startsAt: Instant,
        durationMinutes: Int?
    ): TournamentGame {
        val tournament = requireById(leagueId)
        val league = tournament as? League
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Only leagues accept free-form games; use /bracket for ${tournament.eventType}."
            )
        if (league.status == EventStatus.CANCELLED || league.status == EventStatus.FINISHED) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "League is not running (status=${league.status})."
            )
        }

        val members = eventParticipantRepository.findUserIdsByEventId(leagueId.value).toSet()
        if (actorId.value !in members) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Only league participants can schedule league games."
            )
        }
        val distinct = participantIds.distinct()
        if (distinct.size != participantIds.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate participants.")
        }
        val outsiders = distinct - members
        if (outsiders.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Users are not league participants: ${outsiders.joinToString()}."
            )
        }

        val game = gameService.requireById(league.gameId)
        if (distinct.size < game.minPlayers || distinct.size > game.maxPlayers) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "'${game.name}' requires ${game.minPlayers}..${game.maxPlayers} players, " +
                    "got ${distinct.size}."
            )
        }

        val gameEvent = TournamentGame().apply {
            this.title = "${league.title} — партия"
            this.description = ""
            this.gameId = league.gameId
            this.creatorId = actorId
            this.tournamentEventId = league.id
            this.round = LEAGUE_ROUND
            this.startsAt = startsAt
            this.durationMinutes = durationMinutes
            this.minParticipants = distinct.size
            this.maxParticipants = distinct.size
            this.participantsCount = distinct.size
        }
        val saved = tournamentGameRepository.save(gameEvent)
        distinct.forEach {
            eventParticipantRepository.save(
                EventParticipant(eventId = saved.id, userId = ObjectId(it))
            )
        }
        return saved
    }

    fun leagueStandings(tournamentId: ObjectId): List<LeagueStandingView> {
        val tournament = requireById(tournamentId)
        val league = tournament as? League
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Standings are only available for leagues."
            )
        return leagueService.standings(league)
    }

    fun championshipStandings(tournamentId: ObjectId): List<ChampionshipStandingView> {
        val tournament = requireById(tournamentId)
        val championship = tournament as? Championship
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Standings are only available for championships."
            )
        return championshipService.standings(championship)
    }

    /**
     * Круговая сетка ставит игроков попарно, поэтому имеет смысл только для игр,
     * допускающих двух участников.
     */
    private fun requirePairwisePlayable(game: Game) {
        if (game.minPlayers > PAIR_SIZE || game.maxPlayers < PAIR_SIZE) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Bracket pairs two players, but '${game.name}' requires " +
                    "${game.minPlayers}..${game.maxPlayers} players."
            )
        }
    }

    private companion object {
        const val PAIR_SIZE = 2

        /** У лиги нет раундов; все её партии относятся к одному условному раунду. */
        const val LEAGUE_ROUND = 1
    }
}
