@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineProtocolFrame
import com.vowifi.sw.manager.model.EngineRuntimeStatus
import com.vowifi.sw.manager.model.MessageUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class EngineMessageAttachmentDetachIsolationTest {

    private class TrackingTransport : EngineMessageTransport {
        override val events = MutableSharedFlow<EngineProtocolFrame.Event>(extraBufferCapacity = 16)
        override val messageDisconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var retireCount = 0

        override suspend fun connect() = Unit
        override fun retireMessages() {
            retireCount++
        }

        override suspend fun request(
            method: String,
            params: JsonObject,
            timeoutMillis: Long,
        ): EngineProtocolFrame.Response = EngineProtocolFrame.Response(
            1,
            buildJsonObject {
                put("tunnel", "CONNECTED")
                put("reconnects", 0)
                put("ims", "REGISTERED")
                put("call", "IDLE")
                put("transport", "UDP")
                put("pcscf", JsonArray(emptyList()))
            },
            null,
        )
    }

    private class FaultingUnknownRepository(
        private val delegate: FakeMessageRepository = FakeMessageRepository(),
        private val failMarkUnknownFor: Set<SimOwnerId> = emptySet(),
        private val markUnknownGate: CompletableDeferred<Unit>? = null,
    ) : MessageRepository by delegate {
        val markUnknownEntered = CompletableDeferred<Unit>()

        override suspend fun markUnknown(owner: SimOwnerId, messageId: String, updatedAtMillis: Long) {
            markUnknownEntered.complete(Unit)
            markUnknownGate?.await()
            if (owner in failMarkUnknownFor) {
                throw MessageStorageException()
            }
            delegate.markUnknown(owner, messageId, updatedAtMillis)
        }
    }

    private fun running(iccid: String, pid: Long) = EngineInstanceState(
        iccid,
        EngineLifecycle.RUNNING,
        pid = pid,
        runtime = EngineRuntimeStatus("CONNECTED", reconnects = 0, ims = "REGISTERED", call = "IDLE", transport = "UDP", pcscf = emptyList()),
    )

    @Test
    fun detachStorageFailureOnFirstLineStillRemovesItAndDetachesSecondLine() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val childScope = CoroutineScope(dispatcher + SupervisorJob())
        val iccid1 = "8986001"
        val iccid2 = "8986002"
        val owner1 = ownerFor(iccid1)!!
        val owner2 = ownerFor(iccid2)!!
        val wire1 = TrackingTransport()
        val wire2 = TrackingTransport()
        var currentInstances = listOf(running(iccid1, pid = 10), running(iccid2, pid = 20))
        val repository = FaultingUnknownRepository(failMarkUnknownFor = setOf(owner1))
        val session = EngineMessageSession(
            runtime = EngineMessageRuntime(
                listInstances = { currentInstances },
                transportFor = { if (it == iccid1) wire1 else wire2 },
                clock = { 100L },
                activeIccids = { listOf(iccid1, iccid2) },
            ),
            repository = repository,
            ownerIdFor = ::ownerFor,
            nextMessageId = sequentialIds(),
            scope = childScope,
        )
        try {
            session.selection.refreshInbox()
            runCurrent()
            refreshMessageAttachment(session)
            runCurrent()

            val line1 = session.sessions[owner1]!!
            val line2 = session.sessions[owner2]!!
            line1.sendBusy = true
            line1.submitted = PlannedMessageSend("m1", line1, "155501", "synth")
            assertTrue(line2.attached)
            assertEquals(0, wire2.retireCount)

            currentInstances = emptyList()
            refreshMessageAttachment(session)
            runCurrent()

            assertNull(session.sessions[owner1], "failed detach left already-detached first line in session.sessions")
            assertNull(session.sessions[owner2], "second stopped line was skipped after first line detach threw")
            assertFalse(line2.attached, "second stopped line stayed attached")
            assertEquals(1, wire1.retireCount)
            assertEquals(1, wire2.retireCount, "second stopped line transport was never retired")
            assertTrue(line2.eventJob?.isCancelled == true, "second stopped line eventJob was never cancelled")
            assertTrue(line2.disconnectJob?.isCancelled == true, "second stopped line disconnectJob was never cancelled")
            val failure = assertIs<MessageUiState.Failure>(session.fallback)
            assertEquals(STORAGE_SYNC_NOTICE, failure.message)
        } finally {
            session.closed = true
            session.selection.close()
            childScope.cancel()
            runCurrent()
        }
    }

    @Test
    fun detachStorageFailureOnReplacedLineStillAttachesHealthySecondTarget() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val childScope = CoroutineScope(dispatcher + SupervisorJob())
        val iccid1 = "8986001"
        val iccid2 = "8986002"
        val owner1 = ownerFor(iccid1)!!
        val owner2 = ownerFor(iccid2)!!
        val wire1Old = TrackingTransport()
        val wire1New = TrackingTransport()
        val wire2 = TrackingTransport()
        var currentInstances = listOf(running(iccid1, pid = 10))
        var wire1Calls = 0
        val repository = FaultingUnknownRepository(failMarkUnknownFor = setOf(owner1))
        val session = EngineMessageSession(
            runtime = EngineMessageRuntime(
                listInstances = { currentInstances },
                transportFor = {
                    if (it == iccid1) {
                        if (wire1Calls++ == 0) wire1Old else wire1New
                    } else wire2
                },
                clock = { 200L },
                activeIccids = { listOf(iccid1, iccid2) },
            ),
            repository = repository,
            ownerIdFor = ::ownerFor,
            nextMessageId = sequentialIds(),
            scope = childScope,
        )
        try {
            session.selection.refreshInbox()
            runCurrent()
            refreshMessageAttachment(session)
            runCurrent()

            val oldLine1 = session.sessions[owner1]!!
            oldLine1.sendBusy = true
            oldLine1.submitted = PlannedMessageSend("m1", oldLine1, "155501", "synth")

            currentInstances = listOf(running(iccid1, pid = 11), running(iccid2, pid = 20))
            refreshMessageAttachment(session)
            runCurrent()

            val replaced1 = assertNotNull(session.sessions[owner1], "restarted line 1 was not reattached")
            assertEquals(11L, replaced1.pid, "stale pid-10 line remained in session.sessions")
            assertTrue(replaced1.attached)
            val attached2 = assertNotNull(session.sessions[owner2], "healthy second line was never attached after first detach error")
            assertEquals(20L, attached2.pid)
            assertTrue(attached2.attached)
            assertEquals(1, wire1Old.retireCount)
        } finally {
            session.closed = true
            session.selection.close()
            childScope.cancel()
            runCurrent()
        }
    }

    @Test
    fun cancellationDuringDetachStillRemovesAlreadyDetachedLineFromSessions() = runTest(UnconfinedTestDispatcher()) {
        val childScope = CoroutineScope(coroutineContext + SupervisorJob())
        val iccid1 = "8986001"
        val owner1 = ownerFor(iccid1)!!
        val wire1 = TrackingTransport()
        var currentInstances = listOf(running(iccid1, pid = 10))
        val gate = CompletableDeferred<Unit>()
        val repository = FaultingUnknownRepository(markUnknownGate = gate)
        val session = EngineMessageSession(
            runtime = EngineMessageRuntime(
                listInstances = { currentInstances },
                transportFor = { wire1 },
                clock = { 300L },
                activeIccids = { listOf(iccid1) },
            ),
            repository = repository,
            ownerIdFor = ::ownerFor,
            nextMessageId = sequentialIds(),
            scope = childScope,
        )
        try {
            session.selection.refreshInbox()
            refreshMessageAttachment(session)
            val line1 = session.sessions[owner1]!!
            line1.sendBusy = true
            line1.submitted = PlannedMessageSend("m1", line1, "155501", "synth")

            currentInstances = emptyList()
            val refreshJob = childScope.launch { refreshMessageAttachment(session) }
            repository.markUnknownEntered.await()
            refreshJob.cancelAndJoin()

            assertFalse(line1.attached)
            assertEquals(1, wire1.retireCount)
            assertNull(
                session.sessions[owner1],
                "cancelled detach left already-detached line in session.sessions",
            )
        } finally {
            session.closed = true
            session.selection.close()
            childScope.cancel()
        }
    }
}
