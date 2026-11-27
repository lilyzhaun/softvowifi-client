package com.vowifi.sw.manager.model

enum class ConfigurationImsSuite(val wire: String, val encryption: String, val integrity: String) {
    AES128_SHA1("esp-aes128-sha1-96", "AES-CBC 128 位", "HMAC-SHA1-96"),
    TDES_SHA1("esp-3des-sha1-96", "3DES", "HMAC-SHA1-96"),
    NULL_SHA1("esp-null-sha1-96", "ESP NULL", "HMAC-SHA1-96"),
    AES128_MD5("esp-aes128-md5-96", "AES-CBC 128 位", "HMAC-MD5-96"),
    TDES_MD5("esp-3des-md5-96", "3DES", "HMAC-MD5-96"),
    NULL_MD5("esp-null-md5-96", "ESP NULL", "HMAC-MD5-96"),
}

/** Missing wrapper is unknown. Null suites explicitly clears; empty is an incomplete draft. */
data class ConfigurationImsProtection(val suites: List<ConfigurationImsSuite>? = null) {
    override fun toString(): String = "ConfigurationImsProtection"
}

/** Requested/loaded group, not the authenticated server selection or a kernel readback. */
data class ConfigurationImsProtectionFacts(
    val known: Boolean,
    val source: String,
    val value: ConfigurationImsProtection? = null,
    val overrideValue: ConfigurationImsProtection? = null,
    val baseline: String? = null,
)
