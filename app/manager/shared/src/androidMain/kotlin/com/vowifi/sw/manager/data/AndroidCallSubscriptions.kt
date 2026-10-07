package com.vowifi.sw.manager.data

import android.content.Context
import android.telephony.SubscriptionManager

internal fun activeCallIccids(context: Context): List<String?>? = try {
    context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
        ?.sortedBy { it.simSlotIndex.takeIf { slot -> slot >= 0 } ?: Int.MAX_VALUE }?.map {
        IccidNormalizer.normalize(it.iccId)
    }
} catch (_: SecurityException) { null }
catch (_: IllegalStateException) { null }
