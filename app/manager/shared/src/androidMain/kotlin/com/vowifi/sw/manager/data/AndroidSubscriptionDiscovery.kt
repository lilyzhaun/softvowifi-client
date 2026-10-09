package com.vowifi.sw.manager.data

import android.content.Context
import com.vowifi.sw.manager.model.SubscriptionDiscovery

internal class AndroidSubscriptionDiscovery(
    context: Context,
) {
    private val context = context.applicationContext

    fun discover(configIccids: List<String>): List<SubscriptionDiscovery> {
        return try {
            val active = (AndroidSubscriptionInventory(context).listActive() as? SubscriptionInventoryResult.Active)?.subscriptions
                ?: return configIccids.map { SubscriptionDiscovery.Unavailable }
            if (active.isEmpty()) {
                return configIccids.map { SubscriptionDiscovery.Missing }
            }
            configIccids.map { configIccid ->
                val expected = IccidNormalizer.normalize(configIccid)
                    ?: return@map SubscriptionDiscovery.Missing
                val info = active.firstOrNull { candidate ->
                    candidate.key == expected
                } ?: return@map SubscriptionDiscovery.Missing
                SubscriptionDiscovery.Matched(
                    homeCarrierLabel(info.homeCarrierLabel),
                    validatedSlotIndex(info.slotIndex),
                    info.mcc,
                    info.mnc,
                )
            }
        } catch (_: SecurityException) {
            configIccids.map { SubscriptionDiscovery.Unavailable }
        } catch (_: IllegalStateException) {
            configIccids.map { SubscriptionDiscovery.Unavailable }
        } catch (_: RuntimeException) {
            configIccids.map { SubscriptionDiscovery.Unavailable }
        }
    }
}
