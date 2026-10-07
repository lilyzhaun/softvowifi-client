package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.ui.navigation.*
import kotlin.test.*

class CallHistoryGroupTest {
    private val a = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val b = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    private fun call(phone: String?, time: Long) = CallRecord(CallDirection.INCOMING, phone?.let(CallPeerPhone::parse), time,
        null, time + 1000, null, CallOutcome.MISSED)
    @Test fun onlyKnownNumbersAggregateAndUnknownCallsRemainIndependent() {
        val groups = callHistoryGroups(CallHistoryState.Ready(listOf(call("155501", 1000), call("+155501", 3000),
            call("155502", 2000), call(null, 4000), call(null, 4000)), a))
        assertEquals(4, groups.size)
        assertEquals(listOf(3000L, 1000L), groups.single { it.peer?.digits() == "155501" }.records.map { it.startedAtMs })
        assertEquals(2, groups.count { it.peer == null })
    }
    @Test fun routeHandleIsOpaqueAndDifferentForSameNumberOnAnotherOwner() {
        val record = call("155501", 1000)
        val first = callHistoryGroups(CallHistoryState.Ready(listOf(record), a)).single()
        val second = callHistoryGroups(CallHistoryState.Ready(listOf(record), b)).single()
        assertNotEquals(first.handle, second.handle)
        val route = AppRoute.CallHistoryDetail(first.handle)
        val saved = AppNavigator.push(AppNavigationState.initial(AppRoute.Calls), route).save()
        assertFalse(saved.contains("155501"))
        assertFalse(saved.contains(a.wire()))
    }
    @Test fun detailDurationIncludesSecondsForLongCallsAndUnknownIsNotZero() {
        assertEquals("1 小时 1 分 1 秒", exactCallDuration(3661))
        assertEquals("时长未知", exactCallDuration(null))
        assertTrue(exactCallTime(1000).endsWith(":01"))
    }
}
