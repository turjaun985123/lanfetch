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

    @Test fun seasonLikeNamesMatchRealVariantsWithoutFalsePositives() {
        assertTrue(CrawlRules.isSeasonLike("Season 1"))
        assertTrue(CrawlRules.isSeasonLike("Season 02"))
        assertTrue(CrawlRules.isSeasonLike("S01"))
        assertTrue(CrawlRules.isSeasonLike("Season 1 (Part-1)"))
        assertTrue(CrawlRules.isSeasonLike("Season 1 (Hindi Language)"))
        assertTrue(CrawlRules.isSeasonLike("Season ALL"))
        assertTrue(CrawlRules.isSeasonLike("Season 1 & 2"))
        assertTrue(CrawlRules.isSeasonLike("Season 1.5 (Signs of Holy War)"))
        assertTrue(CrawlRules.isSeasonLike("Season 1 - Aegis of Uruk"))
        assertFalse(CrawlRules.isSeasonLike("Season of the Witch (2011)"))
        assertFalse(CrawlRules.isSeasonLike("Sample"))
        assertFalse(CrawlRules.isSeasonLike("Extras"))
    }

    @Test fun titleFolderOnlyDescendsIntoSeasons() {
        assertTrue(CrawlRules.shouldDescend(2, true, "Season 2", 4))
        assertFalse(CrawlRules.shouldDescend(2, true, "Sample", 4))
        assertFalse(CrawlRules.shouldDescend(2, true, "Behind the Scenes", 4))
        assertTrue(CrawlRules.shouldDescend(1, false, "A - F", 4))
        assertFalse(CrawlRules.shouldDescend(1, false, "Trailers", 4))
        assertFalse(CrawlRules.shouldDescend(4, false, "Season 1", 4))
    }
}
