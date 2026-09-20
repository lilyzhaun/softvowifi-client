package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.CountryCatalogGenerated
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeCountryCatalogTest {
    @Test fun newZealand530KeepsItsFlagAndHomeCarrierName() =
        assertCountry("530", "nz", "🇳🇿")

    @Test fun hongKong454KeepsItsFlagAndHomeCarrierName() =
        assertCountry("454", "hk", "🇭🇰")

    @Test fun unitedStates310KeepsItsFlagAndHomeCarrierName() =
        assertCountry("310", "us", "🇺🇸")

    @Test fun unknownAndMalformedMccDoNotGuessACountryOrChangeTheName() {
        for (mcc in listOf(null, "", "000", "999", "US", "31", "3100", " 310", "310 ", "３１０", "3a0", "+31", "53\n")) {
            assertEquals("", CountryCatalogGenerated.countryForMcc(mcc), "MCC=$mcc")
            assertEquals("SIM 1 · 示例运营商", project(instance(mcc)).title, "MCC=$mcc")
        }
    }

    @Test fun missingHomeNameRetainsTheExistingUnknownFallback() {
        assertEquals("SIM 1 · 🇳🇿 归属运营商未知", project(instance("530").copy(homeCarrierLabel = null)).title)
        assertEquals("SIM 1 · 归属运营商未知", project(instance(null).copy(homeCarrierLabel = null)).title)
    }

    @Test fun countryNeverSelectsAnActionOrEnablesProvisioning() {
        for (canProvision in listOf(false, true)) {
            val base = instance(null).copy(presence = SimPresence.ACTIVE_UNCONFIGURED, canProvision = canProvision)
            val reference = project(base)
            for (mcc in listOf("530", "454", "310", "999", "3a0")) {
                val row = project(base.copy(mcc = mcc))
                assertEquals(reference.action, row.action)
                assertEquals(reference.statusText, row.statusText)
                assertEquals(reference.tone, row.tone)
            }
        }
    }

    @Test fun homeCountryLookupHasNoMixedCarrierTableDependency() {
        val relative = "src/commonMain/kotlin/com/vowifi/sw/manager/ui/HomePresentation.kt"
        val source = listOf(File(relative), File("shared", relative), File("app/manager/shared", relative))
            .single(File::isFile).readText()
        assertFalse(Regex("\\bCarrierCatalogGenerated\\b").containsMatchIn(source))
        assertTrue(Regex("CountryCatalogGenerated\\.countryForMcc\\(instance\\.mcc\\)").containsMatchIn(source))
    }

    private fun assertCountry(mcc: String, iso2: String, flag: String) {
        assertEquals(iso2, CountryCatalogGenerated.countryForMcc(mcc))
        val source = instance(mcc)
        val row = project(source)
        assertEquals("SIM 1 · $flag 示例运营商", row.title)
        assertEquals(row, project(source.copy(carrierLabel = "Other roaming network")))
        assertEquals(row.copy(title = "$flag 示例运营商"), project(source.copy(slotIndex = null)))
    }

    private fun instance(mcc: String?) = SimControlInstance(
        handle = SimHandle("home-country-test"), displayLabel = "Unchanged display label",
        presence = SimPresence.ACTIVE_MATCHED, slotIndex = 0, carrierLabel = "Roaming network",
        homeCarrierLabel = "示例运营商", mcc = mcc, mnc = "01",
        lifecycle = EngineLifecycle.STOPPED, pid = null,
        canStart = true, canStop = false, canApply = true,
    )

    private fun project(instance: SimControlInstance) = homeDashboardPresentation(
        RegistrationUiState.Empty,
        SimControlUiState.Ready(listOf(instance)),
        EnvironmentDiagnosticsUiState.Loading,
        nowMs = 0,
    ).actions.single()
}
