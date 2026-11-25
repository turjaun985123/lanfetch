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
        assertTrue(CrawlRules.shouldDescend(2, true, "Season 2", 4))
        assertFalse(CrawlRules.shouldDescend(2, true, "Sample", 4))
        assertFalse(CrawlRules.shouldDescend(2, true, "Behind the Scenes", 4))
        assertTrue(CrawlRules.shouldDescend(1, false, "A - F", 4))
        assertFalse(CrawlRules.shouldDescend(1, false, "Trailers", 4))
        assertFalse(CrawlRules.shouldDescend(4, false, "Season 1", 4))
    }

    @Test fun pruneOnlyKnownUnchangedLeaves() {
        val leaf = FolderMeta(modified = "2024-01-01 10:00", isLeaf = true, hasVideos = true)
        assertTrue(CrawlRules.shouldPrune(false, leaf, "2024-01-01 10:00"))
        assertFalse(CrawlRules.shouldPrune(false, leaf, "2024-02-01 10:00"))
        assertFalse(CrawlRules.shouldPrune(true, leaf, "2024-01-01 10:00"))
        assertFalse(CrawlRules.shouldPrune(false, null, "2024-01-01 10:00"))
        assertFalse(CrawlRules.shouldPrune(false, leaf, null))
        // A folder that had subfolders last time must be rescanned: its own
        // mtime does not change when a grandchild gains an episode.
        assertFalse(
            CrawlRules.shouldPrune(
                false,
                FolderMeta(modified = "2024-01-01 10:00", isLeaf = false, hasVideos = false),
                "2024-01-01 10:00",
            )
        )
    }
}
