package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.*
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog

internal fun LazyListScope.configurationImsProtectionSections(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, enabled: Boolean) {
    item(key = "configuration-ims-protection-title") { ConfigurationSectionTitle("IMS 信令保护") }
    item(key = "configuration-ims-protection-policy") { ConfigurationImsProtectionNotice(instance, draft, enabled) }
    if (instance.imsProtection == null) return
    val suites = draft.imsProtection?.suites ?: return
    suites.forEachIndexed { index, suite ->
        item(key = "configuration-ims-protection-suite-$index") {
            ConfigurationSectionCard {
                val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
                BasicComponent(title = "IMS 保护组合 ${index + 1}", summary = imsSuiteDescription(suite))
                ConfigurationChoicePreference("IMS 报价顺序 · ${index + 1}", (1..suites.size).map { ConfigurationChoice(it.toString(), "第 $it 位") },
                    (index + 1).toString(), "完整组合一起移动；服务器仍按自身报价在交集内选择。", draft.protectionModified, actionable) { value ->
                    if (actionable && draft.imsProtection?.suites == suites) {
                        val moved = suites.toMutableList()
                        moved.add(value.toInt() - 1, moved.removeAt(index))
                        draft.replaceImsProtection(ConfigurationImsProtection(moved))
                    }
                }
                ManagerTextAction("移除 IMS 组合 ${index + 1}", ManagerAction.ContextAction, {
                    if (actionable && draft.imsProtection?.suites == suites) draft.replaceImsProtection(ConfigurationImsProtection(suites - suite))
                }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
            }
        }
    }
    item(key = "configuration-ims-protection-add") {
        ConfigurationSectionCard {
            val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
            val options = ConfigurationImsSuite.entries.filter { it !in suites }
            if (options.isNotEmpty()) ConfigurationChoicePreference("添加 IMS 组合", listOf(ConfigurationChoice("", "选择完整组合")) +
                options.map { ConfigurationChoice(it.wire, imsSuiteLabel(it), imsSuiteDescription(it)) }, "",
                "至少选择一项。ESP NULL 仍有完整性保护；不是无内层 IPsec 的 UDP 模式。", draft.protectionModified, actionable) { value ->
                if (actionable && value.isNotEmpty() && draft.imsProtection?.suites == suites)
                    draft.replaceImsProtection(ConfigurationImsProtection(suites + options.first { it.wire == value }))
            } else BasicComponent(title = "IMS 组合已全部选择", summary = "可调整顺序或移除；不会重复报价。")
        }
    }
}

@Composable
private fun ConfigurationImsProtectionNotice(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, enabled: Boolean) {
    var clearing by remember(draft) { mutableStateOf(false) }
    val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
    LaunchedEffect(instance.configurationEditAuthority(), actionable) { clearing = false }
    ConfigurationSectionCard {
        BasicComponent(title = if (instance.imsProtection == null) "IMS 保护组尚未完整确认" else "保护组 · 下次启动生效", summary = when {
            instance.imsProtection == null && draft.imsProtection == null -> "请刷新核对配套引擎；也可明确清除整组后保存，不补猜旧值。"
            instance.imsProtection == null -> "已选择清除整组。请先保存并读回确认，再新建保护组；其它草稿保留。"
            draft.imsProtection?.suites == null -> "未设置手动保护组；优先继承目录，无目录组时沿当前 SIP 安全策略的兼容行为。新建时自行选择完整组合，不自动改变安全模式。"
            else -> "仅允许所列完整组合。运行时保存会重启本实例；配置报价不是网侧已选算法。" + if (draft.protectionModified) "\n$CONFIGURATION_UNSAVED_MARK" else ""
        })
        instance.facts?.imsProtection?.let { BasicComponent(title = "当前解析的 IMS 保护组", summary = imsProtectionFactsSummary(it)) }
        BasicComponent(title = "已加载的 IMS 保护组", summary = instance.runtimeFacts?.loaded?.imsProtection?.let(::imsProtectionFactsSummary)
            ?: "未知；不使用磁盘配置推断运行快照")
        draft.protectionError?.let { Text(it, color = LocalSoftVoWiFiColors.current.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).configurationErrorSemantics(it)) }
        if (instance.imsProtection != null && draft.imsProtection == ConfigurationImsProtection())
            ManagerTextAction("新建 IMS 保护组", ManagerAction.ContextAction, {
                if (actionable && draft.imsProtection == ConfigurationImsProtection()) draft.replaceImsProtection(ConfigurationImsProtection(emptyList()))
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
        if (draft.imsProtection != ConfigurationImsProtection())
            ManagerTextAction("清除 IMS 保护组", ManagerAction.ContextAction, { if (actionable) clearing = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
    }
    OverlayDialog(show = clearing && actionable, title = "清除整组 IMS 保护报价？",
        summary = "清除手动完整组合并恢复目录继承；无目录组时沿当前 SIP 安全策略的兼容行为，不自动改变安全模式。此处只修改草稿，仍需保存；运行时保存会重启本实例。",
        onDismissRequest = { clearing = false }) {
        ConfigurationActionPair(listOf("取消"), listOf("清除整组"),
            secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, { clearing = false }, modifier = m) },
            primary = { m -> ManagerTextAction("清除整组", ManagerAction.ContextAction, {
                if (clearing && actionable) draft.replaceImsProtection(ConfigurationImsProtection())
                clearing = false
            }, modifier = m, enabled = actionable, primary = true) })
    }
}

internal fun imsSuiteLabel(suite: ConfigurationImsSuite): String = when (suite) {
    ConfigurationImsSuite.AES128_SHA1 -> "AES128 / SHA1"
    ConfigurationImsSuite.TDES_SHA1 -> "3DES / SHA1"
    ConfigurationImsSuite.NULL_SHA1 -> "ESP NULL / SHA1"
    ConfigurationImsSuite.AES128_MD5 -> "AES128 / MD5"
    ConfigurationImsSuite.TDES_MD5 -> "3DES / MD5"
    ConfigurationImsSuite.NULL_MD5 -> "ESP NULL / MD5"
}

private fun imsSuiteDescription(suite: ConfigurationImsSuite): String = "${suite.encryption} · ${suite.integrity}" +
    if (suite in setOf(ConfigurationImsSuite.NULL_SHA1, ConfigurationImsSuite.NULL_MD5)) "\nIMS 层不加密；完整性与外层 SWu 保护仍保留。" else ""

internal fun imsProtectionFactsSummary(facts: ConfigurationImsProtectionFacts): String = when {
    !facts.known -> "未知；请刷新核对"
    facts.baseline == "swu-only-v1" -> "当前模式仅使用外层 SWu 保护，不建立 IMS 层 IPsec"
    facts.baseline == "ims-esp-compatible-v1" -> "引擎兼容完整组合；不是协商结果"
    else -> (if (facts.source == "catalog") "运营商目录：" else "手动设置：") + facts.value!!.suites!!.joinToString("；", transform = ::imsSuiteLabel) + "\n这是配置快照，不是网侧协商结果"
}
