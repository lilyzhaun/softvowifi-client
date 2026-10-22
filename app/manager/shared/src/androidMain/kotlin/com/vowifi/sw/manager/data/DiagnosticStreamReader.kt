package com.vowifi.sw.manager.data

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** One bounded line at a time. Oversized and torn records are counted, never echoed. */
internal object DiagnosticStreamReader {
    data class Result(val invalid: Int, val bytes: Long)
    fun read(input: InputStream, maxBytes: Long, line: (String) -> Unit): Result {
        val buffer = ByteArray(4096)
        val record = ByteArray(2047)
        var used = 0
        var oversized = false
        var invalid = 0
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if(total>maxBytes)throw java.io.IOException("diagnostic stream limit")
            for (i in 0 until n) {
                val b = buffer[i]
                if (b == 10.toByte()) {
                    if (oversized) invalid++ else if (used > 0) {
                        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(record,0,used)).toString() }
                        catch (_: java.nio.charset.CharacterCodingException) { null }
                        if (text == null) invalid++ else line(text)
                    }
                    used = 0; oversized = false
                } else if (used < record.size) record[used++] = b else oversized = true
            }
        }
        if (used > 0 || oversized) invalid++
        return Result(invalid, total)
    }
}
