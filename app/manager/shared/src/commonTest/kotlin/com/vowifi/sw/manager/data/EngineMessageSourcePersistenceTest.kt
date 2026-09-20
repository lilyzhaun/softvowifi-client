@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.ICCID_A
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.ICCID_B
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.MO_ID
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.MT_ID
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.NOW_MS
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_NUMBER
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_PEER
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_TEXT
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.running
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.sendOk
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.sourceOf
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.stopped
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class EngineMessageSourcePersistenceTest {
    @Test
    fun historySurvivesSourceReconstructionAndDetach() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeMessageRepository()
        val transport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66))
        val first = sourceOf(listOf(running(ICCID_A)), transport, repository)
        first.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        first.close()

        val reconstructed = sourceOf(listOf(running(ICCID_A)), transport, repository)
        val restored = inspectInboxThreads(reconstructed)
        val outgoing = assertIs<MessageRecord.Outgoing>(restored.records.single())
        assertEquals("msg-1", outgoing.id)
        assertEquals(MO_ID, outgoing.engineSmsId)
        assertTrue(restored.composerEnabled)

        reconstructed.refresh()
        val stillReady = inspectInboxThreads(reconstructed)
        assertEquals("msg-1", stillReady.records.single().id)
        reconstructed.close()

        val detached = sourceOf(listOf(stopped(ICCID_A)), FakeEngineMessageTransport(), repository)
        val history = inspectInboxThreads(detached)
        assertFalse(history.composerEnabled)
        assertEquals(ActiveEngineSelector.NONE_MESSAGE, history.attachmentNotice)
        assertEquals("msg-1", history.records.single().id)
        detached.sendSms(SYNTH_PEER, "synth-late")
        assertTrue(transport.requests.size == 1)
        assertFalse(repository.closed)
        detached.close()
        assertFalse(repository.closed)
    }

    @Test
    fun lastOwnerWithMultipleRunningEnginesStaysReadOnly() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeMessageRepository()
        val first = sourceOf(listOf(running(ICCID_A)), FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66)), repository)
        first.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        first.close()
        assertFalse(repository.closed)

        val transport = FakeEngineMessageTransport()
        val multiple = sourceOf(listOf(running(ICCID_A), running(ICCID_B)), transport, repository)
        val ready = inspectInboxThreads(multiple)
        assertFalse(ready.composerEnabled)
        assertEquals(ActiveEngineSelector.MULTIPLE_MESSAGE, ready.attachmentNotice)
        assertEquals("msg-1", ready.records.single().id)
        multiple.sendSms(SYNTH_PEER, "synth-late")
        assertTrue(transport.requests.isEmpty())
        multiple.close()
    }

    @Test
    fun attachClearsStaleNoticeAndIdentityFailureIsBounded() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeMessageRepository()
        var instances = listOf(stopped(ICCID_A))
        val first = sourceOf(listOf(running(ICCID_A)), FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66)), repository)
        first.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        first.close()

        val transport = FakeEngineMessageTransport()
        val source = EngineMessageSource(
            runtime = EngineMessageRuntime(
                listInstances = { instances },
                transportFor = { transport },
                clock = { NOW_MS },
            ),
            parentContext = kotlin.coroutines.coroutineContext,
            repository = repository,
            ownerIdFor = ::ownerFor,
            nextMessageId = sequentialIds(),
        )
        val history = inspectInboxThreads(source)
        assertEquals(ActiveEngineSelector.NONE_MESSAGE, history.attachmentNotice)
        assertFalse(history.composerEnabled)

        instances = listOf(running(ICCID_A))
        source.refresh()
        val attached = inspectInboxThreads(source)
        assertTrue(attached.composerEnabled)
        assertEquals(null, attached.attachmentNotice)
        assertEquals("msg-1", attached.records.single().id)
        source.close()

        val unknown = EngineMessageSource(
            runtime = EngineMessageRuntime(
                listInstances = { listOf(running(ICCID_A)) },
                transportFor = { FakeEngineMessageTransport() },
                clock = { NOW_MS },
            ),
            parentContext = kotlin.coroutines.coroutineContext,
            repository = FakeMessageRepository(),
            ownerIdFor = { null },
            nextMessageId = sequentialIds(),
        )
        val failure = assertIs<MessageUiState.Failure>(unknown.state.value)
        assertEquals(MESSAGE_IDENTITY_NOTICE, failure.message)
        unknown.close()
    }

    @Test
    fun noLastOwnerKeepsUnavailableAndMultipleEnginesStayUnsupported() = runTest(UnconfinedTestDispatcher()) {
        val empty = sourceOf(listOf(stopped(ICCID_A)), FakeEngineMessageTransport())
        assertIs<MessageUiState.Unavailable>(empty.state.value)
        empty.close()

        val transport = FakeEngineMessageTransport()
        val multiple = sourceOf(listOf(running(ICCID_A), running(ICCID_B)), transport)
        val failure = assertIs<MessageUiState.Failure>(multiple.state.value)
        assertEquals(ActiveEngineSelector.MULTIPLE_MESSAGE, failure.message)
        multiple.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        assertTrue(transport.requests.isEmpty())
        multiple.close()
    }

    @Test
    fun leftoverQueuedIsInterruptedOnAttachAndIncomingUsesGeneratedId() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeMessageRepository()
        repository.insertQueued(OWNER_A, "msg-stale", SYNTH_NUMBER, SYNTH_TEXT, NOW_MS)
        val transport = FakeEngineMessageTransport()
        val source = sourceOf(listOf(running(ICCID_A)), transport, repository)
        val leftover = assertIs<MessageRecord.Outgoing>(
            inspectInboxThreads(source).records.single(),
        )
        assertEquals("msg-stale", leftover.id)
        assertEquals(OutgoingMessageStatus.UNKNOWN, leftover.status)
        assertTrue(transport.requests.isEmpty())

        transport.emitReceived(MT_ID, SYNTH_NUMBER, SYNTH_TEXT, 1_755_000_000L)
        val ready = inspectInboxThreads(source)
        val incoming = assertIs<MessageRecord.Incoming>(ready.records.last())
        assertEquals("msg-1", incoming.id)
        assertEquals(MT_ID, incoming.engineSmsId)
        assertFalse(incoming.isRead)
        transport.emitReceived(MT_ID, SYNTH_NUMBER, SYNTH_TEXT, 1_755_000_000L)
        assertEquals(2, inspectInboxThreads(source).records.size)
        source.close()
    }

}
