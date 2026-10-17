package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun managerImeScrollIsControllable(): Boolean = false

@Composable
internal actual fun Modifier.managerPlatformImeScroll(enabled: Boolean): Modifier = this
