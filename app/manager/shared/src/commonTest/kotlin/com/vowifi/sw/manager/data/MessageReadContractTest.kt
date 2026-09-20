package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MessageReadContractTest {
    @Test
    fun snapshotsOwnInputsAndReturnedLists() {
        val raw = mutableListOf("seen-1", "seen-2")
        val request = checkNotNull(ObservedIncomingReadRequest.create(OWNER_A, "155501", raw))
        val result = IncomingReadUpdate.of(raw)
        raw += "unseen"
        (request.observedMessageIds as MutableList<String>).clear()
        (result.updatedIds as MutableList<String>).clear()
        assertEquals(listOf("seen-1", "seen-2"), request.observedMessageIds)
        assertEquals(listOf("seen-1", "seen-2"), result.updatedIds)
        assertEquals(2, result.updatedCount)
        assertTrue(ObservedIncomingReadRequest::class.java.declaredConstructors
            .filterNot { it.isSynthetic }.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) })
        assertTrue(IncomingReadUpdate::class.java.declaredConstructors
            .filterNot { it.isSynthetic }.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) })
        assertFalse(ObservedIncomingReadRequest::class.java.methods.any { it.name == "copy" })
        assertFalse(IncomingReadUpdate::class.java.methods.any { it.name == "copy" })
    }

    @Test
    fun productionSqlReadsAll2005ObservedIdsUnder999BindLimit() = runTest {
        MessageReadSqliteDriver().use { database ->
            database.execute(exportedMessageV1Schema())
            database.execute("INSERT INTO message_metadata VALUES ('synth-key','synth-value');")
            MessageReadSql.migrate1to2Statements.forEach { database.execute(it) }
            database.execute("""
                WITH RECURSIVE sequence(number) AS (VALUES(1) UNION ALL SELECT number+1 FROM sequence WHERE number<2006)
                INSERT INTO messages(message_id,sim_owner_id,direction,peer_address,conversation_key,body,message_at_ms,engine_sms_id,status_updated_at_ms,`read`)
                SELECT 'seen-'||number,'$OWNER_WIRE_A','INCOMING','+155501','155501','synth',number,'mt-'||number,number,0 FROM sequence;
                INSERT INTO messages(message_id,sim_owner_id,direction,peer_address,conversation_key,body,message_at_ms,status_updated_at_ms,`read`)
                VALUES ('other-owner','$OWNER_WIRE_B','INCOMING','+155501','155501','synth',1,1,0),
                       ('other-peer','$OWNER_WIRE_A','INCOMING','+155502','155502','synth',1,1,0),
                       ('outgoing','$OWNER_WIRE_A','OUTGOING','+155501','155501','synth',1,1,0);
            """.trimIndent())
            val ids = (1..2005).map { "seen-$it" }
            val request = checkNotNull(ObservedIncomingReadRequest.create(
                OWNER_A, "155501", ids + listOf("other-owner", "other-peer", "outgoing", "missing"),
            ))
            val updated = database.mark(request, 9000)
            assertEquals(ids.toSet(), updated.toSet())
            assertEquals(2005, updated.size)
            assertEquals(listOf("2005"), database.execute("SELECT count(*) FROM messages WHERE `read`=1 AND read_at_ms=9000;"))
            assertEquals(listOf("other-owner", "other-peer", "outgoing", "seen-2006"), database.execute(
                "SELECT message_id FROM messages WHERE `read`=0 ORDER BY message_id;",
            ))
            assertTrue(database.mark(request, 9500).isEmpty())
            assertEquals(listOf("2005"), database.execute("SELECT count(*) FROM messages WHERE read_at_ms=9000;"))
            assertEquals(listOf("synth-value"), database.execute("SELECT value FROM message_metadata WHERE key='synth-key';"))
            assertTrue(database.mark(checkNotNull(ObservedIncomingReadRequest.create(OWNER_A, "155501", emptyList())), 9600).isEmpty())
        }
    }

    @Test
    fun chunkHelperBoundsBothQueriesAtBoundarySizes() = runTest {
        for (count in listOf(0, 996, 997, 998, 2005)) {
            val ids = (1..count).map { "id-$it" }
            val selected = mutableListOf<Int>()
            val marked = mutableListOf<Int>()
            val result = markObservedUnreadChunked(ids, { chunk ->
                selected += chunk.size
                chunk
            }, { chunk -> marked += chunk.size })
            assertEquals(ids, result)
            assertEquals(selected, marked)
            assertTrue(selected.all { it <= 996 })
            assertEquals((count + 995) / 996, selected.size)
        }
    }
}
