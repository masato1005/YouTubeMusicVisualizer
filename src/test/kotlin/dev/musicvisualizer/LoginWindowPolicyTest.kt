package dev.musicvisualizer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginWindowPolicyTest {
    @Test
    fun loginSetupUsesAnInteractiveWindowAndDisablesVisualizerClicks() {
        val policy = resolveWindowPolicy(loginSetupVisible = true)

        assertTrue(policy.showInteractiveLoginWindow)
        assertFalse(policy.enableVisualizerInput)
    }

    @Test
    fun normalPlaybackKeepsVisualizerClicksEnabled() {
        val policy = resolveWindowPolicy(loginSetupVisible = false)

        assertFalse(policy.showInteractiveLoginWindow)
        assertTrue(policy.enableVisualizerInput)
    }
}
