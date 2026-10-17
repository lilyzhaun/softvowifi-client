package com.vowifi.sw.manager.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.CallHistoryHandle
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.pressable

private val PhoneContextExit = CubicBezierEasing(.2f, 0f, 0f, 1f)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun CallHistoryGroupRow(group: CallHistoryGroup, onOpen: (CallHistoryHandle) -> Unit,
    callbackEnabled: Boolean, onCallback: (String) -> Unit, onDelete: (Set<CallHistoryRecordId>) -> Unit) =
    CallHistoryGroupRow(group, onOpen, callbackEnabled, onCallback, onDelete, enabled = true)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun CallHistoryGroupRow(group: CallHistoryGroup, onOpen: (CallHistoryHandle) -> Unit,
    callbackEnabled: Boolean, onCallback: (String) -> Unit, onDelete: (Set<CallHistoryRecordId>) -> Unit,
    enabled: Boolean, onActionStart: () -> Unit = {}, rootCustomActionsBlocked: Boolean = false) {
    var open by remember(group.handle) { mutableStateOf(false) }
    val active = LocalAppRouteActive.current && enabled
    LaunchedEffect(active) { if (!active) open = false }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val copied = LocalCommunicationCopyFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val activeNow by rememberUpdatedState(active)
    val rootBlocked by rememberUpdatedState(rootCustomActionsBlocked)
    val feedback = managerActionFeedback(ManagerAction.Navigate, active)
    val acceptedClick = rememberManagerActionHandler(feedback) { onOpen(group.handle) }
    val click = { if (activeNow) acceptedClick() }
    val acceptedLongClick = rememberManagerActionHandler(managerActionFeedback(ManagerAction.LongPress, active)) {
        onActionStart(); open = true
    }
    val longClick = { if (activeNow) acceptedLongClick() }
    val contextTarget = rememberCommunicationContextTarget()
    val mounted = remember(group.handle) { mutableStateOf(true) }
    DisposableEffect(mounted) { onDispose { mounted.value = false } }
    val dialNumber = group.peer?.dialNumber()
    val callback by rememberUpdatedState<() -> Boolean>({
        if (!mounted.value || !active || !callbackEnabled || dialNumber == null) false
        else { onActionStart(); open = false; onCallback(dialNumber); true }
    })
    val copyNumber by rememberUpdatedState<() -> Boolean>({
        if (!mounted.value || !active || dialNumber == null) false else {
            onActionStart(); open = false
            scope.launch {
                if (!mounted.value || !activeNow) return@launch
                clipboard.setClipEntry(messageTextClipEntry(dialNumber))
                if (mounted.value && activeNow) copied(CommunicationCopyFeedback.NUMBER)
            }; true
        }
    })
    val deleteGroup by rememberUpdatedState<() -> Boolean>({
        if (!mounted.value || !active || group.ids.size != group.records.size) false
        else { onActionStart(); open = false; onDelete(group.ids); true }
    })
    Column {
        Box {
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 72.dp)
                .communicationContextSource(contextTarget, open && active, hideUnderPreview = false)
                .managerKeyboardFocusRing(interaction, active, androidx.compose.ui.graphics.RectangleShape, phoneColors().accent)
                .communicationContentPress(if (open) ManagerActionFeedback.None else feedback, interaction)
                .semantics {
                    selected = open && active
                    customActions = if (!active || rootBlocked || open || contextTarget.presented) emptyList() else buildList {
                        if (dialNumber != null && callbackEnabled) add(CustomAccessibilityAction("回拨") { mounted.value && !rootBlocked && !open && !contextTarget.presented && callback() })
                        if (dialNumber != null) add(CustomAccessibilityAction("复制号码") { mounted.value && !rootBlocked && !open && !contextTarget.presented && copyNumber() })
                        if (group.ids.size == group.records.size) add(CustomAccessibilityAction("删除") { mounted.value && !rootBlocked && !open && !contextTarget.presented && deleteGroup() })
                    }
                }
                .managerPressInputGate(active)
                .combinedClickable(enabled = active, interactionSource = interaction, indication = null, onClick = click,
                    hapticFeedbackEnabled = false, onLongClickLabel = "通话记录操作", onLongClick = longClick)) {
                CallHistoryGroupContent(group,
                     stackedMetadata = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f)
            }
            CommunicationContextMenu(show = open && active, target = contextTarget, alignEnd = true,
                stableSurface = true, exitEasing = PhoneContextExit,
                onDismiss = { open = false }, previewContent = { progress ->
                    // Keep the original row drawing underneath. The preview must be identical at handoff.
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val stacked = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f
                        Box(Modifier.padding(horizontal = 12.dp * progress)) {
                            CallHistoryGroupContent(group, stackedMetadata = stacked)
                        }
                    }
                }) { finish ->
                    dialNumber?.let {
                        CommunicationMenuRow("回拨", CallPhoneIcons.Phone, { finish { callback() } }, enabled = callbackEnabled)
                        CommunicationMenuRow("复制号码", CommunicationCopy, { finish { copyNumber() } })
                    }
                    CommunicationMenuRow("删除", CommunicationTrash, { finish { deleteGroup() } },
                        enabled = group.ids.size == group.records.size, destructive = true, divider = false)
            }
        }
    }
}

