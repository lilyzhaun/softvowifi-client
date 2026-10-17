package com.vowifi.sw.manager.data

import android.content.Context
import com.vowifi.sw.manager.model.CountryCatalogGenerated
import com.vowifi.sw.manager.model.SimActiveJoinInput

internal class AndroidHistoryLineNames(
    context: Context,
    private val ownerFor: (String) -> SimOwnerId?,
    private val active: () -> List<SimActiveJoinInput> = {
        (AndroidSubscriptionInventory(context).listActive() as? SubscriptionInventoryResult.Active)?.subscriptions.orEmpty()
    },
    private val knownKeys: suspend () -> List<String> = { emptyList() },
    /** Home MCC of a configured instance; lets removed lines cached before country support gain a flag. */
    private val homeMcc: suspend (String) -> String? = { null },
) {
    private val preferences = context.applicationContext.getSharedPreferences("history_line_names", Context.MODE_PRIVATE)
    private val countryTried = mutableSetOf<String>()

    fun rememberKeys(rawKeys: List<String>) {
        val edit = preferences.edit()
        val stored = preferences.all
        var changed = false
        rawKeys.forEach { raw ->
            val key = IccidNormalizer.normalize(raw) ?: return@forEach
            ownerFor(key)?.let { owner ->
                val entry = "tail:${owner.wire()}"
                if (stored[entry] != key.takeLast(4)) { edit.putString(entry, key.takeLast(4)); changed = true }
            }
        }
        if (changed) edit.commit()
    }

    suspend fun labels(owners: List<SimOwnerId>): Map<SimOwnerId, String> {
        val known = knownKeys()
        rememberKeys(known)
        rememberConfiguredCountries(known)
        val edit = preferences.edit()
        val previous = preferences.all
        var changed = false
        active().forEach { row ->
            val key = IccidNormalizer.normalize(row.key) ?: return@forEach
            val owner = ownerFor(key) ?: return@forEach
            val name = homeCarrierLabel(row.homeCarrierLabel)
            val tailKey = "tail:${owner.wire()}"
            val carrierKey = "carrier:${owner.wire()}"
            val countryKey = "country:${owner.wire()}"
            val country = CountryCatalogGenerated.countryForMcc(row.mcc).uppercase().takeIf { it.length == 2 }
            if (previous[tailKey] != key.takeLast(4)) { edit.putString(tailKey, key.takeLast(4)); changed = true }
            if (name != homeCarrierLabel(null) && previous[carrierKey] != name) { edit.putString(carrierKey, name); changed = true }
            if (country != null && previous[countryKey] != country) { edit.putString(countryKey, country); changed = true }
        }
        if (changed) edit.commit()
        val stored = preferences.all
        val names = stored.keys.filter { it.startsWith("tail:") }.mapNotNull { SimOwnerId.parse(it.removePrefix("tail:")) }
            .associateWith { owner -> HistoryLineName.parse(stored["carrier:${owner.wire()}"] as? String,
                stored["tail:${owner.wire()}"] as? String, stored["country:${owner.wire()}"] as? String) }
        val titles = historyLineTitles(names)
        return owners.associateWith { titles[it] ?: homeCarrierLabel(null) }
    }

    // One configuration read per line and process; the result is cached as a two-letter code only.
    private suspend fun rememberConfiguredCountries(rawKeys: List<String>) {
        val stored = preferences.all
        rawKeys.forEach { raw ->
            val key = IccidNormalizer.normalize(raw) ?: return@forEach
            val owner = ownerFor(key) ?: return@forEach
            val countryKey = "country:${owner.wire()}"
            if (stored[countryKey] != null || !countryTried.add(owner.wire())) return@forEach
            val country = CountryCatalogGenerated.countryForMcc(homeMcc(key)).uppercase().takeIf { it.length == 2 } ?: return@forEach
            preferences.edit().putString(countryKey, country).commit()
        }
    }
}
