@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class CallSessionAudioTest {
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
