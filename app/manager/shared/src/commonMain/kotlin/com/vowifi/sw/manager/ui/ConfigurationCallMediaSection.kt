package com.vowifi.sw.manager.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.data.ConfigurationCallMediaCodec
import com.vowifi.sw.manager.model.*
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

internal enum class CallMediaField(val title: String, val description: String) {
    Modes("允许模式", "逗号分隔的模式编号。NB 为 0–7，WB 为 0–8；留空允许该编解码的全部模式。"),
    Initial("初始模式", "仅指定本地首帧编码模式，须属于允许集合；留空沿引擎选择。不会缩小允许集合或写入 SDP。"),
    Payload("本地载荷编号", "本地报价偏好：AMR 为 96–127，PCMU 为 0 或 96–127；留空由引擎分配。碰撞时引擎迁移语音编号，不替换 DTMF 或强制对端编号。"),
}

internal data class CallMediaTextEdit(val index: Int, val field: CallMediaField, val sequence: Long)

internal fun LazyListScope.configurationCallMediaSections(instance: ConfigurationInstance, draft: ConfigurationEditorDraft,
    enabled: Boolean, onEdit: (Int, CallMediaField) -> Unit) {
    item(key = "configuration-media-title") { ConfigurationSectionTitle("语音媒体") }
    item(key = "configuration-media-notice") { ConfigurationCallMediaNotice(instance, draft, enabled) }
    // Unknown choices may only be cleared as a whole and read back first.
    if (instance.callMedia == null) return
    if (!draft.callMedia?.speechFormats.isNullOrEmpty()) {
        item(key = "configuration-media-packetization") {
            ConfigurationSectionCard {
                ConfigurationCallPacketizationRow(draft, enabled)
                ConfigurationCallInactivityRow(draft, enabled)
                ConfigurationCallMediaSecurityRows(draft, enabled)
            }
        }
    }
    draft.callMedia?.speechFormats?.forEachIndexed { index, format ->
        item(key = "configuration-media-title-$index") { ConfigurationSectionTitle("格式 ${index + 1} · ${mediaCodecLabel(format.codec)} / ${format.framing.wire.uppercase()}") }
        item(key = "configuration-media-format-$index") {
            ConfigurationSectionCard { ConfigurationCallMediaFormatRows(draft, index, format, enabled, onEdit) }
        }
    }
    item(key = "configuration-media-add") { ConfigurationCallMediaAdd(draft, enabled) }
    item(key = "configuration-media-limits") {
        ConfigurationFootnote("按顺序报价或选择完整格式；AMR-NB / AMR-WB 使用 BE / OA，PCMU 使用 SAMPLE。发送支持 20–240 ms 完整帧分包，PCMU 接收支持上限内的逐样本载荷。接收建议与上限只声明本地接收能力，实际发送遵守对端要求。只改通话组用于下一通，当前通话保持捕获值；不代表网络 QoS 预留、已协商结果或可听验收。")
    }
}

