package com.vowifi.sw

import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Looper
import android.os.PowerManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.CallAudioControls
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CallAudioPolicyHostTest {
    @Test fun platformRouteRefusalKeepsConfirmedControlsAndOnlyActualDeviceReadbackSelectsSpeaker() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val platform = shadowOf(manager)
        val earpiece = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        platform.lockCommunicationDevice(false)
        platform.setAvailableCommunicationDevices(listOf(earpiece, speaker))
        assertTrue(manager.setCommunicationDevice(earpiece))
        platform.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val reports = mutableListOf<CallAudioControls>()
        val policy = AndroidCallAudioPolicy(manager, { fail("本用例不应丢失焦点") }, { reports += it })
        try {
            assertTrue(policy.acquire()); assertTrue(reports.last().earpiece); assertFalse(reports.last().speakerOn)
            platform.lockCommunicationDevice(true) // 固定4.16.1的公开shadow会拒绝setCommunicationDevice，非虚构成功。
            assertFalse(policy.speaker(true)); shadowOf(Looper.getMainLooper()).idle()
            assertSame(earpiece, manager.communicationDevice)
            assertFalse(reports.last().speakerOn); assertNotNull(reports.last().message)
            platform.lockCommunicationDevice(false)
            assertTrue(policy.speaker(true)); assertSame(speaker, manager.communicationDevice)
            assertTrue(reports.last().speakerOn); assertNull(reports.last().message)
            platform.callOnCommunicationDeviceChangedListeners(earpiece) // 仅通知不改平台readback，不能伪选听筒。
            shadowOf(Looper.getMainLooper()).idle(); assertTrue(reports.last().speakerOn)
            assertTrue(policy.speaker(false)); assertSame(earpiece, manager.communicationDevice)
            assertFalse(reports.last().speakerOn); assertTrue(reports.last().earpiece)
        } finally { platform.lockCommunicationDevice(false); policy.close(); platform.setAvailableCommunicationDevices(emptyList()) }
    }

    @Test fun deniedFocusCannotClaimCommunicationMode() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val policy = AndroidCallAudioPolicy(manager, {}, {})
        assertFalse(policy.acquire())
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        policy.close()
    }
    @Test fun focusLossIsReportedAndMissingDeviceNeverBecomesSpeaker() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        var losses = 0
        var controls = CallAudioControls()
        val policy = AndroidCallAudioPolicy(manager, { losses++ }, { controls = it })
        assertTrue(policy.acquire())
        assertFalse(policy.speaker(true))
        assertFalse(controls.speakerOn)
        assertNotNull(controls.message)
        policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, losses)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        policy.close()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, losses)
    }
    @Test fun proximityReleasesForKeypadSpeakerBackgroundAndClose() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(PowerManager::class.java)
        shadowOf(manager).setIsWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, true)
        val proximity = AndroidCallProximity(context)
        proximity.update(true, true, true, false)
        assertTrue(proximity.held)
        proximity.update(true, true, true, true)
        assertFalse(proximity.held)
        proximity.update(true, true, true, false)
        proximity.update(false, true, true, false)
        assertFalse(proximity.held)
        proximity.update(true, true, true, false)
        proximity.update(true, true, false, false)
        assertFalse(proximity.held)
        proximity.close()
    }
}
