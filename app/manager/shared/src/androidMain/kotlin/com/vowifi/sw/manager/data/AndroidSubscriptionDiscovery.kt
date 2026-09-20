package com.vowifi.sw.manager.data

import android.content.Context
import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.model.SubscriptionDiscovery

internal class AndroidSubscriptionDiscovery(
    context: Context,
) {
    private val context = context.applicationContext

    fun discover(configIccids: List<String>): List<SubscriptionDiscovery> {
        return try {
            val manager = context.getSystemService(SubscriptionManager::class.java)
                ?: return configIccids.map { SubscriptionDiscovery.Unavailable }
            val active = manager.activeSubscriptionInfoList
            if (active.isNullOrEmpty()) {
                return configIccids.map { SubscriptionDiscovery.Missing }
            }
            configIccids.map { configIccid ->
                val expected = IccidNormalizer.normalize(configIccid)
                    ?: return@map SubscriptionDiscovery.Missing
                val info = active.firstOrNull { candidate ->
                    IccidNormalizer.normalize(candidate.iccId) == expected
                } ?: return@map SubscriptionDiscovery.Missing
                matchedSubscription(
                    info.carrierName?.toString(),
                    info.displayName?.toString(),
                    info.simSlotIndex,
                    info.mccString,
                    info.mncString,
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
