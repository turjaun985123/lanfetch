package com.lanfetch.app.filter

import com.lanfetch.app.data.CatalogItem
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterEngineTest {

    private fun item(
        title: String, year: Int?, quality: String?, size: Long,
        kind: String = "movie", category: String = "English Movies",
        language: String? = "English", audio: String? = null, fav: Boolean = false,
    ) = CatalogItem(
        sourceId = 1, sourceName = "S1", category = category,
        folderUrl = "http://x/$title", fileUrl = "http://x/$title/f.mkv", fileName = "f.mkv",
        title = title, year = year, quality = quality, sizeBytes = size,
        kind = kind, language = language, audio = audio, favorite = fav, addedAt = 1,
    )

    private val catalog = listOf(
        item("Dune Part Two", 2024, "2160p", 8_000_000_000),
        item("Old Classic", 1972, "720p", 900_000_000),
        item("Dark Episode", 2017, "1080p", 1_200_000_000, kind = "episode", category = "TV Series", audio = "Dual Audio"),
        item("Small Movie", 2020, "480p", 400_000_000, fav = true),
    )

    @Test fun queryMatchesTitle() {
        val out = FilterEngine.apply(catalog, CatalogFilter(query = "dune"))
        assertEquals(listOf("Dune Part Two"), out.map { it.title })
    }

    @Test fun qualityAndKindFilters() {
        val out = FilterEngine.apply(catalog, CatalogFilter(qualities = setOf("1080p"), kinds = setOf("episode")))
        assertEquals(listOf("Dark Episode"), out.map { it.title })
    }

    @Test fun yearRangeAndFavorites() {
        val out = FilterEngine.apply(catalog, CatalogFilter(yearMin = 2000, yearMax = 2025, favoritesOnly = true))
        assertEquals(listOf("Small Movie"), out.map { it.title })
    }

    @Test fun dualAudioOnly() {
        val out = FilterEngine.apply(catalog, CatalogFilter(dualAudioOnly = true))
        assertEquals(listOf("Dark Episode"), out.map { it.title })
    }

    @Test fun sortsBySizeDesc() {
        val out = FilterEngine.apply(catalog, CatalogFilter(sort = SortBy.SIZE_DESC))
        assertEquals("Dune Part Two", out.first().title)
        assertEquals("Small Movie", out.last().title)
    }

    @Test fun facetsCollectValues() {
        val f = FilterEngine.facets(catalog)
        assertEquals(listOf("2160p", "1080p", "720p", "480p"), f.qualities)
        assertEquals(listOf(1972, 2017, 2020, 2024), f.years)
    }
}
