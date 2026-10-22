package com.vowifi.sw

import android.app.Application
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],application=Application::class,manifest=Config.NONE)
class DiagnosticSafExportHostTest {
    @Test fun actualSafEntryProducesArchiveEvenWithoutRootInsteadOfPageText() {
        Robolectric.buildActivity(MainActivity::class.java).use {controller->
            val activity=controller.get();val bytes=ByteArrayOutputStream();var closed=false
            val uri=Uri.parse("content://diagnostic-export-test/archive.jsonl")
            shadowOf(activity.contentResolver).registerOutputStream(uri,object:OutputStream(){
                override fun write(value:Int){bytes.write(value)}
                override fun write(data:ByteArray,offset:Int,length:Int){bytes.write(data,offset,length)}
                override fun close(){closed=true}
            })
            val method=MainActivity::class.java.getDeclaredMethod("writeDiagnosticExport",Uri::class.java,Long::class.javaPrimitiveType,Long::class.javaPrimitiveType).apply{isAccessible=true}
            assertEquals(true,method.invoke(activity,uri,0L,System.currentTimeMillis()))
            val text=bytes.toString("UTF-8")
            assertTrue(closed);assertTrue(text.contains("\"diagnostic_archive\":1"));assertTrue(text.contains("\"root_complete\":false"))
            assertTrue(text.contains("\"capture_completeness_proven\":false"))
            assertFalse(text.contains("SoftVoWiFi 运行日志"))
            assertFalse(activity.filesDir.resolve("diagnostic-export-v1/export.jsonl").exists())
        }
    }
}
