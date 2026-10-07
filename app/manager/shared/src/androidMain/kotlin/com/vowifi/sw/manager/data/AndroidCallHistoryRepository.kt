package com.vowifi.sw.manager.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.model.CallRecord
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers

internal class AndroidCallHistoryRepository(
    private val database: SoftVoWiFiCallDatabase,
) : CallHistoryRepository {
    private val calls = database.callRecordDao()

    override suspend fun listOwners(): List<SimOwnerId> = storageCall {
        calls.listOwners().map { SimOwnerId.parse(it) ?: throw CallHistoryStorageException() }
    }

    override suspend fun lastOwner(): SimOwnerId? = storageCall {
        val preference = calls.selectedOwner()
        preference?.let(SimOwnerId::parse)?.let { return@storageCall it }
        if (preference != null) calls.clearSelectedOwner(preference)
        calls.mostRecentOwner()?.let { SimOwnerId.parse(it) ?: throw CallHistoryStorageException() }
    }

    override suspend fun selectOwner(ownerId: SimOwnerId) = storageCall {
        calls.selectOwner(CallHistorySelectionEntity(ownerId = ownerId.wire()))
    }

    override suspend fun upsert(ownerId: SimOwnerId, write: CallHistoryWrite) = storageCall {
        calls.upsertAndPrune(write.toEntity(ownerId.wire()), CallHistoryPolicy.MAX_RECORDS_PER_OWNER)
    }

    override suspend fun listByOwner(ownerId: SimOwnerId): List<CallRecord> = storageCall {
        calls.listByOwner(ownerId.wire()).map { entity -> entity.toWrite().toRecord(ownerId) }
    }

    override suspend fun clearByOwner(ownerId: SimOwnerId) = storageCall {
        calls.clearByOwner(ownerId.wire())
    }
    override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<com.vowifi.sw.manager.model.CallHistoryRecordId>) = storageCall {
        val correlations = calls.listByOwner(ownerId.wire()).filter { it.toWrite().toRecord(ownerId).recordId in ids }.map { it.correlationId }
        if (correlations.isNotEmpty()) calls.deleteByIds(ownerId.wire(), correlations)
    }

    override suspend fun interruptOpen(endedAtMs: Long) = storageCall {
        calls.listOpen().forEach { entity ->
            val interrupted = entity.toWrite().interrupted(endedAtMs) ?: return@forEach
            calls.upsertAndPrune(interrupted.toEntity(entity.simOwnerId), CallHistoryPolicy.MAX_RECORDS_PER_OWNER)
        }
    }

    override fun close() {
        database.close()
    }

    companion object {
        const val FILE_NAME = "softvowifi-calls.db"

        fun open(
            context: Context,
            queryContext: CoroutineContext = Dispatchers.IO.limitedParallelism(1),
        ): AndroidCallHistoryRepository {
            val appContext = context.applicationContext
            openCallHistoryStorageGuard(appContext.isDeviceProtectedStorage)
            val database = Room.databaseBuilder(
                appContext,
                SoftVoWiFiCallDatabase::class.java,
                FILE_NAME,
            )
                .addMigrations(CALL_HISTORY_MIGRATION_1_2)
                .setDriver(AndroidSQLiteDriver())
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCoroutineContext(queryContext)
                .build()
            return AndroidCallHistoryRepository(database)
        }
    }
}

private suspend fun <T> storageCall(block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: CallHistoryStorageException) {
    throw error
} catch (_: Exception) {
    throw CallHistoryStorageException()
}
