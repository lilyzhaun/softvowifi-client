package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationNetworkOutlets
import com.vowifi.sw.manager.model.ConfigurationTransport
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog

internal data class ConfigurationFieldEdit(val key: ConfigurationFieldKey, val sequence: Long)

internal data class ConfigurationFieldSpec(val key: ConfigurationFieldKey, val title: String, val label: String)

internal fun configurationFieldSpec(key: ConfigurationFieldKey): ConfigurationFieldSpec = when (key) {
    ConfigurationFieldKey.Epdg -> ConfigurationFieldSpec(key, "ePDG 地址", "ePDG FQDN")
    ConfigurationFieldKey.Realm -> ConfigurationFieldSpec(key, "IMS 域", "IMS realm")
    ConfigurationFieldKey.Smsc -> ConfigurationFieldSpec(key, "短信中心", "SMSC")
    ConfigurationFieldKey.Dns -> ConfigurationFieldSpec(key, "DNS", "DNS")
    ConfigurationFieldKey.PaniCountry -> ConfigurationFieldSpec(key, "国家参数", "国家代码（用户参数）")
    ConfigurationFieldKey.DigestRealm -> ConfigurationFieldSpec(key, "Digest 认证域", "Digest认证域（可选）")
    ConfigurationFieldKey.SipSecurity -> ConfigurationFieldSpec(key, "SIP 安全策略", "SIP 安全策略")
    ConfigurationFieldKey.PaniInitial -> ConfigurationFieldSpec(key, "初始 PANI", "初始 PANI")
}

internal fun configurationFieldDescription(instance: ConfigurationInstance, key: ConfigurationFieldKey,
    transport: ConfigurationTransport = instance.transport, outlets: ConfigurationNetworkOutlets = ConfigurationNetworkOutlets()): String {
    val automatic = configurationAutomaticValue(instance, key, transport, outlets)?.takeIf { it.isNotBlank() }
    val fallback = automatic?.let { "留空则使用自动值：$it。" } ?: "留空则使用自动值。"
    return when (key) {
        ConfigurationFieldKey.Epdg -> fallback
        ConfigurationFieldKey.Realm -> "IMS 域同时是身份域。$fallback"
        ConfigurationFieldKey.Smsc -> "以 + 开头的短信中心号码。$fallback"
        ConfigurationFieldKey.Dns -> "只对当前所选底层网络生效，每个网络单独保存。$fallback"
        ConfigurationFieldKey.PaniCountry -> "两个 ASCII 字母。留空优先继承匹配的运营商策略，未匹配时使用归属国家（Home MCC），不是当前位置；只覆盖本字段。"
        ConfigurationFieldKey.DigestRealm -> "仅用于 challenge / HA1 realm，不改变 IMPI、IMPU 或 REGISTER 地址；IMS 域仍是身份域。留空自动继承。"
        ConfigurationFieldKey.SipSecurity, ConfigurationFieldKey.PaniInitial -> ""
    }
}

/**
 * Edits one text parameter in place of an always-open form. Confirm validates with the same codec as save and only
 * then writes the draft; the page's save bar still owns persistence, so nothing reaches the backend from here.
 */
@Composable
internal fun ConfigurationFieldDialog(
    edit: ConfigurationFieldEdit?,
    instance: ConfigurationInstance,
    draft: ConfigurationEditorDraft,
    onDismiss: () -> Unit,
    outlets: ConfigurationNetworkOutlets = ConfigurationNetworkOutlets(),
) {
    // Keep the last content while the dialog plays its exit.
    var last by remember { mutableStateOf<ConfigurationFieldEdit?>(null) }
    SideEffect { if (edit != null) last = edit }
    val current = edit ?: last ?: return
    val spec = configurationFieldSpec(current.key)
    val input = remember(current) { TextFieldState(draft.textFor(current.key)?.text?.toString().orEmpty()) }
    var attempted by remember(current) { mutableStateOf(false) }
    val focus = remember(current) { FocusRequester() }
    LaunchedEffect(current, edit != null) {
        if (edit != null) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
    }
    OverlayDialog(
        show = edit != null,
        title = spec.title,
        summary = configurationFieldDescription(instance, current.key, draft.transport, outlets),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Read the live input inside the overlay content so its own recomposition owns the validation result.
            val error = if (attempted) draft.candidateError(current.key, input.text.toString()) else null
            TextField(
                state = input,
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .then(if (error != null) Modifier.configurationErrorSemantics(error) else Modifier),
                label = spec.label,
                useLabelAsPlaceholder = false,
                enabled = edit != null && !draft.pending,
                lineLimits = TextFieldLineLimits.SingleLine,
            )
            val sizeToken = MotionTokens.SpringSpatialFast
            val heightSpec = spring<IntSize>(dampingRatio = sizeToken.dampingRatio, stiffness = sizeToken.stiffness)
            AnimatedVisibility(error != null,
                enter = fadeIn(tween(MotionTokens.DurFast, easing = MotionTokens.EaseStandard)) +
                    expandVertically(heightSpec, expandFrom = Alignment.Top),
                exit = shrinkVertically(heightSpec, shrinkTowards = Alignment.Top),
                label = "configuration-field-dialog-error",
            ) {
                Text(error.orEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    color = LocalSoftVoWiFiColors.current.error, softWrap = true)
            }
            ConfigurationActionPair(listOf("取消"), listOf("确定"),
                secondary = { m -> ManagerTextAction("取消", ManagerAction.Rejected, onDismiss, modifier = m) },
                primary = { m ->
                    ManagerTextAction("确定", ManagerAction.ContextAction, {
                        attempted = true
                        val value = input.text.toString()
                        if (draft.candidateError(current.key, value) == null) {
                            draft.replaceField(current.key, value)
                            onDismiss()
                        }
                    }, modifier = m, enabled = edit != null && !draft.pending, primary = true)
                },
            )
        }
    }
}
