package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.SmsEngineStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SmsStatusMachineTest {
    @Test
    fun acceptedAndPendingAdvanceMonotonically() {
        assertEquals(
            SmsEngineStatus.ACCEPTED,
            SmsStatusMachine.reduce(current = null, incoming = SmsEngineStatus.ACCEPTED),
        )
        assertEquals(
            SmsEngineStatus.PENDING,
            SmsStatusMachine.reduce(SmsEngineStatus.ACCEPTED, SmsEngineStatus.PENDING),
        )
        assertEquals(
            SmsEngineStatus.PENDING,
            SmsStatusMachine.reduce(SmsEngineStatus.PENDING, SmsEngineStatus.ACCEPTED),
        )
    }

    @Test
    fun firstTerminalStatusIsAbsorbingAndDuplicatesAreIdempotent() {
        terminalStatuses.forEach { terminal ->
            SmsEngineStatus.entries.forEach { incoming ->
                assertEquals(terminal, SmsStatusMachine.reduce(terminal, incoming))
            }
        }
        SmsEngineStatus.entries.forEach { status ->
            assertEquals(status, SmsStatusMachine.reduce(status, status))
        }
    }

    @Test
    fun nonTerminalStatusCanSkipAheadToEveryTerminalStatus() {
        terminalStatuses.forEach { terminal ->
            assertEquals(terminal, SmsStatusMachine.reduce(null, terminal))
            assertEquals(terminal, SmsStatusMachine.reduce(SmsEngineStatus.ACCEPTED, terminal))
            assertEquals(terminal, SmsStatusMachine.reduce(SmsEngineStatus.PENDING, terminal))
        }
    }
}

private val terminalStatuses = listOf(
    SmsEngineStatus.DELIVERED,
    SmsEngineStatus.FORWARDED,
    SmsEngineStatus.REPLACED,
    SmsEngineStatus.FAILED,
)
