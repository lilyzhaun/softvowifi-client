package com.vowifi.sw.manager.ui.navigation

import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.ui.shouldAutoSelectCalls

object AppNavigator {
    /** Contextual push: retain call detail below SMS so the stock Nav3 forward/back motion stays continuous. */
    fun openCallMessage(state: AppNavigationState, target: AppRoute.Subpage): AppNavigationState {
        if (state.topRoute !is AppRoute.CallHistoryDetail ||
            (target !is AppRoute.MessageThread && target != AppRoute.NewMessage)) return state
        return AppNavigationState.create(state.backStack + target) ?: state
    }

    fun selectRoot(
        state: AppNavigationState,
        root: AppRoute.Root,
    ): AppNavigationState {
        if (state.backStack == listOf(root)) return state
        return requireNotNull(AppNavigationState.create(listOf(root)))
    }

    fun push(
        state: AppNavigationState,
        subpage: AppRoute.Subpage,
    ): AppNavigationState {
        if (subpage.root != state.currentRoot || state.topRoute == subpage) return state
        return AppNavigationState.create(state.backStack + subpage) ?: state
    }

    fun open(
        state: AppNavigationState,
        subpage: AppRoute.Subpage,
    ): AppNavigationState {
        if (state.topRoute == subpage) return state
        // D5: internal opens retain their source root; reopening a child pops to its existing slot.
        val existingIndex = state.backStack.indexOf(subpage)
        if (existingIndex > 0) {
            return AppNavigationState.create(state.backStack.take(existingIndex + 1)) ?: state
        }
        val currentBranch = (state.topRoute as? AppRoute.Subpage)?.root
        val base = if (currentBranch != null && currentBranch != subpage.root) {
            listOf(state.currentRoot)
        } else {
            state.backStack
        }
        return AppNavigationState.create(base + subpage) ?: state
    }

    fun replaceTop(state: AppNavigationState, subpage: AppRoute.Subpage): AppNavigationState {
        val previous = state.topRoute as? AppRoute.Subpage ?: return state
        if (previous == subpage || previous.root != subpage.root) return state
        return AppNavigationState.create(state.backStack.dropLast(1) + subpage) ?: state
    }

    fun back(state: AppNavigationState): AppNavigationState {
        if (state.backStack.size == 1) {
            return if (state.currentRoot == AppRoute.Home) state else AppNavigationState.initial(AppRoute.Home)
        }
        return requireNotNull(AppNavigationState.create(state.backStack.dropLast(1)))
    }

    fun forCallTransition(
        state: AppNavigationState,
        previous: CallPhase?,
        current: CallPhase?,
    ): AppNavigationState = if (shouldAutoSelectCalls(previous, current) ||
        (state.topRoute == AppRoute.Dialpad && current != null && current != CallPhase.Idle)
    ) {
        selectRoot(state, AppRoute.Calls)
    } else {
        state
    }
}
