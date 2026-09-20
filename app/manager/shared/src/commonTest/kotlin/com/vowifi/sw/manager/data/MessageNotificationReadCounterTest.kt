package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageNotificationReadCounterTest {
    @Test fun visibleHistoryWithoutPostedNotificationDoesNotCreateOneOnRead() {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val rows = listOf("one", "two").map { MessageRecord.Incoming(it, "+15550000001", "synthetic", 1) }
        val posts = mutableListOf<Int>()
        val policy = MessageNotificationPolicy({ _, count, _ -> posts += count }, {})
        policy.visible(messageConversationHandle(owner, "15550000001"))
        policy.snapshot(owner, rows, "two")
        policy.read(owner, setOf("one"))
        policy.visible(null)
        policy.snapshot(owner, listOf(rows.last()))
        assertEquals(emptyList(), posts)
    }

    @Test fun dismissedThreadDoesNotReappearOnReadOrSnapshotButNewMtAlerts() {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val rows = listOf("one", "two").map { MessageRecord.Incoming(it, "+15550000001", "synthetic", 1) }
        val posts = mutableListOf<Pair<Int, Boolean>>()
        var active = true
        val policy = MessageNotificationPolicy({ _, count, alert -> posts += count to alert }, {}, { active })
        policy.snapshot(owner, rows, "two")
        active = false
        policy.read(owner, setOf("one"))
        policy.snapshot(owner, listOf(rows.last()))
        assertEquals(listOf(2 to true), posts)
        policy.snapshot(owner, listOf(rows.last(), rows.first().copy(id = "three")), "three")
        assertEquals(listOf(2 to true, 2 to true), posts)
    }

    @Test fun visibleSnapshotReadMaintainsCountAndPreservesFutureArrival() {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val rows = listOf("one", "two").map { MessageRecord.Incoming(it, "+15550000001", "synthetic", 1) }
        val posts = mutableListOf<Pair<Int, Boolean>>()
        val policy = MessageNotificationPolicy({ _, count, alert -> posts += count to alert }, {})
        policy.snapshot(owner, rows, "two")
        policy.visible(messageConversationHandle(owner, "15550000001"))
        policy.snapshot(owner, listOf(rows.first().copy(isRead = true), rows.last()))
        assertEquals(listOf(2 to true, 1 to false), posts)
        policy.snapshot(owner, listOf(rows.last(), rows.first().copy(id = "future")), "future")
        policy.read(owner, setOf("two"))
        assertEquals(1 to false, posts.last())
    }

    @Test fun visiblePartialReadUpdatesExistingNotificationThenZeroCancels() {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val rows = listOf("one", "two").map { MessageRecord.Incoming(it, "+15550000001", "synthetic", 1) }
        val posts = mutableListOf<Pair<Int, Boolean>>()
        var cancels = 0
        val policy = MessageNotificationPolicy({ _, count, alert -> posts += count to alert }, { cancels++ })
        policy.snapshot(owner, rows, "two")
        policy.visible(messageConversationHandle(owner, "15550000001"))
        policy.read(owner, setOf("one"))
        assertEquals(listOf(2 to true, 1 to false), posts)
        policy.read(owner, setOf("two"))
        assertEquals(1, cancels)
    }
}
