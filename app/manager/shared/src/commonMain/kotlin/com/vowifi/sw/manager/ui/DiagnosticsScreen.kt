package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.DiagnosticCheck
import com.vowifi.sw.manager.model.DiagnosticCheckId
import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Report

internal fun diagnosticStatusIcon(status: DiagnosticCheckStatus): ImageVector = when (status) {
    DiagnosticCheckStatus.PASS -> MiuixIcons.Ok
    DiagnosticCheckStatus.FAIL -> MiuixIcons.Close
    DiagnosticCheckStatus.WARN -> MiuixIcons.Report
}

internal fun diagnosticRecoveryRoute(check: DiagnosticCheck): AppRoute? =
    if (check.status == DiagnosticCheckStatus.PASS) null else when (check.id) {
        DiagnosticCheckId.CONFIG -> AppRoute.SettingsConfiguration
        DiagnosticCheckId.ENGINE -> AppRoute.Home
        else -> null
    }

internal fun LazyListScope.diagnosticItems(state: EnvironmentDiagnosticsUiState, onRetry: (() -> Unit)? = null,
    onOpenConfiguration: (() -> Unit)? = null, onHome: (() -> Unit)? = null,
) {
    when (state) {
        EnvironmentDiagnosticsUiState.Loading,
        is EnvironmentDiagnosticsUiState.Failure,
        is EnvironmentDiagnosticsUiState.PermissionDenied,
        is EnvironmentDiagnosticsUiState.Unavailable,
        -> item(key = "diagnostics-state") {
            val panel = diagnosticsManagerStatus(state).toStatePanelSpec()
            StatePanel(panel.copy(retryDescription = if (onRetry != null && !panel.loading) "重试检查" else null), onRetry = onRetry)
        }
        is EnvironmentDiagnosticsUiState.Ready -> {
            item(key = "diagnostics-overall") {
                OverallDiagnosticsBand(
                    status = state.severity,
                    checkCount = state.checks.size,
                    modifier = Modifier.padding(bottom = diagnosticsUiContract().overallToChecksGapDp.dp),
                )
            }
            item(key = "diagnostics-checks") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(vertical = 4.dp),
                ) {
                    Column {
                        state.checks.forEach { check ->
                            key(check.id) {
                                val recover = when (diagnosticRecoveryRoute(check)) {
                                    AppRoute.SettingsConfiguration -> onOpenConfiguration
                                    AppRoute.Home -> onHome
                                    else -> null
                                }
                                DiagnosticCheckRow(check, onRecover = recover)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OverallDiagnosticsBand(
    status: DiagnosticCheckStatus,
    checkCount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSoftVoWiFiColors.current
    val tone = diagnosticTone(status)
    val semanticTone = tone.toSemanticTone()
    val summaryLines = diagnosticSummaryLines(
        status = status,
        checkCount = checkCount,
        stacked = LocalDensity.current.fontScale >= 1.5f,
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(vertical = 4.dp),
    ) {
        BasicComponent(
            title = diagnosticOverallLabel(status),
            summary = summaryLines.joinToString("\n"),
            startAction = {
                Icon(
                    imageVector = diagnosticStatusIcon(status),
                    contentDescription = null,
                    tint = diagnosticAccent(tone, colors),
                    modifier = Modifier.size(24.dp),
                )
            },
            endActions = {
                SemanticStatusBadge(semanticStatusBadge(diagnosticStatusLabel(status), semanticTone))
            },
        )
    }
}

@Composable
fun DiagnosticCheckRow(check: DiagnosticCheck, modifier: Modifier = Modifier, onRecover: (() -> Unit)? = null) {
    val colors = LocalSoftVoWiFiColors.current
    val tone = diagnosticTone(check.status)
    val summary = if (check.guidance != null) "${check.detail}\n${check.guidance}" else check.detail
    BasicComponent(
        modifier = modifier.fillMaxWidth(),
        title = check.title,
        summary = summary,
        startAction = {
            Icon(
                imageVector = diagnosticStatusIcon(check.status),
                contentDescription = null,
                tint = diagnosticAccent(tone, colors),
                modifier = Modifier.size(20.dp),
            )
        },
        endActions = {
            SemanticStatusBadge(diagnosticCheckBadgeSpec(check))
        },
        bottomAction = {
            if (onRecover != null) {
                val label = when (diagnosticRecoveryRoute(check)) {
                    AppRoute.SettingsConfiguration -> "前往 SIM 与网络配置"
                    AppRoute.Home -> "前往首页"
                    else -> null
                }
                if (label != null) ManagerTextAction(label, ManagerAction.Navigate, onRecover)
            }
        },
    )
}

internal enum class DiagnosticTone {
    SUCCESS,
    WARNING,
    ERROR;

    fun toSemanticTone(): SemanticTone = when (this) {
        SUCCESS -> SemanticTone.SUCCESS
        WARNING -> SemanticTone.WARNING
        ERROR -> SemanticTone.ERROR
    }
}

internal fun diagnosticTone(status: DiagnosticCheckStatus): DiagnosticTone = when (status) {
    DiagnosticCheckStatus.PASS -> DiagnosticTone.SUCCESS
    DiagnosticCheckStatus.WARN -> DiagnosticTone.WARNING
    DiagnosticCheckStatus.FAIL -> DiagnosticTone.ERROR
}

internal fun diagnosticCheckBadgeSpec(check: DiagnosticCheck): SemanticStatusBadgeSpec =
    semanticStatusBadge(diagnosticStatusLabel(check.status), diagnosticTone(check.status).toSemanticTone())

internal fun diagnosticsManagerStatus(state: EnvironmentDiagnosticsUiState): ManagerStatus = when (state) {
    EnvironmentDiagnosticsUiState.Loading -> managerStatus(
        kind = ManagerStatusKind.Loading,
        title = "正在检查运行环境",
        summary = "完成后将显示 Root 后端、权限和运行条件检查。",
    )
    is EnvironmentDiagnosticsUiState.Failure -> managerStatus(
        kind = ManagerStatusKind.Failure,
        title = "诊断失败",
        summary = "无法读取诊断信息，请稍后刷新。",
    )
    is EnvironmentDiagnosticsUiState.PermissionDenied -> managerStatus(
        kind = ManagerStatusKind.PermissionDenied,
        title = "权限不足",
        summary = "当前权限无法执行环境诊断。",
    )
    is EnvironmentDiagnosticsUiState.Unavailable -> managerStatus(
        kind = ManagerStatusKind.Unavailable,
        title = "诊断不可用",
        summary = "当前环境不提供环境诊断。",
    )
    is EnvironmentDiagnosticsUiState.Ready -> error("Ready state uses overall and check cards")
}

internal data class DiagnosticsUiContract(
    val verticalScrollOwnerCount: Int = 1,
    val embeddedNestedScrollEnabled: Boolean = false,
    val usesGroupedCards: Boolean = true,
    val cardGapDp: Int = homeLayoutContract.cardGapDp,
    val overallToChecksGapDp: Int = homeLayoutContract.cardGapDp,
)

internal fun diagnosticsUiContract(): DiagnosticsUiContract = DiagnosticsUiContract()

internal fun diagnosticSummaryLines(
    status: DiagnosticCheckStatus,
    checkCount: Int,
    stacked: Boolean,
): List<String> {
    val count = if (stacked) "$checkCount 项检查已完成" else "已完成 $checkCount 项环境检查"
    val result = diagnosticStatusLabel(status)
    return if (stacked) listOf(count, "结果：$result") else listOf("$count · $result")
}

internal fun diagnosticStatusLabel(status: DiagnosticCheckStatus): String = when (status) {
    DiagnosticCheckStatus.PASS -> "通过"
    DiagnosticCheckStatus.WARN -> "需注意"
    DiagnosticCheckStatus.FAIL -> "未通过"
}

private fun diagnosticOverallLabel(status: DiagnosticCheckStatus): String = when (status) {
    DiagnosticCheckStatus.PASS -> "环境检查通过"
    DiagnosticCheckStatus.WARN -> "环境存在告警"
    DiagnosticCheckStatus.FAIL -> "环境检查未通过"
}

private fun diagnosticAccent(tone: DiagnosticTone, colors: SoftVoWiFiColors): Color = when (tone) {
    DiagnosticTone.SUCCESS -> colors.success
    DiagnosticTone.WARNING -> colors.warning
    DiagnosticTone.ERROR -> colors.error
}
