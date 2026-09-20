package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallRecord

internal class FakeCallHistoryRepository : CallHistoryRepository {
    var failNextUpsert = false
    var failAllUpserts = false
    var failNextList = false
    var closed = false
    var upsertCount = 0
    var upsertGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private val records = linkedMapOf<StoreKey, CallHistoryWrite>()
    private var selectedOwner: SimOwnerId? = null
    override suspend fun lastOwner(): SimOwnerId? = selectedOwner ?: records.entries
        .sortedWith(compareByDescending<Map.Entry<StoreKey, CallHistoryWrite>> { it.value.times.startedAtMs }
            .thenBy { it.key.owner.wire() }).firstOrNull()?.key?.owner

    override suspend fun selectOwner(ownerId: SimOwnerId) { selectedOwner = ownerId }
    override suspend fun listOwners(): List<SimOwnerId> = records.keys.map { it.owner }.distinct().sortedBy { it.wire() }
    private val newestFirst = compareByDescending<Map.Entry<StoreKey, CallHistoryWrite>> { entry ->
        entry.value.times.startedAtMs
    }.thenByDescending { entry -> entry.key.correlation }

    override suspend fun upsert(ownerId: SimOwnerId, write: CallHistoryWrite) {
        checkOpen()
        upsertGate?.await()
        upsertCount += 1
        if (failAllUpserts || failNextUpsert) {
            failNextUpsert = false
            throw CallHistoryStorageException()
        }
        val key = StoreKey(ownerId, write.correlation.wire())
        records[key] = mergeCallHistory(records[key], write)
        prune(ownerId)
    }

    override suspend fun listByOwner(ownerId: SimOwnerId): List<CallRecord> {
        checkOpen()
        if (failNextList) {
            failNextList = false
            throw CallHistoryStorageException()
        }
        return records.entries
            .filter { entry -> entry.key.owner == ownerId }
            .sortedWith(newestFirst)
            .map { entry -> entry.value.toRecord() }
    }

    override suspend fun clearByOwner(ownerId: SimOwnerId) {
        checkOpen()
        records.keys.filter { key -> key.owner == ownerId }.forEach(records::remove)
    }

    override suspend fun interruptOpen(endedAtMs: Long) {
        checkOpen()
        records.forEach { (key, write) ->
            if (write.outcome.isTerminal()) return@forEach
            val interrupted = write.interrupted(endedAtMs) ?: return@forEach
            records[key] = mergeCallHistory(write, interrupted)
        }
    }

    override fun close() {
        closed = true
    }

    private fun prune(ownerId: SimOwnerId) {
        val owned = records.entries.filter { entry -> entry.key.owner == ownerId }
        if (owned.size <= CallHistoryPolicy.MAX_RECORDS_PER_OWNER) return
        owned.sortedWith(newestFirst)
            .drop(CallHistoryPolicy.MAX_RECORDS_PER_OWNER)
            .forEach { entry -> records.remove(entry.key) }
    }

    private fun checkOpen() {
        check(!closed) { "repository closed" }
    }

    private data class StoreKey(val owner: SimOwnerId, val correlation: String) {
        override fun toString(): String = "StoreKey"
    }
}
