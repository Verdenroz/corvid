package com.verdenroz.corvid.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp

private val RingWidth = 2.dp
private val RingGap = 2.dp

/**
 * An accent ring [RingGap] outside [shape] while the focus target after it holds focus in keyboard
 * mode, so pointer and touch never show it. Place it before any clip, which would cut the ring off.
 */
fun Modifier.focusRing(shape: Shape): Modifier = this then FocusRingElement(shape, inset = false)

/**
 * [focusRing] drawn inside the bounds, over the content, for edge-to-edge rows and bars where a
 * ring outside would be clipped by the screen or the list.
 */
fun Modifier.focusRingInset(shape: Shape = RectangleShape): Modifier = this then FocusRingElement(shape, inset = true)

private data class FocusRingElement(
    val shape: Shape,
    val inset: Boolean,
) : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode(shape, inset)

    override fun update(node: FocusRingNode) = node.update(shape, inset)
}

private class FocusRingNode(
    private var shape: Shape,
    private var inset: Boolean,
) : Modifier.Node(),
    FocusEventModifierNode,
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    private var focused = false

    fun update(
        shape: Shape,
        inset: Boolean,
    ) {
        this.shape = shape
        this.inset = inset
        invalidateDraw()
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (focused == focusState.isFocused) return
        focused = focusState.isFocused
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused || currentValueOf(LocalInputModeManager).inputMode != InputMode.Keyboard) return
        val reach = if (inset) -(RingWidth / 2).toPx() else (RingGap + RingWidth / 2).toPx()
        val outline = shape.createOutline(Size(size.width + reach * 2, size.height + reach * 2), layoutDirection, this)
        translate(-reach, -reach) {
            drawOutline(outline, currentValueOf(LocalCorvidColors).accent, style = Stroke(RingWidth.toPx()))
        }
    }
}
