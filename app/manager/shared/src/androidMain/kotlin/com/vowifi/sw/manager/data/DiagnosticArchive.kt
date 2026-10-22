package com.vowifi.sw.manager.data

import java.io.OutputStream
import java.io.IOException
import java.io.FilterInputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class DiagnosticArchive(
    private val directory: () -> Path?,
    private val appHealthy: Boolean = true,
    private val root: (Long, Long, (DiagnosticRecord) -> Unit) -> RootDiagnosticReceipt,
) {
    fun write(from: Long, to: Long, output: OutputStream) {
        require(from in 0..to && to<=9_999_999_999_999L)
        val salt=ByteArray(32).also { SecureRandom().nextBytes(it) }
        var written=0L
        var limited=false
        var appInvalid=0L
        var appUnavailable=if(appHealthy)0L else 1L
        var appCount=0L
        var rootCount=0L
        fun line(value: JsonObject, terminal: Boolean = false) {
            if(Thread.currentThread().isInterrupted)throw InterruptedException()
            val bytes=(value.toString()+"\n").encodeToByteArray()
            if(bytes.size>2048)throw ArchiveOutputFailure(IOException("diagnostic frame limit"))
            if(written+bytes.size>8L*1024*1024-(if(terminal)0 else 2048)) {limited=true;throw ArchiveLimit()}
            try {output.write(bytes)} catch(failure:IOException){throw ArchiveOutputFailure(failure)}
            written+=bytes.size
        }
        fun event(source: String, record: DiagnosticRecord) {
            if(record.wall !in from..to)return
            val endpoint=record.event.endpoint?.let { value ->
                val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(salt,"HmacSHA256"))
                mac.doFinal((record.run+":"+value).encodeToByteArray()).take(16).joinToString("") { "%02x".format(it) }
            }
            val safe=record.copy(event=record.event.copy(endpoint=endpoint))
            line(buildJsonObject {put("source",source);put("event",Json.parseToJsonElement(safe.bytes().decodeToString()))})
            if(source=="app")appCount++ else rootCount++
        }
        line(buildJsonObject {put("diagnostic_archive",1);put("from_wall_ms",from);put("to_wall_ms",to)
            put("snapshot_atomic",false);put("legacy_excluded",true);put("endpoint_scope","random_per_export")})
        try {
            val dir=directory()
            if(dir==null)appUnavailable++ else {
                checkAncestors(dir)
                if(Files.exists(dir,NOFOLLOW_LINKS)) {
                    if(!Files.isDirectory(dir,NOFOLLOW_LINKS))throw IOException("unsafe diagnostic directory")
                    val names=setOf("app.jsonl","app.jsonl.1","app.jsonl.2","writer.lock")
                    Files.newDirectoryStream(dir).use { entries ->
                        var count=0
                        for(path in entries) {
                            if(++count>4 || path.fileName.toString() !in names)throw IOException("unsafe diagnostic directory")
                            if(path.fileName.toString()=="writer.lock" && (!Files.isRegularFile(path,NOFOLLOW_LINKS) || Files.size(path)!=0L))throw IOException("unsafe diagnostic lock")
                        }
                    }
                    val generations=listOf("app.jsonl.2","app.jsonl.1","app.jsonl")
                    fun identities()=generations.map { name ->
                        val path=dir.resolve(name)
                        if(!Files.exists(path,NOFOLLOW_LINKS))null else Files.readAttributes(path,BasicFileAttributes::class.java,NOFOLLOW_LINKS).let {it.fileKey() to it.size()}
                    }
                    val before=identities()
                    for(name in generations) {
                        val path=dir.resolve(name)
                        if(!Files.exists(path,NOFOLLOW_LINKS))continue
                        try {
                            if(!Files.isRegularFile(path,NOFOLLOW_LINKS) || Files.size(path)>1024*1024 ||
                                (Files.getAttribute(path,"unix:nlink",NOFOLLOW_LINKS) as Number).toLong()!=1L)throw IOException("unsafe diagnostic file")
                            FileChannel.open(path,READ,NOFOLLOW_LINKS).use { file ->
                                val snapshotSize=file.size()
                                val snapshot=object:FilterInputStream(Channels.newInputStream(file)){
                                    var remaining=snapshotSize
                                    override fun read():Int {if(remaining<=0)return -1;val value=super.read();if(value>=0)remaining--;return value}
                                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                                        if(remaining<=0)return -1
                                        val n=super.read(bytes,offset,minOf(length.toLong(),remaining).toInt());if(n>0)remaining-=n;return n
                                    }
                                }
                                val read=DiagnosticStreamReader.read(snapshot,1024L*1024) { text ->
                                    val record=DiagnosticCodec.parse(text)
                                    if(record==null || record.event.component !in appComponents)appInvalid++ else event("app",record)
                                }
                                appInvalid+=read.invalid
                                if(read.bytes!=snapshotSize)appUnavailable++
                            }
                        } catch (limit:ArchiveLimit){throw limit}
                        catch (failure:ArchiveOutputFailure){throw failure}
                        catch (_:IOException){appUnavailable++}
                    }
                    // Do not block the process writer. Detect a crossed rotation/append instead of
                    // silently exporting mixed generations and claiming full evidence.
                    if(before!=identities())appUnavailable++
                }
            }
        } catch (_:ArchiveLimit){limited=true}
        catch (failure:ArchiveOutputFailure){throw failure}
        catch (_:IOException){appUnavailable++}
        catch (_:SecurityException){appUnavailable++}
        catch (_:IllegalArgumentException){appUnavailable++}
        var receipt=RootDiagnosticReceipt(false,0,0,1)
        if(!limited) {
            try {receipt=root(from,to) {event("root",it)}}
            catch (_:ArchiveLimit){limited=true}
            catch (failure:ArchiveOutputFailure){throw failure}
            catch (_:IOException){ }
            catch (_:IllegalStateException){ }
            catch (_:SecurityException){ }
        }
        line(buildJsonObject {
            put("diagnostic_archive_end",1);put("app_records",appCount);put("root_records",rootCount)
            put("app_complete",appInvalid==0L && appUnavailable==0L && !limited);put("app_invalid",appInvalid);put("app_unavailable",appUnavailable)
            put("root_complete",receipt.complete && receipt.invalid==0L && receipt.unavailable==0L && !limited)
            put("root_invalid",receipt.invalid);put("root_unavailable",receipt.unavailable)
            put("limit_reached",limited);put("snapshot_atomic",false);put("legacy_excluded",true)
            put("retained_events_only",true);put("capture_completeness_proven",false);put("pending_may_be_unflushed",true)
            put("dns_io_observed_per_event",true);put("older_runs_may_lack_dns_io",true)
        },terminal=true)
    }
    private class ArchiveLimit: IOException("diagnostic archive limit")
    private class ArchiveOutputFailure(cause:IOException): IOException("diagnostic archive write failed",cause)
    companion object {
        private val appComponents=setOf(DiagnosticComponent.APP,DiagnosticComponent.SUBSCRIPTIONS,DiagnosticComponent.CONFIGURATION,DiagnosticComponent.UNDERLAY)
        internal fun checkAncestors(path:Path) {
            var current:Path?=path.toAbsolutePath().normalize();var depth=0
            while(current!=null) {
                if(++depth>32 || Files.isSymbolicLink(current))throw IOException("unsafe diagnostic path")
                current=current.parent
            }
        }
    }
}
