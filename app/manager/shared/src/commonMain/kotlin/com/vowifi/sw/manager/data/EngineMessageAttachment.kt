package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageUiState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal suspend fun refreshMessageAttachment(session: EngineMessageSession, force: Boolean = true) {
    session.attachmentMutex.withLock {
        if (session.closed) return
        if (!recoverStartupMessages(session)) return
        val instances = try {
            session.runtime.listInstances()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MessagePermissionException) {
            failMessageAttachment(session, MessageUiState.PermissionDenied(MESSAGE_PERMISSION_MESSAGE))
            return
        } catch (_: Exception) {
            failMessageAttachment(session, MessageUiState.Failure(MESSAGE_LIST_FAILURE))
            return
        }
        val active = try { session.runtime.activeIccids()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { null }
        session.mutex.withLock {
            if (session.closed) return
            val owners = active?.map { it?.let(session.ownerIdFor) }
            session.activeOwners = owners?.filterNotNull()?.toSet()
            session.activeRosterComplete = owners != null && owners.none { it == null }
            session.lineLabels = active.orEmpty().filterNotNull().mapNotNull { raw ->
                val owner = session.ownerIdFor(raw) ?: return@mapNotNull null
                session.runtime.lineLabelFor(raw)?.let { owner to it }
            }.toMap()
            val running = instances.filter { it.lifecycle == EngineLifecycle.RUNNING }
             val targets = running.mapNotNull { instance -> session.ownerIdFor(instance.iccid)?.let { it to instance } }.toMap()
             session.runningOwners = targets.keys
            try {
                val offlineUnchanged = !force && session.sessions.isEmpty() && running.isEmpty() &&
                    session.fallback is MessageUiState.Ready
                session.sessions.values.filter { line ->
                    val target = targets[line.owner]
                    target == null || target.iccid != line.iccid || target.pid != line.pid || !line.attached
                }.forEach { line ->
                    session.detach(line)
                    session.sessions = session.sessions - line.owner
                }
                if (!offlineUnchanged) session.fallback = when {
                    running.size != targets.size -> MessageUiState.Failure(MESSAGE_IDENTITY_NOTICE)
                    running.isNotEmpty() -> MessageUiState.Unavailable(MESSAGE_SELECT_OWNER)
                    else -> MessageUiState.Unavailable(ActiveEngineSelector.NONE_MESSAGE)
                }
                if (running.isEmpty() && !offlineUnchanged) loadOfflineOwner(session)
                targets.forEach { (owner, instance) ->
                  try {
                    val current = session.sessions[owner]
                    if (current != null) {
                        if (!current.storageHealthy && !current.sendBusy) {
                            session.repository.interruptLeftoverQueued(owner, session.runtime.clock())
                            current.records = session.repository.listByOwner(owner)
                            current.storageHealthy = true
                        }
                        refreshMessageStatus(session, current)
                    } else {
                        val line = MessageLiveSession(owner, instance.iccid, instance.pid, session.runtime.transportFor(instance.iccid))
                        line.records = session.repository.listByOwner(owner)
                        if (session.activeRosterComplete && session.activeOwners?.singleOrNull() == owner) {
                            session.repository.setLastOwner(owner)
                        }
                        session.sessions = session.sessions + (owner to line)
                        line.disconnectJob = session.scope.launch {
                            line.transport.messageDisconnects.collect { disconnectMessageSession(session, line) }
                        }
                        line.eventJob = session.scope.launch { collectMessageEvents(session, line) }
                        session.publishRecords(line)
                    }
                  } catch (cancelled: CancellationException) { throw cancelled
                  } catch (_: Exception) {
                      session.sessions[owner]?.let { it.storageHealthy = false; it.attachmentNotice = STORAGE_OPEN_NOTICE }
                      session.fallback = MessageUiState.Failure(STORAGE_OPEN_NOTICE)
                  }
                }
                session.publish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                session.fallback = MessageUiState.Failure(STORAGE_OPEN_NOTICE)
                session.publish()
            }
        }
    }
}

private suspend fun recoverStartupMessages(session: EngineMessageSession): Boolean {
    if (session.startupRecovered) return true
    try {
        session.selection.initialOwners().forEach {
            session.repository.interruptLeftoverQueued(it, session.runtime.clock())
        }
        session.startupRecovered = true
        session.selection.refreshAfterStartup()
        return true
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (_: MessageStorageException) {
        session.mutex.withLock {
            session.fallback = MessageUiState.Failure(STORAGE_OPEN_NOTICE)
            session.publish()
        }
        return false
    }
}

private suspend fun failMessageAttachment(session: EngineMessageSession, state: MessageUiState) {
    session.mutex.withLock {
        if (session.closed) return
        var failure = state
        session.sessions.values.forEach { line ->
            try { session.detach(line)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { failure = MessageUiState.Failure(STORAGE_SYNC_NOTICE) }
        }
        session.sessions = emptyMap()
        session.automaticOwner = null
        session.fallback = failure
        session.publish()
    }
}

private suspend fun loadOfflineOwner(session: EngineMessageSession) {
    val owner = session.repository.lastOwner() ?: return
    session.fallback = MessageUiState.Ready(session.repository.listByOwner(owner), MessageOrigin.ENGINE,
        ownerId = owner, attachmentNotice = ActiveEngineSelector.NONE_MESSAGE)
}

internal fun refreshMessageStatus(session: EngineMessageSession, line: MessageLiveSession) {
    if (!session.isCurrent(line) || !line.connected || line.statusJob?.isActive == true) return
    line.statusJob = session.scope.launch {
        try {
            val response = line.transport.request("getStatus")
            val registered = response.error == null && EngineProtocolCodec.status(response).ims == "REGISTERED"
            session.mutex.withLock {
                if (!session.isCurrent(line)) return@withLock
                line.registered = registered
                if (line.storageHealthy) line.attachmentNotice = if (registered) null else MESSAGE_NOT_REGISTERED
                session.publish()
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { disconnectMessageSession(session, line) }
    }
}

internal suspend fun disconnectMessageSession(session: EngineMessageSession, line: MessageLiveSession) {
    session.mutex.withLock {
        if (!session.isCurrent(line)) return
        try {
            session.detach(line)
            line.attachmentNotice = MESSAGE_CONNECT_FAILURE
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            line.storageHealthy = false
            line.attachmentNotice = STORAGE_SYNC_NOTICE
        }
        if (session.sessions.values.none { it.connected }) session.fallback = MessageUiState.Failure(MESSAGE_CONNECT_FAILURE)
        session.publishRecords(line)
    }
}
