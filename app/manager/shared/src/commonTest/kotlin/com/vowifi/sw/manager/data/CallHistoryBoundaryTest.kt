package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallPeerPhone
import com.vowifi.sw.manager.model.CallRecord
import com.vowifi.sw.manager.model.CallTimesDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallHistoryBoundaryTest {
    @Test
    fun validPhonePeerParsesPlusAndPlainToDigits() {
        val plain = CallPeerPhone.parse(SYNTH_PHONE)
        val plus = CallPeerPhone.parse(SYNTH_PHONE_PLUS)
        assertNotNull(plain)
        assertNotNull(plus)
        assertEquals(SYNTH_PHONE, plain.digits())
        assertEquals(SYNTH_PHONE, plus.digits())
        assertEquals(plain, plus)
        assertEquals("CallPeerPhone", plain.toString())
        assertPrivate(plain.toString())
        assertFalse(plain.toString().contains(SYNTH_PHONE))
    }

    @Test
    fun invalidPeerSipRawAndMalformedAreRejected() {
        val invalidPeers = listOf(
            "sip:alice@example.invalid",
            "sips:alice@example.invalid",
            "tel:+15550101",
            "raw:15550101",
            "raw:+15550101",
            "alice@example.invalid",
            "++15550101",
            "",
            "+",
            " 15550101",
            "15550101 ",
            "peer1555",
            "123456789012345678901",
        )
        invalidPeers.forEach { peer ->
            assertNull(CallPeerPhone.parse(peer), peer)
            assertNull(CallHistoryWrite.parse(historyDraft(peer = peer)), peer)
        }
    }

    @Test
    fun invalidTimesAndOutcomeCombinationsAreRejected() {
        assertNull(historyWriteOrNull(startedAtMs = -1))
        assertNull(historyWriteOrNull(connectedAtMs = T0 - 1, outcome = CallOutcome.CONNECTED))
        assertNull(historyWriteOrNull(endedAtMs = T0 - 1, outcome = CallOutcome.COMPLETED))
        assertNull(
            historyWriteOrNull(
                connectedAtMs = T0 + 5,
                endedAtMs = T0 + 1,
                outcome = CallOutcome.COMPLETED,
            ),
        )
        assertNull(historyWriteOrNull(durationSec = -1, endedAtMs = T0 + 1, outcome = CallOutcome.COMPLETED))
        assertNull(historyWriteOrNull(endedAtMs = T0 + 1, outcome = CallOutcome.STARTED))
        assertNull(historyWriteOrNull(outcome = CallOutcome.CONNECTED))
        assertNull(historyWriteOrNull(outcome = CallOutcome.COMPLETED))
        assertNull(CallHistoryWrite.parse(historyDraft(correlation = "sip:call@example.invalid")))
        assertNull(CallHistoryWrite.parse(historyDraft(correlation = "raw:corr-synth-a")))
        assertNull(CallHistoryWrite.parse(historyDraft(correlation = "")))
    }

    @Test
    fun publicModelsDoNotExposeCorrelationOrPeerInToString() {
        val write = requireNotNull(CallHistoryWrite.parse(historyDraft()))
        val record = CallRecord(
            direction = CallDirection.OUTGOING,
            peer = requireNotNull(CallPeerPhone.parse(SYNTH_PHONE)),
            startedAtMs = T0,
            connectedAtMs = null,
            endedAtMs = null,
            durationSec = null,
            outcome = CallOutcome.STARTED,
        )
        val ready = CallHistoryState.Ready(listOf(record))
        val texts = listOf(
            write.toString(),
            record.toString(),
            ready.toString(),
            CallHistoryDraft::class.simpleName ?: "",
            CallTimesDraft(T0).toString(),
            CallHistoryStorageException().toString(),
        )
        texts.forEach { text ->
            assertFalse(text.contains(CORR_A), text)
            assertFalse(text.contains(SYNTH_PHONE), text)
            assertFalse(text.contains("sip:"), text)
            assertFalse(text.contains("raw:"), text)
            assertPrivate(text)
        }
        assertEquals("CallHistoryStorageException", CallHistoryStorageException().toString())
        assertEquals(CALL_HISTORY_STORAGE_NOTICE, CallHistoryStorageException().message)
        assertEquals("Ready(recordCount=1)", ready.toString())
    }

    @Test
    fun callHistoryStorageGuardRejectsDeviceProtectedStorage() {
        openCallHistoryStorageGuard(false)
        val error = assertFailsWith<CallHistoryStorageException> {
            openCallHistoryStorageGuard(true)
        }
        assertEquals("CallHistoryStorageException", error.toString())
        assertPrivate(error.toString())
    }

    @Test
    fun historyStateFailureStaysBounded() {
        val failure = CallHistoryState.Failure(CALL_HISTORY_STORAGE_NOTICE)
        assertTrue(failure.message.isNotEmpty())
        assertPrivate(failure.toString())
        assertFalse(failure.toString().contains(CORR_A))
    }
}

internal const val CORR_A = "corr-synth-a"
internal const val CORR_B = "corr-synth-b"
internal const val SYNTH_PHONE = "15550101"
internal const val SYNTH_PHONE_PLUS = "+15550101"
internal const val T0 = 1_700_000_000_000L

internal fun historyDraft(
    correlation: String = CORR_A,
    direction: CallDirection = CallDirection.OUTGOING,
    peer: String? = SYNTH_PHONE,
    startedAtMs: Long = T0,
    connectedAtMs: Long? = null,
    endedAtMs: Long? = null,
    durationSec: Long? = null,
    outcome: CallOutcome = CallOutcome.STARTED,
): CallHistoryDraft = CallHistoryDraft(
    correlation = correlation,
    direction = direction,
    peer = peer,
    times = CallTimesDraft(startedAtMs, connectedAtMs, endedAtMs, durationSec),
    outcome = outcome,
)

internal fun historyWriteOrNull(
    correlation: String = CORR_A,
    direction: CallDirection = CallDirection.OUTGOING,
    peer: String? = SYNTH_PHONE,
    startedAtMs: Long = T0,
    connectedAtMs: Long? = null,
    endedAtMs: Long? = null,
    durationSec: Long? = null,
    outcome: CallOutcome = CallOutcome.STARTED,
): CallHistoryWrite? = CallHistoryWrite.parse(
    historyDraft(
        correlation = correlation,
        direction = direction,
        peer = peer,
        startedAtMs = startedAtMs,
        connectedAtMs = connectedAtMs,
        endedAtMs = endedAtMs,
        durationSec = durationSec,
        outcome = outcome,
    ),
)

internal fun historyWrite(
    correlation: String = CORR_A,
    direction: CallDirection = CallDirection.OUTGOING,
    peer: String? = SYNTH_PHONE,
    startedAtMs: Long = T0,
    connectedAtMs: Long? = null,
    endedAtMs: Long? = null,
    durationSec: Long? = null,
    outcome: CallOutcome = CallOutcome.STARTED,
): CallHistoryWrite = requireNotNull(
    historyWriteOrNull(
        correlation = correlation,
        direction = direction,
        peer = peer,
        startedAtMs = startedAtMs,
        connectedAtMs = connectedAtMs,
        endedAtMs = endedAtMs,
        durationSec = durationSec,
        outcome = outcome,
    ),
)
