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

    @Test fun homeCountryFromMccFlagsPersistedNamesAndSeparatesSameNameAcrossCountries() = runBlocking {
        var active = listOf(SimActiveJoinInput(rawA, 0, mcc = "234", mnc = "20", homeCarrierLabel = "3"),
            SimActiveJoinInput(rawB, 1, mcc = "454", mnc = "03", homeCarrierLabel = "3"))
        val names = AndroidHistoryLineNames(context, ownerFor, active = { active })
        val expected = mapOf(a to "\uD83C\uDDEC\uD83C\uDDE7 3", b to "\uD83C\uDDED\uD83C\uDDF0 3")
        assertEquals(expected, names.labels(listOf(a, b)))
        active = emptyList()
        assertEquals("移除后仍保留已观察的归属国家", expected, AndroidHistoryLineNames(context, ownerFor, active = { active }).labels(listOf(a, b)))
    }

    @Test fun removedLinesWithoutObservedCountryUseTheirConfiguredHomeMccOnce() = runBlocking {
        var active = listOf(SimActiveJoinInput(rawA, 0, homeCarrierLabel = "3"), SimActiveJoinInput(rawB, 1, homeCarrierLabel = "3"))
        AndroidHistoryLineNames(context, ownerFor, active = { active }).labels(listOf(a, b))
        active = emptyList()
        val asked = mutableListOf<String>()
        val configured = mapOf(rawA to "234", rawB to "454")
        val names = AndroidHistoryLineNames(context, ownerFor, active = { active }, knownKeys = { listOf(rawA, rawB) },
            homeMcc = { key -> asked += key; configured[key] })
        val expected = mapOf(a to "\uD83C\uDDEC\uD83C\uDDE7 3", b to "\uD83C\uDDED\uD83C\uDDF0 3")
        assertEquals("旧缓存无国家码的历史线路由配置归属MCC补国旗且跨国同名不冲突", expected, names.labels(listOf(a, b)))
        assertEquals(expected, names.labels(listOf(a, b)))
        assertEquals("国家码写入缓存后不再读取配置", listOf(rawA, rawB), asked)
        val stored = context.getSharedPreferences("history_line_names", 0).all.toString()
        assertFalse(stored.contains(rawA)); assertFalse(stored.contains(rawB))
    }

    @Test fun unreadableConfiguredMccIsTriedOncePerProcessAndKeepsTheUnflaggedName() = runBlocking {
        AndroidHistoryLineNames(context, ownerFor, active = { listOf(SimActiveJoinInput(rawA, 0, homeCarrierLabel = "T-Mobile")) }).labels(listOf(a))
        var calls = 0
        val names = AndroidHistoryLineNames(context, ownerFor, active = { emptyList() }, knownKeys = { listOf(rawA) },
            homeMcc = { calls++; null })
        assertEquals("T-Mobile", names.labels(listOf(a))[a])
        assertEquals("T-Mobile", names.labels(listOf(a))[a])
        assertEquals(1, calls)
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
