package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageUiState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal suspend fun refreshMessageAttachment(session: EngineMessageSession, force: Boolean = true) {
    val instances = try {
        session.runtime.listInstances()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: MessagePermissionException) {
        session.mutex.withLock {
            session.fail(MessageUiState.PermissionDenied(error.message ?: MESSAGE_PERMISSION_MESSAGE))
        }
        return
    } catch (_: Exception) {
        session.mutex.withLock { session.fail(MessageUiState.Failure(MESSAGE_LIST_FAILURE)) }
        return
    }
    session.mutex.withLock {
        val selected = ActiveEngineSelector.select(instances)
        val unchangedNotice = when (selected) {
            ActiveEngineSelection.None -> ActiveEngineSelector.NONE_MESSAGE
            ActiveEngineSelection.Multiple -> ActiveEngineSelector.MULTIPLE_MESSAGE
            is ActiveEngineSelection.Single -> null
        }
        if (!force && session.storageHealthy && session.activeTransport == null &&
            unchangedNotice != null && session.attachmentNotice == unchangedNotice) return
        try {
            when (val selection = ActiveEngineSelector.select(instances)) {
                ActiveEngineSelection.None -> showLastOwnerReadOnly(
                    session,
                    ActiveEngineSelector.NONE_MESSAGE,
                    MessageUiState.Unavailable(ActiveEngineSelector.NONE_MESSAGE),
                )
                ActiveEngineSelection.Multiple -> showLastOwnerReadOnly(
                    session,
                    ActiveEngineSelector.MULTIPLE_MESSAGE,
                    MessageUiState.Failure(ActiveEngineSelector.MULTIPLE_MESSAGE),
                )
                is ActiveEngineSelection.Single -> attachMessageTransport(session, selection.iccid)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            session.fail(MessageUiState.Failure(STORAGE_OPEN_NOTICE))
        }
    }
}

private suspend fun showLastOwnerReadOnly(
    session: EngineMessageSession,
    notice: String,
    missing: MessageUiState,
) {
    session.detachTransport()
    val last = session.repository.lastOwner()
    if (last == null) {
        session.records = emptyList()
        session.activeOwner = null
        session.attachmentNotice = null
        session.fail(missing)
        return
    }
    session.activeOwner = last
    session.repository.interruptLeftoverQueued(last, session.runtime.clock())
    session.records = session.repository.listByOwner(last)
    session.attachmentNotice = notice
    session.publishRecords()
}

private suspend fun attachMessageTransport(session: EngineMessageSession, iccid: String) {
    val owner = session.ownerIdFor(iccid)
    if (owner == null) {
        session.fail(MessageUiState.Failure(MESSAGE_IDENTITY_NOTICE))
        return
    }
    if (session.activeIccid == iccid && session.activeTransport != null && session.activeOwner == owner) {
        if (!session.storageHealthy && !session.sendBusy) {
            session.repository.interruptLeftoverQueued(owner, session.runtime.clock())
        }
        if (!session.storageHealthy && !session.sendBusy) {
            session.records = session.repository.listByOwner(owner)
            session.storageHealthy = true
            session.attachmentNotice = null
        }
        session.publishRecords()
        return
    }
    session.detachTransport()
    session.repository.interruptLeftoverQueued(owner, session.runtime.clock())
    session.repository.setLastOwner(owner)
    session.activeOwner = owner
    session.activeIccid = iccid
    val transport = session.runtime.transportFor(iccid)
    session.activeTransport = transport
    session.records = session.repository.listByOwner(owner)
    session.storageHealthy = true
    session.attachmentNotice = null
    session.publishRecords()
    session.eventJob = session.scope.launch { collectMessageEvents(session, transport) }
}
