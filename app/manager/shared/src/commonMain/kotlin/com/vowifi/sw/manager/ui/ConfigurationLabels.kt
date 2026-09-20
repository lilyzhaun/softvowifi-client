package com.vowifi.sw.manager.ui

internal data class ConfigurationHeader(val title: String, val details: String?)

internal fun configurationHeader(label: String): ConfigurationHeader {
    val parts = label.split(" · ", limit = 3)
    return if (parts.size == 3 && parts[0] in setOf("SIM 1", "SIM 2")) {
        ConfigurationHeader("${parts[0]} · ${parts[1]}", parts[2])
    } else {
        ConfigurationHeader(label, null)
    }
}
