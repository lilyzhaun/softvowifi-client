package com.vowifi.sw

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import androidx.lifecycle.Lifecycle
import com.vowifi.sw.manager.data.AndroidCallLifecycle
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SoftVoWiFiApplication::class,
    shadows = [CallEntryApplicationShadow::class, CallEntryShellShadow::class, CallEntryHmacShadow::class,
        CallEntrySocketShadow::class, CallEntryAudioShadow::class, RootFrameworkShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallPermissionResumeHostTest {
    @Test fun grantedMicrophoneDialogContinuesTheFirstDial() = runBlocking {
        val fixture = CallEntryFixture()
        shadowOf(fixture.context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val activity = Robolectric.buildActivity(CallEntryActivity::class.java).setup()
        try {
            fixture.connected()
            fixture.source.selectLine(fixture.ownerA)
            val binding = fixture.source.lines.value.outgoingBinding!!
            activity.get().calls.dial(binding, NUMBER)
            shadowOf(Looper.getMainLooper()).idle()
            val request = shadowOf(activity.get()).lastRequestedPermission
            assertArrayEquals(arrayOf(Manifest.permission.RECORD_AUDIO), request.requestedPermissions)
            assertFalse(activity.get().serviceRequest.isCompleted)

            // The system dialog pauses the activity and reports the grant before it resumes;
            // Lifecycle reaches RESUMED only after onResume/onPostResume have returned.
            activity.pause()
            shadowOf(fixture.context).grantPermissions(Manifest.permission.RECORD_AUDIO)
            @Suppress("DEPRECATION")
            activity.get().onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                intArrayOf(PackageManager.PERMISSION_GRANTED))
            activity.resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Lifecycle.State.RESUMED, activity.get().lifecycle.currentState)

            val intent = withTimeout(3000) { activity.get().serviceRequest.await() }
            assertTrue(fixture.source.lifecycle.acceptsService(intent.getStringExtra(AndroidCallLifecycle.EXTRA_REQUEST)!!))
        } finally { activity.close(); fixture.close() }
    }

    companion object { private const val NUMBER = "+12025550123" }
}
