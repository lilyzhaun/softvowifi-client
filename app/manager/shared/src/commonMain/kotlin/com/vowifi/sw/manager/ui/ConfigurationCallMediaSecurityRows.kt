package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.*
import top.yukonga.miuix.kmp.basic.BasicComponent

@Composable
internal fun ConfigurationCallMediaSecurityRows(draft: ConfigurationEditorDraft, enabled: Boolean) {
    val actionable by rememberUpdatedState(enabled && LocalAppRouteActive.current && !draft.pending)
    val security = draft.callMedia!!.security
    fun replace(next: ConfigurationCallMediaSecurity?) {
        if (actionable && draft.callMedia?.security == security) draft.replaceCallMedia(draft.callMedia!!.copy(security = next))
    }
    fun choices(suites: List<ConfigurationSrtpSuite>) = suites.map {
        ConfigurationChoice(it.wire, mediaSuiteLabel(it), if (it == ConfigurationSrtpSuite.AES_CM_128)
            "HMAC-SHA1，80 位认证标签" else "AES-GCM，128 位认证标签")
    }
    if (security == null) {
        ConfigurationChoicePreference("媒体保护", choices(ConfigurationSrtpSuite.entries), "",
            "RTP 兼容路径；明确选择套件后建立 SDES 策略，仅用于后续通话。", draft.mediaModified, actionable) { value ->
            replace(ConfigurationCallMediaSecurity("sdes", listOf(ConfigurationSrtpSuite.entries.first { it.wire == value })))
        }
        return
    }
    BasicComponent(title = "密钥协商", summary = "SDES；实际信令保护不满足时拒绝建立通话。密钥由引擎为通话生成，不在此保存或显示。")
    security.suites.forEachIndexed { index, suite ->
        val number = index + 1
        ConfigurationChoicePreference("加密套件 · 优先 $number", choices(ConfigurationSrtpSuite.entries.filter {
            it == suite || it !in security.suites }), suite.wire,
            mediaSuiteLabel(suite), draft.mediaModified, actionable) { value ->
            replace(security.copy(suites = security.suites.mapIndexed { position, old ->
                if (position == index) ConfigurationSrtpSuite.entries.first { it.wire == value } else old
            }))
        }
        if (security.suites.size > 1) {
            ConfigurationChoicePreference("优先顺序 · 套件 $number", (1..security.suites.size).map {
                ConfigurationChoice(it.toString(), "第 $it 位") }, number.toString(),
                "仅为本地协商偏好，实际套件由本地与对端共同确定。", draft.mediaModified, actionable) { value ->
                val moved = security.suites.toMutableList()
                moved.add(value.toInt() - 1, moved.removeAt(index))
                replace(security.copy(suites = moved))
            }
            ManagerTextAction("移除套件 $number", ManagerAction.ContextAction,
                { replace(security.copy(suites = security.suites.filterIndexed { position, _ -> position != index })) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
        }
    }
    if (security.suites.size < ConfigurationSrtpSuite.entries.size) {
        ConfigurationChoicePreference("添加加密套件", choices(ConfigurationSrtpSuite.entries.filter { it !in security.suites }), "",
            "添加到偏好末尾；不重复添加，也不自动改变已有顺序。", false, actionable) { value ->
            replace(security.copy(suites = security.suites + ConfigurationSrtpSuite.entries.first { it.wire == value }))
        }
    }
    ManagerTextAction("清除媒体保护策略", ManagerAction.ContextAction, { replace(null) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), enabled = actionable)
}

internal fun mediaSuiteLabel(suite: ConfigurationSrtpSuite): String = when (suite) {
    ConfigurationSrtpSuite.AES_CM_128 -> "AES-CM 128"
    ConfigurationSrtpSuite.AES_GCM_128 -> "AES-GCM 128"
    ConfigurationSrtpSuite.AES_GCM_256 -> "AES-GCM 256"
}
