package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class ClientCapabilitiesSourceTest {
    @Test
    fun queryUsesRealMethodAndRequiredMetadataParams() = runTest {
        // Given
        var called = false
        val source = EngineClientCapabilitiesSource { method, params ->
            called = true
            assertEquals("getClientCapabilities", method)
            assertEquals(Json.parseToJsonElement("""{"schema":1,"required":["config.metadata.v1"]}"""), params)
            assertIs<EngineProtocolFrame.Response>(EngineProtocolCodec.decode(PublicClientCapabilitiesTest.CANONICAL))
        }
        // When
        val result = source.query()
        // Then
        assertTrue(called)
        assertIs<ClientCapabilitiesResult.Ready>(result)
    }

    @Test
    fun oldRpcFailureDoesNotEchoServerMessage() = runTest {
        // Given
        val source = EngineClientCapabilitiesSource { _, _ -> EngineProtocolFrame.Response(1, null, EngineProtocolError(1001, "private-canary")) }
        // When
        val result = source.query()
        // Then
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported), result)
    }

    @Test
    fun rpcFailureIsTypedWithoutServerPayload() = runTest {
        // Given
        val source = EngineClientCapabilitiesSource { _, _ -> EngineProtocolFrame.Response(1, null, EngineProtocolError(500, "private-canary")) }
        // When
        val result = source.query()
        // Then
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Rpc), result)
    }

    @Test
    fun timeoutCancelsRequestAt250Milliseconds() = runTest {
        // Given
        var cancelled = false
        val source = EngineClientCapabilitiesSource { _, _ ->
            try { awaitCancellation() } finally { cancelled = true }
        }
        // When
        val result = source.query()
        // Then
        assertEquals(250, testScheduler.currentTime)
        assertTrue(cancelled)
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Timeout), result)
    }

    @Test
    fun innerRequestTimeoutIsNotMisclassifiedAsTransport() = runTest {
        // Given
        val source = EngineClientCapabilitiesSource { _, _ -> throw EngineRequestTimeoutException() }
        // When
        val result = source.query()
        // Then
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Timeout), result)
    }

    @Test
    fun parentCancellationIsNotConvertedToFailure() = runTest {
        // Given
        val cancelled = CancellationException("parent")
        val source = EngineClientCapabilitiesSource { _, _ -> throw cancelled }
        // When / Then
        assertEquals("parent", assertFailsWith<CancellationException> { source.query() }.message)
    }

    @Test
    fun outerTimeoutPropagatesRatherThanBecomingOptionalFailure() = runTest {
        // Given
        val source = EngineClientCapabilitiesSource { _, _ -> awaitCancellation() }
        // When / Then
        assertFailsWith<TimeoutCancellationException> { withTimeout(100) { source.query() } }
        assertEquals(100, testScheduler.currentTime)
    }

    @Test
    fun transportFailureDoesNotEchoRawJson() = runTest {
        // Given
        val source = EngineClientCapabilitiesSource { _, _ -> throw IllegalStateException(PublicClientCapabilitiesTest.CANONICAL) }
        // When
        val result = source.query()
        // Then
        assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Transport), result)
    }
}
