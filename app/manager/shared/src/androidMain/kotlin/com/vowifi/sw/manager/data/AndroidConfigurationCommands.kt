package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationSnapshot
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.EngineLifecycle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal class AndroidConfigurationCommands(
    private val command: AndroidSuCommand = AndroidSuCommand(),
    private val diagnostic: (DiagnosticEvent) -> Unit = { AndroidDiagnosticLog.emit(it) },
) {
    suspend fun list(): CommandRead = invoke(ConfigurationControlCommand.listLine()) { output ->
        CommandRead.Listed(
            EngineControlScriptCodec.instances(output).map { instance ->
                ListedIccid(
                    iccid = instance.iccid,
                    missing = instance.lifecycle == EngineLifecycle.CONFIG_MISSING,
                )
            },
        )
    }

    suspend fun show(iccid: String): CommandRead {
        val line = runCatching { ConfigurationControlCommand.showLine(iccid) }
            .getOrElse { return CommandRead.Mapped(ConfigurationEvaluator.failure()) }
        return invoke(line) { output ->
            CommandRead.Snapshot(ConfigurationCodec.show(output))
        }
    }

    suspend fun apply(iccid: String, payload: String): CommandRead = invoke(
        ConfigurationControlCommand.applyLine(iccid),
        payload,
        ConfigurationControlCommand.APPLY_TIMEOUT_SECONDS,
        writeOperation = true,
        stage = DiagnosticStage.CONFIG_APPLY,
    ) { CommandRead.Listed(emptyList()) }

    suspend fun applyTracked(iccid: String, payload: String): ConfigurationCommandApplyResult =
        withContext(Dispatchers.IO) {
            try {
                val observed = runInterruptible { command.run(
                    ConfigurationControlCommand.applyLine(iccid), payload,
                    ConfigurationControlCommand.APPLY_TIMEOUT_SECONDS,
                ) }.toOutcome()
                val result = configurationApplyResult(observed)
                observe(observed, DiagnosticStage.CONFIG_APPLY, null, null)
                result
            } catch (_: IOException) {
                failed(DiagnosticStage.CONFIG_APPLY, DiagnosticReason.IO_FAILED)
                ConfigurationCommandApplyResult.Unconfirmed
            }
        }

    suspend fun create(
        subscriptionId: Int,
        slot: Int,
        payload: String,
    ): CommandRead {
        val line = runCatching { ConfigurationControlCommand.createLine(subscriptionId, slot) }
            .getOrElse { return CommandRead.Mapped(ConfigurationEvaluator.failure("无法创建配置")) }
        return invoke(
            commandLine = line,
            stdinText = payload,
            timeoutSeconds = ConfigurationControlCommand.CREATE_TIMEOUT_SECONDS,
            mapper = ConfigurationRootMapper::mapCreate,
            writeOperation = true,
            stage = DiagnosticStage.CONFIG_CREATE,
            slot = slot,
        ) { CommandRead.Created }
    }

    suspend fun refreshIdentity(iccid: String, subscriptionId: Int, slot: Int, payload: String): CommandRead = invoke(
        commandLine = ConfigurationControlCommand.refreshIdentityLine(iccid, subscriptionId, slot),
        stdinText = payload,
        timeoutSeconds = ConfigurationControlCommand.CREATE_TIMEOUT_SECONDS,
        mapper = ConfigurationRootMapper::mapIdentityRefresh,
        stage = DiagnosticStage.CONFIG_REFRESH,
        slot = slot,
    ) { CommandRead.Listed(emptyList()) }

    suspend fun rebuild(iccid: String, subscriptionId: Int, slot: Int, payload: String): CommandRead = invoke(
        commandLine = ConfigurationControlCommand.rebuildLine(iccid, subscriptionId, slot),
        stdinText = payload,
        timeoutSeconds = ConfigurationControlCommand.CREATE_TIMEOUT_SECONDS,
        mapper = ConfigurationRootMapper::mapRebuild,
        writeOperation = true,
        stage = DiagnosticStage.CONFIG_CREATE,
        slot = slot,
    ) { output ->
        if (ConfigurationCodec.result(output)?.code == "APPLIED") CommandRead.Created else CommandRead.Listed(emptyList())
    }

    private suspend fun invoke(
        commandLine: String,
        stdinText: String? = null,
        timeoutSeconds: Long = 20,
        mapper: (ConfigurationCommandOutcome) -> ConfigurationUiState? = ConfigurationRootMapper::map,
        writeOperation: Boolean = false,
        stage: DiagnosticStage = DiagnosticStage.CONFIG_MATCH,
        slot: Int? = null,
        parse: (String) -> CommandRead,
    ): CommandRead = withContext(Dispatchers.IO) {
        diagnostic(DiagnosticEvent(DiagnosticComponent.CONFIGURATION, DiagnosticLevel.INFO, DiagnosticName.OBSERVED,
            stage, DiagnosticResult.STARTED, slot = slot?.takeIf { it in 0..1 }))
        try {
            val result = runInterruptible {
                if (stdinText == null) command.run(commandLine)
                else command.run(commandLine, stdinText, timeoutSeconds)
            }
            val mapped = mapper(result.toOutcome())
            val read = when {
                mapped != null -> CommandRead.Mapped(mapped,
                    writeUnconfirmed = writeOperation && (result.timedOut || result.overflowed || result.writeFailed ||
                        (!result.output.isPermissionDenied() && ConfigurationCodec.failure(result.output) == null)))
                else -> {
                    val parsed = parse(result.output)
                    if (parsed == CommandRead.Created &&
                        (result.exitCode != 0 || ConfigurationCodec.result(result.output)?.ok != true)) {
                        CommandRead.Mapped(ConfigurationEvaluator.failure("无法创建配置"), writeUnconfirmed = true)
                    } else parsed
                }
            }
            observe(result.toOutcome(), stage, slot, read)
            read
        } catch (error: CancellationException) {
            diagnostic(DiagnosticEvent(DiagnosticComponent.CONFIGURATION, DiagnosticLevel.INFO, DiagnosticName.OBSERVED,
                stage, DiagnosticResult.CANCELLED, DiagnosticReason.CANCEL_UNKNOWN, slot?.takeIf { it in 0..1 }))
            throw error
        } catch (error: ConfigurationFormatException) {
            failed(stage, DiagnosticReason.INVALID_RESPONSE, slot)
            CommandRead.Mapped(ConfigurationEvaluator.failure(error.message ?: "配置结果无法解析"), writeUnconfirmed = writeOperation)
        } catch (error: IOException) {
            failed(stage, if (error.message.isPermissionDenied()) DiagnosticReason.ROOT_DENIED else DiagnosticReason.IO_FAILED, slot)
            CommandRead.Mapped(
                if (error.message.isPermissionDenied()) {
                    ConfigurationEvaluator.permissionDenied()
                } else {
                    mappedFailure(mapper)
                },
                writeUnconfirmed = writeOperation && !error.message.isPermissionDenied(),
            )
        } catch (_: Exception) {
            failed(stage, DiagnosticReason.UNKNOWN, slot)
            CommandRead.Mapped(mappedFailure(mapper), writeUnconfirmed = writeOperation)
        }
    }

    private fun failed(stage: DiagnosticStage, reason: DiagnosticReason, slot: Int? = null) {
        diagnostic(DiagnosticEvent(DiagnosticComponent.CONFIGURATION, DiagnosticLevel.WARN, DiagnosticName.REJECTED,
            stage, DiagnosticResult.FAILURE, reason, slot?.takeIf { it in 0..1 }))
    }

    private fun observe(outcome: ConfigurationCommandOutcome, stage: DiagnosticStage, slot: Int?, read: CommandRead?) {
        val envelope = ConfigurationCodec.result(outcome.output)
        val code = envelope?.code
        val reason = when {
            outcome.output.isPermissionDenied() -> DiagnosticReason.ROOT_DENIED
            outcome.timedOut -> DiagnosticReason.TIMEOUT
            outcome.overflowed -> DiagnosticReason.INVALID_RESPONSE
            outcome.writeFailed -> DiagnosticReason.IO_FAILED
            code == "IDENTITY_MISMATCH" || code == "IDENTITY_STALE" -> DiagnosticReason.IDENTITY_MISMATCH
            code == "IDENTITY_UNREADABLE" -> DiagnosticReason.UNAVAILABLE
            code == "TARGET_RUNNING" -> DiagnosticReason.TARGET_RUNNING
            code == "TARGET_EXISTS" -> DiagnosticReason.TARGET_EXISTS
            code == "PROFILE_UNSUPPORTED" -> DiagnosticReason.PROFILE_UNSUPPORTED
            code == "CANDIDATE_INVALID" -> DiagnosticReason.INVALID_RESPONSE
            code == "USAGE" || code == "USAGE_INVALID" -> DiagnosticReason.UNAVAILABLE
            outcome.exitCode != 0 -> DiagnosticReason.IO_FAILED
            envelope?.ok == false -> DiagnosticReason.UNKNOWN
            read is CommandRead.Mapped -> if (read.writeUnconfirmed) DiagnosticReason.UNCONFIRMED else DiagnosticReason.UNKNOWN
            read == null && configurationApplyResult(outcome) == ConfigurationCommandApplyResult.Unconfirmed -> DiagnosticReason.UNCONFIRMED
            else -> DiagnosticReason.NONE
        }
        if (reason == DiagnosticReason.IDENTITY_MISMATCH || code == "IDENTITY_UNREADABLE") {
            failed(DiagnosticStage.STRICT_VERIFY, reason, slot)
        }
        if (reason != DiagnosticReason.NONE) { failed(stage, reason, slot); return }
        if ((stage == DiagnosticStage.CONFIG_CREATE && code == "CREATED" && read == CommandRead.Created) ||
            (stage == DiagnosticStage.CONFIG_REFRESH && code == "APPLIED" && read is CommandRead.Listed)) {
            diagnostic(DiagnosticEvent(DiagnosticComponent.CONFIGURATION, DiagnosticLevel.INFO, DiagnosticName.OBSERVED,
                DiagnosticStage.STRICT_VERIFY, DiagnosticResult.SUCCESS, slot = slot?.takeIf { it in 0..1 }))
        }
        diagnostic(DiagnosticEvent(DiagnosticComponent.CONFIGURATION, DiagnosticLevel.INFO, DiagnosticName.OBSERVED,
            stage, DiagnosticResult.SUCCESS, slot = slot?.takeIf { it in 0..1 }, count = when (read) {
                is CommandRead.Listed -> read.iccids.size.coerceAtMost(1_000_000_000)
                is CommandRead.Snapshot, CommandRead.Created -> 1
                else -> null
            }))
    }
}

private fun mappedFailure(
    mapper: (ConfigurationCommandOutcome) -> ConfigurationUiState?,
): ConfigurationUiState = mapper(
    ConfigurationCommandOutcome(
        timedOut = false,
        overflowed = false,
        writeFailed = true,
        exitCode = -1,
        output = "",
    ),
) ?: ConfigurationEvaluator.failure()

internal data class ListedIccid(
    val iccid: String,
    val missing: Boolean,
)

internal sealed interface CommandRead {
    data class Listed(val iccids: List<ListedIccid>) : CommandRead
    data class Snapshot(val snapshot: ConfigurationSnapshot) : CommandRead
    data class Mapped(val state: ConfigurationUiState, val writeUnconfirmed: Boolean = false) : CommandRead
    data object Created : CommandRead
}
