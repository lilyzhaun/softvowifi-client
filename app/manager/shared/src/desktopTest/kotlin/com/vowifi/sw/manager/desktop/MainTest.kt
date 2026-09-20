package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.MessageFixtureMode
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallOrigin
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.ui.AppDestination
import com.vowifi.sw.manager.ui.AppearanceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainTest {
    @Test
    fun parsesAndClampsPositiveFiniteDensity() {
        assertEquals(2.75f, parseDensity("2.75"))
        assertEquals(1f, parseDensity("0.5"))
        assertEquals(4f, parseDensity("5"))
    }

    @Test
    fun invalidDensityFallsBackToPlatformDefault() {
        listOf(null, "invalid", "0", "-1", "NaN", "Infinity").forEach { value ->
            assertNull(parseDensity(value))
        }
    }

    @Test
    fun logsOptionSelectsOperationalLogFixtureAndDefaultsToReady() {
        assertEquals("ready", logsFixtureMode(parseLaunchOptions(emptyArray())))
        assertEquals("warning", logsFixtureMode(parseLaunchOptions(arrayOf("--logs=warning"))))
        assertIs<OperationalLogUiState.Ready>(
            FixtureOperationalLogSource(logsFixtureMode(emptyMap())).state.value,
        )
    }

    @Test
    fun destinationOptionMapsEverySupportedAlias() {
        assertEquals(AppDestination.HOME, initialDestination(emptyMap()))
        listOf("status", "home").forEach { alias ->
            assertEquals(AppDestination.HOME, initialDestination(mapOf("destination" to alias)))
        }
        listOf("configuration", "diagnostics", "settings").forEach { alias ->
            assertEquals(AppDestination.SETTINGS, initialDestination(mapOf("destination" to alias)))
        }
        assertEquals(AppDestination.CALLS, initialDestination(mapOf("destination" to "calls")))
        assertEquals(AppDestination.MESSAGES, initialDestination(mapOf("destination" to "messages")))
        assertFailsWith<IllegalArgumentException> {
            initialDestination(mapOf("destination" to "unknown"))
        }
    }

    @Test
    fun messageFixtureOptionIsTypedAndClearlyNonProduction() {
        val wires = listOf("loading", "empty", "ready", "read-only", "error", "permission", "unavailable")

        assertEquals(MessageFixtureMode.READY, messageFixtureMode(emptyMap()))
        assertEquals(
            MessageFixtureMode.entries,
            wires.map { messageFixtureMode(mapOf("messages" to it)) },
        )
        val ready = assertIs<MessageUiState.Ready>(
            com.vowifi.sw.manager.data.FixtureMessageSource(messageFixtureMode(emptyMap())).state.value,
        )
        assertEquals(MessageOrigin.DESKTOP_FIXTURE, ready.origin)
        assertFailsWith<IllegalArgumentException> {
            messageFixtureMode(mapOf("messages" to "unknown"))
        }
    }

    @Test
    fun appearanceAndSubpageOptionsAreTypedAndDefaultToEnabledRoot() {
        assertEquals(AppearanceSettings.Default, appearanceSeed(emptyMap()))
        assertEquals(true, blurSupportOption(emptyMap(), renderApi = "OPENGL", shaderApiSupported = true))
        assertNull(initialMessageSubpage(emptyMap()))

        val options = parseLaunchOptions(
            arrayOf("--floating=false", "--blur=false", "--blur-supported=false", "--subpage=thread"),
        )
        assertEquals(AppearanceSettings(false, false), appearanceSeed(options))
        assertEquals(false, blurSupportOption(options, renderApi = "OPENGL", shaderApiSupported = true))
        assertEquals(DesktopMessageSubpage.THREAD, initialMessageSubpage(options))
        assertEquals(DesktopMessageSubpage.NEW, initialMessageSubpage(mapOf("subpage" to "new")))
        assertFailsWith<IllegalArgumentException> { initialMessageSubpage(mapOf("subpage" to "unknown")) }
    }

    @Test
    fun softwareRendererCannotBeForcedPastDesktopBlurCapability() {
        val software = desktopBlurCapability("SOFTWARE", shaderApiSupported = true)
        val hardware = desktopBlurCapability("OPENGL", shaderApiSupported = true)

        assertEquals(DesktopRenderApi.SOFTWARE, software.renderApi)
        assertFalse(software.supported)
        assertTrue(hardware.supported)
        assertFalse(
            blurSupportOption(
                mapOf("blur-supported" to "true"),
                renderApi = "SOFTWARE",
                shaderApiSupported = true,
            ),
        )
        assertFalse(
            blurSupportOption(
                mapOf("blur-supported" to "false"),
                renderApi = "OPENGL",
                shaderApiSupported = true,
            ),
        )
        assertTrue(
            blurSupportOption(
                mapOf("blur-supported" to "true"),
                renderApi = "OPENGL",
                shaderApiSupported = true,
            ),
        )
    }

    @Test
    fun callFixtureOptionIsTypedAndDefaultsToIdle() {
        val wires = listOf(
            "idle",
            "recents",
            "ringing",
            "dialing",
            "connecting",
            "incall",
            "audio-failed",
            "loading",
            "permission",
            "unavailable",
            "failure",
        )

        assertEquals(CallFixtureMode.IDLE, callFixtureMode(emptyMap()))
        assertEquals(CallFixtureMode.entries, wires.map { callFixtureMode(mapOf("calls" to it)) })
        assertFailsWith<IllegalArgumentException> {
            callFixtureMode(mapOf("calls" to "unknown"))
        }
    }

    @Test
    fun recentsLaunchOptionProvidesIdleNonProductionHistory() {
        val mode = callFixtureMode(parseLaunchOptions(arrayOf("--calls=recents")))
        assertEquals(CallFixtureMode.RECENTS, mode)
        val source = FixtureCallSource(mode)
        val state = assertIs<CallUiState.Ready>(source.state.value)
        assertEquals(CallPhase.Idle, state.phase)
        assertEquals(CallOrigin.DESKTOP_FIXTURE, state.origin)
        assertFalse(state.busy)
        val history = assertIs<CallHistoryState.Ready>(source.history.value)
        assertEquals(listOf(CallOutcome.COMPLETED, CallOutcome.MISSED), history.records.map { it.outcome })
    }

    @Test
    fun simFixtureOptionIsTypedAndDefaultsToDual() {
        val wires = listOf(
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

        assertEquals("dual", simFixtureMode(emptyMap()))
        assertEquals(wires, wires.map { simFixtureMode(mapOf("sim" to it)) })
        assertIs<SimControlUiState.Ready>(FixtureSimControlSource(simFixtureMode(emptyMap())).state.value)
        assertFailsWith<IllegalArgumentException> {
            simFixtureMode(mapOf("sim" to "unknown"))
        }
    }

    @Test
    fun configurationFixtureModeDerivesFromSimUnlessExplicitlyOverridden() {
        assertEquals(ConfigurationFixtureMode.SIM_2, configurationFixtureMode(emptyMap()))
        assertEquals(ConfigurationFixtureMode.READY, configurationFixtureMode(mapOf("sim" to "provision")))
        assertEquals(listOf("sim-2"), configurationIds(emptyMap()))
        listOf("matched-running", "removed-running", "removed-stopped").forEach { simMode ->
            assertEquals(
                ConfigurationFixtureMode.SIM_1,
                configurationFixtureMode(mapOf("sim" to simMode)),
            )
        }
        assertEquals(
            ConfigurationFixtureMode.EMPTY,
            configurationFixtureMode(mapOf("sim" to "empty")),
        )
        assertEquals(emptyList(), configurationIds(mapOf("sim" to "empty")))
        listOf("permission", "failure", "loading").forEach { simMode ->
            assertEquals(
                ConfigurationFixtureMode.READY,
                configurationFixtureMode(mapOf("sim" to simMode)),
            )
        }
        assertEquals(
            ConfigurationFixtureMode.SIM_1,
            configurationFixtureMode(mapOf("sim" to "dual", "configuration" to "sim-1")),
        )
        assertEquals(
            listOf("sim-1"),
            configurationIds(mapOf("sim" to "dual", "configuration" to "sim-1")),
        )
    }

    private fun configurationIds(options: Map<String, String>): List<String> =
        when (val state = FixtureConfigurationSource(configurationFixtureMode(options)).state.value) {
            is com.vowifi.sw.manager.model.ConfigurationUiState.Ready ->
                state.instances.map { it.instanceId.value }
            else -> emptyList()
        }
}
