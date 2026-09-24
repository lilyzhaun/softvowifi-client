package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidObserverLogSource
import com.vowifi.sw.manager.data.RegistrationLogParser
import com.vowifi.sw.manager.model.RegistrationUiState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ObserverLogSourceHostTest {
    @Test fun independentObserversAppendCompleteFramesWithoutRewritingLegacyTail() {
        val file = File(RuntimeEnvironment.getApplication().filesDir, "writer-synthetic.jsonl")
        val legacy = """{"timestampMs":0,"subId":555,"state":"UNREGISTERED"}"""
        file.writeText(legacy)
        val write = RegistrationObserver::class.java.getDeclaredMethod("write", JSONObject::class.java)
            .apply { isAccessible = true }
        val workers = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..2).map { writer ->
                workers.submit {
                    val observer = RegistrationObserver(555, null, { it.run() }, file)
                    check(start.await(3, TimeUnit.SECONDS))
                    repeat(50) { index ->
                        write.invoke(observer, JSONObject().put("timestampMs", writer * 100 + index)
                            .put("subId", 555).put("state", "UNREGISTERED"))
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            val content = file.readText()
            assertTrue(content.startsWith(legacy))
            assertTrue(content.endsWith("\n"))
            val rows = content.lineSequence().filter { it.isNotBlank() }.toList()
            assertEquals(100, rows.size)
            var count = 0
            rows.forEach { row ->
                val data = RegistrationLogParser().parse(row) as RegistrationUiState.Data
                assertTrue(data.snapshot.issues.isEmpty())
                count += data.snapshot.events.size
            }
            assertEquals(101, count)
        } finally {
            workers.shutdownNow()
            file.delete()
        }
    }

    @Test fun realSourceReadsLatest50AndPreservesClosedTruncatedRecordWarning() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val output = File(context.filesDir, "ims-registration.jsonl")
        output.writeText((1..1000).joinToString("\n") {
            """{"timestampMs":$it,"subId":555,"state":"UNREGISTERED"}"""
        })
        val source = AndroidObserverLogSource(context)
        try {
            val data = withTimeout(3000) {
                source.state.first { it is RegistrationUiState.Data } as RegistrationUiState.Data
            }
            assertEquals((951L..1000L).toList(), data.snapshot.events.map { it.timestampMs })
            assertTrue(data.snapshot.issues.isEmpty())
            output.appendText("\n{\"timestampMs\":")
            source.refresh()
            val warning = withTimeout(3000) {
                source.state.first { it is RegistrationUiState.Data && it.snapshot.issues.isNotEmpty() }
            } as RegistrationUiState.Data
            assertEquals(1, warning.snapshot.issues.size)
        } finally {
            source.close()
            output.delete()
        }
    }
}
