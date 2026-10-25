package com.vowifi.sw.manager.data

import kotlinx.serialization.json.*
import kotlin.test.*

class NotificationDiagnosticTest {
    private fun sample() = DiagnosticRecord(DiagnosticEvent(DiagnosticComponent.APP, DiagnosticLevel.INFO,
        DiagnosticName.OBSERVED, DiagnosticStage.NOTIFICATION, DiagnosticResult.STARTED, attempt = 7,
        notification = NotificationDiagnostic(NotificationDiagnosticOwner.MICROPHONE, NotificationDiagnosticAction.PROMOTE,
            101, NotificationDiagnosticChannel.ONGOING, true, 2, true, true, true, false, false)),
        "f47ac10b-58cc-4372-a567-0e02b2c3d479", 123, 1_792_000_000_000, 42)

    @Test fun fixedMetadataRoundTripsWithoutChangingTheTwoKiBWireBoundary() {
        val record = sample()
        assertTrue(record.bytes().size < 2048)
        assertEquals(record, DiagnosticCodec.parse(record.bytes().decodeToString()))
        assertTrue(DiagnosticLabels.technicalSummary(record.event).contains("前台通知"))
        assertFalse(DiagnosticLabels.summary(record.event).contains("网络"))
    }

    @Test fun nestedUnknownFieldsFreeTextAndOversizedNumbersAreRejected() {
        val record = Json.parseToJsonElement(sample().bytes().decodeToString()).jsonObject
        val metadata = record.getValue("notification").jsonObject
        for (key in listOf("owner", "action", "id", "channel", "importance", "small_icon", "title", "intent", "exception")) {
            val malicious = JsonObject(metadata + (key to JsonPrimitive("private-notification-body")))
            assertNull(DiagnosticCodec.parse(JsonObject(record + ("notification" to malicious)).toString()), key)
        }
        assertNull(DiagnosticCodec.parse(JsonObject(record + ("notification" to JsonNull)).toString()))
        assertNull(DiagnosticCodec.parse(JsonObject(record + ("notification" to
            JsonObject(metadata + ("id" to JsonPrimitive(Long.MAX_VALUE))))).toString()))
        assertFalse(sample().event.copy(notification = sample().event.notification!!.copy(id = 1)).valid())
        assertFalse(sample().event.copy(component = DiagnosticComponent.TUNNEL).valid())
    }

    @Test fun notificationPriorityUsesTheSameGlobalRateAndQueueBudgets() {
        val buffer = DiagnosticBuffer(sample().run, 123)
        repeat(30) { buffer.offer(sample().event.copy(attempt = it), 1000, 0) }
        assertEquals(20, buffer.stats().queued)
        assertEquals(10, buffer.stats().dropped)
        repeat(20) { assertTrue(buffer.poll()!!.size <= 2048) }
        assertNull(buffer.poll())
    }

    @Test fun onlyCoordinatorCanRecordTheIndependentPlainNotificationKey() {
        val old = sample()
        val metadata = old.event.notification!!.copy(owner = NotificationDiagnosticOwner.COORDINATOR,
            action = NotificationDiagnosticAction.NOTIFY, id = 102, callStyle = false)
        val plain = old.copy(event = old.event.copy(notification = metadata))
        assertEquals(plain, DiagnosticCodec.parse(plain.bytes().decodeToString()))
        for (owner in NotificationDiagnosticOwner.entries.filter { it != NotificationDiagnosticOwner.COORDINATOR }) {
            val invalid = plain.copy(event = plain.event.copy(notification = metadata.copy(owner = owner)))
            assertFalse(invalid.event.valid())
            assertNull(DiagnosticCodec.parse(invalid.bytes().decodeToString()))
        }
        assertFalse(plain.event.copy(notification = metadata.copy(id = 103)).valid())
    }
}