@Composable
private fun ConfigurationCallMediaNotice(instance: ConfigurationInstance, draft: ConfigurationEditorDraft, enabled: Boolean) {
    var clearing by remember(draft) { mutableStateOf(false) }
    val inherited = instance.facts?.callMedia?.takeIf { it.known && it.source == "catalog" }?.value
    val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
    LaunchedEffect(instance.configurationEditAuthority(), actionable) { clearing = false }
    ConfigurationSectionCard {
        BasicComponent(title = if (instance.callMedia == null) "媒体覆盖尚未完整确认" else "仅用于后续新通话", summary = when {
            instance.callMedia == null && draft.callMedia == null -> "请刷新核对配套引擎；也可明确清除整组后保存。不会补猜未读取的旧格式。"
            instance.callMedia == null -> "已选择清除整组。请先保存并读回确认，再添加格式；其它草稿保留。"
            draft.callMedia?.speechFormats.isNullOrEmpty() && inherited != null -> "继承运营商目录的 ${inherited.speechFormats.size} 个格式；编辑目录格式会完整复制到手动草稿，保存前不改变已存配置。"
            draft.callMedia?.speechFormats.isNullOrEmpty() && instance.facts?.callMedia?.source == "user" -> "将恢复运营商目录或引擎兼容基线；请保存并读回确认，不预填解析结果。"
            draft.callMedia?.speechFormats.isNullOrEmpty() -> "继承引擎兼容基线；本地与对端共同协商，不补写运营商默认。可明确添加完整格式。"
            else -> "${draft.callMedia!!.speechFormats.size} 个显式格式，序号小的优先。修改只在草稿中，保存时整组提交。"
        })
        instance.runtimeFacts?.loaded?.callMedia?.let { loaded ->
            BasicComponent(title = "已加载媒体快照", summary = when {
                !loaded.known -> "未知，可刷新核对"
                loaded.compatibilityBaseline -> "引擎兼容基线；不是当前通话的协商结果"
                loaded.source == "catalog" -> "运营商目录 · ${loaded.value!!.speechFormats.size} 个格式；不是当前通话的协商结果"
                else -> "${loaded.value!!.speechFormats.size} 个显式格式；不是当前通话的协商结果"
            })
        }
        draft.mediaError?.let { MediaError(it) }
        if (inherited != null && draft.callMedia == ConfigurationCallMedia()) ManagerTextAction("编辑目录格式", ManagerAction.ContextAction,
            { if (actionable) draft.replaceCallMedia(inherited) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
        if (draft.callMedia != ConfigurationCallMedia()) ManagerTextAction("清除媒体覆盖", ManagerAction.ContextAction,
            { if (actionable) clearing = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
    }
    OverlayDialog(show = clearing && actionable, title = "清除整组媒体覆盖？",
        summary = "移除本组全部手动格式、接收分包时长、媒体静默超时及媒体保护策略，恢复运营商目录；未命中时沿引擎兼容基线。此处只修改草稿，仍需保存；其它配置和当前通话不变。",
        onDismissRequest = { clearing = false }) {
        ConfigurationActionPair(listOf("取消"), listOf("清除整组"),
            secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, { clearing = false }, modifier = m) },
            primary = { m -> ManagerTextAction("清除整组", ManagerAction.ContextAction, {
                if (clearing && actionable) draft.replaceCallMedia(ConfigurationCallMedia())
                clearing = false
            }, modifier = m, enabled = actionable, primary = true) })
    }
}

@Composable
private fun ConfigurationCallMediaFormatRows(draft: ConfigurationEditorDraft, index: Int, format: ConfigurationSpeechFormat,
    enabled: Boolean, onEdit: (Int, CallMediaField) -> Unit) {
    val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
    var clearing by remember(draft) { mutableStateOf(false) }
    LaunchedEffect(draft.callMedia, actionable) { clearing = false }
    var rejected by remember(draft.callMedia) { mutableStateOf(false) }
    fun replace(next: ConfigurationSpeechFormat) {
        if (actionable) rejected = !replaceMediaFormat(draft, index, next)
    }
    val number = index + 1
    ConfigurationChoicePreference("编解码 · 格式 $number", ConfigurationSpeechCodec.entries.map {
        ConfigurationChoice(it.wire, mediaCodecLabel(it)) }, format.codec.wire,
        "AMR 之间切换须核对模式；切换到或离开 PCMU 请移除后添加完整格式，不自动清除参数。", draft.mediaModified, actionable) { value ->
        replace(format.copy(codec = ConfigurationSpeechCodec.entries.first { it.wire == value }))
    }
    val pcmu = format.codec == ConfigurationSpeechCodec.PCMU
    ConfigurationChoicePreference("封装 · 格式 $number", mediaFramings(format.codec).map {
        ConfigurationChoice(it.wire, it.wire.uppercase(), when (it) {
            ConfigurationSpeechFraming.BE -> "带宽高效"
            ConfigurationSpeechFraming.OA -> "八位对齐"
            ConfigurationSpeechFraming.SAMPLE -> "逐样本编码"
        }) },
        format.framing.wire, "同一 PT 保持完整封装，不改变对端已报价的格式。", draft.mediaModified, actionable) { value ->
        replace(format.copy(framing = ConfigurationSpeechFraming.entries.first { it.wire == value }))
    }
    CallMediaField.entries.filter { !pcmu || it == CallMediaField.Payload }.forEach { field ->
        val indication = rememberManagerPressIndication(ManagerPressStyle.CardRow, actionable)
        val handler = rememberManagerActionHandler(if (actionable) managerActionFeedback(ManagerAction.Navigate) else ManagerActionFeedback.None) {
            if (actionable) onEdit(index, field)
        }
        CompositionLocalProvider(LocalIndication provides indication) {
            ArrowPreference(title = "${field.title} · 格式 $number", summary = mediaFieldText(format, field).ifEmpty { "引擎选择" },
                summaryColor = configurationSummaryColor(draft.mediaModified, false), enabled = actionable, onClick = handler,
                modifier = Modifier.defaultMinSize(minHeight = ManagerPreferenceLayoutContract.minTouchTarget).managerPressInputGate(actionable)
                    .then(if (actionable) Modifier else Modifier.semantics(mergeDescendants = true) { disabled() }))
        }
    }
    if (!pcmu) {
        ConfigurationChoicePreference("接收模式周期 · 格式 $number", listOf(
            ConfigurationChoice("", "继承"), ConfigurationChoice("1", "每帧"), ConfigurationChoice("2", "每两帧")),
            format.receiveModeChangePeriod?.toString().orEmpty(), "约束对端发送；每两帧需对端声明支持，否则拒绝该格式。", draft.mediaModified, actionable) {
            replace(format.copy(receiveModeChangePeriod = it.toIntOrNull()))
        }
        ConfigurationChoicePreference("接收相邻模式 · 格式 $number", listOf(
            ConfigurationChoice("", "继承"), ConfigurationChoice("false", "不限制相邻"), ConfigurationChoice("true", "仅相邻模式")),
            format.receiveModeChangeNeighbor?.toString().orEmpty(), "对端模式切换建议；与本地编码器遵守的对端要求分开。", draft.mediaModified, actionable) {
            replace(format.copy(receiveModeChangeNeighbor = it.takeIf(String::isNotEmpty)?.toBooleanStrict()))
        }
    }
    ConfigurationChoicePreference("优先顺序 · 格式 $number", (1..draft.callMedia!!.speechFormats.size).map {
        ConfigurationChoice(it.toString(), "第 $it 位") }, number.toString(), "序号越小越优先；每条完整格式一起移动。", draft.mediaModified, actionable) { value ->
        if (actionable) {
            val moved = draft.callMedia!!.speechFormats.toMutableList()
            moved.add(value.toInt() - 1, moved.removeAt(index))
            draft.replaceCallMedia(draft.callMedia!!.copy(speechFormats = moved))
        }
    }
    if (rejected) MediaError("参数与编解码不兼容或格式重复；跨 AMR / PCMU 请移除后重新添加")
    ManagerTextAction("移除格式 $number", ManagerAction.ContextAction, {
        if (actionable) {
            val policy = draft.callMedia!!
            if (policy.speechFormats.size == 1 && (policy.packetization != null || policy.inactivity != null || policy.security != null)) clearing = true
            else draft.replaceCallMedia(policy.copy(speechFormats = policy.speechFormats.filterIndexed { position, _ -> position != index }))
        }
    }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
    OverlayDialog(show = clearing && actionable, title = "清除整组媒体覆盖？",
        summary = "移除最后一个格式会一并移除接收分包时长、媒体静默超时及媒体保护策略，恢复整组继承。此处只修改草稿，仍需保存；其它配置和当前通话不变。",
        onDismissRequest = { clearing = false }) {
        ConfigurationActionPair(listOf("取消"), listOf("清除整组"),
            secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, { clearing = false }, modifier = m) },
            primary = { m -> ManagerTextAction("清除整组", ManagerAction.ContextAction, {
                if (clearing && actionable) draft.replaceCallMedia(ConfigurationCallMedia())
                clearing = false
            }, modifier = m, enabled = actionable, primary = true) })
    }
}

@Composable
private fun ConfigurationCallPacketizationRow(draft: ConfigurationEditorDraft, enabled: Boolean) {
    var editing by remember(draft) { mutableStateOf(false) }
    var sequence by remember(draft) { mutableLongStateOf(0L) }
    val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
    LaunchedEffect(draft.callMedia, actionable) { editing = false }
    val pair = draft.callMedia!!.packetization
    val preferred = remember(sequence) { TextFieldState(pair?.receivePtimeMs?.toString().orEmpty()) }
    val maximum = remember(sequence) { TextFieldState(pair?.receiveMaxPtimeMs?.toString().orEmpty()) }
    var attempted by remember(sequence) { mutableStateOf(false) }
    val indication = rememberManagerPressIndication(ManagerPressStyle.CardRow, actionable)
    val handler = rememberManagerActionHandler(if (actionable) managerActionFeedback(ManagerAction.Navigate) else ManagerActionFeedback.None) {
        if (actionable) { sequence++; editing = true }
    }
    CompositionLocalProvider(LocalIndication provides indication) {
        ArrowPreference(title = "接收分包时长", summary = pair?.let { "建议 ${it.receivePtimeMs} ms · 上限 ${it.receiveMaxPtimeMs} ms" } ?: "本组引擎兼容能力",
            summaryColor = configurationSummaryColor(draft.mediaModified, false), enabled = actionable, onClick = handler,
            modifier = Modifier.defaultMinSize(minHeight = ManagerPreferenceLayoutContract.minTouchTarget).managerPressInputGate(actionable)
                .then(if (actionable) Modifier else Modifier.semantics(mergeDescendants = true) { disabled() }))
    }
    OverlayDialog(show = editing && actionable, title = "接收分包时长",
        summary = "仅声明本地接收能力，不强制对端发送。成对留空恢复本组兼容能力，不恢复目录中的单项值。",
        onDismissRequest = { editing = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(state = preferred, label = "建议时长（ms）", useLabelAsPlaceholder = false,
                lineLimits = TextFieldLineLimits.SingleLine, enabled = actionable && editing, modifier = Modifier.fillMaxWidth())
            TextField(state = maximum, label = "接收上限（ms）", useLabelAsPlaceholder = false,
                lineLimits = TextFieldLineLimits.SingleLine, enabled = actionable && editing, modifier = Modifier.fillMaxWidth())
            if (attempted) MediaError("请成对填写；建议须为 20–240 ms 的完整 20 ms 帧，上限不得小于建议或超过 240 ms")
            ConfigurationActionPair(listOf("取消"), listOf("确定"),
                secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, { editing = false }, modifier = m) },
                primary = { m -> ManagerTextAction("确定", ManagerAction.ContextAction, {
                    if (actionable && editing) {
                        val candidate = packetizationCandidate(draft.callMedia!!, preferred.text.toString(), maximum.text.toString())
                        val accepted = candidate != null && acceptMediaCandidate(draft, candidate)
                        attempted = !accepted
                        if (accepted) editing = false
                    }
                }, modifier = m, enabled = actionable && editing, primary = true) })
        }
    }
}

