package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationUiState

internal data class ConfigurationCommandOutcome(
    val timedOut: Boolean,
    val overflowed: Boolean,
    val writeFailed: Boolean,
    val exitCode: Int,
    val output: String,
)

internal object ConfigurationRootMapper {
    fun mapIdentityRefresh(outcome: ConfigurationCommandOutcome): ConfigurationUiState? {
        if (outcome.output.isPermissionDenied()) return ConfigurationEvaluator.permissionDenied()
        if (outcome.timedOut || outcome.overflowed || outcome.writeFailed) {
            return ConfigurationEvaluator.failure("身份刷新未确认，请重新读取配置")
        }
        val result = ConfigurationCodec.result(outcome.output)
        return when (result?.code) {
            "APPLIED" -> if (outcome.exitCode == 0 && result.ok) null else ConfigurationEvaluator.failure("无法刷新 SIM 身份")
            "TARGET_RUNNING" -> ConfigurationEvaluator.failure("请先停止该 SIM 的引擎，再刷新身份")
            "IDENTITY_MISMATCH", "IDENTITY_STALE" -> ConfigurationEvaluator.failure("当前 SIM 身份未通过校验")
            "IDENTITY_UNREADABLE" -> ConfigurationEvaluator.failure("无法读取 SIM 身份")
            "USAGE", "USAGE_INVALID" -> ConfigurationEvaluator.unavailable()
            else -> ConfigurationEvaluator.failure("无法刷新 SIM 身份")
        }
    }

    fun map(outcome: ConfigurationCommandOutcome): ConfigurationUiState? {
        if (outcome.output.isPermissionDenied()) {
            return ConfigurationEvaluator.permissionDenied()
        }
        if (outcome.timedOut || outcome.overflowed || outcome.writeFailed) {
            return ConfigurationEvaluator.failure()
        }
        val failure = ConfigurationCodec.failure(outcome.output)
        if (outcome.exitCode == 0 && failure == null) {
            return null
        }
        return if (failure.isUnavailable() || outcome.output.isUnavailable()) {
            ConfigurationEvaluator.unavailable()
        } else {
            ConfigurationEvaluator.failure()
        }
    }

    fun mapCreate(outcome: ConfigurationCommandOutcome): ConfigurationUiState? {
        if (outcome.output.isPermissionDenied()) {
            return ConfigurationEvaluator.permissionDenied()
        }
        if (outcome.timedOut || outcome.overflowed || outcome.writeFailed) {
            return ConfigurationEvaluator.failure("无法创建配置")
        }
        return when (ConfigurationCodec.result(outcome.output)?.code) {
            "CREATED" -> null
            "TARGET_EXISTS" -> ConfigurationEvaluator.failure("配置已存在")
            "IDENTITY_MISMATCH" -> ConfigurationEvaluator.failure("SIM 与配置身份不一致")
            "IDENTITY_UNREADABLE" -> ConfigurationEvaluator.failure("无法读取 SIM 身份")
            "PROFILE_UNSUPPORTED" -> ConfigurationEvaluator.failure("当前 SIM 不支持创建配置")
            "CANDIDATE_INVALID" -> ConfigurationEvaluator.failure("无法创建配置")
            "USAGE", "USAGE_INVALID" -> ConfigurationEvaluator.unavailable()
            else -> if (outcome.output.isUnavailable()) {
                ConfigurationEvaluator.unavailable()
            } else {
                ConfigurationEvaluator.failure("无法创建配置")
            }
        }
    }
}

internal fun String?.isPermissionDenied(): Boolean =
    this.orEmpty().contains("EACCES", ignoreCase = true) ||
        this.orEmpty().contains("Permission denied", ignoreCase = true) ||
        this.orEmpty().contains("error=13", ignoreCase = true)

private fun String.isUnavailable(): Boolean =
    contains("not found", ignoreCase = true) ||
        contains("No such file", ignoreCase = true) ||
        contains("unknown command", ignoreCase = true) ||
        contains("USAGE", ignoreCase = true)

private fun ControlScriptFailure?.isUnavailable(): Boolean =
    this?.code == "USAGE" || this?.code == "USAGE_INVALID"
