package com.vowifi.sw.manager.model

/** Fixed meanings only; neither command output nor arbitrary UI messages are completion signals. */
enum class ConfigurationProvisionOutcome { Created, Updated, Unchanged, Failed, Unconfirmed, Cancelled }

data class ConfigurationProvisionCompletion(
    val sequence: Long,
    val outcome: ConfigurationProvisionOutcome,
)

/** Pending includes real automatic work; completions contain explicit accepted actions only. */
data class ConfigurationProvisionState(
    val pending: Set<SimHandle> = emptySet(),
    val completions: Map<SimHandle, ConfigurationProvisionCompletion> = emptyMap(),
)
