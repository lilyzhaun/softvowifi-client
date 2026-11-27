package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*

internal object ConfigurationImsTimingCodec {
    const val MAX_SECONDS = Long.MAX_VALUE / 1_000_000_000
    const val MAX_MILLIS = Long.MAX_VALUE / 1_000_000

    fun read(facts: ConfigurationFacts?): ConfigurationImsTiming? = facts?.imsTiming?.takeIf { it.known }?.let {
        it.overrideValue ?: ConfigurationImsTiming()
    }

    fun decode(element: JsonElement): ConfigurationImsTiming {
        val obj = objectWith(element, "transaction", "registration", "recovery")
        val tx = objectWith(obj["transaction"], "t1Millis", "t2Millis", "t4Millis", "bMillis", "dMillis", "fMillis", "hMillis", "jMillis")
        val reg = objectWith(obj["registration"], "requestedSeconds", "refreshPercent")
        val retry = objectWith(obj["recovery"], "initialSeconds", "maximumSeconds", "unavailableSeconds")
        val policy = ConfigurationImsTimingPolicy(
            ConfigurationSipTransactionTiming(tx.number("t1Millis"), tx.number("t2Millis"), tx.number("t4Millis"),
                tx.number("bMillis"), tx.number("dMillis"), tx.number("fMillis"), tx.number("hMillis"), tx.number("jMillis")),
            ConfigurationImsRegistrationTiming(reg.number("requestedSeconds"), reg.number("refreshPercent")),
            ConfigurationImsRecoveryTiming(retry.number("initialSeconds"), retry.number("maximumSeconds"), retry.number("unavailableSeconds")))
        validate(policy)
        return ConfigurationImsTiming(policy)
    }

    fun validate(policy: ConfigurationImsTimingPolicy) {
        fun bounded(value: Long?, minimum: Long, maximum: Long): Long = value?.takeIf { it in minimum..maximum } ?: invalid()
        val tx = policy.transaction
        val t1 = bounded(tx.t1Millis, 1, MAX_MILLIS / 64)
        bounded(tx.t2Millis, t1, MAX_MILLIS); bounded(tx.t4Millis, 1, MAX_MILLIS)
        bounded(tx.bMillis, 1, MAX_MILLIS); bounded(tx.dMillis, 32000, MAX_MILLIS)
        bounded(tx.fMillis, 1, MAX_MILLIS / 2); bounded(tx.hMillis, 1, MAX_MILLIS); bounded(tx.jMillis, 1, MAX_MILLIS)
        bounded(policy.registration.requestedSeconds, 1, Int.MAX_VALUE.toLong())
        bounded(policy.registration.refreshPercent, 1, 99)
        val initial = bounded(policy.recovery.initialSeconds, 1, MAX_SECONDS)
        val maximum = bounded(policy.recovery.maximumSeconds, initial, MAX_SECONDS)
        if (initial <= MAX_SECONDS / 64 && maximum > initial * 64) invalid()
        bounded(policy.recovery.unavailableSeconds, 0, MAX_SECONDS - maxOf(maximum, 4_294_967_295L))
    }

    fun encode(value: ConfigurationImsTiming): JsonElement {
        val p = value.policy ?: return JsonNull
        validate(p)
        return buildJsonObject {
            put("transaction", buildJsonObject {
                put("t1Millis", p.transaction.t1Millis!!); put("t2Millis", p.transaction.t2Millis!!); put("t4Millis", p.transaction.t4Millis!!)
                put("bMillis", p.transaction.bMillis!!); put("dMillis", p.transaction.dMillis!!); put("fMillis", p.transaction.fMillis!!)
                put("hMillis", p.transaction.hMillis!!); put("jMillis", p.transaction.jMillis!!)
            })
            put("registration", buildJsonObject {
                put("requestedSeconds", p.registration.requestedSeconds!!); put("refreshPercent", p.registration.refreshPercent!!)
            })
            put("recovery", buildJsonObject {
                put("initialSeconds", p.recovery.initialSeconds!!); put("maximumSeconds", p.recovery.maximumSeconds!!)
                put("unavailableSeconds", p.recovery.unavailableSeconds!!)
            })
        }
    }

    fun facts(element: JsonElement?): ConfigurationImsTimingFacts? {
        if (element == null) return null
        val obj = element as? JsonObject ?: invalid()
        val source = obj.string("source")?.takeIf { it in setOf("user", "catalog", "default") } ?: invalid()
        if (obj.string("state") == "unknown") {
            if (obj.keys != setOf("state", "source")) invalid()
            return ConfigurationImsTimingFacts(false, source)
        }
        if (obj.string("state") != "known") invalid()
        if (source == "default") {
            if (obj.keys != setOf("state", "source", "baseline") || obj.string("baseline") != "ims-timing-compatible-v1") invalid()
            return ConfigurationImsTimingFacts(true, source, compatibilityBaseline = true)
        }
        val keys = if (source == "catalog") setOf("state", "source", "value") else setOf("state", "source", "value", "override")
        if (obj.keys != keys) invalid()
        val value = decode(obj.getValue("value"))
        val override = if (source == "catalog") null else decode(obj.getValue("override"))
        if (source == "user" && value != override) invalid()
        return ConfigurationImsTimingFacts(true, source, value, override)
    }

    private fun objectWith(element: JsonElement?, vararg keys: String): JsonObject =
        (element as? JsonObject)?.takeIf { it.keys == keys.toSet() } ?: invalid()
    private fun JsonObject.number(key: String): Long = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: invalid()
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun invalid(): Nothing = throw ConfigurationFormatException.InvalidImsTiming()
}
