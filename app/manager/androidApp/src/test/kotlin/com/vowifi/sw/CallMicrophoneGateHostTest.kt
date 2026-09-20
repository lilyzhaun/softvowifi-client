package com.vowifi.sw

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CallMicrophoneGateHostTest {
    @Test fun permissionDenialDoesNotStartServiceOrDial() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        val fixture = CallPlatformFixture()
        val lifecycle = fixture.lifecycle(controller.get())
        try {
            lifecycle.setVisible(controller.get(), true)
            val binding = (fixture.source.state.value as CallUiState.Ready).binding!!
            assertFalse(lifecycle.perform(controller.get(), binding, MicrophoneCallAction.Dial, "+12025550123"))
            assertNull(controller.get().serviceIntent)
            assertTrue(fixture.commands.isEmpty())
            assertFalse(fixture.microphone.foreground)
        } finally { lifecycle.close(); fixture.close(); controller.close() }
    }
    @Test fun foregroundStartDenialNeverAcknowledgesDial() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        controller.get().denyService = true
        val fixture = CallPlatformFixture()
        val lifecycle = fixture.lifecycle(controller.get())
        try {
            lifecycle.setVisible(controller.get(), true)
            val binding = (fixture.source.state.value as CallUiState.Ready).binding!!
            assertFalse(lifecycle.perform(controller.get(), binding, MicrophoneCallAction.Dial, "+12025550123"))
            assertTrue(fixture.commands.isEmpty())
            assertEquals(CallPhase.Idle, (fixture.source.state.value as CallUiState.Ready).phase)
            assertFalse(fixture.microphone.foreground)
            assertNotNull(lifecycle.feedback.value)
        } finally { lifecycle.close(); fixture.close(); controller.close() }
    }
    @Test fun actualSourceWaitsForServiceForegroundBarrierAndThenOwnsCleanup() = runBlocking {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildActivity(CallGateActivity::class.java).setup()
        val fixture = CallPlatformFixture()
        val lifecycle = fixture.lifecycle(controller.get())
        var stops = 0
        try {
            lifecycle.setVisible(controller.get(), true)
            val binding = (fixture.source.state.value as CallUiState.Ready).binding!!
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                lifecycle.perform(controller.get(), binding, MicrophoneCallAction.Dial, "+12025550123")
            }
            assertTrue(fixture.commands.isEmpty())
            assertFalse(fixture.microphone.foreground)
            val key = controller.get().serviceIntent!!.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)!!
            assertFalse(lifecycle.serviceStarted("stale") { stops++ })
            assertTrue(lifecycle.serviceStarted(key) { stops++ })
            assertTrue(withTimeout(3000) { operation.await() })
            assertEquals(listOf("dial"), fixture.commands)
            assertTrue(fixture.microphone.foreground)
            lifecycle.setVisible(controller.get(), false)
            assertTrue(fixture.microphone.foreground)
            lifecycle.close()
            assertFalse(fixture.microphone.foreground)
            assertEquals(1, stops)
        } finally { lifecycle.close(); fixture.close(); controller.close() }
    }
}

class CallGateActivity : Activity() {
    var serviceIntent: Intent? = null
    var denyService = false
    override fun startForegroundService(service: Intent): ComponentName? {
        if (denyService) throw IllegalStateException("synthetic foreground denial")
        serviceIntent = service
        return ComponentName(packageName, AndroidCallLifecycle.SERVICE_CLASS)
    }
}
