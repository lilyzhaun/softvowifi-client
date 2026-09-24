package com.vowifi.sw.manager.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

object ObserverLogTail {
    fun read(file: File): String = RandomAccessFile(file, "r").use(::read)

    fun read(input: RandomAccessFile): String {
        var position = input.length()
        val buffer = ByteArray(minOf(position, 65536L).toInt())
        var start = buffer.size
        var rows = 0
        var hasContent = false
        while (position > 0 && start > 0) {
            val count = minOf(start, 4096)
            position -= count
            input.seek(position)
            input.readFully(buffer, start - count, count)
            for (index in start - 1 downTo start - count) {
                when (buffer[index].toInt()) {
                    10 -> if (hasContent) {
                        rows++
                        hasContent = false
                        if (rows == 50) return buffer.decodeToString(index + 1, buffer.size)
                    }
                    9, 13, 32 -> Unit
                    else -> hasContent = true
                }
            }
            start -= count
        }
        if (position > 0) {
            val boundary = buffer.indexOf(10.toByte())
            if (boundary < 0) throw IOException("Observer record exceeds byte window")
            start = boundary + 1
            if (start == buffer.size) throw IOException("Observer record exceeds byte window")
        }
        return buffer.decodeToString(start, buffer.size)
    }
}
