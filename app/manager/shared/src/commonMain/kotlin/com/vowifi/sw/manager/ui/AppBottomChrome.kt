package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.Settings

internal enum class BottomChromeComposition {
    PRESENT,
    ABSENT,
}

internal fun bottomChromeComposition(visible: Boolean): BottomChromeComposition =
    if (visible) BottomChromeComposition.PRESENT else BottomChromeComposition.ABSENT

internal data class BottomChromeVisibilityContract(
    val hiddenHitTestLayer: Boolean = false,
    val hiddenExitAnimation: Boolean = false,
)

internal fun bottomChromeVisibilityContract(): BottomChromeVisibilityContract =
    BottomChromeVisibilityContract(
        hiddenHitTestLayer = false,
        hiddenExitAnimation = false,
    )

@Composable
internal fun AnimatedBottomNavigationBar(
    visible: Boolean,
    destination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    chromeSpec: AppChromeSpec,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    if (bottomChromeComposition(visible) == BottomChromeComposition.ABSENT) return

    val transition = bottomChromeTransitionContract()
    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedContent(
            targetState = chromeSpec.bottomMode,
            contentAlignment = Alignment.BottomCenter,
            transitionSpec = {
                (fadeIn(tween(transition.modeDurationMillis)) + scaleIn(tween(transition.modeDurationMillis), 0.96f)) togetherWith
                    (fadeOut(tween(transition.modeDurationMillis)) + scaleOut(tween(transition.modeDurationMillis), 0.96f))
            },
            contentKey = { it },
        ) { mode ->
            BottomNavigationBar(
                destination,
                onDestinationSelected,
                chromeSpec.copy(bottomMode = mode),
                backdrop,
                modifier = if (mode == BottomChromeMode.NORMAL) Modifier.fillMaxWidth() else Modifier,
            )
        }
    }
}

@Composable
internal fun BottomNavigationBar(
    destination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    chromeSpec: AppChromeSpec,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSoftVoWiFiColors.current
    when (chromeSpec.bottomMode) {
        BottomChromeMode.NORMAL -> NavigationBar(
            modifier = chromeSurfaceModifier(modifier, chromeSpec, backdrop, androidx.compose.ui.graphics.RectangleShape),
            color = if (chromeSpec.effectiveBlur) Color.Transparent else colors.surface,
            showDivider = !chromeSpec.effectiveBlur,
            mode = NavigationBarDisplayMode.IconAndText,
        ) {
            DestinationNavigationItems(destination, onDestinationSelected)
        }
        BottomChromeMode.FLOATING -> NativeFloatingNavigation(modifier) {
            DestinationNavigationItems(destination) { item ->
                if (item != destination) onDestinationSelected(item)
            }
        }
    }
}

@Composable
internal fun RowScope.DestinationNavigationItems(
    destination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    AppDestination.entries.forEach { item ->
        NavigationBarItem(
            selected = item == destination,
            onClick = { onDestinationSelected(item) },
            icon = item.icon,
            label = item.label,
            badge = if (item == AppDestination.MESSAGES) {
                { MessageSummaryBadge(LocalMessageUnreadSummary.current, indicatorOnly = LocalDensity.current.fontScale > 1.3f) }
            } else null,
        )
    }
}

private val AppDestination.icon: ImageVector
    get() = when (this) {
        AppDestination.HOME -> MiuixIcons.Home
        AppDestination.CALLS -> MiuixIcons.Phone
        AppDestination.MESSAGES -> MiuixIcons.Messages
        AppDestination.SETTINGS -> MiuixIcons.Settings
    }
