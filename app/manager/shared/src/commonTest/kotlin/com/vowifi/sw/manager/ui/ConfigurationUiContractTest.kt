package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationImsPolicy
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.AppRouteCodec
import com.vowifi.sw.manager.ui.navigation.ConfigurationSimHandle
import com.vowifi.sw.manager.ui.navigation.RouteDecodeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigurationUiContractTest {
    @Test
    fun overviewRowStatesLiveEngineNetworkAndDraftOutcomeOnly() {
        val running = instance(lifecycle = ConfigurationLifecycle.RUNNING, transport = ConfigurationTransport.WIFI)
        val draft = ConfigurationEditorDraft(running)
        assertEquals("001 / 01 · •••• 1001\n运行中 · 底层网络 Wi-Fi", configurationSimEntrySummary(running, draft, "001 / 01 · •••• 1001"))
        draft.transport = ConfigurationTransport.SIM2
        assertEquals("运行中 · 底层网络 Wi-Fi\n有未保存修改", configurationSimEntrySummary(running, draft, null),
            "网络摘要只显示权威配置，草稿只以“有未保存修改”出现")
    }

    @Test
    fun valueRowsShowManualValueOrTheAuthoritativeAutomaticValue() {
        assertEquals("自动：epdg.example.invalid", configurationValueSummary("", "epdg.example.invalid", false, null))
        assertEquals("自动", configurationValueSummary("", null, false, null))
        assertEquals("epdg.user.invalid · 未保存", configurationValueSummary("epdg.user.invalid", "epdg.example.invalid", true, null))
        assertEquals("SMSC 须以 + 开头，或留空", configurationValueSummary("bad", null, true, "SMSC 须以 + 开头，或留空"),
            "错误优先，不把非法值显示成待保存的正常值")

        val base = instance()
        assertEquals("epdg.example.invalid", configurationAutomaticValue(base, ConfigurationFieldKey.Epdg))
        assertEquals("ims.example.invalid", configurationAutomaticValue(base, ConfigurationFieldKey.Realm))
        assertEquals("尚未获取，启动引擎后从 SIM 卡读取", configurationAutomaticValue(base, ConfigurationFieldKey.Smsc))
        assertEquals("NZ（归属国家，非当前位置）", configurationAutomaticValue(base, ConfigurationFieldKey.PaniCountry))
        val manual = base.copy(imsPolicy = base.imsPolicy.copy(country = "AU", countrySource = "user"))
        assertNull(configurationAutomaticValue(manual, ConfigurationFieldKey.PaniCountry), "已保存的手动值会遮住自动值，不能冒充自动结果")
    }

    @Test
    fun sourcesAndPoliciesUsePlainWordsInsteadOfWireValues() {
        assertEquals(listOf("运营商策略", "运营商目录", "手动设置", "默认值", "归属国家，非当前位置", "身份域", "来源未知"),
            listOf("carrier", "catalog", "user", "default", "home_mcc", "identity", "surprise").map(::configurationSourceLabel))
        assertEquals("TCP + IPsec", configurationSecurityLabel("tcp_ipsec_3gpp"))
        assertEquals("当前解析：TCP + IPsec · 运营商策略", configurationEffectiveSummary("TCP + IPsec", "carrier", false))
        assertEquals("当前解析：wlan · 手动设置\n未保存", configurationEffectiveSummary("wlan", "user", true))
    }

    @Test
    fun saveBarNamesCountConsequenceAndOutcomeWithoutRewordingFailures() {
        val stopped = ConfigurationEditorDraft(instance())
        stopped.transport = ConfigurationTransport.WIFI
        stopped.smsc.edit { replace(0, length, "+123") }
        assertEquals(2, stopped.modifiedCount)
        assertEquals("2 项修改未保存", configurationSaveBarTitle(stopped))
        assertEquals("下次启动生效。", configurationSaveBarDetail(stopped, ConfigurationLifecycle.STOPPED))
        assertEquals("保存后将重启当前引擎实例。", configurationSaveBarDetail(stopped, ConfigurationLifecycle.RUNNING))
        assertEquals("2 项修改尚未保存。保存后将重启当前引擎实例，连接会暂时中断。",
            configurationLeaveSummary(stopped.modifiedCount, ConfigurationLifecycle.RUNNING))
        assertEquals("2 项修改尚未保存。保存后下次启动生效。", configurationLeaveSummary(2, ConfigurationLifecycle.STOPPED))

        var complete: ((com.vowifi.sw.manager.model.ConfigurationApplyOutcome) -> Unit)? = null
        stopped.submit { _, _, done -> complete = done; com.vowifi.sw.manager.model.ConfigurationApplyAcceptance.Accepted }
        assertEquals("正在保存 2 项修改", configurationSaveBarTitle(stopped))
        assertEquals("正在保存配置，请稍候", configurationSaveBarDetail(stopped, ConfigurationLifecycle.STOPPED))
        requireNotNull(complete).invoke(com.vowifi.sw.manager.model.ConfigurationApplyOutcome.Failed)
        assertEquals("保存失败，修改已保留；可检查后重试", configurationSaveBarDetail(stopped, ConfigurationLifecycle.STOPPED))
    }

    @Test
    fun fieldCandidatesUseSaveValidationAndNeverWriteTheDraft() {
        val draft = ConfigurationEditorDraft(instance())
        assertEquals("SMSC 须以 + 开头，或留空", draft.candidateError(ConfigurationFieldKey.Smsc, "invalid"))
        assertNull(draft.candidateError(ConfigurationFieldKey.Smsc, "+123"))
        assertEquals("", draft.smsc.text.toString())
        assertFalse(draft.dirty)
        draft.replaceField(ConfigurationFieldKey.Smsc, "+123")
        assertEquals(setOf(ConfigurationFieldKey.Smsc), draft.modifiedFields())
        assertTrue(draft.dirty)
        assertFalse(draft.transportModified)
    }

    @Test
    fun simRouteCarriesOnlyTheOpaqueInstanceHandle() {
        val route = AppRoute.SettingsConfigurationSim(requireNotNull(ConfigurationSimHandle.parse("sim-2")))
        val encoded = AppRouteCodec.encode(route)
        assertEquals("""{"type":"SettingsConfigurationSim","sim":"sim-2"}""", encoded)
        assertEquals(route, assertIs<RouteDecodeResult.Success>(AppRouteCodec.decode(encoded)).route)
        assertEquals("SettingsConfigurationSim", route.toString())
        assertNull(ConfigurationSimHandle.parse("8986 0000 0000 0000 0000"))
        assertNull(ConfigurationSimHandle.parse(""))
        assertIs<RouteDecodeResult.Failure>(AppRouteCodec.decode("""{"type":"SettingsConfigurationSim","sim":"../x"}"""))
    }

    private fun instance(
        lifecycle: ConfigurationLifecycle = ConfigurationLifecycle.STOPPED,
        transport: ConfigurationTransport = ConfigurationTransport.AUTO,
    ) = ConfigurationInstance(
        instanceId = ConfigurationInstanceId("sim-1"),
        displayLabel = "SIM 1",
        lifecycle = lifecycle,
        transport = transport,
        smsc = "",
        epdgFqdn = "",
        imsRealm = "",
        dns = "",
        resolvedEpdgFqdn = "epdg.example.invalid",
        resolvedImsRealm = "ims.example.invalid",
        mcc = "001",
        mnc = "01",
        imsPolicy = ConfigurationImsPolicy(country = "NZ", countrySource = "home_mcc"),
    )
}
