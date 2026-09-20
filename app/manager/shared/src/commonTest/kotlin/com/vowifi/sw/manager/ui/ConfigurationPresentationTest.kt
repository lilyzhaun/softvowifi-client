package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigurationPresentationTest {
    @Test
    fun readyFixtureExposesSaveActionAndDesktopDisclosure() {
        val presentation = configurationPresentation(
            ConfigurationUiState.Ready(listOf(configuration("fixture-a")), isFixture = true),
        )

        assertTrue(presentation.showEditor)
        assertTrue(presentation.showSaveAction)
        assertEquals(FIXTURE_DISCLOSURE_TEXT, presentation.disclosure)
        assertNull(presentation.statePanel)
    }

    @Test
    fun legacyNonReadyStatesUseBoundedPanels() {
        val states = listOf(
            ConfigurationUiState.Loading,
            ConfigurationUiState.Empty,
            ConfigurationUiState.Failure("raw failure"),
            ConfigurationUiState.PermissionDenied("raw permission"),
            ConfigurationUiState.Unavailable("raw unavailable"),
        )

        assertEquals(
            listOf(
                ConfigurationPanelKind.LOADING,
                ConfigurationPanelKind.EMPTY,
                ConfigurationPanelKind.FAILURE,
                ConfigurationPanelKind.PERMISSION_DENIED,
                ConfigurationPanelKind.UNAVAILABLE,
            ),
            states.map { configurationPresentation(it).panelKind },
        )
        val panels = states.mapNotNull { configurationPresentation(it).statePanel }
        assertTrue(panels.all { it.title.isNotBlank() && it.description.length <= 32 })
        assertTrue(panels.none { "raw" in it.description })
    }

    @Test
    fun readySimRowsRetainEveryNonReadyConfigurationPanel() {
        val configurationStates = listOf(
            ConfigurationUiState.Loading to ConfigurationPanelKind.LOADING,
            ConfigurationUiState.Failure("raw failure") to ConfigurationPanelKind.FAILURE,
            ConfigurationUiState.PermissionDenied("raw permission") to ConfigurationPanelKind.PERMISSION_DENIED,
            ConfigurationUiState.Unavailable("raw unavailable") to ConfigurationPanelKind.UNAVAILABLE,
        )
        val simState = SimControlUiState.Ready(listOf(unconfiguredSim()))

        configurationStates.forEach { (configurationState, expectedKind) ->
            val presentation = configurationPresentation(configurationState, simState)

            assertEquals(expectedKind, presentation.panelKind)
            assertNotNull(presentation.statePanel)
            assertEquals(ConfigurationRowKind.ACTIVE_UNCONFIGURED, presentation.rows.single().kind)
        }
    }

    @Test
    fun emptyConfigurationWithActiveSimShowsRowsWithoutEmptyPanel() {
        val presentation = configurationPresentation(
            ConfigurationUiState.Empty,
            SimControlUiState.Ready(listOf(unconfiguredSim())),
        )

        assertNull(presentation.panelKind)
        assertNull(presentation.statePanel)
        assertEquals(ConfigurationRowKind.ACTIVE_UNCONFIGURED, presentation.rows.single().kind)
    }

    @Test
    fun readyEmptyInventoryHidesRetainedConfigurations() {
        val states = listOf(
            ConfigurationUiState.Empty,
            ConfigurationUiState.Ready(emptyList()),
            ConfigurationUiState.Ready(listOf(configuration("retained"))),
        )

        states.forEach { configurationState ->
            val presentation = configurationPresentation(
                configurationState,
                SimControlUiState.Ready(emptyList()),
            )

            assertEquals(ConfigurationPanelKind.EMPTY, presentation.panelKind)
            assertNotNull(presentation.statePanel)
            assertTrue(presentation.rows.isEmpty())
        }
    }

    @Test
    fun readyMessageUsesBoundedWarningFallbackWithoutHidingRows() {
        val hostile = "raw subId=7 8986000000000000336 /data/adb/vowifi"
        val presentation = configurationPresentation(
            ConfigurationUiState.Ready(listOf(configuration("sim-1"))),
            SimControlUiState.Ready(
                instances = listOf(matchedSim("sim-1")),
                message = hostile,
            ),
        )

        val panel = assertNotNull(presentation.supplementalStatePanel)
        val publicText = listOf(panel.title, panel.description) + presentation.rows.flatMap { row ->
            listOfNotNull(row.label, row.status, row.instance?.displayLabel)
        }
        assertEquals(StatePanelTone.WARNING, panel.tone)
        assertEquals("SIM 状态可能不完整", panel.title)
        assertEquals("已显示可用的 SIM 状态。", panel.description)
        assertEquals(ConfigurationRowKind.CONFIGURED, presentation.rows.single().kind)
        assertFalse(publicText.any { hostile in it || "/data/" in it })
        assertFalse(publicText.any { Regex("""\d{15,}""").containsMatchIn(it) })
    }

    @Test
    fun unavailableInventoryPreservesLegacyEditorsAndAddsSimPanel() {
        val configuration = configuration("sim-1")
        val inventoryStates = listOf(
            SimControlUiState.Loading,
            SimControlUiState.PermissionDenied("raw permission /data/private"),
            SimControlUiState.Failure("raw failure 123456789012345"),
        )

        inventoryStates.forEach { simState ->
            val presentation = configurationPresentation(
                ConfigurationUiState.Ready(listOf(configuration)),
                simState,
            )

            assertEquals(configuration.instanceId, presentation.rows.single().instance?.instanceId)
            assertTrue(presentation.rows.single().canApply)
            assertNotNull(presentation.supplementalStatePanel)
        }
    }

    @Test
    fun twoMatchedSimsKeepSlotOrderAndOnlyTheSavingRowChanges() {
        val sim1 = configuration("sim-1")
        val sim2 = configuration("sim-2")
        val inventory = SimControlUiState.Ready(
            listOf(
                sim("sim-2", SimPresence.ACTIVE_MATCHED, slotIndex = 1),
                sim("sim-1", SimPresence.ACTIVE_MATCHED, slotIndex = 0),
            ),
        )

        val idle = configurationPresentation(
            ConfigurationUiState.Ready(listOf(sim1, sim2)),
            inventory,
        )
        val applyingSim1 = configurationPresentation(
            ConfigurationUiState.Ready(listOf(sim1, sim2), applyingInstanceId = sim1.instanceId),
            inventory,
        )

        assertEquals(listOf("sim-1", "sim-2"), idle.rows.map { it.key })
        assertEquals(List(2) { ConfigurationRowKind.CONFIGURED }, idle.rows.map { it.kind })
        assertTrue(idle.rows.all { it.canApply })
        assertEquals(listOf("sim-1", "sim-2"), applyingSim1.rows.map { it.key })
        assertFalse(applyingSim1.rows.first().canApply)
        assertTrue(applyingSim1.rows.last().canApply)
    }

    @Test
    fun matchedAndUnconfiguredSimsExposeEditorAndProvisionRows() {
        val presentation = configurationPresentation(
            ConfigurationUiState.Ready(listOf(configuration("sim-1"))),
            SimControlUiState.Ready(
                listOf(
                    sim("sim-1", SimPresence.ACTIVE_MATCHED, slotIndex = 0),
                    sim("sim-2", SimPresence.ACTIVE_UNCONFIGURED, slotIndex = 1, canProvision = true),
                ),
            ),
        )

        assertEquals(listOf("sim-1", "sim-2"), presentation.rows.map { it.key })
        assertEquals(
            listOf(ConfigurationRowKind.CONFIGURED, ConfigurationRowKind.ACTIVE_UNCONFIGURED),
            presentation.rows.map { it.kind },
        )
        assertTrue(presentation.rows.first().canApply)
        assertTrue(presentation.rows.last().canProvision)
    }

    private fun configuration(id: String) = ConfigurationInstance(
        instanceId = ConfigurationInstanceId(id),
        displayLabel = "实例 •••• 1001",
        lifecycle = ConfigurationLifecycle.STOPPED,
        transport = ConfigurationTransport.AUTO,
        smsc = "",
        epdgFqdn = "",
        imsRealm = "",
        dns = "",
        resolvedEpdgFqdn = "epdg.example.invalid",
        resolvedImsRealm = "ims.example.invalid",
        mcc = "001",
        mnc = "01",
    )

    private fun unconfiguredSim() = sim("sim-new", SimPresence.ACTIVE_UNCONFIGURED, canApply = false)

    private fun matchedSim(id: String) = sim(id, SimPresence.ACTIVE_MATCHED)

    private fun sim(
        id: String,
        presence: SimPresence,
        canApply: Boolean = true,
        canProvision: Boolean = false,
        slotIndex: Int = 0,
    ) = SimControlInstance(
        handle = SimHandle(id),
        displayLabel = "SIM ${slotIndex + 1} · Alpha",
        presence = presence,
        slotIndex = slotIndex,
        carrierLabel = null,
        mcc = "001",
        mnc = "01",
        lifecycle = EngineLifecycle.STOPPED,
        canStart = false,
        canStop = false,
        canApply = canApply,
        canProvision = canProvision,
    )
}
