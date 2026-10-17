package ru.gohasoft.wanderingtable.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import ru.gohasoft.wanderingtable.database.model.ObjectId
import ru.gohasoft.wanderingtable.database.model.UserDevice
import ru.gohasoft.wanderingtable.database.repository.UserDeviceRepository

class EventCreatedPushNotificationTest {
    private val devices = mockk<UserDeviceRepository>()
    private val sender = mockk<PushNotificationSender>(relaxed = true)
    private val listener = EventCreatedPushNotificationListener(devices, sender)

    @Test
    fun `sends event type and id to every non creator device`() {
        every { devices.findByUserIdNot("creator-id") } returns listOf(
            UserDevice(userId = ObjectId("player-a"), fcmToken = "token-a"),
            UserDevice(userId = ObjectId("player-b"), fcmToken = "token-b"),
        )

        listener.onEventCreated(
            EventCreatedPushNotification(
                eventId = "event-123",
                eventType = "REGULAR_GAME",
                title = "Saturday game",
                creatorId = "creator-id",
            )
        )

        verify {
            devices.findByUserIdNot("creator-id")
            sender.sendToDevices(
                listOf("token-a", "token-b"),
                match {
                    it["eventType"] == "REGULAR_GAME" &&
                        it["eventId"] == "event-123" &&
                        it["type"] == "GENERAL"
                },
            )
        }
    }

    @Test
    fun `does not call firebase when no recipient devices are registered`() {
        every { devices.findByUserIdNot("creator-id") } returns emptyList()

        listener.onEventCreated(
            EventCreatedPushNotification("event-123", "LEAGUE", "Club league", "creator-id")
        )

        verify(exactly = 0) { sender.sendToDevices(any(), any()) }
    }
}
