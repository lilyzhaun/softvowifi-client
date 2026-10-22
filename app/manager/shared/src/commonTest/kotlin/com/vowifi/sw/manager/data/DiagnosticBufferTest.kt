package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DiagnosticBufferTest {
    private val run = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
    private fun failure(slot: Int? = 0, count: Int = 0) = DiagnosticEvent(
        DiagnosticComponent.SUBSCRIPTIONS, DiagnosticLevel.ERROR, DiagnosticName.REJECTED,
        DiagnosticStage.IDENTITY, DiagnosticResult.FAILURE, DiagnosticReason.UNSUPPORTED_CHARACTERS,
        slot = slot, count = count,
    )
    private fun buffer() = DiagnosticBuffer(run, 123)
    private fun drain(buffer: DiagnosticBuffer): List<String> = buildList {
        while (true) add(buffer.poll()?.decodeToString() ?: break)
    }

    @Test fun firstFailureAndSixtySecondSummaryKeepLastOccurrence() {
        val buffer = buffer()
        buffer.offer(failure(), 1000, 0)
        for (time in 1L..59_999L) buffer.offer(failure(), 1000 + time, time)
        assertEquals(1, buffer.stats().queued)
        buffer.offer(failure(), 61_000, 60_000)
        val lines = drain(buffer)
        assertEquals(2, lines.size)
        val summary = Json.parseToJsonElement(lines.last()).jsonObject
        assertEquals("60000", summary["suppressed"]?.jsonPrimitive?.content)
        assertEquals("60000", summary["last_mono_ms"]?.jsonPrimitive?.content)
        assertEquals("0", summary["first_mono_ms"]?.jsonPrimitive?.content)
    }

    @Test fun recoverySettlesPendingBeforeRecoveryAndCloseDoesNotInventDurability() {
        val buffer = buffer()
        buffer.offer(failure(), 1000, 0)
        buffer.offer(failure(), 1001, 1)
        buffer.offer(failure().copy(name = DiagnosticName.RECOVERED, result = DiagnosticResult.SUCCESS,
            reason = DiagnosticReason.NONE, level = DiagnosticLevel.INFO), 1002, 2)
        val lines = drain(buffer)
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("\"suppressed\":1"))
        assertTrue(lines[2].contains("\"name\":\"recovered\""))
        buffer.finish(1003, 3)
        assertFalse(buffer.offer(failure(), 1004, 4))
        assertFalse(drain(buffer).joinToString().contains("fsync"))
    }

    @Test fun stateChurnAndErrorFloodCannotBypassRateOrQueueBudget() {
        val buffer = buffer()
        repeat(100_000) { buffer.offer(failure(count = it), 1000, 0) }
        assertTrue(buffer.stats().queued <= 20)
        assertTrue(buffer.stats().queuedBytes <= 512 * 1024)
        assertTrue(buffer.stats().dropped > 99_000)
        for (second in 1L..1000L) repeat(20) {
            buffer.offer(failure(count = it), 1000 + second * 1000, second * 1000)
        }
        assertTrue(buffer.stats().queued <= 256)
        assertTrue(buffer.stats().queuedBytes <= 512 * 1024)
        val lines = drain(buffer)
        assertTrue(lines.all { it.encodeToByteArray().size <= 2048 })
        println("diagnostic_budget queued=${lines.size} bytes=${lines.sumOf { it.encodeToByteArray().size }} " +
            "keys=${buffer.stats().keys} dropped=${buffer.stats().dropped} max_record=${lines.maxOf { it.encodeToByteArray().size }}")
    }

    @Test fun manyKeysAreBoundedAndEvictedSuppressionIsAccounted() {
        val buffer = buffer()
        var time = 0L
        for (component in DiagnosticComponent.entries) for (stage in DiagnosticStage.entries) {
            val event = failure().copy(component = component, stage = stage)
            buffer.offer(event, 1000 + time, time++)
            buffer.offer(event, 1000 + time, time++)
        }
        assertTrue(buffer.stats().keys <= 128)
        assertTrue(buffer.stats().dropped > 0)
        assertTrue(buffer.stats().evicted > 0)
    }

    @Test fun terminalHasFinitePriorityAndRunChangesAcrossRestart() {
        val buffer = buffer()
        for (second in 0L..30L) repeat(16) {
            buffer.offer(failure(count = it).copy(level = DiagnosticLevel.INFO,
                result = DiagnosticResult.OBSERVED), second * 1000, second * 1000)
        }
        buffer.finish(32_000, 32_000)
        assertTrue(buffer.stats().queued <= 256)
        val text = drain(buffer).joinToString()
        assertTrue(text.contains("process_stopping"))
        assertTrue(text.contains("dropped"))
        val restarted = DiagnosticBuffer("91df0957-6bc2-48da-bbcc-4ecde24d1ed4", 124)
        restarted.offer(failure(), 33_000, 1)
        assertFalse(drain(restarted).single().contains(run))
    }

    @Test fun byteLimitIsIndependentOfRecordCountAndCannotBeRaisedAboveHardCap() {
        val small = DiagnosticBuffer(run, 123, maxQueuedBytes = 2048)
        repeat(20) { small.offer(failure(count = it), 1000, 0) }
        assertTrue(small.stats().queuedBytes <= 2048)
        assertTrue(small.stats().queued in 1..19)
        assertTrue(small.stats().dropped > 0)
        val capped = DiagnosticBuffer(run, 123, maxQueuedBytes = Int.MAX_VALUE)
        for (second in 0L..20L) repeat(20) { capped.offer(failure(count = it), second * 1000, second * 1000) }
        assertTrue(capped.stats().queuedBytes <= 512 * 1024)
        assertTrue(capped.stats().queued <= 256)
    }
}
