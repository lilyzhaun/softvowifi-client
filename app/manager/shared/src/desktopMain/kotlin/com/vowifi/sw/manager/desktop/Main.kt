package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.FixtureMessageSource
import com.vowifi.sw.manager.data.MessageFixtureMode
import com.vowifi.sw.manager.ui.AppDestination
import com.vowifi.sw.manager.ui.AppearanceSettings
import com.vowifi.sw.manager.ui.InMemoryAppearanceSettingsSource
import com.vowifi.sw.manager.ui.messageProjectionOwner
import com.vowifi.sw.manager.ui.projectMessageConversations
import com.vowifi.sw.manager.ui.runtimeBlurSupported
import com.vowifi.sw.manager.ui.navigation.AppRoute

fun main(args: Array<String>) {
    val options = parseLaunchOptions(args)
    val subpage = initialMessageSubpage(options)
    val destination = if (subpage == null) initialDestination(options) else AppDestination.MESSAGES
    val messageMode = messageFixtureMode(options)
    val simMode = simFixtureMode(options)
    val configurationMode = configurationFixtureMode(options, simMode)
    val lastExportedLogs = arrayOfNulls<String>(1)
    val appearanceSource = InMemoryAppearanceSettingsSource(appearanceSeed(options))
    val blurSupported = blurSupportOption(
        options,
        renderApi = System.getProperty("skiko.renderApi"),
        shaderApiSupported = runtimeBlurSupported(),
    )
    application {
        val mode = options["state"] ?: "data"
        val width = options["width"]?.toIntOrNull()?.dp ?: 420.dp
        val height = options["height"]?.toIntOrNull()?.dp ?: 820.dp
        val fontScale = options["scale"]?.toFloatOrNull()?.coerceIn(1f, 2f) ?: 1f
        val requestedDensity = parseDensity(options["density"])
        val forceDark = when (options["theme"]) {
            "dark" -> true
            "light" -> false
            else -> null
        }
        val source = FixtureObserverLogSource(mode)
        val engineSource = FixtureEngineControlSource(options["engine"] ?: mode)
        val simControlSource = FixtureSimControlSource(simMode)
        val configurationSource = FixtureConfigurationSource(configurationMode)
        val diagnosticsSource = FixtureEnvironmentDiagnosticsSource(options["diagnostics"] ?: "ready")
        val operationalLogSource = FixtureOperationalLogSource(logsFixtureMode(options))
        val messageSource = when (options["communication-simulation"]) {
            "failed-send" -> CommunicationMessageSimulation(true)
            "accepted-send" -> CommunicationMessageSimulation(false)
            else -> FixtureMessageSource(messageMode)
        }
        val callSource = FixtureCallSource(callFixtureMode(options), simulateControls = options["call-simulation"] == "true")
        val sources = SoftVoWiFiSources(
            observer = source,
            engine = engineSource,
            simControl = simControlSource,
            configuration = configurationSource,
            diagnostics = diagnosticsSource,
            logs = operationalLogSource,
            messages = messageSource,
            calls = callSource,
            readEngineIntegrity = {
                com.vowifi.sw.manager.model.EngineIntegritySnapshot("d".repeat(12), listOf(
                    com.vowifi.sw.manager.model.RunningEngineIntegrity(101, com.vowifi.sw.manager.model.EngineExecutableMatch.SAME)))
            },
        )
        val initialSubpage = when (subpage) {
            null -> null
            DesktopMessageSubpage.NEW -> AppRoute.NewMessage
            DesktopMessageSubpage.THREAD -> AppRoute.MessageThread(
                projectMessageConversations(messageProjectionOwner(), messageSource.state.value).rows.first().handle,
            )
        }
        Window(
            onCloseRequest = {
                source.close()
                diagnosticsSource.close()
                operationalLogSource.close()
                messageSource.close()
                callSource.close()
                simControlSource.close()
                configurationSource.close()
                engineSource.close()
                exitApplication()
            },
            title = "SoftVoWiFi",
            state = WindowState(width = width, height = height),
        ) {
            val platformDensity = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(requestedDensity ?: platformDensity, fontScale)) {
                SoftVoWiFiApp(
                    sources = sources,
                    onExportLogs = { lastExportedLogs[0] = it },
                    exportBusy = false,
                    appVersion = "desktop-fixture",
                    initialDestination = destination,
                    forceDark = forceDark,
                    microphonePermissionGranted = options["microphone"] != "denied",
                    appearanceSource = appearanceSource,
                    blurSupported = blurSupported,
                    initialSubpage = initialSubpage,
                )
            }
        }
    }
}

