package com.vowifi.sw.manager.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallAudioControlsTest {
    @Test
    fun toggledMuteFlipsOnlyMuted() {
        val idle = CallAudioControls()
        val muted = idle.toggledMute()

        assertEquals(true, muted.muted)
        assertEquals(false, muted.speakerOn)
        assertEquals(idle, muted.toggledMute())
    }

    @Test
    fun toggledSpeakerFlipsOnlySpeaker() {
        val idle = CallAudioControls()
        val speaker = idle.toggledSpeaker()

        assertEquals(false, speaker.muted)
        assertEquals(true, speaker.speakerOn)
        assertEquals(idle, speaker.toggledSpeaker())
    }

    @Test
    fun applyMuteZerosOnlyWhenMuted() {
        val live = byteArrayOf(1, 2, 3, 4)

        CallAudioControls().applyMute(live, 4)
        assertTrue(live.contentEquals(byteArrayOf(1, 2, 3, 4)))

        CallAudioControls(muted = true).applyMute(live, 3)
        assertTrue(live.contentEquals(byteArrayOf(0, 0, 0, 4)))
    }

    @Test
    fun applyMuteCoercesLength() {
        val live = byteArrayOf(9, 8)

        CallAudioControls(muted = true).applyMute(live, 99)
        assertTrue(live.contentEquals(byteArrayOf(0, 0)))
        CallAudioControls(muted = true).applyMute(live, -1)
        assertTrue(live.contentEquals(byteArrayOf(0, 0)))
    }
}
