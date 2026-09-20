package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimControlEvaluatorPrivacyTest {
    @Test
    fun nineteenDigitKeyIsAbsentFromEveryOutputString() {
        val handles = OpaqueIccidHandleStore()
        val ready = simReady(
            active = listOf(simActive(SIM_ICCID_19, slotIndex = 1, carrierName = "T-Mobile", mcc = "310", mnc = "260")),
            engineRows = listOf(simEngine(SIM_ICCID_19, EngineLifecycle.STOPPED)),
            handles = handles,
        )

        assertEquals("sim-1", ready.instances.single().handle.value)
        assertEquals(SIM_ICCID_19, handles.keyFor(ready.instances.single().handle))
        assertFalse(ready.instances.single().displayLabel.contains(SIM_TAIL_FOUR))
        assertNoRawKeys(ready, SIM_ICCID_19)
        publicStrings(ready).forEach { text ->
            assertFalse(text.contains(SIM_ICCID_19))
            assertFalse(text.contains("joinKey", ignoreCase = true))
            assertFalse(text.contains("iccid", ignoreCase = true))
        }
    }

    @Test
    fun hostileFifteenDigitCarrierIsSanitizedOrFallback() {
        val fallback = simReady(
            active = listOf(simActive(SIM_KEY_A, slotIndex = 0, carrierName = SIM_DIGITS_15, displayName = null)),
            engineRows = emptyList(),
        ).instances.single()
        val secondary = simReady(
            active = listOf(simActive(SIM_KEY_B, slotIndex = 1, carrierName = "alpha $SIM_DIGITS_15", displayName = "SafeTel")),
            engineRows = emptyList(),
        ).instances.single()

        assertEquals("已匹配订阅", fallback.carrierLabel)
        assertEquals("SafeTel", secondary.carrierLabel)
        assertFalse(fallback.displayLabel.contains(SIM_DIGITS_15))
        assertFalse(secondary.displayLabel.contains(SIM_DIGITS_15))
        assertNoRawKeys(simJoin(listOf(simActive(SIM_KEY_A, 0, carrierName = SIM_DIGITS_15)), emptyList()), SIM_KEY_A, SIM_DIGITS_15)
    }

    @Test
    fun hostileMatchedEngineMessageFailsClosed() {
        val leaked = simJoin(
            active = listOf(simActive(SIM_KEY_A, slotIndex = 0)),
            engineRows = listOf(simEngine(SIM_KEY_A, EngineLifecycle.STOPPED, message = "iccid=$SIM_ICCID_19")),
        )
        val path = simJoin(
            active = listOf(simActive(SIM_KEY_B, slotIndex = 1)),
            engineRows = listOf(simEngine(SIM_KEY_B, EngineLifecycle.RUNNING, message = "/data/adb/vowifi/config/x.json")),
        )

        val leakedFailure = assertIs<SimControlUiState.Failure>(leaked)
        val pathFailure = assertIs<SimControlUiState.Failure>(path)
        assertEquals("SIM 控制包含敏感标识", leakedFailure.message)
        assertEquals("SIM 控制包含敏感标识", pathFailure.message)
        assertNoRawKeys(leakedFailure, SIM_KEY_A, SIM_ICCID_19)
        assertNoRawKeys(pathFailure, SIM_KEY_B, "/data/adb/vowifi/config/x.json")
    }

    @Test
    fun unconfiguredReplacesHostileMessageAndRetainedEngineMessageStaysPrivate() {
        val unconfigured = simReady(
            active = listOf(simActive(SIM_KEY_A, slotIndex = 0)),
            engineRows = listOf(simEngine(SIM_KEY_A, EngineLifecycle.CONFIG_MISSING, message = "/data/adb/$SIM_ICCID_19")),
        )
        val removed = simReady(
            active = emptyList(),
            engineRows = listOf(simEngine(SIM_KEY_C, EngineLifecycle.STOPPED, message = "imsi=$SIM_DIGITS_15")),
        )

        assertEquals(UNCONFIGURED_MESSAGE, unconfigured.instances.single().message)
        assertTrue(removed.instances.isEmpty())
        assertNoRawKeys(unconfigured, SIM_KEY_A, SIM_ICCID_19)
        assertNoRawKeys(removed, SIM_KEY_C, SIM_DIGITS_15)
    }

    @Test
    fun hostileRetainedDisplayLabelIsNotProjected() {
        val state = simJoin(
            active = emptyList(),
            engineRows = listOf(simEngine(SIM_KEY_C, EngineLifecycle.STOPPED, displayLabel = "/data/adb/$SIM_ICCID_19.json")),
        )

        val ready = assertIs<SimControlUiState.Ready>(state)
        assertTrue(ready.instances.isEmpty())
        assertNoRawKeys(ready, SIM_KEY_C, SIM_ICCID_19)
    }

    @Test
    fun exactFourDigitTailOnly() {
        val withTail = simReady(
            active = listOf(simActive(SIM_KEY_A, slotIndex = 0, lastFour = SIM_TAIL_FOUR)),
            engineRows = emptyList(),
        ).instances.single()
        val five = simReady(
            active = listOf(simActive(SIM_KEY_B, slotIndex = 1, lastFour = "03367")),
            engineRows = emptyList(),
        ).instances.single()
        val fullKey = simReady(
            active = listOf(simActive(SIM_ICCID_19, slotIndex = 2, lastFour = SIM_ICCID_19)),
            engineRows = emptyList(),
        ).instances.single()

        assertTrue(withTail.displayLabel.endsWith("•••• 0336"))
        assertFalse(five.displayLabel.contains("03367"))
        assertFalse(five.displayLabel.contains("••••"))
        assertFalse(fullKey.displayLabel.contains(SIM_ICCID_19))
        assertFalse(fullKey.displayLabel.contains("••••"))
        assertNoRawKeys(
            simJoin(listOf(simActive(SIM_ICCID_19, 2, lastFour = SIM_ICCID_19)), emptyList()),
            SIM_ICCID_19,
        )
    }

    @Test
    fun publicModelsDoNotCarryIdentityFields() {
        val dumped = SimControlInstance(
            handle = SimHandle("sim-1"),
            displayLabel = "SIM 1 · alpha · 001 / 01",
            presence = SimPresence.ACTIVE_UNCONFIGURED,
            slotIndex = 0,
            carrierLabel = "alpha",
            mcc = "001",
            mnc = "01",
            lifecycle = EngineLifecycle.CONFIG_MISSING,
            pid = null,
            runtime = null,
            message = UNCONFIGURED_MESSAGE,
            canStart = false,
            canStop = false,
            canApply = false,
            canProvision = false,
        ).toString()
        val ui = SimControlUiState.Ready(emptyList()).toString()

        listOf("iccid", "imsi", "msisdn", "subId", "joinKey", "subscriptionId", "phone").forEach { token ->
            assertFalse(dumped.contains(token, ignoreCase = true), dumped)
            assertFalse(ui.contains(token, ignoreCase = true), ui)
        }
        assertTrue(dumped.contains("handle="))
        assertTrue(dumped.contains("displayLabel="))
        assertTrue(dumped.contains("canStart="))
        assertTrue(dumped.contains("canProvision="))
    }

    @Test
    fun joinReadyTopLevelMessageDefaultsToNull() {
        val ready = simReady(emptyList(), emptyList())

        assertNull(ready.message)
        assertFalse(publicStrings(ready).contains("null"))
    }

    @Test
    fun safeReadyMessageIsIncludedInPrivacyInspection() {
        val ready = SimControlUiState.Ready(emptyList(), message = "引擎暂时不可用")

        assertTrue(publicStrings(ready).contains("引擎暂时不可用"))
        assertNoRawKeys(ready)
        assertPrivate("引擎暂时不可用")
    }

    @Test
    fun hostileReadyMessageCannotBeOverlookedByHelpers() {
        val digits = SimControlUiState.Ready(emptyList(), message = SIM_DIGITS_15)
        val path = SimControlUiState.Ready(emptyList(), message = "/data/adb/vowifi/config/x.json")

        assertTrue(publicStrings(digits).contains(SIM_DIGITS_15))
        assertTrue(publicStrings(path).any { it.contains("/data/") })
        assertFails { assertNoRawKeys(digits) }
        assertFails { assertNoRawKeys(path) }
    }
}
