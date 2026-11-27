package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.ConfigurationImsTimingCodec
import com.vowifi.sw.manager.model.*

internal enum class ImsTimingField(val title: String, val group: String, val unit: String, val description: String) {
    T1("T1 · 初次重传", "SIP 事务计时", "毫秒", "大于 0；A、E、G 从此值开始，成功事务保留 L/M 为 64 倍。"),
    T2("T2 · 重传间隔上限", "SIP 事务计时", "毫秒", "须不小于 T1。非 INVITE 请求和失败 INVITE 响应使用此上限。"),
    T4("T4 · 网络报文保留", "SIP 事务计时", "毫秒", "大于 0；不可靠传输的 I/K 保留使用此值。"),
    B("B · INVITE 首响应", "SIP 事务计时", "毫秒", "大于 0。与非 INVITE 的 F 分开，不代替通话接通期限。"),
    D("D · INVITE 失败保留", "SIP 事务计时", "毫秒", "UDP 至少 32000 毫秒；可靠传输不使用该保留等待。"),
    F("F · 非 INVITE 响应", "SIP 事务计时", "毫秒", "大于 0。用于 REGISTER、MESSAGE 等事务；socket 写入仍有独立上界。"),
    H("H · 失败响应等 ACK", "SIP 事务计时", "毫秒", "大于 0。INVITE 服务端失败响应的 ACK 等待期限。"),
    J("J · 服务端响应保留", "SIP 事务计时", "毫秒", "大于 0。UDP 非 INVITE 最终响应重放窗口，可靠传输不等待。"),
    Expires("请求注册期限", "注册期限与刷新", "秒", "请求值不是网侧授予值；实际注册以响应为准。"),
    Refresh("刷新时点", "注册期限与刷新", "%", "1–99。按网侧实际注册寿命的百分比安排刷新，不延长寿命。"),
    Initial("恢复退避基数", "注册失败恢复", "秒", "大于 0。按失败次数倍增，再在窗口的一半至全长内随机等待。"),
    Maximum("恢复退避上限", "注册失败恢复", "秒", "不小于基数，最多为基数的 64 倍；不覆盖服务器 Retry-After。"),
    Unavailable("额外不可用期", "注册失败恢复", "秒", "大于等于 0；适用失败在退避后再加此值。另一 P-CSCF 独立计时。"),
    ;

    fun value(p: ConfigurationImsTimingPolicy): Long? = when (this) {
        T1 -> p.transaction.t1Millis; T2 -> p.transaction.t2Millis; T4 -> p.transaction.t4Millis
        B -> p.transaction.bMillis; D -> p.transaction.dMillis; F -> p.transaction.fMillis
        H -> p.transaction.hMillis; J -> p.transaction.jMillis
        Expires -> p.registration.requestedSeconds; Refresh -> p.registration.refreshPercent
        Initial -> p.recovery.initialSeconds; Maximum -> p.recovery.maximumSeconds; Unavailable -> p.recovery.unavailableSeconds
    }

    fun withText(p: ConfigurationImsTimingPolicy, text: String): ConfigurationImsTimingPolicy {
        val minimum = when (this) { Unavailable -> 0L; D -> 32000L; else -> 1L }
        val maximum = when (this) {
            Refresh -> 99L; Expires -> Int.MAX_VALUE.toLong()
            T1 -> ConfigurationImsTimingCodec.MAX_MILLIS / 64
            F -> ConfigurationImsTimingCodec.MAX_MILLIS / 2
            T2, T4, B, D, H, J -> ConfigurationImsTimingCodec.MAX_MILLIS
            else -> ConfigurationImsTimingCodec.MAX_SECONDS
        }
        val number = text.trim().toLongOrNull()?.takeIf { it in minimum..maximum } ?: throw ConfigurationFormatException.InvalidImsTiming()
        return when (this) {
            T1 -> p.copy(transaction = p.transaction.copy(t1Millis = number))
            T2 -> p.copy(transaction = p.transaction.copy(t2Millis = number))
            T4 -> p.copy(transaction = p.transaction.copy(t4Millis = number))
            B -> p.copy(transaction = p.transaction.copy(bMillis = number))
            D -> p.copy(transaction = p.transaction.copy(dMillis = number))
            F -> p.copy(transaction = p.transaction.copy(fMillis = number))
            H -> p.copy(transaction = p.transaction.copy(hMillis = number))
            J -> p.copy(transaction = p.transaction.copy(jMillis = number))
            Expires -> p.copy(registration = p.registration.copy(requestedSeconds = number))
            Refresh -> p.copy(registration = p.registration.copy(refreshPercent = number))
            Initial -> p.copy(recovery = p.recovery.copy(initialSeconds = number))
            Maximum -> p.copy(recovery = p.recovery.copy(maximumSeconds = number))
            Unavailable -> p.copy(recovery = p.recovery.copy(unavailableSeconds = number))
        }
    }
}

internal data class ImsTimingTextEdit(val field: ImsTimingField, val sequence: Long)
