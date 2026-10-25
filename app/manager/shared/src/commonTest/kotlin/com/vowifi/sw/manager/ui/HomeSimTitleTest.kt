package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.OpaqueIccidHandleStore
import com.vowifi.sw.manager.data.SimControlEvaluator
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimActiveJoinInput
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HomeSimTitleTest {
    @Test fun pureDecimalHomeNamesFallBackAtSevenUnicodeCodePoints() {
        val supplementaryZero = "\uD835\uDFCE"
        for (name in listOf(supplementaryZero.repeat(7), "3" + supplementaryZero.repeat(5) + "\u0660", "\u0660".repeat(7))) {
            val row = project(fixture().copy(homeCarrierLabel = name))
            assertEquals("SIM 2 · 🇺🇸 归属运营商未知", row.title)
        }
    }

    @Test fun shortDecimalHomeNamesRetainTheirOriginalCodePoints() {
        val supplementaryZero = "\uD835\uDFCE"
        for (name in listOf(supplementaryZero.repeat(6), "3" + supplementaryZero.repeat(4) + "\u0660", "\u0660".repeat(6), "3")) {
            val row = project(fixture().copy(homeCarrierLabel = name))
            assertEquals("SIM 2 · 🇺🇸 $name", row.title)
        }
    }

    @Test fun completeTitleKeepsCarrierEmojiAndOptionalSlotFlagBoundaries() {
        val original = fixture().copy(homeCarrierLabel = "示例😀")
        assertEquals("SIM 2 · 🇺🇸 示例😀", project(original).title)
        assertEquals("SIM 2 · 示例😀", project(original.copy(mcc = null)).title)
        assertEquals("🇺🇸 示例😀", project(original.copy(slotIndex = null)).title)
        assertEquals("😀示例", project(original.copy(slotIndex = null, mcc = null, homeCarrierLabel = "😀示例")).title)
        assertEquals("示例", project(original.copy(slotIndex = null, mcc = null, homeCarrierLabel = "示例")).title)
    }

    @Test fun homeFieldUsesCodePointsAndRemovesDirectionalAndLineControls() {
        val unchanged = project(fixture())
        val cases = listOf(
            "甲".repeat(31) + "😀尾" to "甲".repeat(31) + "😀",
            "😀".repeat(33) to "😀".repeat(32),
            "示例\u202E运营\u202C商\u2028\u2029" to "示例运营商",
            "\u2066示例\u2069" to "示例",
            "7".repeat(11) to "归属运营商未知",
            "3" to "3",
        )
        for ((input, expected) in cases) {
            val row = project(fixture().copy(homeCarrierLabel = input))
            assertEquals("SIM 2 · 🇺🇸 $expected", row.title)
            assertEquals(unchanged.summary, row.summary)
            assertEquals(unchanged.action, row.action)
        }
    }

    @Test fun homeSpnAndCountryDoNotFollowLegacyCarrier() {
        val original = fixture().copy(homeCarrierLabel = "T-Mobile")
        assertEquals("SIM 2 · 🇺🇸 T-Mobile", project(original).title)
        assertEquals("SIM 2 · 🇺🇸 T-Mobile", project(original.copy(carrierLabel = "Another roaming label")).title)
        assertEquals("SIM 2 · 🇨🇳 T-Mobile", project(original.copy(mcc = "460", mnc = "01")).title)
        assertEquals("PLMN 460/01\n隧道 状态未知 · IMS 未\u2060注\u2060册",
            project(original.copy(mcc = "460", mnc = "01")).summary)
    }

    @Test fun countryIsNeverGuessedAndIsoMustBeTwoUppercaseAsciiLetters() {
        for (mcc in listOf(null, "999", "US", "31", "3100")) {
            assertEquals("SIM 2 · T-Mobile", project(fixture().copy(mcc = mcc, homeCarrierLabel = "T-Mobile")).title)
        }
        assertEquals("🇺🇸", homeCountryFlag("US"))
        for (iso in listOf(null, "", "U", "USA", "us", "U1", "ÜS")) {
            assertEquals(null, homeCountryFlag(iso))
        }
    }

    @Test fun evaluatorSanitizesOnlyHomeNameWithoutDroppingTheSim() {
        for (name in listOf(null, "", "1".repeat(15), "imsi=private", "/data/private", "key-home")) {
            val state = SimControlEvaluator.join(
                listOf(SimActiveJoinInput("key-home", 1, carrierName = "Legacy", mcc = "310", mnc = "260", homeCarrierLabel = name)),
                emptyList(), OpaqueIccidHandleStore(),
            )
            val instance = assertIs<SimControlUiState.Ready>(state).instances.single()
            val row = project(instance)
            assertEquals("SIM 2 · 🇺🇸 归属运营商未知", row.title)
            assertEquals("Legacy", instance.carrierLabel)
            assertEquals("PLMN 310/260\n运营商资料已就绪，可以创建现有配置。", row.summary)
            assertEquals("未配置", row.statusText)
            // S3 C3 also carries the real lifecycle so a completed target clears its old failure.
            assertEquals(HomeActionPresentation(HomeActionId.PROVISION, instance.handle, "创建配置", true, false,
                lifecycle = EngineLifecycle.CONFIG_MISSING), row.action)
            assertEquals(SemanticTone.ERROR, row.tone)
        }
    }

    @Test fun evaluatorPropagatesNativeNameAndKeepsLegacyFields() {
        val state = SimControlEvaluator.join(
            listOf(SimActiveJoinInput("key-home", 1, carrierName = "Legacy", displayName = "Custom", mcc = "310", mnc = "260", homeCarrierLabel = " T-Mobile\n")),
            emptyList(), OpaqueIccidHandleStore(),
        )
        val instance = assertIs<SimControlUiState.Ready>(state).instances.single()
        assertEquals("T-Mobile", instance.homeCarrierLabel)
        assertEquals("Legacy", instance.carrierLabel)
        assertEquals("SIM 2 · Legacy · 310 / 260", instance.displayLabel)
        assertEquals("SIM 2 · 🇺🇸 T-Mobile", project(instance).title)
    }

    @Test fun legacyCarrierIsNotTrustedAsHomeCarrier() {
        val row = project(fixture())
        assertEquals("SIM 2 · 🇺🇸 归属运营商未知", row.title)
        assertEquals("PLMN 310/260\n隧道 状态未知 · IMS 未\u2060注\u2060册", row.summary)
        assertEquals("已停止", row.statusText)
        assertEquals(SemanticTone.NEUTRAL, row.tone)
        // S3 C3: keep the full action contract, including its new confirmed lifecycle projection.
        assertEquals(HomeActionPresentation(HomeActionId.START, SimHandle("sim-test"), "启动引擎", true, false,
            lifecycle = EngineLifecycle.STOPPED), row.action)
    }

    private fun fixture() = SimControlInstance(
        handle = SimHandle("sim-test"), displayLabel = "Custom label",
        presence = SimPresence.ACTIVE_MATCHED, slotIndex = 1, carrierLabel = "Roaming carrier",
        mcc = "310", mnc = "260", lifecycle = EngineLifecycle.STOPPED, pid = 42,
        canStart = true, canStop = false, canApply = true,
    )

    private fun project(instance: SimControlInstance) = homeDashboardPresentation(
        RegistrationUiState.Empty, SimControlUiState.Ready(listOf(instance)),
        EnvironmentDiagnosticsUiState.Loading, 0,
    ).actions.single()
}
