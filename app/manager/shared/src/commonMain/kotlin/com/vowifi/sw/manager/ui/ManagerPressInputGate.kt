package com.vowifi.sw.manager.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/**
 * §4.6: revoke a pointer gesture after a closed gate or a second finger, until every finger is up.
 * The pinned Foundation clickable can retain its down event across enabled=false→true.
 * Consume only revoked pointer events so the real recognizer emits its own Cancel; never drive
 * drawing, manufacture interactions, invoke callbacks, or reject keyboard/accessibility actions.
 */
internal fun Modifier.managerPressInputGate(enabled: Boolean): Modifier = then(ManagerPressInputElement(enabled))

private data class ManagerPressInputElement(val enabled: Boolean) : ModifierNodeElement<ManagerPressInputNode>() {
    override fun create() = ManagerPressInputNode(enabled)
    override fun update(node: ManagerPressInputNode) { node.updateEnabled(enabled) }
    override fun InspectorInfo.inspectableProperties() {
        name = "managerPressInputGate"
        properties["enabled"] = enabled
    }
}

private class ManagerPressInputNode(private var enabled: Boolean) : Modifier.Node(), PointerInputModifierNode {
    private var held = false
    private var revoked = false
    private var dragHandedOff = false
    private var consumedHere = false
    override val shouldAutoInvalidate = false

    fun updateEnabled(value: Boolean) {
        if (!value && held) revoked = true
        enabled = value
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Final) {
            // A parent scroll recognizer has already canceled this control's press.
            // Route changes must not reclaim and cancel that parent's drag in Initial.
            if (held && !consumedHere && pointerEvent.changes.any {
                    it.pressed && it.previousPressed && it.position != it.previousPosition && it.isConsumed
                }) dragHandedOff = true
            return
        }
        if (pass != PointerEventPass.Initial) return
        consumedHere = false
        val pressed = pointerEvent.changes.count { it.pressed }
        if (!held) {
            if (pressed == 0) return
            held = true
            revoked = !enabled
        }
        if (!enabled || pressed > 1) revoked = true
        // Reject a revoked click at its down/up boundary, not every move. A new
        // drag can start on a still-visible inactive page while its pager settles.
        // The ancestor must be able to cross touch slop and take that gesture.
        if (revoked && !dragHandedOff && pointerEvent.changes.any { it.pressed != it.previousPressed }) {
            pointerEvent.changes.forEach { it.consume() }
            consumedHere = true
        }
        if (pressed == 0) onCancelPointerInput()
    }

    override fun onCancelPointerInput() {
        held = false
        revoked = false
        dragHandedOff = false
        consumedHere = false
    }
}
