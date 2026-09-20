package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException

internal fun digestRealmValue(value: String): String {
    if (containsIdentityDigits(value)) throw ConfigurationFormatException.IdentityLeak()
    if (value.isEmpty()) return value
    val hostname = value.removeSuffix(".")
    if (hostname.length !in 1..253 || hostname.split('.').any { !DIGEST_HOST_LABEL.matches(it) }) {
        throw ConfigurationFormatException.InvalidDigestRealm()
    }
    return value
}

private val DIGEST_HOST_LABEL = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
