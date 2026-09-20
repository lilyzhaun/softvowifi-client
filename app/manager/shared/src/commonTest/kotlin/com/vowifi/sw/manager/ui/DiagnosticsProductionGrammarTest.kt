package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.DiagnosticCheck
import com.vowifi.sw.manager.model.DiagnosticCheckId
import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsProductionGrammarTest {
    @Test
    fun nonReadyDiagnosticsStatesMapToBoundedSafeManagerStatusWithoutRawExceptionsOrPaths() {
        // Given
        val states = listOf(
            EnvironmentDiagnosticsUiState.Loading to ManagerStatus(
                kind = ManagerStatusKind.Loading,
                title = "正在检查运行环境",
                summary = "完成后将显示模块、权限和运行条件检查。",
                retryDescription = null,
            ),
            EnvironmentDiagnosticsUiState.Failure("java.io.IOException: /data/local/tmp/fail at internal.Native.call") to ManagerStatus(
                kind = ManagerStatusKind.Failure,
                title = "诊断失败",
                summary = "无法读取诊断信息，请稍后刷新。",
                retryDescription = null,
            ),
            EnvironmentDiagnosticsUiState.PermissionDenied("Permission denied for uid 10234") to ManagerStatus(
                kind = ManagerStatusKind.PermissionDenied,
                title = "权限不足",
                summary = "当前权限无法执行环境诊断。",
                retryDescription = null,
            ),
            EnvironmentDiagnosticsUiState.Unavailable("KernelSU not detected on device") to ManagerStatus(
                kind = ManagerStatusKind.Unavailable,
                title = "诊断不可用",
                summary = "当前环境不提供环境诊断。",
                retryDescription = null,
            ),
        )

        // When & Then
        states.forEach { (state, expectedStatus) ->
            val status = diagnosticsManagerStatus(state)
            assertEquals(expectedStatus.kind, status.kind)
            assertEquals(expectedStatus.title, status.title)
            assertEquals(expectedStatus.summary, status.summary)
            assertFalse(status.summary.contains("/data/"))
            assertFalse(status.summary.contains("IOException"))
            assertFalse(status.summary.contains("KernelSU"))
        }
    }

    @Test
    fun diagnosticToneMapsCorrectlyToSemanticTone() {
        // Given & When & Then
        assertEquals(SemanticTone.SUCCESS, DiagnosticTone.SUCCESS.toSemanticTone())
        assertEquals(SemanticTone.WARNING, DiagnosticTone.WARNING.toSemanticTone())
        assertEquals(SemanticTone.ERROR, DiagnosticTone.ERROR.toSemanticTone())
    }

    @Test
    fun diagnosticCheckBadgesExposeSemanticToneAndSoftWrapForAccessibility() {
        // Given
        val check = DiagnosticCheck(
            id = DiagnosticCheckId.ROOT,
            title = "Root 授权状态",
            status = DiagnosticCheckStatus.PASS,
            detail = "已获取 KernelSU root 权限",
            guidance = null,
        )

        // When
        val badge = diagnosticCheckBadgeSpec(check)

        // Then
        assertEquals("通过", badge.label)
        assertEquals(SemanticTone.SUCCESS, badge.tone)
        assertTrue(badge.softWrap)
    }

    @Test
    fun diagnosticUiContractMaintainsSingleScrollOwnerAndCardGrouping() {
        // Given
        val contract = diagnosticsUiContract()

        // Then
        assertEquals(1, contract.verticalScrollOwnerCount)
        assertFalse(contract.embeddedNestedScrollEnabled)
        assertTrue(contract.usesGroupedCards)
    }
}
