package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineOperationGateTest {
    @Test
    fun ignoresConcurrentOperationInsteadOfQueueingIt() {
        val gate = EngineOperationGate()

        assertTrue(gate.tryEnter())
        assertFalse(gate.tryEnter())

        gate.release()

        assertTrue(gate.tryEnter())
        gate.release()
    }
}
