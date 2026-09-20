package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageNotificationPresentationTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val thread = StoredMessageConversation(owner, PeerNumberNormalizer.normalize("+12025550123"))

    @Test fun keepsLatestSixUnreadIncomingInStableChronologicalOrder() {
        val rows = (0..9).map { MessageRecord.Incoming("row-$it", "+12025550123", "消息 $it", 100) }
        val result = requireNotNull(MessageNotificationPresentation.from(thread, rows.reversed() +
            rows.last().copy(id = "other", peerLabel = "+12025550124") + rows.last().copy(id = "read", isRead = true)))
        assertEquals((4..9).map { "row-$it" }, result.messages.map { it.id })
        assertEquals("+12025550123", result.peer)
        assertFalse(result.toString().contains(result.peer))
    }

    @Test fun unicodeClippingPreservesSurrogatePairsAndRealRtlText() {
        val body = "مرحبا\n" + "文".repeat(760) + "😀".repeat(12)
        val row = MessageRecord.Incoming("row", "+12025550123", body, 1)
        val clipped = requireNotNull(MessageNotificationPresentation.from(thread, listOf(row))).messages.single().preview
        assertTrue(clipped.startsWith("مرحبا\n"))
        assertTrue(clipped.length <= 769)
        assertFalse(clipped[clipped.lastIndex - 1].isHighSurrogate())
        assertNull(MessageNotificationPresentation.from(thread, listOf(row.copy(isRead = true))))
    }
}
