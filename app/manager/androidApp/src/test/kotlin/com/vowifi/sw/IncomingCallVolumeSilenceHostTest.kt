package com.vowifi.sw

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.os.VibratorManager
import android.view.KeyEvent
import com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator
import com.vowifi.sw.manager.data.SoftVoWiFiRuntime
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class,
    shadows = [RuntimeApplicationShadow::class, MessageSourceShadow::class])
class IncomingCallVolumeSilenceHostTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private val vibrator get() = shadowOf(context.getSystemService(VibratorManager::class.java).defaultVibrator)

    @Before fun allowForegroundAlerts() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        vibrator.setHasVibrator(true)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    @Test fun mainActivitySilencesAndConsumesBothVolumeKeysWithoutChangingVolumeOrRejecting() = verifyActivity(MainActivity::class.java)
    @Test fun callActivitySilencesAndConsumesBothVolumeKeysWithoutChangingVolumeOrRejecting() = verifyActivity(CallActivity::class.java)

    private fun verifyActivity(type: Class<out Activity>) = withRuntime { fixture, runtime ->
        val key = requireNotNull(runtime.notifications.keyForCurrent())
        val intent = if (type == CallActivity::class.java) runtime.notifications.openIntent(key)
            else Intent(context, MainActivity::class.java).putExtra(CallActivity.EXTRA_MINIMIZED, key)
        Robolectric.buildActivity(type, intent).use { host ->
            host.create().start().resume(); idle()
            assertTrue("前提：真实Activity可见并由App接管来电振动", vibrator.isVibrating)
            val volumes = listOf(AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC).map(audio::getStreamVolume)
            for (code in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) {
                val consumed = host.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); idle()
                assertFalse("音量键必须停止当前来电波形", vibrator.isVibrating)
                assertTrue("已静音的同来电仍应消费后续音量键", consumed)
                assertTrue(host.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)))
            }
            assertEquals(volumes, listOf(AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC).map(audio::getStreamVolume))
            assertEquals(CallPhase.Ringing, (fixture.source.state.value as CallUiState.Ready).phase)
            assertTrue(fixture.commands.isEmpty())
            host.pause().stop().destroy(); idle()
        }
    }

    @Test fun silencedRingingStaysQuietInBackgroundAndOnReturnButTheNextKeyRings() = withRuntime { fixture, runtime ->
        val key = requireNotNull(runtime.notifications.keyForCurrent())
        val intent = Intent(context, MainActivity::class.java).putExtra(CallActivity.EXTRA_MINIMIZED, key)
        Robolectric.buildActivity(MainActivity::class.java, intent).use { host ->
            host.create().start().resume(); idle()
            assertTrue(vibrator.isVibrating)
            val channel = manager.getNotificationChannel(CHANNEL)
            host.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)); idle()
            assertFalse(vibrator.isVibrating)
            host.pause().stop(); idle()
            val notification = requireNotNull(shadowOf(manager).getNotification(INCOMING))
            assertEquals("android.app.Notification\$CallStyle", notification.extras.getString(Notification.EXTRA_TEMPLATE))
            assertEquals(Notification.CATEGORY_CALL, notification.category)
            assertNotNull(notification.fullScreenIntent)
            assertEquals(0, notification.flags and Notification.FLAG_INSISTENT)
            assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
            assertNotNull(notification.group)
            assertEquals(0, notification.flags and Notification.FLAG_GROUP_SUMMARY)
            val after = manager.getNotificationChannel(CHANNEL)
            assertEquals(channel.sound, after.sound); assertEquals(channel.shouldVibrate(), after.shouldVibrate())
            assertEquals(channel.importance, after.importance)
            assertArrayEquals(channel.vibrationPattern, after.vibrationPattern)
            host.restart().start().resume(); idle()
            assertFalse("恢复前台不能重启已静音来电的波形", vibrator.isVibrating)
            fixture.stage = "IDLE"; fixture.revision++; fixture.source.refresh(); idle()
            fixture.stage = "RINGING"; fixture.token += "-new"; fixture.revision++; fixture.source.refresh(); idle()
            assertNotEquals(key, runtime.notifications.keyForCurrent())
            assertTrue("离开Ringing后新来电必须恢复正常提醒", vibrator.isVibrating)
            assertTrue(fixture.commands.isEmpty())
            host.pause().stop().destroy()
        }
    }

    @Test fun volumeUpCanSilenceFirstAndAReplacementKeyWhileStillRingingIsNotMuted() = withRuntime { fixture, runtime ->
        val key = requireNotNull(runtime.notifications.keyForCurrent())
        val intent = Intent(context, MainActivity::class.java).putExtra(CallActivity.EXTRA_MINIMIZED, key)
        Robolectric.buildActivity(MainActivity::class.java, intent).use { host ->
            host.create().start().resume(); idle()
            assertTrue(vibrator.isVibrating)
            val volume = audio.getStreamVolume(AudioManager.STREAM_RING)
            assertTrue(host.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
            assertTrue(host.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)))
            assertFalse(vibrator.isVibrating)
            runtime.notifications.setCallForeground(true); runtime.notifications.setCallForeground(false); idle()
            assertFalse("同key可见性更新不能复响", vibrator.isVibrating)
            fixture.token += "-replacement"; fixture.revision++; fixture.source.refresh(); idle()
            assertEquals(CallPhase.Ringing, (fixture.source.state.value as CallUiState.Ready).phase)
            assertNotEquals(key, runtime.notifications.keyForCurrent())
            assertTrue("Ringing直接替换key也必须撤销旧静音", vibrator.isVibrating)
            assertTrue(host.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
            assertFalse(vibrator.isVibrating)
            assertEquals(volume, audio.getStreamVolume(AudioManager.STREAM_RING))
            assertTrue(fixture.commands.isEmpty())
            host.pause().stop().destroy()
        }
    }

    @Test fun idleVolumeKeysAndRingingWithoutAppVibrationAreNotConsumed() = withRuntime(channelVibration = false) { fixture, _ ->
        fixture.stage = "IDLE"; fixture.revision++; fixture.source.refresh(); idle()
        Robolectric.buildActivity(MainActivity::class.java).use { host ->
            host.create().start().resume(); idle()
            for (code in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP)) {
                assertFalse(host.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)))
            }
            // Respect an existing user's channel preference, without granting an artificial alert lease.
            assertFalse(manager.getNotificationChannel(CHANNEL).shouldVibrate())
            fixture.stage = "RINGING"; fixture.token += "-quiet"; fixture.revision++; fixture.source.refresh(); idle()
            assertFalse(vibrator.isVibrating)
            assertFalse(host.get().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
            assertTrue(fixture.commands.isEmpty())
            host.pause().stop().destroy()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun withRuntime(channelVibration: Boolean = true, block: (CallPlatformFixture, SoftVoWiFiRuntime) -> Unit) {
        if (!channelVibration) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Existing", NotificationManager.IMPORTANCE_HIGH)
            .apply { enableVibration(false); setSound(null, null) })
        val fixture = CallPlatformFixture("RINGING")
        RuntimeApplicationShadow.callFixture = fixture
        try {
            val application = context as SoftVoWiFiApplication
            val runtime = application.runtime()
            // RuntimeApplicationShadow supplies a shell; unlike production open(), it does not start reminders.
            runtime.notifications.start(); idle()
            try { block(fixture, runtime) } finally {
                try { runtime.notifications.stop() } finally { runtime.calls.lifecycle.close() }
            }
        } finally { RuntimeApplicationShadow.callFixture = null; fixture.close() }
    }

    private companion object {
        const val CHANNEL = AndroidIncomingCallNotificationCoordinator.CHANNEL_ID
        const val INCOMING = AndroidIncomingCallNotificationCoordinator.NOTIFICATION_ID
    }
}
