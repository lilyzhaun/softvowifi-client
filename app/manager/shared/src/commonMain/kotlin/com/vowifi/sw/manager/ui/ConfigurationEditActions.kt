package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationSubmit
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * C9 save bar: pinned above the page bottom inset whenever the draft is dirty or saving, so the action is
 * reachable from any scroll position. Reports its full height so the list can scroll its last row above it.
 */
@Composable
internal fun ConfigurationSaveBar(
    model: ConfigurationEditorModel,
    draft: ConfigurationEditorDraft,
    submit: ConfigurationSubmit,
    modifier: Modifier = Modifier,
    onHeightChange: (Dp) -> Unit = {},
) {
    var confirmation by remember(draft) { mutableStateOf<ConfigurationEditConfirmation?>(null) }
    val enabled = model.enabled && !draft.pending
    LaunchedEffect(model.instance, enabled) { confirmation = null }
    ConfigurationCompletionFeedback(draft.completionSequence,
        draft.completionStatus == ConfigurationEditStatus.Failed || draft.completionStatus == ConfigurationEditStatus.Unconfirmed)
    val visible = configurationEditActionsVisible(draft)
    val latestHeight = rememberUpdatedState(onHeightChange)
    LaunchedEffect(visible) { if (!visible) latestHeight.value(0.dp) }
    val density = LocalDensity.current
    val bottomInset = LocalSubpageBottomContentInset.current
    val spatial = MotionTokens.SpringSpatial
    val offsetSpec = spring<IntOffset>(dampingRatio = spatial.dampingRatio, stiffness = spatial.stiffness)
    val shape = RoundedCornerShape(CardDefaults.CornerRadius)
    AnimatedVisibility(visible, modifier = modifier,
        enter = slideInVertically(offsetSpec) { it } + fadeIn(tween(MotionTokens.DurBase, easing = MotionTokens.EaseEnter)),
        exit = slideOutVertically(offsetSpec) { it } + fadeOut(tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard)),
        label = "configuration-save-bar",
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = bottomInset + 12.dp)) {
            Card(
                // The list already reserves the bottom inset; it only needs the card plus one 12dp gap.
                // Dark cards match the bar colour and swallow the shadow; the hairline keeps the edge legible.
                modifier = Modifier.fillMaxWidth().onSizeChanged { latestHeight.value(with(density) { it.height.toDp() } + 12.dp) }
                    .dropShadow(shape) {
                        radius = 16.dp.toPx(); offset = Offset(0f, 4.dp.toPx()); color = Color.Black; alpha = .12f
                    }.border(1.dp, LocalSoftVoWiFiColors.current.separator, shape),
                insideMargin = PaddingValues(bottom = 12.dp),
            ) {
                ConfigurationCompletionSummary(configurationSaveBarTitle(draft),
                    configurationSaveBarDetail(draft, model.instance.lifecycle), draft.completionSequence)
                ConfigurationActionPair(listOf("放弃修改"), listOf("保存配置", "保存并重启", "正在保存"),
                    Modifier.padding(horizontal = 16.dp),
                    secondary = { m ->
                        ManagerTextAction("放弃修改", ManagerAction.Rejected, onClick = {
                            if (draft.dirty) confirmation = ConfigurationEditConfirmation.Discard else draft.discard()
                        }, modifier = m, enabled = draft.dirty && !draft.pending)
                    },
                    primary = { m ->
                        ManagerTextAction(
                            configurationSubmitLabel(draft, model.instance.lifecycle), ManagerAction.SaveConfiguration,
                            onClick = {
                                if (enabled && !draft.pending) {
                                    if (draft.dirty && draft.validate() && configurationSaveRestarts(model.instance.lifecycle))
                                        confirmation = ConfigurationEditConfirmation.Restart
                                    else draft.submit(submit)
                                }
                            }, modifier = m, enabled = enabled && draft.dirty, busy = draft.pending, primary = true,
                            busyLabel = "正在保存", labelCandidates = listOf("保存配置", "保存并重启", "正在保存"),
                        )
                    },
                )
            }
        }
    }
    val selected = confirmation
    OverlayDialog(
        show = selected != null,
        title = if (selected == ConfigurationEditConfirmation.Discard) "放弃修改？" else "保存并重启实例？",
        summary = if (selected == ConfigurationEditConfirmation.Discard) "恢复最近读取的配置，不修改设备配置。"
            else listOfNotNull("保存后将重启当前引擎实例，连接会暂时中断。", model.callWarning).joinToString(" "),
        onDismissRequest = { confirmation = null },
    ) {
        val confirmLabel = if (selected == ConfigurationEditConfirmation.Discard) "放弃修改" else "保存并重启"
        ConfigurationActionPair(listOf("取消"), listOf(confirmLabel),
            secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, { confirmation = null }, modifier = m) },
            primary = { m ->
                ManagerTextAction(
                    confirmLabel,
                    if (selected == ConfigurationEditConfirmation.Discard) ManagerAction.Rejected else ManagerAction.SaveConfiguration,
                    onClick = {
                        confirmation = null
                        if (!draft.pending) when (selected) {
                            ConfigurationEditConfirmation.Discard -> draft.discard()
                            ConfigurationEditConfirmation.Restart -> if (enabled) draft.submit(submit)
                            null -> Unit
                        }
                    }, modifier = m, enabled = !draft.pending, primary = true,
                )
            },
        )
    }
}

