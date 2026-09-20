package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineRuntimeStatus
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.RegistrationEvent
import com.vowifi.sw.manager.model.RegistrationSnapshot
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomePresentationStateTest {
    @Test
    fun loadingEmptyNoConfigPermissionFailurePartialAndRegisteredRemainDistinct() {
        val cases = listOf(
            case(RegistrationUiState.Loading, SimControlUiState.Loading, EnvironmentDiagnosticsUiState.Loading, "正在汇总 VoWiFi 状态"),
            case(RegistrationUiState.Empty, SimControlUiState.Ready(emptyList()), readyDiagnostics(), "尚无 IMS 注册事件"),
            case(RegistrationUiState.Empty, SimControlUiState.Ready(listOf(unconfigured())), readyDiagnostics(), "需要创建 SIM 配置"),
            case(RegistrationUiState.Empty, SimControlUiState.PermissionDenied("raw"), readyDiagnostics(), "无法确认引擎状态"),
            case(RegistrationUiState.ReadError("raw"), SimControlUiState.Failure("raw"), EnvironmentDiagnosticsUiState.Failure("raw"), "状态读取失败"),
            case(registration("REGISTERED", "UNREGISTERED"), SimControlUiState.Ready(listOf(configured())), readyDiagnostics(), "部分 SIM 已注册"),
            case(registration("REGISTERED"), SimControlUiState.Ready(listOf(configured())), readyDiagnostics(), "IMS 已注册"),
        )

        assertEquals(
            cases.map { it.expectedTitle },
            cases.map { input -> presentation(input).hero.title },
        )
    }

    @Test
    fun longCjkCarrierWrapsAtTwoHundredPercentWithoutVerticalLabelContract() {
        val longCarrier = "超长中文运营商名称用于验证二倍字体换行不会把生命周期标签挤成竖排孤字".repeat(3)
        val state = SimControlUiState.Ready(listOf(configured(displayLabel = "SIM 1 · $longCarrier").copy(carrierLabel = longCarrier)))

        val action = homeDashboardPresentation(
            RegistrationUiState.Empty,
            state,
            readyDiagnostics(),
            NOW_MS,
        ) { "8月25日" }.actions.single()

        assertTrue(action.title.length <= 48)
        assertTrue(action.titleFlow.softWrap)
        assertEquals(null, action.titleFlow.maxLines)
        assertTrue(action.stackAtLargeFont)
        assertTrue(action.geometry.stackAtLargeFont)
        assertEquals(1.5f, action.geometry.largeFontThreshold)
        assertEquals(48, action.geometry.minimumTouchDp)
        assertFalse(action.statusText.contains("\n"))
        assertFalse(action.action.label.contains("\n"))
    }

    @Test
    fun twoHundredPercentHeroUsesShortGroupedCjkWithoutOrphanStateTokens() {
        val unconfigured = homeDashboardPresentation(
            RegistrationUiState.Empty,
            SimControlUiState.Ready(listOf(unconfigured())),
            readyDiagnostics(),
            NOW_MS,
        ) { "8月25日" }
        val available = homeDashboardPresentation(
            registration("REGISTERED"),
            SimControlUiState.Ready(
                listOf(
                    configured().copy(
                        lifecycle = EngineLifecycle.RUNNING,
                        runtime = runtime(),
                        canStart = false,
                        canStop = true,
                    ),
                ),
            ),
            readyDiagnostics(),
            NOW_MS,
        ) { "8月25日" }

        val unconfiguredText = homeHeroText(unconfigured.hero, fontScale = 2f)
        val availableText = homeHeroText(available.hero, fontScale = 2f)

        assertEquals("SIM 未\u2060配\u2060置", unconfiguredText.title)
        assertEquals(listOf("活动 SIM 尚无配\u2060置", "创建后才可启动引擎"), unconfiguredText.summaryLines)
        assertEquals("VoWiFi 可\u2060用", availableText.title)
        assertEquals(listOf("IMS 已\u2060注\u2060册", "隧道已\u2060连\u2060接", "环境检查通过"), availableText.summaryLines)
        val orphanTokens = setOf("配置", "可用", "注册", "连接")
        assertTrue((unconfiguredText.visibleLines + availableText.visibleLines).none { it.replace("\u2060", "") in orphanTokens })
    }

    @Test
    fun normalHeroKeepsRegularText() {
        val presentation = homeDashboardPresentation(
            RegistrationUiState.Empty,
            SimControlUiState.Ready(listOf(unconfigured())),
            readyDiagnostics(),
            NOW_MS,
        ) { "8月25日" }

        val text = homeHeroText(presentation.hero, fontScale = 1f)

        assertEquals("需要创建 SIM 配置", text.title)
        assertEquals(listOf("至少一张活动 SIM 尚无可用配置。"), text.summaryLines)
    }

    @Test
    fun metricAndRowTonesRemainSemanticAcrossStoppedStartingAndRunningStates() {
        val presentation = homeDashboardPresentation(
            registration("REGISTERED", "UNREGISTERED"),
            SimControlUiState.Ready(
                listOf(
                    configured("SIM 1").copy(lifecycle = EngineLifecycle.STARTING, canStart = false),
                    configured("SIM 2", SimHandle("sim-2")).copy(lifecycle = EngineLifecycle.RUNNING, canStart = false, canStop = true),
                ),
            ),
            readyDiagnostics(),
            NOW_MS,
        ) { "8月25日" }

        assertEquals(listOf("1", "1"), presentation.metrics.map { it.value })
        assertEquals(listOf(SemanticTone.WARNING, SemanticTone.NEUTRAL), presentation.actions.map { it.tone })
        assertEquals(SemanticTone.SUCCESS, presentation.metrics.first().tone)
        assertEquals(SemanticTone.SUCCESS, presentation.metrics.last().tone)
    }

    @Test
    fun dualConfiguredRowsRenderWithoutDualVowifiValidationClaim() {
        val presentation = homeDashboardPresentation(
            registration("REGISTERED", "REGISTERED"),
            SimControlUiState.Ready(listOf(configured("SIM 1"), configured("SIM 2", SimHandle("sim-2")))),
            readyDiagnostics(),
            NOW_MS,
        ) { "8月25日" }

        assertEquals(2, presentation.actions.size)
        assertTrue(presentation.hero.summary.contains("2 张 SIM"))
        assertTrue(presentation.visibleStrings.none { "双卡 VoWiFi" in it || "双卡VoWiFi" in it || "已验证" in it })
    }

    @Test
    fun simActionSummaryUsesNonBreakingPhraseForUnregisteredIms() {
        val configuredInstance = configured("SIM 1 · Lebara")
        val presentation = homeDashboardPresentation(
            RegistrationUiState.Empty,
            SimControlUiState.Ready(listOf(configuredInstance)),
            readyDiagnostics(),
            NOW_MS,
        ) { "8月25日" }

        val action = presentation.actions.single()
        assertTrue(action.summary.contains("IMS 未\u2060注\u2060册"))
        assertFalse(action.summary.contains("IMS 未注册"))
    }

    @Test
    fun rawIdentityPathsEpochsAndTokensNeverReachPresentation() {
        val hostile = "ICCID ${"8".repeat(19)} IMSI ${"4".repeat(15)} MSISDN ${"5".repeat(11)} subId=7 /data/adb/vowifi token=secret"
        val hostileInstance = configured(displayLabel = hostile).copy(carrierLabel = hostile, message = hostile)
        val presentation = homeDashboardPresentation(
            RegistrationUiState.ReadError(hostile),
            SimControlUiState.Ready(listOf(hostileInstance), message = hostile),
            EnvironmentDiagnosticsUiState.Failure(hostile),
            NOW_MS,
        ) { hostile }
        val rendered = presentation.visibleStrings.joinToString(" ").lowercase()

        listOf("iccid", "imsi", "msisdn", "subid", "/data/", "/proc/", "token", NOW_MS.toString()).forEach { forbidden ->
            assertFalse(rendered.contains(forbidden.lowercase()), forbidden)
        }
        assertFalse(Regex("\\d{13,}").containsMatchIn(rendered))
    }

    private fun presentation(input: Case) = homeDashboardPresentation(
        input.registration,
        input.engine,
        input.diagnostics,
        NOW_MS,
    ) { "8月25日" }

    private fun case(
        registration: RegistrationUiState,
        engine: SimControlUiState,
        diagnostics: EnvironmentDiagnosticsUiState,
        expectedTitle: String,
    ) = Case(registration, engine, diagnostics, expectedTitle)

    private fun registration(vararg states: String): RegistrationUiState.Data {
        val events = states.mapIndexed { index, state -> RegistrationEvent(NOW_MS - index * 1_000, index + 1, state) }
        return RegistrationUiState.Data(RegistrationSnapshot(events, events, emptyList()))
    }

    private fun configured(displayLabel: String = "SIM 1 · Carrier", handle: SimHandle = SimHandle("sim-1")) = SimControlInstance(
        handle, displayLabel, SimPresence.ACTIVE_MATCHED, 0, "Carrier", "460", "00", EngineLifecycle.STOPPED,
        canStart = true, canStop = false, canApply = false,
    )

    private fun unconfigured() = SimControlInstance(
        SimHandle("sim-1"), "SIM 1", SimPresence.ACTIVE_UNCONFIGURED, 0, "Carrier", "460", "00", EngineLifecycle.CONFIG_MISSING,
        canStart = false, canStop = false, canApply = false, canProvision = true,
    )

    private fun readyDiagnostics() = EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.PASS, emptyList())

    private fun runtime() = EngineRuntimeStatus(
        tunnel = "CONNECTED",
        reconnects = 0,
        ims = "REGISTERED",
        call = "IDLE",
        transport = "UDP",
        pcscf = emptyList(),
    )

    private data class Case(
        val registration: RegistrationUiState,
        val engine: SimControlUiState,
        val diagnostics: EnvironmentDiagnosticsUiState,
        val expectedTitle: String,
    )

    private companion object {
        const val NOW_MS = 1_800_000_000_000L
    }
}
