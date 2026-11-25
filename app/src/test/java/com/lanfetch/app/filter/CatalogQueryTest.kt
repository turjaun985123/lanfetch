package com.lanfetch.app.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogQueryTest {

    @Test fun emptyFilterPagesEverythingByTitle() {
        val spec = CatalogQuery.build(CatalogFilter())
        assertTrue(spec.sql.startsWith("SELECT t.* FROM titles t WHERE 1 = 1"))
        assertTrue(spec.sql.contains("ORDER BY t.title COLLATE NOCASE ASC"))
        assertTrue(spec.args.isEmpty())
    }

    @Test fun searchMatchesTitlesAndFileNames() {
        val spec = CatalogQuery.build(CatalogFilter(query = "dune"))
        assertTrue(spec.sql.contains("t.searchText LIKE ?"))
        assertTrue(spec.sql.contains("FROM files f"))
        assertEquals(listOf<Any?>("%dune%", "%dune%"), spec.args)
    }

    @Test fun filtersAreBindArgumentsNotSql() {
        val spec = CatalogQuery.build(
            CatalogFilter(
                kinds = setOf("series"),
                categories = setOf("TV Series", "English Movies"),
                qualities = setOf("1080p"),
                languages = setOf("English"),
                sourceTags = setOf("WEB-DL"),
                sourceNames = setOf("DHAKA-FLIX-7"),
                yearMin = 2000,
                yearMax = 2025,
                minSizeBytes = 700_000_000,
                favoritesOnly = true,
                dualAudioOnly = true,
            )
        )
        assertFalse(spec.sql.contains("English Movies"))
        assertFalse(spec.sql.contains("DHAKA-FLIX-7"))
        assertTrue(spec.sql.contains("t.kind IN (?)"))
        assertTrue(spec.sql.contains("t.category IN (?, ?)"))
        assertTrue(spec.sql.contains("t.favorite = 1"))
        assertTrue(spec.sql.contains("t.hasDualAudio = 1"))
        assertTrue(spec.sql.contains("t.year >= ?"))
        assertTrue(spec.sql.contains("t.maxSizeBytes >= ?"))
        assertEquals(10, spec.args.size)
    }

    @Test fun searchEscapesLikeWildcards() {
        val spec = CatalogQuery.build(CatalogFilter(query = "100%_off"))
        assertEquals(listOf<Any?>("%100\\%\\_off%", "%100\\%\\_off%"), spec.args)
        assertTrue(spec.sql.contains("ESCAPE '\\'"))
    }

    @Test fun sortsUseAggregateColumns() {
        assertEquals(
            "t.addedAt DESC, t.id DESC",
            CatalogQuery.build(CatalogFilter(sort = SortBy.NEWEST)).sql.substringAfter("ORDER BY "),
        )
        assertTrue(CatalogQuery.build(CatalogFilter(sort = SortBy.SIZE_DESC)).sql.contains("maxSizeBytes DESC"))
        assertTrue(CatalogQuery.build(CatalogFilter(sort = SortBy.YEAR_DESC)).sql.contains("t.year DESC"))
    }

    @Test fun activeFilterCountIgnoresSortAndQuery() {
        assertEquals(0, CatalogFilter(query = "x", sort = SortBy.NEWEST).activeCount)
        assertEquals(
            2,
            CatalogFilter(kinds = setOf("movie"), favoritesOnly = true).activeCount,
        )
    }
}
