package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.DiagnosticCheckId
import com.vowifi.sw.manager.model.DiagnosticCheckStatus
import com.vowifi.sw.manager.model.DiagnosticCheck
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
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
            EnvironmentDiagnosticsEvaluator.fromOutput(healthyJson()),
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
            EnvironmentDiagnosticsEvaluator.fromOutput(healthyJson(capable = true)),
        ).checks.single { it.id == DiagnosticCheckId.ROOT }
        val warning = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            EnvironmentDiagnosticsEvaluator.fromOutput(healthyJson(capable = false)),
        ).checks.single { it.id == DiagnosticCheckId.ROOT }

        assertEquals("Root 权限", healthy.title)
        assertEquals("已确认 Root 能力", healthy.detail)
        assertEquals("当前会话未确认 Root 能力", warning.detail)
        assertTrue(warning.guidance.orEmpty().contains("Root 授权"))
    }

    @Test
    fun markersConfigZeroDegradedObserverAndUnreadableWfcAreWarnings() {
        val ready = assertIs<EnvironmentDiagnosticsUiState.Ready>(
            EnvironmentDiagnosticsEvaluator.fromOutput(
                healthyJson(
                    disable = true,
                    configCount = 0,
                    instanceCount = 0,
                    runningCount = 0,
                    stoppedCount = 0,
                    packagePid = true,
                    processRecord = true,
                    foreground = false,
                    wfc = """{"readable":false}""",
                ),
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
            EnvironmentDiagnosticsEvaluator.fromOutput(
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
            EnvironmentDiagnosticsEvaluator.fromOutput(healthyJson(update = true)),
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
            EnvironmentDiagnosticsEvaluator.fromOutput(healthyJson(disable = true, remove = true)),
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
            EnvironmentDiagnosticsEvaluator.fromOutput(HEALTHY_WITH_SECRETS),
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
            EnvironmentDiagnosticsEvaluator.fromOutput(
                healthyJson(
                    installed = false,
                    remove = true,
                    configCount = 0,
                    packagePid = false,
                    processRecord = false,
                    foreground = false,
                    wfc = """{"readable":false}""",
                ),
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
    fun readableWfcValuesArePassAndReportConciseReadOnlyCopy() {
        listOf(0, 1).forEach { value ->
            val ready = assertIs<EnvironmentDiagnosticsUiState.Ready>(
                EnvironmentDiagnosticsEvaluator.fromOutput(
                    healthyJson(wfc = """{"readable":true,"global":$value}"""),
                ),
            )
            val wfc = ready.checks.single { it.id == DiagnosticCheckId.WFC }

            assertEquals(DiagnosticCheckStatus.PASS, wfc.status)
            assertEquals("Android 原生 WFC 只读值：$value", wfc.detail)
            listOf("开启", "关闭", "打开", "关掉").forEach { token ->
                assertFalse(wfc.title.contains(token), "WFC title advises $token")
                assertFalse(wfc.detail.contains(token), "WFC detail advises $token")
                assertFalse(wfc.guidance.orEmpty().contains(token), "WFC guidance advises $token")
            }
            assertPrivate(wfc.title)
            assertPrivate(wfc.detail)
        }
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
            EnvironmentDiagnosticsEvaluator.fromOutput(
                """{"ok":false,"code":"USAGE","message":"control.sh list|diagnose"}""",
            ),
        )
        assertIs<EnvironmentDiagnosticsUiState.Failure>(
            EnvironmentDiagnosticsEvaluator.fromOutput(
                """{"ok":false,"code":"DIRECTORY_FAILED","message":"/data/adb/vowifi"}""",
            ),
        )
        assertIs<EnvironmentDiagnosticsUiState.Failure>(
            EnvironmentDiagnosticsEvaluator.fromOutput("""{"module":{"installed":true}"""),
        )
        assertIs<EnvironmentDiagnosticsUiState.Failure>(
            EnvironmentDiagnosticsEvaluator.fromOutput(healthyJson(ok = false)),
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
            EnvironmentDiagnosticsEvaluator.fromOutput(
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
        EnvironmentDiagnosticsEvaluator.fromOutput(output),
    )
    return ready.checks.single { it.id == DiagnosticCheckId.ENGINE }
}

private val MUTATING_TOKENS = listOf("写入", "删除", "setprop", "pm install")
