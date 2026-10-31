package com.vowifi.sw.manager.data

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.util.Log
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max

internal class AndroidCallAudioDevices(
    private val record: AudioRecord?,
    private val track: AudioTrack,
    private val policy: AndroidCallAudioPolicy,
    private val onPlaybackFailed: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var tone: ToneGenerator? = null
    private var remotePlayback = false
    private var lastMediaAt: Long? = null
    private var ringbackRequested = false
    private var watchScheduled = false
    private var recordingCallbackRegistered = false
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            if (!closed.get() && configs.any { it.isClientSilenced }) onPlaybackFailed()
        }
    }
    private val ringbackHandler = Handler(Looper.getMainLooper())
    private val ringbackWatch = Runnable {
        val failed = synchronized(this) {
            watchScheduled = false
            if (closed.get()) false else try { updateRingback(); false } catch (_: AndroidCallAudioException) { true }
        }
        if (failed) onPlaybackFailed()
    }
    init { policy.onFocusChanged = { applyFocus() } }

    fun start() {
        if (!policy.checkAudioAdmission()) throw AndroidCallAudioException()
        if (record != null && record.state != AudioRecord.STATE_INITIALIZED) throw AndroidCallAudioException()
        if (track.state != AudioTrack.STATE_INITIALIZED) throw AndroidCallAudioException()
        if (record != null && policy.coexistsWithMedia) {
            record.registerAudioRecordingCallback(Executor { ringbackHandler.post(it) }, recordingCallback)
            recordingCallbackRegistered = true
        }
        record?.startRecording()
        if (record != null && record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw AndroidCallAudioException()
        }
        if (record != null && policy.coexistsWithMedia && record.activeRecordingConfiguration?.isClientSilenced == true) {
            throw AndroidCallAudioException()
        }
        if (!policy.checkAudioAdmission()) throw AndroidCallAudioException()
        track.play()
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            throw AndroidCallAudioException()
        }
        applyFocus()
    }

    fun read(buffer: ByteArray): Int {
        val n = record!!.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
        if (n > 0 && !policy.permitsAudio) buffer.fill(0, 0, n)
        return n
    }

    private fun applyFocus() {
        val failed = synchronized(this) {
            if (closed.get()) return
            try {
                if (track.setVolume(policy.outputGain) != AudioTrack.SUCCESS) throw AndroidCallAudioException()
                updateRingback()
                false
            } catch (_: IllegalStateException) { true }
              catch (_: AndroidCallAudioException) { true }
        }
        if (failed) onPlaybackFailed()
    }

    @Synchronized fun ringback(enabled: Boolean) {
        Log.i("SoftVoWiFiAudio", "progress ringback request enabled=$enabled closed=${closed.get()}")
        if (closed.get()) return
        ringbackRequested = enabled
        updateRingback()
    }

    private fun updateRingback() {
        val local = policy.hasAudioFocus && shouldPlayLocalRingback(ringbackRequested, lastMediaAt, SystemClock.elapsedRealtime())
        if (local) {
            if (tone == null) {
                val next = try { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }
                    catch (_: RuntimeException) { throw AndroidCallAudioException() }
                if (!next.startTone(ToneGenerator.TONE_SUP_RINGTONE)) { next.release(); throw AndroidCallAudioException() }
                tone = next
                Log.i("SoftVoWiFiAudio", "progress ringback started capture=false")
            }
        } else {
            if (tone != null) Log.i("SoftVoWiFiAudio", "progress ringback stopped")
            tone?.stopTone(); tone?.release(); tone = null
        }
        if (policy.hasAudioFocus && ringbackRequested && !local && !watchScheduled) {
            watchScheduled = true
            ringbackHandler.postDelayed(ringbackWatch, EARLY_MEDIA_PRESENCE_MILLIS)
        } else if (!policy.hasAudioFocus || !ringbackRequested || local) {
            ringbackHandler.removeCallbacks(ringbackWatch)
            watchScheduled = false
        }
    }

    @Synchronized fun remotePlaybackStarted(pcm: ByteArray) {
        if (closed.get() || !hasRingbackSuppressingPcm(pcm)) return
        lastMediaAt = SystemClock.elapsedRealtime()
        if (!remotePlayback) Log.i("SoftVoWiFiAudio", "progress remote PCM playback capture=false")
        remotePlayback = true
        updateRingback()
    }

    fun write(bytes: ByteArray, offset: Int, length: Int): Int =
        // Strict progress audio still drains/discards while paused. Conversation keeps live speech
        // at the policy's reduced gain; never inject other apps' media into the microphone uplink.
        if (!policy.permitsAudio) length else track.write(bytes, offset, length, AudioTrack.WRITE_BLOCKING)

    fun logProgressOutput() = Log.i("SoftVoWiFiAudio",
        "progress output play=${track.playState} rate=${track.sampleRate} channels=${track.channelCount} encoding=${track.audioFormat} routed=${track.routedDevice?.type ?: 0} ${policy.progressOutputState()}")

    fun enableSpeaker(): Boolean {
        return policy.speaker(true)
    }

    fun disableSpeaker() {
        policy.speaker(false)
    }

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ringbackHandler.removeCallbacks(ringbackWatch)
        if (recordingCallbackRegistered) {
            recordingCallbackRegistered = false
            runCatching { record?.unregisterAudioRecordingCallback(recordingCallback) }
        }
        policy.close()
        if (tone != null) Log.i("SoftVoWiFiAudio", "progress ringback stopped")
        tone?.stopTone(); tone?.release(); tone = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    companion object {
        fun create(context: Context, onLost: () -> Unit, onRoute: (com.vowifi.sw.manager.model.CallAudioControls) -> Unit, playbackOnly: Boolean = false): AndroidCallAudioDevices {
            var record: AudioRecord? = null
            var track: AudioTrack? = null
            var policy: AndroidCallAudioPolicy? = null
            try {
                if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) throw AndroidCallAudioException()
                val manager = context.getSystemService(AudioManager::class.java)
                    ?: throw AndroidCallAudioException()
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val canCoexist: (() -> Boolean)? = if (playbackOnly) null else ({
                    canCoexistWithMedia(context, manager)
                })
                policy = AndroidCallAudioPolicy(manager, onLost, onRoute, canCoexist)
                if (!policy.acquire()) throw AndroidCallAudioException()
                if (!playbackOnly) record = buildRecord()
                track = buildTrack(attributes)
                return AndroidCallAudioDevices(record, track, policy, onLost)
            } catch (error: CancellationException) {
                releasePartial(record, track, policy)
                throw error
            } catch (_: Exception) {
                releasePartial(record, track, policy)
                throw AndroidCallAudioException()
            }
        }

        private fun canCoexistWithMedia(context: Context, manager: AudioManager): Boolean {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
            return try {
                com.vowifi.sw.root.RootBridge.nativeCallsIdle() &&
                    (manager.mode == AudioManager.MODE_NORMAL || manager.mode == AudioManager.MODE_IN_COMMUNICATION)
            } catch (_: SecurityException) { false }
              catch (_: IllegalStateException) { false }
        }

        @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
        private fun buildRecord(): AudioRecord {
            val minBuffer = AudioRecord.getMinBufferSize(
                PcmFrameCodec.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) throw AndroidCallAudioException()
            val record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(PcmFrameCodec.SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuffer, PcmFrameCodec.PAYLOAD_BYTES))
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw AndroidCallAudioException()
            }
            return record
        }

        private fun buildTrack(attributes: AudioAttributes): AudioTrack {
            val minBuffer = AudioTrack.getMinBufferSize(
                PcmFrameCodec.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) throw AndroidCallAudioException()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(PcmFrameCodec.SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuffer, PcmFrameCodec.PAYLOAD_BYTES))
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                throw AndroidCallAudioException()
            }
            return track
        }

        private fun releasePartial(
            record: AudioRecord?,
            track: AudioTrack?,
            policy: AndroidCallAudioPolicy?,
        ) {
            runCatching { record?.release() }
            runCatching { track?.release() }
            policy?.close()
        }
    }
}
