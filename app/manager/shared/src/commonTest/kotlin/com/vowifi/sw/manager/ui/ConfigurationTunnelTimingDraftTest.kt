package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*
import kotlin.test.*

class ConfigurationTunnelTimingDraftTest {
    @Test fun catalogSummaryDoesNotClaimAManualOverrideOrNetworkLifetime() {
        val facts = ConfigurationTunnelTimingFacts(true, "catalog", selected)
        val summary = timingFactsSummary(facts)
        assertTrue(summary.startsWith("运营商目录"))
        assertFalse("手动设置" in summary)
        assertTrue("不是网侧协商结果" in summary)
        assertTrue(timingFactsSummary(facts.copy(source = "user", overrideValue = selected)).startsWith("手动设置"))
        assertEquals("未知；请刷新核对", timingFactsSummary(ConfigurationTunnelTimingFacts(false, "catalog")))
    }

    @Test fun refreshAndRetentionKeepTheWholeEditedGroupAndOriginalRevision() {
        val row = instance().copy(tunnelTiming = selected)
        val draft = ConfigurationEditorDraft(row)
        val edited = selected.copy(policy = selected.policy!!.copy(dpdSeconds = 0))
        assertTrue(draft.replaceTunnelTiming(edited))
        val fresh = row.copy(configRevision = NEXT, tunnelTiming = ConfigurationTunnelTiming(), epdgFqdn = "fresh.example")
        draft.syncFrom(fresh)
        val retained = ConfigurationEditorDraft(fresh, draft.retained())
        assertEquals(edited, retained.overrides().tunnelTiming)
        assertEquals(INITIAL, retained.overrides().expectedRevision)
        assertEquals("fresh.example", retained.epdgFqdn.text.toString())
        assertEquals(1, retained.modifiedCount)
        retained.discard()
        assertEquals(ConfigurationTunnelTiming(), retained.tunnelTiming)
        assertEquals(NEXT, retained.overrides().expectedRevision)
        assertFalse(retained.dirty)
    }

    @Test fun timingAndMixedEditsAlwaysUseNextStartRatherThanNextCall() {
        val draft = ConfigurationEditorDraft(instance())
        draft.replaceCallSignaling(ConfigurationCallSignaling(includePANI = false))
        assertTrue(draft.onlyNextCallChanges)
        assertTrue(draft.replaceTunnelTiming(selected))
        assertFalse(draft.onlyNextCallChanges)
        assertFalse(draft.onlySignalingChanges)
        assertEquals(2, draft.modifiedCount)
        assertEquals("保存并重启", configurationSubmitLabel(draft, ConfigurationLifecycle.RUNNING))
        assertTrue(configurationSaveRestarts(ConfigurationLifecycle.RUNNING, draft))
        assertFalse(configurationSaveRestarts(ConfigurationLifecycle.STOPPED, draft))
    }

    @Test fun missingHardLifetimesBlockSavingAndUnknownNeedsConfirmedClear() {
        val row = instance()
        val draft = ConfigurationEditorDraft(row)
        draft.replaceTunnelTiming(ConfigurationTunnelTiming(ConfigurationTunnelTimingPolicy()))
        draft.submit { _, _, _ -> error("incomplete timing group must not write") }
        assertEquals(ConfigurationEditStatus.Invalid, draft.status)
        assertNotNull(draft.timingError)
        assertTrue(draft.errors.isEmpty(), "期限组错误不能污染无关的单字段校验")
        assertEquals("请检查标出的字段；修改尚未保存", configurationEditSummary(draft))
        draft.replaceTunnelTiming(selected)
        assertTrue(draft.validate())
        val unknown = ConfigurationEditorDraft(row.copy(tunnelTiming = null))
        assertFalse(unknown.replaceTunnelTiming(selected))
        assertTrue(unknown.replaceTunnelTiming(ConfigurationTunnelTiming()))
        assertFalse(unknown.replaceTunnelTiming(selected))
        unknown.submit { _, payload, complete ->
            assertEquals(ConfigurationTunnelTiming(), payload.tunnelTiming)
            complete(ConfigurationApplyOutcome.Confirmed(row.copy(configRevision = NEXT)))
            ConfigurationApplyAcceptance.Accepted
        }
        assertEquals(ConfigurationEditStatus.Saved, unknown.status)
        assertTrue(unknown.replaceTunnelTiming(selected))
    }

    @Test fun pendingAndMismatchedReadbackRetainTheDraftWithoutClaimingSuccess() {
        val row = instance()
        val draft = ConfigurationEditorDraft(row)
        draft.replaceTunnelTiming(selected)
        var finish: (ConfigurationApplyOutcome) -> Unit = {}
        draft.submit { _, payload, callback ->
            assertEquals(selected, payload.tunnelTiming)
            finish = callback
            ConfigurationApplyAcceptance.Accepted
        }
        assertFalse(draft.replaceTunnelTiming(ConfigurationTunnelTiming()))
        finish(ConfigurationApplyOutcome.Confirmed(row.copy(configRevision = NEXT)))
        assertEquals(ConfigurationEditStatus.Unconfirmed, draft.status)
        assertEquals(selected, draft.tunnelTiming)
        assertEquals(INITIAL, draft.overrides().expectedRevision)
    }

    private fun instance() = ConfigurationInstance(ConfigurationInstanceId("sim-1"), "实例", ConfigurationLifecycle.RUNNING,
        ConfigurationTransport.WIFI, "", "", "", "", "epdg.example", "ims.example", "001", "01",
        configVersion = 1, configRevision = INITIAL, callSignaling = ConfigurationCallSignaling(),
        tunnelTiming = ConfigurationTunnelTiming())
    private val selected = ConfigurationTunnelTiming(ConfigurationTunnelTimingPolicy(
        ike = ConfigurationSaTiming(1200, 1800, 30), child = ConfigurationSaTiming(1500, 2100, 30)))
    private companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
