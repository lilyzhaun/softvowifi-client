package com.vowifi.sw.manager.ui

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Settings

class RootReferenceCorrectionTest {
    @Test
    fun gridKeepsCompanionColumnsButStacksWhenTextNeedsSpace() {
        val spec = HomeTopGridSpec()
        assertEquals(HomeTopGridLayout.SPLIT, homeTopGridLayout(spec, HomeTopGridEnvironment(369f, 1f)))
        assertEquals(HomeTopGridLayout.SPLIT, homeTopGridLayout(spec, HomeTopGridEnvironment(360f, 1.49f)))
        assertEquals(HomeTopGridLayout.STACKED, homeTopGridLayout(spec, HomeTopGridEnvironment(359f, 1f)))
        assertEquals(HomeTopGridLayout.STACKED, homeTopGridLayout(spec, HomeTopGridEnvironment(369f, 1.5f)))
        assertEquals(HomeTopGridLayout.STACKED, homeTopGridLayout(spec, HomeTopGridEnvironment(960f, 2f)))
    }

    @Test
    fun clearAndSendConfirmAcceptedActionsAndSuppressDisabledCallbacks() {
        val events = mutableListOf<String>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                events += "haptic:$hapticFeedbackType"
            }
        }
        listOf(ManagerAction.ClearCallHistory, ManagerAction.SendMessage).forEach { action ->
            managerActionHandler(managerActionFeedback(action), haptics) { events += "accepted" }()
            managerActionHandler(managerActionFeedback(action, false), haptics) { events += "disabled" }()
        }
        assertEquals(listOf("haptic:${HapticFeedbackType.Confirm}", "accepted", "haptic:${HapticFeedbackType.Confirm}", "accepted"), events)
    }

    @Test
    fun rootGuttersMatchSettingsAtPhoneAndWideWidthsWithoutChangingEmbeddedPages() {
        listOf(320.dp, 393.dp, 768.dp, 1280.dp).forEach { width ->
            assertEquals(settingsHubLayoutContract().horizontalInset, pageHorizontalInset(true, width))
        }
        assertEquals(16.dp, pageHorizontalInset(false, 393.dp))
        assertEquals(24.dp, pageHorizontalInset(false, 768.dp))
        assertEquals(0.dp, pageHorizontalInset(false, 393.dp, 0.dp))
    }

    @Test
    fun settingsProjectionUsesConfigurationAndLogIconsWithoutEnablingUnavailableRoutes() {
        val rows = settingsCardGroups(settingsCatalog(settingsSnapshot())).flatMap { it.rows }
        val configuration = rows.single { it.id == SettingsRowId.Root.CONFIGURATION }
        val logs = rows.single { it.id == SettingsRowId.Root.OPERATIONAL_LOGS }
        assertEquals(MiuixIcons.Settings, settingsRowToNavigatePreference(configuration).startIcon)
        assertEquals(MiuixIcons.File, settingsRowToNavigatePreference(logs).startIcon)
        val notification = rows.single { it.id == SettingsRowId.Root.CALL_NOTIFICATION }
        assertTrue(settingsRowToNavigatePreference(notification).actionable)
        val recovery = callNotificationPreferenceRows(
            CallNotificationSettingsSnapshot(NotificationPermissionStatus.DENIED, FullScreenStatus.ALLOWED),
        ).first()
        assertTrue(recovery.actionable)
        assertTrue(callNotificationPreferenceRows(
            CallNotificationSettingsSnapshot(NotificationPermissionStatus.UNAVAILABLE, FullScreenStatus.UNAVAILABLE),
        ).none { it.actionable })
    }

    @Test
    fun navigationSelectionAndCancelInvokeExactlyOnceAndRespectHapticSwitch() {
        val events = mutableListOf<String>()
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                events += "haptic:$hapticFeedbackType"
            }
        }
        managerActionHandler(managerActionFeedback(ManagerAction.Navigate), haptics) { events += "navigate" }()
        managerActionHandler(managerActionFeedback(ManagerAction.SegmentSelection), haptics, false) { events += "select" }()
        managerActionHandler(managerActionFeedback(ManagerAction.Rejected), haptics) { events += "cancel" }()
        managerActionHandler(managerActionFeedback(ManagerAction.Navigate, false), haptics) { events += "disabled" }()
        assertEquals(listOf("haptic:${HapticFeedbackType.ContextClick}", "navigate", "select", "haptic:${HapticFeedbackType.Reject}", "cancel"), events)
    }
}
