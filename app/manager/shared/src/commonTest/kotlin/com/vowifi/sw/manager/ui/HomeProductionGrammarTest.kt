package com.vowifi.sw.manager.ui

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeProductionGrammarTest {
    @Test
    fun homeNoticesRouteThroughManagerStatusLanguage() {
        // Given
        val errorNotice = NoticeCardSpec(SemanticTone.ERROR, "无法读取 IMS 状态", "请刷新后重试。")
        val warningNotice = NoticeCardSpec(SemanticTone.WARNING, "IMS 数据含告警", "部分 observer 行无法解析。")
        val neutralNotice = NoticeCardSpec(SemanticTone.NEUTRAL, "环境检查不可用", "当前环境不提供诊断摘要。")
        val busyNotice = NoticeCardSpec(SemanticTone.INTERACTIVE, "正在同步", "请稍候。")

        // When
        val errorStatus = homeNoticeStatus(errorNotice)
        val warningStatus = homeNoticeStatus(warningNotice)
        val neutralStatus = homeNoticeStatus(neutralNotice)
        val busyStatus = homeNoticeStatus(busyNotice)

        // Then
        assertEquals(ManagerStatusKind.Failure, errorStatus.kind)
        assertEquals("无法读取 IMS 状态", errorStatus.title)
        assertEquals("请刷新后重试。", errorStatus.summary)
        assertNull(errorStatus.retryDescription)

        assertEquals(ManagerStatusKind.Warning, warningStatus.kind)
        assertEquals("IMS 数据含告警", warningStatus.title)

        assertEquals(ManagerStatusKind.Unavailable, neutralStatus.kind)
        assertEquals("环境检查不可用", neutralStatus.title)

        assertEquals(ManagerStatusKind.Busy, busyStatus.kind)
        assertEquals("正在同步", busyStatus.title)
    }

    @Test
    fun homeManagerActionMapsStartStopAndProvisionToTypedActionsWithConfirmHaptic() {
        // Given
        val start = HomeActionId.START
        val stop = HomeActionId.STOP
        val provision = HomeActionId.PROVISION

        // When
        val startAction = homeManagerAction(start)
        val stopAction = homeManagerAction(stop)
        val provisionAction = homeManagerAction(provision)

        val startFeedback = managerActionFeedback(startAction, enabled = true)
        val stopFeedback = managerActionFeedback(stopAction, enabled = true)
        val provisionFeedback = managerActionFeedback(provisionAction, enabled = true)

        // Then
        assertEquals(ManagerAction.StartEngine, startAction)
        assertEquals(ManagerAction.StopEngine, stopAction)
        assertEquals(ManagerAction.ProvisionConfiguration, provisionAction)

        assertEquals(HapticFeedbackType.Confirm, startFeedback.hapticType)
        assertEquals(0.96f, startFeedback.pressScale)
        assertTrue(startFeedback.accepted)

        assertEquals(HapticFeedbackType.Confirm, stopFeedback.hapticType)
        assertEquals(0.96f, stopFeedback.pressScale)
        assertTrue(stopFeedback.accepted)

        assertEquals(HapticFeedbackType.Confirm, provisionFeedback.hapticType)
        assertEquals(0.96f, provisionFeedback.pressScale)
        assertTrue(provisionFeedback.accepted)
    }

    @Test
    fun busyAndDisabledHomeActionsProvideNoHapticOrScaleFeedback() {
        // Given
        val disabledStart = managerActionFeedback(homeManagerAction(HomeActionId.START), enabled = false)
        val busyStopFeedback = managerActionFeedback(homeManagerAction(HomeActionId.STOP), enabled = false)

        // Then
        assertNull(disabledStart.hapticType)
        assertNull(disabledStart.pressScale)
        assertFalse(disabledStart.accepted)

        assertNull(busyStopFeedback.hapticType)
        assertNull(busyStopFeedback.pressScale)
        assertFalse(busyStopFeedback.accepted)
    }

    @Test
    fun homeStatusBadgeSpecPreservesToneAndEnablesTextWrapping() {
        // Given
        val text = "已注册 · 运行中"
        val tone = SemanticTone.SUCCESS

        // When
        val badge = homeStatusBadge(text, tone)

        // Then
        assertEquals(text, badge.label)
        assertEquals(tone, badge.tone)
        assertTrue(badge.softWrap)
        assertNull(badge.maxLines)
    }

    @Test
    fun homeNoticeRenderingContractSpecifiesActionableCardAndNoStatePanel() {
        val contract = homeNoticeRenderingContract()

        assertTrue(contract.actionable)
        assertTrue(contract.usesArrowPreference)
        assertTrue(contract.usesCard)
        assertFalse(contract.usesStatePanel)
        val recovery = homeNoticeRenderingContract(navigation = false)
        assertTrue(recovery.actionable)
        assertTrue(recovery.usesCard)
        assertFalse(recovery.usesArrowPreference)
        assertFalse(recovery.usesStatePanel)
    }
}
