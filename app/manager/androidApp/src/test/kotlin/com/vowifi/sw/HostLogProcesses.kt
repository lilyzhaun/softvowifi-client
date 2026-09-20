package com.vowifi.sw

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

internal class HostLogProcesses : AutoCloseable {
    private val processes = CopyOnWriteArrayList<Process>()
    private val children = CopyOnWriteArrayList<ProcessHandle>()
    private val baseline = logThreads().toSet()

    fun start(script: String): Process = ProcessBuilder("sh", "-c", script)
        .redirectErrorStream(true).start().also { processes.add(it) }

    fun captureChild(): ProcessHandle {
        await("owned shell child") {
            processes.forEach { process ->
                handle(process).descendants().use { stream -> stream.forEach { children.addIfAbsent(it) } }
            }
            children.any { it.isAlive }
        }
        return children.first { it.isAlive }
    }

    fun awaitWorkerWaiting() = await("runner waiting for live process") {
        logThreads().any { thread ->
            thread !in baseline && thread.name == "log-shell" &&
                thread.stackTrace.any { it.methodName == "waitFor" }
        }
    }

    fun assertStopped() {
        try {
            await("runner threads stopped") { logThreads().none { it !in baseline } }
        } catch (error: AssertionError) {
            throw AssertionError("${error.message}\n${stacks()}", error)
        }
        assertTrue("owned direct process survived", processes.none { it.isAlive })
    }

    fun bounded(body: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "host-log-test-driver").apply { isDaemon = true } }
        val future = executor.submit(body)
        try {
            future.get(5, TimeUnit.SECONDS)
        } catch (error: java.util.concurrent.TimeoutException) {
            throw AssertionError("real process operation exceeded 5s\n${stacks()}", error)
        } finally {
            future.cancel(true)
            executor.shutdownNow()
            close()
            assertTrue("test driver survived cleanup", executor.awaitTermination(3, TimeUnit.SECONDS))
            close()
            assertStopped()
            await("owned descendants stopped") { children.none { it.isAlive } }
            println("cleanup: directAlive=${processes.count { it.isAlive }} childAlive=${children.count { it.isAlive }} runnerThreads=${logThreads().count { it !in baseline }}")
        }
    }

    override fun close() {
        processes.filter { it.isAlive }.forEach { process ->
            handle(process).descendants().use { stream -> stream.forEach { children.addIfAbsent(it) } }
        }
        children.filter { it.isAlive }.forEach { it.destroyForcibly() }
        processes.forEach { process ->
            process.destroyForcibly()
            assertTrue("owned process not reaped", process.waitFor(2, TimeUnit.SECONDS))
        }
    }

    companion object {
        private fun stacks() = logThreads().joinToString("\n") {
            "${it.name} ${it.state}\n${it.stackTrace.joinToString("\n")}"
        }

        private fun handle(process: Process): ProcessHandle =
            Process::class.java.getMethod("toHandle").invoke(process) as ProcessHandle

        fun await(label: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(label, condition())
        }

        fun logThreads(): List<Thread> = Thread.getAllStackTraces().keys.filter {
            it.isAlive && (it.name == "log-shell" || it.name == "log-output")
        }

        fun pipeFds(): Set<String> = File("/proc/self/fd").listFiles().orEmpty().mapNotNull {
            val target = runCatching { java.nio.file.Files.readSymbolicLink(it.toPath()).toString() }.getOrNull()
            target?.takeIf { link -> link.startsWith("pipe:") }
        }.toSet()
    }
}
