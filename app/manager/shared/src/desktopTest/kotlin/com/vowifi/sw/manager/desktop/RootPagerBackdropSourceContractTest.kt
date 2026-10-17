package com.vowifi.sw.manager.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootPagerBackdropSourceContractTest {
    @Test
    fun globalBackdropAndPagerRemainFullscreenWhilePageContentConsumesBottomInset() {
        val shellSource = readRequired("shared/src/commonMain/kotlin/com/vowifi/sw/manager/ui/AppShell.kt")
        val scaffoldSource = readRequired("shared/src/commonMain/kotlin/com/vowifi/sw/manager/ui/AppPageScaffold.kt")
        val hostStart = shellSource.indexOf("private fun RootPagerHost(")
        val hostEnd = shellSource.indexOf("\ndata class AppShellPolicy", hostStart)
        val host = shellSource.substring(hostStart, hostEnd)
        val outerBoxStart = host.indexOf("Box(")
        val pagerStart = host.indexOf("HorizontalPager(")
        val outerBox = host.substring(outerBoxStart, pagerStart)
        val pagerModifierEnd = host.indexOf("beyondViewportPageCount", pagerStart)
        val pagerModifier = host.substring(pagerStart, pagerModifierEnd)
        val rootScaffoldStart = shellSource.indexOf("Scaffold(")
        val rootLayerStart = shellSource.indexOf("Box(Modifier.fillMaxSize().background(colors.canvas.copy(alpha = 1f)))")
        val rootCallStart = shellSource.indexOf("RootPagerHost(", rootLayerStart)
        val coveredLayerStart = shellSource.indexOf("if (!rootInteractive) Box(", rootCallStart)
        val childCallStart = shellSource.indexOf("SubpageHost(", rootCallStart)
        val resultCallStart = shellSource.indexOf("resultOverlay?.invoke(", childCallStart)
        val rootCall = shellSource.substring(rootCallStart, childCallStart)

        assertTrue(hostStart >= 0)
        assertTrue(hostEnd > hostStart)
        assertTrue(outerBox.contains(".fillMaxSize()"))
        assertTrue(outerBox.contains(".then(capture)"))
        assertFalse(outerBox.contains("padding("), "全局capture层不消费页面底部占位")
        assertFalse(outerBox.contains("viewportBottomInset"))
        assertTrue(pagerModifier.contains(".fillMaxSize()"))
        assertFalse(pagerModifier.contains("viewportBottomInset"))
        assertFalse(pagerModifier.contains("padding(bottom"))
        assertTrue(host.contains("RootBottomContentInset(layoutInsets.pageContentBottomInset + ongoingInset)"))
        // S4/C14保留正在退场的返回条绘制占位；校准同一RootPagerHost实参，不把它改成root padding。
        assertTrue(shellSource.contains("ongoingCall: (@Composable (hitEnabled: Boolean) -> Unit)? = null"))
        assertTrue(rootLayerStart >= 0 && rootCallStart > rootLayerStart && childCallStart > rootCallStart,
            "满屏根内容后绘制真实SubpageHost，子页仍可覆盖根内容")
        assertTrue(rootScaffoldStart >= 0 && rootLayerStart > rootScaffoldStart && coveredLayerStart > rootCallStart &&
            childCallStart > coveredLayerStart && resultCallStart > childCallStart,
            "根Scaffold完成后依次绘制covered兄弟屏障、SubpageHost和唯一结果层；子页不再被根Scaffold的底栏覆盖")
        assertFalse(shellSource.substring(rootLayerStart, rootCallStart).contains("padding("))
        assertTrue(rootCall.contains("if (ongoingDrawn) 80.dp else 0.dp"))
        assertTrue(scaffoldSource.contains("rootBottomInset.value + 12.dp"))
    }

    private fun readRequired(relativeFromManager: String): String {
        val file = locate(relativeFromManager)
        assertTrue(file.isFile, "missing $relativeFromManager at ${file.absolutePath}")
        return file.readText()
    }

    private fun locate(relativeFromManager: String): File {
        val here = File("").absoluteFile
        val candidates = listOf(
            File(here, relativeFromManager),
            File(here, "../$relativeFromManager"),
            File(here, "../../$relativeFromManager"),
            File(here, "app/manager/$relativeFromManager"),
        )
        return candidates.firstOrNull(File::isFile) ?: candidates[1]
    }
}
