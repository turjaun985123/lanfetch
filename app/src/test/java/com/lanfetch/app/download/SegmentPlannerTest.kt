package com.lanfetch.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPlannerTest {

    @Test fun planCoversFileExactly() {
        val total = 10_000_001L
        val segs = SegmentPlanner.plan(total, 8)
        assertEquals(total, segs.sumOf { it.length })
        assertEquals(0L, segs.first().start)
        assertEquals(total - 1, segs.last().end)
        for (i in 1 until segs.size) {
            assertEquals(segs[i - 1].end + 1, segs[i].start)
        }
    }

    @Test fun smallFileGetsOneSegment() {
        val segs = SegmentPlanner.plan(1000, 8)
        assertEquals(1, segs.size)
        assertEquals(1000L, segs[0].length)
    }

    @Test fun emptyForUnknownSize() {
        assertTrue(SegmentPlanner.plan(-1, 8).isEmpty())
        assertTrue(SegmentPlanner.plan(0, 8).isEmpty())
    }

    @Test fun encodeDecodeRoundTrip() {
        val segs = SegmentPlanner.plan(5_000_000, 4)
        segs[1].downloaded = 12345
        val decoded = SegmentPlanner.decode(SegmentPlanner.encode(segs))
        assertEquals(segs.size, decoded.size)
        assertEquals(12345L, decoded[1].downloaded)
        assertEquals(segs[2].start, decoded[2].start)
        assertEquals(segs[3].end, decoded[3].end)
    }

    @Test fun respectsRequestedSegmentCap() {
        val segs = SegmentPlanner.plan(100_000_000, 4)
        assertEquals(4, segs.size)
    }
}
