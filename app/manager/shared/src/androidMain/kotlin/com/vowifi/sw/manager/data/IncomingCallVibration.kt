package com.vowifi.sw.manager.data

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/** 前台通话页接管已撤下通知的振动；后台由系统通知独占提醒。 */
internal class IncomingCallVibration(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var activeKey: String? = null

    fun isActiveFor(key: String): Boolean = activeKey == key

    fun start(key: String) {
        val channel = notifications.getNotificationChannel(AndroidIncomingCallNotificationCoordinator.CHANNEL_ID)
        val interruptionAllowed = when (notifications.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> true
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> channel?.canBypassDnd() == true
            else -> false
        }
        if (channel == null || channel.importance < NotificationManager.IMPORTANCE_DEFAULT ||
            !channel.shouldVibrate() || !notifications.areNotificationsEnabled() || !vibrator.hasVibrator() ||
            audio.ringerMode == AudioManager.RINGER_MODE_SILENT || !interruptionAllowed) {
            stop()
            return
        }
        if (activeKey == key) return
        stop()
        vibrator.vibrate(
            VibrationEffect.createWaveform(channel.vibrationPattern ?: pattern(), 0),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE),
        )
        activeKey = key
    }

    fun stop() {
        if (activeKey == null) return
        vibrator.cancel()
        activeKey = null
    }

    companion object {
        fun pattern() = longArrayOf(0, 500, 500, 500, 1500)
    }
}
