package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.sync.withLock

internal fun CallSource.resolveCallChild(expected: CallBinding): EngineCallSource? = when (this) {
    is EngineCallSource -> takeIf { !closed && (state.value as? CallUiState.Ready)?.binding === expected }
    is CallSourceAggregate -> resolve(expected)
    else -> null
}

internal fun EngineCallSource.dialPrepared(expected: CallBinding, number: String, grant: (String, Long) -> Boolean) = launch {
    performDial(expected, number) { grant(expected.owner, generation) }
}

internal fun EngineCallSource.answerPrepared(expected: CallBinding, grant: (String, Long) -> Boolean) = launch {
    performControl(expected, CallAction.Answer) { grant(expected.owner, generation) }
}

internal fun EngineCallSource.resumePreparedAudio(expected: CallBinding, grant: (String, Long) -> Boolean) = launch {
    val effects = mutex.withLock {
        if (!owns(expected) || session.phase != CallPhase.InCall || session.audio == CallAudioState.Active) return@withLock CallEffects()
        val token = session.token ?: return@withLock CallEffects()
        val transport = activeTransport ?: return@withLock CallEffects()
        if (!grant(expected.owner, generation)) return@withLock CallEffects()
        session = session.copy(audio = CallAudioState.Idle, operationMessage = null)
        publish()
        CallEffects(audio = PendingCallAudio(token, transport, expected.owner, generation))
    }
    apply(effects)
}
