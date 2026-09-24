package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallFailureMessages
import com.vowifi.sw.manager.model.CallUiState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal suspend fun startOwnedAudio(
    audio: CallAudioController,
    audioMutex: Mutex,
    callMutex: Mutex,
    pending: PendingCallAudio,
    owns: () -> Boolean,
    onFailed: () -> Unit,
    onStarted: () -> Unit,
) {
    audioMutex.withLock {
        if (!callMutex.withLock { owns() }) return
        try {
            audio.start(pending.session())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            callMutex.withLock { if (owns()) onFailed() }
            return
        }
        val stale = callMutex.withLock {
            if (!owns()) return@withLock true
            onStarted()
            false
        }
        if (stale) audio.stopIf(pending.session())
    }
}

internal suspend fun collectOwnedAudioEnded(
    audio: CallAudioController,
    onEnded: suspend (CallAudioEvent.Ended) -> CallEffects,
    apply: suspend (CallEffects) -> Unit,
) {
    audio.events.collect { event ->
        if (event is CallAudioEvent.Ended) apply(onEnded(event))
    }
}

internal suspend fun collectCallTransportEvents(
    transport: EngineMessageTransport,
    onEvent: suspend (CallWireEvent) -> CallEffects,
    onFailure: suspend () -> CallEffects,
    apply: suspend (CallEffects) -> Unit,
) {
    try {
        transport.events.onSubscription { transport.connect() }.collect { frame ->
            val event = CallProtocolCodec.decodeEvent(frame) ?: return@collect
            apply(onEvent(event))
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        apply(onFailure())
    }
}

internal fun connectFailureEffects(
    closed: Boolean,
    active: EngineMessageTransport?,
    transport: EngineMessageTransport,
    fail: () -> CallEffects,
): CallEffects = if (!closed && active === transport) {
    fail()
} else {
    CallEffects()
}

internal fun audioEndedEffects(
    closed: Boolean,
    session: CallSession,
    generation: Long,
    event: CallAudioEvent.Ended,
    handle: () -> CallEffects,
): CallEffects {
    if (closed || session.phase != com.vowifi.sw.manager.model.CallPhase.InCall ||
        session.audio is com.vowifi.sw.manager.model.CallAudioState.Failed ||
        session.token != event.session.token || generation != event.session.generation
    ) {
        return CallEffects()
    }
    return handle()
}

internal fun permissionFailure(): CallUiState = CallUiState.PermissionDenied(CallFailureMessages.PERMISSION)

internal fun listFailure(): CallUiState = CallUiState.Failure(CallFailureMessages.LIST)

internal fun connectFailure(): CallUiState = CallUiState.Failure(CallFailureMessages.CONNECT)
