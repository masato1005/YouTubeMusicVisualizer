package dev.musicvisualizer.interaction

import dev.musicvisualizer.model.PlaybackCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClickCommandRouterTest {
    @Test
    fun routesAllSixCommands() {
        assertEquals(PlaybackCommand.SEEK_BACK_10, routeClick(0.1f, false))
        assertEquals(PlaybackCommand.PREVIOUS, routeClick(0.1f, true))
        assertEquals(PlaybackCommand.TOGGLE_PLAY_PAUSE, routeClick(0.5f, false))
        assertEquals(PlaybackCommand.NONE, routeClick(0.5f, true))
        assertEquals(PlaybackCommand.SEEK_FORWARD_10, routeClick(0.9f, false))
        assertEquals(PlaybackCommand.NEXT, routeClick(0.9f, true))
    }

    @Test
    fun boundaryBelongsToCenter() {
        assertEquals(PlaybackCommand.TOGGLE_PLAY_PAUSE, routeClick(0.30f, false))
        assertEquals(PlaybackCommand.TOGGLE_PLAY_PAUSE, routeClick(0.70f, false))
    }

    @Test
    fun altDragReleaseIsNeverTreatedAsAClick() {
        assertFalse(isClickGesture(pointerTravelPixels = 0f, altPressedAtPress = true))
        assertTrue(isClickGesture(pointerTravelPixels = 0f, altPressedAtPress = false))
    }
}
