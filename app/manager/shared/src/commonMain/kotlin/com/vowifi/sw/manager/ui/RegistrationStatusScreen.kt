package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.RegistrationSnapshot
import com.vowifi.sw.manager.model.RegistrationState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh

@Composable
fun RegistrationStatusScreen(
    state: RegistrationUiState,
    engineState: SimControlUiState,
    onRefresh: () -> Unit,
    onStartEngine: (SimHandle) -> Unit,
    onStopEngine: (SimHandle) -> Unit,
) {
    val presentation = registrationEnginePresentation(engineState)
    RegistrationStatusContent(state, onRefresh) {
        engineItem(presentation, onStartEngine, onStopEngine)
    }
}

internal fun registrationEnginePresentation(state: SimControlUiState): SimControlBandPresentation =
    simControlBandPresentation(state)

@Composable
internal fun RegistrationStatusContent(
    state: RegistrationUiState,
    onRefresh: () -> Unit,
    engineItem: LazyGridScope.() -> Unit,
) {
    val colors = LocalSoftVoWiFiColors.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 600.dp) 2 else 1
        val wide = columns == 2
        LazyVerticalGrid(
            modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth().align(Alignment.TopCenter),
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(if (wide) 24.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                RegistrationUiState.Loading -> {
                    engineItem()
                    statePanelItem(StatePanelSpec(MiuixIcons.Refresh, StatePanelTone.INTERACTIVE, "正在读取 IMS 状态", "读取 observer 日志后显示每张 SIM 的最新事件。", loading = true))
                }
                RegistrationUiState.Empty -> {
                    engineItem()
                    statePanelItem(StatePanelSpec(MiuixIcons.Info, StatePanelTone.NEUTRAL, "尚无 IMS 事件", "observer 运行后，注册变化会自动出现在这里。"))
                }
                is RegistrationUiState.ParseError -> {
                    engineItem()
                    statePanelItem(StatePanelSpec(MiuixIcons.Close, StatePanelTone.ERROR, "日志无法解析", "${state.issues.size} 行无有效事件，请检查 observer 输出。", retryDescription = "重试读取 IMS 状态"), onRefresh)
                }
                is RegistrationUiState.ReadError -> {
                    engineItem()
                    statePanelItem(StatePanelSpec(MiuixIcons.Close, StatePanelTone.ERROR, "无法读取日志", state.message, retryDescription = "重试读取 IMS 状态"), onRefresh)
                }
                is RegistrationUiState.Data -> {
                    item(span = { GridItemSpan(maxLineSpan) }) { OverallStatusBand(state.snapshot) }
                    engineItem()
                    if (state.snapshot.issues.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "已保留 ${state.snapshot.events.size} 个有效事件；另有 ${state.snapshot.issues.size} 行解析失败。",
                                color = colors.warning,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                    items(state.snapshot.latestBySubId, key = { it.subId }) { event ->
                        SubscriptionStatusCard(event)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.statePanelItem(
    spec: StatePanelSpec,
    onRetry: (() -> Unit)? = null,
) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        StatePanel(spec, Modifier.fillMaxWidth(), onRetry)
    }
}

internal fun LazyGridScope.engineItem(
    presentation: SimControlBandPresentation,
    onStart: (SimHandle) -> Unit,
    onStop: (SimHandle) -> Unit,
) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        SimControlBand(presentation, onStart, onStop)
    }
}

@Composable
private fun OverallStatusBand(snapshot: RegistrationSnapshot) {
    val colors = LocalSoftVoWiFiColors.current
    val registered = snapshot.latestBySubId.count { it.normalizedState == RegistrationState.REGISTERED }
    val allRegistered = registered == snapshot.latestBySubId.size
    val anyRegistering = snapshot.latestBySubId.any { it.normalizedState == RegistrationState.REGISTERING }
    val allUnregistered = snapshot.latestBySubId.all { it.normalizedState == RegistrationState.UNREGISTERED }
    val title = when {
        snapshot.issues.isNotEmpty() -> "IMS 数据含告警"
        allRegistered -> "IMS 已注册"
        anyRegistering -> "IMS 正在注册"
        allUnregistered -> "IMS 未注册"
        else -> "IMS 状态混合"
    }
    val accent = when {
        snapshot.issues.isNotEmpty() || anyRegistering -> colors.warning
        allRegistered -> colors.success
        allUnregistered -> colors.error
        else -> colors.neutralStatus
    }
    val icon = when {
        anyRegistering -> MiuixIcons.Refresh
        allUnregistered -> MiuixIcons.Close
        else -> MiuixIcons.Info
    }
    val lastEvent = snapshot.events.maxOf { it.timestampMs }
    val stacked = LocalDensity.current.fontScale >= 1.5f
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(colors.surface)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
                Text(title, color = colors.textPrimary, fontSize = if (stacked) 20.sp else 24.sp, lineHeight = if (stacked) 28.sp else 32.sp, fontWeight = FontWeight.SemiBold)
            }
            if (stacked) {
                Text("$registered / ${snapshot.latestBySubId.size} 张 SIM 已注册", color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                Text("${snapshot.events.size} 个事件", color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            } else {
                Text("$registered / ${snapshot.latestBySubId.size} 张 SIM 已注册 · ${snapshot.events.size} 个事件", color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Text("最后事件 $lastEvent", color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
