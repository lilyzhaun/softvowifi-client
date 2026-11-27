package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*

/** Mirrors the pinned SDK's duration and retry bounds; the engine revalidates before writing. */
internal object ConfigurationTunnelTimingCodec {
    const val MAX_SECONDS = Long.MAX_VALUE / 1_000_000_000
    const val MAX_MILLIS = Long.MAX_VALUE / 1_000_000

    fun read(facts: ConfigurationFacts?): ConfigurationTunnelTiming? = facts?.tunnelTiming?.takeIf { it.known }?.let {
        it.overrideValue ?: ConfigurationTunnelTiming()
    }

    fun decode(element: JsonElement): ConfigurationTunnelTiming {
        val obj = objectWith(element, "ike", "child", "reauthSeconds", "dpdSeconds", "natKeepaliveSeconds", "natDpdMarginSeconds", "retransmit")
        fun sa(key: String): ConfigurationSaTiming {
            val value = objectWith(obj[key], "softSeconds", "hardSeconds", "jitterSeconds")
            return ConfigurationSaTiming(value.number("softSeconds"), value.number("hardSeconds"), value.number("jitterSeconds"))
        }
        val retry = objectWith(obj["retransmit"], "retries", "initialMillis", "maximumMillis", "backoffFactor")
        val retries = retry.number("retries").takeIf { it in 0..32 } ?: invalid()
        val factor = (retry["backoffFactor"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: invalid()
        val policy = ConfigurationTunnelTimingPolicy(sa("ike"), sa("child"), obj.number("reauthSeconds"), obj.number("dpdSeconds"),
            obj.number("natKeepaliveSeconds"), obj.number("natDpdMarginSeconds"),
            ConfigurationTunnelRetry(retries.toInt(), retry.number("initialMillis"), retry.number("maximumMillis"), factor))
        validate(policy)
        return ConfigurationTunnelTiming(policy)
    }

    fun validate(policy: ConfigurationTunnelTimingPolicy) {
        fun duration(value: Long?, unit: Long): Long {
            if (value == null || value < 0 || value > Long.MAX_VALUE / unit) invalid()
            return value * unit
        }
        for (sa in listOf(policy.ike, policy.child)) {
            val soft = duration(sa.softSeconds, 1_000_000_000)
            val hard = duration(sa.hardSeconds, 1_000_000_000)
            val jitter = duration(sa.jitterSeconds, 1_000_000_000)
            if (hard <= soft || jitter >= soft || soft - jitter < 30_000_000_000) invalid()
        }
        duration(policy.reauthSeconds, 1_000_000_000)
        duration(policy.dpdSeconds, 1_000_000_000)
        val nat = duration(policy.natKeepaliveSeconds, 1_000_000_000)
        val margin = duration(policy.natDpdMarginSeconds, 1_000_000_000)
        if (nat > Long.MAX_VALUE - margin) invalid()
        val retry = policy.retransmit
        val retries = retry.retries?.takeIf { it in 0..32 } ?: invalid()
        val initial = duration(retry.initialMillis, 1_000_000)
        val maximum = duration(retry.maximumMillis, 1_000_000)
        val factor = retry.backoffFactor?.takeIf { it.isFinite() && it >= 1 } ?: invalid()
        if (initial == 0L || maximum > 0 && maximum < initial) invalid()
        var delay = initial
        var total = initial
        repeat(retries) {
            var next = delay.toDouble() * factor
            if (maximum > 0) next = minOf(next, maximum.toDouble())
            if (next >= Long.MAX_VALUE.toDouble()) invalid()
            delay = next.toLong()
            if (delay > Long.MAX_VALUE - total) invalid()
            total += delay
        }
    }

    fun encode(value: ConfigurationTunnelTiming): JsonElement {
        val policy = value.policy ?: return JsonNull
        validate(policy)
        fun sa(value: ConfigurationSaTiming) = buildJsonObject {
            put("softSeconds", value.softSeconds!!); put("hardSeconds", value.hardSeconds!!); put("jitterSeconds", value.jitterSeconds!!)
        }
        return buildJsonObject {
            put("ike", sa(policy.ike)); put("child", sa(policy.child))
            put("reauthSeconds", policy.reauthSeconds!!); put("dpdSeconds", policy.dpdSeconds!!)
            put("natKeepaliveSeconds", policy.natKeepaliveSeconds!!); put("natDpdMarginSeconds", policy.natDpdMarginSeconds!!)
            put("retransmit", buildJsonObject {
                put("retries", policy.retransmit.retries!!); put("initialMillis", policy.retransmit.initialMillis!!)
                put("maximumMillis", policy.retransmit.maximumMillis!!); put("backoffFactor", policy.retransmit.backoffFactor!!)
            })
        }
    }

    fun facts(element: JsonElement?): ConfigurationTunnelTimingFacts? {
        if (element == null) return null
        val obj = element as? JsonObject ?: invalid()
        val source = obj.string("source")?.takeIf { it in setOf("user", "catalog", "default") } ?: invalid()
        if (obj.string("state") == "unknown") {
            if (obj.keys != setOf("state", "source")) invalid()
            return ConfigurationTunnelTimingFacts(false, source)
        }
        if (obj.string("state") != "known") invalid()
        if (source == "default") {
            if (obj.keys != setOf("state", "source", "baseline") || obj.string("baseline") != "swu-renewal-compatible-v1") invalid()
            return ConfigurationTunnelTimingFacts(true, source, compatibilityBaseline = true)
        }
        if (source == "catalog") {
            if (obj.keys != setOf("state", "source", "value")) invalid()
            return ConfigurationTunnelTimingFacts(true, source, decode(obj.getValue("value")))
        }
        if (obj.keys != setOf("state", "source", "value", "override")) invalid()
        val value = decode(obj.getValue("value"))
        val override = decode(obj.getValue("override"))
        if (value != override) invalid()
        return ConfigurationTunnelTimingFacts(true, source, value, override)
    }

    private fun objectWith(value: JsonElement?, vararg keys: String): JsonObject =
        (value as? JsonObject)?.takeIf { it.keys == keys.toSet() } ?: invalid()
    private fun JsonObject.number(key: String): Long = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: invalid()
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun invalid(): Nothing = throw ConfigurationFormatException.InvalidTunnelTiming()
}
