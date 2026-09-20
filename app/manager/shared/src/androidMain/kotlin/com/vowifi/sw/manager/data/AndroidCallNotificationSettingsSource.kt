package com.vowifi.sw.manager.data

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.vowifi.sw.manager.ui.CallNotificationSettingsSnapshot
import com.vowifi.sw.manager.ui.FullScreenStatus
import com.vowifi.sw.manager.ui.IncomingCallChannelStatus
import com.vowifi.sw.manager.ui.NotificationPermissionStatus
import com.vowifi.sw.manager.ui.NotificationSnapshotFreshness
import com.vowifi.sw.manager.ui.SettingsAction
import com.vowifi.sw.manager.ui.ObserverChannelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidCallNotificationSettingsSource(private val context: Context) : CallNotificationSettingsSource {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val mutableState = MutableStateFlow(readSnapshot())
    override val state = mutableState.asStateFlow()

    override fun refresh() {
        mutableState.value = readSnapshot()
    }

    override fun openSettings(action: SettingsAction): CallNotificationSettingsLaunchResult {
        val intent = when (action) {
            SettingsAction.ManageObserverChannel -> {
                if (manager.getNotificationChannel(OBSERVER_CHANNEL) == null) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                } else {
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, OBSERVER_CHANNEL)
                }.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            SettingsAction.ManageNotificationPermission -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            SettingsAction.ManageIncomingCallChannel -> {
                if (manager.getNotificationChannel(AndroidIncomingCallNotificationCoordinator.CHANNEL_ID) == null) {
                    refresh()
                    return CallNotificationSettingsLaunchResult.UNSUPPORTED
                }
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, AndroidIncomingCallNotificationCoordinator.CHANNEL_ID)
            }
            SettingsAction.ManageFullScreenIntent -> {
                if (Build.VERSION.SDK_INT < 34) return CallNotificationSettingsLaunchResult.UNSUPPORTED
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:${context.packageName}"))
            }
            else -> return CallNotificationSettingsLaunchResult.UNSUPPORTED
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            mutableState.value = mutableState.value.copy(freshness = NotificationSnapshotFreshness.STALE)
            CallNotificationSettingsLaunchResult.OPENED
        } catch (_: ActivityNotFoundException) {
            CallNotificationSettingsLaunchResult.FAILED
        } catch (_: SecurityException) {
            CallNotificationSettingsLaunchResult.FAILED
        }
    }

    private fun readSnapshot(): CallNotificationSettingsSnapshot {
        val importance = manager.getNotificationChannel(AndroidIncomingCallNotificationCoordinator.CHANNEL_ID)?.importance
        return CallNotificationSettingsSnapshot(
            permission = if (manager.areNotificationsEnabled()) NotificationPermissionStatus.GRANTED else NotificationPermissionStatus.DENIED,
            fullScreen = when {
                Build.VERSION.SDK_INT < 34 -> FullScreenStatus.NOT_REQUIRED
                manager.canUseFullScreenIntent() -> FullScreenStatus.ALLOWED
                else -> FullScreenStatus.DENIED
            },
            channel = when {
                importance == null -> IncomingCallChannelStatus.MISSING
                importance == NotificationManager.IMPORTANCE_NONE -> IncomingCallChannelStatus.BLOCKED
                importance < NotificationManager.IMPORTANCE_HIGH -> IncomingCallChannelStatus.REDUCED
                else -> IncomingCallChannelStatus.HIGH
            },
            observerChannel = when (manager.getNotificationChannel(OBSERVER_CHANNEL)?.importance) {
                null -> ObserverChannelStatus.MISSING
                NotificationManager.IMPORTANCE_NONE -> ObserverChannelStatus.BLOCKED
                else -> ObserverChannelStatus.ENABLED
            },
        )
    }

    private companion object { const val OBSERVER_CHANNEL = "ims_observer" }
}
