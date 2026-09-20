package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationIdentitySource
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.ui.ConfigurationEditorDraft
import com.vowifi.sw.manager.ui.configurationSourceSummary
import com.vowifi.sw.manager.ui.hasIdentityRealmOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ConfigurationProvenanceTest {
    @Test fun catalogInitialSourceIsKnownWithoutClaimingReviewedCarrierMatch() {
        val instance = instance("isim", initialSource = "catalog")
        assertEquals("catalog", instance.imsPolicy.initialSource)
        assertEquals("按已有配置（含目录参数；其余来自身份或默认值）", configurationSourceSummary(instance))
    }

    @Test fun onlyExplicitLegacyOrOverrideIdentityMaySuggestClearingExistingRealm() {
        for (source in listOf("legacy", "override", "isim", "formula", "unknown", "future", null)) {
            val instance = instance(source).copy(displayLabel = "实例 •••• 1234")
            assertEquals(ConfigurationIdentitySource.entries.firstOrNull { it.wireValue == source }
                ?: ConfigurationIdentitySource.UNKNOWN, instance.identitySource)
            assertEquals(source in setOf("legacy", "override"), hasIdentityRealmOverride(instance), "$source")
            assertFalse(hasIdentityRealmOverride(instance.copy(imsRealm = "")))
            assertFalse(hasIdentityRealmOverride(instance.copy(
                imsPolicy = instance.imsPolicy.copy(digestRealmSource = "carrier"),
            )))
        }
    }

    @Test fun sourceMetadataNeverEntersEditableOverridesOrDelta() {
        val legacy = ConfigurationEditorDraft(instance("legacy")).overrides()
        for (source in listOf("override", "isim", "formula", "future", null)) {
            val overrides = ConfigurationEditorDraft(instance(source)).overrides()
            assertEquals(ConfigurationCodec.encode(legacy), ConfigurationCodec.encode(overrides))
            assertEquals("{}", ConfigurationCodec.encodeChanges(legacy, overrides))
            assertFalse(ConfigurationCodec.encode(overrides).contains("identity"))
        }
    }

    private fun instance(identitySource: String?, initialSource: String = "default") =
        assertIs<ConfigurationUiState.Ready>(ConfigurationEvaluator.fromShow(
            """{
                "ok":true,"displayLabel":"1234","lifecycle":"stopped","transport":"wifi",
                "smsc":"","epdgFqdn":"","imsRealm":"template.example.test","dns":"",
                "resolvedEpdgFqdn":"epdg.example.test","resolvedImsRealm":"identity.example.test",
                "mcc":"530","mnc":"05","imsDigestRealm":"identity.example.test",
                "imsDigestRealmSource":"identity","paniInitialSource":"$initialSource"
                ${identitySource?.let { ",\"identitySource\":\"$it\"" }.orEmpty()}
            }""".trimIndent(),
            ConfigurationInstanceId("sim-1"),
        )).instances.single()
}
