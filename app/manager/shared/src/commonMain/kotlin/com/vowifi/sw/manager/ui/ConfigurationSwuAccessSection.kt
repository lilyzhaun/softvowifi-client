package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.data.ConfigurationSwuAccessCodec
import com.vowifi.sw.manager.model.*
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog

internal fun LazyListScope.configurationSwuAccessSections(instance: ConfigurationInstance, draft: ConfigurationEditorDraft,
    enabled: Boolean, onEdit: () -> Unit) {
    item(key = "configuration-swu-access-title") { ConfigurationSectionTitle("SWu 接入参数") }
    item(key = "configuration-swu-access-policy") { ConfigurationSwuAccessNotice(instance, draft, enabled, onEdit) }
}

@Composable
private fun ConfigurationSwuAccessNotice(instance: ConfigurationInstance, draft: ConfigurationEditorDraft,
    enabled: Boolean, onEdit: () -> Unit) {
    var clearing by remember(draft) { mutableStateOf(false) }
    val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
    LaunchedEffect(instance.configurationEditAuthority(), actionable) { clearing = false }
    ConfigurationSectionCard {
        BasicComponent(title = "接入参数 · 下次启动生效", summary = when {
            instance.swuAccess == null && draft.swuAccess == null -> "当前参数尚未完整确认。请刷新核对；也可明确清除整组，不补猜旧值。"
            instance.swuAccess == null -> "已选择清除整组；保存并读回确认后才能手动设置。其它草稿保留。"
            draft.swuAccess?.policy == null -> if (draft.swuAccessModified) "已选择清除手动 APN 与接口 MTU；请保存后恢复兼容接入。" else "未设置手动 APN 与接口 MTU，保留兼容接入。"
            else -> "${if (draft.swuAccessModified) "未保存草稿" else "手动设置"}：APN ${draft.swuAccess!!.policy!!.requestedApn} · MTU ${draft.swuAccess!!.policy!!.interfaceMtu}" +
                swuInnerSummary(draft.swuAccess!!.policy!!.innerProtocol) + "\n运行时保存会重启本实例。"
        })
        instance.facts?.swuAccess?.let { BasicComponent(title = "当前解析的接入参数", summary = swuAccessFactsSummary(it)) }
        BasicComponent(title = "已加载的接入参数", summary = instance.runtimeFacts?.loaded?.swuAccess?.let(::swuAccessFactsSummary)
            ?: "未知；不使用磁盘配置推断运行快照")
        draft.swuAccessError?.let { Text(it, color = LocalSoftVoWiFiColors.current.error,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).configurationErrorSemantics(it)) }
        if (instance.swuAccess != null && draft.swuAccess != null) ManagerTextAction(
            if (draft.swuAccess?.policy == null) "设置 SWu 接入" else "编辑 SWu 接入", ManagerAction.Navigate,
            { if (actionable) onEdit() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
        if (draft.swuAccess != ConfigurationSwuAccess()) ManagerTextAction("清除 SWu 接入", ManagerAction.ContextAction,
            { if (actionable) clearing = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
    }
    OverlayDialog(show = clearing && actionable, title = "清除整组 SWu 接入参数？",
        summary = "移除手动 APN、接口 MTU 及可选内层协议，恢复兼容接入。只修改本组草稿，仍需保存；运行时保存会重启本实例。",
        onDismissRequest = { clearing = false }) {
        ConfigurationActionPair(listOf("取消"), listOf("清除整组"),
            secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, { clearing = false }, modifier = m) },
            primary = { m -> ManagerTextAction("清除整组", ManagerAction.ContextAction, {
                if (clearing && actionable) draft.replaceSwuAccess(ConfigurationSwuAccess())
                clearing = false
            }, modifier = m, enabled = actionable, primary = true) })
    }
}

@Composable
internal fun ConfigurationSwuAccessDialog(edit: Long?, draft: ConfigurationEditorDraft, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf<Long?>(null) }
    SideEffect { if (edit != null) last = edit }
    val current = edit ?: last ?: return
    val previous = remember(current) { draft.swuAccess }
    val apn = remember(current) { TextFieldState(previous?.policy?.requestedApn ?: "ims") }
    val mtu = remember(current) { TextFieldState((previous?.policy?.interfaceMtu ?: 1358).toString()) }
    var home by remember(current) { mutableStateOf(previous?.policy?.innerProtocol?.home.orEmpty()) }
    var roaming by remember(current) { mutableStateOf(previous?.policy?.innerProtocol?.roaming ?: "dual-stack") }
    var attempted by remember(current) { mutableStateOf(false) }
    fun candidate(): ConfigurationSwuAccessPolicy? = try {
        ConfigurationSwuAccessPolicy(apn.text.toString(), mtu.text.toString().toIntOrNull()
            ?: throw ConfigurationFormatException.InvalidSwuAccess(), innerProtocol =
            if (home.isEmpty()) null else ConfigurationSwuInnerProtocol(home, roaming)).also(ConfigurationSwuAccessCodec::validate)
    } catch (_: ConfigurationFormatException) { null }
    val error = if (attempted && candidate() == null) "APN 须为有效 ASCII 标签名称，最多 100 字符；接口 MTU 须为 1280–1500 的整数" else null
    OverlayDialog(show = edit != null, title = "SWu 接入参数",
        summary = "用途固定为 IMS。APN 用于 SWu 请求，MTU 用于内层接口；不修改系统 APN、外层地址族或 SIP 传输。确定只修改草稿。",
        onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(state = apn, label = "SWu APN", useLabelAsPlaceholder = false, lineLimits = TextFieldLineLimits.SingleLine,
                enabled = edit != null && !draft.pending, modifier = Modifier.fillMaxWidth()
                    .then(if (error != null) Modifier.configurationErrorSemantics(error) else Modifier))
            TextField(state = mtu, label = "内层接口 MTU", useLabelAsPlaceholder = false, lineLimits = TextFieldLineLimits.SingleLine,
                enabled = edit != null && !draft.pending, modifier = Modifier.fillMaxWidth()
                    .then(if (error != null) Modifier.configurationErrorSemantics(error) else Modifier))
            ConfigurationChoicePreference("本地内层协议", listOf(ConfigurationChoice("", "兼容双栈", "省略手动协议策略，保持原请求")) + swuInnerChoices,
                home, "只约束内层 CP/TS，不改变外层网络。", false, edit != null && !draft.pending) {
                if (home.isEmpty() && it.isNotEmpty()) roaming = it
                home = it
            }
            if (home.isNotEmpty()) ConfigurationChoicePreference("漫游内层协议", swuInnerChoices, roaming,
                "与本地不同须有可信漫游事实；未知时不接入。", false, edit != null && !draft.pending) { roaming = it }
            if (error != null) Text(error, color = LocalSoftVoWiFiColors.current.error)
            ConfigurationActionPair(listOf("取消"), listOf("确定"),
                secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, onDismiss, modifier = m) },
                primary = { m -> ManagerTextAction("确定", ManagerAction.ContextAction, {
                    attempted = true
                    val submitted = candidate()
                    if (edit != null && submitted != null && draft.swuAccess == previous &&
                        draft.replaceSwuAccess(ConfigurationSwuAccess(submitted))) onDismiss()
                }, modifier = m, enabled = edit != null && !draft.pending, primary = true) })
        }
    }
}

private fun swuAccessFactsSummary(facts: ConfigurationSwuAccessFacts): String = when {
    !facts.known -> "未知；请刷新核对"
    facts.compatibilityBaseline -> "兼容接入；未设置手动 APN 与接口 MTU，不代表系统承载或资格成功"
    else -> {
        val policy = facts.value!!.policy!!
        "手动设置 · APN ${policy.requestedApn} · MTU ${policy.interfaceMtu}" + swuInnerSummary(policy.innerProtocol) + "\n这是配置快照，不是资格、注册或协商结果"
    }
}

private val swuInnerChoices = listOf(ConfigurationChoice("ipv4", "IPv4"), ConfigurationChoice("ipv6", "IPv6"),
    ConfigurationChoice("dual-stack", "IPv4 + IPv6"))

private fun swuInnerSummary(inner: ConfigurationSwuInnerProtocol?): String = inner?.let {
    fun label(value: String) = swuInnerChoices.first { it.value == value }.label
    "\n内层请求 · 本地 ${label(it.home)} · 漫游 ${label(it.roaming)}"
}.orEmpty()
