@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import android.telecom.TelecomManager
import com.vowifi.sw.manager.data.AndroidCallAudioController
import com.vowifi.sw.manager.data.AndroidCallAudioDevices
import com.vowifi.sw.manager.data.AndroidCallAudioException
import com.vowifi.sw.manager.data.AndroidCallAudioPolicy
import com.vowifi.sw.manager.data.CallAudioMode
import com.vowifi.sw.manager.data.CallAudioSession
import com.vowifi.sw.manager.data.CallControlTransport
import com.vowifi.sw.manager.data.CallMicrophoneLease
import com.vowifi.sw.manager.data.EngineMessageTransport
import com.vowifi.sw.manager.model.CallAudioControls
import com.vowifi.sw.manager.model.EngineProtocolError
import com.vowifi.sw.manager.model.EngineProtocolFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    manifest = Config.NONE,
    shadows = [
        CoexistenceAudioManagerShadow::class,
        CoexistenceRecordingMonitorShadow::class,
        PublicationLocalSocketShadow::class,
        RootFrameworkShadow::class,
    ],
)
class CallAudioRouteOwnershipHostTest {
    private fun setupAudioEnvironment(): Pair<android.app.Application, AudioManager> {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
        val manager = app.getSystemService(AudioManager::class.java)
        val platform = shadowOf(manager)
        val earpiece = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        platform.lockCommunicationDevice(false)
        platform.setAvailableCommunicationDevices(listOf(earpiece, speaker))
        assertTrue(manager.setCommunicationDevice(earpiece))
        platform.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        manager.mode = AudioManager.MODE_NORMAL
        shadowOf(app.getSystemService(TelecomManager::class.java)).setIsInCall(false)
        CoexistenceRecordingMonitorShadow.reset()
        CoexistenceAudioManagerShadow.denyPlaybackRegistration = false
        CoexistenceAudioManagerShadow.denyPlaybackRead = false
        CoexistenceAudioManagerShadow.throwAfterPlaybackRegistration = false
        return app to manager
    }

