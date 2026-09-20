package com.vowifi.sw.manager.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val MESSAGE_READ_MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        MessageReadSql.migrate1to2Statements.forEach { statement ->
            connection.execSQL(statement)
        }
    }
}
