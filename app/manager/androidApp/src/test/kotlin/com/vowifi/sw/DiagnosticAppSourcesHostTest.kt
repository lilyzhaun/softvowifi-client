@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.data.*
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [RootFrameworkShadow::class])
class DiagnosticAppSourcesHostTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun subscription(id: Int, slot: Int, identity: String) = SubscriptionInfoBuilder.newBuilder()
        .setId(id).setSimSlotIndex(slot).setIccId(identity).setMcc("001").setMnc("01").buildSubscriptionInfo()

    @Test fun rejectedIdentityHasASlotReasonButDoesNotChangeTheInventory() {
        val manager = app.getSystemService(SubscriptionManager::class.java)
        shadowOf(manager).setActiveSubscriptionInfoList(listOf(
            subscription(101, 0, "000000000000001F"), subscription(102, 1, "00000000000000X"),
        ))
        val events = mutableListOf<DiagnosticEvent>()
        val result = AndroidSubscriptionInventory(app, events::add).listActive()
        assertTrue(result is SubscriptionInventoryResult.Active)
        assertEquals(listOf("000000000000001"), (result as SubscriptionInventoryResult.Active).subscriptions.map { it.key })
        val rejected = events.singleOrNull { it.name == DiagnosticName.REJECTED && it.stage == DiagnosticStage.NORMALIZE }
        assertNotNull("a filtered active SIM must not silently disappear", rejected)
        assertEquals(1, rejected!!.slot)
        assertEquals(DiagnosticReason.UNSUPPORTED_CHARACTERS, rejected.reason)
        val listed = events.lastOrNull { it.stage == DiagnosticStage.ENUMERATE }
        assertNotNull("real enumeration needs an observed total and accepted count", listed)
        assertEquals(2, listed!!.count)
        assertEquals(1, listed.accepted)
        assertFalse(events.toString().contains("000000000000001"))
        assertFalse(events.toString().contains("00000000000000X"))
    }

    @Test fun emptyInventoryIsAnObservationNotPermissionFailure() {
        shadowOf(app.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(emptyList())
        val events = mutableListOf<DiagnosticEvent>()
        val result = AndroidSubscriptionInventory(app, events::add).listActive()
        assertTrue((result as SubscriptionInventoryResult.Active).subscriptions.isEmpty())
        assertTrue("empty and denied must be distinguishable", events.any {
            it.stage == DiagnosticStage.ENUMERATE && it.result == DiagnosticResult.OBSERVED && it.reason == DiagnosticReason.EMPTY && it.count == 0
        })
    }

    @Test fun subscriptionListenerRegistrationAndRemovalAreObserved() {
        val events = mutableListOf<DiagnosticEvent>()
        val inventory = AndroidSubscriptionInventory(app, events::add)
        var callbacks = 0
        val first = inventory.startWatching { callbacks++ }
        val second = inventory.startWatching { callbacks++ }
        first.stop()
        second.stop()
        assertEquals(1, events.count { it.stage == DiagnosticStage.LISTENER && it.result == DiagnosticResult.SUCCESS })
        assertEquals(1, events.count { it.stage == DiagnosticStage.LISTENER && it.result == DiagnosticResult.STOPPED })
        assertFalse(events.any { it.result == DiagnosticResult.FAILURE })
    }

    private fun observer(events: MutableList<DiagnosticEvent>, publish: (String) -> Boolean = { false }) =
        AndroidUnderlayObserver(app, publish = publish, now = { 20_000 }, diagnostic = events::add)
    private fun callback(observer: AndroidUnderlayObserver) = ReflectionHelpers.getField<ConnectivityManager.NetworkCallback>(observer, "callback")
    private fun network() = ReflectionHelpers.callConstructor(Network::class.java, ClassParameter.from(Int::class.javaPrimitiveType!!, 101))
    private fun add(caps: NetworkCapabilities, method: String, value: Int) {
        ReflectionHelpers.callInstanceMethod<NetworkCapabilities>(caps, method, ClassParameter.from(Int::class.javaPrimitiveType!!, value))
    }
    private fun wifi() = NetworkCapabilities().also {
        add(it, "addTransportType", NetworkCapabilities.TRANSPORT_WIFI)
        add(it, "addCapability", NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    private fun links(dns: String = "192.0.2.53") = LinkProperties().apply {
        interfaceName = "wifi_test"
        setLinkAddresses(listOf(ReflectionHelpers.callConstructor(LinkAddress::class.java, ClassParameter.from(String::class.java, "192.0.2.11/24"))))
        setDnsServers(listOf(InetAddress.getByName(dns)))
    }
    private fun snapshot(observer: AndroidUnderlayObserver) = JSONObject(ReflectionHelpers.callInstanceMethod<String>(observer, "snapshot"))

    @Test fun filteredNetworkReportsAFixedReasonWithoutAddressOrHandle() {
        val events = mutableListOf<DiagnosticEvent>()
        val observer = observer(events)
        val callback = callback(observer)
        val network = network()
        callback.onCapabilitiesChanged(network, wifi())
        callback.onLinkPropertiesChanged(network, links("2001:db8::53"))
        assertEquals(0, snapshot(observer).getJSONArray("networks").length())
        assertTrue("DNS/source family rejection must be diagnosable", events.any {
            it.stage == DiagnosticStage.DISCOVER && it.name == DiagnosticName.REJECTED && it.reason.name == "FAMILY_MISMATCH"
        })
        callback.onLinkPropertiesChanged(network, links())
        assertEquals(1, snapshot(observer).getJSONArray("networks").length())
        assertTrue(events.any { it.stage == DiagnosticStage.DISCOVER && it.accepted == 1 })
        assertFalse(events.toString().contains("192.0.2"))
        assertFalse(events.toString().contains("2001:db8"))
        assertFalse(events.toString().contains(network.networkHandle.toString()))
    }

    @Test fun failedProductionPublisherIsNotReportedAsSuccessful() {
        val events = Collections.synchronizedList(mutableListOf<DiagnosticEvent>())
        val delivered = CountDownLatch(1)
        val observer = AndroidUnderlayObserver(app, publish = { false }, diagnostic = {
            events.add(it)
            if (it.stage == DiagnosticStage.PUBLISH) delivered.countDown()
        })
        try {
            observer.start()
            val heartbeat = ReflectionHelpers.getField<Runnable>(observer, "heartbeat")
            heartbeat.run()
            assertTrue("the real publisher result was discarded", delivered.await(3, TimeUnit.SECONDS))
            val snapshot = synchronized(events) { events.toList() }
            assertTrue(snapshot.any { it.stage == DiagnosticStage.PUBLISH && it.result == DiagnosticResult.FAILURE })
            assertFalse(snapshot.any { it.stage == DiagnosticStage.PUBLISH && it.result == DiagnosticResult.SUCCESS })
        } finally { observer.stop() }
    }

    @Test fun unchangedHeartbeatsDoNotGenerateFreshDiscoveryEvents() {
        val events = mutableListOf<DiagnosticEvent>()
        val observer = observer(events)
        callback(observer).onCapabilitiesChanged(network(), wifi())
        callback(observer).onLinkPropertiesChanged(network(), links())
        snapshot(observer)
        val before = events.toList()
        repeat(50) { snapshot(observer) }
        assertEquals("observedMs-only heartbeat is not a new discovery", before, events)
    }

    @Test fun exactConfigurationJoinEmitsCountsWithoutRuntimeKeys() {
        shadowOf(app.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(listOf(subscription(101, 0, "000000000000001")))
        val events = mutableListOf<DiagnosticEvent>()
        val result = AndroidSubscriptionDiscovery(app, events::add).discover(listOf("000000000000001", "000000000000002"))
        assertTrue(result[0] is com.vowifi.sw.manager.model.SubscriptionDiscovery.Matched)
        assertTrue(result[1] is com.vowifi.sw.manager.model.SubscriptionDiscovery.Missing)
        val event = events.single()
        assertEquals(DiagnosticStage.CONFIG_MATCH, event.stage)
        assertEquals(DiagnosticReason.NOT_ACTIVE, event.reason)
        assertEquals(2, event.count)
        assertEquals(1, event.accepted)
        assertFalse(events.toString().contains("00000000000000"))
    }

    @Test fun everyFixedReasonAndNewStageRoundTripWithinTheExistingBudget() {
        for (reason in DiagnosticReason.entries) {
            val record = DiagnosticRecord(DiagnosticEvent(DiagnosticComponent.CONFIGURATION, DiagnosticLevel.WARN,
                DiagnosticName.REJECTED, DiagnosticStage.CONFIG_APPLY, DiagnosticResult.FAILURE, reason, slot = 0, count = 3),
                "f47ac10b-58cc-4372-a567-0e02b2c3d479", 123, 1_792_000_000_000, 42)
            assertTrue(record.bytes().size <= 2048)
            assertEquals(record, DiagnosticCodec.parse(record.bytes().decodeToString().trimEnd()))
            assertTrue(DiagnosticLabels.summary(record.event).isNotBlank())
        }
    }

    @Test fun identityPreparationFailureIsVisibleBeforeAnyRootCommand() = runBlocking {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, false)
        val events = mutableListOf<DiagnosticEvent>()
        val provisioner = AndroidConfigurationProvisioner(AndroidSubscriptionIdentityReader(app),
            AndroidConfigurationCommands(AndroidSuCommand(start = { fail("identity failure must not invoke root"); error("unreachable") })),
            diagnostic = events::add)
        assertTrue(provisioner.attempt("000000000000001") is ProvisionAttempt.Failed)
        assertTrue(events.any { it.stage == DiagnosticStage.IDENTITY && it.result == DiagnosticResult.FAILURE && it.reason == DiagnosticReason.PROFILE_UNSUPPORTED })
        assertFalse(events.toString().contains("000000000000001"))
    }

    @Test fun invalidSlotIsObservedWithoutChangingTheExistingProjection() {
        shadowOf(app.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(listOf(subscription(101, 7, "000000000000001")))
        val events = mutableListOf<DiagnosticEvent>()
        val result = AndroidSubscriptionInventory(app, events::add).listActive() as SubscriptionInventoryResult.Active
        assertEquals(7, result.subscriptions.single().slotIndex)
        val event = events.single { it.reason.name == "INVALID_SLOT" }
        assertNull(event.slot)
        assertEquals(DiagnosticStage.IDENTITY, event.stage)
    }
}
