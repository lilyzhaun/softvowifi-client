package com.vowifi.sw

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class,
    shadows = [RuntimeApplicationShadow::class, RetirementServiceShadow::class, RetirementNotificationShadow::class])
class CallForegroundRetirementHostTest {
    @Test fun deferredCallStyleCannotBeRepostedAfterThePlainReplacement() = retire(shown = false, beforePlain = false)
    @Test fun deferredCallStyleCannotBeRepostedBeforeThePlainReplacement() = retire(shown = false, beforePlain = true)
    @Test fun alreadyShownCallStyleStillHandsOffToThePlainHangupNotification() = retire(shown = true, beforePlain = false)
    @Test fun lateRemoveCancellationCannotEraseTheDeferredCallsPlainHangupNotification() =
        retire(shown = false, beforePlain = false, lateCancel = true)
    @Test fun lateRemoveCancellationCannotEraseTheShownCallsPlainHangupNotification() =
        retire(shown = true, beforePlain = false, lateCancel = true)

    private fun retire(shown: Boolean, beforePlain: Boolean, lateCancel: Boolean = false) = runBlocking {
        RetirementPlatform.reset()
        val fixture = CallPlatformFixture("IN_CALL")
        RuntimeApplicationShadow.callFixture = fixture
        val app = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val runtime = app.runtime()
        val activity = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        val service = Robolectric.buildService(CallMicrophoneService::class.java).create()
        var destroyed = false
        val manager = app.getSystemService(NotificationManager::class.java)
        try {
            runtime.notifications.start()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val ready = fixture.source.state.value as CallUiState.Ready
            assertEquals(CallPhase.InCall, ready.phase)
            assertTrue(ready.audio is CallAudioState.Failed)
            runtime.calls.lifecycle.setVisible(activity.get(), true)
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.calls.lifecycle.perform(activity.get(), ready.binding!!, MicrophoneCallAction.Restore)
            }
            val intent = requireNotNull(activity.get().serviceIntent)
            service.get().onStartCommand(intent, 0, 7)
            assertTrue(withTimeout(5000) { restore.await() })
            assertTrue(fixture.microphone.foreground)
            val active = requireNotNull(RetirementPlatform.foregroundNotification)
            assertEquals(CALL_STYLE, active.extras.getString(Notification.EXTRA_TEMPLATE))
            assertTrue(active.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            assertTrue(RetirementPlatform.foreground)
            RetirementPlatform.shown = shown
            RetirementPlatform.beforePlain = beforePlain
            RetirementPlatform.lateCancel = lateCancel

            // The real lease-release callback retires the real Service and refreshes Coordinator.
            runtime.calls.lifecycle.close()
            assertFalse(fixture.microphone.foreground)
            assertFalse(RetirementPlatform.foreground)
            assertEquals(7, shadowOf(service.get()).stopSelfId)
            assertTrue("恢复音频不得额外拨号或发送业务命令", fixture.commands.isEmpty())
            val plain = requireNotNull(ordinaryNotification(manager))
            assertNull(plain.extras.getString(Notification.EXTRA_TEMPLATE))
            assertEquals(CallOngoingNotification.CHANNEL, plain.channelId)
            assertEquals("挂断", plain.actions.single().title.toString())
            assertNotNull(plain.actions.single().actionIntent)
            assertEquals(NotificationManager.IMPORTANCE_LOW,
                manager.getNotificationChannel(CallOngoingNotification.CHANNEL).importance)
            RetirementPlatform.drain()
            assertTrue("系统异步重投递拒绝：${RetirementPlatform.rejections}", RetirementPlatform.rejections.isEmpty())
            assertNotNull("系统延后取消旧FGS通知不能误撤新的普通挂断通知", ordinaryNotification(manager))
            assertNotNull(shadowOf(manager).getNotification(CallOngoingNotification.WITHOUT_MICROPHONE_ID))
            assertNull(shadowOf(manager).getNotification(CallOngoingNotification.ID))
            service.destroy()
            destroyed = true
            assertNotNull("Service销毁不能撤掉交接后的普通通知",
                ordinaryNotification(manager))
        } finally {
            runtime.calls.lifecycle.close(); runtime.notifications.stop()
            if (!destroyed) service.destroy()
            activity.close(); fixture.close()
            RuntimeApplicationShadow.callFixture = null
            shadowOf(android.os.Looper.getMainLooper()).idle()
            RetirementPlatform.reset()
        }
    }

