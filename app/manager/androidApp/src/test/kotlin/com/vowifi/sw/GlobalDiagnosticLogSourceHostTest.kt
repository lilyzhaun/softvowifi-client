@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.app.Application
import android.os.UserManager
import com.vowifi.sw.manager.data.AndroidOperationalLogSource
import com.vowifi.sw.manager.data.AndroidDiagnosticLog
import com.vowifi.sw.manager.data.AsyncDiagnosticWriter
import com.vowifi.sw.manager.data.AndroidSuResult
import com.vowifi.sw.manager.data.LocalDiagnosticLogReader
import com.vowifi.sw.manager.data.LogCommand
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GlobalDiagnosticLogSourceHostTest {
    // Robolectric recreates Application/filesDir between tests but retains instrumented statics.
    // Model a real positive PID and release only this test's process-owned writer, not its files.
    private val writerField get() = AndroidDiagnosticLog::class.java.getDeclaredField("writer").apply { isAccessible = true }

    @Before fun configureProcess() {
        releaseProcessWriter()
        ShadowProcess.setPid(1234)
    }

    @After fun releaseProcessWriter() = runBlocking {
        (writerField.get(null) as? AsyncDiagnosticWriter)?.let {
            it.close()
            withTimeout(5000) { it.awaitClosed() }
        }
        writerField.set(null, null)
    }

    private suspend fun settledReader(app: Application): LocalDiagnosticLogReader {
        val reader = LocalDiagnosticLogReader(app)
        val writer = writerField.get(null) as? AsyncDiagnosticWriter ?: error("process writer did not start")
        writer.close()
        withTimeout(5000) { writer.awaitClosed() }
        return reader
    }

    @Test fun rootDenialCannotHideTheIndependentGlobalSource() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        val source = AndroidOperationalLogSource(
            LogCommand { AndroidSuResult(false, 1, "Permission denied", false) },
            CoroutineScope(SupervisorJob() + Dispatchers.Default), local = settledReader(app),
        )
        try {
            val state = withTimeout(5000) {
                source.state.first { it is OperationalLogUiState.Ready && !it.loadInfo.reading }
            } as OperationalLogUiState.Ready
            assertEquals(LocalDiagnosticLogReader.ID, state.selectedFileId)
            assertEquals(listOf("app.jsonl"), state.files.map { it.name })
            assertTrue(state.entries.any { it.safeDetail.contains("process_started") })
            assertTrue(state.loadInfo.issue.orEmpty().contains("Root 后端日志读取失败"))
        } finally { source.close() }
    }

    @Test fun emptyModuleCatalogWithoutAnySimOrEngineStillOffersGlobalDiagnostics() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        val source = AndroidOperationalLogSource(
            LogCommand { AndroidSuResult(false, 0, """{"ok":true,"rotatedCount":0,"files":[]}""", false) },
            CoroutineScope(SupervisorJob() + Dispatchers.Default), local = settledReader(app),
        )
        try {
            val state = withTimeout(5000) {
                source.state.first { it is OperationalLogUiState.Ready && !it.loadInfo.reading }
            } as OperationalLogUiState.Ready
            assertTrue(state.files.any { it.id == LocalDiagnosticLogReader.ID })
            assertEquals(LocalDiagnosticLogReader.ID, state.selectedFileId)
            assertTrue(state.entries.any { it.safeDetail.contains("process_started") })
        } finally { source.close() }
    }
}
