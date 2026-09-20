@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.ICCID_A
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.MO_ID
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_NUMBER
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_PEER
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_TEXT
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.running
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.sendError
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.sendOk
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.sourceOf
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class EngineMessageSourcePersistenceSendTest {
    @Test
    fun readBackFailureBeforeRequestSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeMessageRepository()
        val transport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66))
        val source = sourceOf(listOf(running(ICCID_A)), transport, repository)
        repository.failNextList = true
        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)

        assertEquals(1, repository.insertQueuedCalls)
        assertTrue(transport.requests.isEmpty())
        val ready = assertIs<MessageUiState.Inbox>(source.state.value)
        assertEquals(STORAGE_NOTICE, ready.attachmentNotice)
        assertFalse(ready.composerEnabled)
        assertFalse(ready.attachmentNotice.orEmpty().contains("Exception"))
        source.sendSms(SYNTH_PEER, "synth-late")
        assertTrue(transport.requests.isEmpty())

        source.refresh()
        val restored = inspectInboxThreads(source)
        val interrupted = assertIs<MessageRecord.Outgoing>(restored.records.single())
        assertEquals(OutgoingMessageStatus.UNKNOWN, interrupted.status)
        assertTrue(restored.composerEnabled)
        assertEquals(null, restored.attachmentNotice)
        source.close()
    }

    @Test
    fun repositoryFailureAfterTransportKeepsQueuedRowAndDisablesComposerUntilRefresh() =
        runTest(UnconfinedTestDispatcher()) {
            val repository = FakeMessageRepository().also { it.failNextBind = true }
            val transport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66))
            val source = sourceOf(listOf(running(ICCID_A)), transport, repository)
            source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)

            assertEquals(1, transport.requests.size)
            assertEquals(1, repository.bindAcceptedCalls)
            assertEquals(0, repository.markUnknownCalls)
            val ready = inspectInboxThreads(source)
            val outgoing = assertIs<MessageRecord.Outgoing>(ready.records.single())
            assertEquals(OutgoingMessageStatus.QUEUED, outgoing.status)
            assertEquals(STORAGE_SYNC_NOTICE, ready.attachmentNotice)
            assertFalse(ready.composerEnabled)
            assertFalse(ready.attachmentNotice.orEmpty().contains("Exception"))
            source.sendSms(SYNTH_PEER, "synth-late")
            assertEquals(1, transport.requests.size)

            source.refresh()
            val restored = inspectInboxThreads(source)
            val interrupted = assertIs<MessageRecord.Outgoing>(restored.records.single())
            assertEquals(OutgoingMessageStatus.UNKNOWN, interrupted.status)
            assertTrue(restored.composerEnabled)
            assertEquals(null, restored.attachmentNotice)
            source.close()

            val listRepo = FakeMessageRepository().also { it.failOnListCall = 3 }
            val listTransport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66))
            val listSource = sourceOf(listOf(running(ICCID_A)), listTransport, listRepo)
            listSource.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
            assertEquals(1, listTransport.requests.size)
            val listed = assertIs<MessageUiState.Inbox>(listSource.state.value)
            assertEquals(STORAGE_SYNC_NOTICE, listed.attachmentNotice)
            assertFalse(listed.composerEnabled)
            listSource.refresh()
            val listRestored = inspectInboxThreads(listSource)
            val listOutgoing = assertIs<MessageRecord.Outgoing>(listRestored.records.single())
            assertEquals(OutgoingMessageStatus.ACCEPTED, listOutgoing.status)
            assertTrue(listRestored.composerEnabled)
            assertEquals(null, listRestored.attachmentNotice)
            listSource.close()
        }

    @Test
    fun queueInsertHappensBeforeTransportAndFailureSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeMessageRepository()
        val gate = CompletableDeferred<Unit>()
        val transport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66), requestGate = gate)
        val source = sourceOf(listOf(running(ICCID_A)), transport, repository)

        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        assertEquals(1, repository.insertQueuedCalls)
        assertEquals(1, transport.requests.size)
        val queued = assertIs<MessageRecord.Outgoing>(
            inspectInboxThreads(source).records.single(),
        )
        assertEquals(OutgoingMessageStatus.QUEUED, queued.status)
        gate.complete(Unit)
        source.close()

        val failingRepo = FakeMessageRepository().also { it.failNextInsert = true }
        val failingTransport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66))
        val failing = sourceOf(listOf(running(ICCID_A)), failingTransport, failingRepo)
        failing.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        assertTrue(failingTransport.requests.isEmpty())
        val noticed = assertIs<MessageUiState.Inbox>(failing.state.value)
        assertEquals(STORAGE_NOTICE, noticed.attachmentNotice)
        assertTrue(noticed.composerEnabled)
        assertFalse(noticed.attachmentNotice.orEmpty().contains("Exception"))
        failing.close()
    }

    @Test
    fun twoXxBindsAcceptedAndProtocolErrorFailsWithoutRetry() = runTest(UnconfinedTestDispatcher()) {
        val accepted = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 200, 66))
        val source = sourceOf(listOf(running(ICCID_A)), accepted)
        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        val outgoing = assertIs<MessageRecord.Outgoing>(
            inspectInboxThreads(source).records.single(),
        )
        assertEquals(OutgoingMessageStatus.ACCEPTED, outgoing.status)
        assertEquals("msg-1", outgoing.id)
        source.close()

        val protocol = FakeEngineMessageTransport(sendResult = sendError(4001, "sms failed"))
        val failedSource = sourceOf(listOf(running(ICCID_A)), protocol)
        failedSource.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        val failed = assertIs<MessageRecord.Outgoing>(
            inspectInboxThreads(failedSource).records.single(),
        )
        assertEquals(OutgoingMessageStatus.FAILED, failed.status)
        assertEquals(1, protocol.requests.size)
        failedSource.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        assertEquals(2, protocol.requests.size)
        failedSource.close()
    }

    @Test
    fun timeoutAndIoMarkUnknownAndNeverAutoRetry() = runTest(UnconfinedTestDispatcher()) {
        val timeout = FakeEngineMessageTransport(timeoutRequests = true)
        val timeoutSource = sourceOf(listOf(running(ICCID_A)), timeout)
        timeoutSource.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        testScheduler.advanceUntilIdle()
        val unknown = assertIs<MessageRecord.Outgoing>(
            inspectInboxThreads(timeoutSource).records.single(),
        )
        assertEquals(OutgoingMessageStatus.UNKNOWN, unknown.status)
        assertEquals(1, timeout.requests.size)
        timeoutSource.close()

        val io = FakeEngineMessageTransport(requestError = IllegalStateException("socket closed"))
        val ioSource = sourceOf(listOf(running(ICCID_A)), io)
        ioSource.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        val ioUnknown = assertIs<MessageRecord.Outgoing>(
            inspectInboxThreads(ioSource).records.single(),
        )
        assertEquals(OutgoingMessageStatus.UNKNOWN, ioUnknown.status)
        assertEquals(1, io.requests.size)
        ioSource.close()
    }

    @Test
    fun smsSentAfterBindMatchesEngineSmsId() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66))
        val source = sourceOf(listOf(running(ICCID_A)), transport)
        source.sendSms(SYNTH_NUMBER, SYNTH_TEXT)
        transport.emitSent(MO_ID, SmsEngineStatus.DELIVERED)
        val outgoing = assertIs<MessageRecord.Outgoing>(
            inspectInboxThreads(source).records.single(),
        )
        assertEquals("msg-1", outgoing.id)
        assertEquals(MO_ID, outgoing.engineSmsId)
        assertEquals(OutgoingMessageStatus.DELIVERY_REPORTED, outgoing.status)
        source.close()
    }
}
