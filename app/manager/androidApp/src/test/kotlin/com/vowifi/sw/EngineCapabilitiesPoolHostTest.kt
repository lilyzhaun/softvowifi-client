package com.vowifi.sw

import com.vowifi.sw.EngineCapabilitiesHostFixture.Companion.OTHER_KEY
import com.vowifi.sw.EngineCapabilitiesHostFixture.Reply
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class EngineCapabilitiesPoolHostTest {
    @Test
    fun replacedPoolClientCannotPublishOldMetadata() = runBlocking {
        // Given
        EngineCapabilitiesHostFixture().use { backend ->
            val source = backend.source()
            val original = backend.ready(source)
            backend.reply = Reply.Silent
            source.refresh()
            val (socket, id) = withTimeout(8_000) { backend.pending.await() }
            val methods = backend.pool.javaClass.declaredMethods
            val retired = methods.single { it.name.startsWith("remove$") }.invoke(backend.pool, "synthetic-owner") as AutoCloseable
            try {
                methods.single { it.name.startsWith("clientFor$") }.invoke(backend.pool, "synthetic-owner")
                // When
                socket.respond(id, EngineCapabilitiesHostFixture.metadata())
                val changed = backend.ready(source, requireNotNull(original.runtime).reconnects)
                // Then
                assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Transport), changed.clientCapabilities)
                assertEquals("REGISTERED", changed.runtime?.ims)
                assertNull(changed.message)
            } finally { retired.close() }
        }
    }

    @Test
    fun closedPoolCannotPublishPendingMetadataAsReady() = runBlocking {
        // Given
        EngineCapabilitiesHostFixture().use { backend ->
            val source = backend.source()
            val original = backend.ready(source)
            backend.reply = Reply.Silent
            source.refresh()
            withTimeout(8_000) { backend.pending.await() }
            // When
            backend.pool.close()
            val changed = backend.ready(source, requireNotNull(original.runtime).reconnects)
            // Then
            assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Transport), changed.clientCapabilities)
            assertEquals("REGISTERED", changed.runtime?.ims)
            assertNull(changed.message)
        }
    }

    @Test
    fun lateTimedOutResponseCannotOverrideNextSnapshot() = runBlocking {
        // Given
        EngineCapabilitiesHostFixture().use { backend ->
            backend.reply = Reply.Silent
            val source = backend.source()
            val expired = backend.ready(source)
            assertEquals(ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Timeout), expired.clientCapabilities)
            val (socket, id) = withTimeout(8_000) { backend.pending.await() }
            backend.reply = Reply.Valid
            backend.key = OTHER_KEY
            // When
            socket.respond(id, EngineCapabilitiesHostFixture.metadata())
            source.refresh()
            val changed = backend.ready(source, requireNotNull(expired.runtime).reconnects)
            // Then
            assertEquals(OTHER_KEY, (changed.clientCapabilities as ClientCapabilitiesResult.Ready).capabilities.instanceKey)
        }
    }
}
