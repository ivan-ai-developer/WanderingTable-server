package ru.gohasoft.wanderingtable.service

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.MulticastMessage
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import ru.gohasoft.wanderingtable.database.model.event.Event
import ru.gohasoft.wanderingtable.database.repository.UserDeviceRepository

/** Published only for events that are open for new participants. */
data class EventCreatedPushNotification(
    val eventId: String,
    val eventType: String,
    val title: String,
    val creatorId: String,
)

fun interface PushNotificationSender {
    fun sendToDevices(tokens: List<String>, data: Map<String, String>)
}

@Service
class EventPushNotificationPublisher(private val publisher: ApplicationEventPublisher) {
    fun publishCreated(event: Event) {
        publisher.publishEvent(
            EventCreatedPushNotification(
                eventId = event.id.value,
                eventType = event.eventType,
                title = event.title,
                creatorId = event.creatorId.value,
            )
        )
    }
}

@Component
class EventCreatedPushNotificationListener(
    private val userDeviceRepository: UserDeviceRepository,
    private val sender: PushNotificationSender,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onEventCreated(event: EventCreatedPushNotification) {
        // Device tokens are credentials. Never include them in logs or the message payload.
        val tokens = userDeviceRepository.findByUserIdNot(event.creatorId)
            .map { it.fcmToken }
            .distinct()
        if (tokens.isEmpty()) return

        val data = mapOf(
            "type" to "GENERAL",
            "eventType" to event.eventType,
            "eventId" to event.eventId,
            "title_en" to "New club event",
            "body_en" to "${event.title} is open for sign-ups. Join now!",
            "title_ru" to "Новое событие клуба",
            "body_ru" to "На событие «${event.title}» открыта запись. Присоединяйтесь!",
        )
        runCatching { sender.sendToDevices(tokens, data) }
            .onFailure { logger.warn("Could not send event-created push for event {}", event.eventId, it) }
    }
}

/** Firebase Admin is enabled explicitly in deployed environments with Application Default Credentials. */
class FirebasePushNotificationSender(private val firebaseApp: FirebaseApp) : PushNotificationSender {
    override fun sendToDevices(tokens: List<String>, data: Map<String, String>) {
        tokens.chunked(MAX_TOKENS_PER_REQUEST).forEach { chunk ->
            val response = FirebaseMessaging.getInstance(firebaseApp).sendEachForMulticast(
                MulticastMessage.builder().addAllTokens(chunk).putAllData(data).build()
            )
            if (response.failureCount > 0) {
                logger.warn("FCM event notification failed for {} of {} device(s)", response.failureCount, chunk.size)
            }
        }
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    private companion object {
        const val MAX_TOKENS_PER_REQUEST = 500
    }
}

@Configuration
class FirebasePushConfiguration {
    @Bean
    @ConditionalOnProperty(name = ["app.firebase.push.enabled"], havingValue = "true")
    fun firebaseApp(): FirebaseApp = FirebaseApp.getApps().firstOrNull() ?: FirebaseApp.initializeApp(
        FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.getApplicationDefault())
            .build()
    )

    @Bean
    @ConditionalOnProperty(name = ["app.firebase.push.enabled"], havingValue = "true")
    fun firebasePushNotificationSender(firebaseApp: FirebaseApp): PushNotificationSender =
        FirebasePushNotificationSender(firebaseApp)

    @Bean
    @ConditionalOnMissingBean(PushNotificationSender::class)
    fun noOpPushNotificationSender(): PushNotificationSender = PushNotificationSender { _, _ -> }
}
