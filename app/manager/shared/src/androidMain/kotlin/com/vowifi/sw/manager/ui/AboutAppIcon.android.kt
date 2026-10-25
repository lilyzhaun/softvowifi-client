package com.vowifi.sw.manager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
internal actual fun AboutAppIcon(modifier: Modifier) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { 88.dp.roundToPx() }
    val icon = remember(context, sizePx) {
        val drawable = context.packageManager.getApplicationIcon(context.applicationInfo).mutate()
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(Canvas(bitmap))
        }.asImageBitmap()
    }
    Image(bitmap = icon, contentDescription = null, modifier = modifier)
}
