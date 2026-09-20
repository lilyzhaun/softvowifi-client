package com.vowifi.sw.manager.data

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun exportedMessageV1Schema(): String {
    val schema = File("schemas/com.vowifi.sw.manager.data.SoftVoWiFiMessageDatabase/1.json")
    val database = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject
    val statements = database.getValue("entities").jsonArray.flatMap { entity ->
        val table = entity.jsonObject
        val name = table.getValue("tableName").jsonPrimitive.content
        val create = table.getValue("createSql").jsonPrimitive.content
        val indexes = table["indices"]?.jsonArray.orEmpty().map { index ->
            index.jsonObject.getValue("createSql").jsonPrimitive.content
        }
        (listOf(create) + indexes).map { it.replace("\${TABLE_NAME}", name) }
    } + database.getValue("setupQueries").jsonArray.map { it.jsonPrimitive.content }
    return statements.joinToString(";\n", postfix = ";\nPRAGMA user_version=1;")
}

internal fun boundSqlScript(bound: BoundObservedSql): String = buildString {
    appendLine(".parameter clear")
    bound.args.forEachIndexed { index, value ->
        val hex = value.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        appendLine(".parameter set ?${index + 1} \"CAST(X'$hex' AS TEXT)\"")
    }
    appendLine("${bound.sql};")
}

internal class MessageReadSqliteDriver : AutoCloseable {
    private val file = File.createTempFile("message-read-driver-", ".db")
    private val process = ProcessBuilder("timeout", "20", "sqlite3", "-batch", file.absolutePath)
        .redirectErrorStream(true).start()
    private val input = process.outputStream.bufferedWriter()
    private val output = process.inputStream.bufferedReader()

    init {
        execute(".bail on\n.limit variable_number 999")
    }

    fun execute(sql: String): List<String> {
        input.write("$sql\n;\n.print __message_read_end__\n")
        input.flush()
        val lines = mutableListOf<String>()
        while (true) {
            val line = checkNotNull(output.readLine()) { "SQLite terminated: $lines" }
            if (line == "__message_read_end__") return lines
            lines += line
        }
    }

    fun execute(bound: BoundObservedSql): List<String> {
        assertTrue(bound.args.size <= 999)
        assertEquals(bound.args.size, bound.sql.count { it == '?' })
        return execute(boundSqlScript(bound))
    }

    suspend fun mark(request: ObservedIncomingReadRequest, nowMs: Long): List<String> {
        execute("BEGIN IMMEDIATE;")
        val updated = markObservedUnreadChunked(
            request.observedMessageIds,
            { execute(MessageReadSql.bindListObservedUnread(request.ownerId.wire(), request.conversationKey, it)) },
            { execute(MessageReadSql.bindMarkObservedUnread(request.ownerId.wire(), request.conversationKey, it, nowMs)) },
        )
        execute("COMMIT;")
        return updated
    }

    override fun close() {
        input.close()
        process.waitFor()
        output.close()
        file.delete()
    }
}
