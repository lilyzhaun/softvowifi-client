package com.vowifi.sw.manager.data

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.os.UserManager

/** Process-owned CE log, independent of root, subscription inventory and network publication. */
object AndroidDiagnosticLog {
    @Volatile private var writer: AsyncDiagnosticWriter? = null

    @Synchronized fun start(context: Context) {
        if (writer != null) return
        val app = context.applicationContext
        if (app.isDeviceProtectedStorage || app.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        // filesDir can create a directory: evaluate it only in the IO worker, never in emit().
        writer = AsyncDiagnosticWriter(directory = { app.filesDir?.toPath()?.let { root ->
            DiagnosticFiles.clearLegacyInAppStorage(root)
            DiagnosticFiles.directoryInAppStorage(root)
        } },
            pid = Process.myPid(), monotonic = SystemClock::elapsedRealtime)
        emit(DiagnosticEvent(DiagnosticComponent.APP, DiagnosticLevel.INFO, DiagnosticName.PROCESS_STARTED,
            DiagnosticStage.PROCESS, DiagnosticResult.OBSERVED))
    }

    fun emit(event: DiagnosticEvent): Boolean = writer?.emit(event) ?: false
    internal fun status(): DiagnosticWriterStatus? = writer?.status()
}
