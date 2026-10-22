package com.vowifi.sw.manager.data

import android.content.Context
import java.io.OutputStream

object AndroidDiagnosticExport {
    fun write(context: Context, from: Long, to: Long, destination: OutputStream) {
        val root = context.applicationContext.filesDir.toPath().toRealPath()
        val stream = AndroidRootDiagnosticStream()
        DiagnosticExportSlot(context.applicationContext.cacheDir.toPath().toRealPath().resolve("exports")).export(destination) { output ->
            val status=AndroidDiagnosticLog.status()
            DiagnosticArchive({ DiagnosticFiles.directoryInAppStorage(root) }, appHealthy=status!=null && status.failure==null,
                root=stream::read).write(from,to,output)
        }
    }
    fun cleanup(context: Context) {
        val root=context.applicationContext.cacheDir.toPath().toRealPath()
        DiagnosticExportSlot(root.resolve("exports")).cleanup()
    }
}
