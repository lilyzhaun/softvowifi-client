package com.vowifi.sw.manager.model

/** Negotiated running scalars, not a future offer or proof of audio playback. */
data class ConfigurationRuntimeMediaFacts(
    val epoch: String,
    val revision: Long,
    val codec: ConfigurationSpeechCodec,
    val framing: ConfigurationSpeechFraming,
    val clockRate: Int,
    val payloadType: Int,
    val receivePayloadType: Int,
    val sendPtimeMs: Int,
    val receiveMaxPtimeMs: Int,
    val direction: String,
    val inactivityTimeoutMs: Int,
    val quality: ConfigurationTransportQuality,
    val security: ConfigurationRuntimeMediaSecurity? = null,
) {
    override fun toString(): String = "ConfigurationRuntimeMediaFacts"
}

data class ConfigurationRuntimeMediaSecurity(val keying: String, val suite: ConfigurationSrtpSuite? = null) {
    override fun toString(): String = "ConfigurationRuntimeMediaSecurity"
}

/** Missing directions are unmeasured; revision counts reports/revocations, not health. */
data class ConfigurationTransportQuality(
    val revision: Long,
    val receive: ConfigurationTransportQualitySample? = null,
    val send: ConfigurationTransportQualitySample? = null,
) {
    override fun toString(): String = "ConfigurationTransportQuality"
}

data class ConfigurationTransportQualitySample(val fractionLost: Int, val jitterMs: Int, val totalLost: Int) {
    override fun toString(): String = "ConfigurationTransportQualitySample"
}
