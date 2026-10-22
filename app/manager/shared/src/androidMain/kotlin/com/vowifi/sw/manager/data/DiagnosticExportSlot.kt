package com.vowifi.sw.manager.data

import java.io.OutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions

internal class DiagnosticExportSlot(private val directory: Path, private val maxBytes: Long = 8L*1024*1024) {
    fun export(destination: OutputStream, generate: (OutputStream) -> Unit) = locked {
        val path=directory.resolve("export.jsonl")
        Files.deleteIfExists(path) // validated, fixed stale payload only, under the exclusive slot lock
        try {
            FileChannel.open(path,setOf(CREATE,READ,WRITE,NOFOLLOW_LINKS),privateFile).use { file ->
                var size=0L
                val bounded=object:OutputStream() {
                    override fun write(value:Int)=write(byteArrayOf(value.toByte()),0,1)
                    override fun write(bytes:ByteArray,offset:Int,length:Int) {
                        if(Thread.currentThread().isInterrupted)throw InterruptedException()
                        if(size+length>minOf(maxBytes,8L*1024*1024))throw IOException("diagnostic export size limit")
                        val buffer=ByteBuffer.wrap(bytes,offset,length)
                        while(buffer.hasRemaining()) {
                            if(file.write(buffer)<=0)throw IOException("diagnostic export short write")
                        }
                        size+=length
                    }
                }
                generate(bounded)
                file.position(0)
                val buffer=ByteBuffer.allocate(4096)
                while(true) {
                    if(Thread.currentThread().isInterrupted)throw InterruptedException()
                    buffer.clear();val n=file.read(buffer);if(n<0)break
                    if(n==0)throw IOException("diagnostic export short read")
                    destination.write(buffer.array(),0,n)
                }
            }
        } finally {Files.deleteIfExists(path)}
    }
    fun cleanup() {
        if(!Files.exists(directory,NOFOLLOW_LINKS))return
        locked {Files.deleteIfExists(directory.resolve("export.jsonl"))}
    }
    private fun locked(action:()->Unit) {
        DiagnosticArchive.checkAncestors(directory)
        if(!Files.exists(directory,NOFOLLOW_LINKS)) Files.createDirectory(directory,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        if(!Files.isDirectory(directory,NOFOLLOW_LINKS) || Files.getPosixFilePermissions(directory,NOFOLLOW_LINKS)!=PosixFilePermissions.fromString("rwx------"))throw IOException("unsafe export directory")
        Files.newDirectoryStream(directory).use {entries ->
            var count=0
            for(path in entries) {
                val name=path.fileName.toString()
                val limit=when(name){"writer.lock"->0L;"export.jsonl"->8L*1024*1024;else->throw IOException("unknown export file")}
                if(++count>2 || !Files.isRegularFile(path,NOFOLLOW_LINKS) || Files.size(path)>limit ||
                    Files.getPosixFilePermissions(path,NOFOLLOW_LINKS)!=PosixFilePermissions.fromString("rw-------") ||
                    (Files.getAttribute(path,"unix:nlink",NOFOLLOW_LINKS) as Number).toLong()!=1L)throw IOException("unsafe export file")
            }
        }
        FileChannel.open(directory.resolve("writer.lock"),setOf(CREATE,READ,WRITE,NOFOLLOW_LINKS),privateFile).use { channel ->
            val lock=try {channel.tryLock()}catch(_:OverlappingFileLockException){null}
                ?:throw IOException("diagnostic export busy")
            lock.use {action()}
        }
    }
    companion object {private val privateFile=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
}
