package com.vowifi.sw.manager.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.telephony.SubscriptionManager
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject

/** Observes existing networks only. It never requests a cellular network or changes system settings. */
class AndroidUnderlayObserver(
    context: Context,
    private val publish: (String) -> Boolean = ::publishUnderlay,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(ConnectivityManager::class.java)
    private data class LinkState(val iface: String?, val addresses: List<String>, val dns: List<String>)
    private data class Entry(var capabilities: NetworkCapabilities? = null, var links: LinkState? = null, var blocked: Boolean = false)
    private val entries = LinkedHashMap<Network, Entry>()
    private var defaultHandle = 0L
    private var accessGeneration = 0L
    private var handler: Handler? = null
    private var thread: HandlerThread? = null
    private var scope: CoroutineScope? = null
    @Volatile private var updates: Channel<String>? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { entries.getOrPut(network) { Entry() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            entries.getOrPut(network) { Entry() }.capabilities = NetworkCapabilities(caps)
            emit()
        }
        override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) {
            entries.getOrPut(network) { Entry() }.links = LinkState(
                links.interfaceName,
                links.linkAddresses.map { it.address }.filter(::usableAddress).mapNotNull { it.hostAddress?.substringBefore('%') },
                links.dnsServers.filter(::usableAddress).mapNotNull { it.hostAddress?.substringBefore('%') },
            )
            emit()
        }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            entries.getOrPut(network) { Entry() }.blocked = blocked
            emit()
        }
        override fun onLost(network: Network) { entries.remove(network); emit() }
    }
    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { defaultHandle = network.networkHandle; emit() }
        override fun onLost(network: Network) {
            if (defaultHandle == network.networkHandle) { defaultHandle = 0; emit() }
        }
    }
    private val accessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_AIRPLANE_MODE_CHANGED) {
                accessGeneration++
                emit()
            }
        }
    }
    private val heartbeat = object : Runnable {
        override fun run() { emit(); handler?.postDelayed(this, 5_000) }
    }

    @Synchronized
    fun start() {
        if (handler != null || manager == null) return
        val worker = HandlerThread("vowifi-underlay").apply { start() }
        thread = worker
        val handler = Handler(worker.looper)
        this.handler = handler
        accessGeneration = now()
        val updates = Channel<String>(Channel.CONFLATED)
        this.updates = updates
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope ->
            scope.launch { for (state in updates) runInterruptible { publish(state) } }
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            manager.registerNetworkCallback(request, callback, handler)
            manager.registerDefaultNetworkCallback(defaultCallback, handler)
            appContext.registerReceiver(accessReceiver, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED),
                null, handler, Context.RECEIVER_NOT_EXPORTED)
            // API26+ sends capabilities and LinkProperties for already available
            // networks when registered. Do not race synchronous enumeration
            // against those initial callback messages.
            handler.post(heartbeat)
        } catch (_: SecurityException) {
            stop()
        }
    }

    @Synchronized
    fun stop() {
        val handler = handler ?: return
        this.handler = null
        try { manager?.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) { /* Not registered. */ }
        try { manager?.unregisterNetworkCallback(defaultCallback) } catch (_: IllegalArgumentException) { /* Not registered. */ }
        try { appContext.unregisterReceiver(accessReceiver) } catch (_: IllegalArgumentException) { /* Not registered. */ }
        handler.removeCallbacksAndMessages(null)
        updates?.close()
        scope?.cancel()
        thread?.quitSafely()
        // This handler never waits for root commands. Finish its callback work
        // before clearing and reusing the mutable network cache.
        if (thread !== Thread.currentThread()) {
            try { thread?.join(1_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        thread = null
        scope = null
        updates = null
        // If the host process disappears, the engine's monotonic freshness
        // boundary also invalidates the last publication within 15 seconds.
        entries.clear()
        defaultHandle = 0
    }

    private fun emit() { updates?.trySend(snapshot()) }

    private fun snapshot(): String {
        val networks = JSONArray()
        for ((network, entry) in entries) {
            val caps = entry.capabilities ?: continue
            val links = entry.links ?: continue
            if (entry.blocked || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) continue
            val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            if (wifi == cellular) continue
            val iface = links.iface?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,15}")) } ?: continue
            val addresses = links.addresses
            val dns = links.dns
            if (addresses.isEmpty() || dns.isEmpty() || addresses.size > 16 || dns.size > 8 ||
                addresses.none { local -> dns.any { server -> (':' in local) == (':' in server) } }
            ) continue
            val slots = if (cellular) caps.subscriptionIds.map(SubscriptionManager::getSlotIndex).filter { it in 0..1 }.distinct().sorted() else emptyList()
            if (networks.length() >= 16) break
            networks.put(JSONObject()
                .put("handle", network.networkHandle)
                .put("interface", iface)
                .put("transport", if (wifi) "wifi" else "cellular")
                .put("slots", JSONArray(slots))
                .put("validated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                .put("addresses", JSONArray(addresses))
                .put("dns", JSONArray(dns)))
        }
        return JSONObject().put("version", 1).put("observedMs", now())
            .put("airplaneMode", Settings.Global.getInt(appContext.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
            .put("accessGeneration", accessGeneration)
            .put("defaultHandle", defaultHandle).put("networks", networks).toString()
    }
}

private fun usableAddress(address: InetAddress): Boolean =
    !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress && !address.isLinkLocalAddress

private fun publishUnderlay(state: String): Boolean = try {
    val result = AndroidSuCommand(timeoutSeconds = 3, maxBytes = 1024).run(
        "/data/adb/modules/vowifi_sw/bin/vowifi-engine-arm64 network-update", state, 3,
    )
    !result.timedOut && !result.overflowed && !result.writeFailed && result.exitCode == 0
} catch (_: java.io.IOException) { false }
