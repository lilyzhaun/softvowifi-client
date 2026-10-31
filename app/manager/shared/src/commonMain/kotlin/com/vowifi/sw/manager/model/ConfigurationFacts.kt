package com.vowifi.sw.manager.model

/** Local policy facts, not a loadable configuration or a registration result. */
data class ConfigurationFacts(
    val configRevision: String?,
    val resolutionRevision: String,
    val fields: List<ConfigurationFact>,
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
) {
    override fun toString(): String = "ConfigurationRuntimeFacts"
}
