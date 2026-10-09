package com.vowifi.sw.manager.data

import android.content.Context
import com.vowifi.sw.manager.model.SimActiveJoinInput

internal class AndroidHistoryLineNames(
    context: Context,
    private val ownerFor: (String) -> SimOwnerId?,
    private val active: () -> List<SimActiveJoinInput> = {
        (AndroidSubscriptionInventory(context).listActive() as? SubscriptionInventoryResult.Active)?.subscriptions.orEmpty()
    },
    private val knownKeys: suspend () -> List<String> = { emptyList() },
) {
    private val preferences = context.applicationContext.getSharedPreferences("history_line_names", Context.MODE_PRIVATE)

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
        rememberKeys(knownKeys())
        val edit = preferences.edit()
        val previous = preferences.all
        var changed = false
        active().forEach { row ->
            val key = IccidNormalizer.normalize(row.key) ?: return@forEach
            val owner = ownerFor(key) ?: return@forEach
            val name = homeCarrierLabel(row.homeCarrierLabel)
            val tailKey = "tail:${owner.wire()}"
            val carrierKey = "carrier:${owner.wire()}"
            if (previous[tailKey] != key.takeLast(4)) { edit.putString(tailKey, key.takeLast(4)); changed = true }
            if (name != homeCarrierLabel(null) && previous[carrierKey] != name) { edit.putString(carrierKey, name); changed = true }
        }
        if (changed) edit.commit()
        val stored = preferences.all
        val names = stored.keys.filter { it.startsWith("tail:") }.mapNotNull { SimOwnerId.parse(it.removePrefix("tail:")) }
            .associateWith { owner -> HistoryLineName.parse(stored["carrier:${owner.wire()}"] as? String, stored["tail:${owner.wire()}"] as? String) }
        val titles = historyLineTitles(names)
        return owners.associateWith { titles[it] ?: homeCarrierLabel(null) }
    }
}
