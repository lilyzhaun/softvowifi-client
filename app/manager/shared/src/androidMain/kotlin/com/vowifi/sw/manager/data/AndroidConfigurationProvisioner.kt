package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationUiState

internal class AndroidConfigurationProvisioner(
    private val identityReader: AndroidSubscriptionIdentityReader,
    private val commands: AndroidConfigurationCommands,
) {
    suspend fun attempt(iccid: String): ProvisionAttempt {
        if (!ConfigurationControlCommand.digitsOnly(iccid)) {
            return ProvisionAttempt.Failed(ConfigurationEvaluator.failure(CREATE_FAILED))
        }
        val prepared = when (val candidate = prepareCandidate(identityReader.read(iccid))) {
            is PreparedCandidate.Ready -> candidate
            is PreparedCandidate.Failed -> return ProvisionAttempt.Failed(candidate.state)
        }
        return when (
            val created = commands.create(
                prepared.subscriptionId,
                prepared.slot,
                prepared.payload,
            )
        ) {
            is CommandRead.Created -> ProvisionAttempt.Created
            is CommandRead.Mapped -> when {
                ConfigurationEvaluator.isExistingTarget(created.state) -> ProvisionAttempt.AlreadyExists
                else -> ProvisionAttempt.Failed(created.state)
            }
            is CommandRead.Listed, is CommandRead.Snapshot ->
                ProvisionAttempt.Failed(ConfigurationEvaluator.failure(CREATE_FAILED))
        }
    }

    suspend fun attemptSelected(
        keys: List<String>,
        aborted: () -> Boolean,
    ): AutoProvisionBatch {
        var created = false
        var existed = false
        var failure: ConfigurationUiState? = null
        for (key in keys) {
            if (aborted()) break
            when (val attempt = attempt(key)) {
                ProvisionAttempt.Created -> created = true
                ProvisionAttempt.AlreadyExists -> existed = true
                is ProvisionAttempt.Failed -> if (failure == null) failure = attempt.state
            }
        }
        return AutoProvisionBatch(created = created, existed = existed, failure = failure)
    }
}

internal sealed interface ProvisionAttempt {
    data object Created : ProvisionAttempt
    data object AlreadyExists : ProvisionAttempt
    class Failed(val state: ConfigurationUiState) : ProvisionAttempt {
        override fun toString(): String = "Failed"
    }
}

internal class AutoProvisionBatch(
    val created: Boolean,
    val existed: Boolean,
    val failure: ConfigurationUiState?,
) {
    override fun toString(): String = "AutoProvisionBatch"
}

private fun prepareCandidate(read: SubscriptionIdentityRead): PreparedCandidate = when (read) {
    is SubscriptionIdentityRead.Complete -> when (val assembled = StandardCandidateAssembler.assemble(read.identity)) {
        is CandidateAssembly.Ready -> PreparedCandidate.Ready(
            assembled.payload,
            read.identity.subscriptionId,
            read.identity.slot,
        )
        is CandidateAssembly.Unsupported -> PreparedCandidate.Failed(
            ConfigurationEvaluator.failure(UNSUPPORTED_CREATE),
        )
        is CandidateAssembly.Invalid -> PreparedCandidate.Failed(
            ConfigurationEvaluator.failure(CREATE_FAILED),
        )
    }
    is SubscriptionIdentityRead.PermissionDenied -> PreparedCandidate.Failed(
        ConfigurationEvaluator.permissionDenied(),
    )
    is SubscriptionIdentityRead.Unreadable -> PreparedCandidate.Failed(
        ConfigurationEvaluator.failure(IDENTITY_UNREADABLE),
    )
    is SubscriptionIdentityRead.Unsupported,
    is SubscriptionIdentityRead.NotActive,
    -> PreparedCandidate.Failed(ConfigurationEvaluator.failure(UNSUPPORTED_CREATE))
}

private sealed interface PreparedCandidate {
    class Ready(
        val payload: String,
        val subscriptionId: Int,
        val slot: Int,
    ) : PreparedCandidate {
        override fun toString(): String = "Ready"
    }
    class Failed(val state: ConfigurationUiState) : PreparedCandidate {
        override fun toString(): String = "Failed"
    }
}

private const val UNSUPPORTED_CREATE = "当前 SIM 不支持创建配置"
private const val CREATE_FAILED = "无法创建配置"
private const val IDENTITY_UNREADABLE = "无法读取 SIM 身份"
