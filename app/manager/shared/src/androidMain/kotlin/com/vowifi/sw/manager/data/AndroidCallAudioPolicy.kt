package com.vowifi.sw.manager.data

import android.media.*
import android.os.Handler
import android.os.Looper
import com.vowifi.sw.manager.model.CallAudioControls
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class AndroidCallAudioPolicy(
    private val manager: AudioManager,
    private val onLost: () -> Unit,
    private val onRoute: (CallAudioControls) -> Unit,
) : AutoCloseable, AudioManager.OnAudioFocusChangeListener {
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener(this, Handler(Looper.getMainLooper())).build()
    private val owned = AtomicBoolean(false)
    @Volatile private var activeRequest = false
    @Volatile private var lost = false
    private var savedMode = AudioManager.MODE_NORMAL
    private val listener = AudioManager.OnCommunicationDeviceChangedListener { publish() }
    override fun onAudioFocusChange(change: Int) {
        if (change < 0 && activeRequest) { lost = true; close(); onLost() }
    }
    fun acquire(): Boolean {
        if (owned.get()) return true
        lost = false
        activeRequest = true
        val response = manager.requestAudioFocus(focus)
        if (response != AudioManager.AUDIOFOCUS_REQUEST_GRANTED || lost) {
            activeRequest = false
            if (response == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) manager.abandonAudioFocusRequest(focus)
            return false
        }
        owned.set(true)
        savedMode = manager.mode
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.addOnCommunicationDeviceChangedListener(Executor { Handler(Looper.getMainLooper()).post(it) }, listener)
        if (manager.communicationDevice == null) manager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }?.let { manager.setCommunicationDevice(it) }
        publish()
        return true
    }
    fun speaker(enabled: Boolean): Boolean {
        val type = if (enabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val target = manager.availableCommunicationDevices.firstOrNull { it.type == type }
        if (target == null || !manager.setCommunicationDevice(target)) {
            publish("无法切换音频设备")
            return false
        }
        publish(if (manager.communicationDevice?.id == target.id) null else "正在等待音频路由确认")
        return true
    }
    internal fun progressOutputState(): String =
        "mode=${manager.mode} communication=${manager.communicationDevice?.type ?: 0} voice-volume=${manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)} voice-muted=${manager.isStreamMute(AudioManager.STREAM_VOICE_CALL)}"
    private fun publish(message: String? = null) {
        if (!owned.get()) return
        val type = manager.communicationDevice?.type
        onRoute(CallAudioControls(speakerOn = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            earpiece = type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, message = message))
    }
    override fun close() {
        activeRequest = false
        if (!owned.compareAndSet(true, false)) return
        manager.removeOnCommunicationDeviceChangedListener(listener)
        manager.clearCommunicationDevice()
        manager.mode = savedMode
        manager.abandonAudioFocusRequest(focus)
        onRoute(CallAudioControls())
    }
}
