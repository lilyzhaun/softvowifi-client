package com.vowifi.sw.manager.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** 只核对已安装 0.9.3 的公开能力，不 emit 人造交互，也不复制库实现。 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class MiuixPreferenceSourceTest {
    @Test fun basicComponentUsesThePubliclyProvidedSourceForItsRealClick() = onMotionTestThread { runTest {
        val supplied = MutableInteractionSource()
        val suppliedEvents = mutableListOf<Interaction>()
        val observer = SourceObserver()
        var calls = 0
        ManagerPressRenderFixture(testScheduler) {
            LaunchedEffect(supplied) { supplied.interactions.collect { suppliedEvents += it } }
            CompositionLocalProvider(LocalIndication provides observer) {
                BasicComponent(title = "合成基础行", onClick = { calls++ }, interactionSource = supplied)
            }
        }.use { fixture ->
            tap(fixture, fixture.subjectBounds().center)
            assertEquals(1, calls)
            // Compose Desktop 传给 Indication 的是输入模式过滤视图；源对象可以不同，真实事件不能换。
            val originalPresses = suppliedEvents.filterIsInstance<PressInteraction>()
            assertEquals(2, originalPresses.size, "所传源必须收到恰好一次真实 Press/Release")
            val originalPress = assertIs<PressInteraction.Press>(originalPresses[0])
            assertSame(originalPress, assertIs<PressInteraction.Release>(originalPresses[1]).press)
            val indicationPresses = observer.presses
            assertSame(observer.sources.distinct().single(), pairedSource(indicationPresses))
            assertSame(originalPresses[0], indicationPresses[0].interaction, "过滤视图必须观察所传源的同一个 Press 实例")
            assertSame(originalPresses[1], indicationPresses[1].interaction, "过滤视图必须观察所传源的同一个 Release 实例")
        }
    } }

    @Test fun arrowPreferencePublicIndicationReceivesTheActualPrivateClickSource() = onMotionTestThread { runTest {
        val observer = SourceObserver()
        var calls = 0
        ManagerPressRenderFixture(testScheduler) {
            CompositionLocalProvider(LocalIndication provides observer) {
                ArrowPreference(title = "合成导航行", onClick = { calls++ })
            }
        }.use { fixture ->
            tap(fixture, fixture.subjectBounds().center)
            assertEquals(1, calls)
            assertEquals(1, observer.sources.distinct().size)
            assertSame(observer.sources.distinct().single(), pairedSource(observer.presses))
        }
    } }

    @Test fun switchPreferenceRowAndInnerSwitchHaveDistinctRealSourcesWithoutDuplicateCallbacks() = onMotionTestThread { runTest {
        val observer = SourceObserver()
        var checked by mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        ManagerPressRenderFixture(testScheduler) {
            CompositionLocalProvider(LocalIndication provides observer) {
                SwitchPreference(checked = checked, onCheckedChange = { checked = it; changes += it }, title = "合成开关行")
            }
        }.use { fixture ->
            val body = fixture.subjectBounds().let { Offset(it.left + 32f, it.center.y) }
            tap(fixture, body)
            assertEquals(listOf(true), changes)
            val rowSource = pairedSource(observer.presses)
            val thumb = fixture.nodes().single {
                it.config.contains(SemanticsProperties.ToggleableState) && it.config.contains(SemanticsActions.OnClick)
            }.boundsInRoot.center
            tap(fixture, thumb)
            assertEquals(listOf(true, false), changes)
            assertEquals(4, observer.presses.size, "两个目标各自只有一对 Press/Release")
            val switchSource = pairedSource(observer.presses.drop(2))
            assertNotSame(rowSource, switchSource, "不能把内开关的源冒充整行的源")
            assertEquals(2, observer.sources.distinct().size)
        }
    } }

    private fun tap(fixture: ManagerPressRenderFixture, point: Offset) {
        fixture.environment.press(point)
        fixture.frames(2)
        fixture.environment.release(point)
        fixture.frames(2)
    }

    private fun pairedSource(events: List<SourceEvent>): InteractionSource {
        assertEquals(2, events.size)
        val press = assertIs<PressInteraction.Press>(events[0].interaction)
        assertSame(press, assertIs<PressInteraction.Release>(events[1].interaction).press)
        assertSame(events[0].source, events[1].source)
        return events[0].source
    }

    private data class SourceEvent(val source: InteractionSource, val interaction: Interaction)

    private class SourceObserver : IndicationNodeFactory {
        val sources = mutableListOf<InteractionSource>()
        private val events = mutableListOf<SourceEvent>()
        val presses get() = events.filter { it.interaction is PressInteraction }

        override fun create(interactionSource: InteractionSource): DelegatableNode {
            sources += interactionSource
            return object : Modifier.Node() {
                override fun onAttach() {
                    coroutineScope.launch {
                        interactionSource.interactions.collect { interaction ->
                            events += SourceEvent(interactionSource, interaction)
                            println("s1-miuix-source id=${System.identityHashCode(interactionSource)} event=${interaction.javaClass.simpleName}")
                        }
                    }
                }
            }
        }

        override fun equals(other: Any?) = other === this
        override fun hashCode() = System.identityHashCode(this)
    }
}
