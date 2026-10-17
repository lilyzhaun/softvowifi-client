package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlin.test.*

class CallDualSimUiTest {
    @Test fun twoLinesRequireSelectionAndForwardOriginalOutgoingBindingNotPresentation() = callUiEdt {
        CallDualSimUiDriver().use { driver ->
            driver.edit(callNumber)
            assertFalse(driver.enabled("拨打"))
            driver.select(callOwnerB)
            assertEquals(listOf(callOwnerB), driver.source.selections)
            assertEquals(callNumber, driver.number())
            assertTrue(driver.enabled("拨打"))
            driver.click("拨打")
            assertSame(driver.source.bindingB, driver.requests.single().first)
            assertEquals(callOwnerB, driver.source.historyOwners.value.single().owner)
            assertEquals(callNumber, driver.requests.single().second)
            assertTrue(driver.source.fallbackDials.isEmpty())
        }
    }

    @Test fun singletonAuthorityBindsOnlyEmptyDraftWithoutSelectionSideEffect() = callUiEdt {
        val source = CallDualSimUiSource().apply { authorize(callOwnerA); lines.value = lines.value.copy(choices = lines.value.choices.take(1)) }
        CallDualSimUiDriver(source).use { driver ->
            driver.edit(callNumber)
            assertTrue(driver.enabled("拨打"))
            driver.click("拨打")
            assertSame(source.bindingA, driver.requests.single().first)
            assertTrue(source.selections.isEmpty())
        }
    }

    @Test fun partialRosterAndTwoSimOneEngineNeverAutoSelect() = callUiEdt {
        val source = CallDualSimUiSource()
        CallDualSimUiDriver(source).use { driver ->
            driver.edit(callNumber)
            source.lines.value = source.lines.value.copy(choices = source.lines.value.choices.map {
                if (it.owner == callOwnerB) it.copy(available = false, registered = null, syncReady = false) else it
            })
            driver.frame()
            assertFalse(driver.enabled("拨打"))
            assertTrue(source.selections.isEmpty())
            source.lines.value = source.lines.value.copy(choices = source.lines.value.choices.take(1), rosterComplete = false)
            driver.frame()
            assertFalse(driver.enabled("拨打"))
            assertTrue(source.selections.isEmpty())
            driver.select(callOwnerA)
            assertEquals(listOf(callOwnerA), source.selections)
        }
    }

    @Test fun unavailableRootWithEmptyHistoryStillOffersRealPicker() = callUiEdt {
        val source = CallDualSimUiSource().apply { state.value = CallUiState.Unavailable("合成不可用") }
        CallDualSimUiDriver(source, initial = null).use { driver ->
            driver.select(callOwnerB)
            assertEquals(listOf(callOwnerB), source.selections)
            assertSame(CallHistoryState.Empty, source.history.value)
        }
    }

    @Test fun nonemptyUnboundDraftCannotAcquireUnsolicitedOwner() = callUiEdt {
        CallDualSimUiDriver().use { driver ->
            driver.edit(callNumber)
            driver.source.authorize(callOwnerB)
            driver.frame()
            assertFalse(driver.enabled("拨打"))
            assertEquals(callNumber, driver.number())
            driver.select(callOwnerB)
            assertTrue(driver.enabled("拨打"))
        }
    }

    @Test fun incomingBDoesNotRetargetOutgoingADraftOrCurrentCallLabel() = callUiEdt {
        CallDualSimUiDriver().use { driver ->
            driver.select(callOwnerA); driver.edit(callNumber)
            driver.source.state.value = driver.source.ready(driver.source.bindingB, CallPhase.Ringing)
            driver.source.lines.value = driver.source.lines.value.copy(outgoingBinding = null)
            driver.frame()
            assertTrue(driver.hasText("当前通话 · Synthetic B"))
            assertEquals(callOwnerA, driver.source.lines.value.selectedOwner)
            driver.source.state.value = driver.source.ready()
            driver.source.authorize(callOwnerA)
            driver.route(AppRoute.Dialpad)
            assertEquals(callNumber, driver.number())
            driver.click("拨打")
            assertSame(driver.source.bindingA, driver.requests.single().first)
        }
    }

