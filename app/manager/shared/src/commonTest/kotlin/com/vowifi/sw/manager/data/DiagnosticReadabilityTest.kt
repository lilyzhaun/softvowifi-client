package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticReadabilityTest {
    @Test
    fun configurationMatchExplainsWhichSimWasMatched() {
        val event = DiagnosticEvent(DiagnosticComponent.CONFIGURATION, DiagnosticLevel.INFO,
            DiagnosticName.OBSERVED, DiagnosticStage.CONFIG_MATCH, DiagnosticResult.SUCCESS, slot = 0)
        assertEquals("已找到 SIM 1 的配置。", DiagnosticLabels.summary(event))
    }

    @Test
    fun dnsWriteSaysLocalSendAndDoesNotClaimResolution() {
        val event = DiagnosticEvent(DiagnosticComponent.DNS, DiagnosticLevel.INFO,
            DiagnosticName.OBSERVED, DiagnosticStage.WRITE, DiagnosticResult.SUCCESS, count = 48)
        val summary = DiagnosticLabels.summary(event)
        assertTrue(summary.contains("48 字节"))
        assertTrue(summary.contains("交给系统发送"))
        assertFalse(summary.contains("解析成功"))
    }

    @Test
    fun receivedBytesDoNotClaimDnsAnswerValidation() {
        val event = DiagnosticEvent(DiagnosticComponent.DNS, DiagnosticLevel.INFO,
            DiagnosticName.OBSERVED, DiagnosticStage.RESPONSE, DiagnosticResult.SUCCESS, count = 96)
        val summary = DiagnosticLabels.summary(event)
        assertTrue(summary.contains("96 字节"))
        assertTrue(summary.contains("尚未确认解析结果"))
        assertFalse(summary.contains("解析成功"))
    }

    @Test fun normalTechnicalStepsAreOptionalButFailuresAndCancellationStayKey() {
        val step = DiagnosticEvent(DiagnosticComponent.DNS, DiagnosticLevel.INFO, DiagnosticName.OBSERVED,
            DiagnosticStage.SOCKET_CONTROL, DiagnosticResult.SUCCESS)
        assertFalse(DiagnosticPresentation.isKey(step))
        for (result in listOf(DiagnosticResult.FAILURE, DiagnosticResult.UNAVAILABLE, DiagnosticResult.CANCELLED))
            assertTrue(DiagnosticPresentation.isKey(step.copy(result = result)))
        assertTrue(DiagnosticPresentation.isKey(step.copy(level = DiagnosticLevel.WARN)))
        assertTrue(DiagnosticPresentation.isKey(step.copy(level = DiagnosticLevel.ERROR)))
        assertTrue(DiagnosticPresentation.isKey(step.copy(name = DiagnosticName.RECOVERED)))
        assertFalse(DiagnosticPresentation.isKey(step.copy(component = DiagnosticComponent.CONFIGURATION,
            stage = DiagnosticStage.CONFIG_MATCH)))
    }

    @Test fun onlyTunnelReadinessClaimsTheTunnelIsEstablished() {
        val connected = DiagnosticEvent(DiagnosticComponent.TUNNEL, DiagnosticLevel.INFO, DiagnosticName.OBSERVED,
            DiagnosticStage.CONNECT, DiagnosticResult.SUCCESS)
        assertEquals("VoWiFi 隧道已建立", DiagnosticPresentation.title(connected))
        assertTrue(DiagnosticPresentation.isKey(connected))
        assertFalse(DiagnosticPresentation.title(connected.copy(stage = DiagnosticStage.WRITE)).contains("隧道已建立"))
        assertFalse(DiagnosticPresentation.title(connected.copy(component = DiagnosticComponent.APP)).contains("隧道已建立"))
        assertFalse(DiagnosticLabels.summary(connected).contains("IMS 已注册"))
    }

    @Test fun inactiveConfigurationIsExplainedWithoutClaimingTheSimWasRemoved() {
        val event = DiagnosticEvent(DiagnosticComponent.CONFIGURATION, DiagnosticLevel.INFO, DiagnosticName.OBSERVED,
            DiagnosticStage.CONFIG_MATCH, DiagnosticResult.OBSERVED, DiagnosticReason.NOT_ACTIVE)
        assertEquals("配置对应的 SIM 未启用", DiagnosticPresentation.title(event))
        assertFalse(DiagnosticLabels.summary(event).contains("移除"))
        assertFalse(DiagnosticPresentation.isKey(event))
    }
}
