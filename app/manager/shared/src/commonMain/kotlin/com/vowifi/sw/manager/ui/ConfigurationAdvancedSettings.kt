package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import com.vowifi.sw.manager.model.ConfigurationIdentitySource
import com.vowifi.sw.manager.model.ConfigurationInstance
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

internal data class ConfigurationChoice(val value: String, val label: String, val summary: String? = null)

internal val configurationSecurityChoices = listOf(
    ConfigurationChoice("", "自动", "运营商策略优先"),
    ConfigurationChoice("tcp_ipsec_3gpp", "TCP + IPsec", "显式指定"),
    ConfigurationChoice("swu_udp_aka", "SWu 内 UDP + AKA", "仅限已认证的 SWu 隧道内部"),
)

internal val configurationPaniChoices = listOf(ConfigurationChoice("", "继承", "目录 / 默认")) +
    listOf("absent", "wlan", "wlan-node", "wlan-country", "wlan-node-country").map { ConfigurationChoice(it, it) }

/** IMS policy rows: each states the effective (authoritative) value and where it came from, in plain words. */
@Composable
internal fun ConfigurationImsAdvancedRows(
    instance: ConfigurationInstance,
    draft: ConfigurationEditorDraft,
    enabled: Boolean,
    onEdit: (ConfigurationFieldKey) -> Unit,
) {
    val policy = instance.imsPolicy
    val modified = draft.modifiedFields()
    val securityModified = ConfigurationFieldKey.SipSecurity in modified
    ConfigurationChoicePreference("SIP 安全策略", configurationSecurityChoices, draft.imsSipSecurity,
        configurationEffectiveSummary(configurationSecurityLabel(policy.security), policy.securitySource, securityModified),
        securityModified, enabled) { draft.imsSipSecurity = it }
    if (draft.imsSipSecurity == "swu_udp_aka") {
        BasicComponent(title = "安全边界警告", summary = "仅限已认证的 SWu 隧道内部；不建立额外的 UE–P-CSCF IPsec。不是自动回退。短信和语音尚未经 LIVE 实机验证。")
    }
    val paniModified = ConfigurationFieldKey.PaniInitial in modified
    ConfigurationChoicePreference("初始 PANI", configurationPaniChoices, draft.paniInitial,
        configurationEffectiveSummary(policy.initial.ifEmpty { "旧引擎未报告" }, policy.initialSource, paniModified),
        paniModified, enabled) { draft.paniInitial = it }
    ConfigurationValueRow(instance, draft, ConfigurationFieldKey.PaniCountry, enabled, onEdit)
    ConfigurationValueRow(instance, draft, ConfigurationFieldKey.DigestRealm, enabled, onEdit)
}

/** Short value on the trailing side; the longer meaning of each option lives in the menu item's summary. */
@Composable
private fun ConfigurationChoicePreference(
    title: String,
    choices: List<ConfigurationChoice>,
    selected: String,
    summary: String,
    modified: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    OverlayDropdownPreference(
        entry = DropdownEntry(choices.map { choice ->
            DropdownItem(text = choice.label, selected = choice.value == selected, summary = choice.summary,
                onClick = { onSelect(choice.value) })
        }),
        title = title,
        summary = summary,
        summaryColor = configurationSummaryColor(modified, error = false),
        enabled = enabled,
    )
}

internal fun configurationSecurityLabel(value: String): String = when (value) {
    "tcp_ipsec_3gpp" -> "TCP + IPsec"
    "swu_udp_aka" -> "SWu 内 UDP + AKA"
    "" -> "旧引擎未报告"
    else -> value
}

internal fun configurationEffectiveSummary(value: String, source: String, modified: Boolean): String =
    "当前解析：$value · ${configurationSourceLabel(source)}" + if (modified) "\n$CONFIGURATION_UNSAVED_MARK" else ""

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
