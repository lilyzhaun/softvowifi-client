package com.vowifi.sw.manager.data

internal enum class ConfigurationCommandApplyResult { Applied, Unchanged, Failed, Unconfirmed }

internal fun configurationApplyResult(outcome: ConfigurationCommandOutcome): ConfigurationCommandApplyResult {
    if (outcome.timedOut || outcome.overflowed || outcome.writeFailed) return ConfigurationCommandApplyResult.Unconfirmed
    val response = ConfigurationCodec.result(outcome.output) ?: return ConfigurationCommandApplyResult.Unconfirmed
    return when {
        response.ok && response.code == "APPLIED" && outcome.exitCode == 0 -> ConfigurationCommandApplyResult.Applied
        response.ok && response.code == "UNCHANGED" && outcome.exitCode == 0 -> ConfigurationCommandApplyResult.Unchanged
        !response.ok && response.code != "ROLLBACK_FAILED" -> ConfigurationCommandApplyResult.Failed
        else -> ConfigurationCommandApplyResult.Unconfirmed
    }
}
