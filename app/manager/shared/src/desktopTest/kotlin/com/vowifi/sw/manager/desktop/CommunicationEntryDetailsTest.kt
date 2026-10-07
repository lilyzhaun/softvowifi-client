package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Bitmap
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class CommunicationEntryDetailsTest {
    private var time=0L
    @Test fun offlineRootsHideCreationButKeepSearchAndHistory() {
        for (sms in listOf(false,true)) {
            var opens=0
            val scene=ImageComposeScene(width=390,height=844) { SoftVoWiFiTheme(false) {
                if(sms) MessagesScreen(MessageUiState.Unavailable("离线"),{},{opens++})
                else CallsScreen(CallUiState.Unavailable("离线"),true,{},CallHistoryState.Empty,{opens++},{},{},{},{})
            } }
            try {
                frame(scene)
                assertFalse(nodes(scene).any {it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(if(sms)"新建短信" else "拨号盘")==true})
                val search=nodes(scene).single {it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(if(sms)"搜索短信" else "搜索通话记录")==true && it.config.contains(SemanticsActions.OnClick)}
                search.config[SemanticsActions.OnClick].action!!.invoke();frame(scene)
                assertEquals(1,nodes(scene).count {it.config.contains(SemanticsActions.SetText)})
                assertEquals(0,opens)
            }finally{scene.close()}
        }
    }
    @Test fun smsSearchStartsCollapsedAndCancelClearsActualQuery() {
        val owner=SimOwnerId.parse("sim-owner-v1:"+"a".repeat(43))!!
        val queries=mutableListOf<String>()
        val scene=ImageComposeScene(width=390,height=844) { SoftVoWiFiTheme(false) {
            MessagesScreen(MessageUiState.Ready(emptyList(),MessageOrigin.ENGINE,composerEnabled=true,ownerId=owner),{},{},onSearchHistory=queries::add)
        } }
        try {
            frame(scene);assertFalse(nodes(scene).any {it.config.contains(SemanticsActions.SetText)})
            val search=nodes(scene).single {it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("搜索短信")==true && it.config.contains(SemanticsActions.OnClick)}
            assertTrue(search.boundsInRoot.center.x>320f && search.boundsInRoot.center.y<100f)
            search.config[SemanticsActions.OnClick].action!!.invoke();frame(scene)
            val edit=nodes(scene).single {it.config.contains(SemanticsActions.SetText)}.config[SemanticsActions.SetText].action!!
            edit.invoke(AnnotatedString("合成搜索"));frame(scene)
            assertEquals("合成搜索",queries.last())
            nodes(scene).single {it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("取消")==true && it.config.contains(SemanticsActions.OnClick)}.config[SemanticsActions.OnClick].action!!.invoke();frame(scene)
            assertEquals("",queries.last());assertFalse(nodes(scene).any {it.config.contains(SemanticsActions.SetText)})
            edit.invoke(AnnotatedString("迟到输入"));frame(scene);assertEquals("",queries.last())
        }finally{scene.close()}
    }
    @Test fun registrationTruthControlsBothRootsWithoutRequiringTheSelectedLine() {
        val owner=SimOwnerId.parse("sim-owner-v1:"+"a".repeat(43))!!
        val second=SimOwnerId.parse("sim-owner-v1:"+"b".repeat(43))!!
        val registered=CallLineChoice(owner,"合成线路",true,true,true,true,false,true)
        assertTrue(hasRegisteredCommunicationEngine(CallLineState(listOf(registered),selectedOwner=second)))
        for (line in listOf(registered.copy(registered=null),registered.copy(registered=false),registered.copy(running=false),
            registered.copy(active=false),registered.copy(available=false),registered.copy(syncReady=false))) {
            assertFalse(hasRegisteredCommunicationEngine(CallLineState(listOf(line))))
        }
        for(sms in listOf(false,true)) {
            var present by mutableStateOf(false)
            val scene=ImageComposeScene(width=390,height=844){SoftVoWiFiTheme(false){
                if(sms) MessagesScreen(MessageUiState.Unavailable("历史线路"),{},{},hasRegisteredEngine=present)
                else CallsScreen(CallUiState.Unavailable("历史线路"),true,{},CallHistoryState.Empty,{},{},{},{},{},hasRegisteredEngine=present)
            }}
            try {
                fun entries()=nodes(scene).count {it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(if(sms)"新建短信" else "拨号盘")==true && it.config.contains(SemanticsActions.OnClick)}
                frame(scene);assertEquals(0,entries());present=true;frame(scene);assertEquals(1,entries());present=false;frame(scene);assertEquals(0,entries())
            }finally{scene.close()}
        }
    }
    @Test fun inputFocusChangesOnlySurfaceTreatmentNotFieldGeometry() {
        val scene=ImageComposeScene(width=340,height=110){SoftVoWiFiTheme(false){PhoneStyle{
            Box(Modifier.fillMaxSize().background(phoneColors().canvas).padding(16.dp)) {
                CommunicationInput("",{},"搜索短信",Modifier.fillMaxWidth(),singleLine=true,leading=CommunicationSearch)
            }
        }}}
        fun field()=nodes(scene).single {it.config.contains(SemanticsActions.SetText)}
        fun pixel(x:Int,y:Int)=scene.render(time).use{image->Bitmap.makeFromImage(image).use{it.getColor(x,y)}}
        try {
            frame(scene);val initial=field().boundsInRoot
            val x=initial.left.toInt();val y=initial.center.y.toInt();val resting=pixel(x,y)
            field().config[SemanticsActions.OnClick].action!!.invoke();frame(scene)
            assertEquals(initial,field().boundsInRoot)
            assertNotEquals(resting,pixel(x,y),"聚焦应有可读描边回应")
            val folder=java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/reports/communication-details"))
            scene.render(time).use{image->image.encodeToData()!!.use{java.nio.file.Files.write(folder.resolve("search-focused.png"),it.bytes)}}
        }finally{scene.close()}
    }
    private fun frame(scene:ImageComposeScene){repeat(24){time+=16_000_000L;scene.render(time).close()}}
    private fun nodes(scene:ImageComposeScene)=scene.semanticsOwners.flatMap{o->
        fun tree(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::tree)
        tree(o.rootSemanticsNode)
    }
}
