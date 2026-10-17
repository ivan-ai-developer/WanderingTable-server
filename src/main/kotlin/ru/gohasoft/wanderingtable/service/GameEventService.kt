package ru.gohasoft.wanderingtable.service

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import ru.gohasoft.wanderingtable.controllers.dto.SubmitResultRequest
import ru.gohasoft.wanderingtable.database.model.ObjectId
import ru.gohasoft.wanderingtable.database.model.event.EventParticipant
import ru.gohasoft.wanderingtable.database.model.event.EventStatus
import ru.gohasoft.wanderingtable.database.model.event.GameEvent
import ru.gohasoft.wanderingtable.database.model.event.RegularGame
import ru.gohasoft.wanderingtable.database.model.event.TournamentGame
import ru.gohasoft.wanderingtable.database.model.game.Game
import ru.gohasoft.wanderingtable.database.model.result.GameResult
import ru.gohasoft.wanderingtable.database.repository.EventParticipantRepository
import ru.gohasoft.wanderingtable.database.repository.EventRepository
import ru.gohasoft.wanderingtable.database.repository.GameEventRepository
import ru.gohasoft.wanderingtable.database.repository.GameResultRepository
import java.time.Instant
import kotlin.jvm.optionals.getOrNull

@Service
class GameEventService(
    private val gameEventRepository: GameEventRepository,
    private val eventParticipantRepository: EventParticipantRepository,
    private val eventRepository: EventRepository,
    private val gameResultRepository: GameResultRepository,
    private val gameResultFactory: GameResultFactory,
    private val tournamentProgressService: TournamentProgressService,
    private val eventService: EventService,
    private val gameService: GameService,
    private val eventPushNotificationPublisher: EventPushNotificationPublisher,
) {

    fun requireById(eventId: ObjectId): GameEvent =
        gameEventRepository.findById(eventId.value).getOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Game event not found.")

    /** История партий игрока: и клубные, и турнирные. */
    fun history(userId: ObjectId, pageable: Pageable): Page<GameEvent> =
        gameEventRepository.findByParticipant(userId.value, pageable)

    /**
     * Создание заявки на партию. Доступно базовой роли `PLAYER` — это и есть «запрос оппонента».
     * Создатель сразу становится участником, иначе событие начинало бы жизнь с нулём игроков.
     */
    @PreAuthorize("hasRole('PLAYER')")
    @Transactional
    fun createRegularGame(
        creatorId: ObjectId,
        gameId: ObjectId,
        title: String,
        description: String,
        startsAt: Instant,
        durationMinutes: Int?,
        minParticipants: Int,
        maxParticipants: Int
    ): RegularGame {
        val game = gameService.requireById(gameId)
        validateParticipantBounds(game, minParticipants, maxParticipants)

        val regularGame = RegularGame().apply {
            this.title = title.trim()
            this.description = description.trim()
            this.gameId = gameId
            this.creatorId = creatorId
            this.startsAt = startsAt
            this.durationMinutes = durationMinutes
            this.minParticipants = minParticipants
            this.maxParticipants = maxParticipants
            this.participantsCount = 1
        }
        val saved = gameEventRepository.save(regularGame)
        eventParticipantRepository.save(
            EventParticipant(eventId = saved.id, userId = creatorId)
        )
        eventPushNotificationPublisher.publishCreated(saved)
        return saved as RegularGame
    }

    /**
     * Завершение партии с подведением итогов.
     *
     * Валидация идёт до перевода статуса, чтобы отклонённый запрос не оставил партию
     * завершённой без результатов. Сам перевод — атомарный условный `UPDATE`, поэтому из
     * двух одновременных завершений проходит ровно одно, и статистика не удваивается.
     */
    @Transactional
    fun finish(
        actorId: ObjectId,
        eventId: ObjectId,
        submitted: List<SubmitResultRequest>
    ): Pair<GameEvent, List<GameResult>> {
        val event = requireById(eventId)
        eventService.requireCreatorOrClubManager(actorId, event)

        if (event.status != EventStatus.IN_PROGRESS) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Only an event in progress can be finished (status=${event.status})."
            )
        }

        val game = gameService.requireById(event.gameId)
        val participants = eventParticipantRepository.findUserIdsByEventId(eventId.value).toSet()
        validateCoverage(submitted, participants)

        val results = submitted.map { gameResultFactory.create(game.resultType, eventId, it) }

        if (eventRepository.tryTransition(
                eventId.value,
                listOf(EventStatus.IN_PROGRESS.name),
                EventStatus.FINISHED.name
            ) == 0
        ) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Event has already been finished.")
        }

        // Исход вычисляется здесь и только здесь: каждый результат смотрит на полный набор
        // результатов партии, поэтому кэш outcome не может разойтись с исходными данными.
        results.forEach { it.outcome = it.resolveOutcome(results) }
        val savedResults = gameResultRepository.saveAll(results)

        val finished = requireById(eventId)
        if (finished is TournamentGame) {
            tournamentProgressService.onTournamentGameFinished(finished, savedResults)
        }
        return finished to savedResults
    }

    fun resultsOf(eventId: ObjectId): List<GameResult> =
        gameResultRepository.findByGameEventId(eventId.value)

    /**
     * Результаты должны покрывать ровно состав участников: без этой проверки можно было бы
     * записать победу игроку, который в партии не участвовал, или «забыть» проигравшего.
     */
    private fun validateCoverage(submitted: List<SubmitResultRequest>, participants: Set<String>) {
        val submittedIds = submitted.map { it.userId }
        val duplicates = submittedIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Duplicate results for users: ${duplicates.joinToString()}."
            )
        }
        val submittedSet = submittedIds.toSet()
        val notParticipants = submittedSet - participants
        if (notParticipants.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Users are not participants of this event: ${notParticipants.joinToString()}."
            )
        }
        val missing = participants - submittedSet
        if (missing.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Missing results for participants: ${missing.joinToString()}."
            )
        }
    }

    /**
     * Границы участников должны укладываться в правила самой настолки: нельзя объявить
     * партию в «Каркассон» на 12 человек, если игра рассчитана на 2–5.
     */
    private fun validateParticipantBounds(game: Game, minParticipants: Int, maxParticipants: Int) {
        if (maxParticipants < minParticipants) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "maxParticipants must be greater than or equal to minParticipants."
            )
        }
        if (minParticipants < game.minPlayers || maxParticipants > game.maxPlayers) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Participant bounds must be within the game limits " +
                    "(${game.minPlayers}..${game.maxPlayers})."
            )
        }
    }
}
