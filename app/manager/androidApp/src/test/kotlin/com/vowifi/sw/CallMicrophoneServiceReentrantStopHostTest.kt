@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.os.Looper
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = SoftVoWiFiApplication::class,
    shadows = [RuntimeApplicationShadow::class, RetirementServiceShadow::class, RetirementNotificationShadow::class],
)
class CallMicrophoneServiceReentrantStopHostTest {

    @Test
    fun synchronousStopDuringServiceStartedNeverRepublishesMicrophoneRunningOrCallStyle() = runBlocking {
        RetirementPlatform.reset()
        val app = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val microphone = CallMicrophoneLease()
        val controls = MutableStateFlow(CallAudioControls())
        val primary = object : EngineMessageTransport {
            override val events = MutableSharedFlow<EngineProtocolFrame.Event>()
            override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response =
                EngineProtocolFrame.Response(1, null, EngineProtocolError(2005, "unused"))
        }
        val source = EngineCallSource.connect(
            EngineMessageRuntime({ listOf(EngineInstanceState("synthetic-owner", EngineLifecycle.RUNNING, 1)) }, { primary }, { 0 }),
            object : CallAudioController {
                override val events = MutableSharedFlow<CallAudioEvent>()
                override suspend fun start(session: CallAudioSession) {
                    throw IOException("synthetic immediate audio failure")
                }
                override fun stop() = Unit
            },
            CallControlTransport { _, method, _, _ ->
                if (method == "getCallSnapshot") inCallSnapshot() else error("unexpected $method")
            },
            Dispatchers.Unconfined,
        )
        val lifecycle = AndroidCallLifecycle(app, source, microphone, {}, controls)
        val coordinator = AndroidIncomingCallNotificationCoordinator(
            app,
            source.state,
            IncomingCallNotificationCallbacks({}, {}),
            IncomingCallActionReceiver::class.java,
            CallActivity::class.java,
            source::act,
        )
        val runtime = app.runtime()
        val callsFacade = ReflectionHelpers.getField<AndroidCallSource>(runtime, "calls")
        ReflectionHelpers.setField(callsFacade, "delegate", source)
        ReflectionHelpers.setField(callsFacade, "state", source.state)
        ReflectionHelpers.setField(callsFacade, "lifecycle", lifecycle)
        ReflectionHelpers.setField(runtime, "notifications", coordinator)

        val activity = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        val service = Robolectric.buildService(CallMicrophoneService::class.java).create()
        val manager = app.getSystemService(NotificationManager::class.java)
        var destroyed = false
        try {
            coordinator.start()
            shadowOf(Looper.getMainLooper()).idle()
            val ready = source.state.value as CallUiState.Ready
            assertEquals(CallPhase.InCall, ready.phase)
            assertTrue(ready.audio is CallAudioState.Failed)
            assertNotNull(
                "precondition: ordinary hangup notification posted before restore",
                shadowOf(manager).getNotification(CallOngoingNotification.WITHOUT_MICROPHONE_ID),
            )

            lifecycle.setVisible(activity.get(), true)
            // Dispatchers.Main.immediate matches real Android Main thread execution:
            // serviceStarted(key) synchronously resumes perform(), audio fails immediately, and release() invokes
            // CallMicrophoneService's stop lambda BEFORE serviceStarted(key) returns true.
            val restore = async(Dispatchers.Main.immediate) {
                lifecycle.perform(activity.get(), ready.binding!!, MicrophoneCallAction.Restore)
            }
            val intent = requireNotNull(activity.get().serviceIntent)
            service.get().onStartCommand(intent, 0, 7)
            assertTrue(withTimeout(3_000) { restore.await() })
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse("FGS must already be retired after synchronous stop", RetirementPlatform.foreground)
            assertFalse("microphone lease must be released", microphone.foreground)
            assertEquals(7, shadowOf(service.get()).stopSelfId)
            val micRunning = ReflectionHelpers.getField<Boolean>(coordinator, "microphoneRunning")
            assertFalse("CallMicrophoneService overwrote microphoneRunning=true after synchronous stop", micRunning)
            assertNull(
                "CallStyle notification must not be reposted after FGS retirement",
                shadowOf(manager).getNotification(CallOngoingNotification.ID),
            )
            assertNotNull(
                "ordinary hangup notification must remain posted for active call without microphone",
                shadowOf(manager).getNotification(CallOngoingNotification.WITHOUT_MICROPHONE_ID),
            )
            assertTrue("unexpected CallStyle admission rejections: ${RetirementPlatform.rejections}", RetirementPlatform.rejections.isEmpty())

            service.destroy()
            destroyed = true
            assertFalse(ReflectionHelpers.getField<Boolean>(coordinator, "microphoneRunning"))
        } finally {
            lifecycle.close()
            coordinator.stop()
            if (!destroyed) service.destroy()
            activity.close()
            source.close()
            RuntimeApplicationShadow.callFixture = null
            shadowOf(Looper.getMainLooper()).idle()
            RetirementPlatform.reset()
        }
    }

