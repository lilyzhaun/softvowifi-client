package com.vowifi.sw.manager.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ManagerPressIdleInvalidationTest {
    @Test fun closingAnIdleGateDoesNotInvalidateDrawingState() = runTest {
        val gate = mutableStateOf(true)
        val core = ManagerPressMotionCore(ManagerPressStyle.Icon, gate)
        core.connect(backgroundScope, MutableInteractionSource())
        Snapshot.sendApplyNotifications(); runCurrent()
        val writes = mutableSetOf<Any>()
        val observer = Snapshot.registerGlobalWriteObserver { if (it !== gate) writes += it }
        try {
            repeat(3) {
                gate.value = false
                Snapshot.sendApplyNotifications(); runCurrent()
                Snapshot.sendApplyNotifications(); runCurrent()
                assertTrue(writes.isEmpty(), "An untouched resting control must not replace animation state when its route closes")
                gate.value = true
                Snapshot.sendApplyNotifications(); runCurrent()
            }
        } finally {
            observer.dispose()
            core.disconnect()
        }
    }
}
