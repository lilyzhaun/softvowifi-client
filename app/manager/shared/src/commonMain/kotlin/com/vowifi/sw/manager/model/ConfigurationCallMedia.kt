package com.vowifi.sw.manager.model

enum class ConfigurationSpeechCodec(val wire: String, val maxMode: Int) {
    AMR_NB("amr-nb", 7), AMR_WB("amr-wb", 8), PCMU("pcmu", -1),
}

enum class ConfigurationSpeechFraming(val wire: String) { BE("be"), OA("oa"), SAMPLE("sample") }

data class ConfigurationSpeechFormat(
    val codec: ConfigurationSpeechCodec,
    val framing: ConfigurationSpeechFraming,
    val modeSet: List<Int>? = null,
    val initialMode: Int? = null,
    val payloadType: Int? = null,
    val receiveModeChangePeriod: Int? = null,
    val receiveModeChangeNeighbor: Boolean? = null,
) {
    override fun toString(): String = "ConfigurationSpeechFormat"
}

data class ConfigurationCallPacketization(val receivePtimeMs: Int, val receiveMaxPtimeMs: Int) {
    override fun toString(): String = "ConfigurationCallPacketization"
}

data class ConfigurationCallInactivity(val timeoutMs: Int) {
    override fun toString(): String = "ConfigurationCallInactivity"
}

enum class ConfigurationSrtpSuite(val wire: String) {
    AES_CM_128("AES_CM_128_HMAC_SHA1_80"), AES_GCM_128("AEAD_AES_128_GCM"), AES_GCM_256("AEAD_AES_256_GCM"),
}

data class ConfigurationCallMediaSecurity(val keying: String, val suites: List<ConfigurationSrtpSuite>) {
    override fun toString(): String = "ConfigurationCallMediaSecurity"
}

/** Empty is an explicit restore-inheritance action; null at a caller is unknown. */
data class ConfigurationCallMedia(
    val speechFormats: List<ConfigurationSpeechFormat> = emptyList(),
    val packetization: ConfigurationCallPacketization? = null,
    val inactivity: ConfigurationCallInactivity? = null,
    val security: ConfigurationCallMediaSecurity? = null,
) {
    override fun toString(): String = "ConfigurationCallMedia"
}

data class ConfigurationCallMediaFacts(
    val known: Boolean,
    val source: String,
    val compatibilityBaseline: Boolean = false,
    val value: ConfigurationCallMedia? = null,
    val overrideValue: ConfigurationCallMedia? = null,
) {
    override fun toString(): String = "ConfigurationCallMediaFacts"
}