    @Test fun backendSelectionChangeRequiresExplicitRebindAndPreservesNumber() = callUiEdt {
        CallDualSimUiDriver().use { driver ->
            driver.select(callOwnerA); driver.edit(callNumber)
            driver.source.authorize(callOwnerB)
            driver.source.lines.value = driver.source.lines.value.copy(choices = driver.source.lines.value.choices.takeLast(1))
            driver.frame()
            assertFalse(driver.enabled("拨打"))
            driver.select(callOwnerB)
            assertEquals(callNumber, driver.number())
            driver.click("拨打")
            assertSame(driver.source.bindingB, driver.requests.single().first)
        }
    }

    @Test fun lostOwnerAndLoadingKeepDraftDisabledAndRecoverable() = callUiEdt {
        CallDualSimUiDriver().use { driver ->
            driver.select(callOwnerA); driver.edit(callNumber)
            driver.source.lines.value = driver.source.lines.value.copy(outgoingBinding = null, choices = emptyList(), rosterComplete = false)
            driver.source.state.value = CallUiState.Loading; driver.frame()
            assertEquals(callNumber, driver.number())
            assertFalse(driver.enabled("拨打"))
            driver.capture("unavailable-retained")
        }
    }

    @Test fun restorationOfOwnerIsNotAuthorization() = callUiEdt {
        val source = CallDualSimUiSource()
        val saved = CallDualSimUiDriver(source).use { driver ->
            driver.select(callOwnerA); driver.edit(callNumber); driver.save()
        }
        source.authorize(callOwnerB)
        CallDualSimUiDriver(source, restored = saved).use { driver ->
            assertEquals(callNumber, driver.number())
            assertFalse(driver.enabled("拨打"))
            driver.select(callOwnerB)
            driver.click("拨打")
            assertSame(source.bindingB, driver.requests.single().first)
        }
    }

    @Test fun submittedRequestRemainsPinnedAcrossLaterPickerFrame() = callUiEdt {
        CallDualSimUiDriver().use { driver ->
            driver.select(callOwnerA); driver.edit(callNumber); driver.click("拨打")
            driver.select(callOwnerB)
            assertSame(driver.source.bindingA, driver.requests.single().first)
            assertEquals(callNumber, driver.number())
            driver.capture("selected-line")
            driver.route(AppRoute.Calls); driver.click("出向线路"); driver.capture("picker")
        }
    }

    @Test fun busyFreezesInputAndExistingPermissionEntryRemainsWired() = callUiEdt {
        CallDualSimUiDriver().use { driver ->
            driver.select(callOwnerA); driver.edit(callNumber)
            driver.source.state.value = driver.source.ready(busy = true)
            driver.source.lines.value = driver.source.lines.value.copy(outgoingBinding = null)
            driver.frame()
            assertFalse(driver.enabled("1"))
            assertFalse(driver.enabled("正在准备"))
            driver.click("应用权限")
            assertEquals(1, driver.permissionClicks)
            assertEquals(callNumber, driver.number())
        }
    }

    @Test fun missingPlatformDialCallbackCannotUseEngineFallback() = callUiEdt {
        CallDualSimUiDriver(callback = false).use { driver ->
            driver.select(callOwnerA); driver.edit(callNumber)
            assertFalse(driver.enabled("拨打"))
            assertTrue(driver.hasText("拨号授权入口不可用"))
            assertTrue(driver.source.fallbackDials.isEmpty())
        }
    }

    @Test fun actualAppDialerGeometryIsStableAcrossEditsWhenRegisteredAndUnavailable() = callUiEdt {
        listOf(true, false).forEach { registered ->
            val source = CallDualSimUiSource().apply {
                if (registered) authorize(callOwnerA) else state.value = CallUiState.Unavailable("合成不可用")
            }
            CallDualSimUiDriver(source).use { driver ->
                val geometry = driver.geometry()
                listOf("1", "12", "1", "").forEach { number ->
                    driver.edit(number)
                    assertEquals(geometry, driver.geometry(), "registered=$registered, length=${number.length}")
                }
            }
        }
    }

