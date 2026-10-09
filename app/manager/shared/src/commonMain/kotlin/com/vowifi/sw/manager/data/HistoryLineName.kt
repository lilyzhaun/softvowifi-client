package com.vowifi.sw.manager.data

internal class HistoryLineName private constructor(val carrier: String, val lastFour: String?) {
    override fun toString(): String = "HistoryLineName"
    companion object {
        fun parse(carrier: String?, lastFour: String?) = HistoryLineName(homeCarrierLabel(carrier),
            lastFour?.takeIf { it.length == 4 && it.all { digit -> digit in '0'..'9' } })
    }
}

internal fun historyLineTitles(names: Map<SimOwnerId, HistoryLineName>): Map<SimOwnerId, String> {
    val counts = names.values.groupingBy { it.carrier.lowercase() }.eachCount()
    return names.mapValues { (_, name) ->
        if (counts.getValue(name.carrier.lowercase()) > 1 || name.carrier == homeCarrierLabel(null)) {
            name.lastFour?.let { "${name.carrier} · •••• $it" } ?: name.carrier
        } else name.carrier
    }
}
