@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.ICCID_A
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.ICCID_B
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.MO_ID
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.MT_ID
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.NOW_MS
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_NUMBER
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_TEXT
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.running
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.sendOk
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.sourceOf
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class EngineMessageSourcePersistenceReadTest {
    @Test
    fun receivedInsertsUnreadWhileDuplicateAndDeliveryReportStayDistinct() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeMessageRepository()
        val transport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66))
        val source = sourceOf(listOf(running(ICCID_A)), transport, repository)
        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        transport.emitSent(MO_ID, SmsEngineStatus.DELIVERED)
        val afterReport = inspectInboxThreads(source)
        assertEquals(1, afterReport.records.size)
        assertIs<MessageRecord.Outgoing>(afterReport.records.single())

        transport.emitReceived(MT_ID, SYNTH_NUMBER, SYNTH_TEXT, 1_755_000_000L)
        val incoming = assertIs<MessageRecord.Incoming>(
            inspectInboxThreads(source).records.last(),
        )
        assertEquals("msg-2", incoming.id)
        assertFalse(incoming.isRead)
        val request = checkNotNull(
            parseObservedIncomingRead(OWNER_WIRE_A, "155501", listOf(incoming.id)),
        )
        source.markVisibleRead(request)
        val marked = assertIs<MessageRecord.Incoming>(
            inspectInboxThreads(source).records.last(),
        )
        assertTrue(marked.isRead)
        assertEquals(0, markedUnreadCount(source.state.value))

        transport.emitReceived(MT_ID, SYNTH_NUMBER, SYNTH_TEXT, 1_755_000_000L)
        val afterDuplicate = inspectInboxThreads(source)
        assertEquals(2, afterDuplicate.records.size)
        val stillRead = assertIs<MessageRecord.Incoming>(afterDuplicate.records.last())
        assertTrue(stillRead.isRead)
        assertEquals("msg-2", stillRead.id)
        source.close()
    }

    @Test
    fun markVisibleReadUpdatesReadyWithoutRefreshAndKeepsLaterArrival() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeMessageRepository()
        val transport = FakeEngineMessageTransport()
        val source = sourceOf(listOf(running(ICCID_A)), transport, repository)
        transport.emitReceived(MT_ID, SYNTH_NUMBER, SYNTH_TEXT, 1_755_000_000L)
        val incoming = assertIs<MessageRecord.Incoming>(
            inspectInboxThreads(source).records.single(),
        )
        val snapshot = checkNotNull(parseObservedIncomingRead(OWNER_WIRE_A, "155501", listOf(incoming.id)))
        source.markVisibleRead(snapshot)
        assertEquals(0, markedUnreadCount(source.state.value))
        assertTrue(assertIs<MessageRecord.Incoming>(inspectInboxThreads(source).records.single()).isRead)

        transport.emitReceived("mt-later", SYNTH_NUMBER, "synth-later", 1_755_000_001L)
        val after = inspectInboxThreads(source)
        assertEquals(1, markedUnreadCount(after.inbox))
        assertTrue(assertIs<MessageRecord.Incoming>(after.records.first { it.id == incoming.id }).isRead)
        assertFalse(assertIs<MessageRecord.Incoming>(after.records.last()).isRead)
        source.markVisibleRead(snapshot)
        assertEquals(1, markedUnreadCount(source.state.value))
        source.close()
    }

    @Test
    fun markVisibleReadDoesNotMutateOtherOwnerAfterSimSwitch() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeMessageRepository()
        val transport = FakeEngineMessageTransport()
        var instances = listOf(running(ICCID_A))
        val source = EngineMessageSource(
            runtime = EngineMessageRuntime(
                listInstances = { instances },
                transportFor = { transport },
                clock = { NOW_MS },
                activeIccids = { instances.map { it.iccid } },
            ),
            parentContext = kotlin.coroutines.coroutineContext,
            repository = repository,
            ownerIdFor = ::ownerFor,
            nextMessageId = sequentialIds(),
        )
        transport.emitReceived(MT_ID, SYNTH_NUMBER, SYNTH_TEXT, 1_755_000_000L)
        val incoming = assertIs<MessageRecord.Incoming>(
            inspectInboxThreads(source).records.single(),
        )
        val request = checkNotNull(parseObservedIncomingRead(OWNER_WIRE_A, "155501", listOf(incoming.id)))
        instances = listOf(running(ICCID_B))
        source.refresh()
        val switched = inspectInboxThreads(source)
        assertTrue(switched.records.none { it.id == incoming.id })
        source.markVisibleRead(request)
        val stillSwitched = inspectInboxThreads(source)
        assertTrue(stillSwitched.records.none { it.id == incoming.id })
        assertEquals(null, stillSwitched.attachmentNotice)
        assertTrue(assertIs<MessageRecord.Incoming>(repository.listByOwner(OWNER_A).single()).isRead)
        source.close()
    }

    @Test
    fun markVisibleReadFailureKeepsUnreadAndSetsBoundedNotice() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeMessageRepository()
        val transport = FakeEngineMessageTransport()
        val source = sourceOf(listOf(running(ICCID_A)), transport, repository)
        transport.emitReceived(MT_ID, SYNTH_NUMBER, SYNTH_TEXT, 1_755_000_000L)
        val incoming = assertIs<MessageRecord.Incoming>(
            inspectInboxThreads(source).records.single(),
        )
        repository.failNextMarkRead = true
        source.markVisibleRead(
            checkNotNull(parseObservedIncomingRead(OWNER_WIRE_A, "155501", listOf(incoming.id))),
        )
        val failed = inspectInboxThreads(source)
        assertEquals(STORAGE_SYNC_NOTICE, failed.attachmentNotice)
        assertFalse(failed.attachmentNotice.orEmpty().contains("Exception"))
        assertFalse(assertIs<MessageRecord.Incoming>(failed.records.single()).isRead)
        source.close()
    }
}

private fun markedUnreadCount(state: MessageUiState): Int {
    val inbox = assertIs<MessageUiState.Inbox>(state)
    return inbox.page.items.sumOf { it.unreadCount }.toInt()
}
