package com.vowifi.sw.manager.model

/** Local policy facts, not a loadable configuration or a registration result. */
data class ConfigurationFacts(
    val configRevision: String?,
    val resolutionRevision: String,
    val fields: List<ConfigurationFact>,
    val callMedia: ConfigurationCallMediaFacts? = null,
    val tunnelSecurity: ConfigurationTunnelSecurityFacts? = null,
    val tunnelTiming: ConfigurationTunnelTimingFacts? = null,
    val imsProtection: ConfigurationImsProtectionFacts? = null,
    val epdgSelection: ConfigurationEpdgSelectionFacts? = null,
    val tunnelIdentity: ConfigurationTunnelIdentityFacts? = null,
    val imsTiming: ConfigurationImsTimingFacts? = null,
    val swuAccess: ConfigurationSwuAccessFacts? = null,
) {
    override fun toString(): String = "ConfigurationFacts"
}

data class ConfigurationFact(
    val field: String,
    val value: String?,
    val source: String,
    val overrideValue: String? = null,
) {
    override fun toString(): String = "ConfigurationFact"
}

data class ConfigurationRuntimeFacts(
    val loaded: ConfigurationFacts,
    val registrationSeconds: Long? = null,
    val integrity: String? = null,
    val encryption: String? = null,
    val call: ConfigurationCallFacts? = null,
    val tunnel: ConfigurationNegotiatedTunnelFacts? = null,
    val epdgSelection: ConfigurationEpdgConnection? = null,
    val identity: ConfigurationAuthenticatedIdentity? = null,
) {
    override fun toString(): String = "ConfigurationRuntimeFacts"
}

/** Captured call policy and accepted session interval are not future-call defaults. */
data class ConfigurationCallFacts(
    val configRevision: String,
    val sessionSeconds: Long? = null,
    val sessionRefresher: String? = null,
    val media: ConfigurationRuntimeMediaFacts? = null,
) {
    override fun toString(): String = "ConfigurationCallFacts"
}
