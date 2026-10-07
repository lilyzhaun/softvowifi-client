@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class CallAudioStartControlTest {
    @Test fun audioStartBypassesTheSerialConnectionHoldingAnUnansweredDial() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val serial = Mutex()
        val wire = FakeCallTransport()
        val transport = object : EngineMessageTransport by wire {
            override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response = serial.withLock {
                if (method == "dial") gate.await()
                wire.request(method, params, timeoutMillis)
            }
        }
        val recorded = mutableListOf<Triple<String,String,Long>>()
        val control = CallControlTransport { owner, method, params, timeout ->
            recorded += Triple(owner, method, timeout)
            assertEquals(CALL_TOKEN, (params["callId"] as kotlinx.serialization.json.JsonPrimitive).content)
            EngineProtocolFrame.Response(1, buildJsonObject { put("started", true) }, null)
        }
        val dialing = async { transport.request("dial", CallProtocolCodec.dialParams(CALL_NUMBER), CallProtocolCodec.DIAL_TIMEOUT_MILLIS) }
        val session = CallAudioSession(CALL_ICCID_A, CALL_TOKEN, 1, transport, CallAudioMode.Progress, 1)
        val starting = async { startCallAudioControl(session, control) }
        try {
            assertFalse(dialing.isCompleted)
            assertEquals(listOf(Triple(CALL_ICCID_A,"audioStart",5_000L)), recorded,
                "正在等待dial的串行socket不能阻塞接通前audioStart")
            assertTrue(starting.isCompleted)
            assertNull(starting.await().error)
            assertEquals(0, wire.requests.count { it.method == "audioStart" })
        } finally { gate.complete(Unit); dialing.await(); starting.cancel() }
    }
}
