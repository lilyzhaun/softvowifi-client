package com.vowifi.sw

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import com.vowifi.sw.manager.data.AndroidUnderlayObserver
import java.net.InetAddress
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import kotlinx.coroutines.channels.Channel

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UnderlayNetworkObserverTest {
    private fun observer() = AndroidUnderlayObserver(RuntimeEnvironment.getApplication(), publish = { fail("offline callback fixture must not run root commands"); false }, now = { 20_000 })
    private fun callback(observer: AndroidUnderlayObserver) = ReflectionHelpers.getField<ConnectivityManager.NetworkCallback>(observer, "callback")
    private fun snapshot(observer: AndroidUnderlayObserver) = JSONObject(ReflectionHelpers.callInstanceMethod<String>(observer, "snapshot"))
    private fun capsCall(caps: NetworkCapabilities, method: String, value: Int): NetworkCapabilities =
        ReflectionHelpers.callInstanceMethod(caps, method, ClassParameter.from(Int::class.javaPrimitiveType!!, value))
    private fun network(id: Int): Network = ReflectionHelpers.callConstructor(Network::class.java, ClassParameter.from(Int::class.javaPrimitiveType!!, id))
    private fun address(text: String): LinkAddress = ReflectionHelpers.callConstructor(LinkAddress::class.java, ClassParameter.from(String::class.java, text))
    private fun wifi() = NetworkCapabilities().also {
        capsCall(it, "addTransportType", NetworkCapabilities.TRANSPORT_WIFI)
        capsCall(it, "addCapability", NetworkCapabilities.NET_CAPABILITY_INTERNET)
        capsCall(it, "addCapability", NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        capsCall(it, "addCapability", NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    private fun links() = LinkProperties().apply {
        interfaceName = "wifi_test"
        setLinkAddresses(listOf(address("192.0.2.11/24")))
        setDnsServers(listOf(InetAddress.getByName("192.0.2.53")))
    }

    @Test fun incompleteOnAvailableDoesNotGuessLinkProperties() {
        val observer = observer()
        val callback = callback(observer)
        val network = network(101)
        callback.onAvailable(network)
        assertEquals(0, snapshot(observer).getJSONArray("networks").length())
        callback.onCapabilitiesChanged(network, wifi())
        assertEquals(0, snapshot(observer).getJSONArray("networks").length())
        callback.onLinkPropertiesChanged(network, links())
        val state = snapshot(observer)
        val selected = state.getJSONArray("networks").getJSONObject(0)
        assertEquals(network.networkHandle, selected.getLong("handle"))
        assertEquals("wifi_test", selected.getString("interface"))
        assertEquals("192.0.2.11", selected.getJSONArray("addresses").getString(0))
        assertEquals("192.0.2.53", selected.getJSONArray("dns").getString(0))
        assertEquals(20_000L, state.getLong("observedMs"))
        assertFalse(state.has("bootId"))
        assertFalse(state.toString().contains("imsi", ignoreCase = true))
    }

    @Test fun lostBlockedAndVpnNetworksNeverRetainBindingMetadata() {
        val observer = observer()
        val callback = callback(observer)
        val network = network(101)
        callback.onCapabilitiesChanged(network, wifi())
        callback.onLinkPropertiesChanged(network, links())
        assertEquals(1, snapshot(observer).getJSONArray("networks").length())
        callback.onBlockedStatusChanged(network, true)
        assertEquals(0, snapshot(observer).getJSONArray("networks").length())
        callback.onBlockedStatusChanged(network, false)
        callback.onCapabilitiesChanged(network, capsCall(wifi(), "addTransportType", NetworkCapabilities.TRANSPORT_VPN))
        assertEquals(0, snapshot(observer).getJSONArray("networks").length())
        callback.onCapabilitiesChanged(network, wifi())
        callback.onLost(network)
        assertEquals(0, snapshot(observer).getJSONArray("networks").length())
        callback.onAvailable(network(102))
        assertEquals(0, snapshot(observer).getJSONArray("networks").length())
    }

    @Test fun changedNetworkKeepsInterfaceAddressAndDnsTogether() {
        val observer = observer()
        val callback = callback(observer)
        val network = network(101)
        callback.onLinkPropertiesChanged(network, links())
        callback.onCapabilitiesChanged(network, wifi())
        val changed = LinkProperties().apply {
            interfaceName = "wifi_other"
            setLinkAddresses(listOf(address("2001:db8::11/64")))
            setDnsServers(listOf(InetAddress.getByName("2001:db8::53")))
        }
        callback.onLinkPropertiesChanged(network, changed)
        val selected = snapshot(observer).getJSONArray("networks").getJSONObject(0)
        assertEquals("wifi_other", selected.getString("interface"))
        assertTrue(selected.getJSONArray("addresses").getString(0).contains(':'))
        assertTrue(selected.getJSONArray("dns").getString(0).contains(':'))
    }

    @Test fun incompatibleDnsFamilyIsNotPublishedAsAUsableNetwork() {
        val observer = observer()
        val callback = callback(observer)
        val network = network(101)
        callback.onCapabilitiesChanged(network, wifi())
        callback.onLinkPropertiesChanged(network, links().apply { setDnsServers(listOf(InetAddress.getByName("2001:db8::53"))) })
        assertEquals(0, snapshot(observer).getJSONArray("networks").length())
    }

    @Test fun deliveredLinkPropertiesCannotMutatePreviouslyObservedState() {
        val observer = observer()
        val callback = callback(observer)
        val network = network(101)
        val links = links()
        callback.onCapabilitiesChanged(network, wifi())
        callback.onLinkPropertiesChanged(network, links)
        links.interfaceName = "other_iface"
        links.setDnsServers(listOf(InetAddress.getByName("198.51.100.53")))
        val selected = snapshot(observer).getJSONArray("networks").getJSONObject(0)
        assertEquals("wifi_test", selected.getString("interface"))
        assertEquals("192.0.2.53", selected.getJSONArray("dns").getString(0))
    }

    @Test fun flightChangesArePublishedWithoutChangingTheWifiNetwork() {
        val context = RuntimeEnvironment.getApplication()
        val observer = observer()
        val callback = callback(observer)
        val network = network(101)
        callback.onCapabilitiesChanged(network, wifi())
        callback.onLinkPropertiesChanged(network, links())
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        val initial = snapshot(observer)
        assertFalse(initial.getBoolean("airplaneMode"))
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        val flight = snapshot(observer)
        assertTrue(flight.getBoolean("airplaneMode"))
        assertEquals(initial.getJSONArray("networks").toString(), flight.getJSONArray("networks").toString())
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        assertFalse(snapshot(observer).getBoolean("airplaneMode"))
    }

    @Test fun flightBroadcastPublishesSettingsImmediatelyAndDoesNotTrustExtras() {
        val context = RuntimeEnvironment.getApplication()
        val observer = observer()
        val updates = Channel<String>(Channel.CONFLATED)
        ReflectionHelpers.setField(observer, "updates", updates)
        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(observer, "accessReceiver")
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        receiver.onReceive(context, Intent("synthetic.unrelated"))
        assertTrue(updates.tryReceive().isFailure)
        receiver.onReceive(context, Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", false))
        assertTrue(JSONObject(updates.tryReceive().getOrThrow()).getBoolean("airplaneMode"))
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        receiver.onReceive(context, Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", true))
        assertFalse(JSONObject(updates.tryReceive().getOrThrow()).getBoolean("airplaneMode"))
        updates.close()
    }

    @Test fun observerOwnsAndReleasesTheFlightReceiver() {
        val context = RuntimeEnvironment.getApplication()
        val observer = AndroidUnderlayObserver(context, publish = { true })
        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(observer, "accessReceiver")
        try {
            observer.start()
            assertTrue(shadowOf(context).registeredReceivers.any { it.broadcastReceiver === receiver })
        } finally {
            observer.stop()
        }
        assertFalse(shadowOf(context).registeredReceivers.any { it.broadcastReceiver === receiver })
        observer.stop()
    }

    @Test fun rapidReturnToSameModeStillPublishesANewAccessGeneration() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        val observer = observer()
        val before = snapshot(observer)
        val updates = Channel<String>(Channel.CONFLATED)
        ReflectionHelpers.setField(observer, "updates", updates)
        val receiver = ReflectionHelpers.getField<BroadcastReceiver>(observer, "accessReceiver")
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        receiver.onReceive(context, Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED))
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        receiver.onReceive(context, Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED))
        val after = JSONObject(updates.tryReceive().getOrThrow())
        assertEquals(before.getBoolean("airplaneMode"), after.getBoolean("airplaneMode"))
        assertTrue(after.getLong("accessGeneration") > before.getLong("accessGeneration"))
        assertEquals(before.getJSONArray("networks").toString(), after.getJSONArray("networks").toString())
        updates.close()
    }
}
