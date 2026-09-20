package com.vowifi.sw.manager.ui.navigation

import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.ui.shouldAutoSelectCalls

object AppNavigator {
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
        return requireNotNull(AppNavigationState.create(state.backStack + subpage))
    }

    fun open(
        state: AppNavigationState,
        subpage: AppRoute.Subpage,
    ): AppNavigationState {
        if (state.topRoute == subpage) return state
        val targetRootState = if (state.currentRoot != subpage.root) {
            selectRoot(state, subpage.root)
        } else {
            state
        }
        return push(targetRootState, subpage)
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
