package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AboutRuntimeInformationTest {
    @Test fun originalDiagnosticsProjectionKeepsItsStructuredModuleVersion() {
        val snapshot = EnvironmentDiagnosticsSnapshot(true, module("1.2.3", 12), RootDiagnostics(true),
            ConfigDiagnostics(1), ObserverDiagnostics(true, true, true), EngineDiagnostics(1, 1, 0, 0, 0, 0))
        val projected = com.vowifi.sw.manager.data.EnvironmentDiagnosticsEvaluator.ready(snapshot, StockWfcDiagnostics.Unreadable)
        assertEquals(snapshot.module, projected.module, "不能从诊断detail文案反向解析或重新读module.prop")
    }

    @Test fun unknownModuleAndUnknownRuntimeDoNotPretendToMatchOrMeanZeroRunningInstances() {
        val rows = aboutInformationRows("1.2.3")
        assertEquals("未知", rows.single { it.title == "Root 后端版本" }.summary)
        assertFalse(rows.any { it.title == "引擎标识" || it.title == "运行实例" }, "关于信息不再混入引擎诊断")
        val engine = aboutEngineInformationRows(EngineIntegritySnapshot())
        assertEquals("无法读取", engine.single { it.title == "引擎标识" }.summary)
        assertEquals("无法读取", engine.single { it.title == "运行实例" }.summary)
        assertFalse(rows.any { "版本不一致" in it.summary || "没有运行实例" in it.summary })
    }

    @Test fun moduleVersionComparisonUsesTheRealVersionNameAndCodeIndependently() {
        for (module in listOf(module("1.2.4", 12), module("1.2.3", 13))) {
            val rows = aboutInformationRows("1.2.3", appVersionCode = 12, module = module)
            assertEquals("App 与 Root 后端版本不一致", rows.single { it.title == "版本检查" }.summary)
            assertTrue(rows.single { it.title == "Root 后端版本" }.summary.contains(module.version!!))
            assertTrue(rows.single { it.title == "Root 后端版本" }.summary.contains(module.versionCode.toString()))
        }
        val matching = aboutInformationRows("1.2.3", appVersionCode = 12, module = module("1.2.3", 12))
        assertFalse(matching.any { "版本不一致" in it.summary })
        val unknown = aboutInformationRows("1.2.3", appVersionCode = 12, module = module(null, null))
        assertEquals("未知", unknown.single { it.title == "Root 后端版本" }.summary)
        assertFalse(unknown.any { "版本不一致" in it.summary })
        for (unsafe in listOf("/data/private/version", "4".repeat(19), "imsi=${"4".repeat(15)}")) {
            val rows = aboutInformationRows("1.2.3", appVersionCode = 12, module = module(unsafe, 12))
            assertFalse(rows.any { unsafe in it.summary || "版本不一致" in it.summary })
            assertTrue(rows.single { it.title == "Root 后端版本" }.summary.contains("未知"))
        }
    }

    @Test fun integrityShowsOnlyTheFingerprintAndEachRealPidMatchWithoutInferringASim() {
        val rows = aboutEngineInformationRows(EngineIntegritySnapshot("a".repeat(12), listOf(
            RunningEngineIntegrity(101, EngineExecutableMatch.SAME),
            RunningEngineIntegrity(202, EngineExecutableMatch.DIFFERENT),
            RunningEngineIntegrity(null, EngineExecutableMatch.UNREADABLE))))
        assertEquals("a".repeat(12), rows.single { it.title == "引擎标识" }.summary)
        assertEquals("与文件一致", rows.single { it.title == "运行实例 PID 101" }.summary)
        assertEquals("与文件不一致", rows.single { it.title == "运行实例 PID 202" }.summary)
        assertEquals("无法读取", rows.single { it.title == "运行实例 3" }.summary)
        assertFalse(rows.any { "SIM" in it.title })
        val idle = aboutEngineInformationRows(EngineIntegritySnapshot("a".repeat(12), emptyList()))
        assertEquals("当前没有运行实例", idle.single { it.title == "运行实例" }.summary)
    }

    private fun module(version: String?, code: Int?) = ModuleDiagnostics(true, "vowifi_sw", version, code,
        ModuleMarkers(false, false, false))
}
