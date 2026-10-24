package com.netmusiclite.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * ButtonGroup-like stretch for standalone controls, without changing layout or click ownership.
 * Pass an existing source when the caller owns it; native controls can use the passive observer.
 * Pointer events are never consumed, preserving ripple, long press and scrolling cancellation.
 */
@Composable
fun Modifier.pressStretch(
    enabled: Boolean = true,
    interactionSource: InteractionSource? = null,
    scaleTo: Float = PressMotion.SCALE,
): Modifier {
    if (!enabled) return this
    var observedPress by remember { mutableStateOf(false) }
    val pressed = if (interactionSource != null) {
        val sourcePressed by interactionSource.collectIsPressedAsState()
        sourcePressed
    } else observedPress
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleTo else 1f,
        animationSpec = PressMotion.spec(pressed),
        label = "pressStretch",
    )
    val observer = if (interactionSource != null) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            observedPress = true
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val touch = event.changes.firstOrNull { it.id == down.id } ?: break
                    val moved = touch.position != touch.previousPosition
                    val outside = touch.position.x < 0f || touch.position.x > size.width ||
                        touch.position.y < 0f || touch.position.y > size.height
                    if (!touch.pressed || outside ||
                        (touch.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                        (moved && touch.isConsumed)) break
                }
            } finally {
                observedPress = false
            }
        }
    }
    return this.then(observer).graphicsLayer {
        scaleX = PressMotion.stretchX(scale)
        scaleY = PressMotion.stretchY(scale)
    }
}
