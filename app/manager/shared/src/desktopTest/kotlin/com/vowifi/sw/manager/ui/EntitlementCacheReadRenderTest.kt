package com.vowifi.sw.manager.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import com.vowifi.sw.manager.desktop.FixtureConfigurationSource
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.ConfigurationSimHandle
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EntitlementCacheReadRenderTest {
    @Test fun perCardReadIsExplicitAndDoesNotSaveTheSourceOrRetryAMiss() = onMotionTestThread { runTest {
        val base = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        val row = base.instances.first().copy(configRevision = "r1_abcdefghijklmnopabcdefghijklmnop",
            systemSubscription = SubscriptionDiscovery.Matched("Synthetic", 0, "001", "01"))
        val ready = base.copy(instances = listOf(row))
        val handle = requireNotNull(ConfigurationSimHandle.parse(row.instanceId.value))
        val drafts = ConfigurationDraftStore()
        var calls = 0
        val values = listOf("https://reader.example.invalid/local", "Vendor", "Model", "Software")
        val actions = SettingsConfigurationActions({ _,_,_ -> error("must not save") }, { error("must not provision") },
            { error("must not refresh") }, readEntitlement = { id, revision, source ->
                assertEquals(row.instanceId, id); assertEquals(row.configRevision, revision)
                assertEquals(values, listOf(source.server, source.vendor, source.model, source.software))
                calls++; EntitlementCacheResult.Missing
            })
        MotionTestEnvironment(testScheduler, width = 1080, height = 2400) {
            SoftVoWiFiTheme(false) { SettingsConfigurationSimScreen(handle, ready, null, actions, drafts) }
        }.use { e ->
            fun frames() = repeat(35) { e.advanceFrameBy(16) }
            fun nodes(): List<SemanticsNode> = e.scene.semanticsOwners.flatMap { owner ->
                fun visit(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::visit)
                visit(owner.unmergedRootSemanticsNode)
            }
            fun text(label: String) = nodes().lastOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { s -> s.text == label } == true }
            fun tap(label: String) {
                val n = assertNotNull(text(label), "未到达实际入口：$label")
                assertTrue(generateSequence(n) { it.parent }.firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }())
                frames()
            }
            frames(); assertEquals(0, calls); tap("生效状态与协商快照"); tap("查看本地资格回执"); assertEquals(0, calls)
            fun fields() = nodes().filter { it.config.contains(SemanticsActions.SetText) }
            assertEquals(4, fields().size)
            fields().zip(values).forEach { (n, v) -> assertTrue(n.config[SemanticsActions.SetText].action!!(AnnotatedString(v))) }
            frames(); tap("读取回执"); assertNotNull(text("没有匹配的本地回执")); assertEquals(1, calls)
            tap("关闭"); tap("查看本地资格回执")
            assertTrue(fields().all { it.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty().isEmpty() })
            assertEquals(1, calls); assertFalse(drafts.draftFor(row).dirty)
        }
    } }
}
