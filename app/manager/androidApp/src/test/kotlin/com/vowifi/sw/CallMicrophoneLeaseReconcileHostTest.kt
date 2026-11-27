package com.vowifi.sw

import android.Manifest
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CallMicrophoneLeaseReconcileHostTest {

    @Test
    fun fastFailingRestoreReleasesForegroundServiceAndMicrophoneLease() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(CallGateActivity::class.java).setup()
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
                if (method == "getCallSnapshot") snapshotResponse("IN_CALL") else error("unexpected $method")
            },
            Dispatchers.Unconfined,
        )
        val lifecycle = AndroidCallLifecycle(controller.get(), source, microphone, {}, controls)
        var stops = 0
        try {
            lifecycle.setVisible(controller.get(), true)
            val ready = source.state.value as CallUiState.Ready
            assertEquals(CallPhase.InCall, ready.phase)
            assertTrue(ready.audio is CallAudioState.Failed)
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                lifecycle.perform(controller.get(), ready.binding!!, MicrophoneCallAction.Restore)
            }
            val key = controller.get().serviceIntent!!.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)!!
            assertTrue(lifecycle.serviceStarted(key) { stops++ })
            assertTrue(withTimeout(3_000) { operation.await() })
            shadowOf(Looper.getMainLooper()).idle()
            val after = source.state.value as CallUiState.Ready
            assertTrue("precondition: audio failed immediately", after.audio is CallAudioState.Failed)
            assertEquals("fast-failing Restore leaked foreground microphone service", 1, stops)
            assertFalse("fast-failing Restore leaked microphone lease", microphone.foreground)
        } finally {
            lifecycle.close()
            source.close()
            controller.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun fastFailingAnswerReleasesForegroundServiceAndMicrophoneLease() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        val microphone = CallMicrophoneLease()
        val controls = MutableStateFlow(CallAudioControls())
        var failSnapshots = false
        val primary = object : EngineMessageTransport {
            override val events = MutableSharedFlow<EngineProtocolFrame.Event>()
            override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response =
                EngineProtocolFrame.Response(1, null, EngineProtocolError(2005, "unused"))
        }
        val source = EngineCallSource.connect(
            EngineMessageRuntime({ listOf(EngineInstanceState("synthetic-owner", EngineLifecycle.RUNNING, 1)) }, { primary }, { 0 }),
            object : CallAudioController {
                override val events = MutableSharedFlow<CallAudioEvent>()
                override suspend fun start(session: CallAudioSession) = Unit
                override fun stop() = Unit
            },
            CallControlTransport { _, method, _, _ ->
                when (method) {
                    "getCallSnapshot" -> if (failSnapshots) throw IOException("snapshot unavailable") else snapshotResponse("RINGING")
                    "answer" -> {
                        failSnapshots = true
                        throw IOException("synthetic immediate answer transport failure")
                    }
                    else -> error("unexpected $method")
                }
            },
            Dispatchers.Unconfined,
        )
        val lifecycle = AndroidCallLifecycle(controller.get(), source, microphone, {}, controls)
        var stops = 0
        try {
            lifecycle.setVisible(controller.get(), true)
            val ready = source.state.value as CallUiState.Ready
            assertEquals(CallPhase.Ringing, ready.phase)
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                lifecycle.perform(controller.get(), ready.binding!!, MicrophoneCallAction.Answer)
            }
            val key = controller.get().serviceIntent!!.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)!!
            assertTrue(lifecycle.serviceStarted(key) { stops++ })
            assertTrue(withTimeout(3_000) { operation.await() })
            shadowOf(Looper.getMainLooper()).idle()
            val after = source.state.value as CallUiState.Ready
            assertNotEquals("precondition: answer dropped sync out of Ready", CallSyncState.Ready, after.sync)
            assertEquals("fast-failing Answer leaked foreground microphone service", 1, stops)
            assertFalse("fast-failing Answer leaked microphone lease", microphone.foreground)
        } finally {
            lifecycle.close()
            source.close()
            controller.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun fastFailingDialReleasesForegroundServiceAndMicrophoneLease() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        val microphone = CallMicrophoneLease()
        val controls = MutableStateFlow(CallAudioControls())
        var failSnapshots = false
        val primary = object : EngineMessageTransport {
            override val events = MutableSharedFlow<EngineProtocolFrame.Event>()
            override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response {
                if (method == "dial") {
                    failSnapshots = true
                    throw IOException("synthetic immediate dial transport failure")
                }
                error("unexpected $method")
            }
        }
        val source = EngineCallSource.connect(
            EngineMessageRuntime({ listOf(EngineInstanceState("synthetic-owner", EngineLifecycle.RUNNING, 1)) }, { primary }, { 0 }),
            object : CallAudioController {
                override val events = MutableSharedFlow<CallAudioEvent>()
                override suspend fun start(session: CallAudioSession) = Unit
                override fun stop() = Unit
            },
            CallControlTransport { _, method, _, _ ->
                if (method == "getCallSnapshot") {
                    if (failSnapshots) throw IOException("snapshot unavailable") else snapshotResponse("IDLE")
                } else {
                    error("unexpected $method")
                }
            },
            Dispatchers.Unconfined,
        )
        val lifecycle = AndroidCallLifecycle(controller.get(), source, microphone, {}, controls)
        var stops = 0
        try {
            lifecycle.setVisible(controller.get(), true)
            val ready = source.state.value as CallUiState.Ready
            assertEquals(CallPhase.Idle, ready.phase)
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                lifecycle.perform(controller.get(), ready.binding!!, MicrophoneCallAction.Dial, "+12025550123")
            }
            val key = controller.get().serviceIntent!!.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)!!
            assertTrue(lifecycle.serviceStarted(key) { stops++ })
            assertTrue(withTimeout(3_000) { operation.await() })
            shadowOf(Looper.getMainLooper()).idle()
            val after = source.state.value as CallUiState.Ready
            assertNotEquals("precondition: dial dropped sync out of Ready", CallSyncState.Ready, after.sync)
            assertEquals("fast-failing Dial leaked foreground microphone service", 1, stops)
            assertFalse("fast-failing Dial leaked microphone lease", microphone.foreground)
        } finally {
            lifecycle.close()
            source.close()
            controller.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun staleAnswerDuringServiceStartupFailsFastWithoutThreeSecondTimeout() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        val fixture = CallPlatformFixture("RINGING")
        val lifecycle = fixture.lifecycle(controller.get())
        var stops = 0
        try {
            lifecycle.setVisible(controller.get(), true)
            val ready = fixture.source.state.value as CallUiState.Ready
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                lifecycle.perform(controller.get(), ready.binding!!, MicrophoneCallAction.Answer)
            }
            val key = controller.get().serviceIntent!!.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)!!
            (fixture.primary.events as MutableSharedFlow<EngineProtocolFrame.Event>).emit(
                EngineProtocolFrame.Event(
                    name = "incomingCall",
                    data = buildJsonObject {
                        put("callId", fixture.token)
                        put("state", "ENDED")
                        put("reason", "canceled")
                        put("epoch", fixture.epoch)
                        put("revision", fixture.revision + 1)
                    },
                ),
            )
            assertEquals(CallPhase.Idle, (fixture.source.state.value as CallUiState.Ready).phase)
            assertTrue(lifecycle.serviceStarted(key) { stops++ })
            val result = withTimeoutOrNull(800) { operation.await() }
            assertNotNull("stale Answer stalled waiting 3000ms for uncompleted started barrier", result)
            assertFalse(result!!)
            assertEquals(1, stops)
            assertFalse(fixture.microphone.foreground)
            assertFalse(lifecycle.busy.value)
        } finally {
            lifecycle.close()
            fixture.close()
            controller.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun closedSourceDuringServiceStartupFailsFastWithoutThreeSecondTimeout() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        val fixture = CallPlatformFixture("IDLE")
        val lifecycle = fixture.lifecycle(controller.get())
        var stops = 0
        try {
            lifecycle.setVisible(controller.get(), true)
            val ready = fixture.source.state.value as CallUiState.Ready
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                lifecycle.perform(controller.get(), ready.binding!!, MicrophoneCallAction.Dial, "+12025550123")
            }
            val key = controller.get().serviceIntent!!.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)!!
            fixture.source.close()
            assertTrue(lifecycle.serviceStarted(key) { stops++ })
            val result = withTimeoutOrNull(800) { operation.await() }
            assertNotNull("closed Dial stalled waiting 3000ms for uncompleted started barrier", result)
            assertFalse(result!!)
            assertEquals(1, stops)
            assertFalse(fixture.microphone.foreground)
            assertFalse(lifecycle.busy.value)
        } finally {
            lifecycle.close()
            fixture.close()
            controller.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun snapshotResponse(stage: String) = EngineProtocolFrame.Response(
        1,
        buildJsonObject {
            put("epoch", "synthetic-epoch")
            put("revision", 1)
            put("stage", stage)
            put("confirmedElapsedMs", if (stage == "IN_CALL") 12000 else 0)
            put("dtmfSupported", stage == "IN_CALL")
            if (stage != "IDLE") {
                put("callId", "synthetic-call")
                put("direction", "INCOMING")
                put("peer", "+12025550123")
            }
        },
        null,
    )
}
