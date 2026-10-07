package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.navigation.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SmsDesignCaptureTest {
    @Test fun renderProductionSmsFlowWithSyntheticValues() {
        val owner=SimOwnerId.parse("sim-owner-v1:"+"a".repeat(43))!!
        val peer="+12025550123"
        val now=kotlin.time.Clock.System.now().toEpochMilliseconds()
        val state=MessageUiState.Ready(listOf(
            MessageRecord.Incoming("one",peer,"明天下午三点见，可以吗？",now-180000),
            MessageRecord.Outgoing("two",peer,"可以，到时联系。",now-120000,OutgoingMessageStatus.ACCEPTED),
            MessageRecord.Incoming("three",peer,"好的，明天见。",now-60000)
        ),MessageOrigin.ENGINE,composerEnabled=true,ownerId=owner)
        val choices=MessageHistoryChoices(listOf(MessageHistoryLine(owner,3,true,simLabel="SIM 1 · 合成线路")),owner,true)
        val lifecycle=object:LifecycleOwner{override val lifecycle=LifecycleRegistry.createUnsafe(this).apply{currentState=Lifecycle.State.RESUMED}}
        val handle=StoredMessageConversation(owner,peer.drop(1)).handle
        val folder=Files.createDirectories(Path.of("build/reports/sms-design"))
        fun capture(name:String,content:@Composable ()->Unit){
            val scene=ImageComposeScene(width=390,height=844){CompositionLocalProvider(LocalLifecycleOwner provides lifecycle){SoftVoWiFiTheme(false,content)}}
            var time=0L
            try{
                repeat(40){time+=50_000_000L;scene.render(time).close()}
                scene.render(time).use{image->image.encodeToData()!!.use{Files.write(folder.resolve("$name.png"),it.bytes)}}
            }finally{scene.close()}
        }
        capture("list"){
            AppShell(AppNavigationState.initial(AppRoute.Messages),{},appearanceSettings=AppearanceSettings.Default.copy(floatingNavigation=false),blurSupported=false){
                MessagesScreen(state,{},{},historyChoices=choices,hasRegisteredEngine=true)
            }
        }
        capture("thread"){
            AppSubpageScene(AppRoute.MessageThread(handle),appChromeSpec(AppearanceSettings.Default,false),titleOverride=peer,onBack={}){
                MessageThreadScreen(handle,state,{_,_->error("预览不能发送")},historyChoices=choices)
            }
        }
        capture("new"){
            AppSubpageScene(AppRoute.NewMessage,appChromeSpec(AppearanceSettings.Default,false),onBack={}){
                NewMessageScreen(state,peer,"合成草稿",{},{},{},{_,_->error("预览不能发送")},historyChoices=choices)
            }
        }
    }
}
