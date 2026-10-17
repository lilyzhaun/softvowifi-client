package com.vowifi.sw.manager.ui

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.communicationContentPress(feedback: ManagerActionFeedback,
    interactionSource: InteractionSource): Modifier {
    return managerPressDrawing(rememberManagerPressMotion(interactionSource, feedback.accepted, ManagerPressStyle.Content))
}

internal enum class CommunicationCopyFeedback(val label: String) {
    MESSAGE(MESSAGE_COPIED_FEEDBACK), NUMBER("已复制号码"),
}

internal val LocalCommunicationCopyFeedback = staticCompositionLocalOf<(CommunicationCopyFeedback) -> Unit> { {} }

/** Register one active page; standalone callers use the same layer. */
@Composable
internal fun CommunicationFeedbackHost(bottomInset: Dp = LocalRootBottomContentInset.current.value,
    content: @Composable () -> Unit) {
    val inherited = LocalManagerResultFeedback.current
    val controller = inherited ?: rememberManagerResultFeedback()
    val owner = remember { Any() }
    val active = rememberUpdatedState(LocalAppRouteActive.current)
    val inset = rememberUpdatedState(bottomInset)
    val surface = rememberUpdatedState(communicationColors().field)
    DisposableEffect(controller, active.value) {
        if (active.value) controller.register(owner, inset, surface)
        onDispose { controller.unregister(owner); controller.hide(owner) }
    }
    val show = remember(controller, owner) {
        { value: CommunicationCopyFeedback ->
            if (active.value) controller.show(when (value) {
                CommunicationCopyFeedback.MESSAGE -> ManagerResultLabel.MessageCopied
                CommunicationCopyFeedback.NUMBER -> ManagerResultLabel.NumberCopied
            }, owner)
        }
    }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalManagerResultFeedback provides controller,
            LocalCommunicationCopyFeedback provides show, content = content)
        if (inherited == null && active.value) {
            ManagerResultFeedbackLayer(controller, bottomInset = 0.dp)
        }
    }
}
