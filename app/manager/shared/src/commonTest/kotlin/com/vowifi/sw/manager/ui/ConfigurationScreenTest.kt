package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SubscriptionDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigurationScreenTest {
    @Test
    fun busyInstanceDisablesRepeatedApply() {
        val instance = instance()
        val state = ready(applyingInstanceId = instance.instanceId)

        assertFalse(canApplyConfiguration(state, instance.instanceId))
        assertEquals("正在保存配置", configurationSaveLabel(state, instance.instanceId))
    }

    @Test
    fun retryProvisionActionRequiresActiveUnconfiguredRowAndIdleState() {
        val handle = SimHandle("fixture-sim")
        val eligible = ConfigurationRowPresentation(
            key = "fixture-sim",
            label = "SIM 2 · Lebara",
            kind = ConfigurationRowKind.ACTIVE_UNCONFIGURED,
            handle = handle,
            canProvision = true,
        )
        val matched = eligible.copy(kind = ConfigurationRowKind.CONFIGURED)
        val retained = matched.copy(status = "SIM 已移除/配置保留")
        val unavailable = eligible.copy(canProvision = false)
        val missingHandle = eligible.copy(handle = null)
        val ready = ConfigurationUiState.Ready(emptyList())

        assertTrue(canProvisionConfiguration(ready, eligible))
        assertTrue(canProvisionConfiguration(ConfigurationUiState.Empty, eligible))
        assertFalse(canProvisionConfiguration(ready.copy(applyingInstanceId = ConfigurationInstanceId("busy")), eligible))
        assertFalse(canProvisionConfiguration(ready, matched))
        assertFalse(canProvisionConfiguration(ready, retained))
        assertFalse(canProvisionConfiguration(ready, unavailable))
        assertFalse(canProvisionConfiguration(ready, missingHandle))
        assertEquals("重试创建配置", configurationProvisionLabel())
    }

    @Test
    fun systemSubscriptionFactCoversDiscoveryWithoutDisablingApply() {
        val cases = listOf(
            SubscriptionDiscovery.NotAttempted to null,
            SubscriptionDiscovery.Missing to "缺少元数据",
            SubscriptionDiscovery.Unavailable to "不可用",
            SubscriptionDiscovery.Matched("T-Mobile", 1, "310", "260") to
                "SIM 2 · T-Mobile · 310 / 260",
        )

        cases.forEach { (discovery, expectedSummary) ->
            val state = ready(discovery = discovery)
            val current = state.instances.single()
            val fact = configurationSystemSubscriptionFact(current)

            if (expectedSummary == null) {
                assertNull(fact)
            } else {
                assertEquals("系统订阅", fact?.title)
                assertEquals(expectedSummary, fact?.summary)
                assertTrue(canApplyConfiguration(state, current.instanceId))
            }
        }
    }

    @Test
    fun lifecycleLabelsCoverEveryTypedState() {
        val expected = mapOf(
            ConfigurationLifecycle.CONFIG_MISSING to "缺少配置",
            ConfigurationLifecycle.STOPPED to "已停止",
            ConfigurationLifecycle.STARTING to "启动中",
            ConfigurationLifecycle.RUNNING to "运行中",
            ConfigurationLifecycle.STOPPING to "停止中",
        )

        assertEquals(ConfigurationLifecycle.entries.toSet(), expected.keys)
        assertEquals(expected, ConfigurationLifecycle.entries.associateWith(::configurationLifecycleLabel))
    }

    @Test
    fun applyConsequenceCoversEveryTypedLifecycle() {
        val restart = "保存后将重启当前引擎实例。"
        val nextStart = "下次启动生效。"
        val expected = mapOf(
            ConfigurationLifecycle.CONFIG_MISSING to nextStart,
            ConfigurationLifecycle.STOPPED to nextStart,
            ConfigurationLifecycle.STARTING to restart,
            ConfigurationLifecycle.RUNNING to restart,
            ConfigurationLifecycle.STOPPING to nextStart,
        )

        assertEquals(ConfigurationLifecycle.entries.toSet(), expected.keys)
        assertEquals(expected, ConfigurationLifecycle.entries.associateWith(::configurationApplyConsequence))
    }

    @Test
    fun editActionsAppearOnlyForUnsavedOrPendingDraftAndNameTheRestart() {
        val draft = ConfigurationEditorDraft(instance())
        assertFalse(configurationEditActionsVisible(draft))
        draft.dns.edit { append("192.0.2.10:53") }
        assertTrue(configurationEditActionsVisible(draft))
        assertEquals("保存配置", configurationSubmitLabel(draft, ConfigurationLifecycle.STOPPED))
        assertEquals("保存并重启", configurationSubmitLabel(draft, ConfigurationLifecycle.RUNNING))
        assertEquals("保存并重启", configurationSubmitLabel(draft, ConfigurationLifecycle.STARTING))
        assertEquals("有未保存修改\n保存后将重启当前引擎实例。", configurationEditFooter(draft, ConfigurationLifecycle.RUNNING))
        draft.submit { _, _, _ -> com.vowifi.sw.manager.model.ConfigurationApplyAcceptance.Accepted }
        assertTrue(draft.pending)
        assertTrue(configurationEditActionsVisible(draft))
        assertEquals("正在保存", configurationSubmitLabel(draft, ConfigurationLifecycle.RUNNING))
        assertEquals("正在保存配置，请稍候", configurationEditFooter(draft, ConfigurationLifecycle.RUNNING))
    }

    @Test
    fun refreshIsBlockedOnlyWhileAnInstanceIsBeingSaved() {
        assertTrue(configurationRefreshEnabled(ready()))
        assertTrue(configurationRefreshEnabled(ConfigurationUiState.Loading))
        assertFalse(configurationRefreshEnabled(ready(applyingInstanceId = instance().instanceId)))
    }

    @Test
    fun configurationScreenRequiresTypedSubmitAndExplicitRefresh() {
        assertNotNull(::typedConfigurationScreenCall)
    }

    private fun ready(
        applyingInstanceId: ConfigurationInstanceId? = null,
        discovery: SubscriptionDiscovery = SubscriptionDiscovery.NotAttempted,
    ) = ConfigurationUiState.Ready(
        instances = listOf(instance().copy(systemSubscription = discovery)),
        applyingInstanceId = applyingInstanceId,
    )

    private fun instance() = ConfigurationInstance(
        instanceId = ConfigurationInstanceId("fixture-a"),
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
}

@Composable
private fun typedConfigurationScreenCall(
    state: ConfigurationUiState,
    onSubmit: com.vowifi.sw.manager.model.ConfigurationSubmit,
    onRefresh: suspend () -> com.vowifi.sw.manager.data.RefreshResult,
) {
    ConfigurationScreen(state, onSubmit, onRefresh)
}
