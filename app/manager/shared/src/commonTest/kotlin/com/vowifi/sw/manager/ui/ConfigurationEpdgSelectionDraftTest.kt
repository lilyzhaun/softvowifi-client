package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*
import kotlin.test.*

class ConfigurationEpdgSelectionDraftTest {
    @Test fun sourceSummaryDoesNotDescribeAnInheritedCatalogAsManual() {
        val inherited = ConfigurationEpdgSelectionFacts(true, "catalog", selection())
        assertTrue(epdgSelectionFactsSummary(inherited).startsWith("运营商目录 1 项"))
        assertFalse("手动" in epdgSelectionFactsSummary(inherited))
        assertTrue(epdgSelectionFactsSummary(inherited.copy(source = "user", overrideValue = selection())).startsWith("手动 1 项"))
        assertEquals("未知；请刷新核对", epdgSelectionFactsSummary(ConfigurationEpdgSelectionFacts(false, "catalog")))
    }

    @Test fun editedListKeepsOriginalRevisionAndCannotBeAppliedAsNextCall() {
        val row = instance(ConfigurationEpdgSelection())
        val draft = ConfigurationEditorDraft(row)
        assertTrue(draft.replaceEpdgSelection(selection()))
        assertEquals(1, draft.modifiedCount); assertFalse(draft.onlyNextCallChanges)
        val fresh = row.copy(configRevision = NEXT)
        draft.syncFrom(fresh)
        val retained = ConfigurationEditorDraft(fresh, draft.retained())
        assertEquals(selection(), retained.epdgSelection)
        assertEquals(INITIAL, retained.overrides().expectedRevision)
        retained.epdgFqdn.edit { append("conflicting.example") }
        assertFalse(retained.validate()); assertNotNull(retained.selectionError)
        retained.discard()
        assertFalse(retained.dirty); assertEquals(NEXT, retained.overrides().expectedRevision)
    }

    @Test fun unknownCanOnlyBeClearedAndSavingProtectsTheWholeGroup() {
        val row = instance(null)
        val draft = ConfigurationEditorDraft(row)
        assertFalse(draft.replaceEpdgSelection(selection()))
        assertTrue(draft.replaceEpdgSelection(ConfigurationEpdgSelection()))
        assertFalse(draft.replaceEpdgSelection(selection()))
        draft.submit { _, _, complete ->
            complete(ConfigurationApplyOutcome.Confirmed(row.copy(configRevision = NEXT, epdgSelection = ConfigurationEpdgSelection())))
            ConfigurationApplyAcceptance.Accepted
        }
        assertEquals(ConfigurationEditStatus.Saved, draft.status)
        assertTrue(draft.replaceEpdgSelection(selection()))
        var complete: ((ConfigurationApplyOutcome) -> Unit)? = null
        draft.submit { _, _, done -> complete = done; ConfigurationApplyAcceptance.Accepted }
        assertFalse(draft.replaceEpdgSelection(ConfigurationEpdgSelection()))
        complete!!(ConfigurationApplyOutcome.Unconfirmed)
        assertEquals(selection(), draft.epdgSelection)
        assertEquals(ConfigurationEditStatus.Unconfirmed, draft.status)
    }

    private fun selection() = ConfigurationEpdgSelection(listOf(ConfigurationEpdgCandidate(ConfigurationEpdgKind.HOME_PLMN)))
    private fun instance(selection: ConfigurationEpdgSelection?) = ConfigurationInstance(ConfigurationInstanceId("opaque"), "SIM 1", ConfigurationLifecycle.RUNNING,
        ConfigurationTransport.WIFI, "", "", "", "", "epdg.example", "ims.example", "001", "01", configVersion = 1, configRevision = INITIAL, epdgSelection = selection)
    private companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
