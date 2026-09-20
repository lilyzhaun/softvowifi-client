package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SubscriptionDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FixtureConfigurationSourceTest {
    @Test
    fun exposesEveryBoundedFixtureMode() {
        assertIs<ConfigurationUiState.Loading>(FixtureConfigurationSource("loading").state.value)
        assertIs<ConfigurationUiState.Empty>(FixtureConfigurationSource("empty").state.value)
        assertIs<ConfigurationUiState.Failure>(FixtureConfigurationSource("failure").state.value)
        assertIs<ConfigurationUiState.PermissionDenied>(FixtureConfigurationSource("permission").state.value)
        assertIs<ConfigurationUiState.Unavailable>(FixtureConfigurationSource("unavailable").state.value)
    }

    @Test
    fun readyUsesMultipleMaskedInstancesAndFixtureProvenance() {
        val source = FixtureConfigurationSource("ready")

        val state = assertIs<ConfigurationUiState.Ready>(source.state.value)

        assertTrue(state.isFixture)
        assertEquals(2, state.instances.size)
        assertTrue(state.instances.all { it.displayLabel.startsWith("实例 •••• ") })
        assertTrue(state.instances.none { it.instanceId.value == it.displayLabel })
        assertEquals(listOf("sim-1", "sim-2"), state.instances.map { it.instanceId.value })
        assertTrue(state.instances.all { it.systemSubscription == SubscriptionDiscovery.NotAttempted })
    }

    @Test
    fun alignedModesExposeOnlyTheirConfiguredHandles() {
        assertEquals(
            listOf("sim-1"),
            assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("sim-1").state.value)
                .instances.map { it.instanceId.value },
        )
        assertEquals(
            listOf("sim-2"),
            assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("sim-2").state.value)
                .instances.map { it.instanceId.value },
        )
        assertIs<ConfigurationUiState.Empty>(FixtureConfigurationSource("empty").state.value)

        val sim2Source = FixtureConfigurationSource("sim-2")
        sim2Source.refresh()
        assertEquals(
            listOf("sim-2"),
            assertIs<ConfigurationUiState.Ready>(sim2Source.state.value)
                .instances.map { it.instanceId.value },
        )
    }

    @Test
    fun provisionCreatesEitherGenericHandleWithItsPublicPlmn() {
        val cases = listOf(
            Triple("sim-1", "460", "09"),
            Triple("sim-2", "505", "03"),
        )

        cases.forEach { (handle, mcc, mnc) ->
            val source = FixtureConfigurationSource("empty")

            source.provision(SimHandle(handle))
            val ready = assertIs<ConfigurationUiState.Ready>(source.state.value)

            assertEquals(listOf(handle), ready.instances.map { it.instanceId.value })
            assertEquals("配置已创建", ready.message)
            assertTrue(ready.isFixture)
            assertEquals(null, ready.applyingInstanceId)
            assertEquals(mcc, ready.instances.single().mcc)
            assertEquals(mnc, ready.instances.single().mnc)
            assertPrivate(ready.toString())
        }
    }

    @Test
    fun provisionPreservesTheOtherConfiguredFixture() {
        val cases = listOf(
            Triple("sim-1", "sim-2", "sim-2"),
            Triple("sim-2", "sim-1", "sim-1"),
        )

        cases.forEach { (handle, initialMode, preservedId) ->
            val source = FixtureConfigurationSource(initialMode)
            val before = assertIs<ConfigurationUiState.Ready>(source.state.value).instances.single()

            source.provision(SimHandle(handle))
            val after = assertIs<ConfigurationUiState.Ready>(source.state.value)

            assertEquals(listOf("sim-1", "sim-2"), after.instances.map { it.instanceId.value })
            assertEquals(before, after.instances.single { it.instanceId.value == preservedId })
            assertEquals("配置已创建", after.message)
        }
    }

    @Test
    fun unknownProvisionHandleLeavesConfiguredFixtureUnchanged() {
        val source = FixtureConfigurationSource("ready")
        val before = source.state.value

        source.provision(SimHandle("unknown"))

        assertEquals(before, source.state.value)
    }

    private fun assertPrivate(text: String) {
        assertFalse(Regex("""\d{15,}""").containsMatchIn(text), text)
        listOf("iccid", "imsi", "imei", "msisdn", "line1", "subId").forEach { token ->
            assertFalse(text.contains(token, ignoreCase = true), text)
        }
    }
}
