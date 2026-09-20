package com.vowifi.sw

import android.media.AudioManager
import android.os.PowerManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.CallAudioControls
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CallAudioPolicyHostTest {
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