    private fun stubTransport() = object : EngineMessageTransport {
        override val events = MutableSharedFlow<EngineProtocolFrame.Event>()
        override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long) =
            EngineProtocolFrame.Response(1, null, null)
    }

    @Test
    fun policyInvokesOnRouteWithoutHoldingIntrinsicMonitor() {
        val (_, manager) = setupAudioEnvironment()
        val platform = shadowOf(manager)
        val speaker = manager.availableCommunicationDevices.first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val policyRef = AtomicReference<AndroidCallAudioPolicy>()
        val heldDuringOnRoute = mutableListOf<Boolean>()
        val policy = AndroidCallAudioPolicy(
            manager = manager,
            onLost = {},
            onRoute = {
                val p = policyRef.get()
                if (p != null) heldDuringOnRoute += Thread.holdsLock(p)
            },
            canCoexist = { true },
        )
        policyRef.set(policy)
        try {
            assertTrue(policy.acquire())
            assertTrue(policy.speaker(true))
            platform.callOnCommunicationDeviceChangedListeners(speaker)
            shadowOf(Looper.getMainLooper()).idle()
            policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            policy.close()
            assertTrue("expected multiple onRoute callbacks", heldDuringOnRoute.size >= 5)
            assertTrue(
                "AndroidCallAudioPolicy must not hold its intrinsic monitor while invoking onRoute: $heldDuringOnRoute",
                heldDuringOnRoute.none { it },
            )
        } finally {
            policy.close()
            platform.setAvailableCommunicationDevices(emptyList())
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun prePublicationStartupDoesNotLeakRouteAndFailedStartupCannotClobberActiveSession() = runBlocking {
        val (app, manager) = setupAudioEnvironment()
        val platform = shadowOf(manager)
        val microphone = CallMicrophoneLease()
        val transportA = stubTransport()
        val transportB = stubTransport()
        val sessionA = CallAudioSession("synthetic-route-a", "token-a", 1, transportA, CallAudioMode.Conversation)
        val sessionB = CallAudioSession("synthetic-route-b", "token-b", 1, transportB, CallAudioMode.Progress, earlyMediaRevision = 0L, ringback = false)
        microphone.serviceReady("route-lease")
        assertTrue(microphone.bind("route-lease", sessionA.iccid, sessionA.generation, transportA, sessionA.token))
        val controlEntered = CompletableDeferred<Unit>()
        val controlReply = CompletableDeferred<EngineProtocolFrame.Response>()
        val controller = AndroidCallAudioController(
            app,
            microphone,
            CallControlTransport { _, _, _, _ ->
                controlEntered.complete(Unit)
                controlReply.await()
            },
        )
        try {
            val startA = async(Dispatchers.Default) {
                try {
                    controller.start(sessionA)
                    true
                } catch (_: AndroidCallAudioException) {
                    false
                }
            }
            withTimeout(5_000) { controlEntered.await() }
            assertEquals(
                "uncommitted startup must not leak route controls before session publication",
                CallAudioControls(),
                controller.controls.value,
            )

            assertTrue(microphone.bind("route-lease", sessionB.iccid, sessionB.generation, transportB, sessionB.token))
            controller.start(sessionB)
            assertTrue("published sessionB should expose earpiece route", controller.controls.value.earpiece)
            controller.toggleSpeaker()
            controller.toggleMuted()
            assertTrue("sessionB speaker should be enabled", controller.controls.value.speakerOn)
            assertTrue("sessionB should be muted", controller.controls.value.muted)

            controlReply.complete(EngineProtocolFrame.Response(1, null, EngineProtocolError(5001, "audio_failed")))
            assertFalse("failed sessionA startup must throw AndroidCallAudioException", withTimeout(5_000) { startA.await() })
            assertTrue("failed sessionA close must not wipe active sessionB speakerOn", controller.controls.value.speakerOn)
            assertTrue("failed sessionA close must not wipe active sessionB muted", controller.controls.value.muted)
        } finally {
            controlReply.complete(EngineProtocolFrame.Response(1, null, EngineProtocolError(5001, "cleanup")))
            controller.release()
            microphone.clear("route-lease")
            platform.setAvailableCommunicationDevices(emptyList())
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun lateOnRouteCallbackAfterStopCannotResurrectControlsOnIdleController() = runBlocking {
        val (app, manager) = setupAudioEnvironment()
        val platform = shadowOf(manager)
        val microphone = CallMicrophoneLease()
        val transport = stubTransport()
        val session = CallAudioSession("synthetic-route-late", "token-late", 1, transport, CallAudioMode.Progress, earlyMediaRevision = 0L, ringback = false)
        microphone.serviceReady("late-lease")
        assertTrue(microphone.bind("late-lease", session.iccid, session.generation, transport, session.token))
        val controller = AndroidCallAudioController(app, microphone)
        try {
            controller.start(session)
            assertTrue(controller.controls.value.earpiece)
            val activeSession = ReflectionHelpers.getField<Any?>(controller, "session")
            assertNotNull(activeSession)
            val devices = ReflectionHelpers.getField<AndroidCallAudioDevices>(activeSession, "devices")
            val policy = ReflectionHelpers.getField<AndroidCallAudioPolicy>(devices, "policy")
            @Suppress("UNCHECKED_CAST")
            val capturedOnRoute = ReflectionHelpers.getField<(CallAudioControls) -> Unit>(policy, "onRoute")

            controller.stop()
            assertEquals(CallAudioControls(), controller.controls.value)

            capturedOnRoute(CallAudioControls(speakerOn = true, earpiece = false))
            assertEquals(
                "stale onRoute callback after stop() must not resurrect controls on idle controller",
                CallAudioControls(),
                controller.controls.value,
            )
        } finally {
            controller.release()
            microphone.clear("late-lease")
            platform.setAvailableCommunicationDevices(emptyList())
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun concurrentOnRouteAndToggleMutedPreserveMuteWithoutDeadlock() = runBlocking {
        val (app, manager) = setupAudioEnvironment()
        val platform = shadowOf(manager)
        val microphone = CallMicrophoneLease()
        val transport = stubTransport()
        val session = CallAudioSession("synthetic-route-race", "token-race", 1, transport, CallAudioMode.Progress, earlyMediaRevision = 0L, ringback = false)
        microphone.serviceReady("race-lease")
        assertTrue(microphone.bind("race-lease", session.iccid, session.generation, transport, session.token))
        val controller = AndroidCallAudioController(app, microphone)
        val pool = Executors.newFixedThreadPool(2)
        try {
            controller.start(session)
            val activeSession = ReflectionHelpers.getField<Any?>(controller, "session")
            val devices = ReflectionHelpers.getField<AndroidCallAudioDevices>(activeSession, "devices")
            val policy = ReflectionHelpers.getField<AndroidCallAudioPolicy>(devices, "policy")
            @Suppress("UNCHECKED_CAST")
            val activeOnRoute = ReflectionHelpers.getField<(CallAudioControls) -> Unit>(policy, "onRoute")

            val startGate = CountDownLatch(1)
            val toggleTask = pool.submit {
                startGate.await(5, TimeUnit.SECONDS)
                // Odd number of toggles -> final muted MUST be true.
                repeat(1001) { controller.toggleMuted() }
            }
            val routeTask = pool.submit {
                startGate.await(5, TimeUnit.SECONDS)
                repeat(1001) { idx ->
                    activeOnRoute(CallAudioControls(speakerOn = idx % 2 == 0, earpiece = idx % 2 != 0))
                }
            }
            startGate.countDown()
            toggleTask.get(5, TimeUnit.SECONDS)
            routeTask.get(5, TimeUnit.SECONDS)
            assertTrue(
                "concurrent onRoute updates lost a toggleMuted() update",
                controller.controls.value.muted,
            )
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            controller.release()
            microphone.clear("race-lease")
            platform.setAvailableCommunicationDevices(emptyList())
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