    @Test fun partialRosterDoesNotClaimMissingSimAndUnknownRegistrationIsHonest() = callUiEdt {
        val source = CallDualSimUiSource().apply {
            lines.value = lines.value.copy(rosterComplete = false, choices = lines.value.choices.map {
                it.copy(active = false, available = false, registered = null, syncReady = false)
            })
        }
        CallDualSimUiDriver(source).use { driver ->
            driver.route(AppRoute.Calls); driver.click("出向线路")
            assertTrue(driver.hasText("SIM 状态未知 · 引擎不可用 · 注册未知 · 状态待确认"))
            assertFalse(driver.hasText("SIM 未启用 · 引擎不可用 · 注册未知 · 状态待确认"))
        }
    }

    @Test fun absentAuthorizationAfterExplicitSelectionRetainsDraftAndNeverFallsBack() = callUiEdt {
        CallDualSimUiDriver().use { driver ->
            driver.select(callOwnerA); driver.edit(callNumber)
            driver.source.lines.value = driver.source.lines.value.copy(outgoingBinding = null)
            driver.source.state.value = driver.source.ready().copy(sync = CallSyncState.Unregistered, operationMessage = "IMS 未注册")
            driver.frame()
            assertFalse(driver.enabled("拨打"))
            assertEquals(callNumber, driver.number())
            assertTrue(driver.hasText("原线路暂不可拨出，号码已保留\nIMS 未注册"))
            driver.source.authorize(callOwnerB); driver.frame()
            assertFalse(driver.enabled("拨打"))
            driver.select(callOwnerB)
            assertEquals(callNumber, driver.number())
            driver.click("返回"); driver.click("拨号盘")
            assertEquals(callNumber, driver.number())
            assertFalse(driver.enabled("拨打"), "仅更新出向binding不能覆盖尚未同步的当前state")
            assertTrue(driver.requests.isEmpty())
            driver.source.state.value = driver.source.ready(driver.source.bindingB)
            driver.frame()
            driver.click("拨打")
            assertSame(driver.source.bindingB, driver.requests.single().first)
        }
    }

    @Test fun acceptedOutgoingCallClearsTheSubmittedDraftButFailedDialKeepsIt() = callUiEdt {
        CallDualSimUiDriver().use { driver ->
            driver.select(callOwnerA); driver.edit(callNumber); driver.click("拨打")
            assertEquals(callNumber, driver.requests.single().second)
            // Optimistic dialing has no engine token yet and may still fail; the number stays for a retry.
            driver.source.state.value = outgoing(driver, CallBinding("synthetic-a", null, 2))
            driver.source.lines.value = driver.source.lines.value.copy(outgoingBinding = null)
            driver.frame()
            driver.source.state.value = driver.source.ready()
            driver.source.authorize(callOwnerA)
            driver.route(AppRoute.Dialpad)
            assertEquals(callNumber, driver.number())

            driver.click("拨打")
            driver.source.state.value = outgoing(driver, CallBinding("synthetic-a", "call-a", 3))
            driver.source.lines.value = driver.source.lines.value.copy(outgoingBinding = null)
            driver.frame()
            driver.source.state.value = driver.source.ready()
            driver.source.authorize(callOwnerA)
            driver.route(AppRoute.Dialpad)
            assertEquals("", driver.number())
        }
    }

    @Test fun outgoingCallNotDialedFromTheDraftKeepsTheTypedNumber() = callUiEdt {
        CallDualSimUiDriver().use { driver ->
            driver.select(callOwnerA); driver.edit(callNumber)
            driver.source.state.value = outgoing(driver, CallBinding("synthetic-a", "call-a", 2))
            driver.source.lines.value = driver.source.lines.value.copy(outgoingBinding = null)
            driver.frame()
            driver.source.state.value = driver.source.ready()
            driver.source.authorize(callOwnerA)
            driver.route(AppRoute.Dialpad)
            assertEquals(callNumber, driver.number())
        }
    }

    private fun outgoing(driver: CallDualSimUiDriver, binding: CallBinding) =
        driver.source.ready(binding, CallPhase.Dialing).copy(direction = CallDirection.OUTGOING)
}
