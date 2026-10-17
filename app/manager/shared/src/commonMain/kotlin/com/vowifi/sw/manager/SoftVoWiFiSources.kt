package com.vowifi.sw.manager

import com.vowifi.sw.manager.data.CallSource
import com.vowifi.sw.manager.data.ConfigurationSource
import com.vowifi.sw.manager.data.EngineControlSource
import com.vowifi.sw.manager.data.EnvironmentDiagnosticsSource
import com.vowifi.sw.manager.data.MessageSource
import com.vowifi.sw.manager.data.ObserverLogSource
import com.vowifi.sw.manager.data.OperationalLogSource
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.data.SimControlSource
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.ui.RefreshTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class SoftVoWiFiSources(
    val observer: ObserverLogSource,
    val engine: EngineControlSource,
    val simControl: SimControlSource,
    val configuration: ConfigurationSource,
    val diagnostics: EnvironmentDiagnosticsSource,
    val logs: OperationalLogSource,
    val messages: MessageSource,
    val calls: CallSource,
    val callNotificationSettings: com.vowifi.sw.manager.data.CallNotificationSettingsSource =
        com.vowifi.sw.manager.data.UnsupportedCallNotificationSettingsSource,
)

internal fun SoftVoWiFiSources.refresh(targets: Set<RefreshTarget>) {
    targets.forEach { target ->
        when (target) {
            RefreshTarget.OBSERVER -> observer.refresh()
            RefreshTarget.ENGINE -> engine.refresh()
            RefreshTarget.CONFIGURATION -> configuration.refresh()
            RefreshTarget.MESSAGES -> messages.refresh()
            RefreshTarget.ENVIRONMENT_DIAGNOSTICS -> diagnostics.refresh()
            RefreshTarget.OPERATIONAL_LOGS -> logs.refresh()
            RefreshTarget.SIM_CONTROL -> simControl.refresh()
            RefreshTarget.CALLS -> calls.refresh()
        }
    }
}

/** A user refresh joins real reads; normal configuration reading never refreshes identities. */
internal suspend fun SoftVoWiFiSources.refreshHomeAndAwait(): RefreshResult = coroutineScope {
    val observerRead = async { observer.refreshAndAwait() }
    val configurationRead = async { configuration.refreshAndAwait() }
    val diagnosticsRead = async { diagnostics.refreshAndAwait() }
    val engineResult = engine.refreshAndAwait()
    // SIM inventory publishes against the engine state which has just completed its read.
    val simResult = simControl.refreshAndAwait()
    listOf(observerRead.await(), engineResult, simResult, configurationRead.await(), diagnosticsRead.await())
        .firstOrNull { it != RefreshResult.Completed } ?: RefreshResult.Completed
}

internal fun SoftVoWiFiSources.startSim(handle: SimHandle) {
    simControl.start(handle)
}

internal fun SoftVoWiFiSources.stopSim(handle: SimHandle) {
    simControl.stop(handle)
}

internal fun SoftVoWiFiSources.provisionSim(handle: SimHandle) {
    configuration.provision(handle)
}
