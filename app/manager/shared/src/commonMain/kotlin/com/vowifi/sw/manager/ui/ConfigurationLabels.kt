package com.vowifi.sw.manager.ui

internal data class ConfigurationHeader(val title: String, val details: String?)

internal fun configurationHeader(label: String): ConfigurationHeader {
    val parts = label.split(" · ")
    val details = parts.indices.drop(2).firstOrNull { CONFIGURATION_IDENTITY_DETAIL.matches(parts[it]) }
    return if (details != null && parts[0] in (1..8).map { "SIM $it" }) {
        ConfigurationHeader(parts.take(details).joinToString(" · "), parts.drop(details).joinToString(" · "))
    } else {
        ConfigurationHeader(label, null)
    }
}

private val CONFIGURATION_IDENTITY_DETAIL = Regex("(?:[0-9]{3} */ *[0-9]{2,3}|•••• [0-9]{4})")
