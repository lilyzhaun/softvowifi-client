package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.ui.CallNotificationSettingsSnapshot
import com.vowifi.sw.manager.ui.FullScreenStatus
import com.vowifi.sw.manager.ui.NotificationPermissionStatus
import com.vowifi.sw.manager.ui.SettingsAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CallNotificationSettingsLaunchResult { OPENED, FAILED, UNSUPPORTED }

interface CallNotificationSettingsSource {
    val state: StateFlow<CallNotificationSettingsSnapshot>
    fun refresh()
    fun openSettings(action: SettingsAction): CallNotificationSettingsLaunchResult
}

object UnsupportedCallNotificationSettingsSource : CallNotificationSettingsSource {
    override val state = MutableStateFlow(CallNotificationSettingsSnapshot(
        NotificationPermissionStatus.UNAVAILABLE,
        FullScreenStatus.UNAVAILABLE,
    )).asStateFlow()
    override fun refresh() = Unit
    override fun openSettings(action: SettingsAction) = CallNotificationSettingsLaunchResult.UNSUPPORTED
}
