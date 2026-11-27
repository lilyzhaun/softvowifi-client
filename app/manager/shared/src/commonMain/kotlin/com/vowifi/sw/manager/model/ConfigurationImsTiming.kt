package com.vowifi.sw.manager.model

/** Nullable leaves are incomplete draft input, never inheritance or a clear. */
data class ConfigurationSipTransactionTiming(
    val t1Millis: Long? = 2000, val t2Millis: Long? = 16000, val t4Millis: Long? = 17000,
    val bMillis: Long? = 128000, val dMillis: Long? = 32000, val fMillis: Long? = 128000,
    val hMillis: Long? = 128000, val jMillis: Long? = 128000,
) { override fun toString(): String = "ConfigurationSipTransactionTiming" }

data class ConfigurationImsRegistrationTiming(val requestedSeconds: Long? = 3600, val refreshPercent: Long? = 50) {
    override fun toString(): String = "ConfigurationImsRegistrationTiming"
}

data class ConfigurationImsRecoveryTiming(
    val initialSeconds: Long? = 30, val maximumSeconds: Long? = 1800, val unavailableSeconds: Long? = 300,
) { override fun toString(): String = "ConfigurationImsRecoveryTiming" }

/** Seed values come from existing engine consumers; an explicit group is next-start. */
data class ConfigurationImsTimingPolicy(
    val transaction: ConfigurationSipTransactionTiming = ConfigurationSipTransactionTiming(),
    val registration: ConfigurationImsRegistrationTiming = ConfigurationImsRegistrationTiming(),
    val recovery: ConfigurationImsRecoveryTiming = ConfigurationImsRecoveryTiming(),
) { override fun toString(): String = "ConfigurationImsTimingPolicy" }

/** Null wrapper means unknown; a wrapper without policy explicitly clears. */
data class ConfigurationImsTiming(val policy: ConfigurationImsTimingPolicy? = null) {
    override fun toString(): String = "ConfigurationImsTiming"
}

data class ConfigurationImsTimingFacts(
    val known: Boolean, val source: String,
    val value: ConfigurationImsTiming? = null, val overrideValue: ConfigurationImsTiming? = null,
    val compatibilityBaseline: Boolean = false,
)
