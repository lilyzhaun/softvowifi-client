package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.DiagnosticCheckId
import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.DiagnosticCheck
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.StockWfcCard
import com.vowifi.sw.manager.model.StockWfcDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnvironmentDiagnosticsEvaluatorTest {
    @Test
    fun healthySnapshotIsReadyWithAllPass() {
        val ready = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            evaluate(healthyJson()),
        )

        assertEquals(DiagnosticCheckStatus.PASS, ready.severity)
        assertEquals(DiagnosticCheckId.entries.toList(), ready.checks.map { it.id })
        assertTrue(ready.checks.all { it.status == DiagnosticCheckStatus.PASS })
        val observer = ready.checks.single { it.id == DiagnosticCheckId.OBSERVER }
        assertEquals("前台运行中", observer.detail)
        val engine = ready.checks.single { it.id == DiagnosticCheckId.ENGINE }
        assertEquals("实例 1 · 运行 1", engine.detail)
    }

    @Test
    fun rootCopyUsesConsistentCapitalization() {
        val healthy = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            evaluate(healthyJson(capable = true)),
        ).checks.single { it.id == DiagnosticCheckId.ROOT }
        val warning = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            evaluate(healthyJson(capable = false)),
        ).checks.single { it.id == DiagnosticCheckId.ROOT }

        assertEquals("Root 权限", healthy.title)
        assertEquals("已确认 Root 能力", healthy.detail)
        assertEquals("当前会话未确认 Root 能力", warning.detail)
        assertTrue(warning.guidance.orEmpty().contains("Root 授权"))
    }

    @Test
    fun markersConfigZeroDegradedObserverAndUnreadableWfcAreWarnings() {
        val ready = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            evaluate(
                healthyJson(
                    disable = true,
                    configCount = 0,
                    instanceCount = 0,
                    runningCount = 0,
                    stoppedCount = 0,
                    packagePid = true,
                    processRecord = true,
                    foreground = false,
                ),
                StockWfcDiagnostics.Unreadable,
            ),
        )

        assertEquals(DiagnosticCheckStatus.WARN, ready.severity)
        assertEquals(DiagnosticCheckStatus.WARN, statusOf(ready, DiagnosticCheckId.MODULE))
        assertEquals(DiagnosticCheckStatus.WARN, statusOf(ready, DiagnosticCheckId.CONFIG))
        assertEquals(DiagnosticCheckStatus.WARN, statusOf(ready, DiagnosticCheckId.OBSERVER))
        val observer = ready.checks.single { it.id == DiagnosticCheckId.OBSERVER }
        assertEquals("服务状态异常", observer.detail)
        assertEquals("打开应用重试。\n仅执行检查。", observer.guidance)
        assertEquals(DiagnosticCheckStatus.WARN, statusOf(ready, DiagnosticCheckId.ENGINE))
        assertEquals(DiagnosticCheckStatus.WARN, statusOf(ready, DiagnosticCheckId.WFC))
        assertEquals(DiagnosticCheckStatus.PASS, statusOf(ready, DiagnosticCheckId.ROOT))
    }

    @Test
    fun missingModuleAndAbsentObserverAreFailures() {
        val ready = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            evaluate(
                healthyJson(
                    installed = false,
                    packagePid = false,
                    processRecord = false,
                    foreground = false,
                ),
            ),
        )

        assertEquals(DiagnosticCheckStatus.FAIL, ready.severity)
        assertEquals(DiagnosticCheckStatus.FAIL, statusOf(ready, DiagnosticCheckId.MODULE))
        assertEquals(DiagnosticCheckStatus.FAIL, statusOf(ready, DiagnosticCheckId.OBSERVER))
    }

    @Test
    fun updateMarkerWarnsWithoutTreatingItAsDisableOrRemove() {
        val ready = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            evaluate(healthyJson(update = true)),
        )
        val module = ready.checks.single { it.id == DiagnosticCheckId.MODULE }
        val guidance = assertNotNull(module.guidance)

        assertEquals(DiagnosticCheckStatus.WARN, module.status)
        assertTrue(module.detail.contains("待更新"))
        assertFalse(module.detail.contains("停用"))
        assertFalse(module.detail.contains("待移除"))
        assertTrue(guidance.contains("下次"))
        assertFalse(guidance.contains("取消"))
        assertFalse(guidance.contains("KernelSU"))
        assertPrivate(module.detail)
        assertPrivate(guidance)
    }

    @Test
    fun disableAndRemoveMarkersListMeaningsAndDirectToKernelSu() {
        val ready = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            evaluate(healthyJson(disable = true, remove = true)),
        )
        val module = ready.checks.single { it.id == DiagnosticCheckId.MODULE }
        val guidance = assertNotNull(module.guidance)

        assertEquals(DiagnosticCheckStatus.WARN, module.status)
        assertTrue(module.detail.contains("停用"))
        assertTrue(module.detail.contains("待移除"))
        assertFalse(module.detail.contains("待更新"))
        assertTrue(guidance.contains("KernelSU"))
        assertPrivate(module.detail)
        assertPrivate(guidance)
    }

    @Test
    fun engineTransitionAndConfigMissingAreWarnings() {
        val warnings = listOf(
            engineResult(healthyJson(instanceCount = 1, runningCount = 0, startingCount = 1)),
            engineResult(healthyJson(instanceCount = 1, runningCount = 0, stoppingCount = 1)),
            engineResult(healthyJson(instanceCount = 1, runningCount = 0, configMissingCount = 1)),
        )
        val expectedDetails = listOf(
            "状态需关注：共 1 个实例，启动中 1",
            "状态需关注：共 1 个实例，停止中 1",
            "状态需关注：共 1 个实例，缺配置 1",
        )

        warnings.zip(expectedDetails).forEach { (engine, expectedDetail) ->
            assertEquals(DiagnosticCheckStatus.WARN, engine.status)
            assertEquals(expectedDetail, engine.detail)
            assertPrivate(engine.detail)
        }
    }

    @Test
    fun engineObservedTextContainsCountsOnly() {
        val engine = engineResult(
            healthyJson(instanceCount = 1, runningCount = 0, stoppedCount = 1),
        )

        assertEquals(DiagnosticCheckStatus.PASS, engine.status)
        assertEquals("实例 1 · 停止 1", engine.detail)
        assertPrivate(engine.detail)
    }

    @Test
    fun readyCopyOmitsSensitiveIdentifiersAndLongDigitRuns() {
        val ready = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            evaluate(HEALTHY_WITH_SECRETS),
        )

        ready.checks.forEach { check ->
            assertPrivate(check.title)
            assertPrivate(check.detail)
            check.guidance?.let(::assertPrivate)
        }
    }

    @Test
    fun warnAndFailGuidanceIsPresentAndNonMutating() {
        val ready = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            evaluate(
                healthyJson(
                    installed = false,
                    remove = true,
                    configCount = 0,
                    packagePid = false,
                    processRecord = false,
                    foreground = false,
                ),
                StockWfcDiagnostics.Unreadable,
            ),
        )

        ready.checks.filter { it.status != DiagnosticCheckStatus.PASS }.forEach { check ->
            val guidance = assertNotNull(check.guidance, "missing guidance for ${check.id}")
            MUTATING_TOKENS.forEach { token ->
                assertFalse(guidance.contains(token), "${check.id} guidance mutates via $token")
            }
        }
    }

    @Test
    fun stockWfcOffOnEveryActiveSimIsPassAndIgnoresLegacyGlobalKey() {
        val ready = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            evaluate(healthyJson(wfc = """{"readable":true,"global":1}"""), STOCK_WFC_OFF),
        )
        val wfc = ready.checks.single { it.id == DiagnosticCheckId.WFC }

        assertEquals(DiagnosticCheckStatus.PASS, wfc.status)
        assertEquals("SIM1、SIM2 均未开启", wfc.detail)
        assertEquals(null, wfc.guidance)
    }

    @Test
    fun stockWfcEnabledOnAnyActiveSimWarnsAboutConflict() {
        val ready = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            evaluate(
                healthyJson(wfc = """{"readable":true,"global":0}"""),
                StockWfcDiagnostics.Readable(
                    listOf(StockWfcCard(slot = 1, enabled = true), StockWfcCard(slot = 0, enabled = false)),
                ),
            ),
        )
        val wfc = ready.checks.single { it.id == DiagnosticCheckId.WFC }
        val guidance = assertNotNull(wfc.guidance)

        assertEquals(DiagnosticCheckStatus.WARN, ready.severity)
        assertEquals(DiagnosticCheckStatus.WARN, wfc.status)
        assertEquals("SIM2 已开启", wfc.detail)
        assertTrue(guidance.contains("冲突"))
        assertTrue(guidance.contains("系统设置"))
        assertTrue(guidance.contains("本页不会改写该设置"))
        assertPrivate(wfc.detail)
        assertPrivate(guidance)
    }

    @Test
    fun stockWfcListsEveryEnabledSimInSlotOrder() {
        val wfc = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            evaluate(
                healthyJson(),
                StockWfcDiagnostics.Readable(
                    listOf(StockWfcCard(slot = 1, enabled = true), StockWfcCard(slot = 0, enabled = true)),
                ),
            ),
        ).checks.single { it.id == DiagnosticCheckId.WFC }

        assertEquals(DiagnosticCheckStatus.WARN, wfc.status)
        assertEquals("SIM1、SIM2 已开启", wfc.detail)
    }

    @Test
    fun stockWfcOffOnSingleActiveSimNamesOnlyThatSim() {
        val wfc = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            evaluate(healthyJson(), StockWfcDiagnostics.Readable(listOf(StockWfcCard(slot = 1, enabled = false)))),
        ).checks.single { it.id == DiagnosticCheckId.WFC }

        assertEquals(DiagnosticCheckStatus.PASS, wfc.status)
        assertEquals("SIM2 未开启", wfc.detail)
    }

    @Test
    fun stockWfcWithoutActiveSimIsPass() {
        val wfc = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            evaluate(healthyJson(), StockWfcDiagnostics.Readable(emptyList())),
        ).checks.single { it.id == DiagnosticCheckId.WFC }

        assertEquals(DiagnosticCheckStatus.PASS, wfc.status)
        assertEquals("未发现活动 SIM", wfc.detail)
    }

    @Test
    fun sourceLevelFailuresStayNonReady() {
        assertIs<EnvironmentDiagnosticsUiState.PermissionDenied>(
            EnvironmentDiagnosticsEvaluator.permissionDenied(),
        )
        assertIs<EnvironmentDiagnosticsUiState.Unavailable>(
            EnvironmentDiagnosticsEvaluator.unavailable(),
        )
        assertIs<EnvironmentDiagnosticsUiState.Failure>(
            EnvironmentDiagnosticsEvaluator.failure(),
        )
        assertIs<EnvironmentDiagnosticsUiState.Unavailable>(
            evaluate(
                """{"ok":false,"code":"USAGE","message":"control.sh list|diagnose"}""",
            ),
        )
        assertIs<EnvironmentDiagnosticsUiState.Failure>(
            evaluate(
                """{"ok":false,"code":"DIRECTORY_FAILED","message":"/data/adb/vowifi"}""",
            ),
        )
        assertIs<EnvironmentDiagnosticsUiState.Failure>(
            evaluate("""{"module":{"installed":true}"""),
        )
        assertIs<EnvironmentDiagnosticsUiState.Failure>(
            evaluate(healthyJson(ok = false)),
        )
        assertIs<EnvironmentDiagnosticsUiState.Loading>(EnvironmentDiagnosticsUiState.Loading)
        val source = object : EnvironmentDiagnosticsSource {
            override val state = MutableStateFlow<EnvironmentDiagnosticsUiState>(
                EnvironmentDiagnosticsUiState.Loading,
            )
            override fun refresh() = Unit
        }
        assertIs<EnvironmentDiagnosticsUiState.Loading>(source.state.value)
    }

    @Test
    fun commandFailureCopyOmitsSensitiveIdentifiers() {
        val failure = assertIs<EnvironmentDiagnosticsUiState.Failure>(
            evaluate(
                """{"ok":false,"code":"CONFIG_MISSING","message":"/data/adb/vowifi/config/8986000000000000001.json"}""",
            ),
        )

        assertPrivate(failure.message)
    }
}

private fun statusOf(
    ready: EnvironmentDiagnosticsUiState.Ready,
    id: DiagnosticCheckId,
): DiagnosticCheckStatus = ready.checks.single { it.id == id }.status

private fun engineResult(output: String): DiagnosticCheck {
    val ready = assertIs<EnvironmentDiagnosticsUiState.Ready>(
        evaluate(output),
    )
    return ready.checks.single { it.id == DiagnosticCheckId.ENGINE }
}

private fun evaluate(
    output: String,
    stockWfc: StockWfcDiagnostics = STOCK_WFC_OFF,
): EnvironmentDiagnosticsUiState = EnvironmentDiagnosticsEvaluator.fromOutput(output, stockWfc)

private val STOCK_WFC_OFF = StockWfcDiagnostics.Readable(
    listOf(StockWfcCard(slot = 0, enabled = false), StockWfcCard(slot = 1, enabled = false)),
)

private val MUTATING_TOKENS = listOf("写入", "删除", "setprop", "pm install")
