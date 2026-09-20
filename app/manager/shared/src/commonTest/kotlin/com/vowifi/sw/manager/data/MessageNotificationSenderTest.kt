package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageNotificationSenderTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private fun sender(label: String): String = requireNotNull(MessageNotificationPresentation.from(
        StoredMessageConversation(owner, PeerNumberNormalizer.normalize(label)),
        listOf(MessageRecord.Incoming("row", label, "Synthetic text", 100)),
    )).peer

    @Test fun actualInternationalNumberKeepsItsPlus() { assertEquals("+12025550123", sender("+12025550123")) }
    @Test fun alphanumericOriginatorIsNotAnInternalConversationKey() { assertEquals("ExampleBank", sender("ExampleBank")) }
    @Test fun controlsAreRemovedAndInternalUrisAreNeverDisplayed() {
        assertEquals("ExampleBank", sender("\u0001ExampleBank\u0007"))
        assertEquals("短信", sender("sip:internal@example.invalid"))
        assertEquals("短信", sender("raw:internal"))
        assertTrue(sender("文".repeat(90)).length <= 64)
    }
}
