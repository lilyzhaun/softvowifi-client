package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*

/** 直接串接实际threadHistoryChange与Ready.window；不手造最终live资格或修改reason/ids。 */
class MessageCoalescedArrivalCauseTest {
    @Test fun liveInsertSurvivesAcceptedContentAndUnchangedReadbackKeepsTheSameChange() {
        val f = Fixture(); val old = incoming("old"); val queued = outgoing("live-mo")
        assertTrue(f.publish(listOf(old)).liveInsertedIds.isEmpty())
        val live = f.publish(listOf(old, queued), HistoryChangeReason.LIVE_INSERT, setOf(queued.id))
        assertEquals(HistoryChangeReason.LIVE_INSERT, live.reason); assertEquals(setOf(queued.id), live.ids)
        val accepted = queued.copy(status = OutgoingMessageStatus.ACCEPTED, engineSmsId = "synthetic-mo-wire")
        val content = f.publish(listOf(old, accepted))
        assertEquals(HistoryChangeReason.CONTENT, content.reason); assertEquals(setOf(queued.id), content.ids)
        assertEquals(setOf(queued.id), content.liveInsertedIds)
        assertSame(live.scope, content.scope); assertTrue(content.version > live.version)
        assertSame(content, f.publish(listOf(old, accepted)), "无records变化仍返回原change引用")
        val mt = incoming("live-mt")
        val next = f.publish(listOf(old, accepted, mt), HistoryChangeReason.LIVE_INSERT, setOf(mt.id))
        assertEquals(setOf(queued.id, mt.id), next.liveInsertedIds, "同scope内多次已持久插入有界累积")
        assertEquals(setOf(mt.id), next.ids, "原本次ids语义不扩大")
    }

    @Test fun pageMergeAndItsReadContentCarryOnlyEarlierLiveIdsNeverHistoricalIds() {
        val f = Fixture(); val old = incoming("old"); val live = incoming("live"); val page = incoming("older-page")
        f.publish(listOf(old))
        f.publish(listOf(old, live), HistoryChangeReason.LIVE_INSERT, setOf(live.id))
        val merged = f.publish(listOf(page, old, live), append = true)
        assertEquals(HistoryChangeReason.PAGE_MERGE, merged.reason); assertEquals(setOf(page.id), merged.ids)
        assertEquals(setOf(live.id), merged.liveInsertedIds)
        val read = f.publish(listOf(page.copy(isRead = true), old, live))
        assertEquals(HistoryChangeReason.CONTENT, read.reason); assertEquals(setOf(page.id), read.ids)
        assertEquals(setOf(live.id), read.liveInsertedIds, "PAGE先于CONTENT合并不能把历史页冒充实时消息")
        val noLive = Fixture(); noLive.publish(listOf(old))
        noLive.publish(listOf(page, old), append = true)
        assertTrue(noLive.publish(listOf(page.copy(isRead = true), old)).liveInsertedIds.isEmpty())
    }

    @Test fun replaceAndOwnerScopeClearLiveCauseAndDeletionRemovesOnlyMissingIds() {
        val f = Fixture(); val old = incoming("old"); val first = incoming("live-first"); val second = incoming("live-second")
        f.publish(listOf(old))
        f.publish(listOf(old, first), HistoryChangeReason.LIVE_INSERT, setOf(first.id))
        f.publish(listOf(old, first, second), HistoryChangeReason.LIVE_INSERT, setOf(second.id))
        val removed = f.publish(listOf(old, second), HistoryChangeReason.USER_DELETE, setOf(first.id))
        assertEquals(HistoryChangeReason.USER_DELETE, removed.reason); assertEquals(setOf(first.id), removed.ids)
        assertEquals(setOf(second.id), removed.liveInsertedIds)
        val replacement = f.publish(listOf(old, second), selectedScope = HistoryListScope())
        assertEquals(HistoryChangeReason.REPLACE, replacement.reason); assertTrue(replacement.liveInsertedIds.isEmpty())
        val other = StoredMessageConversation(assertNotNull(SimOwnerId.fromHmacSha256(ByteArray(32) { 2 })), "1555")
        val switched = f.publish(listOf(incoming("other-history")), selected = other, selectedScope = HistoryListScope())
        assertEquals(HistoryChangeReason.REPLACE, switched.reason); assertTrue(switched.liveInsertedIds.isEmpty())
        assertEquals(other.owner, assertNotNull(f.previous).ownerId)
    }

    private class Fixture {
        private val identity = StoredMessageConversation(requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43))), "1555")
        private val scope = HistoryListScope()
        private var version = 0L
        var previous: MessageUiState.Ready? = null
        fun publish(records: List<MessageRecord>, reason: HistoryChangeReason? = null, ids: Set<String> = emptySet(),
            append: Boolean = false, selected: StoredMessageConversation = identity, selectedScope: HistoryListScope = scope): HistoryListChange<String> {
            val mutation = reason?.let { MessageHistoryMutation(selected, it, ids) }
            val change = assertNotNull(threadHistoryChange(previous, records, selected, selectedScope, ++version, append, mutation))
            previous = MessageUiState.Ready(records, MessageOrigin.ENGINE, ownerId = selected.owner,
                window = MessageThreadWindow(selected, older = null, change = change))
            return change
        }
    }
    private fun incoming(id: String) = MessageRecord.Incoming(id, "1555", "合成正文", 1L)
    private fun outgoing(id: String) = MessageRecord.Outgoing(id, "1555", "合成正文", 2L, OutgoingMessageStatus.QUEUED)
}
