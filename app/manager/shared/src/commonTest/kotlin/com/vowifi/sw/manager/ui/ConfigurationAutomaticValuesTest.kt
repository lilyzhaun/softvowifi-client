package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.NetworkOutletsCodec
import com.vowifi.sw.manager.data.ConfigurationCodec
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ConfigurationAutomaticValuesTest {
    @Test
    fun directoryAcceptsActualAutomaticDnsWithoutPersistedOverrides() {
        val outlets = NetworkOutletsCodec.decode("""{"ok":true,"version":3,"interfaces":[],"automaticDns":{"wifi":"192.0.2.53:53","sim1":"[2001:db8::53]:53"}}""")
        assertTrue(outlets.supported, "The real network-list response must carry the automatic resolver")
        val instance = automaticValuesInstance()
        val draft = ConfigurationEditorDraft(instance)
        fun automatic() = configurationAutomaticValue(instance, ConfigurationFieldKey.Dns, draft.transport, outlets)
        assertEquals("192.0.2.53:53（所选网络）", automatic())
        draft.transport = ConfigurationTransport.SIM1
        assertEquals("[2001:db8::53]:53（所选网络）", automatic())
        assertTrue(configurationFieldDescription(instance, ConfigurationFieldKey.Dns, draft.transport, outlets)
            .contains("[2001:db8::53]:53"))
        assertEquals("", draft.dns.text.toString())
        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeChanges(ConfigurationEditorDraft(instance).overrides(), draft.overrides())).jsonObject
        assertEquals(setOf("transport"), patch.keys, "Displaying defaults must not save them as overrides")
        assertEquals("尚未获取所选网络的 DNS，请刷新配置",
            configurationAutomaticValue(instance, ConfigurationFieldKey.Dns, ConfigurationTransport.SIM2, outlets))
        assertEquals("尚未获取所选网络的 DNS，请刷新配置",
            configurationAutomaticValue(instance, ConfigurationFieldKey.Dns, ConfigurationTransport.WIFI, outlets.copy(issue = "读取失败")))
    }

    @Test
    fun unknownSmscExplainsThatSimValueHasNotBeenRead() {
        assertEquals("尚未获取，启动引擎后从 SIM 卡读取",
            configurationAutomaticValue(automaticValuesInstance(), ConfigurationFieldKey.Smsc))
    }

    @Test
    fun simSmscIsVisibleWithoutBecomingAnEditableOverride() {
        val instance = automaticValuesInstance().copy(lifecycle = ConfigurationLifecycle.RUNNING, resolvedSmsc = "+19995550123")
        val draft = ConfigurationEditorDraft(instance)
        assertEquals("+19995550123（SIM 卡）", configurationAutomaticValue(instance, ConfigurationFieldKey.Smsc))
        assertTrue(configurationFieldDescription(instance, ConfigurationFieldKey.Smsc).contains("+19995550123"))
        assertEquals("", draft.smsc.text.toString())
        draft.syncFrom(instance.copy(resolvedSmsc = "+19995550124"))
        assertFalse(draft.dirty)
        assertEquals("", draft.overrides().smsc)
        draft.replaceField(ConfigurationFieldKey.Smsc, "+19995550125")
        assertEquals("+19995550125 · 未保存", configurationValueSummary(draft.smsc.text.toString(),
            configurationAutomaticValue(instance, ConfigurationFieldKey.Smsc), true, null))
        draft.replaceField(ConfigurationFieldKey.Smsc, "")
        assertFalse(draft.dirty)
    }

    @Test
    fun malformedAutomaticDnsIsNotDisplayedAsKnown() {
        for (map in listOf("""{"bogus":"192.0.2.53:53"}""", """{"wifi":""}""",
            """{"wifi":"dns.example.test:53"}""", """{"wifi":"192.0.2.53:0"}""")) {
            val result = NetworkOutletsCodec.decode("""{"ok":true,"version":3,"interfaces":[],"automaticDns":$map}""")
            assertFalse(result.supported)
            assertTrue(result.automaticDns.isEmpty())
        }
        assertFalse(NetworkOutletsCodec.decode("""{"ok":true,"version":2,"interfaces":[],"automaticDns":{}}""").supported)
        assertTrue(NetworkOutletsCodec.decode("""{"ok":true,"version":3,"interfaces":[]}""").supported)
    }
}

internal fun automaticValuesInstance() = ConfigurationInstance(
    instanceId = ConfigurationInstanceId("sim-1"), displayLabel = "SIM 1 · 测试", lifecycle = ConfigurationLifecycle.STOPPED,
    transport = ConfigurationTransport.WIFI, smsc = "", epdgFqdn = "", imsRealm = "", dns = "",
    resolvedEpdgFqdn = "epdg.test", resolvedImsRealm = "ims.test", mcc = "001", mnc = "01",
)
