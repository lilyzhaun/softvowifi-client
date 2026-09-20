package com.vowifi.sw.manager.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageReadSqliteTest {
    @Test
    fun v1RowsMigrateReadThenFreshObservedAndLaterArrivalsStayCorrect() {
        val db = File.createTempFile("softvowifi-messages-v1", ".db")
        try {
            sqlite(db, exportedMessageV1Schema())
            sqlite(
                db,
                """
                INSERT INTO messages VALUES (
                  'msg-old','$OWNER_WIRE_A','INCOMING','+155501','155501','synth-one',
                  1700000000000,'mt-old',NULL,NULL,1700000000500,NULL
                );
                INSERT INTO messages VALUES (
                  'msg-out','$OWNER_WIRE_A','OUTGOING','+155501','155501','synth-out',
                  1700000000000,'mo-old','QUEUED',NULL,1700000000500,NULL
                );
                INSERT INTO messages VALUES (
                  'msg-other','$OWNER_WIRE_B','INCOMING','+155501','155501','synth-b',
                  1700000000000,'mt-b',NULL,NULL,1700000000500,NULL
                );
                """.trimIndent(),
            )

            MessageReadSql.migrate1to2Statements.forEach { statement -> sqlite(db, statement) }

            val columns = sqlite(db, "PRAGMA table_info(messages);")
            assertTrue(columns.contains("read"))
            assertTrue(columns.contains("read_at_ms"))
            assertTrue(columns.contains("|read|INTEGER|1|1|"))
            assertEquals(
                "msg-old|$OWNER_WIRE_A|INCOMING|+155501|155501|synth-one|1700000000000|mt-old|||1700000000500|",
                sqlite(
                    db,
                    "SELECT message_id,sim_owner_id,direction,peer_address,conversation_key,body,message_at_ms,IFNULL(engine_sms_id,''),IFNULL(outgoing_status,''),IFNULL(engine_status,''),status_updated_at_ms,IFNULL(failure_reason,'') FROM messages WHERE message_id = 'msg-old';",
                ),
            )
            val indexes = sqlite(db, "PRAGMA index_list(messages);")
            assertTrue(indexes.contains("index_messages_owner_direction_engine_sms_id"))
            assertTrue(indexes.contains("index_messages_owner_conversation_sort"))
            assertEquals("1|1700000000500", sqlite(db, "SELECT `read`, read_at_ms FROM messages WHERE message_id = 'msg-old';"))
            assertEquals("1|", sqlite(db, "SELECT `read`, IFNULL(read_at_ms,'') FROM messages WHERE message_id = 'msg-out';"))
            assertEquals(emptyList(), MessageReadSql.observedIdChunks(emptyList()))

            sqlite(db, unreadInsert("msg-new", OWNER_WIRE_A, "mt-new", "synth-two", 1_700_000_001_000L))
            assertEquals("0|", sqlite(db, "SELECT `read`, IFNULL(read_at_ms,'') FROM messages WHERE message_id = 'msg-new';"))

            val mark = checkNotNull(
                MessageReadSql.bindMarkObservedUnread(
                    ownerId = OWNER_WIRE_A,
                    conversationKey = "155501",
                    observedIds = listOf("msg-old", "msg-new"),
                    nowMs = 1700000002000L,
                ),
            )
            sqlite(db, boundSqlScript(mark))
            assertEquals("1|1700000002000", sqlite(db, "SELECT `read`, read_at_ms FROM messages WHERE message_id = 'msg-new';"))
            assertEquals("1|1700000000500", sqlite(db, "SELECT `read`, read_at_ms FROM messages WHERE message_id = 'msg-old';"))

            sqlite(db, unreadInsert("msg-later", OWNER_WIRE_A, "mt-later", "synth-later", 1_700_000_003_000L))
            sqlite(db, boundSqlScript(mark))
            assertEquals("0|", sqlite(db, "SELECT `read`, IFNULL(read_at_ms,'') FROM messages WHERE message_id = 'msg-later';"))

            sqlite(db, unreadInsert("msg-new", OWNER_WIRE_A, "mt-new", "synth-dup", 1_700_000_004_000L))
            assertEquals("1|1700000002000", sqlite(db, "SELECT `read`, read_at_ms FROM messages WHERE message_id = 'msg-new';"))
            assertEquals("1", sqlite(db, "SELECT COUNT(*) FROM messages WHERE engine_sms_id = 'mt-new';"))

            val wrongOwner = checkNotNull(
                MessageReadSql.bindMarkObservedUnread(
                    ownerId = OWNER_WIRE_B,
                    conversationKey = "155501",
                    observedIds = listOf("msg-later"),
                    nowMs = 1700000005000L,
                ),
            )
            sqlite(db, boundSqlScript(wrongOwner))
            val wrongConversation = checkNotNull(
                MessageReadSql.bindMarkObservedUnread(
                    ownerId = OWNER_WIRE_A,
                    conversationKey = "155502",
                    observedIds = listOf("msg-later"),
                    nowMs = 1700000005000L,
                ),
            )
            sqlite(db, boundSqlScript(wrongConversation))
            assertEquals("0|", sqlite(db, "SELECT `read`, IFNULL(read_at_ms,'') FROM messages WHERE message_id = 'msg-later';"))
            assertEquals("1|1700000000500", sqlite(db, "SELECT `read`, read_at_ms FROM messages WHERE message_id = 'msg-other';"))

            val uniqueError = sqliteFails(
                db,
                """
                INSERT INTO messages (
                  message_id, sim_owner_id, direction, peer_address, conversation_key, body,
                  message_at_ms, engine_sms_id, outgoing_status, engine_status, status_updated_at_ms,
                  failure_reason, `read`, read_at_ms
                ) VALUES ('msg-new-dup','$OWNER_WIRE_A','INCOMING','+155501','155501','synth-dup',1700000004000,'mt-new',NULL,NULL,1700000004000,NULL,0,NULL)
                """.trimIndent(),
            )
            assertTrue(uniqueError.contains("UNIQUE"), uniqueError)
            assertEquals("1|1700000002000", sqlite(db, "SELECT `read`, read_at_ms FROM messages WHERE message_id = 'msg-new';"))
            assertEquals("1", sqlite(db, "SELECT COUNT(*) FROM messages WHERE engine_sms_id = 'mt-new';"))
            assertEquals("1|1700000000500", sqlite(db, "SELECT `read`, read_at_ms FROM messages WHERE message_id = 'msg-old';"))
            assertEquals("0|", sqlite(db, "SELECT `read`, IFNULL(read_at_ms,'') FROM messages WHERE message_id = 'msg-later';"))
        } finally {
            db.delete()
        }
    }
}

