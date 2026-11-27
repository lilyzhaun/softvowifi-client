package com.vowifi.sw.manager.model

/** Explicit user choices only; null leaves inherit. An empty group explicitly clears all choices. */
data class ConfigurationCallSignaling(
    val sessionTimer: ConfigurationSessionTimer = ConfigurationSessionTimer(),
    val reliableProvisional: ConfigurationReliableProvisional = ConfigurationReliableProvisional(),
    val preconditions: ConfigurationPreconditions = ConfigurationPreconditions(),
    val addressing: ConfigurationCallAddressing = ConfigurationCallAddressing(),
    val includePANI: Boolean? = null,
) {
    override fun toString(): String = "ConfigurationCallSignaling"
}

data class ConfigurationSessionTimer(
    val enabled: Boolean? = null,
    val expiresSeconds: Int? = null,
    val minimumSeconds: Int? = null,
    val refresher: String? = null,
    val refreshMethod: String? = null,
    val requirePeer: Boolean? = null,
) {
    override fun toString(): String = "ConfigurationSessionTimer"
}

data class ConfigurationReliableProvisional(
    val uacEnabled: Boolean? = null,
    val requirePeer: Boolean? = null,
    val uasMode: String? = null,
) {
    override fun toString(): String = "ConfigurationReliableProvisional"
}

data class ConfigurationPreconditions(
    val uacEnabled: Boolean? = null,
    val uasEnabled: Boolean? = null,
    val requirePeer: Boolean? = null,
    val wifiAllowed: Boolean? = null,
) {
    override fun toString(): String = "ConfigurationPreconditions"
}

data class ConfigurationCallAddressing(
    val requestURI: String? = null,
    val phoneContext: String? = null,
    val contactTransport: String? = null,
) {
    override fun toString(): String = "ConfigurationCallAddressing"
}
