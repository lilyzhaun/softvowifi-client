package com.vowifi.sw.manager.data

internal fun interface LogCommand {
    suspend fun run(command: String): AndroidSuResult
}
