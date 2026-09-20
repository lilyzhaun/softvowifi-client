package com.vowifi.sw.manager.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet6Address
import java.net.InetAddress

internal fun interface NetworkDnsLookup {
    fun resolve(): DnsEndpoint?
}

internal class AndroidNetworkDnsSource(
    context: Context,
) : NetworkDnsLookup {
    private val context = context.applicationContext

    override fun resolve(): DnsEndpoint? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        for (network in candidateNetworks(manager)) {
            val servers = manager.getLinkProperties(network)?.dnsServers.orEmpty()
            for (address in servers) {
                endpointFor(address)?.let { return it }
            }
        }
        return null
    }
}

private fun candidateNetworks(manager: ConnectivityManager): List<Network> {
    val ordered = ArrayList<Network>()
    val seen = HashSet<Network>()
    fun add(network: Network) {
        if (seen.add(network)) ordered += network
    }
    manager.activeNetwork?.let(::add)
    val validatedHandles = ArrayList<Long>()
    val unvalidatedHandles = ArrayList<Long>()
    val byHandle = HashMap<Long, Network>()
    @Suppress("DEPRECATION")
    for (network in manager.allNetworks) {
        val caps = manager.getNetworkCapabilities(network) ?: continue
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
        val handle = network.networkHandle
        if (byHandle.putIfAbsent(handle, network) != null) continue
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            validatedHandles += handle
        } else {
            unvalidatedHandles += handle
        }
    }
    for (handle in orderedWifiFallbackHandles(validatedHandles, unvalidatedHandles)) {
        byHandle[handle]?.let(::add)
    }
    return ordered
}

private fun endpointFor(address: InetAddress): DnsEndpoint? {
    if (address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isMulticastAddress ||
        address.isLinkLocalAddress
    ) {
        return null
    }
    val host = address.hostAddress?.substringBefore('%')?.takeIf(String::isNotBlank) ?: return null
    return if (address is Inet6Address) DnsEndpoint.v6(host) else DnsEndpoint.v4(host)
}
