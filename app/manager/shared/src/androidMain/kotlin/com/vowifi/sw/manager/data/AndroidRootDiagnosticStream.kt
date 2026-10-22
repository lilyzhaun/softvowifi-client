package com.vowifi.sw.manager.data

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class RootDiagnosticReceipt(val complete: Boolean, val records: Long, val invalid: Long, val unavailable: Long)

/** Unlike AndroidSuCommand, this path never materializes stdout as one String. */
internal class AndroidRootDiagnosticStream(
    private val start: (String) -> Process = { ProcessBuilder("su", "-c", it)
        .redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start() },
    private val seconds: Long = 35,
) {
    fun read(from: Long, to: Long, consume: (DiagnosticRecord) -> Unit): RootDiagnosticReceipt {
        require(from in 0..to && to <= 9_999_999_999_999L)
        val process = start("/data/adb/modules/vowifi_sw/control.sh logs diagnostics $from $to")
        val result = AtomicReference<RootDiagnosticReceipt?>()
        val error = AtomicReference<Throwable?>()
        val worker = Thread({
            try { process.inputStream.use { result.set(parse(it, consume)) } }
            catch (failure: Throwable) { error.set(failure); process.destroy() }
        }, "vowifi-diagnostic-stream").apply { isDaemon = true }
        try {
            process.outputStream.close()
            worker.start()
            check(process.waitFor(seconds, TimeUnit.SECONDS)) { "diagnostic timeout" }
            worker.join(1000)
            check(!worker.isAlive) { "diagnostic stream failed" }
            error.get()?.let { if(it is java.io.IOException)throw it;if(it is InterruptedException)throw it;throw IllegalStateException("diagnostic stream failed") }
            return result.get()?.let { if (process.exitValue() == 0) it else it.copy(complete=false) }
                ?: RootDiagnosticReceipt(false,0,0,1)
        } finally {
            process.destroyForcibly()
            process.inputStream.close(); process.errorStream.close()
            worker.interrupt(); worker.join(1000)
        }
    }

    private fun parse(input: InputStream, consume: (DiagnosticRecord) -> Unit): RootDiagnosticReceipt {
        var receipt: RootDiagnosticReceipt? = null
        var count = 0L
        var invalid = 0L
        val scan = DiagnosticStreamReader.read(input, 8L*1024*1024) line@{ text ->
            if (receipt != null) { invalid++;return@line }
            val record = DiagnosticCodec.parse(text)
            if (record != null && record.event.component in rootComponents) { consume(record); count++ }
            else {
                val value = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                val fields = setOf("diagnostic_export","complete","records","invalid","unavailable","legacy_excluded","older_runs_may_lack_dns_io")
                fun number(key: String): Long? = (value?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()?.takeIf { it in 0..1_000_000_000L }
                fun flag(key: String): Boolean? = (value?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.content?.let { when(it) { "true"->true; "false"->false; else->null } }
                if (value != null && value.keys == fields && number("diagnostic_export")==1L && flag("complete")!=null &&
                    number("records")==count && number("invalid")!=null && number("unavailable")!=null &&
                    flag("legacy_excluded")==true && flag("older_runs_may_lack_dns_io")==true) {
                    receipt = RootDiagnosticReceipt(flag("complete")!!,count,number("invalid")!!,number("unavailable")!!)
                } else invalid++
            }
        }
        return receipt?.let { it.copy(complete=it.complete && it.invalid==0L && it.unavailable==0L && invalid==0L && scan.invalid==0,
            invalid=it.invalid+invalid+scan.invalid) } ?: RootDiagnosticReceipt(false,count,invalid+scan.invalid,1)
    }
    companion object { val rootComponents = setOf(DiagnosticComponent.ENGINE, DiagnosticComponent.UNDERLAY, DiagnosticComponent.DNS, DiagnosticComponent.TUNNEL) }
}
