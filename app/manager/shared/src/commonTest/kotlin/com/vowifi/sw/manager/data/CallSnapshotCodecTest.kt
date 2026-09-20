package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlin.test.*
import kotlinx.serialization.json.*

class CallSnapshotCodecTest {
    @Test fun idleOmitsIdentityAndMalformedIdentityCannotRestoreCall() {
        val idle = snapshot("IDLE")
        assertEquals(CallPhase.Idle, CallSnapshotCodec.decode(idle)?.phase)
        val active = snapshot("IN_CALL")
        assertNull(CallSnapshotCodec.decode(active.copy(result = JsonObject(active.result!! - "callId"))))
        assertNull(CallSnapshotCodec.decode(idle.copy(result = JsonObject(idle.result!! + ("callId" to JsonPrimitive("old"))))))
        assertNull(CallSnapshotCodec.decode(active.copy(result = JsonObject(active.result + ("revision" to JsonPrimitive("3"))))))
        assertNull(CallSnapshotCodec.decode(active.copy(result = JsonObject(active.result + ("dtmfSupported" to JsonPrimitive("true"))))))
        assertNull(CallSnapshotCodec.decode(protocolError(2006, "unavailable")))
    }

    @Test fun unsafePeerIsOmittedButOpaqueEpochIsNotSortedOrDisplayed() {
        val active = snapshot("IN_CALL", epoch = "opaque-z")
        val wire = active.copy(result = JsonObject(active.result!! + ("peer" to JsonPrimitive("sip:private@example.test"))))
        val decoded = CallSnapshotCodec.decode(wire)!!
        assertNull(decoded.peer)
        assertEquals("opaque-z", decoded.version.epoch)
        assertFalse(decoded.version.toString().contains("opaque-z"))
    }

    @Test fun durationIsBoundedAndDoesNotUseWallClock() {
        var now = 0L
        val clock = CallSessionClock(359_998_000) { now }
        now = Long.MAX_VALUE
        assertEquals("99:59:59", clock.formatted())
        val initial = CallSessionClock(5000) { 200L }
        assertEquals(5000L, initial.elapsedMs())
    }
}
