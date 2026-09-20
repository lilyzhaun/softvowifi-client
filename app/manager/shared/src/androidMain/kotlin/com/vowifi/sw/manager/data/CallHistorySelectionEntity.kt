package com.vowifi.sw.manager.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Entity(tableName = "call_history_selection")
internal class CallHistorySelectionEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "owner_id") val ownerId: String,
)

internal val CALL_HISTORY_MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `call_history_selection` (`id` INTEGER NOT NULL, `owner_id` TEXT NOT NULL, PRIMARY KEY(`id`))")
    }
}
