package com.vowifi.sw

import com.vowifi.sw.EngineCapabilitiesHostFixture.Companion.OTHER_KEY
import com.vowifi.sw.EngineCapabilitiesHostFixture.Reply
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class EngineCapabilitiesStatusHostTest {
    @Test
    fun actualStatusPathConsumesMetadataAfterSuccessfulStatus() = runBlocking {
        // Given
        EngineCapabilitiesHostFixture().use { backend ->
            // When
            val instance = backend.ready(backend.source())
            // Then
            assertEquals("REGISTERED", instance.runtime?.ims)
            val capabilities = (instance.clientCapabilities as ClientCapabilitiesResult.Ready).capabilities
            assertEquals("", capabilities.configMetadata.last().value)
            assertEquals(MetadataSource.Identity, capabilities.configMetadata.last().source)
            assertEquals(listOf("hello", "getStatus", "getClientCapabilities"), backend.methods.toList())
            assertEquals(Json.parseToJsonElement("""{"schema":1,"required":["config.metadata.v1"]}"""), backend.params)
            assertEquals(listOf("list"), backend.commands.toList())
        }
    }

    @Test
    fun olderCoreKeepsSuccessfulStatusWithAccurateUnavailableResult() = runBlocking {
        // Given
        EngineCapabilitiesHostFixture().use { backend ->
            backend.reply = Reply.Old
            // When
            val instance = backend.ready(backend.source())
            // Then
            assertEquals(EngineLifecycle.RUNNING, instance.lifecycle)
            assertEquals("REGISTERED", instance.runtime?.ims)
            assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported), instance.clientCapabilities)
            assertNull(instance.message)
        }
    }

    @Test
    fun silentCapabilityRpcDoesNotTurnStatusIntoEngineFailure() = runBlocking {
        // Given
        EngineCapabilitiesHostFixture().use { backend ->
            backend.reply = Reply.Silent
            // When
            val instance = backend.ready(backend.source())
            // Then
            assertEquals("REGISTERED", instance.runtime?.ims)
            assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Timeout), instance.clientCapabilities)
            assertNull(instance.message)
            assertFalse(backend.sockets.single().closed)
        }
    }

    @Test
    fun refreshDropsOldMetadataAfterCapabilityFailure() = runBlocking {
        // Given
        EngineCapabilitiesHostFixture().use { backend ->
            val source = backend.source()
            val original = backend.ready(source)
            assertTrue(original.clientCapabilities is ClientCapabilitiesResult.Ready)
            backend.reply = Reply.Old
            // When
            source.refresh()
            val changed = backend.ready(source, requireNotNull(original.runtime).reconnects)
            // Then
            assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported), changed.clientCapabilities)
            assertNull(changed.message)
            assertEquals("REGISTERED", changed.runtime?.ims)
        }
    }

    @Test
    fun refreshReplacesMetadataWithNewInstanceSnapshot() = runBlocking {
        // Given
        EngineCapabilitiesHostFixture().use { backend ->
            val source = backend.source()
            val original = backend.ready(source)
            backend.key = OTHER_KEY
            // When
            source.refresh()
            val changed = backend.ready(source, requireNotNull(original.runtime).reconnects)
            // Then
            assertEquals(OTHER_KEY, (changed.clientCapabilities as ClientCapabilitiesResult.Ready).capabilities.instanceKey)
            assertNotEquals(original.clientCapabilities, changed.clientCapabilities)
        }
    }

    @Test
    fun offlineAndMissingInstancesNeverConnectOrProvision() = runBlocking {
        // Given
        EngineCapabilitiesHostFixture().use { backend ->
            backend.running = false
            // When
            val source = backend.source()
            val ready = withTimeout(8_000) { source.state.first { it is EngineControlUiState.Ready } } as EngineControlUiState.Ready
            // Then
            assertEquals(listOf(EngineLifecycle.STOPPED, EngineLifecycle.CONFIG_MISSING), ready.instances.map { it.lifecycle })
            assertTrue(ready.instances.all { it.clientCapabilities == null })
            assertTrue(backend.methods.isEmpty())
            assertTrue(backend.sockets.isEmpty())
            assertEquals(listOf("list"), backend.commands.toList())
        }
    }

    @Test
    fun closingOneSourceDoesNotCloseAnotherSourcesSharedPool() = runBlocking {
        // Given
        EngineCapabilitiesHostFixture().use { backend ->
            val first = backend.source()
            backend.ready(first)
            val second = backend.source()
            val original = backend.ready(second)
            // When
            first.close()
            backend.key = OTHER_KEY
            second.refresh()
            val changed = backend.ready(second, requireNotNull(original.runtime).reconnects)
            // Then
            assertEquals(OTHER_KEY, (changed.clientCapabilities as ClientCapabilitiesResult.Ready).capabilities.instanceKey)
            assertEquals(1, backend.sockets.size)
            assertFalse(backend.sockets.single().closed)
        }
    }
}
