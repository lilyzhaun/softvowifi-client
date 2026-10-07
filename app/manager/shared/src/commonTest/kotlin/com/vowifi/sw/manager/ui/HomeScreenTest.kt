package com.vowifi.sw.manager.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.DiagnosticCheck
import com.vowifi.sw.manager.model.DiagnosticCheckId
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

class HomeScreenTest {
    @Test
    fun homeLayoutContractDefinesConsistent12DpSpacingAndTopInset() {
        assertEquals(12.dp, homeContentTopInset())
        assertEquals(12, homeLayoutContract.contentTopInsetDp)
        assertEquals(12, homeLayoutContract.cardGapDp)
        assertEquals(12, homeLayoutContract.topGridInternalGapDp)
        assertFalse(homeLayoutContract.factsUseGlobalFactRow)
        assertFalse(homeLayoutContract.factsUseBasicComponent56DpMinimum)
        assertTrue(homeLayoutContract.factsUseHomeLocalCompactRow)
    }

    @Test
    fun homeFactsUseLocalCompactRowWithDeclaredTokensAndNo56DpMinimum() {
        val contract = homeFactsLayoutContract()

        assertTrue(contract.usesHomeLocalCompactRow)
        assertFalse(contract.usesGlobalFactRow)
        assertFalse(contract.usesBasicComponent56DpMinimum)
        assertEquals(12.dp, contract.horizontalPadding)
        assertEquals(8.dp, contract.verticalPadding)
        assertEquals(4.dp, contract.rowGap)
        assertEquals(4.dp, contract.innerLabelValueGap)
        assertEquals(12.sp, contract.labelFontSize)
        assertEquals(16.sp, contract.labelLineHeight)
        assertEquals(14.sp, contract.valueFontSize)
        assertEquals(20.sp, contract.valueLineHeight)
    }

    @Test
    fun noticesPrecedeHeroActionsAndFactsInDeterministicSeverityOrder() {
        val presentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.ReadError("raw observer failure"),
            engineState = SimControlUiState.Failure("raw engine failure"),
            diagnosticsState = EnvironmentDiagnosticsUiState.PermissionDenied("raw permission failure"),
            nowMs = NOW_MS,
            localDateLabel = { "2026-08-25" },
        )

        assertEquals(
            listOf(HomeSectionId.NOTICES, HomeSectionId.HERO, HomeSectionId.ACTIONS, HomeSectionId.FACTS),
            presentation.sections,
        )
        assertEquals(listOf("无法读取 SIM 与引擎状态", "环境检查权限不足"), presentation.notices.map { it.title })
        assertEquals(listOf(SemanticTone.ERROR, SemanticTone.WARNING), presentation.notices.map { it.tone })

