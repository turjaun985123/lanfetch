package com.lanfetch.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaParserTest {

    @Test fun cleansReleaseNames() {
        assertEquals("Dune Part Two", MetaParser.cleanTitle("Dune.Part.Two.2024.1080p.WEB-DL.x265"))
        assertEquals("The Dark Knight", MetaParser.cleanTitle("The Dark Knight (2008) [BluRay]"))
        assertEquals("Avengers Endgame", MetaParser.cleanTitle("Avengers_Endgame_2019_720p"))
        assertEquals("Breaking Bad", MetaParser.cleanTitle("Breaking Bad Season 1"))
        assertEquals("Breaking Bad", MetaParser.cleanTitle("Breaking Bad S02"))
    }

    @Test fun cutMarkersRespectWordBoundaries() {
        // "Isaac" contains "aac" and "Improper" contains "proper"; neither may be cut.
        assertEquals("Isaac", MetaParser.cleanTitle("Isaac.2024.1080p"))
        assertEquals("Improper", MetaParser.cleanTitle("Improper.2023.720p"))
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

    @Test fun parsesBinarySizes() {
        assertEquals(1_610_612_736L, MetaParser.parseSize("1.5GiB"))
        assertEquals(734_003_200L, MetaParser.parseSize("700M"))
        assertEquals(2_254_857_830L, MetaParser.parseSize("2.1G"))
        assertEquals(-1L, MetaParser.parseSize("no size here"))
    }

    @Test fun bracketedYearWinsOverTitleNumber() {
        assertEquals(2020, MetaParser.parseYear("Wonder Woman 1984 (2020) 1080p"))
        assertEquals(2017, MetaParser.parseYear("Blade.Runner.2049.2017.1080p"))
        assertNull(MetaParser.parseYear("No year here"))
    }

    @Test fun buildsEpisodeItemWithGroupAndDirectory() {
        val item = MetaParser.buildItem(
            sourceId = 1,
            sourceName = "S1",
            category = "TV Series",
            titleFolderUrl = "http://x/breaking-bad/",
            dirUrl = "http://x/breaking-bad/season-1/",
            titleName = "Breaking Bad Season 1",
            fileName = "Breaking.Bad.S01E05.720p.mkv",
            fileUrl = "http://x/breaking-bad/season-1/bb.mkv",
            sizeBytes = 900_000_000,
            modified = null,
            addedAt = 1,
        )
        assertEquals("episode", item.kind)
        assertEquals("series", item.titleKind)
        assertEquals(1, item.season)
        assertEquals(5, item.episode)
        assertEquals("720p", item.quality)
        assertEquals("Breaking Bad", item.title)
        assertEquals("http://x/breaking-bad/", item.folderUrl)
        assertEquals("http://x/breaking-bad/season-1/", item.dirUrl)
        assertTrue(MetaParser.isVideoFile("a.MKV"))
        assertFalse(MetaParser.isVideoFile("poster.jpg"))
    }

    @Test fun seasonFolderSuppliesSeasonNumber() {
        val item = MetaParser.buildItem(
            sourceId = 1,
            sourceName = "S1",
            category = "TV Series",
            titleFolderUrl = "http://x/show/",
            dirUrl = "http://x/show/season-2/",
            titleName = "Some Show",
            fileName = "Some.Show.E04.1080p.mkv",
            fileUrl = "http://x/show/season-2/e04.mkv",
            sizeBytes = 1_000,
            modified = null,
            addedAt = 1,
            isSeries = true,
            folderSeason = 2,
        )
        assertEquals(2, item.season)
        assertEquals(4, item.episode)
        assertEquals("series", item.titleKind)
    }

    @Test fun languageFromCategory() {
        assertEquals("Korean", MetaParser.parseLanguage("Foreign Language Movies/Korean Language"))
        assertEquals("Bangla", MetaParser.parseLanguage("Kolkata Bangla Movies"))
        assertNull(MetaParser.parseLanguage("Documentary"))
    }

    @Test fun parsesNginxRawSizesIgnoringTimestampsWithSeconds() {
        assertEquals(734_003_200L, MetaParser.parseRawSize("  01-Jan-2024 10:00:12   734003200"))
        assertEquals(1_048_576L, MetaParser.parseRawSize("  2024-03-01 09:12:45   1048576"))
    }

    @Test fun episodeOnlyPatternDoesNotFalseTriggerOnMovies() {
        val movie = MetaParser.buildItem(
            sourceId = 1,
            sourceName = "S1",
            category = "Movies",
            titleFolderUrl = "http://x/wall-e/",
            dirUrl = "http://x/wall-e/",
            titleName = "WALL-E (2008)",
            fileName = "WALL-E.2008.1080p.mkv",
            fileUrl = "http://x/wall-e/walle.mkv",
            sizeBytes = 1_000,
            modified = null,
            addedAt = 1,
        )
        assertEquals("movie", movie.kind)
        assertNull(movie.episode)
    }
}
