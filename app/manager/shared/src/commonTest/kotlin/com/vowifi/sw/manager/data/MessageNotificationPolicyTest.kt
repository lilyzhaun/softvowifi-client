package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class MessageNotificationPolicyTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val other = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    private fun incoming(id: String, peer: String = "+15550000001", read: Boolean = false) =
        MessageRecord.Incoming(id, peer, "synthetic", 100, isRead = read)

    @Test fun freshInsertAlertsButHistoryAndDuplicateDoNot() {
        val posts = mutableListOf<Boolean>()
        val policy = MessageNotificationPolicy({ _, _, alert -> posts += alert }, {})
        val rows = listOf(incoming("one"))
        policy.snapshot(owner, rows)
        policy.snapshot(owner, rows, "one")
        policy.snapshot(owner, rows)
        assertEquals(listOf(true), posts)
    }

    @Test fun onlyVisibleOwnerThreadSuppressesAndPauseClearsVisibility() {
        val posts = mutableListOf<Boolean>()
        val policy = MessageNotificationPolicy({ _, _, alert -> posts += alert }, {})
        val handle = messageConversationHandle(owner, "15550000001")
        policy.visible(handle)
        policy.snapshot(owner, listOf(incoming("one")), "one")
        policy.snapshot(other, listOf(incoming("two")), "two")
        policy.visible(null)
        policy.snapshot(owner, listOf(incoming("one"), incoming("three")), "three")
        assertEquals(listOf(true, true), posts)
    }

    @Test fun readCancelsOnlyMatchingThreadAndDoesNotRepostDismissedHistory() {
        val cancelled = mutableListOf<String>()
        val posts = mutableListOf<Boolean>()
        val policy = MessageNotificationPolicy({ _, _, alert -> posts += alert }, { cancelled += it.value })
        val first = incoming("one")
        val second = incoming("two", "+15550000002")
        policy.snapshot(owner, listOf(first), "one")
        policy.snapshot(owner, listOf(first, second), "two")
        policy.snapshot(owner, listOf(first.copy(isRead = true), second))
        policy.snapshot(owner, listOf(first.copy(isRead = true), second))
        assertEquals(listOf(messageConversationHandle(owner, PeerNumberNormalizer.normalize(first.peerLabel)).value), cancelled)
        assertEquals(listOf(true, true), posts)
    }

    @Test fun permissionRecoveryIsSilentAndKeepsUnread() {
        val posts = mutableListOf<Boolean>()
        val policy = MessageNotificationPolicy({ _, _, alert -> posts += alert }, {})
        policy.snapshot(owner, listOf(incoming("one")))
        policy.reconcile()
        assertEquals(listOf(false), posts)
    }

    @Test fun handlesAreStableOwnerBoundAndNotPhoneText() {
        val handle = messageConversationHandle(owner, "+15550000001")
        assertEquals(handle, messageConversationHandle(owner, "+15550000001"))
        assertNotEquals(handle, messageConversationHandle(other, "+15550000001"))
        assertFalse(handle.value.contains("15550000001"))
    }

    @Test fun activityDisposalDoesNotCloseProcessMessageSource() {
        assertFalse(closesOnActivityDispose(ProcessRuntimeHandleKind.MESSAGE_SOURCE))
    }

    @Test fun completedReadAfterOwnerSwitchCancelsOnlyOriginalOwner() {
        val cancelled = mutableListOf<String>()
        val policy = MessageNotificationPolicy({ _, _, _ -> }, { cancelled += it.value })
        policy.snapshot(owner, listOf(incoming("one")), "one")
        policy.snapshot(other, listOf(incoming("two")), "two")
        policy.read(owner, setOf("one"))
        assertEquals(listOf(messageConversationHandle(owner, "15550000001").value), cancelled)
    }
}
