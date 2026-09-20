package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import kotlin.test.*

class MessageThreadSnapshotTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val first = StoredMessageConversation(owner, "155501")
    private val second = StoredMessageConversation(owner, "155502")
    private val rows = listOf(
        MessageRecord.Incoming("a1", first.key, "synthetic first", 1),
        MessageRecord.Incoming("a2", second.key, "synthetic unrelated", 2),
    )

    @Test fun emptyThreadSnapshotCancelsOnlyItsExplicitTarget() {
        val cancelled = mutableListOf<String>()
        val posts = mutableListOf<Pair<Int, Boolean>>()
        val policy = MessageNotificationPolicy({ _, count, alert -> posts += count to alert }, { cancelled += it.value })
        policy.snapshot(owner, rows, "a1")
        policy.snapshot(owner, rows, "a2")
        policy.threadSnapshot(first, emptyList())
        assertEquals(listOf(first.handle.value), cancelled)
        policy.reconcile()
        assertEquals(listOf(1 to true, 1 to true, 1 to false), posts)
    }

    @Test fun ownerWideSnapshotStillReplacesAllItsConversations() {
        val cancelled = mutableListOf<String>()
        val policy = MessageNotificationPolicy({ _, _, _ -> }, { cancelled += it.value })
        policy.snapshot(owner, rows, "a1")
        policy.snapshot(owner, rows, "a2")
        policy.snapshot(owner, listOf(rows.first()))
        assertEquals(listOf(second.handle.value), cancelled)
    }

    @Test fun baselineDoesNotCreateOrResurrectNotifications() {
        var active = true
        val posts = mutableListOf<Boolean>()
        val policy = MessageNotificationPolicy({ _, _, alert -> posts += alert }, {}, { active })
        policy.threadSnapshot(first, listOf(rows.first()))
        assertEquals(emptyList(), posts)
        policy.snapshot(owner, rows, "a1")
        active = false
        policy.threadSnapshot(first, listOf(rows.first(), rows.first().copy(id = "future")))
        assertEquals(listOf(true), posts)
    }
}
