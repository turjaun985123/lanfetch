package com.lanfetch.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PauseReasonRegistryTest {
    @Test fun userPauseUsesNonNullReasonAndCanBeConsumed() {
        val reasons = PauseReasonRegistry()
        reasons.set(42L, "Paused")
        assertEquals("Paused", reasons.remove(42L))
        assertNull(reasons.remove(42L))
    }
}
