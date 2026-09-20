package com.vowifi.sw.manager.data

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.vowifi.sw.manager.model.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class MicrophoneCallAction { Dial, Answer, Restore }

class AndroidCallLifecycle(
    context: Context,
    private val source: EngineCallSource,
    private val microphone: CallMicrophoneLease,
    private val interruptAudio: () -> Unit,
    private val audioControls: StateFlow<CallAudioControls>,
) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val proximity = AndroidCallProximity(context)
    private val visible = mutableSetOf<Activity>()
    private var callVisible = false
    private var keypad = false
    private var pending: CompletableDeferred<Boolean>? = null
    private var serviceStop: (() -> Unit)? = null
    @Volatile private var leaseKey: String? = null
    @Volatile private var boundGeneration: Long? = null
    val feedback = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val microphoneDenied = MutableStateFlow(false)
    val lineLabel = MutableStateFlow("当前绑定线路")
    private var lineOwner: String? = null
    private val subscriptionWatch = AndroidSubscriptionInventory(context).startWatching { scope.launch { refreshLine() } }

    init {
        scope.launch { source.state.collect { state ->
            val ready = state as? CallUiState.Ready
            if (ready?.binding?.owner != lineOwner) { lineOwner = ready?.binding?.owner; refreshLine() }
            if (pending == null && leaseKey != null && (ready == null || ready.phase == CallPhase.Idle ||
                    ready.sync != CallSyncState.Ready || ready.binding?.generation != boundGeneration || ready.audio is CallAudioState.Failed)) release()
            updateProximity()
        } }
        scope.launch { audioControls.collect { updateProximity() } }
    }

    fun setVisible(activity: Activity, resumed: Boolean, isCall: Boolean = false) {
        if (resumed) visible.add(activity) else visible.remove(activity)
        if (isCall) callVisible = resumed
        if (resumed) {
            refreshLine()
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                microphoneDenied.value = false
                if (feedback.value == MICROPHONE_NOTICE) feedback.value = null
            }
        }
        updateProximity()
    }
    fun setKeypad(open: Boolean) { keypad = open; updateProximity() }
    private fun updateProximity() {
        val ready = source.state.value as? CallUiState.Ready
        proximity.update(audioControls.value.earpiece, ready?.phase == CallPhase.InCall && ready.audio == CallAudioState.Active,
            callVisible, keypad)
    }
    fun permissionDenied() { microphoneDenied.value = true; feedback.value = MICROPHONE_NOTICE }
    fun refreshLine() { lineLabel.value = boundCallLine(context, (source.state.value as? CallUiState.Ready)?.binding) }

    suspend fun perform(activity: Activity, binding: CallBinding, action: MicrophoneCallAction, number: String? = null): Boolean {
        if (busy.value) return false
        feedback.value = null
        val ready = source.state.value as? CallUiState.Ready
        if (ready?.binding !== binding || ready.sync != CallSyncState.Ready) {
            feedback.value = "通话状态已变化，请重试"; return false
        }
        if (activity !in visible || activity.isFinishing) {
            feedback.value = "请在前台电话界面操作"; return false
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionDenied(); return false
        }
        microphoneDenied.value = false
        if (action == MicrophoneCallAction.Dial && (ready.phase != CallPhase.Idle || number == null || !CallProtocolCodec.isValidDialNumber(number))) {
            feedback.value = CallFailureMessages.INVALID; return false
        }
        if (action == MicrophoneCallAction.Answer && ready.phase != CallPhase.Ringing ||
            action == MicrophoneCallAction.Restore && ready.phase != CallPhase.InCall) return false
        if (action == MicrophoneCallAction.Restore && ready.audio !is CallAudioState.Failed) return false
        busy.value = true
        release()
        val key = UUID.randomUUID().toString()
        leaseKey = key
        val barrier = CompletableDeferred<Boolean>()
        pending = barrier
        try {
            activity.startForegroundService(Intent().setClassName(context, SERVICE_CLASS).putExtra(EXTRA_REQUEST, key))
            if (withTimeoutOrNull(4_000) { barrier.await() } != true) {
                feedback.value = "无法启动通话麦克风服务"; release(); return false
            }
            val started = CompletableDeferred<Boolean>()
            val grant: (String, Long) -> Boolean = { owner, generation ->
                val allowed = leaseKey == key && microphone.foreground
                if (allowed) {
                    boundGeneration = source.bindingGeneration
                    microphone.bind(owner, generation)
                }
                started.complete(allowed)
                allowed
            }
            when (action) {
                MicrophoneCallAction.Dial -> source.dialPrepared(binding, number!!, grant)
                MicrophoneCallAction.Answer -> source.answerPrepared(binding, grant)
                MicrophoneCallAction.Restore -> source.resumePreparedAudio(binding, grant)
            }
            if (withTimeoutOrNull(3_000) { started.await() } != true) {
                feedback.value = "通话状态已变化，请重试"; release(); return false
            }
            return true
        } catch (error: CancellationException) {
            if (boundGeneration == null) release()
            throw error
        } catch (_: SecurityException) {
            feedback.value = "系统拒绝麦克风服务，请检查权限"; release(); return false
        } catch (_: IllegalStateException) {
            feedback.value = "系统不允许当前启动麦克风服务，请返回前台重试"; release(); return false
        } finally {
            pending = null
            busy.value = false
            val current = source.state.value as? CallUiState.Ready
            if (current?.phase == CallPhase.Idle && current.binding?.generation != binding.generation) release()
        }
    }

    fun acceptsService(key: String?): Boolean = key != null && key == leaseKey && pending != null
    fun serviceStarted(key: String, stop: () -> Unit): Boolean {
        if (!acceptsService(key)) return false
        serviceStop = stop
        microphone.serviceReady()
        pending?.complete(true)
        return true
    }
    fun serviceFailed(key: String?) { if (key == leaseKey) { pending?.complete(false); release() } }
    fun serviceDestroyed(key: String?) {
        if (key != null && key == leaseKey) {
            serviceStop = null
            microphone.clear()
            interruptAudio()
            feedback.value = "麦克风服务已中断，请在前台恢复音频"
            leaseKey = null
            boundGeneration = null
            proximity.close()
        }
    }
    private fun release() {
        microphone.clear()
        interruptAudio()
        val stop = serviceStop
        serviceStop = null
        leaseKey = null
        boundGeneration = null
        stop?.invoke()
        proximity.close()
    }
    fun close() { release(); subscriptionWatch.stop(); visible.clear(); scope.cancel() }

    companion object {
        private const val MICROPHONE_NOTICE = "需要麦克风权限，可在系统应用权限中开启"
        const val SERVICE_CLASS = "com.vowifi.sw.CallMicrophoneService"
        const val EXTRA_REQUEST = "com.vowifi.sw.call_microphone_request"
    }
}
