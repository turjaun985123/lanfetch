package com.lanfetch.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaParserTest {

    @Test fun cleansReleaseNames() {
        assertEquals("Dune Part Two", MetaParser.cleanTitle("Dune.Part.Two.2024.1080p.WEB-DL.x265"))
        assertEquals("The Dark Knight", MetaParser.cleanTitle("The Dark Knight (2008) [BluRay]"))
        assertEquals("Avengers Endgame", MetaParser.cleanTitle("Avengers_Endgame_2019_720p"))
    }

    @Test fun parsesQualitySourceCodec() {
        assertEquals("2160p", MetaParser.parseQuality("Movie 2160p UHD"))
        assertEquals("1080p", MetaParser.parseQuality("Movie.1080p"))
        assertEquals("720p", MetaParser.parseQuality("Movie 720p"))
        assertEquals("CAM", MetaParser.parseQuality("Movie HDCAM"))
        assertNull(MetaParser.parseQuality("Movie"))
        assertEquals("WEB-DL", MetaParser.parseSourceTag("Movie.WEB-DL"))
        assertEquals("BluRay", MetaParser.parseSourceTag("Movie.BDRip"))
        assertEquals("x265", MetaParser.parseCodec("Movie.x265.HEVC"))
        assertEquals("x264", MetaParser.parseCodec("Movie H.264"))
        assertEquals("Dual Audio", MetaParser.parseAudio("Movie Dual Audio"))
    }

    @Test fun parsesSizes() {
        assertEquals(1_500_000_000L, MetaParser.parseSize("1.5G"))
        assertEquals(700_000_000L, MetaParser.parseSize("700M"))
        assertEquals(-1L, MetaParser.parseSize("no size here"))
    }

    @Test fun buildsEpisodeItem() {
        val item = MetaParser.toItem(
            sourceId = 1, sourceName = "S1", category = "TV Series",
            folderUrl = "http://x/show/", folderName = "Breaking Bad Season 1",
            fileName = "Breaking.Bad.S01E05.720p.mkv", fileUrl = "http://x/show/bb.mkv",
            sizeBytes = 900_000_000, modified = null, addedAt = 1,
        )
        assertEquals("episode", item.kind)
        assertEquals(1, item.season)
        assertEquals(5, item.episode)
        assertEquals("720p", item.quality)
        assertTrue(MetaParser.isVideoFile("a.MKV"))
    }

    @Test fun languageFromCategory() {
        assertEquals("Korean", MetaParser.parseLanguage("Foreign Language Movies/Korean Language"))
        assertEquals("Bangla", MetaParser.parseLanguage("Kolkata Bangla Movies"))
        assertNull(MetaParser.parseLanguage("Documentary"))
    }
}
