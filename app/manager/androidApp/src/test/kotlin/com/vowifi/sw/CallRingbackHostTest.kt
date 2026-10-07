package com.vowifi.sw

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Looper
import com.vowifi.sw.manager.data.AndroidCallAudioPolicy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, shadows = [RingbackToneShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CallRingbackHostTest {
    @Before fun reset() { RingbackToneShadow.started = 0; RingbackToneShadow.released = 0 }

    @Suppress("DEPRECATION")
    private fun output(): AutoCloseable {
        val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val track = AudioTrack(AudioManager.STREAM_VOICE_CALL, 16_000, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, 640, AudioTrack.MODE_STREAM)
        val type = Class.forName("com.vowifi.sw.manager.data.AndroidCallAudioDevices")
        return type.constructors.single().newInstance(null, track, AndroidCallAudioPolicy(manager, {}, {}),
            { fail("ringback playback failed") }) as AutoCloseable
    }

    private fun AutoCloseable.ringback(enabled: Boolean) {
        javaClass.getMethod("ringback", Boolean::class.javaPrimitiveType).invoke(this, enabled)
    }

    private fun AutoCloseable.remotePlaybackStarted(pcm: ByteArray) {
        javaClass.getMethod("remotePlaybackStarted", ByteArray::class.java).invoke(this, pcm)
    }

    @Test fun continuousSilentFramesCannotStopOrPostponeTheDefaultRingback() {
        output().use { devices ->
            devices.ringback(true)
            assertEquals(1, RingbackToneShadow.started)
            repeat(15) {
                devices.remotePlaybackStarted(ByteArray(640))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            }
            assertEquals(1, RingbackToneShadow.started)
            assertEquals(0, RingbackToneShadow.released)
        }
        assertEquals(1, RingbackToneShadow.released)
    }

    @Test fun actualNetworkAudioWinsAndOnlyTheOriginalSilentGapRestoresTone() {
        output().use { devices ->
            devices.ringback(true)
            devices.remotePlaybackStarted(byteArrayOf(1, 0))
            assertEquals(1, RingbackToneShadow.released)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            devices.remotePlaybackStarted(ByteArray(640))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(499))
            assertEquals(1, RingbackToneShadow.started)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(2, RingbackToneShadow.started)
            devices.remotePlaybackStarted(byteArrayOf(0, 1))
            assertEquals(2, RingbackToneShadow.released)
            devices.ringback(false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(2, RingbackToneShadow.started)
        }
    }

    @Test fun silenceWithoutRingingOrAfterCloseCannotStartTone() {
        val devices = output()
        devices.remotePlaybackStarted(ByteArray(640))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, RingbackToneShadow.started)
        devices.close()
        devices.ringback(true)
        devices.remotePlaybackStarted(byteArrayOf(1, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, RingbackToneShadow.started)
    }
}

@Implements(ToneGenerator::class)
class RingbackToneShadow {
    @Implementation fun __constructor__(streamType: Int, volume: Int) = Unit
    @Implementation fun startTone(toneType: Int): Boolean {
        assertEquals(ToneGenerator.TONE_SUP_RINGTONE, toneType)
        started++
        return true
    }
    @Implementation fun stopTone() = Unit
    @Implementation fun release() { released++ }
    companion object { var started = 0; var released = 0 }
}
