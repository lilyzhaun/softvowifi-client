package com.vowifi.sw.manager.model

enum class ConfigurationIkeIdType(val wire: String, val code: Int) {
    RFC822("rfc822", 3), FQDN("fqdn", 2), KEY_ID("key-id", 11), IPV4("ipv4", 1), IPV6("ipv6", 5),
}

data class ConfigurationPeerIdentity(val type: ConfigurationIkeIdType, val value: String) {
    override fun toString(): String = "ConfigurationPeerIdentity"
}

data class ConfigurationTunnelIdentityPolicy(
    val localType: ConfigurationIkeIdType = ConfigurationIkeIdType.RFC822,
    val requestType: ConfigurationIkeIdType = ConfigurationIkeIdType.FQDN,
    val peer: ConfigurationPeerIdentity? = null,
) {
    override fun toString(): String = "ConfigurationTunnelIdentityPolicy"
}

/** Null wrapper preserves an unread group; empty wrapper explicitly clears it. */
data class ConfigurationTunnelIdentity(val policy: ConfigurationTunnelIdentityPolicy? = null) {
    override fun toString(): String = "ConfigurationTunnelIdentity"
}

data class ConfigurationTunnelIdentityTypes(
    val localType: ConfigurationIkeIdType,
    val requestType: ConfigurationIkeIdType,
    val peerType: ConfigurationIkeIdType? = null,
)

data class ConfigurationTunnelIdentityFacts(
    val known: Boolean,
    val source: String,
    val value: ConfigurationTunnelIdentityTypes? = null,
    val compatibilityBaseline: Boolean = false,
)

data class ConfigurationAuthenticatedIdentity(
    val localType: ConfigurationIkeIdType,
    val requestType: ConfigurationIkeIdType,
    // An observed IANA type is independent of the editable constraint choices.
    val peerType: Int,
    val peerConstrained: Boolean,
)
