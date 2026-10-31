package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.SimHandle
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun ConfigurationRebuildAction(row: ConfigurationRowPresentation, pending: Boolean, onRebuild: (SimHandle) -> Boolean) {
    val currentRow by rememberUpdatedState(row)
    val active by rememberUpdatedState(LocalAppRouteActive.current)
    val callback by rememberUpdatedState(onRebuild)
    val busy by rememberUpdatedState(pending)
    var target by remember(row.key) { mutableStateOf<ConfigurationInstance?>(null) }
    var visible by remember(row.key) { mutableStateOf(false) }
    var decision by remember(row.key) { mutableStateOf<Boolean?>(null) }
    var rejected by remember(row.key) { mutableStateOf(false) }
    val confirmPulse = rememberManagerActionHandler(managerActionFeedback(ManagerAction.ProvisionConfiguration)) {}
    val cancelPulse = rememberManagerActionHandler(managerActionFeedback(ManagerAction.Rejected)) {}
    val close: (Boolean) -> Unit = { accepted ->
        if (visible && active) {
            decision = accepted
            visible = false
            if (accepted) confirmPulse() else cancelPulse()
        }
    }
    val allowed = row.canRebuild && row.handle != null && !pending && active
    ManagerTextAction("重建旧配置", ManagerAction.Navigate, onClick = {
        if (currentRow.canRebuild && !busy && active) {
            target = currentRow.instance
            decision = null
            rejected = false
            visible = true
        }
    }, enabled = allowed, busy = pending, busyLabel = "正在重建",
        labelCandidates = listOf("重建旧配置", "正在重建"),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
    if (rejected) BasicComponent(title = "尚未重建", summary = "状态已变化或有其它操作进行中，请刷新核对后重试。")
    LaunchedEffect(active) {
        if (!active) { decision = false; visible = false }
    }
    if (target != null) {
        OverlayDialog(show = visible, title = "重建旧配置？",
            summary = "旧自定义值（包括 DNS、SMSC 和连接参数）将全部清除，并从当前 SIM 重新建立配置。历史记录、日志和 App 偏好保留；不会自动启动引擎。",
            modifier = Modifier.semantics { dialog(); paneTitle = "重建旧配置？" },
            onDismissRequest = { close(false) },
            onDismissFinished = {
                val original = target
                val latest = currentRow.instance
                val sameOwner = original != null && latest != null && original.instanceId == latest.instanceId &&
                    if (original.draftOwnerId != null) original.draftOwnerId == latest.draftOwnerId else original === latest
                val requested = decision == true
                val accepted = requested && active && !busy && currentRow.canRebuild && sameOwner
                decision = null
                target = null
                if (requested) rejected = !accepted || currentRow.handle?.let(callback) != true
            }) {
            Row {
                TextButton("取消", { close(false) }, Modifier.weight(1f), minHeight = 48.dp,
                    enabled = visible && active)
                Spacer(Modifier.width(20.dp))
                TextButton("重建", { close(true) },
                    Modifier.weight(1f), minHeight = 48.dp, enabled = visible && active,
                    colors = ButtonDefaults.textButtonColors(textColor = LocalSoftVoWiFiColors.current.error))
            }
        }
    }
}
