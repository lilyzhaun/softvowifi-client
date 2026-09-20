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
            session.observer.onRead(request.ownerId, update.updatedIds.toSet())
            session.selection.read(request.ownerId, update, nowMs)
            if (session.closed || session.activeOwner != request.ownerId) return
            if (update.updatedCount == 0) return
            val updated = update.updatedIds.toHashSet()
            session.records = session.records.map { record ->
                if (record is MessageRecord.Incoming && !record.isRead && record.id in updated) {
                    record.copy(isRead = true, readAtMs = nowMs)
                } else {
                    record
                }
            }
            session.publishRecords()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: MessageStorageException) {
        session.mutex.withLock {
            if (session.closed || session.activeOwner != request.ownerId) return
            session.storageHealthy = false
            session.attachmentNotice = STORAGE_SYNC_NOTICE
            session.publishRecords()
        }
    }
}
