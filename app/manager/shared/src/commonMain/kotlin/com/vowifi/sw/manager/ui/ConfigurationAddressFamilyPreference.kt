package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.ConfigurationInstance
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text

private val addressFamilyChoices = listOf(
    ConfigurationChoice("", "兼容", "沿用引擎的 IPv4 优先基线"),
    ConfigurationChoice("prefer-ipv4", "优先 IPv4", "可用候选中优先 IPv4，否则选 IPv6"),
    ConfigurationChoice("prefer-ipv6", "优先 IPv6", "可用候选中优先 IPv6，否则选 IPv4"),
    ConfigurationChoice("ipv4-only", "仅 IPv4", "没有兼容的 IPv4 候选时拒绝连接"),
    ConfigurationChoice("ipv6-only", "仅 IPv6", "没有兼容的 IPv6 候选时拒绝连接"),
)

@Composable
internal fun ConfigurationAddressFamilyPreference(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, enabled: Boolean) {
    val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
    if (instance.epdgAddressFamily == null || draft.epdgAddressFamily == null) {
        BasicComponent(title = "ePDG 地址族", summary = if (draft.epdgAddressFamily == "")
            "已选择清除覆盖。保存并读回确认后可重新编辑；其它草稿保留。"
            else "尚未确认。可刷新核对，或明确清除覆盖后保存；不补猜旧值。")
        if (draft.epdgAddressFamily != "") ManagerTextAction("清除地址族覆盖", ManagerAction.ContextAction, {
            if (actionable) draft.replaceEpdgAddressFamily("")
        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
    } else {
        val summary = "仅约束 ePDG 远端，DNS 地址族独立；下次启动生效。\n" +
            configurationFactsSummary(instance, "epdgAddressFamily") +
            if (draft.addressFamilyModified) "\n$CONFIGURATION_UNSAVED_MARK" else ""
        ConfigurationChoicePreference("ePDG 地址族", addressFamilyChoices, draft.epdgAddressFamily.orEmpty(),
            summary, draft.addressFamilyModified, actionable) { if (actionable) draft.replaceEpdgAddressFamily(it) }
    }
    draft.addressFamilyError?.let { error ->
        Text(error, color = LocalSoftVoWiFiColors.current.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).configurationErrorSemantics(error))
    }
}

internal fun configurationAddressFamilyLabel(value: String): String = when (value) {
    "", "compatible" -> "兼容（IPv4 优先）"
    "prefer-ipv4" -> "优先 IPv4"
    "prefer-ipv6" -> "优先 IPv6"
    "ipv4-only" -> "仅 IPv4"
    "ipv6-only" -> "仅 IPv6"
    else -> "未知"
}
