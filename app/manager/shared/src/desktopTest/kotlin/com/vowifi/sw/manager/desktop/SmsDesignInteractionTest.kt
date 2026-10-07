package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigationevent.*
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import kotlinx.coroutines.test.*
import org.jetbrains.skia.Bitmap
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SmsDesignInteractionTest {
    private var time = 0L
    @Test fun sendTargetDoesNotShrinkAndCanceledDragDoesNotSend() = runTest {
        var sends=0
        val scene=ImageComposeScene(width=390,height=200,coroutineContext=UnconfinedTestDispatcher(testScheduler)) {
            SoftVoWiFiTheme(false) { MessageComposer("合成草稿",MessageComposeSpec(MessageComposeMode.ENABLED,null),
                MessageImeOwner.COMPOSE_COMPOSER_PADDING,{},false,true,{}, { sends++ }) }
        }
        try {
            frame(scene,testScheduler,30)
            val before=send(scene).boundsInRoot
            scene.sendPointerEvent(PointerEventType.Press,before.center);frame(scene,testScheduler,12)
            assertEquals(before,send(scene).boundsInRoot,"发送按压只缩放画面，不缩小命中区")
            fun faceWidth():Float {
                fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
                return scene.semanticsOwners.flatMap {tree(it.unmergedRootSemanticsNode)}
                    .single {it.config.getOrNull(SemanticsProperties.TestTag)=="sms-send-face"}.boundsInRoot.width
            }
            assertTrue(faceWidth()<before.width-.5f,"实际绘制层应跟手缩小")
            scene.sendPointerEvent(PointerEventType.Move,Offset(0f,190f));frame(scene,testScheduler,2)
            scene.sendPointerEvent(PointerEventType.Release,Offset(0f,190f));frame(scene,testScheduler,30)
            assertEquals(0,sends)
            scene.sendPointerEvent(PointerEventType.Press,before.center);frame(scene,testScheduler,12)
            scene.sendPointerEvent(PointerEventType.Release,before.center)
            val released=List(50){frame(scene,testScheduler,1);faceWidth()}
            assertTrue(released.max()>before.width+.05f && released.max()<before.width*1.015f,"轻微回弹而非夸张放大")
            assertEquals(before.width,released.last(),.05f)
            assertEquals(1,sends)
        } finally { scene.close() }
    }

    @Test fun inactiveComposerAndCapturedSubmissionCannotEditOrSend() = runTest {
        val owner=SimOwnerId.parse("sim-owner-v1:"+"a".repeat(43))!!
        var active by mutableStateOf(true)
        var body by mutableStateOf("合成草稿")
        var recipient by mutableStateOf("+12025550123")
        var sends=0
        val scene=ImageComposeScene(width=390,height=740,coroutineContext=UnconfinedTestDispatcher(testScheduler)) {
            CompositionLocalProvider(LocalAppRouteActive provides active) { SoftVoWiFiTheme(false) {
                NewMessageScreen(MessageUiState.Ready(emptyList(),MessageOrigin.ENGINE,composerEnabled=true,ownerId=owner),
                    recipient,body,{recipient=it}, {body=it},{body=""},{_,_->sends++})
            } }
        }
        try {
            frame(scene,testScheduler,30)
            val stale=send(scene).config[SemanticsActions.OnClick].action!!
            val editor=nodes(scene).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("短信内容")==true && it.config.contains(SemanticsActions.SetText) }
            val staleEdit=editor.config[SemanticsActions.SetText].action!!
            val staleRecipient=nodes(scene).first {it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("输入电话号码")==true && it.config.contains(SemanticsActions.SetText)}
                .config[SemanticsActions.SetText].action!!
            active=false;frame(scene,testScheduler,20)
            assertTrue(send(scene).config.contains(SemanticsProperties.Disabled))
            stale();staleEdit(AnnotatedString("退出中的输入"));staleRecipient(AnnotatedString("12345"));frame(scene,testScheduler,10)
            assertEquals(0,sends);assertEquals("合成草稿",body)
            assertEquals("+12025550123",recipient)
        } finally { scene.close() }
    }

    @Test fun threadSearchBackClosesSearchWithoutNavigating() = runTest {
        var open by mutableStateOf(true);var query by mutableStateOf("合成")
        var backs=0
        val input=object:NavigationEventInput(){fun back()=dispatchOnBackCompleted()}
        val owner=object:NavigationEventDispatcherOwner {
            override val navigationEventDispatcher=NavigationEventDispatcher(onBackCompletedFallback={backs++}).apply{addInput(input)}
        }
        val scene=ImageComposeScene(width=390,height=300,coroutineContext=UnconfinedTestDispatcher(testScheduler)) {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) { SoftVoWiFiTheme(false) {
                MessageThreadSearch(open,query,1,{open=!open;if(!open)query=""},{query=it})
            } }
        }
        try {
            frame(scene,testScheduler,30);input.back();frame(scene,testScheduler,30)
            assertFalse(open);assertEquals("",query);assertEquals(0,backs)
            input.back();assertEquals(1,backs)
        } finally {scene.close();owner.navigationEventDispatcher.dispose()}
    }

    @Test fun newMessageUsesPhoneCanvasAndKeepsCircularSend() = runTest {
        val scene=ImageComposeScene(width=390,height=740,coroutineContext=UnconfinedTestDispatcher(testScheduler)) {
            SoftVoWiFiTheme(false) { NewMessageScreen(MessageUiState.Unavailable("合成只读"),"","",{},{},{},{_,_->}) }
        }
        try {
            frame(scene,testScheduler,30)
            scene.render(time).use{ image-> Bitmap.makeFromImage(image).use{assertEquals(0xFFF3F7F5.toInt(),it.getColor(2,200))} }
            assertEquals(48f,send(scene).boundsInRoot.width,.1f)
        } finally {scene.close()}
    }
    @Test fun shortFormOverscrollKeepsComposerFixedAndZeroMotionSuppressesTravel() = runTest {
        var factor by mutableStateOf(1f)
        val motion=object:MotionDurationScale{override val scaleFactor get()=factor}
        val window=object:androidx.compose.ui.platform.WindowInfo {
            override val isWindowFocused=true
            override val containerSize=androidx.compose.ui.unit.IntSize(390,740)
            override val containerDpSize=androidx.compose.ui.unit.DpSize(390.dp,740.dp)
        }
        val scene=ImageComposeScene(width=390,height=740,coroutineContext=UnconfinedTestDispatcher(testScheduler)+motion) {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalWindowInfo provides window) { SoftVoWiFiTheme(false) {
                NewMessageScreen(MessageUiState.Unavailable("合成只读"),"","",{},{},{},{_,_->error("滑动不能发送")})
            } }
        }
        fun recipient()=nodes(scene).first {it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("输入电话号码")==true}
        try {
            frame(scene,testScheduler,30)
            val initial=recipient().boundsInRoot;val composer=send(scene).boundsInRoot
            for (enabled in listOf(true,false)) {
                factor=if(enabled)1f else 0f;frame(scene,testScheduler,5)
                val start=Offset(8f,140f)
                scene.sendPointerEvent(PointerEventType.Press,start,type=androidx.compose.ui.input.pointer.PointerType.Touch)
                repeat(8){i->scene.sendPointerEvent(PointerEventType.Move,start+Offset(0f,(i+1)*12f),type=androidx.compose.ui.input.pointer.PointerType.Touch);frame(scene,testScheduler,1)}
                if(enabled)assertTrue(recipient().boundsInRoot.top>initial.top+8f) else assertEquals(initial,recipient().boundsInRoot)
                assertEquals(composer,send(scene).boundsInRoot,"回弹只作用内容，输入/发送停在原位")
                scene.sendPointerEvent(PointerEventType.Release,start+Offset(0f,96f),type=androidx.compose.ui.input.pointer.PointerType.Touch)
                frame(scene,testScheduler,100);assertEquals(initial.top,recipient().boundsInRoot.top,1f)
            }
        } finally {scene.close()}
    }
    private fun frame(scene:ImageComposeScene,s:TestCoroutineScheduler,n:Int){repeat(n){s.advanceTimeBy(16);s.runCurrent();time+=16_000_000L;scene.render(time).close()}}
    private fun nodes(scene:ImageComposeScene)=scene.semanticsOwners.flatMap { o ->
        fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
        tree(o.rootSemanticsNode)
    }
    private fun send(scene:ImageComposeScene)=nodes(scene).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("发送短信")==true && it.config.contains(SemanticsActions.OnClick) }
}
