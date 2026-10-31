package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.model.EngineExecutableMatch
import com.vowifi.sw.manager.model.EngineIntegritySnapshot
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun aboutEngineInformationRows(integrity: EngineIntegritySnapshot): List<ManagerPreferenceRow.Information> = buildList {
    add(managerInformationPreference("引擎标识", integrity.fileFingerprint ?: "无法读取", MiuixIcons.Info))
    val running = integrity.running
    when {
        running == null -> add(managerInformationPreference("运行实例", "无法读取", MiuixIcons.Info))
        running.isEmpty() -> add(managerInformationPreference("运行实例", "当前没有运行实例", MiuixIcons.Info))
        else -> running.forEachIndexed { index, instance ->
            val title = instance.pid?.let { "运行实例 PID $it" } ?: "运行实例 ${index + 1}"
            val summary = when (instance.match) {
                EngineExecutableMatch.SAME -> "与文件一致"
                EngineExecutableMatch.DIFFERENT -> "与文件不一致"
                EngineExecutableMatch.UNREADABLE -> "无法读取"
            }
            add(managerInformationPreference(title, summary, MiuixIcons.Info))
        }
    }
}

@Composable
internal fun SettingsEngineInformationScreen(readEngineIntegrity: (suspend () -> EngineIntegritySnapshot)? = null) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState().value
    val active = LocalAppRouteActive.current && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    // Last manual result belongs to this page. Opening/resuming never starts a root read.
    var integrity by remember { mutableStateOf<EngineIntegritySnapshot?>(null) }
    val check = rememberManagerRefreshController(
        onRefresh = readEngineIntegrity?.let { read -> suspend {
            integrity = read()
            RefreshResult.Completed
        } },
        enabled = active,
    )
    LaunchedEffect(active, check) { if (!active) check.cancelWaiter() }
    val loading = rememberManagerLoadingState(check.running, integrity != null)
    val showLoading = active && loading.showLoading && check.result != RefreshResult.Cancelled
    val result = integrity
    val rows = if (!showLoading && (result != null || readEngineIntegrity == null)) {
        aboutEngineInformationRows(result ?: EngineIntegritySnapshot())
    } else listOf(
        managerInformationPreference("引擎标识", if (showLoading) "正在读取引擎标识" else "尚未检查", MiuixIcons.Info)
            .copy(availability = if (showLoading) ManagerPreferenceAvailability.Busy else ManagerPreferenceAvailability.Enabled),
        managerInformationPreference("运行实例", "尚未检查", MiuixIcons.Info),
    )
    AppPageScaffold(title = "引擎信息", showTopBar = false, contentHorizontalInset = 12.dp) {
        item(key = "engine-check") {
            Card(insideMargin = PaddingValues(vertical = 4.dp)) {
                BasicComponent(title = "手动检查", summary = "仅手动读取；下方为上次检查结果。",
                    endActions = {
                        ManagerTextAction("检查引擎", ManagerAction.Refresh, { check.request() },
                             enabled = check.enabled, busy = check.running, primary = true)
                    })
            }
        }
        item(key = "engine-results") {
            SmallTitle("检查结果")
            Card(insideMargin = PaddingValues(vertical = 4.dp)) {
                Column { rows.forEach { EngineIntegrityInformationItem(it) } }
            }
        }
    }
}

@Composable
private fun EngineIntegrityInformationItem(row: ManagerPreferenceRow.Information) {
    val summaryLineHeight = with(LocalDensity.current) { 20.sp.toDp() }
    val currentSummary = rememberUpdatedState(row.visibleSummary)
    BasicComponent(
        modifier = Modifier.semantics {
            if (row.showsProgress) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        },
        startAction = { ManagerPreferenceStartIcon(row.startIcon, row.availability) },
    ) {
        Text(row.title, fontSize = MiuixTheme.textStyles.headline1.fontSize, lineHeight = 24.sp,
            fontWeight = FontWeight.Medium, color = BasicComponentDefaults.titleColor().color)
        AnimatedContent(targetState = row.visibleSummary, modifier = Modifier.heightIn(min = summaryLineHeight),
            contentAlignment = Alignment.TopStart,
            transitionSpec = {
                (fadeIn(MotionTokens.Crossfade.alphaSpec) togetherWith fadeOut(MotionTokens.Crossfade.alphaSpec)) using null
            }, label = "engine-information-summary",
        ) { summary ->
            Text(summary, modifier = Modifier.heightIn(min = summaryLineHeight).then(
                if (summary == currentSummary.value) Modifier else Modifier.clearAndSetSemantics { }),
                fontSize = MiuixTheme.textStyles.body2.fontSize, lineHeight = 20.sp,
                color = BasicComponentDefaults.summaryColor().color)
        }
    }
}
