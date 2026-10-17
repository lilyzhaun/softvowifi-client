package com.vowifi.sw.manager.model

/** One platform export result, emitted only after the destination stream has closed. */
data class LogExportCompletion(val sequence: Long, val outcome: LogExportOutcome)

enum class LogExportOutcome { Written, Failed, Cancelled }
