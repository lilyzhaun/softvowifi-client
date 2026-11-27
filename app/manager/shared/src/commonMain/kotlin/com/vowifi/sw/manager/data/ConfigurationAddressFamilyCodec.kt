package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFact
import com.vowifi.sw.manager.model.ConfigurationFacts
import com.vowifi.sw.manager.model.ConfigurationFormatException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

internal object ConfigurationAddressFamilyCodec {
    const val FIELD = "epdgAddressFamily"
    val values = setOf("prefer-ipv4", "prefer-ipv6", "ipv4-only", "ipv6-only")

    fun read(facts: ConfigurationFacts?): String? = facts?.fields?.firstOrNull { it.field == FIELD }?.let {
        if (it.value == null) null else it.overrideValue ?: ""
    }

    fun encode(value: String): JsonElement = when {
        value.isEmpty() -> JsonNull
        value in values -> JsonPrimitive(value)
        else -> throw ConfigurationFormatException.InvalidAddressFamily()
    }

    /** The facts IPC boundary validates both the value and its authority. */
    fun validateFact(fact: ConfigurationFact) {
        if (fact.source !in setOf("user", "default")) throw ConfigurationFormatException.InvalidAddressFamily()
        val value = fact.value ?: return
        val valid = if (fact.source == "default") value == "compatible" && fact.overrideValue == null
            else value in values && fact.overrideValue == value
        if (!valid) throw ConfigurationFormatException.InvalidAddressFamily()
    }
}
