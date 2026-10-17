package com.vowifi.sw

import android.os.FileObserver
import com.vowifi.sw.manager.data.AndroidObserverLogSource
import com.vowifi.sw.manager.data.ObserverLogTail
import com.vowifi.sw.manager.model.RegistrationUiState
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [S3ObserverTailShadow::class],
    instrumentedPackages = ["com.vowifi.sw.manager.data"])
class S3ObserverTrailingRefreshHostTest {
    @Test fun eventsDuringRealReadLeaveExactlyOneTrailingReadAndPublishInOrder() = runBlocking {
        S3ObserverIoFixture().use { fixture ->
            fixture.first.awaitEntered()
            assertEquals(RegistrationUiState.Loading, fixture.source.state.value)
            fixture.appendAndNotify(2)
            fixture.first.release()
            fixture.second.awaitEntered()
            assertEquals(1L, fixture.latest()) // 第一批已发布；第二批实际读过但尚未发布。
            assertEquals(2, S3ObserverTailShadow.reads.get())
            fixture.second.release()
            val state = withTimeout(3000) {
                fixture.source.state.first {
                    it is RegistrationUiState.Data && it.snapshot.events.last().timestampMs == 2L
                }
            } as RegistrationUiState.Data
            assertEquals(listOf(1L, 2L), state.snapshot.events.map { it.timestampMs })
            assertEquals(2, S3ObserverTailShadow.reads.get())
        }
    }
}

internal class S3ObserverIoFixture : AutoCloseable {
    val first = S3IoHold()
    val second = S3IoHold()
    private val output = File(RuntimeEnvironment.getApplication().filesDir, "ims-registration.jsonl")
    val source: AndroidObserverLogSource
    private val observer: FileObserver
    init {
        S3ObserverTailShadow.reads.set(0)
        S3ObserverTailShadow.holds.clear()
        S3ObserverTailShadow.holds.addAll(listOf(first, second))
        output.writeText(frame(1))
        source = AndroidObserverLogSource(RuntimeEnvironment.getApplication())
        observer = source.javaClass.getDeclaredField("observer").apply { isAccessible = true }.get(source) as FileObserver
        observer.stopWatching() // 排除 host 原生回调；下面仍调用生产 onEvent 路径。
    }
    fun appendAndNotify(timestamp: Long) {
        output.appendText(frame(timestamp))
        repeat(3) { observer.onEvent(FileObserver.CLOSE_WRITE, output.name) }
    }
    fun latest() = (source.state.value as RegistrationUiState.Data).snapshot.events.last().timestampMs
    override fun close() { first.release(); second.release(); source.close(); output.delete() }
    private fun frame(timestamp: Long) = """{"timestampMs":$timestamp,"subId":555,"state":"UNREGISTERED"}""" + "\n"
}

// 只拦 read(File) 的返回边界；实际 tail 算法仍走未替换的 read(RandomAccessFile)，parser 也未替换。
@Implements(value = ObserverLogTail::class, isInAndroidSdk = false)
internal class S3ObserverTailShadow {
    @Implementation fun read(file: File): String {
        val snapshot = RandomAccessFile(file, "r").use { ObserverLogTail.read(it) }
        reads.incrementAndGet()
        holds.poll()?.block()
        return snapshot
    }
    companion object {
        val reads = AtomicInteger()
        val holds = ConcurrentLinkedQueue<S3IoHold>()
    }
}
