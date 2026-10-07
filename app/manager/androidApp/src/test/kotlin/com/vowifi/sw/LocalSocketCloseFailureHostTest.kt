package com.vowifi.sw

import java.io.EOFException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
class LocalSocketCloseFailureHostTest {
    @Test fun outputShutdownFailureStillClosesDescriptorAndCompletesPending() = runBlocking {
        BlockingSocketClient().use { fixture ->
            fixture.transport.connect()
            fixture.socket.holdResponse = true
            val pending = async(Dispatchers.Default) { runCatching { fixture.transport.request("getStatus") }.exceptionOrNull() }
            withTimeout(5_000) { fixture.socket.requestReceived.await() }
            val outputError = IOException("synthetic output shutdown")
            fixture.socket.shutdownOutputFailure = outputError
            assertSame(outputError, runCatching { fixture.transport.close() }.exceptionOrNull())
            assertFalse(fixture.socket.descriptorOpen)
            assertEquals(1, fixture.socket.closeCalls.get())
            assertNull(fixture.connectedSocket)
            assertTrue(withTimeout(1_000) { pending.await() } is EOFException)
            fixture.transport.close()
            assertEquals(1, fixture.socket.closeCalls.get())
        }
    }

    @Test fun shutdownFailureStillClosesDescriptorAndCompletesPending() = failedClose(shutdownFails = true, closeFails = false)
    @Test fun closeFailureStillClearsConnectionAndCompletesPending() = failedClose(shutdownFails = false, closeFails = true)
    @Test fun bothFailuresKeepShutdownErrorAndSuppressCloseError() = failedClose(shutdownFails = true, closeFails = true)

    private fun failedClose(shutdownFails: Boolean, closeFails: Boolean) = runBlocking {
        BlockingSocketClient().use { fixture ->
            fixture.transport.connect()
            val reader = fixture.reader
            fixture.socket.holdResponse = true
            val pending = async(Dispatchers.Default) { runCatching { fixture.transport.request("getStatus") }.exceptionOrNull() }
            withTimeout(5_000) { fixture.socket.requestReceived.await() }
            val shutdownError = IOException("synthetic shutdown")
            val closeError = IOException("synthetic close")
            fixture.socket.shutdownFailure = shutdownError.takeIf { shutdownFails }
            fixture.socket.closeFailure = closeError.takeIf { closeFails }

            val failure = runCatching { fixture.transport.close() }.exceptionOrNull()

            assertSame(if (shutdownFails) shutdownError else closeError, failure)
            if (shutdownFails && closeFails) assertEquals(listOf(closeError), failure?.suppressed?.toList())
            assertFalse(fixture.socket.descriptorOpen)
            assertEquals(1, fixture.socket.closeCalls.get())
            assertNull(fixture.connectedSocket)
            assertTrue(withTimeout(1_000) { pending.await() } is EOFException)
            assertEquals(0, fixture.pendingCount)
            fixture.transport.close()
            assertEquals(1, fixture.socket.closeCalls.get())
            if (!shutdownFails) withTimeout(1_000) { reader.join() }
        }
    }

    @Test fun readerCloseFailureReachesPendingWithoutLosingDisconnect() = runBlocking {
        BlockingSocketClient().use { fixture ->
            fixture.transport.connect()
            val reader = fixture.reader
            fixture.socket.holdResponse = true
            val closeError = IOException("synthetic reader close")
            fixture.socket.closeFailure = closeError
            val pending = async(Dispatchers.Default) { runCatching { fixture.transport.request("getStatus") }.exceptionOrNull() }
            withTimeout(5_000) { fixture.socket.requestReceived.await() }

            fixture.socket.peerEof()

            val failure = withTimeout(1_000) { pending.await() }
            assertTrue(failure is EOFException)
            assertTrue(generateSequence(failure) { it.cause }.any { closeError in it.suppressed })
            withTimeout(1_000) { reader.join() }
            assertNull(fixture.connectedSocket)
            assertEquals(0, fixture.pendingCount)
            assertFalse(fixture.socket.descriptorOpen)
        }
    }
}
