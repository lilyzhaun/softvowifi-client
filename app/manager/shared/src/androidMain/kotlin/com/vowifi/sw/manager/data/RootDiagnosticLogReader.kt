package com.vowifi.sw.manager.data

import android.content.Context
import com.vowifi.sw.manager.model.OperationalLogFile
import com.vowifi.sw.manager.model.OperationalLogTail

internal class RootDiagnosticLogReader(context: Context, private val stream: AndroidRootDiagnosticStream = AndroidRootDiagnosticStream()) {
    private val local = LocalDiagnosticLogReader(context)
    fun read(): OperationalLogGroupRead {
        val entries = ArrayDeque<DiagnosticRecord>()
        var total = 0
        val receipt = try { stream.read(0,System.currentTimeMillis()) { record ->
            total++;entries.addLast(record);if(entries.size>50)entries.remove(entries.minBy { it.wall })
        } } catch (interrupted: InterruptedException) {throw interrupted}
        catch (_: java.io.IOException) {RootDiagnosticReceipt(false,total.toLong(),0,1)}
        catch (_: IllegalStateException) {RootDiagnosticReceipt(false,total.toLong(),0,1)}
        val issue=if(!receipt.complete || receipt.unavailable>0 || receipt.invalid>0)
            "Root读取不完整或模块不支持；已拒绝${receipt.invalid}条无效事件。App全局源仍可独立读取。" else null
        return OperationalLogGroupRead(OperationalLogTail(ID,total>50,entries.sortedBy { it.wall }.map { local.entry(it,root=true) },emptyList()),issue)
    }
    fun combined(): OperationalLogGroupRead {
        val app=local.read()
        val root=read()
        val candidates=(app.tail.lines+root.tail.lines).sortedBy { it.timestamp }
        val selected=ArrayDeque<com.vowifi.sw.manager.model.OperationalLogEntry>()
        var bytes=0
        for(entry in candidates.asReversed()) {
            val size=(entry.safeSummary+entry.safeDetail).encodeToByteArray().size
            if(selected.size==50 || bytes+size>65536)break
            selected.addFirst(entry);bytes+=size
        }
        return OperationalLogGroupRead(OperationalLogTail(ALL_ID,app.tail.truncated || root.tail.truncated || selected.size<candidates.size,
            selected.toList(),app.tail.issues+root.tail.issues),listOfNotNull(app.issue,root.issue).takeIf { it.isNotEmpty() }?.joinToString("；"))
    }
    companion object {
        const val ID="root-diagnostics-v1";val FILE=OperationalLogFile(ID,"diagnostic.jsonl","")
        const val ALL_ID="all-diagnostics-v1";val ALL_FILE=OperationalLogFile(ALL_ID,"combined.jsonl","")
    }
}
