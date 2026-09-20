package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EnvironmentDiagnosticsFormatException
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsSnapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object EnvironmentDiagnosticsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun snapshot(output: String): EnvironmentDiagnosticsSnapshot {
        val parsed = try {
            json.decodeFromString<EnvironmentDiagnosticsSnapshot>(output)
        } catch (error: SerializationException) {
            throw EnvironmentDiagnosticsFormatException(error.message ?: "诊断 JSON 缺少必要结构")
        }
        if (!parsed.ok) {
            throw EnvironmentDiagnosticsFormatException("诊断结果未成功")
        }
        return parsed
    }

    fun failure(output: String): ControlScriptFailure? =
        runCatching { json.decodeFromString<ControlScriptFailure>(output) }
            .getOrNull()
            ?.takeUnless(ControlScriptFailure::ok)
}
