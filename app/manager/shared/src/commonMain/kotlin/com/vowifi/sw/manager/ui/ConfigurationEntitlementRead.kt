package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun ConfigurationEntitlementRead(instance: ConfigurationInstance, enabled: Boolean, reader: EntitlementCacheReader) {
    val revision = instance.configRevision ?: return
    if (instance.systemSubscription !is SubscriptionDiscovery.Matched) return
    val active = enabled && LocalAppRouteActive.current
    key(instance.instanceId, revision, active) {
        var show by remember { mutableStateOf(false) }
        ConfigurationSectionCard {
            ArrowPreference(title = "查看本地资格回执", summary = "读取这张卡上次查询留下的观察，不联网。",
                enabled = active, onClick = { if (active) show = true })
        }
        if (show && active) LocalReceiptDialog(instance.instanceId, revision, reader) { show = false }
    }
}

@Composable
private fun LocalReceiptDialog(id: ConfigurationInstanceId, revision: String, read: EntitlementCacheReader, close: () -> Unit) {
    val fields = remember { List(4) { TextFieldState() } }
    val labels = listOf("服务地址（HTTPS）", "终端厂商", "终端型号", "终端软件版本")
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<EntitlementCacheResult?>(null) }
    val finished = result
    OverlayDialog(show = true, onDismissRequest = close, title = "本地资格回执",
        summary = if (finished == null) "请填写上次请求使用的原值。不匹配的旧回执会失效；输入仅用于本次读取。"
            else "这是本次读取时的本地观察，不代表当前账户已开通或 IMS 已注册。") {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (finished == null) {
                fields.forEachIndexed { index, field ->
                    TextField(state = field, label = labels[index], useLabelAsPlaceholder = false, enabled = !pending,
                        lineLimits = TextFieldLineLimits.SingleLine, modifier = Modifier.fillMaxWidth())
                }
                if (invalid) Text("请输入无账号、查询参数或片段的 HTTPS 地址，并填写原终端信息。", color = LocalSoftVoWiFiColors.current.error)
            } else when (finished) {
                EntitlementCacheResult.Missing -> Text("没有匹配的本地回执")
                is EntitlementCacheResult.Observed -> {
                    Text(when (finished.state) {
                        EntitlementServiceState.Entitled -> "上次服务结果：符合条件"
                        EntitlementServiceState.NotEntitled -> "上次服务结果：不符合条件"
                        EntitlementServiceState.InProgress -> "上次服务结果：处理中"
                        EntitlementServiceState.ActionRequired -> "上次服务结果：需要用户操作"
                    })
                    Text("原回执有效至 ${finished.validUntil}")
                    if (finished.needsTerms) Text("服务报告缺少条款确认")
                    if (finished.needsAddress) Text("服务报告缺少地址资料")
                }
                else -> Text("无法确认本地回执")
            }
            if (finished == null) ConfigurationActionPair(listOf("关闭"), listOf("读取回执"),
                secondary = { m -> ManagerTextAction("关闭", ManagerAction.Rejected, close, modifier = m) },
                primary = { m -> ManagerTextAction(if (pending) "正在读取" else "读取回执", ManagerAction.ContextAction, {
                    if (!pending && result == null) {
                        val source = EntitlementReadContext(fields[0].text.toString(), fields[1].text.toString(),
                            fields[2].text.toString(), fields[3].text.toString())
                        pending = true; invalid = false
                        scope.launch {
                            try {
                                val answer = read(id, revision, source)
                                if (answer == EntitlementCacheResult.InvalidInput) invalid = true else result = answer
                            } finally { pending = false }
                        }
                    }
                }, modifier = m, enabled = !pending && fields.all { it.text.isNotBlank() }, primary = true) })
            else ManagerTextAction("关闭", ManagerAction.ContextAction, close, modifier = Modifier.fillMaxWidth(), primary = true)
        }
    }
}
