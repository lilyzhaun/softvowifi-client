package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationSubmit
import top.yukonga.miuix.kmp.basic.BasicComponent
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
    BasicComponent(title = "编辑状态", summary = configurationEditSummary(draft))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ManagerTextAction(
            if (draft.pending) "正在保存配置" else "保存配置", ManagerAction.SaveConfiguration,
            onClick = {
                if (enabled && !draft.pending) {
                    if (draft.dirty && draft.validate() && model.instance.lifecycle in
                        setOf(ConfigurationLifecycle.RUNNING, ConfigurationLifecycle.STARTING)
                    ) confirmation = ConfigurationEditConfirmation.Restart
                    else draft.submit(submit)
                }
            }, enabled = enabled && draft.dirty, busy = draft.pending,
        )
        if (!draft.pending) ManagerTextAction("放弃修改", ManagerAction.Rejected, onClick = {
            if (draft.dirty) confirmation = ConfigurationEditConfirmation.Discard else draft.discard()
        }, enabled = draft.dirty)
    }
    val selected = confirmation
    OverlayDialog(
        show = selected != null,
        title = if (selected == ConfigurationEditConfirmation.Discard) "放弃修改？" else "保存并重启实例？",
        summary = if (selected == ConfigurationEditConfirmation.Discard) "恢复最近读取的配置，不修改设备配置。"
            else "保存后将重启当前引擎实例，连接会暂时中断。",
        onDismissRequest = { confirmation = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ManagerTextAction("取消", ManagerAction.Rejected, { confirmation = null })
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
                }, enabled = !draft.pending,
            )
        }
    }
}

private enum class ConfigurationEditConfirmation { Discard, Restart }

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
