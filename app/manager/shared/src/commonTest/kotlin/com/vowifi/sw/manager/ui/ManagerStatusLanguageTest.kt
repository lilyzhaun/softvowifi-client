package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh

class ManagerStatusLanguageTest {
    @Test
    fun everyBoundedStatusMapsToAnExistingPanelToneAndProgressMeaning() {
        // Given
        val statuses = listOf(
            managerStatus(ManagerStatusKind.Loading, "正在读取", "正在读取 IMS 状态"),
            managerStatus(ManagerStatusKind.Empty, "暂无记录", "当前没有可显示的记录"),
            managerStatus(ManagerStatusKind.Unavailable, "暂不可用", "当前环境不提供此能力"),
            managerStatus(ManagerStatusKind.PermissionDenied, "权限不足", "请授权后重试"),
            managerStatus(ManagerStatusKind.Failure, "读取失败", "请稍后刷新"),
            managerStatus(ManagerStatusKind.Busy, "正在保存", "请等待当前操作完成"),
            managerStatus(ManagerStatusKind.Warning, "状态需注意", "部分配置需要关注"),
        )

        // When
        val panels = statuses.map(ManagerStatus::toStatePanelSpec)

        // Then
        assertEquals(
            listOf(
                StatePanelTone.INTERACTIVE,
                StatePanelTone.NEUTRAL,
                StatePanelTone.NEUTRAL,
                StatePanelTone.ERROR,
                StatePanelTone.ERROR,
                StatePanelTone.INTERACTIVE,
                StatePanelTone.WARNING,
            ),
            panels.map(StatePanelSpec::tone),
        )
        assertEquals(listOf(true, false, false, false, false, true, false), panels.map(StatePanelSpec::loading))
        assertEquals(
            listOf(MiuixIcons.Refresh, MiuixIcons.Info, MiuixIcons.Info, MiuixIcons.Close, MiuixIcons.Close, MiuixIcons.Refresh, MiuixIcons.Info),
            panels.map(StatePanelSpec::icon),
        )
    }

    @Test
    fun retryDescriptionIsAbsentUnlessTheDomainProvidesARealRetry() {
        // Given
        val withoutRetry = managerStatus(ManagerStatusKind.Failure, "读取失败", "请稍后刷新")
        val withRetry = managerStatus(
            ManagerStatusKind.Failure,
            "读取失败",
            "请稍后刷新",
            retryDescription = "重试读取 IMS 状态",
        )

        // When
        val absent = withoutRetry.toStatePanelSpec().retryDescription
        val present = withRetry.toStatePanelSpec().retryDescription

        // Then
        assertNull(absent)
        assertEquals("重试读取 IMS 状态", present)
        assertFailsWith<IllegalArgumentException> {
            managerStatus(ManagerStatusKind.Empty, " ", "当前没有记录")
        }
    }

    @Test
    fun semanticBadgeKeepsIconLabelToneAndUnboundedCjkWrapping() {
        // Given
        val longCjkLabel = "这是用于验证百分之二百字体下仍可换行且不会截断状态含义的长中文标签"

        // When
        val badge = semanticStatusBadge(longCjkLabel, SemanticTone.WARNING)

        // Then
        assertEquals(SemanticTone.WARNING, badge.tone)
        assertEquals(PresentationIcon.WARNING, badge.icon)
        assertTrue(badge.softWrap)
        assertNull(badge.maxLines)
        assertFalse(badge.label.isBlank())
    }
}
