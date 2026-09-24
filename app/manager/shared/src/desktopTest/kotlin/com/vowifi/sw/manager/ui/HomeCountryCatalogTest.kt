package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineRuntimeStatus
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HomeCountryCatalogTest {
    @Test fun newZealand530KeepsItsFlagAndHomeCarrierName() = assertCountry("530", "🇳🇿")

    @Test fun unitedStates310KeepsItsFlagAndHomeCarrierName() = assertCountry("310", "🇺🇸")

    @Test fun hongKong454KeepsItsFlagAndHomeCarrierName() = assertCountry("454", "🇭🇰")

    @Test fun unknownAndMalformedMccDoNotGuessACountryOrChangeTheName() {
        for (mcc in listOf(null, "", "000", "999", "US", "31", "3100", " 310", "310 ", "３１０", "3a0", "+31", "53\n")) {
            assertEquals("SIM 1 · 示例运营商", project(instance(mcc)).title, "MCC=$mcc")
        }
    }

    @Test fun missingHomeNameDoesNotGuessFromCountryOrRoamingNetwork() {
        assertEquals("SIM 1 · 🇳🇿 归属运营商未知", project(instance("530").copy(homeCarrierLabel = null)).title)
        assertEquals("SIM 1 · 归属运营商未知", project(instance(null).copy(homeCarrierLabel = null)).title)
    }

    @Test fun countryNeverSelectsAnActionOrEnablesProvisioning() {
        for (canProvision in listOf(false, true)) {
            val base = instance(null).copy(presence = SimPresence.ACTIVE_UNCONFIGURED, canProvision = canProvision)
            val reference = project(base)
            assertEquals(HomeActionId.PROVISION, reference.action.id)
            assertEquals(canProvision, reference.action.enabled)
            for (mcc in listOf("530", "454", "310", "999", "3a0")) {
                val row = project(base.copy(mcc = mcc))
                assertEquals(reference.action, row.action)
                assertEquals(reference.statusText, row.statusText)
                assertEquals(reference.tone, row.tone)
            }
        }
    }

    @Test fun countryDoesNotChangeSoftwareImsProjectionOrLifecycleActions() {
        val runtime = EngineRuntimeStatus("CONNECTED", reconnects = 0, ims = "REGISTERED", call = "IDLE", transport = "UDP", pcscf = emptyList())
        for (lifecycle in EngineLifecycle.entries) {
            val base = instance(null).copy(lifecycle = lifecycle, runtime = runtime, canStop = true)
            val reference = dashboard(base)
            assertEquals(if (lifecycle == EngineLifecycle.RUNNING) "1" else "0", reference.metrics.first().value)
            for (mcc in listOf("530", "310", "454", "999", "3a0")) {
                val result = dashboard(base.copy(mcc = mcc))
                assertEquals(reference.metrics, result.metrics)
                assertEquals(reference.facts, result.facts)
                assertEquals(reference.hero, result.hero)
                assertEquals(reference.notices, result.notices)
                assertEquals(reference.actions.single().action, result.actions.single().action)
                assertEquals(reference.actions.single().statusText, result.actions.single().statusText)
                assertEquals(reference.actions.single().tone, result.actions.single().tone)
            }
        }
    }

    @Test fun compiledHomeCountryReaderReferencesOnlyPublicCountryCatalog() {
        val resource = "/com/vowifi/sw/manager/ui/HomePresentationKt.class"
        val bytecode = assertNotNull(javaClass.getResourceAsStream(resource), resource).use { it.readBytes() }
        // Class-file symbol bytes come from compiled production code, never source comments.
        val symbols = bytecode.toString(Charsets.ISO_8859_1)
        assertFalse(symbols.contains("com/vowifi/sw/manager/model/CarrierCatalogGenerated"), "Home still references the private carrier catalog")
        assertTrue(symbols.contains("com/vowifi/sw/manager/model/CountryCatalogGenerated"), "Home must reference the public country catalog")
        assertTrue(symbols.contains("countryForMcc"), "Home must use the validated MCC lookup")
    }

    private fun assertCountry(mcc: String, flag: String) {
        val source = instance(mcc)
        val row = project(source)
        assertEquals("SIM 1 · $flag 示例运营商", row.title)
        assertEquals(row, project(source.copy(carrierLabel = "Other roaming network")))
        assertEquals(row.copy(title = "$flag 示例运营商"), project(source.copy(slotIndex = null)))
        assertEquals(HomeActionId.START, row.action.id)
        assertTrue(row.action.enabled)
        assertFalse(row.action.busy)
    }

    private fun instance(mcc: String?) = SimControlInstance(
        handle = SimHandle("home-country-test"), displayLabel = "Unchanged display label",
        presence = SimPresence.ACTIVE_MATCHED, slotIndex = 0, carrierLabel = "Roaming network",
        homeCarrierLabel = "示例运营商", mcc = mcc, mnc = "01",
        lifecycle = EngineLifecycle.STOPPED, pid = null,
        canStart = true, canStop = false, canApply = true,
    )

    private fun dashboard(instance: SimControlInstance) = homeDashboardPresentation(
        RegistrationUiState.Empty,
        SimControlUiState.Ready(listOf(instance)),
        EnvironmentDiagnosticsUiState.Loading,
        nowMs = 0,
    )

    private fun project(instance: SimControlInstance) = dashboard(instance).actions.single()
}
