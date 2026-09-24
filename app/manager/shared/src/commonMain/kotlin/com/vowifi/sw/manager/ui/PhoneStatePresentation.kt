package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface

@Composable internal fun PhoneEmblem(icon: ImageVector) {
    Surface(shape = CircleShape, color = phoneColors().surface) {
        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = phoneColors().accent, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable internal fun PhoneStatusRow(label: String, icon: ImageVector) {
    Surface(shape = RoundedCornerShape(24.dp), color = phoneColors().surface) {
        Row(Modifier.semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = phoneColors().accent, modifier = Modifier.size(20.dp))
            PhoneText(label, Modifier.weight(1f, fill = false))
        }
    }
}

@Composable internal fun PhoneResultContent(
    title: String, message: String?, icon: ImageVector, actions: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(phoneColors().canvas).safeDrawingPadding()) {
        val viewportHeight = maxHeight
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewportHeight)
            .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween) {
            PhoneText("电话", secondary = true, size = 14)
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                PhoneEmblem(icon)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PhoneText(title, size = 24)
                    message?.let { PhoneText(it, secondary = true) }
                }
            }
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp), content = actions)
        }
    }
}
