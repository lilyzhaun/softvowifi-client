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

        assertTrue(hostStart >= 0)
        assertTrue(hostEnd > hostStart)
        assertTrue(outerBox.contains(".fillMaxSize()"))
        assertTrue(outerBox.contains(".then(capture)"))
        assertFalse(outerBox.contains("viewportBottomInset"))
        assertTrue(pagerModifier.contains(".fillMaxSize()"))
        assertFalse(pagerModifier.contains("viewportBottomInset"))
        assertFalse(pagerModifier.contains("padding(bottom"))
        assertTrue(host.contains("RootBottomContentInset(layoutInsets.pageContentBottomInset + ongoingInset)"))
        assertTrue(shellSource.contains("if (ongoingCall != null) 80.dp else 0.dp"))
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