private fun packetizationCandidate(policy: ConfigurationCallMedia, preferred: String, maximum: String): ConfigurationCallMedia? {
    val ptime = preferred.trim()
    val limit = maximum.trim()
    if (ptime.isEmpty() && limit.isEmpty()) return policy.copy(packetization = null)
    if (!ptime.matches(Regex("[0-9]{1,3}")) || !limit.matches(Regex("[0-9]{1,3}"))) return null
    return policy.copy(packetization = ConfigurationCallPacketization(ptime.toInt(), limit.toInt()))
}

@Composable
private fun ConfigurationCallMediaAdd(draft: ConfigurationEditorDraft, enabled: Boolean) {
    val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending && draft.callMedia!!.speechFormats.size < 30)
    var rejected by remember(draft.callMedia) { mutableStateOf(false) }
    val formats = ConfigurationSpeechCodec.entries.flatMap { codec -> mediaFramings(codec).map { ConfigurationSpeechFormat(codec, it) } }
    ConfigurationSectionCard {
        ConfigurationChoicePreference("添加语音格式", formats.mapIndexed { index, format ->
            ConfigurationChoice(index.toString(), "${mediaCodecLabel(format.codec)} · ${format.framing.wire.uppercase()}") }, "",
            "明确选择编解码和封装；不自动添加模式或载荷偏好，最多 30 条。", false, actionable) { value ->
            if (actionable) {
                val candidate = draft.callMedia!!.copy(speechFormats = draft.callMedia!!.speechFormats + formats[value.toInt()])
                rejected = !acceptMediaCandidate(draft, candidate)
            }
        }
        if (rejected) MediaError("此完整格式已存在；每组最多一个 PCMU 格式，请编辑原条目或先移除")
    }
}