internal fun parseLaunchOptions(args: Array<String>): Map<String, String> =
    args.mapNotNull { argument ->
        argument.removePrefix("--").split('=', limit = 2).takeIf { it.size == 2 }
    }.associate { it[0] to it[1] }

internal fun logsFixtureMode(options: Map<String, String>): String = options["logs"] ?: "ready"

internal fun initialDestination(options: Map<String, String>): AppDestination = when (val value = options["destination"]) {
    null -> AppDestination.HOME
    else -> AppDestination.fromAlias(value) ?: throw IllegalArgumentException("unknown destination: $value")
}

internal fun messageFixtureMode(options: Map<String, String>): MessageFixtureMode =
    MessageFixtureMode.fromWire(options["messages"] ?: "ready")

internal enum class DesktopMessageSubpage {
    THREAD,
    NEW,
}

internal fun appearanceSeed(options: Map<String, String>): AppearanceSettings = AppearanceSettings(
    floatingNavigation = booleanOption(options, "floating", defaultValue = true),
    blurRequested = booleanOption(options, "blur", defaultValue = true),
)

internal enum class DesktopRenderApi {
    SOFTWARE,
    CAPABLE,
}

internal data class DesktopBlurCapability(
    val renderApi: DesktopRenderApi,
    val shaderApiSupported: Boolean,
    val supported: Boolean,
)

internal fun desktopBlurCapability(renderApi: String?, shaderApiSupported: Boolean): DesktopBlurCapability {
    val typedRenderApi = if (renderApi.equals("SOFTWARE", ignoreCase = true)) {
        DesktopRenderApi.SOFTWARE
    } else {
        DesktopRenderApi.CAPABLE
    }
    return DesktopBlurCapability(
        renderApi = typedRenderApi,
        shaderApiSupported = shaderApiSupported,
        supported = typedRenderApi != DesktopRenderApi.SOFTWARE && shaderApiSupported,
    )
}

internal fun blurSupportOption(
    options: Map<String, String>,
    renderApi: String?,
    shaderApiSupported: Boolean,
): Boolean = booleanOption(options, "blur-supported", defaultValue = true) &&
    desktopBlurCapability(renderApi, shaderApiSupported).supported

internal fun initialMessageSubpage(options: Map<String, String>): DesktopMessageSubpage? =
    when (val value = options["subpage"]) {
        null -> null
        "thread" -> DesktopMessageSubpage.THREAD
        "new" -> DesktopMessageSubpage.NEW
        else -> throw IllegalArgumentException("unknown message subpage: $value")
    }

private fun booleanOption(options: Map<String, String>, key: String, defaultValue: Boolean): Boolean =
    when (val value = options[key]) {
        null -> defaultValue
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("invalid boolean option $key: $value")
    }

internal fun callFixtureMode(options: Map<String, String>): CallFixtureMode =
    CallFixtureMode.fromWire(options["calls"] ?: "idle")

internal fun simFixtureMode(options: Map<String, String>): String =
    (options["sim"] ?: "dual").also { mode ->
        require(mode in SIM_FIXTURE_MODES) { "unknown SIM fixture: $mode" }
    }

internal fun configurationFixtureMode(options: Map<String, String>): ConfigurationFixtureMode =
    configurationFixtureMode(options, simFixtureMode(options))

internal fun configurationFixtureMode(
    options: Map<String, String>,
    simMode: String,
): ConfigurationFixtureMode {
    options["configuration"]?.let { return ConfigurationFixtureMode.fromWire(it) }
    return when (simMode) {
        "dual" -> ConfigurationFixtureMode.SIM_2
        "provision" -> ConfigurationFixtureMode.READY
        "matched-running", "removed-running", "removed-stopped" -> ConfigurationFixtureMode.SIM_1
        "empty" -> ConfigurationFixtureMode.EMPTY
        "permission", "failure", "loading" -> ConfigurationFixtureMode.READY
        else -> error("unsupported SIM fixture: $simMode")
    }
}

internal fun parseDensity(value: String?): Float? = value
    ?.toFloatOrNull()
    ?.takeIf { it.isFinite() && it > 0f }
    ?.coerceIn(1f, 4f)

private val SIM_FIXTURE_MODES = setOf(
    "dual",
    "provision",
    "matched-running",
    "removed-running",
    "removed-stopped",
    "empty",
    "permission",
    "failure",
    "loading",
)
