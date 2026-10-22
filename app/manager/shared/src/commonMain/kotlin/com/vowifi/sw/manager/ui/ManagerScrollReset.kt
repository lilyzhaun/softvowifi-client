package com.vowifi.sw.manager.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.vowifi.sw.manager.data.SimOwnerId

/** Observe user conditions, not result contents; the initial/restored position is not a change. */
@Composable
internal fun ResetManagerScrollOnConditionChange(state: LazyListState, condition: Any?) {
    val previous = remember(state) { ScrollCondition(condition) }
    LaunchedEffect(state, condition) {
        if (previous.value != condition) {
            previous.value = condition
            // A condition can change with the list content during apply. Request the next
            // measure instead of synchronously re-entering a pending subcomposition.
            state.requestScrollToItem(0)
        }
    }
}

/** Loading/unknown ownership is not a selection. The first confirmed owner is a baseline. */
@Composable
internal fun OnConfirmedManagerOwnerChange(owner: SimOwnerId?, onChange: suspend () -> Unit) {
    val previous = remember { ScrollCondition(owner) }
    LaunchedEffect(owner) {
        if (owner != null) {
            val changed = previous.value != null && previous.value != owner
            previous.value = owner
            if (changed) onChange()
        }
    }
}

private class ScrollCondition(var value: Any?)
