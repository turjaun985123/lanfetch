package com.lanfetch.app.filter

import com.lanfetch.app.data.CatalogItem

enum class SortBy { TITLE, YEAR_DESC, SIZE_DESC, NEWEST, SIZE_ASC }

data class CatalogFilter(
    val query: String = "",
    val kinds: Set<String> = emptySet(),       // movie / episode
    val categories: Set<String> = emptySet(),
    val sourceNames: Set<String> = emptySet(),
    val qualities: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val sourceTags: Set<String> = emptySet(),
    val yearMin: Int? = null,
    val yearMax: Int? = null,
    val minSizeBytes: Long? = null,
    val favoritesOnly: Boolean = false,
    val dualAudioOnly: Boolean = false,
    val sort: SortBy = SortBy.TITLE,
) {
    val activeCount: Int
        get() = kinds.size + categories.size + sourceNames.size + qualities.size +
            languages.size + sourceTags.size +
            (if (yearMin != null || yearMax != null) 1 else 0) +
            (if (minSizeBytes != null) 1 else 0) +
            (if (favoritesOnly) 1 else 0) + (if (dualAudioOnly) 1 else 0)
}

/** Pure, unit-tested filtering + sorting for the catalog. */
object FilterEngine {

    fun apply(items: List<CatalogItem>, f: CatalogFilter): List<CatalogItem> {
        val q = f.query.trim().lowercase()
        val filtered = items.filter { item ->
            if (q.isNotEmpty() && q !in item.title.lowercase() && q !in item.fileName.lowercase()) return@filter false
            if (f.kinds.isNotEmpty() && item.kind !in f.kinds) return@filter false
            if (f.categories.isNotEmpty() && item.category !in f.categories) return@filter false
            if (f.sourceNames.isNotEmpty() && item.sourceName !in f.sourceNames) return@filter false
            if (f.qualities.isNotEmpty() && (item.quality ?: "") !in f.qualities) return@filter false
            if (f.languages.isNotEmpty() && (item.language ?: "") !in f.languages) return@filter false
            if (f.sourceTags.isNotEmpty() && (item.sourceTag ?: "") !in f.sourceTags) return@filter false
            if (f.yearMin != null && (item.year ?: Int.MIN_VALUE) < f.yearMin) return@filter false
            if (f.yearMax != null && (item.year ?: Int.MAX_VALUE) > f.yearMax) return@filter false
            if (f.minSizeBytes != null && item.sizeBytes >= 0 && item.sizeBytes < f.minSizeBytes) return@filter false
            if (f.favoritesOnly && !item.favorite) return@filter false
            if (f.dualAudioOnly && item.audio == null) return@filter false
            true
        }
        return when (f.sort) {
            SortBy.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortBy.YEAR_DESC -> filtered.sortedWith(compareByDescending<CatalogItem> { it.year ?: 0 }.thenBy { it.title.lowercase() })
            SortBy.SIZE_DESC -> filtered.sortedByDescending { it.sizeBytes }
            SortBy.SIZE_ASC -> filtered.sortedBy { if (it.sizeBytes < 0) Long.MAX_VALUE else it.sizeBytes }
            SortBy.NEWEST -> filtered.sortedByDescending { it.addedAt }
        }
    }

    /** Facet values present in a catalog, for building filter UI. */
    fun facets(items: List<CatalogItem>): Facets = Facets(
        categories = items.map { it.category }.distinct().sorted(),
        sourceNames = items.map { it.sourceName }.distinct().sorted(),
        qualities = items.mapNotNull { it.quality }.distinct()
            .sortedBy { listOf("2160p", "1080p", "720p", "480p", "360p", "CAM").indexOf(it) },
        languages = items.mapNotNull { it.language }.distinct().sorted(),
        sourceTags = items.mapNotNull { it.sourceTag }.distinct().sorted(),
        years = items.mapNotNull { it.year }.distinct().sorted(),
    )

    data class Facets(
        val categories: List<String>,
        val sourceNames: List<String>,
        val qualities: List<String>,
        val languages: List<String>,
        val sourceTags: List<String>,
        val years: List<Int>,
    )
}
