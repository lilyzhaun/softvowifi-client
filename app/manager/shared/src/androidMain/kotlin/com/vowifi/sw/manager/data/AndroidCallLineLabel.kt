package com.vowifi.sw.manager.data

import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.vowifi.sw.manager.model.CallBinding
import com.vowifi.sw.manager.ui.homeCountryFlag

internal fun boundCallLine(context: Context, binding: CallBinding?): String {
    if (binding == null) return "当前线路尚未连接"
    val owner = IccidNormalizer.normalize(binding.owner) ?: return "当前绑定线路 · SIM 信息不可用"
    return try {
        val info = context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
            ?.singleOrNull { IccidNormalizer.normalize(it.iccId) == owner }
            ?: return "当前绑定线路 · SIM 信息不可用"
        val manager = context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(info.subscriptionId)
        val name = homeCarrierLabel(manager?.simOperatorName)
        val flag = homeCountryFlag(manager?.simCountryIso)
        val slot = info.simSlotIndex.takeIf { it >= 0 }?.let { "SIM ${it + 1}" } ?: "当前绑定线路"
        listOfNotNull(slot, flag, name).joinToString(" · ")
    } catch (_: SecurityException) { "当前绑定线路 · SIM 信息不可用" }
    catch (_: IllegalStateException) { "当前绑定线路 · SIM 信息不可用" }
}
