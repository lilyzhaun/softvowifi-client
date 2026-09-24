package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogFile
import com.vowifi.sw.manager.model.OperationalLogFormatException
import com.vowifi.sw.manager.model.OperationalLogList
import com.vowifi.sw.manager.model.OperationalLogTail
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object OperationalLogShellCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val parser = EngineOperationalLogParser()

    fun list(output: String): OperationalLogList {
        if (OperationalLogRedactor.requiresWireRedaction(output)) invalid("日志目录包含未脱敏内容")
        val wire = decode<ListEnvelope>(output)
        if (!wire.ok || wire.rotatedCount < 0) invalid("日志目录返回失败")
        val files = wire.files.map { file ->
            if (!safeOpaqueId(file.id) || file.name !in allowedNames || !suffix.matches(file.suffix)) {
                invalid("日志目录包含不安全字段")
            }
            OperationalLogFile(file.id, file.name, file.suffix)
        }
        return OperationalLogList(wire.rotatedCount, files)
    }

    fun tail(output: String): OperationalLogTail {
        if (OperationalLogRedactor.requiresWireRedaction(output)) invalid("日志内容未完成脱敏")
        val wire = decode<TailEnvelope>(output)
        if (!wire.ok || !safeOpaqueId(wire.id)) invalid("日志读取返回失败")
        if (wire.lines.any(OperationalLogRedactor::requiresWireRedaction)) invalid("日志内容未完成脱敏")
        val parsed = parser.parse(wire.lines.takeLast(50).joinToString("\n"))
        return OperationalLogTail(wire.id, wire.truncated || wire.lines.size > 50, parsed.entries, parsed.issues)
    }

    fun catalog(output: String): OperationalLogList {
        val wire = decode<KeyCatalogEnvelope>(output)
        if (!wire.ok || wire.rotatedCount < 0 || wire.files.size > 32) invalid("日志目录返回失败")
        val files = wire.files.map { file ->
            if (!identityKey.matches(file.key) || file.name !in allowedNames || !suffix.matches(file.suffix)) {
                invalid("日志目录包含不安全字段")
            }
            OperationalLogFile(file.key, file.name, file.suffix)
        }
        if (files.map { it.id }.distinct().size != files.size) invalid("日志目录包含重复身份")
        return OperationalLogList(wire.rotatedCount, files)
    }

    fun read(output: String): OperationalLogTail {
        val wire = decode<KeyReadEnvelope>(output)
        if (!wire.ok || !identityKey.matches(wire.key) || wire.lines.size > 200) invalid("日志读取返回失败")
        if (wire.lines.any(OperationalLogRedactor::requiresWireRedaction)) invalid("日志内容未完成脱敏")
        val parsed = parser.parse(wire.lines.takeLast(50).joinToString("\n"))
        return OperationalLogTail(wire.key, wire.truncated || wire.lines.size > 50, parsed.entries, parsed.issues)
    }

    @Serializable
    private data class KeyCatalogEnvelope(val ok: Boolean, val rotatedCount: Int, val files: List<KeyFileEnvelope>)

    @Serializable
    private data class KeyFileEnvelope(val key: String, val name: String, val suffix: String)

    @Serializable
    private data class KeyReadEnvelope(val ok: Boolean, val key: String, val truncated: Boolean, val lines: List<String>)

    private inline fun <reified T> decode(output: String): T = try {
        json.decodeFromString<T>(output)
    } catch (error: SerializationException) {
        throw OperationalLogFormatException(error.message ?: "日志 JSON 格式无效")
    } catch (error: IllegalArgumentException) {
        throw OperationalLogFormatException(error.message ?: "日志 JSON 格式无效")
    }

    private fun invalid(message: String): Nothing = throw OperationalLogFormatException(message)

    private fun safeOpaqueId(id: String): Boolean = ordinalId.matches(id)

    @Serializable
    private data class ListEnvelope(
        val ok: Boolean,
        val rotatedCount: Int,
        val files: List<FileEnvelope>,
    )

    @Serializable
    private data class FileEnvelope(
        val id: String,
        val name: String,
        val suffix: String,
    )

    @Serializable
    private data class TailEnvelope(
        val ok: Boolean,
        val id: String,
        val truncated: Boolean,
        val lines: List<String>,
    )

    private val ordinalId = Regex("""f(?:[1-9]|[12]\d|3[0-2])""")
    private val identityKey = Regex("[0-9a-f]{64}")
    private val suffix = Regex("""\d{4}""")
    private val allowedNames = setOf("engine.log", "launcher.log")
}
