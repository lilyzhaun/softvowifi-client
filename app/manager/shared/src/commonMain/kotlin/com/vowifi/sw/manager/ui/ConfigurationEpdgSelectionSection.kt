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

internal fun LazyListScope.configurationEpdgSelectionSections(
    instance: ConfigurationInstance, draft: ConfigurationEditorDraft, enabled: Boolean,
    onEdit: (Int, EpdgCandidateField) -> Unit,
) {
    item(key = "configuration-epdg-selection-title") { ConfigurationSectionTitle("ePDG 候选与访问条件") }
    item(key = "configuration-epdg-selection-policy") { EpdgSelectionNotice(instance, draft, enabled) }
    if (instance.epdgSelection == null) return
    val candidates = draft.epdgSelection?.candidates ?: return
    candidates.forEachIndexed { index, row ->
        item(key = "configuration-epdg-candidate-$index") {
            ConfigurationSectionCard {
                val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
                fun replace(value: ConfigurationEpdgCandidate) {
                    if (actionable && draft.epdgSelection?.candidates == candidates) {
                        draft.replaceEpdgSelection(ConfigurationEpdgSelection(candidates.toMutableList().apply { set(index, value) }))
                    }
                }
                ConfigurationChoicePreference("候选来源 · ${index + 1}", ConfigurationEpdgKind.entries.map { ConfigurationChoice(it.wire, it.label) },
                    row.kind.wire, "PLMN 按标准公式生成地址；当前注册 PLMN 必须来自可信注册事实。", draft.selectionModified, actionable) { value ->
                    val kind = ConfigurationEpdgKind.entries.first { it.wire == value }
                    if (kind != row.kind) replace(row.copy(kind = kind, value = ""))
                }
                if (row.kind in setOf(ConfigurationEpdgKind.ADDRESS, ConfigurationEpdgKind.PLMN)) {
                    BasicComponent(title = "候选值 · ${index + 1}", summary = row.value.ifEmpty {
                        if (row.kind == ConfigurationEpdgKind.PLMN) "请填写 5 或 6 位 PLMN" else "请填写域名或 IP，不含端口"
                    }, enabled = actionable, onClick = { if (actionable) onEdit(index, EpdgCandidateField.VALUE) })
                }
                ConfigurationChoicePreference("访问条件 · ${index + 1}", ConfigurationEpdgAccess.entries.map { ConfigurationChoice(it.wire, it.label) },
                    row.access.wire, "状态未知不匹配非漫游或漫游条件。", draft.selectionModified, actionable) { value ->
                    replace(row.copy(access = ConfigurationEpdgAccess.entries.first { it.wire == value }))
                }
                BasicComponent(title = "注册 PLMN 限定 · ${index + 1}", summary = row.registeredPlmns.joinToString("、").ifEmpty { "不限；与访问条件共同求值" },
                    enabled = actionable, onClick = { if (actionable) onEdit(index, EpdgCandidateField.REGISTERED_PLMNS) })
                ConfigurationChoicePreference("候选顺序 · ${index + 1}", (1..candidates.size).map { ConfigurationChoice(it.toString(), "第 $it 位") },
                    (index + 1).toString(), "按顺序筛选；普通连接失败沿原退避尝试下一候选，永久拒绝不会继续。", draft.selectionModified, actionable) { value ->
                    if (actionable && draft.epdgSelection?.candidates == candidates) {
                        val moved = candidates.toMutableList()
                        moved.add(value.toInt() - 1, moved.removeAt(index))
                        draft.replaceEpdgSelection(ConfigurationEpdgSelection(moved))
                    }
                }
                ManagerTextAction("移除候选 ${index + 1}", ManagerAction.ContextAction, {
                    if (actionable && draft.epdgSelection?.candidates == candidates)
                        draft.replaceEpdgSelection(ConfigurationEpdgSelection(candidates.filterIndexed { i, _ -> i != index }))
                }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
            }
        }
    }
    if (candidates.size < 8) item(key = "configuration-epdg-candidate-add") {
        ConfigurationSectionCard {
            val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
            ConfigurationChoicePreference("添加 ePDG 候选", listOf(ConfigurationChoice("", "选择候选来源")) +
                ConfigurationEpdgKind.entries.map { ConfigurationChoice(it.wire, it.label) }, "",
                "最多 8 项。至少保留一项；空列表不会恢复兼容策略。", draft.selectionModified, actionable) { value ->
                if (actionable && value.isNotEmpty() && draft.epdgSelection?.candidates == candidates)
                    draft.replaceEpdgSelection(ConfigurationEpdgSelection(candidates + ConfigurationEpdgCandidate(ConfigurationEpdgKind.entries.first { it.wire == value })))
            }
        }
    }
}

@Composable
private fun EpdgSelectionNotice(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, enabled: Boolean) {
    var clearing by remember(draft) { mutableStateOf(false) }
    val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
    LaunchedEffect(instance.configurationEditAuthority(), actionable) { clearing = false }
    ConfigurationSectionCard {
        BasicComponent(title = "候选策略 · 下次启动生效", summary = when {
            instance.epdgSelection == null && draft.epdgSelection == null -> "策略尚未确认，请刷新配套引擎；不会把未知访问网补为归属网。"
            instance.epdgSelection == null -> "已选择清除整组。请先保存并读回确认，再新建候选。"
            draft.epdgSelection?.candidates == null -> "未设置手动候选；继承目录中的完整候选组，无目录组时使用单地址兼容解析。新建后只使用手动列表。"
            else -> "与单个 ePDG 地址覆盖不能同时使用。条件无匹配时等待新事实，不猜地址；地址族与 DNS 仍独立。" +
                if (draft.selectionModified) "\n$CONFIGURATION_UNSAVED_MARK" else ""
        })
        BasicComponent(title = "当前解析的 ePDG 候选", summary = instance.facts?.epdgSelection?.let(::epdgSelectionFactsSummary) ?: "未知")
        BasicComponent(title = "已加载的 ePDG 候选", summary = instance.runtimeFacts?.loaded?.epdgSelection?.let(::epdgSelectionFactsSummary) ?: "未知；不以磁盘值代替运行快照")
        BasicComponent(title = "本次连接候选", summary = instance.runtimeFacts?.epdgSelection?.let {
            "已连接，使用第 ${it.candidate} 项；捕获的注册状态：" +
                if (it.roaming == null) "未知" else "${if (it.roaming) "漫游" else "非漫游"} · ${it.registeredPlmn}"
        } ?: "未知；等待当前连接事实")
        draft.selectionError?.let { Text(it, color = LocalSoftVoWiFiColors.current.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).configurationErrorSemantics(it)) }
        if (instance.epdgSelection != null && draft.epdgSelection == ConfigurationEpdgSelection()) {
            ManagerTextAction("新建 ePDG 候选策略", ManagerAction.ContextAction, {
                if (actionable && draft.epdgSelection == ConfigurationEpdgSelection()) draft.replaceEpdgSelection(ConfigurationEpdgSelection(emptyList()))
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
        }
        if (draft.epdgSelection != ConfigurationEpdgSelection()) ManagerTextAction("清除 ePDG 候选策略", ManagerAction.ContextAction, {
            if (actionable) clearing = true
        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
    }
    OverlayDialog(show = clearing && actionable, title = "清除整组 ePDG 候选？",
        summary = "移除手动整组并恢复目录继承；无目录组时使用单地址兼容解析。只修改草稿，仍需保存；运行时保存会重启本实例。", onDismissRequest = { clearing = false }) {
        ConfigurationActionPair(listOf("取消"), listOf("清除整组"),
            secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, { clearing = false }, modifier = m) },
            primary = { m -> ManagerTextAction("清除整组", ManagerAction.ContextAction, {
                if (clearing && actionable) draft.replaceEpdgSelection(ConfigurationEpdgSelection())
                clearing = false
            }, modifier = m, enabled = actionable, primary = true) })
    }
}

internal fun epdgSelectionFactsSummary(facts: ConfigurationEpdgSelectionFacts): String = when {
    !facts.known -> "未知；请刷新核对"
    facts.compatibilityBaseline -> "兼容单地址解析；无手动候选列表"
    else -> "${if (facts.source == "catalog") "运营商目录" else "手动"} ${facts.value!!.candidates!!.size} 项有序候选；访问条件在连接时读取，不是当前协商结果"
}
