package com.vowifi.sw.manager.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info

class ManagerPreferenceItemsTest {
    @Test
    fun grammarPropertiesReflectDisabledErrorAndBusyStates() {
        val enabled = managerNavigatePreference("标题", "正常说明", MiuixIcons.Info)
        val disabled = managerNavigatePreference(
            "标题",
            "正常说明",
            MiuixIcons.Info,
            ManagerPreferenceAvailability.Disabled("不可用原因"),
        )
        val busy = managerNavigatePreference("标题", "正常说明", MiuixIcons.Info, ManagerPreferenceAvailability.Busy)
        val error = managerNavigatePreference(
            "标题",
            "正常说明",
            MiuixIcons.Info,
            ManagerPreferenceAvailability.Error("错误提示"),
        )

        assertEquals("正常说明", enabled.visibleSummary)
        assertTrue(enabled.isVisuallyEnabled)
        assertFalse(enabled.isErrorState)
        assertFalse(enabled.showsProgress)
        assertTrue(enabled.actionable)

        assertEquals("不可用原因", disabled.visibleSummary)
        assertFalse(disabled.isVisuallyEnabled)
        assertFalse(disabled.isErrorState)
        assertFalse(disabled.showsProgress)
        assertFalse(disabled.actionable)

        assertEquals("正常说明", busy.visibleSummary)
        assertTrue(busy.isVisuallyEnabled)
        assertFalse(busy.isErrorState)
        assertTrue(busy.showsProgress)
        assertFalse(busy.actionable)

        assertEquals("错误提示", error.visibleSummary)
        assertTrue(error.isVisuallyEnabled)
        assertTrue(error.isErrorState)
        assertFalse(error.showsProgress)
        assertFalse(error.actionable)
    }

    @Test
    fun preferenceLayoutContractMaintainsStrictStandards() {
        assertEquals(48.dp, ManagerPreferenceLayoutContract.minTouchTarget)
        assertEquals(12.dp, ManagerPreferenceLayoutContract.cardGap)
        assertEquals(4.dp, ManagerPreferenceLayoutContract.cardInsideVerticalPadding)
        assertEquals(6.dp, ManagerPreferenceLayoutContract.startIconEndPadding)
        assertEquals(0.96f, ManagerPreferenceLayoutContract.pressScale)
        assertTrue(ManagerPreferenceLayoutContract.groupedPreferencesUseCardClip)
        assertTrue(ManagerPreferenceLayoutContract.groupedPreferencesApplyExtraPressScale)
        assertFalse(ManagerPreferenceLayoutContract.usesDefaultPlatformGrayIndication)
        assertFalse(ManagerPreferenceLayoutContract.usesGrayFillClippedToRow)
        assertTrue(ManagerPreferenceLayoutContract.suppressesPlatformDefaultIndication)
        assertEquals(1, ManagerPreferenceLayoutContract.actionOwnerCountPerItem)
        assertFalse(ManagerPreferenceLayoutContract.wrapperCardOwnsAction)
        assertTrue(ManagerPreferenceLayoutContract.toggleBehaviorIsExactlyOnce)
    }

    @Test
    fun actionablePreferenceRowsUseNoDefaultGrayIndicationExactPressScaleAndSingleOwner() {
        val navigateEnabled = managerNavigatePreference("外观", "主题与底栏", MiuixIcons.Info)
        val toggle = managerTogglePreference(
            AppearancePreferenceKey.FLOATING_NAV,
            "悬浮底栏",
            "紧凑导航",
            MiuixIcons.Info,
            checked = true,
        )

        val presentationEnabled = managerPreferencePresentation(navigateEnabled)
        val presentationToggle = managerPreferencePresentation(toggle)

        assertFalse(presentationEnabled.usesPlatformDefaultGrayIndication)
        assertFalse(presentationEnabled.usesGrayFillClippedToRow)
        assertTrue(presentationEnabled.suppressesPlatformDefaultIndication)
        assertEquals(0.96f, presentationEnabled.feedback.pressScale)
        assertEquals(1, presentationEnabled.actionOwnerCount)
        assertFalse(presentationEnabled.wrapperCardOwnsAction)

        assertFalse(presentationToggle.usesPlatformDefaultGrayIndication)
        assertFalse(presentationToggle.usesGrayFillClippedToRow)
        assertTrue(presentationToggle.suppressesPlatformDefaultIndication)
        assertEquals(0.96f, presentationToggle.feedback.pressScale)
        assertEquals(1, presentationToggle.actionOwnerCount)
        assertFalse(presentationToggle.wrapperCardOwnsAction)
        assertTrue(presentationToggle.toggleBehaviorIsExactlyOnce)
    }

    @Test
    fun managerPreferencePresentationContractDerivesCorrectMiuixRenderers() {
        val navigateEnabled = managerNavigatePreference("外观", "主题与底栏", MiuixIcons.Info)
        val navigateDisabled = managerNavigatePreference(
            "通知",
            "说明",
            MiuixIcons.Info,
            ManagerPreferenceAvailability.Disabled("环境不支持"),
        )
        val toggle = managerTogglePreference(
            AppearancePreferenceKey.FLOATING_NAV,
            "悬浮底栏",
            "紧凑导航",
            MiuixIcons.Info,
            checked = true,
        )
        val info = managerInformationPreference("版本", "1.0.0", MiuixIcons.Info)

        val presentationEnabled = managerPreferencePresentation(navigateEnabled)
        val presentationDisabled = managerPreferencePresentation(navigateDisabled)
        val presentationToggle = managerPreferencePresentation(toggle)
        val presentationInfo = managerPreferencePresentation(info)

        assertEquals(ManagerPreferenceComponent.ArrowPreference, presentationEnabled.component)
        assertTrue(presentationEnabled.actionable)
        assertEquals(ManagerActionFeedback.PressScale, presentationEnabled.feedback.pressScale)

        assertEquals(ManagerPreferenceComponent.BasicComponent, presentationDisabled.component)
        assertFalse(presentationDisabled.actionable)
        assertEquals(ManagerActionFeedback.None, presentationDisabled.feedback)

        assertEquals(ManagerPreferenceComponent.SwitchPreference, presentationToggle.component)
        assertTrue(presentationToggle.actionable)
        assertEquals(ManagerActionFeedback.PressScale, presentationToggle.feedback.pressScale)

        assertEquals(ManagerPreferenceComponent.BasicComponent, presentationInfo.component)
        assertFalse(presentationInfo.actionable)
        assertEquals(ManagerActionFeedback.None, presentationInfo.feedback)
    }
}
