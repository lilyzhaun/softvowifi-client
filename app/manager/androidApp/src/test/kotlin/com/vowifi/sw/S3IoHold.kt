package com.vowifi.sw

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

// 同步系统/文件 IO 的有界屏障；复用现有 host 测试的 latch 做法，不模拟耗时。
internal class S3IoHold {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    fun block() {
        entered.countDown()
        check(released.await(5, TimeUnit.SECONDS)) { "IO 屏障未释放" }
    }
    fun awaitEntered() = assertTrue("真实 IO 未进入屏障", entered.await(3, TimeUnit.SECONDS))
    fun release() = released.countDown()
}