@Composable
internal fun ConfigurationCallMediaTextDialog(edit: CallMediaTextEdit?, draft: ConfigurationEditorDraft, onDismiss: () -> Unit) {
    var last by remember { mutableStateOf<CallMediaTextEdit?>(null) }
    SideEffect { if (edit != null) last = edit }
    val current = edit ?: last ?: return
    val format = draft.callMedia?.speechFormats?.getOrNull(current.index) ?: return
    val input = remember(current) { TextFieldState(mediaFieldText(format, current.field)) }
    var attempted by remember(current) { mutableStateOf(false) }
    val actionable by rememberUpdatedState(edit != null && LocalAppRouteActive.current && !draft.pending)
    val candidate = mediaTextCandidate(format, current.field, input.text.toString())
    val policy = candidate?.let { draft.callMedia!!.copy(speechFormats = draft.callMedia!!.speechFormats.mapIndexed { index, value -> if (index == current.index) it else value }) }
    OverlayDialog(show = edit != null, title = "${current.field.title} · 格式 ${current.index + 1}",
        summary = current.field.description, onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(state = input, label = current.field.title, useLabelAsPlaceholder = false,
                lineLimits = TextFieldLineLimits.SingleLine, enabled = actionable, modifier = Modifier.fillMaxWidth())
            if (attempted) MediaError("参数无效或与其它格式重复，请核对后再确认")
            ConfigurationActionPair(listOf("取消"), listOf("确定"),
                secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, onDismiss, modifier = m) },
                primary = { m -> ManagerTextAction("确定", ManagerAction.ContextAction, {
                    if (actionable) {
                        val accepted = policy != null && acceptMediaCandidate(draft, policy)
                        attempted = !accepted
                        if (accepted) onDismiss()
                    }
                }, modifier = m, enabled = actionable, primary = true) })
        }
    }
}

