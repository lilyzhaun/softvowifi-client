package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationIdentitySource
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.SubscriptionDiscovery
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

@Composable
internal fun ConfigurationAdvancedSettings(
    instance: ConfigurationInstance,
    draft: ConfigurationEditorDraft,
    enabled: Boolean,
) {
    var advanced by rememberSaveable(draft) { mutableStateOf(false) }
    val subscription = instance.systemSubscription as? SubscriptionDiscovery.Matched
    BasicComponent(
        title = subscription?.carrierLabel?.takeIf { it.isNotBlank() }
            ?: "运营商 ${instance.mcc} / ${instance.mnc}",
        summary = if (instance.lifecycle == ConfigurationLifecycle.CONFIG_MISSING) "缺少配置"
            else "配置可用；配置就绪不代表注册成功，请在首页查看注册状态。",
    )
    BasicComponent(title = "配置来源", summary = configurationSourceSummary(instance))
    ConfigurationDisclosureHeader(
        title = "高级设置",
        summary = "网络、手动覆盖与已解析参数；通常无需修改",
        expanded = advanced,
        interaction = configurationDisclosureInteractionContract(advanced).copy(
            cardClickable = false,
            headerClickable = true,
            headerUsesTopSquircleClip = false,
        ),
        onToggleExpand = { advanced = !advanced },
    )
    if (advanced) {
        OverlayDropdownPreference(
            items = ConfigurationTransport.entries.map(::transportLabel),
            selectedIndex = ConfigurationTransport.entries.indexOf(draft.transport),
            title = "底层网络",
            enabled = enabled,
            onSelectedIndexChange = { draft.transport = ConfigurationTransport.entries[it] },
        )
        if (hasIdentityRealmOverride(instance)) {
            BasicComponent(title = "已有 IMS 域值", summary = "当前 Digest 认证域来自身份域，已有 IMS realm 值可能覆盖运营商认证域。请核对后在 IMS realm 字段手动清空并保存；不会自动修改旧值。")
        }
        ConfigurationFields(draft, enabled)
        ConfigurationPolicyFields(draft, enabled)
        ConfigurationFacts(instance)
    }
}

internal fun hasIdentityRealmOverride(instance: ConfigurationInstance): Boolean =
    instance.identitySource in setOf(ConfigurationIdentitySource.LEGACY, ConfigurationIdentitySource.OVERRIDE) &&
        instance.imsPolicy.digestRealmSource == "identity" && instance.imsRealm.isNotEmpty()

internal fun configurationSourceSummary(instance: ConfigurationInstance): String {
    val policy = instance.imsPolicy
    val sources = listOf(policy.securitySource, policy.initialSource, policy.countrySource, policy.digestRealmSource)
    val summary = when {
        sources.any { it !in setOf("carrier", "catalog", "user", "default", "home_mcc", "identity") } -> "按已有配置（部分参数来源未知）"
        "user" in sources -> "IMS 参数含手动覆盖；其余按已有配置"
        hasIdentityRealmOverride(instance) -> "按已有配置（含已有 IMS 域值）"
        "carrier" in sources -> "IMS 参数自动继承运营商策略、身份或默认值"
        "catalog" in sources -> "按已有配置（含目录参数；其余来自身份或默认值）"
        else -> "按已有配置（IMS 参数来自身份或默认值）"
    }
    return summary
}
