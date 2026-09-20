package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationSnapshot
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.EngineLifecycle
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidConfigurationCommands(
    private val command: AndroidSuCommand = AndroidSuCommand(),
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
    ) { CommandRead.Listed(emptyList()) }

    suspend fun applyTracked(iccid: String, payload: String): ConfigurationCommandApplyResult =
        withContext(Dispatchers.IO) {
            try {
                configurationApplyResult(command.run(
                    ConfigurationControlCommand.applyLine(iccid), payload,
                    ConfigurationControlCommand.APPLY_TIMEOUT_SECONDS,
                ).toOutcome())
            } catch (_: IOException) {
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
        ) { CommandRead.Created }
    }

    private suspend fun invoke(
        commandLine: String,
        stdinText: String? = null,
        timeoutSeconds: Long = 20,
        mapper: (ConfigurationCommandOutcome) -> ConfigurationUiState? = ConfigurationRootMapper::map,
        parse: (String) -> CommandRead,
    ): CommandRead = withContext(Dispatchers.IO) {
        try {
            val result = if (stdinText == null) {
                command.run(commandLine)
            } else {
                command.run(commandLine, stdinText, timeoutSeconds)
            }
            val mapped = mapper(result.toOutcome())
            when {
                mapped != null -> CommandRead.Mapped(mapped)
                else -> parse(result.output)
            }
        } catch (error: ConfigurationFormatException) {
            CommandRead.Mapped(ConfigurationEvaluator.failure(error.message ?: "配置结果无法解析"))
        } catch (error: IOException) {
            CommandRead.Mapped(
                if (error.message.isPermissionDenied()) {
                    ConfigurationEvaluator.permissionDenied()
                } else {
                    mappedFailure(mapper)
                },
            )
        } catch (_: Exception) {
            CommandRead.Mapped(mappedFailure(mapper))
        }
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
    data class Mapped(val state: ConfigurationUiState) : CommandRead
    data object Created : CommandRead
}
