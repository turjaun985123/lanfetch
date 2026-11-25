package com.lanfetch.app.filter

import com.lanfetch.app.data.CatalogFile
import com.lanfetch.app.data.LiveTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleFilterEngineTest {

    private fun sampleTitle(
        folderUrl: String,
        title: String,
        year: Int? = 2024,
        kind: String = "movie",
        category: String = "Hindi Movies",
        sourceName: String = "SRV-10",
        bestQuality: String? = "1080p",
        language: String? = "Hindi",
        bestSourceTag: String? = "WEB-DL",
        maxSizeBytes: Long = 2_000_000_000L,
        hasDualAudio: Boolean = false,
        files: List<CatalogFile> = emptyList(),
    ) = LiveTitle(
        folderUrl = folderUrl,
        sourceId = 1L,
        sourceName = sourceName,
        category = category,
        title = title,
        rawTitleName = "$title ($year)",
        year = year,
        kind = kind,
        posterUrl = null,
        language = language,
        fileCount = files.size.coerceAtLeast(1),
        maxSizeBytes = maxSizeBytes,
        bestQuality = bestQuality,
        bestSourceTag = bestSourceTag,
        bestCodec = "x264",
        hasDualAudio = hasDualAudio,
        modified = null,
        cachedFiles = files,
    )

    private fun sampleFile(url: String) = CatalogFile(
        id = 0L,
        titleId = 0L,
        fileName = url.substringAfterLast('/'),
        fileUrl = url,
        sizeBytes = 1_000_000L,
        modified = null,
        quality = "1080p",
        sourceTag = "WEB-DL",
        codec = "x264",
        audio = null,
        season = null,
        episode = null,
        addedAt = 0L,
    )

    @Test fun enrichesFavoritesAndDownloadedCountsFromState() {
        val f1 = sampleFile("http://172.16.50.10/a/ep1.mkv")
        val f2 = sampleFile("http://172.16.50.10/a/ep2.mkv")
        val item = sampleTitle(
            folderUrl = "http://172.16.50.10/a/",
            title = "Show A",
            kind = "series",
            files = listOf(f1, f2),
        )
        val enriched = TitleFilterEngine.enrich(
            title = item,
            favoriteUrls = setOf("http://172.16.50.10/a/"),
            downloadedUrls = setOf(f2.fileUrl),
        )
        assertTrue(enriched.favorite)
        assertEquals(1, enriched.downloadedCount)
    }

    @Test fun filtersAllCriteriaUniformlyAndComputesActiveCount() {
        val match = sampleTitle(
            folderUrl = "http://172.16.50.10/m1/",
            title = "Dune Part Two",
            year = 2024,
            kind = "movie",
            category = "English Movies",
            sourceName = "SRV-14",
            bestQuality = "2160p",
            language = "English",
            bestSourceTag = "BluRay",
            maxSizeBytes = 6_000_000_000L,
            hasDualAudio = true,
        )
        val wrongYear = match.copy(folderUrl = "http://172.16.50.10/m2/", year = 2019)
        val wrongAudio = match.copy(folderUrl = "http://172.16.50.10/m3/", hasDualAudio = false)
        val notFav = match.copy(folderUrl = "http://172.16.50.10/m4/", title = "Other")

        val filter = CatalogFilter(
            kinds = setOf("movie"),
            categories = setOf("English Movies"),
            sourceNames = setOf("SRV-14"),
            qualities = setOf("2160p"),
            languages = setOf("English"),
            sourceTags = setOf("BluRay"),
            yearMin = 2022,
            yearMax = 2025,
            minSizeBytes = 4_000_000_000L,
            favoritesOnly = true,
            dualAudioOnly = true,
        )
        assertEquals(10, filter.activeCount)

        val result = TitleFilterEngine.filterAndSort(
            pool = listOf(match, wrongYear, wrongAudio, notFav),
            filter = filter,
            favoriteUrls = setOf(match.folderUrl, wrongYear.folderUrl, wrongAudio.folderUrl),
            downloadedUrls = emptySet(),
        )
        assertEquals(listOf(match.folderUrl), result.map { it.folderUrl })
    }

    @Test fun sortsAllModesIncludingUnknownSizesLastWhenAscending() {
        val a = sampleTitle("http://h/1/", "Zulu", year = 2021, maxSizeBytes = -1L)
        val b = sampleTitle("http://h/2/", "Alpha", year = 2026, maxSizeBytes = 500_000L)
        val c = sampleTitle("http://h/3/", "Middle", year = 2024, maxSizeBytes = 3_000_000L)
        val pool = listOf(a, b, c)

        assertEquals(
            listOf("Alpha", "Middle", "Zulu"),
            TitleFilterEngine.filterAndSort(pool, CatalogFilter(sort = SortBy.TITLE), emptySet(), emptySet()).map { it.title },
        )
        assertEquals(
            listOf("Alpha", "Middle", "Zulu"),
            TitleFilterEngine.filterAndSort(pool, CatalogFilter(sort = SortBy.YEAR_DESC), emptySet(), emptySet()).map { it.title },
        )
        assertEquals(
            listOf("Middle", "Alpha", "Zulu"),
            TitleFilterEngine.filterAndSort(pool, CatalogFilter(sort = SortBy.SIZE_DESC), emptySet(), emptySet()).map { it.title },
        )
        assertEquals(
            listOf("Alpha", "Middle", "Zulu"),
            TitleFilterEngine.filterAndSort(pool, CatalogFilter(sort = SortBy.SIZE_ASC), emptySet(), emptySet()).map { it.title },
        )
        assertEquals(
            listOf("Zulu", "Alpha", "Middle"),
            TitleFilterEngine.filterAndSort(pool, CatalogFilter(sort = SortBy.NEWEST), emptySet(), emptySet()).map { it.title },
        )
        assertFalse(CatalogFilter().activeCount > 0)
    }

    @Test fun buildFacetsOrdersQualityHierarchyAndDistinctYears() {
        val pool = listOf(
            sampleTitle("http://h/1/", "T1", year = 2025, bestQuality = "CAM", language = "Hindi"),
            sampleTitle("http://h/2/", "T2", year = 2024, bestQuality = "2160p", language = "English"),
            sampleTitle("http://h/3/", "T3", year = 2025, bestQuality = "720p", language = "Hindi"),
        )
        val facets = TitleFilterEngine.buildFacets(pool)
        assertEquals(listOf("2160p", "720p", "CAM"), facets.qualities)
        assertEquals(listOf("English", "Hindi"), facets.languages)
        assertEquals(listOf(2024, 2025), facets.years)
    }
}
