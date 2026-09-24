package com.vowifi.sw

import android.Manifest
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CallMicrophoneIdentityHostTest {
    @Test fun preparedDialGrantCannotAuthorizeDifferentTransport() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val activity = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        val fixture = CallPlatformFixture()
        val lifecycle = fixture.lifecycle(activity.get())
        try {
            lifecycle.setVisible(activity.get(), true)
            val binding = (fixture.source.state.value as CallUiState.Ready).binding!!
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                lifecycle.perform(activity.get(), binding, MicrophoneCallAction.Dial, "+12025550123")
            }
            val key = activity.get().serviceIntent!!.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)!!
            assertTrue(lifecycle.serviceStarted(key) {})
            assertFalse(lifecycle.serviceStarted(key) {})
            assertTrue(withTimeout(3000) { operation.await() })
            val foreign = object : EngineMessageTransport {
                override val events = MutableSharedFlow<EngineProtocolFrame.Event>()
                override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response = error("unused")
            }
            assertFalse(fixture.microphone.permits(CallAudioSession("synthetic-owner", "same-token", 1, foreign)))
            val actual = CallAudioSession("synthetic-owner", "same-token", 1, fixture.primary)
            assertTrue(fixture.microphone.permits(actual))
            assertFalse(fixture.microphone.permits(actual.copy(token = "different-token")))
        } finally { lifecycle.close(); fixture.close(); activity.close() }
    }
}
