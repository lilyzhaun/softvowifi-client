package com.vowifi.sw

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Looper
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
@Config(sdk = [35], manifest = Config.NONE, shadows = [EndToneShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CallEndToneHostTest {
    private val manager get() = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
    @Before fun reset() { EndToneShadow.created = 0; EndToneShadow.released = 0; EndToneShadow.duration = 0 }
    private fun output() = Class.forName("com.vowifi.sw.manager.data.AndroidCallEndTone")
        .getConstructor(Context::class.java).newInstance(RuntimeEnvironment.getApplication())
    private fun play(output: Any): Boolean = output.javaClass.getMethod("play", Boolean::class.javaPrimitiveType).invoke(output, false) as Boolean
    private fun close(output: Any) { (output as AutoCloseable).close() }

    @Test fun finitePromptReleasesToneAndRestoresCommunicationModeAtDeadline() {
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val output = output()
        assertTrue(play(output))
        assertEquals(AudioManager.STREAM_VOICE_CALL, EndToneShadow.stream)
        assertEquals(ToneGenerator.TONE_PROP_PROMPT, EndToneShadow.type)
        assertEquals(200, EndToneShadow.duration)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(219))
        assertEquals(0, EndToneShadow.released)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, EndToneShadow.released)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        close(output)
        assertEquals(1, EndToneShadow.released)
    }

    @Test fun explicitStopRetiresToneImmediatelyAndOldCallbackCannotReleaseAgain() {
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val output = output()
        assertTrue(play(output))
        close(output)
        assertEquals(1, EndToneShadow.released)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, EndToneShadow.released)
        assertFalse(play(output))
    }

    @Test fun deniedOutputFocusCannotPlayOrChangeMode() {
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val output = output()
        assertFalse(play(output))
        assertEquals(0, EndToneShadow.created)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        close(output)
    }
}

@Implements(ToneGenerator::class)
class EndToneShadow {
    @Implementation fun __constructor__(streamType: Int, volume: Int) { stream = streamType; created++ }
    @Implementation fun startTone(toneType: Int, durationMs: Int): Boolean { type = toneType; duration = durationMs; return true }
    @Implementation fun stopTone() = Unit
    @Implementation fun release() { released++ }
    companion object {
        var created = 0; var released = 0; var stream = 0; var type = 0; var duration = 0
    }
}
