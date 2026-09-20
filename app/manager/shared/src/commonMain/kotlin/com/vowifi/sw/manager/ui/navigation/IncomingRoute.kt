package com.vowifi.sw.manager.ui.navigation

import com.vowifi.sw.manager.ui.AppDestination

object IncomingRoute {
    fun initial(alias: String?): AppRoute.Root =
        AppDestination.fromAlias(alias)?.rootRoute ?: AppRoute.Home

    fun onNewIntent(alias: String?): AppRoute.Root? =
        AppDestination.fromAlias(alias)?.rootRoute

    fun applyNewIntent(
        state: AppNavigationState,
        alias: String?,
    ): AppNavigationState {
        val root = onNewIntent(alias) ?: return state
        return AppNavigator.selectRoot(state, root)
    }
}
