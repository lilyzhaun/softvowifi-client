package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vowifi.sw.manager.data.CallNotificationSettingsLaunchResult
import com.vowifi.sw.manager.data.CallNotificationSettingsSource
import com.vowifi.sw.manager.data.UnsupportedCallNotificationSettingsSource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.Refresh

internal fun callNotificationPreferenceRows(snapshot: CallNotificationSettingsSnapshot): List<ManagerPreferenceRow.Navigate> =
    callNotificationPage(snapshot).rows.map { row ->
        managerNavigatePreference(
            title = row.title,
            summary = row.summary,
            startIcon = if (row.id == SettingsRowId.NotificationPermission) MiuixIcons.Info else MiuixIcons.Phone,
            availability = if (row.enabled) ManagerPreferenceAvailability.Enabled else ManagerPreferenceAvailability.Disabled(row.summary),
        )
    }

internal class CallNotificationSettingsFeedback {
    var failedAction: SettingsAction? by mutableStateOf(null)
        private set
    var message: String? by mutableStateOf(null)
        private set

    fun open(action: SettingsAction, launch: (SettingsAction) -> CallNotificationSettingsLaunchResult) {
        when (launch(action)) {
            CallNotificationSettingsLaunchResult.OPENED -> {
                failedAction = null
                message = null
            }
            CallNotificationSettingsLaunchResult.FAILED -> {
                failedAction = action
                message = "无法打开系统设置。可重试，或在系统设置中查找 SoftVoWiFi 的应用通知、来电通知渠道或全屏通知权限。"
            }
            CallNotificationSettingsLaunchResult.UNSUPPORTED -> {
                failedAction = null
                message = "当前能力入口不可用。请刷新状态，或在系统设置中查找 SoftVoWiFi 的应用通知。"
            }
        }
    }
}

@Composable
internal fun SettingsCallNotificationsScreen(source: CallNotificationSettingsSource) {
    val snapshot by source.state.collectAsState()
    val feedback = remember(source) { CallNotificationSettingsFeedback() }
    LaunchedEffect(source) { source.refresh() }
    val supported = source !== UnsupportedCallNotificationSettingsSource
    val refresh = rememberManagerRefreshController(if (supported) source::refreshAndAwait else null)
    val rotation = rememberManagerRefreshRotation(refresh)
    if (supported) RegisterManagerRefreshAction(refresh, "刷新通知状态", rotation)
    val inlineRefresh = LocalSubpageTopBarAction.current == null
    val rows = callNotificationPreferenceRows(snapshot)
    val actions = callNotificationPage(snapshot).rows
    AppPageScaffold(title = "通知设置", showTopBar = false, refreshController = refresh, refreshRotation = rotation) {
        managerRefreshResultItem(refresh, "notification-refresh-result")
        if (snapshot.freshness == NotificationSnapshotFreshness.STALE) {
            item(key = "notification-stale") {
                ManagerInformationPreferenceItem(managerInformationPreference("状态待重新读取",
                    "上次快照已失效，请刷新后确认权限与渠道。", MiuixIcons.Info))
            }
        }
        item(key = "notification-title") { SmallTitle("系统能力") }
        item(key = "notification-card") {
            Card(insideMargin = PaddingValues(vertical = ManagerPreferenceLayoutContract.cardInsideVerticalPadding)) {
                Column {
                    rows.forEachIndexed { index, row ->
                        val action = (actions[index].target as? SettingsRowTarget.Action)?.action
                        ManagerNavigatePreferenceItem(row, onClick = action?.let { target ->
                            { feedback.open(target, source::openSettings) }
                        })
                    }
                }
            }
        }
        item(key = "observer-title") { SmallTitle("后台服务") }
        item(key = "observer-card") {
            val observer = observerChannelRow(snapshot)
            Card(insideMargin = PaddingValues(vertical = ManagerPreferenceLayoutContract.cardInsideVerticalPadding)) {
                ManagerNavigatePreferenceItem(
                    managerNavigatePreference(observer.title, observer.summary, MiuixIcons.Info,
                        if (observer.enabled) ManagerPreferenceAvailability.Enabled
                        else ManagerPreferenceAvailability.Disabled(observer.summary)),
                    onClick = if (observer.enabled) {
                        { feedback.open(SettingsAction.ManageObserverChannel, source::openSettings) }
                    } else null,
                )
            }
        }
        feedback.message?.let { message ->
            item(key = "notification-failure") {
                ManagerInformationPreferenceItem(
                    managerInformationPreference("设置未打开", message, MiuixIcons.Info),
                    Modifier.padding(top = settingsHubLayoutContract().cardGap),
                )
            }
        }
        feedback.failedAction?.let { action ->
            item(key = "notification-retry") {
                ManagerTextAction("重试打开设置", ManagerAction.Navigate, { feedback.open(action, source::openSettings) },
                    Modifier.padding(top = settingsHubLayoutContract().cardGap))
            }
        }
        if (supported && inlineRefresh) {
            item(key = "notification-refresh") {
                ManagerIconAction(MiuixIcons.Refresh, "刷新通知状态", ManagerAction.Refresh, { refresh.request() },
                    enabled = refresh.enabled, iconRotation = rotation,
                    modifier = Modifier.padding(top = settingsHubLayoutContract().cardGap))
            }
        }
    }
}
