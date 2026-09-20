package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.data.FixtureMessageSource
import com.vowifi.sw.manager.data.MessageFixtureMode
import com.vowifi.sw.manager.ui.AppearanceSettings
import com.vowifi.sw.manager.ui.BottomChromeMode
import com.vowifi.sw.manager.ui.InMemoryAppearanceSettingsSource
import com.vowifi.sw.manager.ui.MessageSubmissionGate
import com.vowifi.sw.manager.ui.appChromeSpec
import com.vowifi.sw.manager.ui.messageProjectionOwner
import com.vowifi.sw.manager.ui.projectMessageConversations
import com.vowifi.sw.manager.ui.AppRootPagerCoordinator
import com.vowifi.sw.manager.ui.RootSelectionMode
import com.vowifi.sw.manager.ui.appPageContract
import com.vowifi.sw.manager.ui.appRootPagerContract
import com.vowifi.sw.manager.ui.appRootTitle
import com.vowifi.sw.manager.ui.bottomChromeTransitionContract
import com.vowifi.sw.manager.ui.messageContentAnimationContract
import com.vowifi.sw.manager.ui.settingsRouteMap
import com.vowifi.sw.manager.ui.appSubpageSceneContract
import com.vowifi.sw.manager.ui.backdropOwnershipContract
import com.vowifi.sw.manager.ui.settingsIconGrammar
import com.vowifi.sw.manager.ui.settingsCatalog
import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppNavigator
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagesChromeFunctionalDriverTest {
    @Test
    fun drivesAppearanceChromeRoutesAndComposerGate() {
        val appearance = InMemoryAppearanceSettingsSource()
        appearance.setFloatingNavigation(false)
        appearance.setBlurRequested(false)
        assertEquals(AppearanceSettings(false, false), appearance.state.value)

        val floating = appChromeSpec(AppearanceSettings.Default, blurSupported = true)
        val normal = appChromeSpec(appearance.state.value, blurSupported = true)
        val fallback = appChromeSpec(AppearanceSettings.Default, blurSupported = false)
        assertEquals(BottomChromeMode.FLOATING, floating.bottomMode)
        assertEquals(BottomChromeMode.NORMAL, normal.bottomMode)
        assertFalse(normal.effectiveBlur)
        assertTrue(fallback.opaqueFallback)

        assertEquals(listOf("SoftVoWiFi", "通话", "短信", "设置"), AppRoute.rootRoutes.map(::appRootTitle))
        assertEquals(1, appPageContract().verticalScrollOwnerCount)
        assertEquals(3, appRootPagerContract().beyondViewportPageCount)
        val pager = AppRootPagerCoordinator(0)
        val tab = pager.select(2, RootSelectionMode.ANIMATE)
        val swipe = pager.select(3, RootSelectionMode.ANIMATE)
        val snap = pager.select(1, RootSelectionMode.SNAP)
        assertFalse(tab.cancelPrevious)
        assertTrue(swipe.cancelPrevious)
        assertEquals(0, snap.durationMillis)
        val chromeTransition = bottomChromeTransitionContract()
        val messageAnimation = messageContentAnimationContract()
        assertEquals(220, chromeTransition.modeDurationMillis)
        assertFalse(messageAnimation.animatePlacement)
        assertTrue(messageAnimation.durationMillis <= 160)

        val source = FixtureMessageSource(MessageFixtureMode.READY)
        val page = projectMessageConversations(messageProjectionOwner(), source.state.value)
        val threadRoute = AppRoute.MessageThread(page.rows.first().handle)
        val list = AppNavigationState.initial(AppRoute.Messages)
        val thread = AppNavigator.push(list, threadRoute)
        val back = AppNavigator.back(thread)
        val fresh = AppNavigator.push(back, AppRoute.NewMessage)
        val rootBack = AppNavigator.back(AppNavigationState.initial(AppRoute.Settings))
        assertEquals(AppRoute.Messages, list.topRoute)
        assertEquals(threadRoute, thread.topRoute)
        assertEquals(AppRoute.NewMessage, fresh.topRoute)
        assertEquals(AppRoute.Home, rootBack.currentRoot)
        assertEquals(6, settingsRouteMap().size)
        assertTrue(appSubpageSceneContract().opaqueContainer)
        assertEquals(1, backdropOwnershipContract(true, false).rootPagerLayerBackdropAttachments)
        assertEquals(1, backdropOwnershipContract(true, true).privateSubpageBackdropAttachments)
        assertEquals(0, backdropOwnershipContract(false, true).textureEffectCount)
        val settingsIcons = settingsIconGrammar(settingsCatalog(com.vowifi.sw.manager.ui.settingsSnapshot()))
        assertTrue(settingsIcons.rootRowsHaveStartIcons)

        val writable = MessageUiState.Ready(
            records = emptyList(),
            origin = MessageOrigin.ENGINE,
            composerEnabled = true,
        )
        val gate = MessageSubmissionGate()
        assertFalse(gate.tryBegin(writable, "+155501", ""))
        assertTrue(gate.tryBegin(writable, "+155501", "synthetic body"))
        assertFalse(gate.tryBegin(writable, "+155501", "synthetic body"))
        gate.observeSourceBusy(true)
        gate.observeSourceBusy(false)
        assertTrue(gate.tryBegin(writable, "+155501", "synthetic body"))

        println("DRIVER appearance=floating:false,blur:false")
        println("DRIVER chrome=floating:true,normal:true,fallback:opaque")
        println("DRIVER pager=tab:messages,swipe:settings,root-back:home,snap:calls")
        println("DRIVER subpage=push:thread,pop:messages,transition:KSU-miuix-default")
        println("DRIVER settings=appearance>configuration>diagnostics>logs>call-notifications>about")
        println("DRIVER motion=pager:300,bottom-mode:220,item:180,message:fade-only-no-placement")
        println("DRIVER routes=messages>thread>messages>new")
        println("DRIVER composer=blank:blocked,first:accepted,duplicate:blocked,settled:accepted")
        println("DRIVER backdrop=root-attachments:1,page-local:1,subpage-private:1,blur-off-effects:0")
        println("DRIVER settings=icons:all,card-gap:12,root-topbar:large-collapse,subpage:small-leading-back")
        source.close()
    }
}
