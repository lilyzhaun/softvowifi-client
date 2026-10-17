package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsProperties
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §4.2/4.6/5.1：失活只关闭交互层，不能把真实可用状态绘成业务禁用态。 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ManagerInactivePressRenderTest {
    @Test
    fun inactiveTextActionKeepsItsRestingBusinessAppearance() = verifyInactiveAppearance {
        ManagerTextAction("测试入口", ManagerAction.ContextAction, {})
    }

    @Test
    fun inactiveNavigationRowKeepsItsRestingBusinessAppearance() = verifyInactiveAppearance {
        ManagerNavigatePreferenceItem(
            managerNavigatePreference("测试入口", "正常说明", ManagerPressTestIcon),
            onClick = {},
        )
    }

    @Test
    fun inactiveSwitchRowKeepsItsRestingBusinessAppearance() = verifyInactiveAppearance {
        ManagerTogglePreferenceItem(
            managerTogglePreference(
                AppearancePreferenceKey.FLOATING_NAV, "测试入口", "正常说明", ManagerPressTestIcon,
                checked = true,
            ),
            onCheckedChange = {},
        )
    }

    private fun verifyInactiveAppearance(content: @Composable () -> Unit) = onMotionTestThread {
        runTest {
            var active by mutableStateOf(true)
            ManagerPressRenderFixture(testScheduler) {
                CompositionLocalProvider(LocalAppRouteActive provides active, content = content)
            }.use { fixture ->
                assertTrue(fixture.textBounds("测试入口").width > 0f)
                assertTrue(fixture.actions().isNotEmpty(), "必须先呈现真实可点控件")
                fun pixels(): IntArray = fixture.environment.scene.render(testScheduler.currentTime * 1_000_000L).use { image ->
                    Bitmap.makeFromImage(image).use { bitmap ->
                        IntArray(bitmap.width * bitmap.height) { index ->
                            bitmap.getColor(index % bitmap.width, index / bitmap.width)
                        }
                    }
                }
                val resting = pixels()
                val geometry = fixture.subjectBounds()
                active = false
                fixture.frames(2)
                assertTrue(fixture.actions().all { it.config.contains(SemanticsProperties.Disabled) })
                assertEquals(geometry, fixture.subjectBounds())
                val inactive = pixels()
                assertEquals(0, resting.indices.count { resting[it] != inactive[it] }, "路由失活不应改变静止业务外观")
                active = true
                fixture.frames(2)
                assertTrue(fixture.actions().none { it.config.contains(SemanticsProperties.Disabled) })
                val restored = pixels()
                assertEquals(0, resting.indices.count { resting[it] != restored[it] })
            }
        }
    }
}
