package com.vowifi.sw

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.MessageRepository
import com.vowifi.sw.manager.data.SimOwnerId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import org.robolectric.RuntimeEnvironment

internal class SmsRoomBoundaryFixture : AutoCloseable {
    private val app = RuntimeEnvironment.getApplication()
    private val name = "sms-boundary-${UUID.randomUUID()}.db"
    private val databaseClass = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase").asSubclass(RoomDatabase::class.java)
    val ownerA = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    val ownerB = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))

    fun open(): MessageRepository {
        val database = Room.databaseBuilder(app, databaseClass, name).setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
        return Class.forName("com.vowifi.sw.manager.data.AndroidMessageRepository").getConstructor(databaseClass)
            .newInstance(database) as MessageRepository
    }
    override fun close() { app.deleteDatabase(name) }
}
