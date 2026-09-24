package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import com.vowifi.sw.manager.data.CallLineState
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.CallBinding

internal class CallDialDraft(number: String = "", owner: SimOwnerId? = null, label: String = "未选择") {
    var number by mutableStateOf(number)
    var owner by mutableStateOf(owner)
        private set
    var label by mutableStateOf(label)
        private set

    fun select(owner: SimOwnerId, lines: CallLineState) {
        this.owner = owner
        label = lines.choices.firstOrNull { it.owner == owner }?.label ?: "原拨出线路"
    }

    fun acceptInitial(lines: CallLineState) {
        if (number.isEmpty() && owner == null && lines.outgoingBinding != null) {
            lines.selectedOwner?.let { select(it, lines) }
        }
    }

    fun binding(lines: CallLineState): CallBinding? =
        lines.outgoingBinding.takeIf { owner != null && owner == lines.selectedOwner }

    fun notice(lines: CallLineState): String? = when {
        owner == null -> "请选择拨出线路，号码将保留"
        owner != lines.selectedOwner -> "草稿线路与当前选择不同，请重新选卡"
        lines.outgoingBinding == null -> "原线路暂不可拨出，号码已保留"
        else -> null
    }

    companion object {
        val Saver = listSaver<CallDialDraft, String>(
            save = { listOf(it.number, it.owner?.wire().orEmpty(), it.label) },
            restore = { CallDialDraft(it[0], SimOwnerId.parse(it[1]), it[2]) },
        )
    }
}

@Composable internal fun rememberCallDialDraft(lines: CallLineState): CallDialDraft {
    val draft = rememberSaveable(saver = CallDialDraft.Saver) { CallDialDraft() }
    LaunchedEffect(lines) { draft.acceptInitial(lines) }
    return draft
}
