package com.lanfetch.app.download

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test fun formatsByteUnitsWithConsistentDecimals() {
        assertEquals("—", Format.bytes(-1))
        assertEquals("0 B", Format.bytes(0))
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.50 KB", Format.bytes(1536))
        assertEquals("1.00 GB", Format.bytes(1_073_741_824))
    }

    @Test fun formatsSpeedAndEta() {
        assertEquals("", Format.speed(0.0))
        assertEquals("2.00 MB/s", Format.speed(2_097_152.0))
        assertEquals("", Format.eta(-1))
        assertEquals("45s left", Format.eta(45))
        assertEquals("2m 5s left", Format.eta(125))
        assertEquals("1h 1m left", Format.eta(3665))
    }
}
