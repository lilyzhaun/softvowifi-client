@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.SimActiveJoinInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
class HistoryLineNamesHostTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val rawA = "89" + "123456789012345671"
    private val rawB = "89" + "123456789012345672"
    private val a = SimOwnerId.fromHmacSha256(ByteArray(32) { 1 })!!
    private val b = SimOwnerId.fromHmacSha256(ByteArray(32) { 2 })!!
    private val ownerFor: (String) -> SimOwnerId? = { if (it == rawA) a else if (it == rawB) b else null }

    @Test fun operatorsAndTailsPersistForRemovedLinesWithoutRawIdentityOrLegacyCarrier() = runBlocking {
        var active = listOf(SimActiveJoinInput(rawA, 0, "Roaming", homeCarrierLabel = "T-Mobile"),
            SimActiveJoinInput(rawB, 1, "Custom SIM", homeCarrierLabel = "T-Mobile"))
        val names = AndroidHistoryLineNames(context, ownerFor, active = { active })
        names.rememberKeys(listOf(rawA, rawB))
        assertEquals("T-Mobile · •••• 5671", names.labels(listOf(a, b))[a])
        active = emptyList()
        val reopened = AndroidHistoryLineNames(context, ownerFor, active = { active })
        assertEquals("T-Mobile · •••• 5672", reopened.labels(listOf(b))[b])
        val stored = context.getSharedPreferences("history_line_names", 0).all.toString()
        assertFalse(stored.contains(rawA))
        assertFalse(stored.contains(rawB))
        assertFalse(stored.contains("Roaming"))
        assertFalse(stored.contains("Custom SIM"))
    }

    @Test fun temporaryUnreadableCarrierPreservesPreviouslyObservedNameAndNewUnknownGetsTail() = runBlocking {
        var active = listOf(SimActiveJoinInput(rawA, 0, homeCarrierLabel = "T-Mobile"))
        val names = AndroidHistoryLineNames(context, ownerFor, active = { active })
        names.rememberKeys(listOf(rawA, rawB))
        assertEquals("T-Mobile", names.labels(listOf(a))[a])
        active = listOf(SimActiveJoinInput(rawA, 0, homeCarrierLabel = null))
        assertEquals("T-Mobile", names.labels(listOf(a))[a])
        assertEquals("归属运营商未知 · •••• 5672", names.labels(listOf(b))[b])
    }
}
