package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationUiState

internal class AndroidConfigurationProvisioner(
    private val identityReader: AndroidSubscriptionIdentityReader,
    private val commands: AndroidConfigurationCommands,
    private val diagnostic: (DiagnosticEvent) -> Unit = { AndroidDiagnosticLog.emit(it) },
) {
    suspend fun refreshIdentity(iccid: String): ConfigurationUiState? {
        val prepared = when (val candidate = prepare(identityReader.read(iccid))) {
            is PreparedCandidate.Ready -> candidate
            is PreparedCandidate.Failed -> return candidate.state
        }
        return when (val result = commands.refreshIdentity(iccid, prepared.subscriptionId, prepared.slot, prepared.payload)) {
            is CommandRead.Listed -> null
            is CommandRead.Mapped -> result.state
            else -> ConfigurationEvaluator.failure("无法刷新 SIM 身份")
        }
    }

    suspend fun attempt(iccid: String): ProvisionAttempt {
        if (!ConfigurationControlCommand.digitsOnly(iccid)) {
            return ProvisionAttempt.Failed(ConfigurationEvaluator.failure(CREATE_FAILED))
        }
        val prepared = when (val candidate = prepare(identityReader.read(iccid))) {
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
                else -> ProvisionAttempt.Failed(created.state, created.writeUnconfirmed)
            }
            is CommandRead.Listed, is CommandRead.Snapshot ->
                ProvisionAttempt.Failed(ConfigurationEvaluator.failure(CREATE_FAILED))
        }
    }

    suspend fun rebuild(iccid: String): ProvisionAttempt {
        if (!ConfigurationControlCommand.digitsOnly(iccid)) return ProvisionAttempt.Failed(ConfigurationEvaluator.failure("无法重建配置"))
        val prepared = when (val candidate = prepare(identityReader.read(iccid))) {
            is PreparedCandidate.Ready -> candidate
            is PreparedCandidate.Failed -> return ProvisionAttempt.Failed(candidate.state)
        }
        return when (val result = commands.rebuild(iccid, prepared.subscriptionId, prepared.slot, prepared.payload)) {
            is CommandRead.Created -> ProvisionAttempt.Created
            is CommandRead.Listed -> ProvisionAttempt.AlreadyExists
            is CommandRead.Mapped -> ProvisionAttempt.Failed(result.state, result.writeUnconfirmed)
            is CommandRead.Snapshot -> ProvisionAttempt.Failed(ConfigurationEvaluator.failure("无法重建配置"), true)
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

    private fun prepare(read: SubscriptionIdentityRead): PreparedCandidate = prepareCandidate(read).also { prepared ->
        val failure = prepared as? PreparedCandidate.Failed
        diagnostic(DiagnosticEvent(DiagnosticComponent.CONFIGURATION,
            if (failure == null) DiagnosticLevel.INFO else DiagnosticLevel.WARN, DiagnosticName.OBSERVED,
            DiagnosticStage.IDENTITY, if (failure == null) DiagnosticResult.SUCCESS else DiagnosticResult.FAILURE,
            failure?.reason ?: DiagnosticReason.NONE,
            slot = (read as? SubscriptionIdentityRead.Complete)?.identity?.slot?.takeIf { it in 0..1 }))
    }
}

internal sealed interface ProvisionAttempt {
    data object Created : ProvisionAttempt
    data object AlreadyExists : ProvisionAttempt
    class Failed(val state: ConfigurationUiState, val unconfirmed: Boolean = false) : ProvisionAttempt {
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
            DiagnosticReason.PROFILE_UNSUPPORTED,
        )
        is CandidateAssembly.Invalid -> PreparedCandidate.Failed(
            ConfigurationEvaluator.failure(CREATE_FAILED),
            DiagnosticReason.INVALID_RESPONSE,
        )
    }
    is SubscriptionIdentityRead.PermissionDenied -> PreparedCandidate.Failed(
        ConfigurationEvaluator.permissionDenied(),
        DiagnosticReason.PERMISSION_DENIED,
    )
    is SubscriptionIdentityRead.Unreadable -> PreparedCandidate.Failed(
        ConfigurationEvaluator.failure(IDENTITY_UNREADABLE),
        DiagnosticReason.UNAVAILABLE,
    )
    is SubscriptionIdentityRead.Unsupported -> PreparedCandidate.Failed(ConfigurationEvaluator.failure(UNSUPPORTED_CREATE), DiagnosticReason.PROFILE_UNSUPPORTED)
    is SubscriptionIdentityRead.NotActive -> PreparedCandidate.Failed(ConfigurationEvaluator.failure(UNSUPPORTED_CREATE), DiagnosticReason.NOT_ACTIVE)
}

private sealed interface PreparedCandidate {
    class Ready(
        val payload: String,
        val subscriptionId: Int,
        val slot: Int,
    ) : PreparedCandidate {
        override fun toString(): String = "Ready"
    }
    class Failed(val state: ConfigurationUiState, val reason: DiagnosticReason) : PreparedCandidate {
        override fun toString(): String = "Failed"
    }
}

private const val UNSUPPORTED_CREATE = "当前 SIM 不支持创建配置"
private const val CREATE_FAILED = "无法创建配置"
private const val IDENTITY_UNREADABLE = "无法读取 SIM 身份"