    @Test
    @Config(shadows = [RuntimeApplicationShadow::class, CancelDuringPromoteServiceShadow::class])
    fun rejectedServiceStartedClearsActiveStartSoDestroyDoesNotInvokeServiceDestroyed() = runBlocking {
        CancelDuringPromoteServiceShadow.onPromote = null
        val fixture = CallPlatformFixture("IDLE")
        RuntimeApplicationShadow.callFixture = fixture
        val app = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val runtime = app.runtime()
        val activity = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        val service = Robolectric.buildService(CallMicrophoneService::class.java).create()
        try {
            runtime.calls.lifecycle.setVisible(activity.get(), true)
            val ready = fixture.source.state.value as CallUiState.Ready
            val dial = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.calls.lifecycle.perform(activity.get(), ready.binding!!, MicrophoneCallAction.Dial, "+12025550123")
            }
            val intent = requireNotNull(activity.get().serviceIntent)
            val key = requireNotNull(intent.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST))
            // Complete pending inside startForeground so acceptsService(key) passes on line 20
            // while serviceStarted(key) returns false on line 41.
            CancelDuringPromoteServiceShadow.onPromote = {
                val pending = ReflectionHelpers.getField<CompletableDeferred<Boolean>?>(runtime.calls.lifecycle, "pending")
                pending?.complete(false)
            }
            service.get().onStartCommand(intent, 0, 9)
            assertFalse(withTimeout(3_000) { dial.await() })
            assertEquals(9, shadowOf(service.get()).stopSelfId)
            assertNull(
                "rejected serviceStarted left stale activeStart on CallMicrophoneService",
                ReflectionHelpers.getField<Any?>(service.get(), "activeStart"),
            )

            // Re-arm a fresh active lease key with the same string to prove onDestroy will not fire serviceDestroyed.
            ReflectionHelpers.setField(runtime.calls.lifecycle, "leaseKey", key)
            runtime.calls.lifecycle.feedback.value = null
            service.destroy()
            assertNull(
                "onDestroy fired serviceDestroyed for a previously rejected start",
                runtime.calls.lifecycle.feedback.value,
            )
        } finally {
            CancelDuringPromoteServiceShadow.onPromote = null
            runtime.calls.lifecycle.close()
            activity.close()
            fixture.close()
            RuntimeApplicationShadow.callFixture = null
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun inCallSnapshot() = EngineProtocolFrame.Response(
        1,
        buildJsonObject {
            put("epoch", "synthetic-epoch")
            put("revision", 1)
            put("stage", "IN_CALL")
            put("confirmedElapsedMs", 12000)
            put("dtmfSupported", true)
            put("callId", "synthetic-call")
            put("direction", "INCOMING")
            put("peer", "+12025550123")
        },
        null,
    )
}

@Implements(Service::class)
class CancelDuringPromoteServiceShadow : ShadowService() {
    @Implementation
    override fun startForeground(id: Int, notification: Notification, type: Int) {
        super.startForeground(id, notification, type)
        onPromote?.invoke()
    }

    companion object {
        var onPromote: (() -> Unit)? = null
    }
}
