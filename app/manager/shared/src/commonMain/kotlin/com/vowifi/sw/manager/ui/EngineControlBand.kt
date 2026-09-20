package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineRuntimeStatus
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play

internal enum class EngineActionId { START, STOP }

internal data class EngineCardAction(
    val id: EngineActionId,
    val label: String,
    val enabled: Boolean,
)

@Composable
internal fun EngineControlBandFrame(
    modifier: Modifier,
    message: String?,
    messageTone: EngineTone,
    content: @Composable () -> Unit,
) {
    val colors = LocalSoftVoWiFiColors.current
    Column(
        modifier = modifier.fillMaxWidth().background(colors.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(MiuixIcons.Info, contentDescription = null, tint = colors.interactive, modifier = Modifier.size(22.dp))
            Text("引擎控制", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        message?.let { EngineMessage(it, engineAccent(messageTone, colors)) }
        content()
    }
}

@Composable
internal fun EngineInstanceCard(
    title: String,
    lifecycle: EngineLifecycle,
    pid: Long?,
    runtime: EngineRuntimeStatus?,
    message: String?,
    statusText: String,
    tone: EngineTone,
    action: EngineCardAction,
    showAction: Boolean,
    onAction: () -> Unit,
) {
    val colors = LocalSoftVoWiFiColors.current
    val accent = engineAccent(tone, colors)
    val stacked = LocalDensity.current.fontScale >= 1.5f
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EngineInstanceTitle(title)
                EngineBadge(statusText, accent, engineSurface(tone, colors))
            }
        } else {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                EngineInstanceTitle(title)
                EngineBadge(statusText, accent, engineSurface(tone, colors))
            }
        }
        FactRow("进程", processLabel(pid), mono = pid != null)
        FactRow("隧道", safeUiText(runtime?.tunnel, "未连接"))
        FactRow("IMS", safeUiText(runtime?.ims, "未注册"))
        message?.let { EngineMessage(it, if (tone == EngineTone.ERROR) colors.error else colors.warning) }
        if (showAction) {
            Button(
                onClick = onAction,
                enabled = action.enabled,
                colors = ButtonDefaults.buttonColors(
                    color = colors.interactive,
                    disabledColor = colors.warningSurface,
                    contentColor = colors.surface,
                    disabledContentColor = colors.warning,
                ),
                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 136.dp).semantics {
                    if (!action.enabled && (lifecycle == EngineLifecycle.STARTING || lifecycle == EngineLifecycle.STOPPING)) {
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = action.label
                        progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                    }
                },
            ) {
                Icon(if (action.id == EngineActionId.STOP) MiuixIcons.Pause else MiuixIcons.Play, null, Modifier.size(18.dp))
                Text(action.label, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun EngineInstanceTitle(title: String) {
    val colors = LocalSoftVoWiFiColors.current
    Text(title, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun EngineBadge(label: String, foreground: Color, background: Color) {
    Text(
        text = label,
        modifier = Modifier.widthIn(min = 88.dp).background(background, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp),
        color = foreground,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun EngineMessage(message: String, color: Color) {
    Text(message, color = color, fontSize = 14.sp, lineHeight = 20.sp)
}

internal fun processLabel(pid: Long?): String =
    pid?.toString()?.takeIf { it.length < 15 }?.let { "运行中 ($it)" } ?: if (pid == null) "未运行" else "运行中"

internal fun safeUiText(value: String?, fallback: String): String {
    val text = value?.takeIf { it.isNotBlank() } ?: return fallback
    return text.takeIf { it.length <= 80 && "/data/" !in it && "/proc/" !in it && !IDENTITY_DIGITS.containsMatchIn(it) } ?: fallback
}

internal fun engineLabel(lifecycle: EngineLifecycle): String = when (lifecycle) {
    EngineLifecycle.CONFIG_MISSING -> "缺少配置"
    EngineLifecycle.STOPPED -> "已停止"
    EngineLifecycle.STARTING -> "启动中"
    EngineLifecycle.RUNNING -> "运行中"
    EngineLifecycle.STOPPING -> "停止中"
}

internal enum class EngineTone { SUCCESS, WARNING, ERROR, NEUTRAL }

internal fun engineTone(
    lifecycle: EngineLifecycle,
    runtime: EngineRuntimeStatus?,
    message: String?,
): EngineTone = when {
    message != null -> EngineTone.ERROR
    lifecycle == EngineLifecycle.RUNNING && runtime?.tunnel == "CONNECTED" -> EngineTone.SUCCESS
    lifecycle == EngineLifecycle.STARTING || lifecycle == EngineLifecycle.STOPPING || lifecycle == EngineLifecycle.CONFIG_MISSING -> EngineTone.WARNING
    else -> EngineTone.NEUTRAL
}

private fun engineAccent(tone: EngineTone, colors: SoftVoWiFiColors): Color = when (tone) {
    EngineTone.SUCCESS -> colors.success
    EngineTone.WARNING -> colors.warning
    EngineTone.ERROR -> colors.error
    EngineTone.NEUTRAL -> colors.textPrimary
}

private fun engineSurface(tone: EngineTone, colors: SoftVoWiFiColors): Color = when (tone) {
    EngineTone.SUCCESS -> colors.successSurface
    EngineTone.WARNING -> colors.warningSurface
    EngineTone.ERROR -> colors.errorSurface
    EngineTone.NEUTRAL -> colors.separator
}

private val IDENTITY_DIGITS = Regex("\\d{15,}")
