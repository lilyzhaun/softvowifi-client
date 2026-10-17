package com.vowifi.sw

import com.vowifi.sw.HomeEngineControlHostFixture.Companion.OWNER_A
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.SimControlInstance
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// 仅使用现有 API；尚未编译/执行。首个测试为行为失败候选，不是 RED 回执。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class S3EngineRefreshBehaviorHostTest {
    @Test fun acceptedStartBehindHeldRefreshIsObservableBeforeExecution() {
        HomeEngineControlHostFixture(firstRunning = false).use { fixture ->
            val hold = CompletableDeferred<Unit>().also { fixture.statusGate = it }
            try {
                fixture.engine.refresh()
                fixture.dispatcher.runCurrent()
                assertTrue(fixture.statusEntered.isCompleted)
                fixture.homeAction(0)
                fixture.dispatcher.runCurrent()

                // 证明请求确实已被接受，且还没有实际执行；不把排队 START 伪装为 RUNNING。
                val pending = fixture.engine.javaClass.getDeclaredField("pendingOwners")
                    .apply { isAccessible = true }.get(fixture.engine) as Set<*>
                assertTrue(OWNER_A in pending)
                assertTrue(fixture.commands.none { it.first == "start" })
                assertEquals(EngineLifecycle.STOPPED, fixture.row(0).lifecycle)
                assertEquals(EngineLifecycle.RUNNING, fixture.row(1).lifecycle)

                val action = homeAction(fixture.row(0))
                assertTrue("已接受但未执行的动作必须可见为 busy", action.javaClass.getMethod("getBusy").invoke(action) as Boolean)
                assertFalse(action.javaClass.getMethod("getEnabled").invoke(action) as Boolean)
            } finally {
                hold.complete(Unit)
                fixture.dispatcher.runCurrent()
            }
            assertEquals(listOf("start" to OWNER_A), fixture.commands.filter { it.first != "list" })
            fixture.assertBPreserved()
        }
    }

    @Test fun duplicateAutomaticRefreshDuringRealStatusIoDoesNotStartMoreIo() {
        HomeEngineControlHostFixture().use { fixture ->
            val before = fixture.sims.state.value
            val lists = fixture.commands.count { it.first == "list" }
            val statuses = fixture.requests.count { it.second == "getStatus" }
            val hold = CompletableDeferred<Unit>().also { fixture.statusGate = it }
            try {
                fixture.engine.refresh()
                fixture.dispatcher.runCurrent()
                assertTrue(fixture.statusEntered.isCompleted)
                repeat(2) { fixture.engine.refresh() }
                fixture.dispatcher.runCurrent()
                assertEquals(before, fixture.sims.state.value)
                assertEquals(lists + 1, fixture.commands.count { it.first == "list" })
                assertEquals(statuses + 1, fixture.requests.count { it.second == "getStatus" })
            } finally {
                hold.complete(Unit)
                fixture.dispatcher.runCurrent()
            }
            assertEquals(statuses + 2, fixture.requests.count { it.second == "getStatus" })
            fixture.assertBPreserved()
        }
    }

    private fun homeAction(row: SimControlInstance): Any {
        val method = Class.forName("com.vowifi.sw.manager.ui.HomePresentationKt")
            .getDeclaredMethod("homeSimAction", SimControlInstance::class.java).apply { isAccessible = true }
        val presentation = method.invoke(null, row)
        return presentation.javaClass.getMethod("getAction").invoke(presentation)
    }
}
