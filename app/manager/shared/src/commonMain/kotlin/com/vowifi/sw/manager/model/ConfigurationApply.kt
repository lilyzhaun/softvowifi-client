package com.vowifi.sw.manager.model

enum class ConfigurationApplyAcceptance { Accepted, Busy, Unavailable }
enum class ConfigurationWriteResult { Applied, Unchanged }

sealed interface ConfigurationApplyOutcome {
    data object Failed : ConfigurationApplyOutcome
    data object Unconfirmed : ConfigurationApplyOutcome
    class Confirmed(
        val instance: ConfigurationInstance,
        val result: ConfigurationWriteResult = ConfigurationWriteResult.Applied,
    ) : ConfigurationApplyOutcome {
        override fun toString(): String = "ConfigurationApplyOutcome.Confirmed"
    }
}

typealias ConfigurationSubmit = (
    ConfigurationInstanceId,
    ConfigurationOverrides,
    (ConfigurationApplyOutcome) -> Unit,
) -> ConfigurationApplyAcceptance
