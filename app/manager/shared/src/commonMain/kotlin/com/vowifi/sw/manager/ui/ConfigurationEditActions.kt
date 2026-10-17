package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationSubmit
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun ConfigurationEditActions(
    model: ConfigurationEditorModel,
    draft: ConfigurationEditorDraft,
    submit: ConfigurationSubmit,
) {
    var confirmation by remember(draft) { mutableStateOf<ConfigurationEditConfirmation?>(null) }
    val enabled = model.enabled && !draft.pending
    LaunchedEffect(model.instance, enabled) { confirmation = null }
    ConfigurationCompletionFeedback(draft.completionSequence,
        draft.completionStatus == ConfigurationEditStatus.Failed || draft.completionStatus == ConfigurationEditStatus.Unconfirmed)
    ConfigurationCompletionSummary("编辑状态", configurationEditFooter(draft, model.instance.lifecycle), draft.completionSequence)
    val spatial = MotionTokens.SpringSpatial
    val heightSpec = spring<IntSize>(dampingRatio = spatial.dampingRatio, stiffness = spatial.stiffness)
    AnimatedVisibility(configurationEditActionsVisible(draft),
        enter = expandVertically(heightSpec, expandFrom = Alignment.Top) +
            fadeIn(tween(MotionTokens.DurBase, easing = MotionTokens.EaseEnter)),
        exit = shrinkVertically(heightSpec, shrinkTowards = Alignment.Top) +
            fadeOut(tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard)),
        label = "configuration-dirty-actions",
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ManagerTextAction("放弃修改", ManagerAction.Rejected, onClick = {
                if (draft.dirty) confirmation = ConfigurationEditConfirmation.Discard else draft.discard()
            }, modifier = Modifier.weight(1f), enabled = draft.dirty && !draft.pending)
            ManagerTextAction(
                configurationSubmitLabel(draft, model.instance.lifecycle), ManagerAction.SaveConfiguration,
                onClick = {
                    if (enabled && !draft.pending) {
                        if (draft.dirty && draft.validate() && configurationSaveRestarts(model.instance.lifecycle))
                            confirmation = ConfigurationEditConfirmation.Restart
                        else draft.submit(submit)
                    }
                }, modifier = Modifier.weight(1f), enabled = enabled && draft.dirty, busy = draft.pending, primary = true,
                busyLabel = "正在保存", labelCandidates = listOf("保存配置", "保存并重启", "正在保存"),
            )
        }
    }
    val selected = confirmation
    OverlayDialog(
        show = selected != null,
        title = if (selected == ConfigurationEditConfirmation.Discard) "放弃修改？" else "保存并重启实例？",
        summary = if (selected == ConfigurationEditConfirmation.Discard) "恢复最近读取的配置，不修改设备配置。"
            else "保存后将重启当前引擎实例，连接会暂时中断。",
        onDismissRequest = { confirmation = null },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ManagerTextAction("取消", ManagerAction.Rejected, { confirmation = null }, modifier = Modifier.weight(1f))
            ManagerTextAction(
                if (selected == ConfigurationEditConfirmation.Discard) "放弃修改" else "保存并重启",
                if (selected == ConfigurationEditConfirmation.Discard) ManagerAction.Rejected else ManagerAction.SaveConfiguration,
                onClick = {
                    confirmation = null
                    if (!draft.pending) when (selected) {
                        ConfigurationEditConfirmation.Discard -> draft.discard()
                        ConfigurationEditConfirmation.Restart -> if (enabled) draft.submit(submit)
                        null -> Unit
                    }
                }, modifier = Modifier.weight(1f), enabled = !draft.pending, primary = true,
            )
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