/**
 * Unsaved edits must not be left silently: toolbar back asks the scene's guard, system and predictive back reach
 * this page handler first. External navigation (incoming call, notification) still bypasses it and keeps the draft.
 */
@Composable
internal fun ConfigurationLeaveGuard(
    model: ConfigurationEditorModel,
    draft: ConfigurationEditorDraft,
    submit: ConfigurationSubmit,
) {
    val controller = LocalSubpageBackController.current
    var asking by remember(draft) { mutableStateOf(false) }
    val guarding = LocalAppRouteActive.current && draft.dirty && !draft.pending
    val latestGuarding = rememberUpdatedState(guarding)
    DisposableEffect(controller) {
        val guard = { if (latestGuarding.value) { asking = true; true } else false }
        controller?.guard = guard
        onDispose { if (controller?.guard === guard) controller.guard = null }
    }
    if (LocalNavigationEventDispatcherOwner.current != null) {
        NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = guarding,
            onBackCompleted = { asking = true })
    }
    LaunchedEffect(guarding) { if (!guarding) asking = false }
    val restart = configurationSaveRestarts(model.instance.lifecycle)
    OverlayDialog(
        show = asking && guarding,
        title = "保存修改？",
        summary = listOfNotNull(configurationLeaveSummary(draft.modifiedCount, model.instance.lifecycle),
            model.callWarning?.takeIf { restart }).joinToString(" "),
        onDismissRequest = { asking = false },
    ) {
        val saveLabel = if (restart) "保存并重启" else "保存"
        ConfigurationActionPair(listOf("放弃修改"), listOf(saveLabel),
            secondary = { m ->
                ManagerTextAction("放弃修改", ManagerAction.Rejected, {
                    asking = false
                    if (!draft.pending) {
                        draft.discard()
                        controller?.leave()
                    }
                }, modifier = m, enabled = !draft.pending)
            },
            primary = { m ->
                ManagerTextAction(saveLabel, ManagerAction.SaveConfiguration, {
                    asking = false
                    if (model.enabled && !draft.pending) {
                        draft.submit(submit)
                        // Accepted (or already confirmed) leaves; invalid, busy, unavailable or a synchronous failure stays.
                        if (draft.pending || !draft.dirty) controller?.leave()
                    }
                }, modifier = m, enabled = model.enabled && !draft.pending, primary = true)
            },
        )
    }
}

/**
 * Two equal actions side by side while both labels fit on one line; otherwise (large font, narrow window) they stack
 * full width with the primary action first, so labels never wrap inside a button.
 */
