package com.vowifi.sw.manager.data

import android.content.Context
import android.os.Process
import com.vowifi.sw.manager.model.CallAudioControls
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal class AndroidCallAudioController(
    context: Context,
    private val microphone: CallMicrophoneLease,
) : CallAudioController {
    private val appContext = context.applicationContext
    private val executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "vowifi-call-audio")
    }
    private val lock = Any()
    private val released = AtomicBoolean(false)
    private val mutableEvents = MutableSharedFlow<CallAudioEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<CallAudioEvent> = mutableEvents.asSharedFlow()
    private val mutableControls = MutableStateFlow(CallAudioControls())
    override val controls: StateFlow<CallAudioControls> = mutableControls.asStateFlow()

    @Volatile
    private var session: Session? = null

    override suspend fun start(session: CallAudioSession) {
        if (released.get()) throw AndroidCallAudioException()
        if (!microphone.permits(session)) throw AndroidCallAudioException()
        stop()
        val focusLost = AtomicBoolean(false)
        val devices = AndroidCallAudioDevices.create(appContext,
            onLost = { focusLost.set(true); interrupt(session) },
            onRoute = { route -> mutableControls.value = route.copy(muted = mutableControls.value.muted) })
        val socket = try {
            AndroidCallAudioSocket.connect(session.iccid)
        } catch (error: CancellationException) {
            devices.close()
            throw error
        } catch (_: Exception) {
            devices.close()
            throw AndroidCallAudioException()
        }
        try {
            val response = session.transport.request(
                "audioStart",
                CallProtocolCodec.audioStartParams(session.token),
                CallProtocolCodec.AUDIO_START_TIMEOUT_MILLIS,
            )
            if (response.error != null) throw AndroidCallAudioException()
            if (focusLost.get() || !microphone.permits(session)) throw AndroidCallAudioException()
            devices.start()
            if (focusLost.get() || !microphone.permits(session)) throw AndroidCallAudioException()
        } catch (error: Exception) {
            socket.close()
            devices.close()
            if (error is CancellationException) throw error
            throw AndroidCallAudioException()
        }
        val next = Session(socket, devices, session)
        synchronized(lock) {
            if (released.get() || !microphone.permits(session)) {
                next.closeFromStop()
                throw AndroidCallAudioException()
            }
            this.session?.closeFromStop()
            this.session = next
        }
        try {
            executor.execute { captureLoop(next) }
            executor.execute { playbackLoop(next) }
        } catch (_: RejectedExecutionException) {
            stopIf(session)
            throw AndroidCallAudioException()
        }
    }

    override fun stop() {
        synchronized(lock) {
            session?.closeFromStop()
            session = null
            mutableControls.value = CallAudioControls()
        }
    }
    override fun stopIf(session: CallAudioSession) {
        synchronized(lock) {
            if (this.session?.owner?.sameSession(session) != true) return
            this.session?.closeFromStop()
            this.session = null
            mutableControls.value = CallAudioControls()
        }
    }

    override fun toggleMuted() {
        synchronized(lock) {
            if (session == null) return
            mutableControls.value = mutableControls.value.toggledMute()
        }
    }

    override fun toggleSpeaker() {
        synchronized(lock) {
            val current = session ?: return
            val controls = mutableControls.value
            if (controls.speakerOn) {
                current.devices.disableSpeaker()
            } else current.devices.enableSpeaker()
        }
    }

    fun release() {
        if (released.compareAndSet(false, true)) {
            stop()
            executor.shutdownNow()
        }
    }
    fun interrupt(expected: CallAudioSession? = null) {
        val current = session ?: return
        if (expected == null || current.owner.sameSession(expected)) onWorkerEnd(current)
    }

    private fun captureLoop(current: Session) {
        runAudioWorker(current) {
            val buffer = ByteArray(PcmFrameCodec.PAYLOAD_BYTES)
            val capture = PcmFrameCodec.Capture()
            while (!current.closed.get()) {
                if (!microphone.permits(current.owner)) return@runAudioWorker
                val n = current.devices.read(buffer)
                if (n <= 0 || current.closed.get()) return@runAudioWorker
                mutableControls.value.applyMute(buffer, n)
                for (frame in capture.push(buffer, 0, n)) {
                    current.socket.writeFrame(frame)
                }
            }
        }
    }

    private fun playbackLoop(current: Session) {
        runAudioWorker(current) {
            while (!current.closed.get()) {
                val payloads = current.socket.readPayloads()
                if (current.closed.get()) return@runAudioWorker
                for (payload in payloads) {
                    if (!PcmFrameCodec.writeFully(payload, current.devices::write)) {
                        return@runAudioWorker
                    }
                }
            }
        }
    }

    private inline fun runAudioWorker(current: Session, block: () -> Unit) {
        applyAudioPriority()
        try {
            block()
        } catch (_: IOException) {
            return
        } catch (_: IllegalStateException) {
            return
        } catch (_: PcmFrameException) {
            return
        } catch (_: InterruptedException) {
            return
        } finally {
            onWorkerEnd(current)
        }
    }

    private fun onWorkerEnd(current: Session) {
        val emitEnded = synchronized(lock) {
            val emit = current.closeFromWorker()
            if (session === current) {
                session = null
                mutableControls.value = CallAudioControls()
            }
            emit
        }
        if (emitEnded) mutableEvents.tryEmit(CallAudioEvent.Ended(current.owner))
    }

    private fun applyAudioPriority() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (_: IllegalArgumentException) {
            return
        } catch (_: SecurityException) {
            return
        }
    }

    private class Session(
        val socket: AndroidCallAudioSocket,
        val devices: AndroidCallAudioDevices,
        val owner: CallAudioSession,
    ) {
        val closed = AtomicBoolean(false)
        private val emitEnded = AtomicBoolean(true)
        private val endedPublished = AtomicBoolean(false)

        fun closeFromStop() {
            emitEnded.set(false)
            closeResources()
        }

        fun closeFromWorker(): Boolean {
            closeResources()
            return emitEnded.get() && endedPublished.compareAndSet(false, true)
        }

        private fun closeResources() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { socket.close() }
            runCatching { devices.close() }
        }
    }
}

internal class AndroidCallAudioException : Exception()
