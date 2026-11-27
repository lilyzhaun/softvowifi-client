package com.vowifi.sw.manager.model

enum class ConfigurationIkeSuite(
    val wire: String, val keyBits: Int, val integrity: String, val prf: String, val dhGroup: Int,
) {
    CBC128_SHA256_DH14("ike-cbc128-sha256-prfsha256-dh14", 128, "HMAC-SHA2-256-128", "HMAC-SHA2-256", 14),
    CBC256_SHA384_DH14("ike-cbc256-sha384-prfsha384-dh14", 256, "HMAC-SHA2-384-192", "HMAC-SHA2-384", 14),
    CBC128_SHA1_DH14("ike-cbc128-sha1-prfsha1-dh14", 128, "HMAC-SHA1-96", "HMAC-SHA1", 14),
    CBC256_SHA256_DH14("ike-cbc256-sha256-prfsha256-dh14", 256, "HMAC-SHA2-256-128", "HMAC-SHA2-256", 14),
    CBC128_SHA256_DH2("ike-cbc128-sha256-prfsha256-dh2", 128, "HMAC-SHA2-256-128", "HMAC-SHA2-256", 2),
    CBC256_SHA384_DH2("ike-cbc256-sha384-prfsha384-dh2", 256, "HMAC-SHA2-384-192", "HMAC-SHA2-384", 2),
    CBC128_SHA1_DH2("ike-cbc128-sha1-prfsha1-dh2", 128, "HMAC-SHA1-96", "HMAC-SHA1", 2),
    CBC256_SHA256_DH2("ike-cbc256-sha256-prfsha256-dh2", 256, "HMAC-SHA2-256-128", "HMAC-SHA2-256", 2),
}

enum class ConfigurationChildSuite(val wire: String, val encryption: String, val keyBits: Int, val integrity: String, val esn: Boolean = false) {
    GCM256("esp-gcm256-noesn", "AES-GCM", 256, "AEAD · 128 位标签"),
    GCM128("esp-gcm128-noesn", "AES-GCM", 128, "AEAD · 128 位标签"),
    CBC256_SHA256("esp-cbc256-sha256-noesn", "AES-CBC", 256, "HMAC-SHA2-256-128"),
    CBC128_SHA256("esp-cbc128-sha256-noesn", "AES-CBC", 128, "HMAC-SHA2-256-128"),
    CBC128_SHA1("esp-cbc128-sha1-noesn", "AES-CBC", 128, "HMAC-SHA1-96"),
    CBC256_SHA512("esp-cbc256-sha512-noesn", "AES-CBC", 256, "HMAC-SHA2-512-256"),
    GCM256_ESN("esp-gcm256-esn", "AES-GCM", 256, "AEAD · 128 位标签", true),
    GCM128_ESN("esp-gcm128-esn", "AES-GCM", 128, "AEAD · 128 位标签", true),
    CBC256_SHA256_ESN("esp-cbc256-sha256-esn", "AES-CBC", 256, "HMAC-SHA2-256-128", true),
    CBC128_SHA256_ESN("esp-cbc128-sha256-esn", "AES-CBC", 128, "HMAC-SHA2-256-128", true),
    CBC128_SHA1_ESN("esp-cbc128-sha1-esn", "AES-CBC", 128, "HMAC-SHA1-96", true),
    CBC256_SHA512_ESN("esp-cbc256-sha512-esn", "AES-CBC", 256, "HMAC-SHA2-512-256", true),
}

enum class ConfigurationTunnelLayout(val wire: String) {
    MULTIPLE_COMPLETE("multiple-complete"),
    SINGLE_COMBINED("single-combined"),
}

/** Empty lists are an incomplete editor draft, never an implicit clear. */
data class ConfigurationTunnelPolicy(
    val ikeSuites: List<ConfigurationIkeSuite> = emptyList(),
    val childSuites: List<ConfigurationChildSuite> = emptyList(),
    val layout: ConfigurationTunnelLayout = ConfigurationTunnelLayout.MULTIPLE_COMPLETE,
    /** Additional DH for Child rekey, not the initial IKE_AUTH Child. */
    val childPFSGroup: Int = 0,
)

/** null at the containing model means unknown; this empty wrapper explicitly clears. */
data class ConfigurationTunnelSecurity(val policy: ConfigurationTunnelPolicy? = null) {
    override fun toString(): String = "ConfigurationTunnelSecurity"
}

/** Requested/loaded configuration, not the peer's negotiated algorithms. */
data class ConfigurationTunnelSecurityFacts(
    val known: Boolean,
    val source: String,
    val value: ConfigurationTunnelSecurity? = null,
    val overrideValue: ConfigurationTunnelSecurity? = null,
    val compatibilityBaseline: Boolean = false,
)