@Composable
internal fun ConfigurationActionPair(
    secondaryLabels: List<String>,
    primaryLabels: List<String>,
    modifier: Modifier = Modifier,
    secondary: @Composable (Modifier) -> Unit,
    primary: @Composable (Modifier) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MiuixTheme.textStyles.main.merge(fontSize = 14.sp, lineHeight = 20.sp)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val labels = secondaryLabels + primaryLabels
        // Same allowance as ManagerTextAction: widest label, busy spinner room and its 12dp side margins.
        val needed = with(density) { labels.maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }.toDp() } +
            20.dp + 24.dp
        if (needed <= (maxWidth - 12.dp) / 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                secondary(Modifier.weight(1f))
                primary(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                primary(Modifier.fillMaxWidth())
                secondary(Modifier.fillMaxWidth())
            }
        }
    }
}

private enum class ConfigurationEditConfirmation { Discard, Restart }

internal fun configurationSaveRestarts(lifecycle: ConfigurationLifecycle): Boolean =
    lifecycle == ConfigurationLifecycle.RUNNING || lifecycle == ConfigurationLifecycle.STARTING

internal fun configurationEditActionsVisible(draft: ConfigurationEditorDraft): Boolean = draft.dirty || draft.pending

internal fun configurationSubmitLabel(draft: ConfigurationEditorDraft, lifecycle: ConfigurationLifecycle): String = when {
    draft.pending -> "正在保存"
    configurationSaveRestarts(lifecycle) -> "保存并重启"
    else -> "保存配置"
}

internal fun configurationSaveBarTitle(draft: ConfigurationEditorDraft): String {
    val count = draft.modifiedCount.coerceAtLeast(1)
    return if (draft.pending) "正在保存 $count 项修改" else "$count 项修改未保存"
}

/** Outcome text wins over the plain save consequence so a failed or rejected save is never reworded as pending. */
internal fun configurationSaveBarDetail(draft: ConfigurationEditorDraft, lifecycle: ConfigurationLifecycle): String =
    when (draft.status) {
        ConfigurationEditStatus.Idle, ConfigurationEditStatus.Saved, ConfigurationEditStatus.Unchanged ->
            configurationApplyConsequence(lifecycle)
        ConfigurationEditStatus.Invalid -> if (draft.errors.isEmpty()) configurationApplyConsequence(lifecycle)
            else configurationEditSummary(draft)
        else -> configurationEditSummary(draft)
    }

internal fun configurationLeaveSummary(count: Int, lifecycle: ConfigurationLifecycle): String =
    "${count.coerceAtLeast(1)} 项修改尚未保存。" + if (configurationSaveRestarts(lifecycle))
        "保存后将重启当前引擎实例，连接会暂时中断。" else "保存后下次启动生效。"

internal fun configurationEditFooter(draft: ConfigurationEditorDraft, lifecycle: ConfigurationLifecycle): String =
    listOfNotNull(
        configurationEditSummary(draft),
        configurationApplyConsequence(lifecycle).takeIf { draft.dirty && !draft.pending },
    ).joinToString("\n")

internal fun configurationEditSummary(draft: ConfigurationEditorDraft): String = when (draft.status) {
    ConfigurationEditStatus.Saving -> "正在保存配置，请稍候"
    ConfigurationEditStatus.Invalid -> if (draft.errors.isNotEmpty()) "请检查标出的字段；修改尚未保存"
        else if (draft.dirty) "有未保存修改" else "与最近读取的配置一致"
    ConfigurationEditStatus.Failed -> "保存失败，修改已保留；可检查后重试"
    ConfigurationEditStatus.Unconfirmed -> "结果未确认，修改已保留；请先刷新核对，不会自动重试"
    ConfigurationEditStatus.BusyRejected -> "后台正忙，本次未提交；请稍后重试"
    ConfigurationEditStatus.Unavailable -> "配置暂不可用，本次未提交；请刷新后重试"
    ConfigurationEditStatus.Saved -> if (draft.dirty) "有未保存修改" else "配置已保存并读回确认"
    ConfigurationEditStatus.Unchanged -> if (draft.dirty) "有未保存修改" else "没有需要保存的修改"
    ConfigurationEditStatus.Idle -> if (draft.dirty) "有未保存修改" else "与最近读取的配置一致"
}
