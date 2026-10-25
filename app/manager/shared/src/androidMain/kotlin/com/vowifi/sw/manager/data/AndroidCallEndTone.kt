package com.vowifi.sw.manager.data

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

internal class AndroidCallEndTone(context: Context) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val policy = AndroidCallAudioPolicy(context.getSystemService(AudioManager::class.java), { close() }, {})
    private val finish = Runnable { close() }
    private var tone: ToneGenerator? = null
    private var closed = false
    init { policy.onFocusChanged = { focused -> if (!focused) close() } }

    @Synchronized fun play(speaker: Boolean): Boolean {
        if (closed) return false
        return try {
            if (!policy.acquire()) { close(); return false }
            if (speaker) policy.speaker(true)
            val output = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
            tone = output
            if (!output.startTone(ToneGenerator.TONE_PROP_PROMPT, 200)) { close(); return false }
            Log.i("SoftVoWiFiAudio", "call end tone started duration-ms=200 capture=false")
            handler.postDelayed(finish, 220)
            true
        } catch (_: RuntimeException) {
            close()
            Log.i("SoftVoWiFiAudio", "call end tone unavailable")
            false
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(finish)
        tone?.stopTone()
        tone?.release()
        if (tone != null) Log.i("SoftVoWiFiAudio", "call end tone stopped")
        tone = null
        policy.close()
    }
}
