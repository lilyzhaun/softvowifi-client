package com.vowifi.sw.manager.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource

/** Byte-identical copy of the existing launcher PNG for desktop rendering, not Android's icon source. */
@Composable
internal actual fun AboutAppIcon(modifier: Modifier) {
    Image(painter = painterResource("about-launcher.png"), contentDescription = null, modifier = modifier)
}
