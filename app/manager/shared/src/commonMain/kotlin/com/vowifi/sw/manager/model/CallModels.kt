package com.vowifi.sw.manager.model

enum class CallOrigin {
    ENGINE,
    DESKTOP_FIXTURE,
}

enum class CallPhase {
    Idle,
    Ringing,
    Connecting,
    Dialing,
    InCall,
    Canceling,
    Ending,
}

enum class CallSyncState { Ready, Recovering, Unavailable, Unregistered, OwnerChanged }
enum class CallAction { Answer, Reject, Hangup, Cancel }

class CallBinding internal constructor(
    internal val owner: String,
    internal val token: String?,
    internal val generation: Long,
    internal val epoch: String? = null,
) {
    fun sameCall(other: CallBinding): Boolean = owner == other.owner && epoch == other.epoch &&
        if (token != null && other.token != null) token == other.token else generation == other.generation
    override fun toString(): String = "CallBinding"
}

sealed interface CallAudioState {
    data object Idle : CallAudioState
    data object Active : CallAudioState
    data class Failed(val message: String) : CallAudioState
}

data class CallAudioControls(
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val earpiece: Boolean = false,
    val message: String? = null,
) {
    fun toggledMute(): CallAudioControls = copy(muted = !muted)

    fun toggledSpeaker(): CallAudioControls = copy(speakerOn = !speakerOn)

    fun applyMute(buffer: ByteArray, length: Int) {
        if (!muted) return
        buffer.fill(0, 0, length.coerceIn(0, buffer.size))
    }
}

sealed interface CallUiState {
    data object Loading : CallUiState
    data class Ready(
        val phase: CallPhase,
        val audio: CallAudioState,
        val origin: CallOrigin,
        val busy: Boolean = false,
        val operationMessage: String? = null,
        val peer: CallPeer? = null,
        val binding: CallBinding? = null,
        val direction: CallDirection? = null,
        val sync: CallSyncState = CallSyncState.Ready,
        val dtmfSupported: Boolean = false,
        val dtmfBusy: Boolean = false,
        val controlBusy: Boolean = false,
        val pendingAction: CallAction? = null,
        val dialBusy: Boolean = false,
        val cancelPending: Boolean = false,
        val clock: com.vowifi.sw.manager.data.CallSessionClock? = null,
    ) : CallUiState {
        override fun toString(): String =
            "Ready(phase=$phase, origin=$origin, busy=$busy, hasPeer=${peer != null})"
    }
    data class Failure(val message: String) : CallUiState
    data class PermissionDenied(val message: String) : CallUiState
    data class Unavailable(val message: String) : CallUiState
}

object CallFailureMessages {
    const val PERMISSION = "请在 KernelSU 中授予 SoftVoWiFi root 权限"
    const val LIST = "无法读取通话会话"
    const val CONNECT = "无法连接通话会话"
    const val INVALID = "呼叫请求无效"
    const val BUSY = "线路忙"
    const val IMS = "IMS 未注册"
    const val AUDIO = "无法启动通话音频"
    const val AUDIO_INTERRUPTED = "通话音频已中断"
    const val NETWORK = "通话网络异常"
    const val NO_ANSWER = "无人接听"
    const val SNAPSHOT = "通话状态暂不可用，请刷新"
    const val RECOVERING = "连接已中断，正在等待通话状态确认"
    const val OWNER_CHANGED = "当前线路已变化，请先恢复原通话线路"
    const val DTMF_UNSUPPORTED = "本次通话不支持按键发送"
    const val CANCELED = "已取消拨号"
}
