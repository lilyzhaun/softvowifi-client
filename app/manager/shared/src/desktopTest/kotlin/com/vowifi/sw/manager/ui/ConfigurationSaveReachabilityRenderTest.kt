package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.desktop.FixtureConfigurationSource
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationTransport
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.ui.navigation.ConfigurationSimHandle
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 用户回归（2026-10-20）：小米13视口内改完底层网络，保存入口必须不滚动即可见，且滚到任何位置都仍可见。
 * 旧折叠卡实现的有效 RED：按钮未裁剪顶边 6311px，远在 2400px 视口之外（见任务文档）。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConfigurationSaveReachabilityRenderTest {
    @Test fun networkChangeSaveActionIsVisibleWithoutScrolling() = onMotionTestThread { runTest {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        val drafts = ConfigurationDraftStore()
        var opened by mutableStateOf<ConfigurationSimHandle?>(null)
        val actions = SettingsConfigurationActions({ _, _, _ -> ConfigurationApplyAcceptance.Accepted }, {},
            { error("刷新不属于本用例") }, onOpenSim = { opened = it })
        MotionTestEnvironment(testScheduler, width = WIDTH, height = HEIGHT) {
            SoftVoWiFiTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(2.75f)) {
                    val sim = opened
                    if (sim == null) SettingsConfigurationScreen(ready, null, actions, drafts)
                    else SettingsConfigurationSimScreen(sim, ready, null, actions, drafts)
                }
            }
        }.use { e ->
            fun frames() = repeat(60) { e.advanceFrameBy(16) }
            fun nodes(): List<SemanticsNode> = e.scene.semanticsOwners.flatMap { owner ->
                fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
                visit(owner.unmergedRootSemanticsNode)
            }
            fun hasText(text: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true }
            fun tap(text: String) {
                val node = nodes().last { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == text } == true }
                val action = generateSequence(node) { it.parent }.firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
                assertTrue(action(), "前提：真实入口 $text 可点击")
                frames()
            }
            fun assertSaveVisible(stage: String) {
                val save = nodes().filter { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "保存配置" } == true
                }.map { node -> generateSequence(node) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) } }
                assertEquals(1, save.size, "改动后须恰有一个保存入口（$stage）")
                val bounds = save.single().boundsInRoot
                println("CONFIGURATION_SAVE_REACHABILITY|$stage|bounds=$bounds|unclippedTop=${save.single().positionInRoot.y}|viewport=${WIDTH}x$HEIGHT")
                // boundsInRoot 已按祖先裁剪；完全在列表视口外的按钮会塌成 0×0，不能当作“在视口内”。
                assertTrue(bounds.width > 0f && bounds.height >= 48f * 2.75f - 1f && bounds.top >= 0f && bounds.bottom <= HEIGHT.toFloat(),
                    "保存入口必须完整可见（含 48dp 点击高度）于小米13视口内（$stage），实际=$bounds")
            }
            frames()
            assertTrue(hasText("配置就绪不代表注册成功；注册状态请在首页查看。"), "总览页说明配置与注册的区别")
            tap("实例 •••• 1001")
            assertEquals("sim-1", opened?.value, "总览行以不透明实例句柄进入单卡页")
            tap("底层网络")
            tap("Wi-Fi")
            assertEquals(ConfigurationTransport.WIFI, drafts.draftFor(ready.instances.first()).transport, "前提：真实菜单已改草稿")
            assertSaveVisible("after-network-change")
            val list = nodes().first { it.config.contains(SemanticsActions.ScrollBy) }
            requireNotNull(list.config[SemanticsActions.ScrollBy].action).invoke(0f, 50_000f)
            frames()
            assertSaveVisible("scrolled-to-end")

            opened = null
            frames()
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.endsWith("有未保存修改") } == true },
                "离开单卡页后总览行仍标出未保存修改")
        }
    } }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2400
    }
}
