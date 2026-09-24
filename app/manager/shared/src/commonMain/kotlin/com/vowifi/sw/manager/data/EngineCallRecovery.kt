package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallSyncState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun EngineCallSource.scheduleRecovery(transport: EngineMessageTransport) {
    if (closed || recoveryAttempt || transport !is CallEventTransport || activeTransport !== transport || recoveryJob?.isActive == true) return
    val owner = activeIccid
    recoveryJob = scope.launch(start = CoroutineStart.LAZY) {
        repeat(3) {
            delay(3_000)
            val attempt = mutex.withLock {
                if (closed || activeTransport !== transport || activeIccid != owner ||
                    sync == CallSyncState.Ready || sync == CallSyncState.OwnerChanged) return@launch
                if (connectionWork != 0) false else { recoveryAttempt = true; true }
            }
            if (attempt) {
                try { refreshConnection(recovering = true) }
                finally { finishRecoveryAttempt() }
                mutex.withLock {
                    if (closed || activeTransport !== transport || sync == CallSyncState.Ready ||
                        sync == CallSyncState.OwnerChanged) return@launch
                }
            }
        }
    }.also { it.start() }
}

internal fun EngineCallSource.cancelRecovery() {
    requiredSnapshot = null
    recoveryJob?.cancel()
    recoveryJob = null
}

internal suspend fun EngineCallSource.finishRecoveryAttempt() = withContext(NonCancellable) {
    mutex.withLock {
        recoveryAttempt = false
        drainRequiredSnapshot()
    }
}

internal fun EngineCallSource.drainRequiredSnapshot() {
    if (closed || sync == CallSyncState.OwnerChanged || recoveryAttempt || connectionWork != 0) return
    val transport = requiredSnapshot ?: return
    requiredSnapshot = null
    if (activeTransport !== transport) return
    recoveryAttempt = true
    scope.launch(start = CoroutineStart.LAZY) {
        try { fetchSnapshot(transport, recovering = true) }
        finally { finishRecoveryAttempt() }
    }.start()
}

internal suspend inline fun EngineCallSource.withConnectionWork(
    recovering: Boolean, required: EngineMessageTransport? = null, block: () -> Unit,
) {
    mutex.withLock {
        if (closed) return
        if (recoveryAttempt && !recovering) {
            if (required != null && activeTransport === required) requiredSnapshot = required
            return
        }
        connectionWork++
    }
    try { block() }
    finally { withContext(NonCancellable) {
        mutex.withLock { connectionWork--; drainRequiredSnapshot() }
    } }
}
