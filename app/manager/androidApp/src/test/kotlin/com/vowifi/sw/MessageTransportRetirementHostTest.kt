package com.vowifi.sw

import java.io.EOFException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
class MessageTransportRetirementHostTest {
    @Test
    fun retireBeforeConnectRejectsConnectAndRequestWithoutOpeningSocket() = runBlocking {
        SmsSocketBoundaryFixture().use { wire ->
            val transport = wire.transport()
            transport.retireMessages()

            val connectFailure = runCatching { transport.connect() }.exceptionOrNull()
            assertTrue(
                "retired transport connect() must fail with EOFException, got $connectFailure",
                connectFailure is EOFException,
            )
            assertEquals("retired transport must not open a socket", 0, wire.sockets.size)

            val requestFailure = runCatching {
                transport.request("sendSms", buildJsonObject {
                    put("number", "155501")
                    put("text", "synthetic retired before connect")
                })
            }.exceptionOrNull()
            assertTrue(
                "retired transport request() must fail with EOFException, got $requestFailure",
                requestFailure is EOFException,
            )
            assertEquals(0, wire.writes.count { it.second == "sendSms" })
        }
    }

    @Test
    fun retireDuringInFlightConnectRetiresOpenedConnectionAndRejectsRequests() = runBlocking {
        SmsSocketBoundaryFixture().use { wire ->
            val transport = wire.transport()
            val gate = SmsWriterGate(wire.writeLock)
            val connectJob = async(Dispatchers.Default) {
                runCatching { transport.connect() }.exceptionOrNull()
            }
            try {
                wire.awaitBlockedWriters(1)
                transport.retireMessages()
            } finally {
                gate.close()
            }
            val connectFailure = withTimeout(5_000) { connectJob.await() }
            assertTrue(
                "connect() retired while handshake was in flight must fail with EOFException, got $connectFailure",
                connectFailure is EOFException,
            )

            val requestFailure = runCatching {
                transport.request("sendSms", buildJsonObject {
                    put("number", "155501")
                    put("text", "synthetic retired during connect")
                })
            }.exceptionOrNull()
            assertTrue(
                "request() after in-flight retirement must fail with EOFException, got $requestFailure",
                requestFailure is EOFException,
            )
            assertEquals(0, wire.writes.count { it.second == "sendSms" })
        }
    }
}
