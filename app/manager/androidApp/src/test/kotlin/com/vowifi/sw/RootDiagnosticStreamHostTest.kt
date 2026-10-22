@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class RootDiagnosticStreamHostTest {
    @Test fun malformedAndOversizedLinesHaveBoundedMemoryAndNeverReachConsumer() {
        val seen=mutableListOf<String>()
        val result=DiagnosticStreamReader.read(ByteArrayInputStream(("x".repeat(3000)+"\nvalid\npartial").toByteArray()),4096,seen::add)
        assertEquals(listOf("valid"),seen);assertEquals(2,result.invalid)
    }
    @Test fun actualStreamRequiresReceiptAndRejectsAppImpersonation() {
        val event=DiagnosticRecord(DiagnosticEvent(DiagnosticComponent.DNS,DiagnosticLevel.WARN,DiagnosticName.OBSERVED,DiagnosticStage.SOCKET,
            DiagnosticResult.FAILURE,DiagnosticReason.TIMEOUT,family=DiagnosticFamily.IPV4,endpoint="0123456789abcdef0123456789abcdef"),
            "f47ac10b-58cc-4372-a567-0e02b2c3d479",123,1000,10).bytes().decodeToString()
        val receipt="""{"diagnostic_export":1,"complete":true,"records":1,"invalid":0,"unavailable":0,"legacy_excluded":true,"older_runs_may_lack_dns_io":true}"""+"\n"
        var command=""
        fun stream(text:String)=AndroidRootDiagnosticStream(start={command=it;ProcessBuilder("sh","-c","printf '%s' '$text'").start()})
        val records=mutableListOf<DiagnosticRecord>()
        assertTrue(stream(event+receipt).read(0,2000,records::add).complete)
        assertEquals(1,records.size);assertTrue(command.endsWith("logs diagnostics 0 2000"))
        assertFalse(stream(event).read(0,2000){}.complete)
        assertFalse(stream(event.replace("\"dns\"","\"app\"")+receipt).read(0,2000){}.complete)
        assertFalse(stream(event+receipt+event).read(0,2000){}.complete)
    }
    @Test(timeout=8000) fun cancellingBlockedRealRootReaderRetiresItsProcess() {
        val process=AtomicReference<Process>();val started=CountDownLatch(1);val finished=CountDownLatch(1)
        val stream=AndroidRootDiagnosticStream(start={ProcessBuilder("sh","-c","exec sleep 30").start().also{process.set(it);started.countDown()}})
        val worker=Executors.newSingleThreadExecutor()
        try {
            val request=worker.submit{try {stream.read(0,2000){}}finally{finished.countDown()}}
            assertTrue(started.await(2,TimeUnit.SECONDS));request.cancel(true)
            assertTrue("cancel must retire reader",finished.await(2,TimeUnit.SECONDS))
            assertTrue("cancel must retire its actual process",process.get().waitFor(2,TimeUnit.SECONDS))
            assertFalse(process.get().isAlive)
        } finally {worker.shutdownNow();process.get()?.destroyForcibly();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS))}
    }
}
