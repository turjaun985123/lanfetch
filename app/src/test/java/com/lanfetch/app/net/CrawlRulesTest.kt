package com.lanfetch.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrawlRulesTest {

    @Test fun junkFoldersAreSkipped() {
        assertTrue(CrawlRules.isJunkFolder("Sample"))
        assertTrue(CrawlRules.isJunkFolder("subtitles"))
        assertTrue(CrawlRules.isJunkFolder("@eaDir"))
        assertFalse(CrawlRules.isJunkFolder("English Movies"))
    }

    @Test fun seasonLikeNames() {
        assertTrue(CrawlRules.isSeasonLike("Season 1"))
        assertTrue(CrawlRules.isSeasonLike("Season 02"))
        assertTrue(CrawlRules.isSeasonLike("S01"))
        assertFalse(CrawlRules.isSeasonLike("Sample"))
        assertFalse(CrawlRules.isSeasonLike("Extras"))
    }

    @Test fun titleFolderOnlyDescendsIntoSeasons() {
        // folder with videos: only season-like children
        assertTrue(CrawlRules.shouldDescend(2, true, "Season 2", 4))
        assertFalse(CrawlRules.shouldDescend(2, true, "Sample", 4))
        assertFalse(CrawlRules.shouldDescend(2, true, "Behind the Scenes", 4))
        // folder without videos: descend normally, but never into junk
        assertTrue(CrawlRules.shouldDescend(1, false, "A - F", 4))
        assertFalse(CrawlRules.shouldDescend(1, false, "Trailers", 4))
        // depth cap
        assertFalse(CrawlRules.shouldDescend(4, false, "Season 1", 4))
    }

    @Test fun pruneOnlyWhenUnchangedAndKnown() {
        assertTrue(CrawlRules.shouldPrune(false, "2024-01-01 10:00", "2024-01-01 10:00"))
        assertFalse(CrawlRules.shouldPrune(false, "2024-01-01 10:00", "2024-02-01 10:00"))
        assertFalse(CrawlRules.shouldPrune(true, "x", "x"))
        assertFalse(CrawlRules.shouldPrune(false, null, "x"))
        assertFalse(CrawlRules.shouldPrune(false, "x", null))
    }
}
