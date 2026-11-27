package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.ConfigurationTunnelTimingCodec
import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationTunnelTimingPolicy

internal enum class TunnelTimingField(val title: String, val group: String, val description: String) {
    IkeSoft("IKE 软期限", "IKE SA", "单位：秒。到期前按提前量发起 IKE 重键；软期限减提前量须至少 30 秒。"),
    IkeHard("IKE 硬期限", "IKE SA", "必填，单位：秒，须大于 IKE 软期限。到期仍未完成替换时终止该隧道。"),
    IkeJitter("IKE 提前量", "IKE SA", "单位：秒。随机提前范围为 0 到该值之前；0 表示不提前。"),
    ChildSoft("Child 软期限", "Child SA", "单位：秒。独立触发 Child 重键，同时设置两向内核 SA 的软限额。"),
    ChildHard("Child 硬期限", "Child SA", "必填，单位：秒，须大于 Child 软期限。两向内核限额与本地硬到期独立执行。"),
    ChildJitter("Child 提前量", "Child SA", "单位：秒。随机提前范围为 0 到该值之前；软期限减提前量须至少 30 秒。"),
    Reauth("本地重新认证", "认证与保活", "单位：秒。0 关闭本地主动重新认证；网侧 AUTH_LIFETIME 的更早期限仍有效。"),
    Dpd("DPD 探测间隔", "认证与保活", "单位：秒。入向空闲后检查对端存活；0 关闭周期探测。"),
    Nat("NAT 保活间隔", "认证与保活", "单位：秒。检测到 NAT 后按出向空闲时间保活；0 关闭保活。"),
    NatMargin("NAT 转 DPD 余量", "认证与保活", "单位：秒。超过保活间隔再加此余量时改发 DPD；0 关闭此升级。"),
    Retries("重传次数", "请求重传", "首次发送后的重传次数，范围 0–32。0 表示不重传。"),
    Initial("初次重传等待", "请求重传", "单位：毫秒，必须大于 0。实际调度仍使用引擎既有的半秒检查精度。"),
    Maximum("重传等待上限", "请求重传", "单位：毫秒。0 表示不设单次上限；非零时须不小于初次等待。"),
    Backoff("重传退避倍率", "请求重传", "有限数值，至少为 1。引擎会核对所有重传等待及累计预算，溢出时拒绝整组。"),
    ;

    fun value(p: ConfigurationTunnelTimingPolicy): String? = when (this) {
        IkeSoft -> p.ike.softSeconds; IkeHard -> p.ike.hardSeconds; IkeJitter -> p.ike.jitterSeconds
        ChildSoft -> p.child.softSeconds; ChildHard -> p.child.hardSeconds; ChildJitter -> p.child.jitterSeconds
        Reauth -> p.reauthSeconds; Dpd -> p.dpdSeconds; Nat -> p.natKeepaliveSeconds; NatMargin -> p.natDpdMarginSeconds
        Retries -> p.retransmit.retries; Initial -> p.retransmit.initialMillis; Maximum -> p.retransmit.maximumMillis
        Backoff -> p.retransmit.backoffFactor
    }?.toString()

    fun display(p: ConfigurationTunnelTimingPolicy): String {
        val text = value(p) ?: return "必填，尚未设置"
        return when (this) {
            Backoff -> "$text 倍"
            Retries -> "$text 次"
            Initial, Maximum -> "$text 毫秒"
            else -> "$text 秒"
        }
    }

    /** Scalar-only validation allows filling an incomplete group one field at a time. Save validates the whole group. */
    fun withText(p: ConfigurationTunnelTimingPolicy, text: String): ConfigurationTunnelTimingPolicy {
        fun invalid(): Nothing = throw ConfigurationFormatException.InvalidTunnelTiming()
        if (this == Backoff) {
            val number = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1 } ?: invalid()
            return p.copy(retransmit = p.retransmit.copy(backoffFactor = number))
        }
        val maximum = when (this) {
            Retries -> 32L
            Initial, Maximum -> ConfigurationTunnelTimingCodec.MAX_MILLIS
            else -> ConfigurationTunnelTimingCodec.MAX_SECONDS
        }
        val number = text.trim().toLongOrNull()?.takeIf { it in 0..maximum } ?: invalid()
        if (number == 0L && this in setOf(IkeSoft, IkeHard, ChildSoft, ChildHard, Initial)) invalid()
        return when (this) {
            IkeSoft -> p.copy(ike = p.ike.copy(softSeconds = number))
            IkeHard -> p.copy(ike = p.ike.copy(hardSeconds = number))
            IkeJitter -> p.copy(ike = p.ike.copy(jitterSeconds = number))
            ChildSoft -> p.copy(child = p.child.copy(softSeconds = number))
            ChildHard -> p.copy(child = p.child.copy(hardSeconds = number))
            ChildJitter -> p.copy(child = p.child.copy(jitterSeconds = number))
            Reauth -> p.copy(reauthSeconds = number)
            Dpd -> p.copy(dpdSeconds = number)
            Nat -> p.copy(natKeepaliveSeconds = number)
            NatMargin -> p.copy(natDpdMarginSeconds = number)
            Retries -> p.copy(retransmit = p.retransmit.copy(retries = number.toInt()))
            Initial -> p.copy(retransmit = p.retransmit.copy(initialMillis = number))
            Maximum -> p.copy(retransmit = p.retransmit.copy(maximumMillis = number))
            Backoff -> invalid()
        }
    }
}

internal data class TunnelTimingTextEdit(val field: TunnelTimingField, val sequence: Long)
