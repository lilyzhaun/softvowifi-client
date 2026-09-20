package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallRecord

internal class CallHistoryStorageException : Exception(CALL_HISTORY_STORAGE_NOTICE) {
    override fun toString(): String = "CallHistoryStorageException"
}

internal const val CALL_HISTORY_STORAGE_NOTICE = "无法确认通话记录已保存"

internal fun openCallHistoryStorageGuard(isDeviceProtectedStorage: Boolean) {
    try {
        requireCredentialEncryptedStorage(isDeviceProtectedStorage)
    } catch (_: MessageStorageException) {
        throw CallHistoryStorageException()
    }
}

interface CallHistoryRepository {
    suspend fun lastOwner(): SimOwnerId?
    suspend fun listOwners(): List<SimOwnerId>
    suspend fun selectOwner(ownerId: SimOwnerId)
    suspend fun upsert(ownerId: SimOwnerId, write: CallHistoryWrite)
    suspend fun listByOwner(ownerId: SimOwnerId): List<CallRecord>
    suspend fun clearByOwner(ownerId: SimOwnerId)
    suspend fun interruptOpen(endedAtMs: Long)
    fun close()
}
