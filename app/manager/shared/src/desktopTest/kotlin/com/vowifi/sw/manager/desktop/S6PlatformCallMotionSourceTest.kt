package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.ui.MotionTokens
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T4/T5资源与平台入口合同；不证明Android/OEM实际采纳转场。 */
class S6PlatformCallMotionSourceTest {
    @Test fun platformCallTransitionsUseTheExactSharedTimingAndLeaveTheUnderlyingActivityStationary() {
        val root = generateSequence(File("").canonicalFile) { it.parentFile }.firstOrNull {
            File(it, "app/manager/androidApp/src/main/kotlin/com/vowifi/sw/CallActivity.kt").isFile
        }
        assertTrue(root != null, "必须定位真实App源码，不能空扫通过")
        val main = File(root, "app/manager/androidApp/src/main")
        val ns = "http://schemas.android.com/apk/res/android"
        fun xml(path: String): org.w3c.dom.Element {
            val file = File(main, "res/$path.xml")
            assertTrue(file.isFile, "T4/T5：缺少真实平台转场资源 $path")
            return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(file).documentElement
        }
        fun movement(name: String, from: String, to: String, duration: Int, curve: String?) {
            val node = xml("anim/$name")
            assertEquals("translate", node.tagName)
            assertEquals(from, node.getAttributeNS(ns, "fromYDelta"))
            assertEquals(to, node.getAttributeNS(ns, "toYDelta"))
            assertEquals(duration.toString(), node.getAttributeNS(ns, "duration"))
            if (curve != null) assertEquals("@interpolator/$curve", node.getAttributeNS(ns, "interpolator"))
        }
        movement("s6_call_up", "100%p", "0%p", MotionTokens.DurLarge, "s6_call_enter")
        movement("s6_call_down", "0%p", "100%p", MotionTokens.DurEmphasis, "s6_call_exit")
        movement("s6_call_stay_enter", "0%p", "0%p", MotionTokens.DurLarge, null)
        movement("s6_call_stay_exit", "0%p", "0%p", MotionTokens.DurEmphasis, null)
        for ((name, points) in listOf("s6_call_enter" to listOf(.05f, .7f, .1f, 1f),
            "s6_call_exit" to listOf(.3f, 0f, 1f, 1f))) {
            val node = xml("interpolator/$name")
            assertEquals("pathInterpolator", node.tagName)
            listOf("controlX1", "controlY1", "controlX2", "controlY2").forEachIndexed { i, key ->
                assertEquals(points[i], node.getAttributeNS(ns, key).toFloat())
            }
        }
        val activity = File(main, "kotlin/com/vowifi/sw/CallActivity.kt").readText()
        assertTrue(activity.contains("overrideActivityTransition("), "Android14+须接平台open/close入口")
        assertTrue(activity.contains("OVERRIDE_TRANSITION_OPEN") && activity.contains("OVERRIDE_TRANSITION_CLOSE"))
        assertTrue(activity.contains("overridePendingTransition("), "Android13须保留旧平台入口")
    }
}
