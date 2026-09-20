package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SmsInnerBehaviorTest {
    @Test fun searchOnlyLoadedBodiesAndOnlyTheirDates() {
        val first = MessageThreadItem.DateSeparator(MessageDateSpec("first", "first"))
        val second = MessageThreadItem.DateSeparator(MessageDateSpec("second", "second"))
        val matching = MessageThreadItem.IncomingBubble("a", "Mixed NEEDLE", MessageTimestampSpec(1))
        val other = MessageThreadItem.IncomingBubble("b", "other", MessageTimestampSpec(2))
        val loaded = listOf(first, matching, second, other)
        assertSame(loaded, filterLoadedThread(loaded, ""))
        assertEquals(listOf(first, matching), filterLoadedThread(loaded, "needle"))
        assertEquals(emptyList(), filterLoadedThread(loaded, "unloaded"))
        assertEquals(listOf(first, matching, second, other.copy(body = "needle")),
            filterLoadedThread(loaded.dropLast(1) + other.copy(body = "needle"), "needle"))
    }
    @Test fun reeditRequiresExplicitFailedOutgoing() {
        assertFalse(canReeditMessage(MessageRecord.Incoming("a", "+12025550123", "synthetic", 1)))
        OutgoingMessageStatus.entries.forEach { status ->
            assertEquals(status == OutgoingMessageStatus.FAILED,
                canReeditMessage(MessageRecord.Outgoing("a", "+12025550123", "synthetic", 1, status)))
        }
    }
    @Test fun recipientAndBodyKeepExistingBoundsAndMultiline() {
        assertEquals("+12025550123", boundedRecipientDraft("+1 202 555 0123"))
        assertFalse(isValidMessageRecipient("++1"))
        assertTrue(isValidMessageRecipient("+12025550123"))
        assertEquals(21, boundedRecipientDraft("1".repeat(100)).length)
        assertEquals(1000, boundedDraftBody("A".repeat(1200)).length)
        assertEquals("one\ntwo", boundedDraftBody("one\ntwo"))
    }
}
