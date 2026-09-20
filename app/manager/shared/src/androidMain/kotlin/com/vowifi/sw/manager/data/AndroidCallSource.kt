package com.vowifi.sw.manager.data

import android.content.Context
import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.EngineInstanceState
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class AndroidCallSource(
    context: Context,
    history: CallHistoryRepository,
    parentContext: CoroutineContext = Dispatchers.IO,
    clock: () -> Long = { System.currentTimeMillis() },
) : CallSource {
    private val microphone = CallMicrophoneLease()
    private val audio = AndroidCallAudioController(context, microphone)
    private val hmac = AndroidSimOwnerHmac()
    private val delegate = EngineCallSource(
        runtime = EngineMessageRuntime(
            listInstances = ::listInstances,
            transportFor = { iccid -> AndroidCallEventTransport(iccid) },
            clock = clock,
        ),
        audio = audio,
        control = AndroidCallControlTransport(),
        parentContext = parentContext,
        history = CallHistoryBinding(
            repository = history,
            ownerIdFor = { iccid ->
                try {
                    hmac.ensureKey()
                    hmac.ownerIdFor(iccid)
                } catch (_: Exception) {
                    null
                }
            },
            nextCorrelation = { UUID.randomUUID().toString() },
        ),
    )

    val lifecycle = AndroidCallLifecycle(context, delegate, microphone, { audio.interrupt() }, audio.controls)
    private val uiScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    override val state: StateFlow<CallUiState> = kotlinx.coroutines.flow.combine(delegate.state, lifecycle.feedback, lifecycle.busy) { state, feedback, preparing ->
        if (state is CallUiState.Ready) state.copy(operationMessage = feedback ?: state.operationMessage, busy = state.busy || preparing) else state
    }.stateIn(uiScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, CallUiState.Loading)
    override val history: StateFlow<CallHistoryState> = delegate.history
    override val historyOwners get() = delegate.historyOwners
    override fun selectHistoryOwner(choice: CallHistoryOwnerChoice) = delegate.selectHistoryOwner(choice)
    override val audioControls = audio.controls

    override fun refresh() = delegate.refresh()

    override fun answer() { lifecycle.feedback.value = "请打开来电页面接听" }

    override fun reject() = currentAction(com.vowifi.sw.manager.model.CallAction.Reject)

    override fun hangup() = currentAction(com.vowifi.sw.manager.model.CallAction.Hangup)
    override fun cancel() = currentAction(com.vowifi.sw.manager.model.CallAction.Cancel)
    private fun currentAction(action: com.vowifi.sw.manager.model.CallAction) {
        (state.value as? CallUiState.Ready)?.binding?.let { act(it, action) }
    }
    override fun act(expected: com.vowifi.sw.manager.model.CallBinding, action: com.vowifi.sw.manager.model.CallAction) {
        if (action == com.vowifi.sw.manager.model.CallAction.Answer) answer()
        else delegate.launch { delegate.performControl(expected, action) { lifecycle.feedback.value = null; true } }
    }
    override fun sendDtmf(expected: com.vowifi.sw.manager.model.CallBinding, digit: Char) = delegate.sendDtmf(expected, digit)

    override fun dial(number: String) { lifecycle.feedback.value = "请从拨号页面开始通话" }
    override fun dial(expected: com.vowifi.sw.manager.model.CallBinding, number: String) = dial(number)
    override fun clearHistory(expected: CallHistoryState.Ready) = delegate.clearHistory(expected)

    override fun mute() = audio.toggleMuted()

    override fun speaker() = audio.toggleSpeaker()

    override fun close() {
        lifecycle.close()
        uiScope.cancel()
        delegate.close()
        audio.release()
    }

    private suspend fun listInstances(): List<EngineInstanceState> = withContext(Dispatchers.IO) {
        val result = try {
            AndroidSuCommand().run("$CONTROL_SCRIPT list")
        } catch (failure: IOException) {
            if (failure.message.isCallPermissionDenied()) {
                throw MessagePermissionException(CallFailureMessages.PERMISSION)
            }
            throw IllegalStateException(CallFailureMessages.LIST)
        }
        when {
            result.timedOut -> error(CallFailureMessages.LIST)
            result.exitCode != 0 && result.output.isCallPermissionDenied() ->
                throw MessagePermissionException(CallFailureMessages.PERMISSION)
            result.exitCode != 0 -> error(CallFailureMessages.LIST)
            else -> EngineControlScriptCodec.instances(result.output)
        }
    }

    private companion object {
        const val CONTROL_SCRIPT = "/data/adb/modules/vowifi_sw/control.sh"
    }
}

private fun String?.isCallPermissionDenied(): Boolean =
    this.orEmpty().contains("EACCES", ignoreCase = true) ||
        this.orEmpty().contains("Permission denied", ignoreCase = true) ||
        this.orEmpty().contains("error=13", ignoreCase = true)
