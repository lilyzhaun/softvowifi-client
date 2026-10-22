@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DiagnosticArchiveHostTest {
    private fun record(time: Long = 1000) = DiagnosticRecord(DiagnosticEvent(DiagnosticComponent.APP,DiagnosticLevel.INFO,
        DiagnosticName.OBSERVED,DiagnosticStage.PROCESS,DiagnosticResult.OBSERVED),"f47ac10b-58cc-4372-a567-0e02b2c3d479",123,time,10)

    @Test fun exportVisitsAllGenerationsBeyondThePageWindow() {
        val dir=Files.createTempDirectory("diagnostic-full-")
        try {
            for (name in listOf("app.jsonl.2","app.jsonl.1","app.jsonl")) Files.write(dir.resolve(name), (1..120).flatMap { record().bytes().asIterable() }.toByteArray())
            val output=ByteArrayOutputStream()
            DiagnosticArchive({dir}) {_,_,_->RootDiagnosticReceipt(true,0,0,0)}.write(0,2000,output)
            assertEquals("page's 50-line window is not a full export",360,output.toString("UTF-8").lineSequence().count { it.contains("\"source\":\"app\"") })
            assertTrue(output.toString("UTF-8").contains("\"app_complete\":true"))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun windowAndInvalidFramesAreReportedWithoutRawEcho() {
        val dir=Files.createTempDirectory("diagnostic-window-")
        try {
            Files.write(dir.resolve("app.jsonl"),record(100).bytes()+record(1000).bytes()+"private-raw-secret\npartial-secret".toByteArray())
            val output=ByteArrayOutputStream()
            DiagnosticArchive({dir}) {_,_,_->RootDiagnosticReceipt(false,0,0,1)}.write(500,1500,output)
            val text=output.toString("UTF-8")
            assertEquals(1,text.lineSequence().count {it.contains("\"source\":\"app\"")})
            assertTrue(text.contains("\"root_complete\":false"))
            assertTrue(text.contains("\"app_invalid\":2"))
            assertFalse(text.contains("secret"))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun unsafeLocalFileDoesNotHideUsableRootEvents() {
        val dir=Files.createTempDirectory("diagnostic-link-");val target=Files.createTempFile("diagnostic-target-",".txt")
        try {
            Files.writeString(target,"never-export-me")
            Files.createSymbolicLink(dir.resolve("app.jsonl"),target)
            val output=ByteArrayOutputStream()
            DiagnosticArchive({dir}) {_,_,emit->emit(record().copy(event=record().event.copy(component=DiagnosticComponent.ENGINE)));RootDiagnosticReceipt(true,1,0,0)}.write(0,2000,output)
            val text=output.toString("UTF-8")
            assertTrue(text.contains("\"source\":\"root\""));assertTrue(text.contains("\"app_complete\":false"));assertFalse(text.contains("never-export-me"))
        } finally {dir.toFile().deleteRecursively();Files.deleteIfExists(target)}
    }

    @Test fun endpointPseudonymsAreScopedToEachArchiveAndSourcesStayDistinct() {
        val dir=Files.createTempDirectory("diagnostic-salt-")
        try {
            val event=record().copy(event=record().event.copy(component=DiagnosticComponent.DNS,endpoint="0123456789abcdef0123456789abcdef"))
            val archive=DiagnosticArchive({dir}) {_,_,emit->emit(event);emit(event);RootDiagnosticReceipt(true,2,0,0)}
            fun export():List<String> {val output=ByteArrayOutputStream();archive.write(0,2000,output);return output.toString("UTF-8").lineSequence().filter{it.contains("\"source\":\"root\"")}.toList()}
            val first=export();val second=export()
            assertEquals(2,first.size);assertEquals(first[0],first[1]);assertNotEquals(first[0],second[0])
            assertFalse(first[0].contains("0123456789abcdef0123456789abcdef"))
        } finally {dir.toFile().deleteRecursively()}
    }

    @Test fun rotationDuringReadCannotProduceACompleteReceipt() {
        val dir=Files.createTempDirectory("diagnostic-rotation-")
        try {
            for(name in listOf("app.jsonl.2","app.jsonl.1","app.jsonl"))Files.write(dir.resolve(name),record().bytes())
            val bytes=ByteArrayOutputStream();var rotated=false
            val output=object:OutputStream(){
                override fun write(value:Int){bytes.write(value)}
                override fun write(data:ByteArray,offset:Int,length:Int){
                    bytes.write(data,offset,length)
                    if(!rotated && data.copyOfRange(offset,offset+length).decodeToString().contains("\"source\":\"app\"")) {
                        rotated=true
                        Files.move(dir.resolve("app.jsonl.1"),dir.resolve("app.jsonl.2"),REPLACE_EXISTING)
                        Files.move(dir.resolve("app.jsonl"),dir.resolve("app.jsonl.1"),REPLACE_EXISTING)
                        Files.write(dir.resolve("app.jsonl"),record().bytes())
                    }
                }
            }
            DiagnosticArchive({dir}){_,_,_->RootDiagnosticReceipt(true,0,0,0)}.write(0,2000,output)
            assertTrue(rotated)
            assertTrue("a missed generation must not claim complete",bytes.toString("UTF-8").contains("\"app_complete\":false"))
        } finally {dir.toFile().deleteRecursively()}
    }
}
