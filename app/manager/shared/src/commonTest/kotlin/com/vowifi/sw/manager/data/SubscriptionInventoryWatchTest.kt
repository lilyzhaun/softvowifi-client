package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubscriptionInventoryWatchTest {
    @Test
    fun twoIndependentWatchersBothReceiveChange() {
        val inventory = FakeSubscriptionInventory()
        var firstTicks = 0
        var secondTicks = 0

        inventory.startWatching { firstTicks += 1 }
        inventory.startWatching { secondTicks += 1 }
        inventory.emitChange()

        assertEquals(1, firstTicks)
        assertEquals(1, secondTicks)
    }

    @Test
    fun stoppingOneWatcherPreservesTheOther() {
        val inventory = FakeSubscriptionInventory()
        var firstTicks = 0
        var secondTicks = 0
        val first = inventory.startWatching { firstTicks += 1 }
        inventory.startWatching { secondTicks += 1 }

        first.stop()
        first.stop()
        inventory.emitChange()

        assertEquals(0, firstTicks)
        assertEquals(1, secondTicks)
    }

    @Test
    fun stoppingLastWatcherRemovesAll() {
        val inventory = FakeSubscriptionInventory()
        var ticks = 0
        val first = inventory.startWatching { ticks += 1 }
        val second = inventory.startWatching { ticks += 1 }

        first.stop()
        second.stop()
        inventory.emitChange()

        assertEquals(0, ticks)
        assertNull(inventory.onChanged)
        assertEquals(2, inventory.stopWatchingCalls)
    }
}
