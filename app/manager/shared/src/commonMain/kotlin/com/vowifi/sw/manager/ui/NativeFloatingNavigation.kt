package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode

internal object NativeNavigationLayout {
    val height = NavigationBarDefaults.ItemHeight
    val bottomGap = 12.dp
    val horizontalGap = 12.dp
    val maximumWidth = 328.dp
}

@Composable
internal fun NativeFloatingNavigation(
    modifier: Modifier = Modifier,
    navigationInsets: WindowInsets = WindowInsets.navigationBars,
    content: @Composable RowScope.() -> Unit,
) {
    NavigationBar(
        modifier = modifier
            .windowInsetsPadding(navigationInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(
                start = NativeNavigationLayout.horizontalGap,
                end = NativeNavigationLayout.horizontalGap,
                bottom = NativeNavigationLayout.bottomGap,
            )
            .widthIn(max = NativeNavigationLayout.maximumWidth)
            .fillMaxWidth()
            .clip(CircleShape),
        color = LocalSoftVoWiFiColors.current.surface,
        showDivider = false,
        defaultWindowInsetsPadding = false,
        mode = NavigationBarDisplayMode.IconAndText,
        content = content,
    )
}
