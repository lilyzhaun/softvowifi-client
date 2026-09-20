package com.vowifi.sw.manager.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max

internal class AndroidCallAudioDevices(
    private val record: AudioRecord,
    private val track: AudioTrack,
    private val policy: AndroidCallAudioPolicy,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun start() {
        if (record.state != AudioRecord.STATE_INITIALIZED) throw AndroidCallAudioException()
        if (track.state != AudioTrack.STATE_INITIALIZED) throw AndroidCallAudioException()
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw AndroidCallAudioException()
        }
        track.play()
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            throw AndroidCallAudioException()
        }
    }

    fun read(buffer: ByteArray): Int =
        record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)

    fun write(bytes: ByteArray, offset: Int, length: Int): Int =
        track.write(bytes, offset, length, AudioTrack.WRITE_BLOCKING)

    fun enableSpeaker(): Boolean {
        return policy.speaker(true)
    }

    fun disableSpeaker() {
        policy.speaker(false)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        policy.close()
        runCatching { record.stop() }
        runCatching { record.release() }
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    companion object {
        fun create(context: Context, onLost: () -> Unit, onRoute: (com.vowifi.sw.manager.model.CallAudioControls) -> Unit): AndroidCallAudioDevices {
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
                policy = AndroidCallAudioPolicy(manager, onLost, onRoute)
                if (!policy.acquire()) throw AndroidCallAudioException()
                record = buildRecord()
                track = buildTrack(attributes)
                return AndroidCallAudioDevices(record, track, policy)
            } catch (error: CancellationException) {
                releasePartial(record, track, policy)
                throw error
            } catch (_: Exception) {
                releasePartial(record, track, policy)
                throw AndroidCallAudioException()
            }
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