@Composable private fun CallHistoryGroupContent(group: CallHistoryGroup, preview: Boolean = false,
    stackedMetadata: Boolean = false) {
    Column {
        CallHistoryRow(callHistoryRowPresentation(group.latest, kotlin.time.Clock.System.now().toEpochMilliseconds(), ::messageTimestampLabel)
            .copy(timeText = if (group.latest.startedAtMs > 0) messageTimestampLabel(group.latest.startedAtMs) else "时间未知"),
            divider = !preview, stackedMetadata = stackedMetadata)
        if (group.records.size > 1) Text("${group.records.size} 次通话", Modifier.padding(start = 64.dp, bottom = 8.dp),
            fontSize = 13.sp, lineHeight = 18.sp, color = LocalSoftVoWiFiColors.current.textSecondary)
    }
}

@Composable internal fun CallHistoryDeleteConfirmation(history: CallHistoryState.Ready, ids: Set<CallHistoryRecordId>,
    single: Boolean, onCancel: () -> Unit, onDelete: (CallHistoryState.Ready, Set<CallHistoryRecordId>) -> Unit) {
    CommunicationConfirmation(if (single) "删除这条通话记录？" else "删除该号码的通话记录？",
        if (single) "仅删除当前线路的这一条记录，无法撤销。" else "删除当前线路下该号码的全部 ${ids.size} 条记录，其他号码和线路不受影响。无法撤销。",
        "删除", onCancel, { onDelete(history, ids); onCancel() })
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun CallHistoryOccurrence(record: CallRecord, onDelete: (CallHistoryRecordId) -> Unit) {
    CallHistoryOccurrence(record, enabled = true, onDelete = onDelete)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun CallHistoryOccurrence(record: CallRecord, enabled: Boolean, onActionStart: () -> Unit = {},
    rootCustomActionsBlocked: Boolean = false, onDelete: (CallHistoryRecordId) -> Unit) {
    var menu by remember(record.recordId) { mutableStateOf(false) }
    val active = LocalAppRouteActive.current
    LaunchedEffect(active) { if (!active) menu = false }
    val actionable = active && enabled && record.recordId != null
    LaunchedEffect(actionable) { if (!actionable) menu = false }
    val actionableNow by rememberUpdatedState(actionable)
    val rootBlocked by rememberUpdatedState(rootCustomActionsBlocked)
    val interaction = remember(record.recordId) { MutableInteractionSource() }
    val feedback = managerActionFeedback(ManagerAction.LongPress, actionable)
    val acceptedOpenMenu = rememberManagerActionHandler(feedback) { onActionStart(); menu = true }
    val openMenu = { if (actionableNow) acceptedOpenMenu() }
    val currentOpenMenu by rememberUpdatedState(openMenu)
    val contextTarget = rememberCommunicationContextTarget()
    val mounted = remember(record.recordId) { mutableStateOf(true) }
    DisposableEffect(mounted) { onDispose { mounted.value = false } }
    val deleteOccurrence by rememberUpdatedState<() -> Boolean>({
        val id = record.recordId
        if (!mounted.value || !actionable || id == null) false else { onActionStart(); onDelete(id); menu = false; true }
    })
    Box {
        Column(Modifier.fillMaxWidth().communicationContextSource(contextTarget, menu && active, hideUnderPreview = false)
            .focusable(actionable, interaction)
            .managerKeyboardFocusRing(interaction, actionable, androidx.compose.ui.graphics.RectangleShape, phoneColors().accent)
            .managerPressInputGate(actionable)
            .semantics {
                if (actionable) onLongClick("删除通话记录") { if (actionableNow) { openMenu(); true } else false }
                customActions = if (actionable && !rootBlocked && !menu && !contextTarget.presented)
                    listOf(CustomAccessibilityAction("删除") { mounted.value && !rootBlocked && !menu && !contextTarget.presented && deleteOccurrence() }) else emptyList()
            }
            .then(if (actionable) Modifier.pointerInput(record.recordId) {
                detectTapGestures(onLongPress = { currentOpenMenu() })
            } else Modifier)
            // Main pass reaches the inner pressable before the long detector consumes a normal Up.
            .pressable(interactionSource = interaction, indication = null, enabled = actionable,
                delay = communicationTapIndicationDelayMillis())) {
            CallHistoryOccurrenceContent(record,
                Modifier.communicationContentPress(if (menu) ManagerActionFeedback.None else feedback, interaction))
        }
        CommunicationContextMenu(show = menu && actionable, target = contextTarget, alignEnd = true, onDismiss = { menu = false },
            sourceShape = RoundedCornerShape(20.dp), sourceColor = phoneColors().surface,
            stableSurface = true, exitEasing = PhoneContextExit,
            previewContent = { CallHistoryOccurrenceContent(record) }) { finish ->
                CommunicationMenuRow("删除", CommunicationTrash, { finish { deleteOccurrence() } },
                    destructive = true, divider = false)
        }
    }
}

@Composable private fun CallHistoryOccurrenceContent(record: CallRecord, contentModifier: Modifier = Modifier) {
    val palette = phoneColors()
    val missed = record.outcome == CallOutcome.MISSED
    val accent = if (missed) palette.missed else palette.accent
    Column(Modifier.fillMaxWidth().background(palette.surface, RoundedCornerShape(20.dp))
        .padding(horizontal = 16.dp, vertical = 16.dp).then(contentModifier), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val spec = callHistoryRowPresentation(record, kotlin.time.Clock.System.now().toEpochMilliseconds(), ::messageTimestampLabel)
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(accent.copy(alpha = .08f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (record.direction == CallDirection.INCOMING) CommunicationIncoming else CommunicationOutgoing,
                    null, Modifier.size(20.dp), tint = accent)
            }
            Text("${spec.directionText} · ${spec.outcomeText}", Modifier.weight(1f), fontSize = 17.sp,
                lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = if (missed) accent else palette.text)
        }
        Text("时长：${exactCallDuration(record.durationSec)}", fontSize = 16.sp, lineHeight = 24.sp, color = palette.text)
        Text("开始：${exactCallTime(record.startedAtMs)}", fontSize = 14.sp, lineHeight = 20.sp, color = palette.secondary)
        Text("接通：${exactCallTime(record.connectedAtMs)}", fontSize = 14.sp, lineHeight = 20.sp, color = palette.secondary)
        Text("结束：${exactCallTime(record.endedAtMs)}", fontSize = 14.sp, lineHeight = 20.sp, color = palette.secondary)
    }
}
