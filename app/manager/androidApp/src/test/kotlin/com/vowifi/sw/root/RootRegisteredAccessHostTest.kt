package com.vowifi.sw.root

import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyManager
import android.os.Looper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Callable
import java.util.function.LongSupplier

/** Real framework registration objects; the service lookup is the only platform seam. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RootRegisteredAccessHostTest {
    @Test fun firstEquivalentHomeKeepsTheFirstPhoneItemOnlyWhileItsSubscriptionIsCurrent() {
        val target = owner(0)
        fun observe(reader: (Int) -> Any?): JSONObject? = RootRegisteredAccess.read(Callable { listOf(target) },
            { ServiceState() }, { sub -> assertEquals(target.subscriptionId, sub); reader(sub) }, LongSupplier { 1000 })
        fun card(reader: (Int) -> Any?) = observe(reader)!!.getJSONArray("cards").getJSONObject(0)
        val first = card { listOf("00102", "00103") }
        assertEquals("unknown", first.getString("state")); assertFalse(first.has("plmn"))
        assertEquals("00102", first.getString("firstEhplmn")); assertFalse(first.toString().contains("00103"))
        assertEquals("00103", card { listOf("00103", "00102") }.getString("firstEhplmn"))
        assertEquals("", card { emptyList<String>() }.getString("firstEhplmn"))
        assertEquals("", card { listOf("invalid", "00102") }.getString("firstEhplmn"))
        assertFalse(card { throw SecurityException("private-provider-detail") }.has("firstEhplmn"))
        assertFalse(card { 42 }.has("firstEhplmn"))
        assertNull(observe { ReflectionHelpers.setField(target, "mPortIndex", 1); listOf("00102") })
    }

    @Test fun watcherNotifiesForPortOnlyChangesButNotForRepeatedObservations() {
        val target = owner(0)
        val cards = listOf(target, owner(1))
        val backend = java.lang.reflect.Proxy.newProxyInstance(IRootBackend::class.java.classLoader,
            arrayOf(IRootBackend::class.java)) { _, method, _ ->
            check(method.name == "activeSubscriptions")
            cards
        } as IRootBackend
        var events = 0
        val watcher = Runnable { events++ }
        fun sample() { RootBridge.checkSubscriptions(backend); shadowOf(Looper.getMainLooper()).idle() }
        RootBridge.watchSubscriptions(watcher)
        try {
            sample()
            assertEquals(1, events)
            sample()
            assertEquals(1, events)
            ReflectionHelpers.setField(target, "mPortIndex", 1)
            sample()
            assertEquals("端口是此次观察唯一的变化", 2, events)
            sample()
            assertEquals(2, events)
        } finally { RootBridge.unwatchSubscriptions(watcher) }
    }

    @Test fun rawNetworkRegistrationWinsOverCarrierRoamingOverrides() {
        for ((rawState, overlay) in listOf(5 to false, 1 to true)) {
            val network = registration(rawState, "00101", carrierRoaming = overlay)
            assertEquals(overlay, boolean(network, "isRoaming"))
            assertEquals(rawState == 5, boolean(network, "isNetworkRoaming"))
            val info = owner(0)
            val frame = read(listOf(info)) { service(network) }!!
            val card = frame.getJSONArray("cards").getJSONObject(0)
            assertEquals("known", card.getString("state"))
            assertEquals("00101", card.getString("plmn"))
            assertEquals(rawState == 5, card.getBoolean("roaming"))
            assertTrue(card.getString("iccid") == info.iccId)
            assertEquals(info.subscriptionId, card.getInt("subId"))
            assertEquals(0, card.getInt("slot")); assertEquals(0, card.getInt("port"))
            assertEquals(setOf("iccid", "slot", "subId", "port", "state", "plmn", "roaming"), card.keys().asSequence().toSet())
        }
    }

    @Test fun deniedLastAttemptMissingPlmnOrWlanCannotBecomeHome() {
        for (state in listOf(service(registration(3, "00101")), service(registration(1, null)),
            service(registration(1, "00101", transport = 2)), ServiceState())) {
            val card = read(listOf(owner(0))) { state }!!.getJSONArray("cards").getJSONObject(0)
            assertEquals("unknown", card.getString("state"))
            assertFalse(card.has("plmn")); assertFalse(card.has("roaming"))
        }
    }

    @Test fun conflictingRegisteredDomainsAreUnknownButOneUnregisteredDomainDoesNotHideTheOther() {
        for (voice in listOf(registration(5, "00101", domain = 1), registration(1, "00202", domain = 1))) {
            val card = read(listOf(owner(0))) { service(registration(1, "00101"), voice) }!!
                .getJSONArray("cards").getJSONObject(0)
            assertEquals("unknown", card.getString("state")); assertFalse(card.has("roaming"))
        }
        val card = read(listOf(owner(0))) { service(registration(3, "00202"), registration(5, "00101", domain = 1)) }!!
            .getJSONArray("cards").getJSONObject(0)
        assertEquals("known", card.getString("state")); assertTrue(card.getBoolean("roaming"))
        assertEquals("00101", card.getString("plmn"))
    }

    @Test fun oneUnavailableLineStaysUnknownWithoutLeakingAnExceptionOrHidingTheOther() {
        val frame = read(listOf(owner(1), owner(0))) { slot ->
            if (slot == 0) throw SecurityException("synthetic-private-provider-detail")
            service(registration(5, "00101"))
        }!!
        val rows = frame.getJSONArray("cards")
        assertEquals(0, rows.getJSONObject(0).getInt("slot"))
        assertEquals("unknown", rows.getJSONObject(0).getString("state"))
        assertEquals(1, rows.getJSONObject(1).getInt("slot"))
        assertEquals("known", rows.getJSONObject(1).getString("state"))
        assertFalse(frame.toString().contains("private-provider"))
    }

    @Test fun subscriptionOrPortChangeDuringReadRetiresTheWholeObservation() {
        val info = owner(0)
        var reads = 0
        val frame = RootRegisteredAccess.read(Callable { reads++; listOf(info) }, {
            ReflectionHelpers.setField(info, "mPortIndex", 1)
            service(registration(1, "00101"))
        }, { null }, LongSupplier { 1000 })
        assertNull(frame); assertEquals(2, reads)
        assertNull(read(listOf(owner(0), owner(0))) { service(registration(1, "00101")) })
    }

    @Test fun observationClockIsCapturedBeforeReadsAndRosterReorderingIsNotAnIdentityChange() {
        val cards = listOf(owner(1), owner(0))
        var reads = 0
        var now = 1000L
        val frame = RootRegisteredAccess.read(Callable { if (reads++ == 0) cards else cards.reversed() }, {
            now = 9000L; service(registration(1, "00101"))
        }, { null }, LongSupplier { now })!!
        assertEquals(1000L, frame.getLong("observedMs"))
        assertEquals(2, reads); assertEquals(1, frame.getInt("schema"))
        assertEquals(setOf("schema", "observedMs", "cards"), frame.keys().asSequence().toSet())
    }

    private fun read(cards: List<SubscriptionInfo>, states: (Int) -> Any): JSONObject? =
        RootRegisteredAccess.read(Callable { cards }, { slot -> states(slot) }, { null }, LongSupplier { 1000 })
    private fun owner(slot: Int): SubscriptionInfo = SubscriptionInfoBuilder.newBuilder().setId(slot + 7)
        .setSimSlotIndex(slot).setIccId("0".repeat(18) + (slot + 1)).buildSubscriptionInfo()
        .also { ReflectionHelpers.setField(it, "mPortIndex", 0) }
    private fun boolean(value: Any, method: String) = value.javaClass.getMethod(method).invoke(value) as Boolean
    private fun registration(state: Int, plmn: String?, domain: Int = 2, transport: Int = 1, carrierRoaming: Boolean? = null): Any {
        val type = Class.forName("android.telephony.NetworkRegistrationInfo\$Builder")
        val builder = type.getDeclaredConstructor().newInstance()
        for ((name, value) in listOf("setDomain" to domain, "setTransportType" to transport,
            "setRegistrationState" to state, "setAccessNetworkTechnology" to TelephonyManager.NETWORK_TYPE_LTE)) {
            type.getMethod(name, Int::class.javaPrimitiveType).invoke(builder, value)
        }
        if (plmn != null) type.getMethod("setRegisteredPlmn", String::class.java).invoke(builder, plmn)
        return type.getMethod("build").invoke(builder).also { network -> carrierRoaming?.let { enabled ->
            val flag = ServiceState::class.java.getField(if (enabled) "ROAMING_TYPE_INTERNATIONAL" else "ROAMING_TYPE_NOT_ROAMING").getInt(null)
            network.javaClass.getMethod("setRoamingType", Int::class.javaPrimitiveType).invoke(network, flag)
        } }
    }
    private fun service(vararg registrations: Any) = ServiceState().also { value ->
        val type = Class.forName("android.telephony.NetworkRegistrationInfo")
        val add = ServiceState::class.java.getMethod("addNetworkRegistrationInfo", type)
        registrations.forEach { add.invoke(value, it) }
    }
}
