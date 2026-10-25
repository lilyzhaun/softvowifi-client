package com.vowifi.sw.manager.data

import android.media.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vowifi.sw.manager.model.CallAudioControls
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class AndroidCallAudioPolicy(
    private val manager: AudioManager,
    private val onLost: () -> Unit,
    private val onRoute: (CallAudioControls) -> Unit,
    private val canCoexist: (() -> Boolean)? = null,
) : AutoCloseable, AudioManager.OnAudioFocusChangeListener {
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val handler = Handler(Looper.getMainLooper())
    private val focus = AudioFocusRequest.Builder(if (canCoexist != null)
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(canCoexist == null)
        .setOnAudioFocusChangeListener(this, handler).build()
    private val owned = AtomicBoolean(false)
    @Volatile private var activeRequest = false
    @Volatile private var lost = false
    @Volatile internal var hasAudioFocus = false
        private set
    @Volatile internal var onFocusChanged: (Boolean) -> Unit = {}
    private var savedMode = AudioManager.MODE_NORMAL
    private var routeListenerRegistered = false
    private var devicesRegistered = false
    private var modeListenerRegistered = false
    private var playbackCallbackRegistered = false
    private var mediaPlaybackActive: Boolean? = null
    private var routeMessage: String? = null
    private var speakerRequested = false
    private val listener = AudioManager.OnCommunicationDeviceChangedListener { synchronized(this) { publish() } }
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configurations: List<AudioPlaybackConfiguration>) {
            synchronized(this@AndroidCallAudioPolicy) {
                if (!activeRequest || !owned.get() || !playbackCallbackRegistered) return
                val active = configurations.any(::isMediaPlayback)
                if (mediaPlaybackActive != active) {
                    mediaPlaybackActive = active
                    publishControls()
                }
            }
        }
    }
    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        val ended = synchronized(this) {
            if (!activeRequest || mode == AudioManager.MODE_NORMAL || mode == AudioManager.MODE_IN_COMMUNICATION) false
            else { lost = true; close(); true }
        }
        if (ended) onLost()
    }
    internal val coexistsWithMedia: Boolean get() = canCoexist != null
    internal val permitsAudio: Boolean get() = owned.get() && (hasAudioFocus || coexistsWithMedia)
    internal val outputGain: Float get() = if (!permitsAudio) 0f else if (hasAudioFocus) 1f else 0.5f
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = refreshDevices()
    }
    internal fun checkAudioAdmission(): Boolean = permitsAudio && (canCoexist?.invoke() ?: true)
    override fun onAudioFocusChange(change: Int) {
        val ended = synchronized(this) {
            if (!activeRequest) return
            if (coexistsWithMedia && !canCoexist!!.invoke()) {
                lost = true; close(); return@synchronized true
            }
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    if (coexistsWithMedia) { hasAudioFocus = false; readMediaPlayback(); publish(); false }
                    else { lost = true; close(); true }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    hasAudioFocus = false
                    readMediaPlayback()
                    publish()
                    false
                }
                AudioManager.AUDIOFOCUS_GAIN -> { hasAudioFocus = true; publish(); false }
                else -> return
            }
        }
        // Do not call into the controller/devices while holding the policy lock.
        if (ended) onLost() else onFocusChanged(hasAudioFocus)
    }
    @Synchronized fun acquire(): Boolean {
        if (owned.get()) return true
        if (coexistsWithMedia && !canCoexist!!.invoke()) return false
        lost = false
        activeRequest = true
        val response = manager.requestAudioFocus(focus)
        if (response != AudioManager.AUDIOFOCUS_REQUEST_GRANTED || lost) {
            activeRequest = false
            if (response == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) manager.abandonAudioFocusRequest(focus)
            return false
        }
        owned.set(true)
        hasAudioFocus = true
        savedMode = manager.mode
        if (coexistsWithMedia) {
            manager.addOnModeChangedListener(Executor { handler.post(it) }, modeListener)
            modeListenerRegistered = true
            if (!canCoexist!!.invoke()) { lost = true; close(); return false }
            try {
                // AudioManager may add the local callback before its service call throws.
                // Mark it for rollback before entering that boundary.
                playbackCallbackRegistered = true
                manager.registerAudioPlaybackCallback(playbackCallback, handler)
                readMediaPlayback()
            } catch (error: RuntimeException) {
                // Optional status observation must not terminate an otherwise valid call.
                mediaPlaybackActive = null
                if (unregisterPlaybackCallback("playback-callback-rollback")) playbackCallbackRegistered = false
                Log.w("SoftVoWiFiAudio", "media status unavailable step=register type=${error.javaClass.simpleName}")
            }
        }
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (lost) return false
        if (coexistsWithMedia && !canCoexist!!.invoke()) { lost = true; close(); return false }
        manager.addOnCommunicationDeviceChangedListener(Executor { handler.post(it) }, listener)
        routeListenerRegistered = true
        manager.registerAudioDeviceCallback(devices, handler)
        devicesRegistered = true
        if (manager.availableCommunicationDevices.isNotEmpty()) speaker(false) else publish()
        return true
    }
    @Synchronized fun speaker(enabled: Boolean): Boolean {
        if (!owned.get()) return false
        val available = manager.availableCommunicationDevices
        val current = manager.communicationDevice
        val target = if (enabled) available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            else available.firstOrNull { it.id == current?.id && it.type == current?.type && isHeadset(it) }
                ?: available.firstOrNull(::isHeadset)
                ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (target == null) { publish("无法切换音频设备"); return false }
        val accepted = try {
            current?.id == target.id && current?.type == target.type || manager.setCommunicationDevice(target)
        } catch (_: IllegalArgumentException) {
            // A device can disconnect between enumeration and the platform selection call.
            false
        }
        if (!accepted) {
            publish("无法切换音频设备")
            return false
        }
        speakerRequested = enabled
        publish(if (manager.communicationDevice?.let { it.id == target.id && it.type == target.type } == true)
            null else "正在等待音频路由确认")
        return true
    }
    @Synchronized private fun refreshDevices() {
        if (owned.get()) speaker(speakerRequested)
    }
    private fun isHeadset(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> true
        else -> false
    }
    internal fun progressOutputState(): String =
        "mode=${manager.mode} communication=${manager.communicationDevice?.type ?: 0} voice-volume=${manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)} voice-muted=${manager.isStreamMute(AudioManager.STREAM_VOICE_CALL)}"
    private fun publish(message: String? = null) {
        routeMessage = message
        publishControls()
    }
    private fun publishControls() {
        if (!owned.get()) return
        val type = manager.communicationDevice?.type
        onRoute(CallAudioControls(speakerOn = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            earpiece = type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            message = routeMessage ?: if (!hasAudioFocus) {
                if (!coexistsWithMedia) "通话音频暂时被其他应用占用"
                else if (mediaPlaybackActive == true) "正在同时播放其他音频，通话音量已降低" else null
            } else null))
    }
    private fun readMediaPlayback() {
        if (!playbackCallbackRegistered) return
        mediaPlaybackActive = try {
            manager.activePlaybackConfigurations.any(::isMediaPlayback)
        } catch (error: RuntimeException) {
            // Unknown is not evidence of simultaneous playback; audio ownership is unchanged.
            Log.w("SoftVoWiFiAudio", "media status unavailable step=read type=${error.javaClass.simpleName}")
            null
        }
    }
    // The public callback/query supplies active, anonymized configurations without extra permission.
    // Inspect usage only, not hidden player state, identities, packages or audio content.
    private fun isMediaPlayback(configuration: AudioPlaybackConfiguration): Boolean = when (configuration.audioAttributes.usage) {
        AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME,
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> true
        else -> false
    }
    @Synchronized override fun close() {
        activeRequest = false
        hasAudioFocus = false
        mediaPlaybackActive = null
        routeMessage = null
        if (!owned.compareAndSet(true, false)) return
        if (playbackCallbackRegistered) {
            if (unregisterPlaybackCallback("playback-callback")) playbackCallbackRegistered = false
        }
        if (modeListenerRegistered) {
            modeListenerRegistered = false
            cleanup("mode-listener") { manager.removeOnModeChangedListener(modeListener) }
        }
        if (devicesRegistered) {
            devicesRegistered = false
            cleanup("device-listener") { manager.unregisterAudioDeviceCallback(devices) }
        }
        if (routeListenerRegistered) {
            routeListenerRegistered = false
            cleanup("route-listener") { manager.removeOnCommunicationDeviceChangedListener(listener) }
        }
        cleanup("route") { manager.clearCommunicationDevice() }
        // Do not overwrite the system phone's newer mode when it preempts this VoIP session.
        cleanup("mode") { if (manager.mode == AudioManager.MODE_IN_COMMUNICATION) manager.mode = savedMode }
        cleanup("focus") { manager.abandonAudioFocusRequest(focus) }
        onRoute(CallAudioControls())
    }
    private fun unregisterPlaybackCallback(step: String): Boolean = try {
        manager.unregisterAudioPlaybackCallback(playbackCallback)
        true
    } catch (error: RuntimeException) {
        Log.w("SoftVoWiFiAudio", "media status cleanup failed step=$step type=${error.javaClass.simpleName}")
        false
    }
    private inline fun cleanup(step: String, action: () -> Unit) {
        try { action() } catch (error: RuntimeException) {
            // Audio-service cleanup failure must not escape the main-thread focus callback or
            // prevent the remaining resources from being released. No raw exception/device data.
            Log.w("SoftVoWiFiAudio", "audio cleanup failed step=$step type=${error.javaClass.simpleName}")
        }
    }
}
