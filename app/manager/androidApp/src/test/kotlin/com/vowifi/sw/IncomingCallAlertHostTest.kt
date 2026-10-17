package com.vowifi.sw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibratorManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class, shadows = [RuntimeApplicationShadow::class])
class IncomingCallAlertHostTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private val vibrator get() = shadowOf(context.getSystemService(VibratorManager::class.java).defaultVibrator)

    @Before fun allowAlerts() {
        vibrator.setHasVibrator(true)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    @Test fun backgroundCallRequestsLoopingAlertAndRetainsNotificationUntilTerminal() = withCall { state, _ ->
        val incoming = requireNotNull(shadowOf(manager).getNotification(INCOMING))
        assertTrue(incoming.flags and Notification.FLAG_INSISTENT != 0)
        assertTrue(incoming.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(incoming.fullScreenIntent)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertSame(incoming, shadowOf(manager).getNotification(INCOMING))
        assertFalse(vibrator.isVibrating) // 后台仅系统通知持有提醒。
        state.value = ready(state).copy(phase = CallPhase.Idle)
        idle()
        assertNull(shadowOf(manager).getNotification(INCOMING))
    }

    @Test fun freshChannelUsesCallAttributesAndAnExplicitVibrationCadence() = withCall { _, _ ->
        val channel = manager.getNotificationChannel(CHANNEL)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE, channel.audioAttributes.usage)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), channel.sound)
        assertTrue(channel.shouldVibrate())
        assertTrue(channel.vibrationPattern.size >= 4)
    }

    @Test fun foregroundTakesOverVibrationWithoutStoppingWhenCallPageOpens() = withCall { _, coordinator ->
        coordinator.setAppForeground(true)
        assertNull(shadowOf(manager).getNotification(INCOMING))
        assertRepeating()
        val pattern = vibrator.pattern
        coordinator.setCallForeground(true)
        coordinator.setAppForeground(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
        assertRepeating()
        assertSame(pattern, vibrator.pattern) // 可见性及重复状态不能重启波形。
        coordinator.setCallForeground(false)
        assertFalse(vibrator.isVibrating)
        assertNotNull(shadowOf(manager).getNotification(INCOMING))
    }

    @Test fun callAlreadyForegroundRingsUntilAnswerAndOngoingNotificationIsSilent() = withCall(foreground = true) { state, _ ->
        assertRepeating()
        state.value = ready(state).copy(phase = CallPhase.InCall)
        idle()
        assertFalse(vibrator.isVibrating)
        assertNull(shadowOf(manager).getNotification(INCOMING))
        val ongoing = requireNotNull(shadowOf(manager).getNotification(101))
        assertEquals(0, ongoing.flags and Notification.FLAG_INSISTENT)
    }

    @Test fun remoteCancelOrRejectTerminalStopsForegroundVibration() = withCall(foreground = true) { state, _ ->
        assertRepeating()
        state.value = ready(state).copy(phase = CallPhase.Idle)
        idle()
        assertFalse(vibrator.isVibrating)
    }

    @Test fun lostCallStateStopsBothAlertOwnersAndDoesNotReviveOnBackground() = withCall(foreground = true) { state, coordinator ->
        assertRepeating()
        state.value = ready(state).copy(sync = CallSyncState.Unavailable)
        idle()
        assertFalse(vibrator.isVibrating)
        coordinator.setCallForeground(false)
        assertNull(shadowOf(manager).getNotification(INCOMING))
    }

    @Test fun microphonePreparationStopsRingingAndFailedPreparationCanResumeIt() = withCall(foreground = true) { _, coordinator ->
        assertRepeating()
        coordinator.setMicrophoneRunning(true)
        assertFalse(vibrator.isVibrating)
        coordinator.setMicrophoneRunning(false)
        assertRepeating()
    }

    @Test fun acceptingOrRejectingStopsAlertsBeforeEngineReplyAndFailureRestoresThem() = withCall { state, coordinator ->
        for (action in listOf(CallAction.Answer, CallAction.Reject)) {
            state.value = ready(state).copy(pendingAction = action, controlBusy = true)
            idle()
            assertNull(shadowOf(manager).getNotification(INCOMING))
            coordinator.setCallForeground(true)
            assertFalse(vibrator.isVibrating)
            state.value = ready(state).copy(pendingAction = null, controlBusy = false)
            idle()
            assertRepeating()
            coordinator.setCallForeground(false)
            assertNotNull(shadowOf(manager).getNotification(INCOMING))
        }
    }

    @Test fun stoppingCoordinatorCancelsItsVibration() = withCall(foreground = true) { _, coordinator ->
        assertRepeating()
        coordinator.stop()
        assertFalse(vibrator.isVibrating)
        coordinator.setCallForeground(true)
        assertFalse(vibrator.isVibrating)
    }

    @Test fun freshRuntimeClearsLoopingNotificationLeftByPreviousProcess() = withCall { _, previous ->
        val orphan = requireNotNull(shadowOf(manager).getNotification(INCOMING))
        previous.stop()
        manager.notify(INCOMING, orphan)
        val fixture = CallPlatformFixture("IDLE")
        val current = fixture.coordinator(context)
        try {
            current.start()
            idle()
            assertNull(shadowOf(manager).getNotification(INCOMING))
            assertFalse(vibrator.isVibrating)
        } finally { current.stop(); fixture.close() }
    }

    @Test fun disabledChannelVibrationAndPermissionAreRespected() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Existing", NotificationManager.IMPORTANCE_HIGH)
            .apply { enableVibration(false); setSound(null, null) })
        withCall(foreground = true) { _, _ -> assertFalse(vibrator.isVibrating) }
    }

    @Test fun deniedNotificationsDoNotVibrateInForeground() {
        shadowOf(manager).setNotificationsEnabled(false)
        withCall(foreground = true) { _, _ -> assertFalse(vibrator.isVibrating) }
    }

    @Test fun silentModeAndDoNotDisturbStopForegroundVibration() = withCall(foreground = true) { _, coordinator ->
        assertRepeating()
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        coordinator.setCallForeground(true)
        assertFalse(vibrator.isVibrating)
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        coordinator.setCallForeground(true)
        assertRepeating()
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        coordinator.setCallForeground(true)
        assertFalse(vibrator.isVibrating)
    }

    private fun withCall(foreground: Boolean = false,
        block: (MutableStateFlow<CallUiState>, AndroidIncomingCallNotificationCoordinator) -> Unit,
    ) {
        val fixture = CallPlatformFixture("RINGING")
        val state = MutableStateFlow(fixture.source.state.value)
        val coordinator = AndroidIncomingCallNotificationCoordinator(context, state,
            IncomingCallNotificationCallbacks({}, {}), IncomingCallActionReceiver::class.java, CallActivity::class.java)
        try {
            coordinator.setCallForeground(foreground)
            coordinator.start()
            idle()
            block(state, coordinator)
        } finally { coordinator.stop(); fixture.close() }
    }

    private fun assertRepeating() {
        assertTrue(vibrator.isVibrating)
        assertEquals(0, vibrator.repeat)
        assertEquals(VibrationAttributes.USAGE_RINGTONE,
            (vibrator.vibrationAttributesFromLastVibration as VibrationAttributes).usage)
    }
    private fun ready(state: MutableStateFlow<CallUiState>) = state.value as CallUiState.Ready
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private companion object {
        const val CHANNEL = AndroidIncomingCallNotificationCoordinator.CHANNEL_ID
        const val INCOMING = AndroidIncomingCallNotificationCoordinator.NOTIFICATION_ID
    }
}
