@file:OptIn(ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class EngineCallTimeoutTest {
    @Test
    fun dialTimeoutReturnsNetworkWithoutThrowing() = runTest {
        val transport = FakeCallTransport(timeoutMethods = setOf("dial"))
        val result = EngineCallRequestExecutor.execute(
            PlannedCallRequest.Dial(transport, CALL_NUMBER, OWNER),
        )
        assertEquals(CallFailureMessages.NETWORK, result.operationMessage)
        assertEquals(null, result.resultToken)
    }

    @Test
    fun controlTimeoutsReturnNetworkWithoutThrowing() = runTest {
        CallControlAction.entries.forEach { action ->
            val transport = FakeCallTransport(timeoutMethods = setOf(action.method))
            val result = EngineCallRequestExecutor.execute(
                PlannedCallRequest.Control(transport, action, CALL_TOKEN, OWNER),
            )
            assertEquals(CallFailureMessages.NETWORK, result.operationMessage, action.name)
            assertEquals(null, result.resultToken)
        }
    }

    @Test
    fun sourceDialTimeoutGoesIdleWithNetwork() = runTest(UnconfinedTestDispatcher()) {
        val transport = FakeCallTransport(timeoutMethods = setOf("dial"))
        val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
        source.dial(CALL_NUMBER)
        testScheduler.advanceUntilIdle()
        val idle = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Idle, idle.phase)
        assertFalse(idle.busy)
        assertEquals(CallFailureMessages.NETWORK, idle.operationMessage)
        assertCallPrivacy(idle, CALL_NUMBER, CALL_TOKEN)
        source.close()
    }

    @Test
    fun parentCancellationPropagatesFromExecutor() = runTest {
        val transport = FakeCallTransport()
        transport.cancelRequest = true
        val error = assertFailsWith<CancellationException> {
            EngineCallRequestExecutor.execute(
                PlannedCallRequest.Dial(transport, CALL_NUMBER, OWNER),
            )
        }
        assertTrue(error !is TimeoutCancellationException)
        assertEquals("parent cancelled", error.message)
    }

    @Test
    fun outerTimeoutCancellationPropagatesThroughTransportBoundary() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(1) {
                withEngineRequestTimeout(50) {
                    delay(100)
                }
            }
        }
    }
}

private val OWNER = CallRequestOwner(generation = 1, operationId = 1)
