package dev.musicvisualizer.interaction

import dev.musicvisualizer.model.PlaybackCommand
import dev.musicvisualizer.model.ClickBindings

fun routeClick(
    horizontalFraction: Float,
    doubleClick: Boolean,
    leftZoneFraction: Float = 0.30f,
    rightZoneFraction: Float = 0.30f,
    bindings: ClickBindings = ClickBindings(),
): PlaybackCommand {
    val fraction = horizontalFraction.coerceIn(0f, 1f)
    return when {
        fraction < leftZoneFraction -> if (doubleClick) bindings.leftDouble else bindings.leftSingle
        fraction > 1f - rightZoneFraction -> if (doubleClick) bindings.rightDouble else bindings.rightSingle
        else -> if (doubleClick) bindings.centerDouble else bindings.centerSingle
    }
}

fun isClickGesture(pointerTravelPixels: Float, altPressedAtPress: Boolean): Boolean =
    !altPressedAtPress && pointerTravelPixels <= 8f
