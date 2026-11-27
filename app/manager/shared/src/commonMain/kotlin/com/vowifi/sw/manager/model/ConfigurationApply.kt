package com.vowifi.sw.manager.model

enum class ConfigurationApplyAcceptance { Accepted, Busy, Unavailable }
enum class ConfigurationWriteResult { Applied, Unchanged }
enum class ConfigurationApplyEffect { Module, NextCall }

sealed interface ConfigurationApplyOutcome {
    data object Busy : ConfigurationApplyOutcome
    data object Failed : ConfigurationApplyOutcome
    data object Unconfirmed : ConfigurationApplyOutcome
    class Confirmed(
        val instance: ConfigurationInstance,
        val result: ConfigurationWriteResult = ConfigurationWriteResult.Applied,
        val effect: ConfigurationApplyEffect = ConfigurationApplyEffect.Module,
    ) : ConfigurationApplyOutcome {
        override fun toString(): String = "ConfigurationApplyOutcome.Confirmed"
    }
}

typealias ConfigurationSubmit = (
    ConfigurationInstanceId,
    ConfigurationOverrides,
    (ConfigurationApplyOutcome) -> Unit,
) -> ConfigurationApplyAcceptance