    private fun ordinaryNotification(manager: NotificationManager): Notification? = shadowOf(manager).allNotifications
        .singleOrNull { it.channelId == CallOngoingNotification.CHANNEL &&
            it.extras.getString(Notification.EXTRA_TEMPLATE) != CALL_STYLE }
}

private const val CALL_STYLE = "android.app.Notification\$CallStyle"

/** Minimal Android 16 policy model, not an embedded AMS/NMS or an actual system crash.
 * ActiveServices: non-REMOVE may force an undisplayed notification via postNotification(false).
 * ServiceRecord: capture that object before DETACH clears ownership; execute on a later handler.
 * NMS: no live FGS => strip FGS flag, then reject ownerless CallStyle without FSI.
 * See the pinned AOSP source links and limitations in the task document. */
internal object RetirementPlatform {
    var foreground = false
    var foregroundNotification: Notification? = null
    var shown = false
    var beforePlain = false
    var lateCancel = false
    var capturingServiceCancel = false
    private val posts = ArrayDeque<() -> Unit>()
    private val cancellations = ArrayDeque<() -> Unit>()
    val rejections = mutableListOf<String>()

    fun reset() {
        foreground = false; foregroundNotification = null; shown = false; beforePlain = false
        lateCancel = false; capturingServiceCancel = false
        posts.clear(); cancellations.clear(); rejections.clear()
    }

    fun admit(id: Int, notification: Notification, byForegroundService: Boolean) {
        if (foreground && id == CallOngoingNotification.ID) {
            notification.flags = notification.flags or Notification.FLAG_FOREGROUND_SERVICE
            foregroundNotification = notification
        } else notification.flags = notification.flags and Notification.FLAG_FOREGROUND_SERVICE.inv()
        if (notification.extras.getString(Notification.EXTRA_TEMPLATE) == CALL_STYLE &&
            notification.flags and Notification.FLAG_FOREGROUND_SERVICE == 0 &&
            notification.fullScreenIntent == null && !byForegroundService) {
            throw IllegalArgumentException("CallStyle notifications must be for a foreground service or use a fullScreenIntent")
        }
    }

    fun retire(flags: Int) {
        if (foreground && flags and Service.STOP_FOREGROUND_REMOVE == 0 && !shown) {
            val captured = requireNotNull(foregroundNotification)
            posts.addLast {
                try { admit(CallOngoingNotification.ID, captured, byForegroundService = false) }
                catch (error: IllegalArgumentException) { rejections += "CannotPostForegroundServiceNotificationException: ${error.message}" }
            }
        }
        foreground = false; foregroundNotification = null
    }

    fun cancel(operation: () -> Unit) {
        if (capturingServiceCancel && lateCancel) cancellations.addLast(operation) else operation()
    }

    fun drain() {
        repeat(posts.size) { posts.removeFirst().invoke() }
        repeat(cancellations.size) { cancellations.removeFirst().invoke() }
    }
}

@Implements(Service::class)
class RetirementServiceShadow : ShadowService() {
    @Implementation override fun startForeground(id: Int, notification: Notification, type: Int) {
        assertEquals(CallOngoingNotification.ID, id)
        RetirementPlatform.foreground = true
        super.startForeground(id, notification, type)
    }
    @Implementation override fun stopForeground(flags: Int) {
        RetirementPlatform.retire(flags)
        RetirementPlatform.capturingServiceCancel = true
        try { super.stopForeground(flags) }
        finally { RetirementPlatform.capturingServiceCancel = false }
    }
}

@Implements(NotificationManager::class)
class RetirementNotificationShadow : ShadowNotificationManager() {
    @Implementation override fun notify(id: Int, notification: Notification) {
        if (id == CallOngoingNotification.ID || id == CallOngoingNotification.WITHOUT_MICROPHONE_ID) {
            if (!RetirementPlatform.foreground && RetirementPlatform.beforePlain) RetirementPlatform.drain()
            RetirementPlatform.admit(id, notification, byForegroundService = false)
        }
        super.notify(id, notification)
    }
    @Implementation override fun cancel(id: Int) {
        RetirementPlatform.cancel { super.cancel(id) }
    }
}
