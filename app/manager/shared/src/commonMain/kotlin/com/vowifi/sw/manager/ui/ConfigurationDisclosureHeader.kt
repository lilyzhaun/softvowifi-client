package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.squircle.squircleClip

@Composable
internal fun ConfigurationDisclosureHeader(
    title: String,
    summary: String,
    expanded: Boolean,
    interaction: ConfigurationDisclosureInteractionContract,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSoftVoWiFiColors.current
    val arrowRotation = animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = MotionTokens.SpringSpatialFast,
        label = "ConfigurationDisclosureArrowRotation",
    )
    val headerVisibility = remember { BringIntoViewRequester() }
    var previousExpanded by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (previousExpanded != expanded) {
            previousExpanded = expanded
            if (expanded) headerVisibility.bringIntoView()
        }
    }
    val headerModifier = modifier
        .fillMaxWidth()
        .bringIntoViewRequester(headerVisibility)
        .then(
            if (interaction.headerUsesTopSquircleClip) {
                Modifier.squircleClip(
                    topStart = CardDefaults.CornerRadius,
                    topEnd = CardDefaults.CornerRadius,
                    bottomEnd = 0.dp,
                    bottomStart = 0.dp,
                )
            } else {
                Modifier
            },
        )
        .then(
            if (interaction.headerClickable) {
                Modifier.clickable(onClick = onToggleExpand)
            } else {
                Modifier
            },
        )

    Box(modifier = headerModifier) {
        BasicComponent(
            modifier = Modifier.fillMaxWidth(),
            title = title,
            summary = summary,
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Phone,
                    contentDescription = null,
                    modifier = Modifier.padding(end = ManagerPreferenceLayoutContract.startIconEndPadding).size(20.dp),
                )
            },
            endActions = {
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = arrowRotation.value },
                )
            },
        )
    }
}