private fun mediaCodecLabel(codec: ConfigurationSpeechCodec) = codec.wire.uppercase()
private fun mediaFramings(codec: ConfigurationSpeechCodec) = if (codec == ConfigurationSpeechCodec.PCMU)
    listOf(ConfigurationSpeechFraming.SAMPLE) else listOf(ConfigurationSpeechFraming.BE, ConfigurationSpeechFraming.OA)
private fun mediaFieldText(format: ConfigurationSpeechFormat, field: CallMediaField): String = when (field) {
    CallMediaField.Modes -> format.modeSet?.joinToString(",").orEmpty()
    CallMediaField.Initial -> format.initialMode?.toString().orEmpty()
    CallMediaField.Payload -> format.payloadType?.toString().orEmpty()
}
private fun mediaTextCandidate(format: ConfigurationSpeechFormat, field: CallMediaField, text: String): ConfigurationSpeechFormat? {
    val trimmed = text.trim()
    return when (field) {
        CallMediaField.Modes -> if (trimmed.isEmpty()) format.copy(modeSet = null) else {
            val modes = trimmed.split(',').map { it.trim().toIntOrNull() ?: return null }
            format.copy(modeSet = modes)
        }
        CallMediaField.Initial -> format.copy(initialMode = if (trimmed.isEmpty()) null else trimmed.toIntOrNull() ?: return null)
        CallMediaField.Payload -> format.copy(payloadType = if (trimmed.isEmpty()) null else trimmed.toIntOrNull() ?: return null)
    }
}
private fun replaceMediaFormat(draft: ConfigurationEditorDraft, index: Int, format: ConfigurationSpeechFormat): Boolean =
    acceptMediaCandidate(draft, draft.callMedia!!.copy(speechFormats = draft.callMedia!!.speechFormats.mapIndexed { position, value -> if (index == position) format else value }))
private fun acceptMediaCandidate(draft: ConfigurationEditorDraft, candidate: ConfigurationCallMedia): Boolean {
    return try {
        val canonical = candidate.copy(speechFormats = candidate.speechFormats.map { it.copy(modeSet = it.modeSet?.sorted()) })
        ConfigurationCallMediaCodec.encode(canonical)
        draft.replaceCallMedia(canonical)
    } catch (_: ConfigurationFormatException) { false } // Invalid user input stays editable; no partial group reaches the draft.
}
@Composable
private fun MediaError(message: String) {
    Text(message, color = LocalSoftVoWiFiColors.current.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).configurationErrorSemantics(message))
}
