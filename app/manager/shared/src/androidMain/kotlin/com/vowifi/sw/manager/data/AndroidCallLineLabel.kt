package com.vowifi.sw.manager.data

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.vowifi.sw.manager.model.CallBinding
import com.vowifi.sw.manager.ui.simCarrierTitle

internal fun boundCallLine(context: Context, binding: CallBinding?): String {
    if (binding == null) return "当前线路尚未连接"
    return callLineLabel(context, binding.owner)
}

internal fun callLineLabel(context: Context, rawOwner: String): String {
    val owner = IccidNormalizer.normalize(rawOwner) ?: return "当前绑定线路 · SIM 信息不可用"
    return try {
        val info = context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
            ?.singleOrNull { IccidNormalizer.normalize(it.iccId) == owner }
            ?: return "当前绑定线路 · SIM 信息不可用"
        simCarrierTitle(validatedSlotIndex(info.simSlotIndex), subscriptionHomeCarrierLabel(context, info.subscriptionId), validatedMcc(info.mccString))
    } catch (_: SecurityException) { "当前绑定线路 · SIM 信息不可用" }
    catch (_: IllegalStateException) { "当前绑定线路 · SIM 信息不可用" }
}

internal fun subscriptionHomeCarrierLabel(context: Context, subscriptionId: Int): String = try {
    homeCarrierLabel(context.getSystemService(TelephonyManager::class.java)
        ?.createForSubscriptionId(subscriptionId)?.simOperatorName)
} catch (_: RuntimeException) { homeCarrierLabel(null) }
