package com.vowifi.sw

import com.vowifi.sw.EngineCapabilitiesControlFixture.Companion.A
import com.vowifi.sw.EngineCapabilitiesControlFixture.Companion.B
import com.vowifi.sw.EngineCapabilitiesControlFixture.Companion.metadata
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class EngineCapabilitiesOwnershipHostTest {
    @Test
    fun stopDuringCapabilityRefreshQueuesOnceAndPreservesOtherOwner() {
        // Given
        var gate: CompletableDeferred<Unit>? = null
        EngineCapabilitiesControlFixture { owner ->
            if (owner == A) gate?.await()
            metadata(owner)
        }.use { fixture ->
            val originalB = fixture.row(B)
            val barrier = CompletableDeferred<Unit>().also { gate = it }
            fixture.source.refresh()
            fixture.dispatcher.runCurrent()
            val pending = fixture.capabilityOwners.toList()
            assertEquals(A, pending.last())
            // When
            repeat(2) { fixture.source.stop(A) }
            fixture.dispatcher.runCurrent()
            assertTrue(fixture.commands.none { it.first == "stop" })
            barrier.complete(Unit)
            fixture.dispatcher.runCurrent()
            // Then
            assertEquals(listOf("stop" to A), fixture.commands.filter { it.first != "list" })
            assertEquals(listOf(A to "stop"), fixture.requests.filter { it.second == "stop" })
            assertEquals(EngineLifecycle.STOPPED, fixture.row(A).lifecycle)
            assertNull(fixture.row(A).clientCapabilities)
            assertEquals(originalB, fixture.row(B))
            assertEquals(listOf(A), fixture.removed)
        }
    }

    @Test
    fun startDuringCapabilityRefreshQueuesOnceWithoutBlockingOtherOwner() {
        // Given
        var gate: CompletableDeferred<Unit>? = null
        EngineCapabilitiesControlFixture(firstRunning = false) { owner ->
            if (owner == B) gate?.await()
            metadata(owner)
        }.use { fixture ->
            val originalB = fixture.row(B)
            val barrier = CompletableDeferred<Unit>().also { gate = it }
            fixture.source.refresh()
            fixture.dispatcher.runCurrent()
            // When
            repeat(2) { fixture.source.start(A) }
            fixture.dispatcher.runCurrent()
            barrier.complete(Unit)
            fixture.dispatcher.runCurrent()
            // Then
            assertEquals(listOf("start" to A), fixture.commands.filter { it.first != "list" })
            assertEquals(EngineLifecycle.RUNNING, fixture.row(A).lifecycle)
            assertEquals(metadata(A), fixture.row(A).clientCapabilities)
            assertEquals(originalB, fixture.row(B))
        }
    }

    @Test
    fun lateAReplyStaysOnAAndCannotReplaceBMetadata() {
        // Given
        var gate: CompletableDeferred<Unit>? = null
        var resultA = metadata(A)
        EngineCapabilitiesControlFixture { owner ->
            if (owner == A) { gate?.await(); resultA } else metadata(B)
        }.use { fixture ->
            val originalA = fixture.row(A).clientCapabilities
            val originalB = fixture.row(B)
            val barrier = CompletableDeferred<Unit>().also { gate = it }
            fixture.source.refresh()
            fixture.dispatcher.runCurrent()
            // When
            resultA = ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Timeout)
            barrier.complete(Unit)
            fixture.dispatcher.runCurrent()
            // Then
            assertEquals(resultA, fixture.row(A).clientCapabilities)
            assertNotEquals(originalA, fixture.row(A).clientCapabilities)
            assertEquals("REGISTERED", fixture.row(A).runtime?.ims)
            assertNull(fixture.row(A).message)
            assertEquals(originalB, fixture.row(B))
        }
    }

    @Test
    fun closeRejectsNonCancellableLateMetadataAndCancelsQueuedAction() {
        // Given
        var gate: CompletableDeferred<Unit>? = null
        EngineCapabilitiesControlFixture { owner ->
            if (owner == A) gate?.let { withContext(NonCancellable) { it.await() } }
            metadata(owner)
        }.use { fixture ->
            val before = fixture.source.state.value
            val barrier = CompletableDeferred<Unit>().also { gate = it }
            fixture.source.refresh()
            fixture.dispatcher.runCurrent()
            fixture.source.stop(B)
            fixture.dispatcher.runCurrent()
            val requests = fixture.requests.toList()
            val commands = fixture.commands.toList()
            val owners = fixture.capabilityOwners.toList()
            // When
            fixture.source.close()
            barrier.complete(Unit)
            fixture.dispatcher.runCurrent()
            // Then
            assertEquals(before, fixture.source.state.value)
            assertEquals(requests, fixture.requests)
            assertEquals(commands, fixture.commands)
            assertEquals(owners, fixture.capabilityOwners)
            assertTrue(fixture.removed.isEmpty())
            assertEquals(1, fixture.closes)
        }
    }
}
