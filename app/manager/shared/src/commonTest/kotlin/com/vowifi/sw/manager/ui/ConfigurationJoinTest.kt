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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigurationJoinTest {
    @Test
    fun readyInventoryProjectsOnlyActiveRows() {
        val presentation = configurationPresentation(
            ready(instance("sim-1"), instance("sim-2")),
            SimControlUiState.Ready(
                listOf(
                    sim("sim-2", "实例 •••• 2002", SimPresence.REMOVED_CONFIG_RETAINED),
                    sim("sim-1", "SIM 1 · Alpha", SimPresence.ACTIVE_MATCHED),
                    sim("sim-new", "SIM 2 · Beta", SimPresence.ACTIVE_UNCONFIGURED, canApply = false),
                ),
            ),
        )

        assertEquals(listOf("sim-1", "sim-new"), presentation.rows.map { it.key })
        assertEquals(
            listOf(
                ConfigurationRowKind.CONFIGURED,
                ConfigurationRowKind.ACTIVE_UNCONFIGURED,
            ),
            presentation.rows.map { it.kind },
        )
    }

    @Test
    fun activeUnconfiguredHasNoEditorOrSave() {
        val row = configurationPresentation(
            ConfigurationUiState.Empty,
            SimControlUiState.Ready(
                listOf(sim("sim-new", "SIM 1 · Alpha", SimPresence.ACTIVE_UNCONFIGURED, canApply = false)),
            ),
        ).rows.single()

        assertEquals(ConfigurationRowKind.ACTIVE_UNCONFIGURED, row.kind)
        assertEquals("未配置/未支持", row.status)
        assertEquals(SimHandle("sim-new"), row.handle)
        assertNull(row.instance)
        assertFalse(row.canApply)
        assertFalse(row.canProvision)
    }

    @Test
    fun matchedConfigurationUsesBothApplyRules() {
        val configuration = instance("sim-1")
        val ready = ready(configuration)
        val allowed = row(ready, sim("sim-1", "SIM 1 · Alpha", SimPresence.ACTIVE_MATCHED))
        val deniedBySim = row(
            ready,
            sim("sim-1", "SIM 1 · Alpha", SimPresence.ACTIVE_MATCHED, canApply = false),
        )
        val deniedByConfiguration = row(
            ready.copy(applyingInstanceId = configuration.instanceId),
            sim("sim-1", "SIM 1 · Alpha", SimPresence.ACTIVE_MATCHED),
        )

        assertEquals(configuration.instanceId, allowed.instance?.instanceId)
        assertTrue(allowed.canApply)
        assertNull(allowed.handle)
        assertFalse(allowed.canProvision)
        assertFalse(deniedBySim.canApply)
        assertFalse(deniedByConfiguration.canApply)
    }

    @Test
    fun retainedConfigurationIsHiddenWhenInventoryIsReady() {
        val configuration = instance("sim-2")
        val presentation = configurationPresentation(
            ready(configuration),
            SimControlUiState.Ready(
                listOf(sim("sim-2", "实例 •••• 2002", SimPresence.REMOVED_CONFIG_RETAINED)),
            ),
        )

        assertTrue(presentation.rows.isEmpty())
        assertFalse(presentation.showEditor)
        assertFalse(presentation.showSaveAction)
        assertEquals(ConfigurationPanelKind.EMPTY, presentation.panelKind)
    }

    @Test
    fun missingConfigurationJoinFailsSafeWithoutExposingHandle() {
        val row = row(
            ready(),
            sim("sim-secret-handle", "SIM 1 · Alpha", SimPresence.ACTIVE_MATCHED),
        )

        assertEquals(ConfigurationRowKind.UNAVAILABLE, row.kind)
        assertEquals("配置暂不可用", row.status)
        assertNull(row.instance)
        assertNull(row.handle)
        assertFalse(row.canApply)
        assertFalse(row.canProvision)
        assertFalse(row.visibleText().contains("sim-secret-handle"))
    }

    @Test
    fun hostileIdentityFallsBackToMaskedLabel() {
        val row = row(
            ready(instance("sim-1")),
            sim(
                "sim-1",
                "subId=7 ICCID 8986000000000000336 /data/adb/vowifi",
                SimPresence.ACTIVE_MATCHED,
            ),
        )

        assertEquals("实例 ••••", row.label)
        assertFalse(row.visibleText().contains("/data/"))
        assertFalse(Regex("""\d{15,}""").containsMatchIn(row.visibleText()))
    }

    @Test
    fun unconfiguredVerifiedRowCarriesOpaqueHandleWithoutEditor() {
        val presentation = configurationPresentation(
            ConfigurationUiState.Empty,
            SimControlUiState.Ready(
                listOf(
                    sim(
                        "sim-new",
                        "SIM 2 · T-Mobile",
                        SimPresence.ACTIVE_UNCONFIGURED,
                        canApply = false,
                        mcc = "310",
                        mnc = "260",
                        canProvision = true,
                    ),
                ),
            ),
        )
        val row = presentation.rows.single()
        val publicText = row.visibleText() + " " + row.toString()

        assertEquals(ConfigurationRowKind.ACTIVE_UNCONFIGURED, row.kind)
        assertEquals(SimHandle("sim-new"), row.handle)
        assertEquals(true, row.canProvision)
        assertNull(row.instance)
        assertFalse(row.canApply)
        assertEquals("未配置/未支持", row.status)
        assertFalse(presentation.showEditor)
        assertFalse(presentation.showSaveAction)
        assertFalse(Regex("""\d{15,}""").containsMatchIn(publicText))
        listOf("iccid", "imsi", "imei", "msisdn", "line1", "subId").forEach { token ->
            assertFalse(publicText.contains(token, ignoreCase = true), publicText)
        }
    }

    private fun row(state: ConfigurationUiState.Ready, sim: SimControlInstance) =
        configurationPresentation(state, SimControlUiState.Ready(listOf(sim))).rows.single()

    private fun ready(vararg instances: ConfigurationInstance) = ConfigurationUiState.Ready(instances.toList())

    private fun instance(id: String) = ConfigurationInstance(
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

    private fun sim(
        id: String,
        label: String,
        presence: SimPresence,
        canApply: Boolean = true,
        mcc: String = "001",
        mnc: String = "01",
        canProvision: Boolean = false,
    ) = SimControlInstance(
        handle = SimHandle(id),
        displayLabel = label,
        presence = presence,
        slotIndex = if (presence == SimPresence.REMOVED_CONFIG_RETAINED) null else 0,
        carrierLabel = null,
        mcc = mcc,
        mnc = mnc,
        lifecycle = EngineLifecycle.STOPPED,
        canStart = false,
        canStop = false,
        canApply = canApply,
        canProvision = canProvision,
    )

    private fun ConfigurationRowPresentation.visibleText(): String =
        listOfNotNull(label, status, instance?.displayLabel, handle?.value).joinToString(" ")
}
