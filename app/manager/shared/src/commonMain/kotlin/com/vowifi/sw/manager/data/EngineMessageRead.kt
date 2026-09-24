package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.withLock

internal suspend fun markVisibleIncomingRead(
    session: EngineMessageSession,
    request: ObservedIncomingReadRequest,
) {
    val nowMs = session.mutex.withLock {
        if (session.closed) return
        session.runtime.clock()
    }
    try {
        val update = session.repository.markIncomingRead(request, nowMs)
        session.unreadSummary.refresh()
        session.mutex.withLock {
            if (session.closed) return
            session.observer.onRead(request.ownerId, update.updatedIds.toSet())
            session.selection.read(request.ownerId, update, nowMs)
            val line = session.sessions[request.ownerId] ?: return
            if (update.updatedCount == 0) return
            val updated = update.updatedIds.toHashSet()
            line.records = line.records.map { record ->
                if (record is MessageRecord.Incoming && !record.isRead && record.id in updated) {
                    record.copy(isRead = true, readAtMs = nowMs)
                } else {
                    record
                }
            }
            session.publishRecords(line)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: MessageStorageException) {
        session.mutex.withLock {
            if (session.closed) return
            val line = session.sessions[request.ownerId] ?: return
            line.storageHealthy = false
            line.attachmentNotice = STORAGE_SYNC_NOTICE
            session.publishRecords(line)
        }
    }
}
