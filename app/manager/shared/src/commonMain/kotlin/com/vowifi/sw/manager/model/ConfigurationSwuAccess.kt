package com.vowifi.sw.manager.model

/** null policy inherits the compatible SWu request; null group preserves unread input. */
data class ConfigurationSwuAccess(val policy: ConfigurationSwuAccessPolicy? = null) {
    override fun toString(): String = "ConfigurationSwuAccess"
}

data class ConfigurationSwuAccessPolicy(
    val requestedApn: String,
    val interfaceMtu: Int,
    val purpose: String = "ims",
    val innerProtocol: ConfigurationSwuInnerProtocol? = null,
) {
    override fun toString(): String = "ConfigurationSwuAccessPolicy"
}

data class ConfigurationSwuInnerProtocol(val home: String, val roaming: String) {
    override fun toString(): String = "ConfigurationSwuInnerProtocol"
}

data class ConfigurationSwuAccessFacts(
    val known: Boolean,
    val source: String,
    val value: ConfigurationSwuAccess? = null,
    val overrideValue: ConfigurationSwuAccess? = null,
    val compatibilityBaseline: Boolean = false,
) {
    override fun toString(): String = "ConfigurationSwuAccessFacts"
}
