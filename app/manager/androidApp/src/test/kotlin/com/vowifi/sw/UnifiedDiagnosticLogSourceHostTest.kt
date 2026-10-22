@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.vowifi.sw

import android.app.Application
import android.os.UserManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.OperationalLogOrigin
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import com.vowifi.sw.manager.model.OperationalLogUiState

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class)
class UnifiedDiagnosticLogSourceHostTest {
    private val writerField get()=AndroidDiagnosticLog::class.java.getDeclaredField("writer").apply {isAccessible=true}
    @Before fun prepare() {release();ShadowProcess.setPid(1234)}
    @After fun release()=runBlocking {
        (writerField.get(null) as? AsyncDiagnosticWriter)?.let {it.close();withTimeout(5000){it.awaitClosed()}}
        writerField.set(null,null)
    }
    private suspend fun app():Application {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        LocalDiagnosticLogReader(app)
        val writer=writerField.get(null) as AsyncDiagnosticWriter
        writer.close();withTimeout(5000){writer.awaitClosed()}
        return app
    }
    @Test fun combinedViewKeepsBothFileEstablishedSources()=runBlocking {
        val app=app()
        val record=DiagnosticRecord(DiagnosticEvent(DiagnosticComponent.TUNNEL,DiagnosticLevel.INFO,DiagnosticName.OBSERVED,
            DiagnosticStage.CONNECT,DiagnosticResult.STARTED),"f47ac10b-58cc-4372-a567-0e02b2c3d479",777,System.currentTimeMillis(),10).bytes().decodeToString()
        val receipt="""{"diagnostic_export":1,"complete":true,"records":1,"invalid":0,"unavailable":0,"legacy_excluded":true,"older_runs_may_lack_dns_io":true}"""+"\n"
        val root=RootDiagnosticLogReader(app,AndroidRootDiagnosticStream(start={ProcessBuilder("sh","-c","printf '%s' '$record$receipt'").start()}))
        val read=root.combined()
        assertTrue(read.tail.lines.any{it.origin==OperationalLogOrigin.DIAGNOSTIC && it.safeDetail.contains("process_started")})
        assertTrue(read.tail.lines.any{it.origin==OperationalLogOrigin.ENGINE && it.safeDetail.contains("connect")})
        assertEquals(read.tail.lines.sortedBy{it.timestamp},read.tail.lines)
        assertNull(read.issue)
    }
    @Test fun rootFailureCannotHideLocalEventsOrBecomeEmptySuccess()=runBlocking {
        val app=app()
        val root=RootDiagnosticLogReader(app,AndroidRootDiagnosticStream(start={ProcessBuilder("sh","-c","exit 1").start()}))
        val read=root.combined()
        assertTrue(read.tail.lines.any{it.safeDetail.contains("process_started")})
        assertTrue(read.issue.orEmpty().contains("Root读取不完整"))
    }
    @Test fun publicSourceCatalogSelectionAndWindowUseProductionState()=runBlocking {
        val app=app()
        val root=RootDiagnosticLogReader(app,AndroidRootDiagnosticStream(start={ProcessBuilder("sh","-c","exit 1").start()}))
        val source=AndroidOperationalLogSource(LogCommand{AndroidSuResult(false,0,"""{"ok":true,"rotatedCount":0,"files":[]}""",false)},
            CoroutineScope(SupervisorJob()+Dispatchers.Default),local=LocalDiagnosticLogReader(app),rootDiagnostics=root)
        try {
            val initial=withTimeout(5000){source.state.first{it is OperationalLogUiState.Ready && !it.loadInfo.reading}} as OperationalLogUiState.Ready
            assertTrue(initial.files.map{it.id}.containsAll(listOf(LocalDiagnosticLogReader.ID,RootDiagnosticLogReader.ID,RootDiagnosticLogReader.ALL_ID)))
            source.setExportWindow(DiagnosticExportWindow.ALL)
            assertEquals(DiagnosticExportWindow.ALL,source.diagnosticExportWindow.value)
            assertEquals(source.diagnosticExportWindow.value,source.exportWindow())
            source.selectFile(RootDiagnosticLogReader.ALL_ID)
            val combined=withTimeout(5000){source.state.first{it is OperationalLogUiState.Ready && !it.loadInfo.reading && it.selectedFileId==RootDiagnosticLogReader.ALL_ID}} as OperationalLogUiState.Ready
            assertTrue(combined.entries.any{it.safeDetail.contains("process_started")})
            assertTrue(combined.loadInfo.issue.orEmpty().contains("Root读取不完整"))
        } finally {source.close()}
    }
}