        val warnPresentation = homeDashboardPresentation(
            registrationState = RegistrationUiState.Empty,
            engineState = SimControlUiState.Ready(emptyList()),
            diagnosticsState = diagnostics(DiagnosticCheckStatus.WARN),
            nowMs = NOW_MS,
            localDateLabel = { "2026-08-25" },
        )
        val warnNotice = warnPresentation.notices.single { it.title == "环境存在告警" }
        assertEquals("请前往诊断页查看详情。", warnNotice.summary)
    }

    @Test
    fun heroMetadataKeepsTruthfulCounts() {
        val presentation = homeDashboardPresentation(
            registrationState = registration("REGISTERED"),
            engineState = SimControlUiState.Ready(
                listOf(
                    instance(EngineLifecycle.RUNNING, runtime = runtime("CONNECTED", "REGISTERED")),
                    instance(EngineLifecycle.STOPPED, handle = SimHandle("second")),
                ),
            ),
            diagnosticsState = diagnostics(DiagnosticCheckStatus.PASS),
            nowMs = NOW_MS,
            localDateLabel = { "2026-08-25" },
        )

        assertEquals(listOf("1", "1"), presentation.metrics.map { it.value })
        assertEquals(listOf("已注册 IMS", "运行中引擎"), presentation.metrics.map { it.label })
        assertEquals(listOf(SemanticTone.SUCCESS, SemanticTone.SUCCESS), presentation.metrics.map { it.tone })
        assertEquals("1 / 2 张活动 SIM 已注册", presentation.facts.first().value)
    }

    @Test
    fun heroUsesShortCopyOnlyAtLargeFont() {
        val hero = homeHeroPresentation(RegistrationUiState.Empty, SimControlUiState.Ready(emptyList()), diagnostics(DiagnosticCheckStatus.PASS))
        assertEquals(hero.regularText, homeHeroText(hero, fontScale = 1f))
        assertEquals(hero.regularText, homeHeroText(hero, fontScale = 1.49f))
        assertEquals(hero.largeText, homeHeroText(hero, fontScale = 1.5f))
        assertEquals(hero.largeText, homeHeroText(hero, fontScale = 2f))
    }

    @Test
    fun overallHeroUsesRegistrationEngineAndDiagnosticsTruth() {
        val ready = homeDashboardPresentation(
            registrationState = registration("REGISTERED"),
            engineState = SimControlUiState.Ready(listOf(instance(EngineLifecycle.RUNNING, runtime = runtime("CONNECTED", "REGISTERED")))),
            diagnosticsState = diagnostics(DiagnosticCheckStatus.PASS),
            nowMs = NOW_MS,
            localDateLabel = { "2026-08-25" },
        )
        val stale = homeDashboardPresentation(
            registrationState = registration("UNREGISTERED"),
            engineState = SimControlUiState.Ready(listOf(instance(EngineLifecycle.RUNNING, runtime = runtime("CONNECTED", "UNREGISTERED")))),
            diagnosticsState = diagnostics(DiagnosticCheckStatus.PASS),
            nowMs = NOW_MS,
            localDateLabel = { "2026-08-25" },
        )
        val observerLagging = homeDashboardPresentation(
            registrationState = registration("UNREGISTERED"),
            engineState = SimControlUiState.Ready(listOf(instance(EngineLifecycle.RUNNING, runtime = runtime("CONNECTED", "REGISTERED")))),
            diagnosticsState = diagnostics(DiagnosticCheckStatus.PASS),
            nowMs = NOW_MS,
            localDateLabel = { "2026-08-25" },
        )

        assertEquals(SemanticTone.SUCCESS, ready.hero.tone)
        assertEquals("IMS 已注册", ready.hero.title)
        assertEquals("1 / 1 张活动 SIM 已注册", ready.hero.summary)
        assertEquals(SemanticTone.WARNING, stale.hero.tone)
        assertEquals("IMS 尚\u2060未\u2060注\u2060册", stale.hero.title)
        assertEquals("引擎正在运行，软件 IMS 尚未注册。", stale.hero.summary)
        assertEquals(SemanticTone.SUCCESS, observerLagging.hero.tone)
        assertEquals("IMS 已注册", observerLagging.hero.title)
        assertEquals("1 / 1 张活动 SIM 已注册", observerLagging.hero.summary)
    }

    @Test
    fun stoppedAndRunningUnregisteredTitlesKeepTheWholeStatePhraseTogether() {
        val stopped = homeDashboardPresentation(
            registrationState = registration("UNREGISTERED"),
            engineState = SimControlUiState.Ready(listOf(instance(EngineLifecycle.STOPPED))),
            diagnosticsState = diagnostics(DiagnosticCheckStatus.PASS),
            nowMs = NOW_MS,
            localDateLabel = { "2026-08-25" },
        )
        val running = homeDashboardPresentation(
            registrationState = registration("UNREGISTERED"),
            engineState = SimControlUiState.Ready(listOf(instance(EngineLifecycle.RUNNING, runtime = runtime("CONNECTED", "UNREGISTERED")))),
            diagnosticsState = diagnostics(DiagnosticCheckStatus.PASS),
            nowMs = NOW_MS,
            localDateLabel = { "2026-08-25" },
        )

        assertEquals("IMS 尚\u2060未\u2060注\u2060册", stopped.hero.title)
        assertEquals("IMS 尚\u2060未\u2060注\u2060册", running.hero.title)
    }

    @Test
    fun perSimActionsExposeOnlyRealStartStopAndProvisionIds() {
        val start = instance(EngineLifecycle.STOPPED, canStart = true)
        val stop = instance(EngineLifecycle.RUNNING, canStop = true, runtime = runtime("CONNECTED", "REGISTERED"), handle = SimHandle("stop"))
        val provision = instance(
            lifecycle = EngineLifecycle.CONFIG_MISSING,
            presence = SimPresence.ACTIVE_UNCONFIGURED,
            canProvision = true,
            handle = SimHandle("provision"),
        )
        val busy = instance(EngineLifecycle.STARTING, handle = SimHandle("busy"))
        val disabled = instance(EngineLifecycle.STOPPED, handle = SimHandle("disabled"))

        val actions = homeDashboardPresentation(
            registrationState = RegistrationUiState.Empty,
            engineState = SimControlUiState.Ready(listOf(start, stop, provision, busy, disabled)),
            diagnosticsState = diagnostics(DiagnosticCheckStatus.WARN),
            nowMs = NOW_MS,
            localDateLabel = { "2026-08-25" },
        ).actions

        assertEquals(listOf(HomeActionId.START, HomeActionId.STOP, HomeActionId.PROVISION, HomeActionId.START, HomeActionId.START), actions.map { it.action.id })
        assertEquals(listOf(true, true, true, false, false), actions.map { it.action.enabled })
        assertEquals(listOf(false, false, false, true, false), actions.map { it.action.busy })
        assertTrue(actions.all { it.action.minimumTouchDp == 48 })
        assertTrue(actions.all { it.geometry.trailingAction && !it.geometry.fullWidthAction })
        assertTrue(actions.all { it.geometry.minimumRowDp <= 80 })
    }

    @Test
    fun actionDispatchKeepsTypedHandlesAndExactCallbacks() {
        val calls = mutableListOf<String>()
        val handle = SimHandle("opaque-home-handle")

        HomeActionId.entries.forEach { id ->
            dispatchHomeAction(
                HomeActionPresentation(id, handle, "动作", enabled = true, busy = false),
                onStart = { calls += "start:${it.value}" },
                onStop = { calls += "stop:${it.value}" },
                onProvision = { calls += "provision:${it.value}" },
            )
        }

        assertEquals(listOf("start:opaque-home-handle", "stop:opaque-home-handle", "provision:opaque-home-handle"), calls)
    }

    @Test
    fun factsAreBoundedAndDoNotUseObserverEventTime() {
        val relative = homeDashboardPresentation(
            registrationState = registration("REGISTERED", NOW_MS - 5 * 60_000),
            engineState = SimControlUiState.Ready(listOf(instance(EngineLifecycle.RUNNING))),
            diagnosticsState = diagnostics(DiagnosticCheckStatus.PASS),
            nowMs = NOW_MS,
            localDateLabel = { "8月24日" },
        )
        val localized = homeEventTime(NOW_MS - 2 * 24 * 60 * 60_000, NOW_MS) { "8月23日" }

        assertTrue(relative.facts.size <= 4)
        assertEquals(listOf("IMS 注册概况", "活动 SIM", "隧道连接", "环境检查"), relative.facts.map { it.label })
        assertEquals("0 / 1 张活动 SIM 已注册 · 1 张未知", relative.facts.first().value)
        assertEquals("8月23日", localized)
        assertTrue(relative.facts.all { it.flow.softWrap && it.flow.stackAtLargeFont })
    }

    private fun diagnostics(status: DiagnosticCheckStatus) = EnvironmentDiagnosticsUiState.Ready(
        status,
        listOf(DiagnosticCheck(DiagnosticCheckId.MODULE, status, "模块", "bounded")),
    )

    private fun registration(state: String, timestampMs: Long = NOW_MS - 30_000) = RegistrationUiState.Data(
        RegistrationSnapshot(listOf(event(state, timestampMs)), listOf(event(state, timestampMs)), emptyList()),
    )

    private fun event(state: String, timestampMs: Long) = RegistrationEvent(timestampMs, 7, state, transportType = 2)

    private fun runtime(tunnel: String, ims: String) = EngineRuntimeStatus(
        tunnel = tunnel,
        reconnects = 0,
        ims = ims,
        call = "IDLE",
        transport = "UDP",
        pcscf = emptyList(),
    )

    private fun instance(
        lifecycle: EngineLifecycle,
        presence: SimPresence = SimPresence.ACTIVE_MATCHED,
        canStart: Boolean = false,
        canStop: Boolean = false,
        canProvision: Boolean = false,
        runtime: EngineRuntimeStatus? = null,
        handle: SimHandle = SimHandle("start"),
    ) = SimControlInstance(
        handle, "SIM 1 · 很长的运营商名称", presence, 0, "运营商", "460", "00", lifecycle,
        runtime = runtime, canStart = canStart, canStop = canStop, canApply = false, canProvision = canProvision,
    )

    private companion object {
        const val NOW_MS = 1_800_000_000_000L
    }
}
