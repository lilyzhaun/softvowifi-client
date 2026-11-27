@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.ICCID_A
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.MO_ID
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.NOW_MS
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_NUMBER
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_PEER
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_TEXT
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.running
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.sendOk
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class EngineMessageSenderCleanupTest {
    private class GatedMessageRepository(
        private val delegate: FakeMessageRepository = FakeMessageRepository(),
    ) : MessageRepository by delegate {
        var insertGate: CompletableDeferred<Unit>? = null
        var listGate: CompletableDeferred<Unit>? = null
        var bindGate: CompletableDeferred<Unit>? = null
        var markUnknownEntered: CompletableDeferred<Unit>? = null
        var markUnknownGate: CompletableDeferred<Unit>? = null

        override suspend fun insertQueued(
            owner: SimOwnerId,
            messageId: String,
            number: String,
            text: String,
            createdAtMillis: Long,
        ) {
            insertGate?.await()
            delegate.insertQueued(owner, messageId, number, text, createdAtMillis)
        }

        override suspend fun listByOwner(owner: SimOwnerId): List<MessageRecord> {
            listGate?.await()
            return delegate.listByOwner(owner)
        }

        override suspend fun bindAccepted(
            owner: SimOwnerId,
            messageId: String,
            smsId: String,
            updatedAtMillis: Long,
        ) {
            bindGate?.await()
            delegate.bindAccepted(owner, messageId, smsId, updatedAtMillis)
        }

        override suspend fun markUnknown(ownerId: SimOwnerId, messageId: String, nowMs: Long) {
            markUnknownEntered?.complete(Unit)
            markUnknownGate?.await()
            delegate.markUnknown(ownerId, messageId, nowMs)
        }
    }

    private fun liveSession(
        scope: CoroutineScope,
        transport: FakeEngineMessageTransport,
        repository: MessageRepository,
    ): Pair<EngineMessageSession, MessageLiveSession> {
        val owner = ownerFor(ICCID_A)!!
        val session = EngineMessageSession(
            runtime = EngineMessageRuntime(
                listInstances = { listOf(running(ICCID_A)) },
                transportFor = { transport },
                clock = { NOW_MS },
                activeIccids = { listOf(ICCID_A) },
            ),
            repository = repository,
            ownerIdFor = ::ownerFor,
            nextMessageId = sequentialIds(),
            scope = scope,
        )
        val line = MessageLiveSession(owner, ICCID_A, pid = 7, transport = transport).apply {
            connected = true
            registered = true
            storageHealthy = true
            attachmentNotice = null
        }
        session.sessions = mapOf(owner to line)
        return session to line
    }

    @Test
    fun cancellationDuringInsertQueuedReleasesSendBusyAndClearsUnpersistedSubmitted() =
        runTest(UnconfinedTestDispatcher()) {
            val childScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val gate = CompletableDeferred<Unit>()
                val repository = GatedMessageRepository().also { it.insertGate = gate }
                val transport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66))
                val (session, line) = liveSession(childScope, transport, repository)

                val sendJob = childScope.launch { sendMessage(session, line, SYNTH_NUMBER, SYNTH_TEXT) }
                assertTrue(line.sendBusy)

                sendJob.cancelAndJoin()

                assertFalse(line.sendBusy, "cancelled insertQueued left sendBusy stuck true")
                assertNull(line.submitted, "cancelled insertQueued retained unpersisted submitted payload")
                assertTrue(transport.requests.isEmpty())

                repository.insertGate = null
                sendMessage(session, line, SYNTH_PEER, "synth-after-insert-cancel")
                assertEquals(1, transport.requests.size)
                assertFalse(line.sendBusy)
                session.selection.close()
            } finally {
                childScope.cancel()
            }
        }

    @Test
    fun cancellationDuringListAfterInsertReleasesSendBusyAndMarksStorageUnhealthy() =
        runTest(UnconfinedTestDispatcher()) {
            val childScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val gate = CompletableDeferred<Unit>()
                val repository = GatedMessageRepository().also { it.listGate = gate }
                val transport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66))
                val (session, line) = liveSession(childScope, transport, repository)

                val sendJob = childScope.launch { sendMessage(session, line, SYNTH_NUMBER, SYNTH_TEXT) }
                assertTrue(line.sendBusy)

                sendJob.cancelAndJoin()

                assertFalse(line.sendBusy, "cancelled listByOwner left sendBusy stuck true")
                assertFalse(line.storageHealthy, "cancelled listByOwner left storageHealthy true with unsynced records")
                assertTrue(transport.requests.isEmpty())
                session.selection.close()
            } finally {
                childScope.cancel()
            }
        }

    @Test
    fun cancellationDuringTransportRequestReleasesSendBusyMarksUnknownAndAllowsNextSend() =
        runTest(UnconfinedTestDispatcher()) {
            val childScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val repository = FakeMessageRepository()
                val gate = CompletableDeferred<Unit>()
                val transport = FakeEngineMessageTransport(
                    sendResults = ArrayDeque(listOf(sendOk(MO_ID, 202, 66), sendOk("mo-synth-2", 202, 67))),
                    requestGate = gate,
                )
                val (session, line) = liveSession(childScope, transport, repository)

                val sendJob = childScope.launch { sendMessage(session, line, SYNTH_NUMBER, SYNTH_TEXT) }
                assertEquals(1, transport.requests.size)
                assertTrue(line.sendBusy)
                line.pendingSent = listOf(SmsWireEvent.Sent(MO_ID, SmsEngineStatus.ACCEPTED, 66))

                sendJob.cancelAndJoin()

                assertFalse(line.sendBusy, "cancelled send left sendBusy stuck true")
                assertTrue(line.pendingSent.isEmpty(), "cancelled send leaked pendingSent")
                assertEquals(OutgoingMessageStatus.UNKNOWN, line.snapshot().sendAttempt?.status)
                val first = assertIs<MessageRecord.Outgoing>(repository.listByOwner(line.owner).single())
                assertEquals(OutgoingMessageStatus.UNKNOWN, first.status)

                gate.complete(Unit)
                sendMessage(session, line, SYNTH_PEER, "synth-after-cancel")
                assertEquals(2, transport.requests.size)
                assertFalse(line.sendBusy)
                assertEquals(OutgoingMessageStatus.ACCEPTED, line.snapshot().sendAttempt?.status)
                session.selection.close()
            } finally {
                childScope.cancel()
            }
        }

    @Test
    fun cancellationDuringOutcomePersistenceReleasesSendBusyAndClearsPendingSent() =
        runTest(UnconfinedTestDispatcher()) {
            val childScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val gate = CompletableDeferred<Unit>()
                val repository = GatedMessageRepository().also { it.bindGate = gate }
                val transport = FakeEngineMessageTransport(sendResult = sendOk(MO_ID, 202, 66))
                val (session, line) = liveSession(childScope, transport, repository)

                val sendJob = childScope.launch { sendMessage(session, line, SYNTH_NUMBER, SYNTH_TEXT) }
                assertEquals(1, transport.requests.size)
                assertTrue(line.sendBusy)
                line.pendingSent = listOf(SmsWireEvent.Sent(MO_ID, SmsEngineStatus.ACCEPTED, 66))

                sendJob.cancelAndJoin()

                assertFalse(line.sendBusy, "cancelled persistSendOutcome left sendBusy stuck true")
                assertTrue(line.pendingSent.isEmpty(), "cancelled persistSendOutcome leaked pendingSent")
                assertEquals(OutgoingMessageStatus.UNKNOWN, line.snapshot().sendAttempt?.status)
                session.selection.close()
            } finally {
                childScope.cancel()
            }
        }

    @Test
    fun cancellationDuringUnknownOutcomeAfterTransportErrorReleasesSendBusyAndAllowsNextSend() =
        runTest(UnconfinedTestDispatcher()) {
            val childScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val entered = CompletableDeferred<Unit>()
                val gate = CompletableDeferred<Unit>()
                val repository = GatedMessageRepository().also {
                    it.markUnknownEntered = entered
                    it.markUnknownGate = gate
                }
                val failingTransport = FakeEngineMessageTransport(requestError = IllegalStateException("synth-io"))
                val (session, line) = liveSession(childScope, failingTransport, repository)

                val sendJob = childScope.launch { sendMessage(session, line, SYNTH_NUMBER, SYNTH_TEXT) }
                entered.await()
                assertTrue(line.sendBusy)
                line.pendingSent = listOf(SmsWireEvent.Sent(MO_ID, SmsEngineStatus.ACCEPTED, 66))

                sendJob.cancel()
                gate.complete(Unit)
                sendJob.join()

                assertFalse(line.sendBusy, "cancelled persistUnknownOutcome(error) left sendBusy stuck true")
                assertTrue(line.pendingSent.isEmpty(), "cancelled persistUnknownOutcome(error) leaked pendingSent")
                assertEquals(OutgoingMessageStatus.UNKNOWN, line.snapshot().sendAttempt?.status)
                val first = assertIs<MessageRecord.Outgoing>(repository.listByOwner(line.owner).single())
                assertEquals(OutgoingMessageStatus.UNKNOWN, first.status)

                repository.markUnknownEntered = null
                repository.markUnknownGate = null
                sendMessage(session, line, SYNTH_PEER, "synth-after-error-cancel")
                assertEquals(2, failingTransport.requests.size)
                assertFalse(line.sendBusy)
                session.selection.close()
            } finally {
                childScope.cancel()
            }
        }

    @Test
    fun cancellationDuringUnknownOutcomeAfterTimeoutReleasesSendBusyAndAllowsNextSend() =
        runTest(UnconfinedTestDispatcher()) {
            val childScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val entered = CompletableDeferred<Unit>()
                val gate = CompletableDeferred<Unit>()
                val repository = GatedMessageRepository().also {
                    it.markUnknownEntered = entered
                    it.markUnknownGate = gate
                }
                val timeoutTransport = FakeEngineMessageTransport(timeoutRequests = true)
                val (session, line) = liveSession(childScope, timeoutTransport, repository)

                val sendJob = childScope.launch { sendMessage(session, line, SYNTH_NUMBER, SYNTH_TEXT) }
                advanceTimeBy(2)
                runCurrent()
                entered.await()
                assertTrue(line.sendBusy)
                line.pendingSent = listOf(SmsWireEvent.Sent(MO_ID, SmsEngineStatus.ACCEPTED, 66))

                sendJob.cancel()
                gate.complete(Unit)
                sendJob.join()

                assertFalse(line.sendBusy, "cancelled persistUnknownOutcome(timeout) left sendBusy stuck true")
                assertTrue(line.pendingSent.isEmpty(), "cancelled persistUnknownOutcome(timeout) leaked pendingSent")
                assertEquals(OutgoingMessageStatus.UNKNOWN, line.snapshot().sendAttempt?.status)
                val first = assertIs<MessageRecord.Outgoing>(repository.listByOwner(line.owner).single())
                assertEquals(OutgoingMessageStatus.UNKNOWN, first.status)

                repository.markUnknownEntered = null
                repository.markUnknownGate = null
                val nextJob = childScope.launch { sendMessage(session, line, SYNTH_PEER, "synth-after-timeout-cancel") }
                advanceTimeBy(2)
                runCurrent()
                nextJob.join()
                assertEquals(2, timeoutTransport.requests.size)
                assertFalse(line.sendBusy)
                session.selection.close()
            } finally {
                childScope.cancel()
            }
        }
}