private fun sqlite(db: File, sql: String): String {
    val (code, output) = sqliteRaw(db, sql)
    assertEquals(0, code, output)
    return output.trim()
}

private fun sqliteFails(db: File, sql: String): String {
    val (code, output) = sqliteRaw(db, sql)
    assertTrue(code != 0, output)
    return output
}

private fun sqliteRaw(db: File, sql: String): Pair<Int, String> {
    val process = ProcessBuilder("timeout", "15", "sqlite3", "-batch", db.absolutePath)
        .redirectErrorStream(true)
        .start()
    process.outputStream.bufferedWriter().use { writer ->
        writer.write(sql)
        writer.write(";\n")
    }
    val output = process.inputStream.bufferedReader().readText()
    return process.waitFor() to output
}

private fun unreadInsert(
    messageId: String,
    ownerWire: String,
    engineSmsId: String,
    body: String,
    atMs: Long,
): String = """
INSERT OR IGNORE INTO messages (
  message_id, sim_owner_id, direction, peer_address, conversation_key, body,
  message_at_ms, engine_sms_id, outgoing_status, engine_status, status_updated_at_ms,
  failure_reason, `read`, read_at_ms
) VALUES ('$messageId','$ownerWire','INCOMING','+155501','155501','$body',$atMs,'$engineSmsId',NULL,NULL,$atMs,NULL,0,NULL)
"""
