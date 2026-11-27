package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.serialization.json.*

/** Only numeric transforms supported by the maintained engine reach presentation. */
internal object ConfigurationNegotiatedTunnelCodec {
    fun decode(element: JsonElement?): ConfigurationNegotiatedTunnelFacts? {
        if (element == null || element == JsonNull) return null
        val obj = element as? JsonObject ?: invalid()
        if (obj.keys != setOf("ike", "child")) invalid()
        val ike = obj["ike"] as? JsonObject ?: invalid()
        val child = obj["child"] as? JsonObject ?: invalid()
        if (ike.keys != setOf("encryption", "keyBits", "integrity", "prf", "dh") ||
            child.keys != setOf("encryption", "keyBits", "integrity", "esn", "pfs")) invalid()
        val selectedIke = ConfigurationNegotiatedIke(
            ike.number("encryption", setOf(12)), ike.number("keyBits", setOf(128, 256)),
            ike.number("integrity", setOf(2, 12, 13, 14)), ike.number("prf", setOf(2, 4, 5, 6, 7)),
            ike.number("dh", setOf(2, 14)),
        )
        val encryption = child.number("encryption", setOf(12, 20))
        val selectedChild = ConfigurationNegotiatedChild(
            encryption, child.number("keyBits", setOf(128, 256)),
            child.number("integrity", if (encryption == 20) setOf(0) else setOf(2, 12, 13, 14)),
            (child["esn"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid(),
            child.number("pfs", setOf(0, 14)),
        )
        return ConfigurationNegotiatedTunnelFacts(selectedIke, selectedChild)
    }

    private fun JsonObject.number(key: String, allowed: Set<Int>): Int =
        (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in allowed } ?: invalid()

    private fun invalid(): Nothing = throw ConfigurationFormatException.Unparseable()
}
