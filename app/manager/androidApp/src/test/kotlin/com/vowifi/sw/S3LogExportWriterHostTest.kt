package com.vowifi.sw

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Supplier
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// 全部未编译/运行。只调用实际 MainActivity writer；不启动 onCreate/Compose/引擎，也不伪造 Boolean 结果。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class S3LogExportWriterHostTest {
    private val uri = Uri.parse("content://s3-export-test/logs.txt")
    private val sample = "合成脱敏日志\n时间 10:00 · 引擎已停止\n"

    @Test(timeout = 8000) fun utf8SuccessIsReturnedOnlyAfterActualStreamCloseCompletes() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.get() // 仅 attach，避免无关 runtime 和生产 IO 初始化。
            val bytes = ByteArrayOutputStream()
            val closeEntered = CountDownLatch(1)
            val releaseClose = CountDownLatch(1)
            val closed = AtomicBoolean(false)
            val output = object : OutputStream() {
                override fun write(value: Int) { bytes.write(value) }
                override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes.write(buffer, offset, length) }
                override fun close() {
                    closeEntered.countDown()
                    if (!releaseClose.await(5, TimeUnit.SECONDS)) throw IOException("合成关闭屏障未释放")
                    bytes.close()
                    closed.set(true)
                }
            }
            shadowOf(activity.contentResolver).registerOutputStream(uri, output)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<Boolean> { write(activity, sample) }
                assertTrue("实际 writer 未进入 close，不算完成行为复现", closeEntered.await(2, TimeUnit.SECONDS))
                assertArrayEquals(sample.toByteArray(Charsets.UTF_8), bytes.toByteArray())
                assertFalse("写完字节但 use/close 尚未结束时不能提前成功", result.isDone)
                assertFalse(closed.get())
                releaseClose.countDown()
                assertTrue(result.get(2, TimeUnit.SECONDS))
                assertTrue(closed.get())
            } finally {
                releaseClose.countDown()
                worker.shutdownNow()
                assertTrue("writer 线程必须退出", worker.awaitTermination(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test(timeout = 4000) fun actualOpenWriteAndCloseFailuresAllReturnFalseWithoutRetry() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.get()
            for (boundary in listOf("open", "write", "close")) {
                var opens = 0
                var writes = 0
                var closes = 0
                val output = object : OutputStream() {
                    override fun write(value: Int) {
                        writes++
                        if (boundary == "write") throw IOException("合成写入失败")
                    }
                    override fun close() {
                        closes++
                        if (boundary == "close") throw IOException("合成关闭失败")
                    }
                }
                shadowOf(activity.contentResolver).registerOutputStreamSupplier(uri, Supplier<OutputStream> {
                    opens++
                    if (boundary == "open") throw SecurityException("合成 URI 写入拒绝")
                    output
                })
                assertFalse("实际 $boundary 失败不得宣称导出成功", write(activity, sample))
                assertEquals(1, opens)
                assertEquals(if (boundary == "open") 0 else 1, closes)
                if (boundary == "open") assertEquals(0, writes) else assertTrue(writes > 0)
            }
        }
    }

    @Test(timeout = 3000) fun missingPendingTextReturnsFalseWithoutEvenOpeningTheDestination() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.get()
            var opens = 0
            var writes = 0
            var closes = 0
            shadowOf(activity.contentResolver).registerOutputStreamSupplier(uri, Supplier<OutputStream> {
                opens++
                object : OutputStream() {
                    override fun write(value: Int) { writes++ }
                    override fun close() { closes++ }
                }
            })
            assertFalse(write(activity, null))
            assertEquals(0, opens)
            assertEquals(0, writes)
            assertEquals(0, closes)
        }
    }

    @Test(timeout = 3000) fun realCreateDocumentContractMakesCancellationNullRatherThanASuccessfulDestination() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val contract = ActivityResultContracts.CreateDocument("text/plain")
            val intent = contract.createIntent(controller.get(), "vowifi-logs-redacted.txt")
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
            // The pinned CreateDocument contract sets action/type/title, without an OPENABLE category.
            assertNull(intent.categories)
            assertEquals("text/plain", intent.type)
            assertEquals("vowifi-logs-redacted.txt", intent.getStringExtra(Intent.EXTRA_TITLE))
            assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(uri)))
            assertNull(contract.parseResult(Activity.RESULT_OK, null))
            assertEquals(uri, contract.parseResult(Activity.RESULT_OK, Intent().setData(uri)))
        }
    }

    private fun write(activity: MainActivity, text: String?): Boolean = MainActivity::class.java
        .getDeclaredMethod("writeUtf8Export", Uri::class.java, String::class.java)
        .apply { isAccessible = true }.invoke(activity, uri, text) as Boolean
}
