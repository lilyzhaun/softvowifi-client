package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class MessageUnreadSqliteTest {
    @Test
    fun productionCountExcludesMigratedHistoryAcrossOwners() {
        MessageReadSqliteDriver().use { database ->
            database.execute(exportedMessageV1Schema())
            database.execute("""
                INSERT INTO messages(message_id,sim_owner_id,direction,peer_address,conversation_key,body,message_at_ms,status_updated_at_ms)
                VALUES ('old-a','$OWNER_WIRE_A','INCOMING','155501','155501','synthetic',1,2),
                       ('old-b','$OWNER_WIRE_B','INCOMING','155501','155501','synthetic',1,2);
            """.trimIndent())
            MessageReadSql.migrate1to2Statements.forEach { database.execute(it) }
            assertEquals(listOf("0"), database.execute(COUNT_UNREAD_INCOMING_SQL))
        }
    }

    @Test
    fun productionCountIncludesAllOwnersButOnlyUnreadIncomingAfterObservedRead() = runTest {
        MessageReadSqliteDriver().use { database ->
            database.execute(exportedMessageV1Schema())
            MessageReadSql.migrate1to2Statements.forEach { database.execute(it) }
            database.execute("""
                INSERT INTO messages(message_id,sim_owner_id,direction,peer_address,conversation_key,body,message_at_ms,status_updated_at_ms,`read`)
                VALUES ('a-1','$OWNER_WIRE_A','INCOMING','155501','155501','synthetic',1,2,0),
                       ('a-2','$OWNER_WIRE_A','INCOMING','155501','155501','synthetic',1,2,0),
                       ('b-1','$OWNER_WIRE_B','INCOMING','155501','155501','synthetic',1,2,0),
                       ('b-2','$OWNER_WIRE_B','INCOMING','155501','155501','synthetic',1,2,0),
                       ('b-3','$OWNER_WIRE_B','INCOMING','155501','155501','synthetic',1,2,0),
                       ('out','$OWNER_WIRE_B','OUTGOING','155501','155501','synthetic',1,2,0);
            """.trimIndent())
            assertEquals(listOf("5"), database.execute(COUNT_UNREAD_INCOMING_SQL))
            database.mark(unreadRequest(OWNER_A, "a-1"), 9000)
            assertEquals(listOf("4"), database.execute(COUNT_UNREAD_INCOMING_SQL))
            assertEquals(listOf("3"), database.execute("SELECT COUNT(*) FROM messages WHERE sim_owner_id='$OWNER_WIRE_B' AND direction='INCOMING' AND `read`=0;"))
        }
    }
}
