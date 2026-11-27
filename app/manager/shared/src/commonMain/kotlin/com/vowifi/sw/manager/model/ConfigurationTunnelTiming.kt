package com.vowifi.sw.manager.model

/** Null leaves are incomplete editor input, never a default or a group clear. */
data class ConfigurationSaTiming(
    val softSeconds: Long? = null,
    val hardSeconds: Long? = null,
    val jitterSeconds: Long? = null,
) {
    override fun toString(): String = "ConfigurationSaTiming"
}

data class ConfigurationTunnelRetry(
    val retries: Int? = 5,
    val initialMillis: Long? = 4000,
    val maximumMillis: Long? = 0,
    val backoffFactor: Double? = 1.8,
) {
    override fun toString(): String = "ConfigurationTunnelRetry"
}

/** Only known compatibility values seed a new draft; hard lifetimes must be chosen. */
data class ConfigurationTunnelTimingPolicy(
    val ike: ConfigurationSaTiming = ConfigurationSaTiming(1500, null, 30),
    val child: ConfigurationSaTiming = ConfigurationSaTiming(1800, null, 30),
    val reauthSeconds: Long? = 0,
    val dpdSeconds: Long? = 20,
    val natKeepaliveSeconds: Long? = 20,
    val natDpdMarginSeconds: Long? = 150,
    val retransmit: ConfigurationTunnelRetry = ConfigurationTunnelRetry(),
) {
    override fun toString(): String = "ConfigurationTunnelTimingPolicy"
}

/** A missing wrapper is unknown; an empty wrapper explicitly clears this group. */
data class ConfigurationTunnelTiming(val policy: ConfigurationTunnelTimingPolicy? = null) {
    override fun toString(): String = "ConfigurationTunnelTiming"
}

/** Configured/loaded timing, not negotiated lifetimes or elapsed runtime clocks. */
data class ConfigurationTunnelTimingFacts(
    val known: Boolean,
    val source: String,
    val value: ConfigurationTunnelTiming? = null,
    val overrideValue: ConfigurationTunnelTiming? = null,
    val compatibilityBaseline: Boolean = false,
)
