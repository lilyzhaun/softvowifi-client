package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class RootPagerEasing {
    SPRING,
}

internal enum class RootSelectionMode {
    ANIMATE,
    SNAP,
}

internal data class AppRootPagerContract(
    val pages: List<AppRoute.Root>,
    val beyondViewportPageCount: Int,
    val stateOwnerCount: Int,
    val rootContentCrossfade: Boolean,
    val selectionDurationMillis: Int,
    val easing: RootPagerEasing,
    val springStiffness: Float,
    val springDampingRatio: Float,
    val springVisibilityThreshold: Float,
)

internal fun appRootPagerContract(): AppRootPagerContract = AppRootPagerContract(
    pages = AppRoute.rootRoutes,
    beyondViewportPageCount = 3,
    stateOwnerCount = 1,
    rootContentCrossfade = false,
    selectionDurationMillis = 0,
    easing = RootPagerEasing.SPRING,
    springStiffness = 322.27f,
    springDampingRatio = 0.9f,
    springVisibilityThreshold = 0.5f,
)

internal data class RootSelectionPlan(
    val page: Int,
    val cancelPrevious: Boolean,
    val durationMillis: Int,
    val usesSpring: Boolean,
)

internal class AppRootPagerCoordinator(initialPage: Int) {
    var selectedPage: Int = initialPage
        private set
    private var programmaticTarget: Int? = null
    var inFlight: Boolean = false
        private set
    val hasProgrammaticTarget: Boolean
        get() = programmaticTarget != null

    fun select(page: Int, mode: RootSelectionMode): RootSelectionPlan {
        val plan = RootSelectionPlan(
            page = page,
            cancelPrevious = inFlight,
            durationMillis = 0,
            usesSpring = mode == RootSelectionMode.ANIMATE,
        )
        selectedPage = page
        programmaticTarget = page.takeIf { mode == RootSelectionMode.ANIMATE }
        inFlight = mode == RootSelectionMode.ANIMATE
        return plan
    }

    fun observePager(targetPage: Int, settledPage: Int, scrollInProgress: Boolean) {
        if (scrollInProgress) {
            selectedPage = programmaticTarget ?: targetPage
            inFlight = true
            return
        }
        selectedPage = settledPage
        programmaticTarget = null
        inFlight = false
    }
}

@Stable
internal class AppRootPagerState(
    val pagerState: PagerState,
    private val scope: CoroutineScope,
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set
    private var selectionJob: Job? = null
    private val coordinator = AppRootPagerCoordinator(pagerState.currentPage)

    fun selectRoot(root: AppRoute.Root, mode: RootSelectionMode) {
        val page = pagerPageForRoot(root)
        val active = selectionJob?.isActive == true
        if (mode == RootSelectionMode.ANIMATE && coordinator.selectedPage == page && (pagerState.currentPage == page || active)) return
        coordinator.select(page, mode)
        selectedPage = coordinator.selectedPage
        selectionJob?.cancel()
        selectionJob = scope.launch {
            if (mode == RootSelectionMode.SNAP) {
                // Only forced routes preempt UserInput; normal selections keep their spring.
                with(pagerState) {
                    scroll(MutatePriority.PreventUserInput) { requestScrollToPage(page) }
                }
            } else {
                val contract = appRootPagerContract()
                pagerState.animateScrollToPage(
                    page = page,
                    animationSpec = spring(
                        dampingRatio = contract.springDampingRatio,
                        stiffness = contract.springStiffness,
                        visibilityThreshold = contract.springVisibilityThreshold,
                    ),
                )
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (selectionJob === job) selectionJob = null
            }
        }
    }

    // Only finish our in-flight selection; a resize must not select a root for a user drag.
    fun finishProgrammaticSelectionOnResize() {
        if (selectionJob?.isActive != true || !coordinator.hasProgrammaticTarget) return
        val target = coordinator.selectedPage
        selectionJob?.cancel()
        coordinator.select(target, RootSelectionMode.SNAP)
        selectedPage = coordinator.selectedPage
        pagerState.requestScrollToPage(target)
    }

    fun observePager(targetPage: Int, settledPage: Int, scrollInProgress: Boolean) {
        coordinator.observePager(targetPage, settledPage, scrollInProgress)
        selectedPage = coordinator.selectedPage
    }
}

@Composable
internal fun rememberAppRootPagerState(initialRoot: AppRoute.Root): AppRootPagerState {
    val pagerState = rememberPagerState(
        initialPage = pagerPageForRoot(initialRoot),
        pageCount = { AppRoute.rootRoutes.size },
    )
    val scope = rememberCoroutineScope()
    val state = remember(pagerState, scope) { AppRootPagerState(pagerState, scope) }
    val containerSize = LocalWindowInfo.current.containerSize
    var previousSize by remember(state) { mutableStateOf(containerSize) }
    LaunchedEffect(state, containerSize) {
        if (previousSize != containerSize) {
            previousSize = containerSize
            state.finishProgrammaticSelectionOnResize()
        }
    }
    return state
}

internal fun pagerPageForRoot(root: AppRoute.Root): Int = AppRoute.rootRoutes.indexOf(root)

internal fun rootForPagerPage(page: Int): AppRoute.Root = AppRoute.rootRoutes[page]

internal fun pagerSaveableKey(page: Int): Int = page

internal fun rootPagerSwipeEnabled(state: AppNavigationState): Boolean = state.topRoute is AppRoute.Root

internal fun shouldSynchronizeExternalRoot(selectedPage: Int, requestedPage: Int, forceSnap: Boolean): Boolean =
    forceSnap || selectedPage != requestedPage
