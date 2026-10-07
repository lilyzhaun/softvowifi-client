package com.vowifi.sw.manager.data

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Transaction
import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallTimesDraft

@Entity(
    tableName = "call_records",
    primaryKeys = ["sim_owner_id", "correlation_id"],
    indices = [
        Index(
            name = "index_call_records_owner_started",
            value = ["sim_owner_id", "started_at_ms", "correlation_id"],
        ),
    ],
)
internal data class CallRecordEntity(
    @ColumnInfo(name = "sim_owner_id") val simOwnerId: String,
    @ColumnInfo(name = "correlation_id") val correlationId: String,
    @ColumnInfo(name = "direction") val direction: String,
    @ColumnInfo(name = "peer_phone") val peerPhone: String?,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "connected_at_ms") val connectedAtMs: Long?,
    @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long?,
    @ColumnInfo(name = "duration_sec") val durationSec: Long?,
    @ColumnInfo(name = "outcome") val outcome: String,
) {
    override fun toString(): String = "CallRecordEntity"
}

@Dao
internal interface CallRecordDao {
    @Query("SELECT DISTINCT sim_owner_id FROM call_records ORDER BY sim_owner_id ASC")
    suspend fun listOwners(): List<String>

    @Query("SELECT sim_owner_id FROM call_records GROUP BY sim_owner_id ORDER BY MAX(started_at_ms) DESC, sim_owner_id ASC LIMIT 1")
    suspend fun mostRecentOwner(): String?

    @Query("SELECT owner_id FROM call_history_selection WHERE id = 1")
    suspend fun selectedOwner(): String?

    @Query("DELETE FROM call_history_selection WHERE id = 1 AND owner_id = :malformedOwner")
    suspend fun clearSelectedOwner(malformedOwner: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun selectOwner(selection: CallHistorySelectionEntity)

    @Query("SELECT * FROM call_records WHERE sim_owner_id = :ownerId AND correlation_id = :correlationId")
    suspend fun find(ownerId: String, correlationId: String): CallRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entity: CallRecordEntity)

    @Query(
        "SELECT * FROM call_records WHERE sim_owner_id = :ownerId ORDER BY started_at_ms DESC, correlation_id DESC",
    )
    suspend fun listByOwner(ownerId: String): List<CallRecordEntity>

    @Query("SELECT * FROM call_records WHERE outcome IN ('STARTED', 'CONNECTED')")
    suspend fun listOpen(): List<CallRecordEntity>

    @Query("DELETE FROM call_records WHERE sim_owner_id = :ownerId")
    suspend fun clearByOwner(ownerId: String)

    @Query("DELETE FROM call_records WHERE sim_owner_id = :ownerId AND correlation_id IN (:correlations)")
    suspend fun deleteByIds(ownerId: String, correlations: List<String>)

    @Query(
        """
        DELETE FROM call_records
        WHERE sim_owner_id = :ownerId
          AND rowid NOT IN (
            SELECT rowid FROM call_records
            WHERE sim_owner_id = :ownerId
            ORDER BY started_at_ms DESC, correlation_id DESC
            LIMIT :keep
          )
        """,
    )
    suspend fun pruneOwner(ownerId: String, keep: Int)

    @Transaction
    suspend fun upsertAndPrune(entity: CallRecordEntity, keep: Int) {
        val current = find(entity.simOwnerId, entity.correlationId)
        val merged = mergeCallHistory(current?.toWrite(), entity.toWrite())
        replace(merged.toEntity(entity.simOwnerId))
        pruneOwner(entity.simOwnerId, keep)
    }
}

@Database(
    entities = [CallRecordEntity::class, CallHistorySelectionEntity::class],
    version = 2,
    exportSchema = true,
)
internal abstract class SoftVoWiFiCallDatabase : RoomDatabase() {
    abstract fun callRecordDao(): CallRecordDao
}

internal fun CallRecordEntity.toWrite(): CallHistoryWrite {
    val parsedDirection = try {
        CallDirection.valueOf(direction)
    } catch (_: IllegalArgumentException) {
        throw CallHistoryStorageException()
    }
    val parsedOutcome = try {
        CallOutcome.valueOf(outcome)
    } catch (_: IllegalArgumentException) {
        throw CallHistoryStorageException()
    }
    return CallHistoryWrite.parse(
        CallHistoryDraft(
            correlation = correlationId,
            direction = parsedDirection,
            peer = peerPhone,
            times = CallTimesDraft(startedAtMs, connectedAtMs, endedAtMs, durationSec),
            outcome = parsedOutcome,
        ),
    ) ?: throw CallHistoryStorageException()
}

internal fun CallHistoryWrite.toEntity(ownerWire: String): CallRecordEntity = CallRecordEntity(
    simOwnerId = ownerWire,
    correlationId = correlation.wire(),
    direction = direction.name,
    peerPhone = peer?.dialNumber(),
    startedAtMs = times.startedAtMs,
    connectedAtMs = times.connectedAtMs,
    endedAtMs = times.endedAtMs,
    durationSec = times.durationSec,
    outcome = outcome.name,
)
