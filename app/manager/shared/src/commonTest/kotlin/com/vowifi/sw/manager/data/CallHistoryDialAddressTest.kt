package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlin.test.*

class CallHistoryDialAddressTest {
    @Test fun preservesGlobalAddressWithoutChangingDigitMatching() {
        val global = requireNotNull(CallPeerPhone.parse("+12025550123"))
        val local = requireNotNull(CallPeerPhone.parse("12025550123"))
        assertEquals("+12025550123", global.dialNumber())
        assertEquals("12025550123", local.dialNumber())
        assertEquals(local.digits(), global.digits())
        assertEquals(local, global)
        assertEquals(local.hashCode(), global.hashCode())
        assertEquals("CallPeerPhone", global.toString())
    }

    @Test fun planningAndInterruptedHistoryRetainTheOriginalDialAddress() {
        val peer = requireNotNull(callHistoryDialPeer("+12025550123"))
        val write = requireNotNull(callHistoryWrite(requireNotNull(CallCorrelationKey.parse("synthetic-global")),
            CallDirection.OUTGOING, peer, 1_000, null, null, null, CallOutcome.STARTED))
        assertEquals("+12025550123", write.peer?.dialNumber())
        val ended = requireNotNull(write.interrupted(2_000))
        assertEquals("+12025550123", ended.peer?.dialNumber())
        assertEquals(CallOutcome.INTERRUPTED, ended.outcome)
        assertEquals("+12025550123", callHistoryPeer(CallPeer.parse("+12025550123"))?.dialNumber())
    }

    @Test fun legacyLocalHistoryIsNotGuessedIntoAnInternationalAddress() {
        val write = requireNotNull(CallHistoryWrite.parse(CallHistoryDraft("synthetic-legacy",
            CallDirection.OUTGOING, "12025550123", CallTimesDraft(1_000), CallOutcome.STARTED)))
        assertEquals("12025550123", write.peer?.dialNumber())
        assertEquals("12025550123", requireNotNull(write.interrupted(2_000)).peer?.dialNumber())
        assertFalse(write.toString().contains("12025550123"))
    }
}
