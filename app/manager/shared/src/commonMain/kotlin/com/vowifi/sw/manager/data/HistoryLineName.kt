package com.vowifi.sw.manager.data

internal class HistoryLineName private constructor(val carrier: String, val lastFour: String?, val country: String?) {
    override fun toString(): String = "HistoryLineName"
    companion object {
        fun parse(carrier: String?, lastFour: String?, country: String? = null) = HistoryLineName(homeCarrierLabel(carrier),
            lastFour?.takeIf { it.length == 4 && it.all { digit -> digit in '0'..'9' } },
            country?.uppercase()?.takeIf { it.length == 2 && it.all { letter -> letter in 'A'..'Z' } })
    }
}

/** Regional-indicator flag for a two-letter ISO country code; null for anything else. */
internal fun countryFlagEmoji(iso2: String?): String? {
    if (iso2 == null || iso2.length != 2 || iso2.any { it !in 'A'..'Z' }) return null
    return buildString {
        iso2.forEach { letter ->
            append('\uD83C')
            append((0xDDE6 + (letter - 'A')).toChar())
        }
    }
}

// The same operator name in different home countries (e.g. 3 in GB and HK) is not a naming conflict.
internal fun historyLineTitles(names: Map<SimOwnerId, HistoryLineName>): Map<SimOwnerId, String> {
    val counts = names.values.groupingBy { it.carrier.lowercase() to it.country }.eachCount()
    return names.mapValues { (_, name) ->
        val base = listOfNotNull(countryFlagEmoji(name.country), name.carrier).joinToString(" ")
        if (counts.getValue(name.carrier.lowercase() to name.country) > 1 || name.carrier == homeCarrierLabel(null)) {
            name.lastFour?.let { "$base · •••• $it" } ?: base
        } else base
    }
}
