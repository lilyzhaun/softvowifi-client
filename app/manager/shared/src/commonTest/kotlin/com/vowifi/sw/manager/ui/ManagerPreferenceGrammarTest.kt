package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info

class ManagerPreferenceGrammarTest {
    @Test
    fun navigateToggleAndInformationChooseTheirMiuixComponents() {
        // Given
        val navigate = managerNavigatePreference("配置", "管理当前 SIM 配置", MiuixIcons.Info)
        val toggle = managerTogglePreference(
            AppearancePreferenceKey.FLOATING_NAV,
            "悬浮底栏",
            "使用紧凑悬浮导航",
            MiuixIcons.Info,
            checked = true,
        )
        val information = managerInformationPreference("通知", "当前设备不支持通知权限状态", MiuixIcons.Info)

        // When
        val components = listOf(navigate.renderedComponent, toggle.renderedComponent, information.renderedComponent)

        // Then
        assertEquals(
            listOf(
                ManagerPreferenceComponent.ArrowPreference,
                ManagerPreferenceComponent.SwitchPreference,
                ManagerPreferenceComponent.BasicComponent,
            ),
            components,
        )
        assertTrue(navigate.actionable)
        assertTrue(toggle.actionable)
        assertFalse(information.actionable)
    }

    @Test
    fun disabledBusyAndErrorRowsAreNotActionableAndBusyExposesProgress() {
        // Given
        val disabled = managerNavigatePreference(
            "诊断",
            "当前不可用",
            MiuixIcons.Info,
            ManagerPreferenceAvailability.Disabled("依赖不可用"),
        )
        val busy = managerNavigatePreference("日志", "正在导出日志", MiuixIcons.Info, ManagerPreferenceAvailability.Busy)
        val error = managerNavigatePreference(
            "配置",
            "保存失败，请检查后重试",
            MiuixIcons.Info,
            ManagerPreferenceAvailability.Error("保存失败，请检查后重试"),
        )

        // When
        val rows = listOf(disabled, busy, error)

        // Then
        assertTrue(rows.none { it.actionable })
        assertTrue(busy.showsProgress)
        assertFalse(disabled.showsProgress)
        assertFalse(error.showsProgress)
        assertEquals(ManagerPreferenceComponent.BasicComponent, disabled.renderedComponent)
        assertEquals(ManagerPreferenceComponent.BasicComponent, busy.renderedComponent)
        assertEquals(ManagerPreferenceComponent.BasicComponent, error.renderedComponent)
    }

    @Test
    fun onlyTheAppearanceKeysCanCreateToggleRowsAndCopyCannotBeBlank() {
        // Given
        val expectedKeys = setOf(
            AppearancePreferenceKey.FLOATING_NAV,
            AppearancePreferenceKey.BLUR_ENABLED,
            AppearancePreferenceKey.LIQUID_GLASS_ENABLED,
            AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED,
            AppearancePreferenceKey.HAPTICS_ENABLED,
        )

        // When
        val createdKeys = managerTogglePreferenceKeys

        // Then
        assertEquals(expectedKeys, createdKeys)
        assertFailsWith<IllegalArgumentException> {
            managerInformationPreference(" ", "有说明", MiuixIcons.Info)
        }
        assertFailsWith<IllegalArgumentException> {
            ManagerPreferenceAvailability.Error(" ")
        }
    }
}
