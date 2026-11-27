@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class CallSessionAudioTest {
    @Test fun failedFirstStartDoesNotRecordLastOwnerOrPlaySpuriousEndTone() = runTest(UnconfinedTestDispatcher()) {
        val physical = FakeCallAudioController(startHandler = { error("synthetic audio start failed") })
        val root = CallSessionAudio(physical)
        val scope = root.child()
        val session = CallAudioSession("a", CALL_TOKEN, 1, FakeCallTransport())
        try {
            assertFailsWith<IllegalStateException> { scope.start(session) }
            scope.callEnded(session)
            assertEquals(
                0,
                physical.endTones.size,
                "a failed first start() must not leave last owner eligible to play an end tone",
            )
        } finally {
            scope.retire()
            root.close()
        }
    }

    @Test fun failedUpgradeStartDuringCallEndedDoesNotStopActiveEndTone() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val delegate = object : CallAudioController {
            override val events: SharedFlow<CallAudioEvent> = MutableSharedFlow()
            var active: CallAudioSession? = null
            var endTonePlaying = false
            var unconditionalStops = 0
            override suspend fun start(session: CallAudioSession) {
                if (session.mode == CallAudioMode.Conversation) {
                    gate.await()
                    error("synthetic conversation start failed")
                }
                endTonePlaying = false
                active = session
            }
            override fun stop() {
                unconditionalStops++
                endTonePlaying = false
                active = null
            }
            override fun stopIf(session: CallAudioSession) {
                if (active?.sameSession(session) == true) active = null
            }
            override fun callEnded(session: CallAudioSession) {
                active = null
                endTonePlaying = true
            }
        }
        val root = CallSessionAudio(delegate)
        val scope = root.child()
        val wire = FakeCallTransport()
        val progress = CallAudioSession("a", CALL_TOKEN, 1, wire, CallAudioMode.Progress, 1, true)
        val conversation = CallAudioSession("a", CALL_TOKEN, 1, wire, CallAudioMode.Conversation)
        try {
            scope.start(progress)
            assertSame(progress, delegate.active)

            val upgrade = launch {
                runCatching { scope.start(conversation) }
            }
            scope.callEnded(conversation)
            assertTrue(delegate.endTonePlaying, "callEnded during upgrade must start end tone")

            gate.complete(Unit)
            upgrade.join()

            assertTrue(
                delegate.endTonePlaying,
                "failed upgrade start() must use stopIf(session) rather than unconditional stop() that kills active endTone",
            )
            assertEquals(0, delegate.unconditionalStops)
        } finally {
            gate.complete(Unit)
            scope.retire()
            root.close()
        }
    }

    @Test fun idleAndRetiredScopesCannotStopSuccessorWithCollidingTokenGeneration() = runTest(UnconfinedTestDispatcher()) {
        val physical = FakeCallAudioController()
        val root = CallSessionAudio(physical)
        val first = root.child()
        val second = root.child()
        val old = CallAudioSession("a", CALL_TOKEN, 1, FakeCallTransport())
        first.start(old)
        second.stop()
        assertEquals(CALL_TOKEN, physical.activeToken)
        first.retire()
        val successor = old.copy(transport = FakeCallTransport())
        second.start(successor)
        val stops = physical.stopCalls
        first.stopIf(old)
        second.stopIf(old)
        second.stopIf(successor.copy(iccid = "b"))
        assertEquals(stops, physical.stopCalls)
        assertEquals(CALL_TOKEN, physical.activeToken)
        second.stopIf(successor)
        assertNull(physical.activeToken)
        root.close()
    }

    @Test fun retiredSuspendedStartIsCleanedBeforeSuccessorStarts() = runTest(UnconfinedTestDispatcher()) {
        val physical = FakeCallAudioController()
        val gate = physical.enqueueStartGate()
        val root = CallSessionAudio(physical)
        val first = root.child()
        val next = root.child()
        val old = CallAudioSession("a", CALL_TOKEN, 1, FakeCallTransport())
        val starting = launch { first.start(old) }
        first.retire()
        val successor = launch { next.start(old.copy(transport = FakeCallTransport())) }
        assertEquals(1, physical.starts.size)
        gate.complete(Unit)
        starting.join(); successor.join()
        assertEquals(2, physical.starts.size)
        assertEquals(CALL_TOKEN, physical.activeToken)
        val stops = physical.stopCalls
        first.stopIf(old)
        assertEquals(stops, physical.stopCalls)
        root.close()
    }
}
