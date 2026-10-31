package com.vowifi.sw

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.CompletableDeferred

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RootFrameworkShadow::class])
class HomeConnectionProgressHostTest {
    @Test fun pushedStagesUpdateWithoutThreeSecondPollingAndCannotRegressOrLeakAcrossConnections() {
        HomeEngineControlHostFixture(withProgress = true).use { f ->
            val owner = HomeEngineControlHostFixture.OWNER_A
            val commands = f.commands.size; val requests = f.requests.size
            f.emitProgress(owner, 1, "CONNECTING", "UNREGISTERED", "EAP_CHALLENGE_RECEIVED")
            assertEquals("EAP_CHALLENGE_RECEIVED", f.row(0).runtime?.tunnelProgress)
            assertEquals("隧道连接中", badge(f, 0))
            f.emitProgress(owner, 2, "CONNECTING", "UNREGISTERED", "SIM_AKA_COMPLETE")
            assertEquals("SIM_AKA_COMPLETE", f.row(0).runtime?.tunnelProgress)
            f.emitProgress(owner, 3, "CONNECTED", "REGISTERING", imsStep = "SENDING_REGISTER")
            assertEquals("SENDING_REGISTER", f.row(0).runtime?.imsProgress)
            assertEquals("隧道已连接", badge(f, 0))
            f.emitProgress(owner, 2, "CONNECTING", "UNREGISTERED", "IKE_NEGOTIATION")
            assertEquals("SENDING_REGISTER", f.row(0).runtime?.imsProgress)
            assertEquals(commands, f.commands.size); assertEquals(requests, f.requests.size)
            f.assertBPreserved()

            val gate = CompletableDeferred<Unit>(); f.statusGate = gate
            f.engine.refresh(); f.dispatcher.runCurrent()
            f.emitProgress(owner, 4, "CONNECTED", "REGISTERING", imsStep = "AKA_AUTHENTICATION")
            gate.complete(Unit); f.dispatcher.runCurrent()
            assertEquals("REGISTERING", f.row(0).runtime?.ims)
            assertEquals("在途旧getStatus不能覆盖刚推送的新实际阶段", "AKA_AUTHENTICATION", f.row(0).runtime?.imsProgress)
            val old = f.progressConnection(owner)
            val next = f.replaceProgressConnection(owner)
            f.emitProgress(owner, 1, "CONNECTING", "UNREGISTERED", "RESOLVING_EPDG", connection = next)
            f.emitProgress(owner, 100, "CONNECTED", "REGISTERED", connection = old)
            assertEquals("RESOLVING_EPDG", f.row(0).runtime?.tunnelProgress)
            val state = f.engine.state.value
            f.engine.close()
            f.emitProgress(owner, 2, "CONNECTED", "REGISTERED", connection = next)
            assertEquals(state, f.engine.state.value)
        }
    }
    @Test fun actualAndroidStatusDecodeAndSimJoinKeepEachOwnersCurrentStepWithoutControlSideEffects() {
        HomeEngineControlHostFixture().use { f ->
            fun publish(tunnel: String, ims: String, tunnelStep: String, imsStep: String) {
                f.statusOverrides[HomeEngineControlHostFixture.OWNER_A] = buildJsonObject {
                    put("tunnel", tunnel); put("ims", ims); put("reconnects", 0)
                    put("tunnelProgress", tunnelStep); put("imsProgress", imsStep)
                    put("call", "IDLE"); put("transport", "UDP"); put("pcscf", JsonArray(emptyList()))
                }
                f.engine.refresh(); f.dispatcher.runCurrent()
            }
            publish("CONNECTING", "UNREGISTERED", "EAP_AKA_AUTHENTICATION", "")
            assertEquals("EAP_AKA_AUTHENTICATION", f.row(0).runtime?.tunnelProgress)
            publish("CONNECTED", "REGISTERING", "", "SENDING_REGISTER")
            assertEquals("SENDING_REGISTER", f.row(0).runtime?.imsProgress)
            publish("CONNECTED", "REGISTERING", "", "AKA_AUTHENTICATION")
            assertEquals("AKA_AUTHENTICATION", f.row(0).runtime?.imsProgress)
            publish("CONNECTED", "REGISTERED", "", "")
            assertEquals("REGISTERED", f.row(0).runtime?.ims)
            assertEquals("运行中", badge(f, 0))
            publish("CONNECTING", "UNREGISTERED", "RESOLVING_EPDG", "")
            assertEquals("RESOLVING_EPDG", f.row(0).runtime?.tunnelProgress)
            publish("CONNECTED", "REGISTERING", "/data/private", "untrusted-text")
            assertEquals("", f.row(0).runtime?.tunnelProgress)
            assertEquals("", f.row(0).runtime?.imsProgress)
            assertEquals("旧引擎缺可选步骤字段仍正常解码", "", f.row(1).runtime?.imsProgress)
            f.assertBPreserved()
            assertTrue(f.commands.all { it.first == "list" })
            assertTrue(f.requests.all { it.second == "getStatus" })
        }
    }

    private fun badge(f: HomeEngineControlHostFixture, slot: Int): String {
        val type = Class.forName("com.vowifi.sw.manager.ui.HomePresentationKt")
        val row = type.getDeclaredMethod("homeSimAction", com.vowifi.sw.manager.model.SimControlInstance::class.java)
            .apply { isAccessible = true }.invoke(null, f.row(slot))
        return row.javaClass.getMethod("getStatusText").invoke(row) as String
    }
}
