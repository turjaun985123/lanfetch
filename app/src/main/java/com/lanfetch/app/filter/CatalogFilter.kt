package com.lanfetch.app.filter

import androidx.compose.runtime.Immutable
import com.lanfetch.app.data.LiveTitle

enum class SortBy { TITLE, YEAR_DESC, SIZE_DESC, NEWEST, SIZE_ASC }

@Immutable
data class CatalogFilter(
    val query: String = "",
    val kinds: Set<String> = emptySet(),       // movie / series
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
    val sort: SortBy = SortBy.NEWEST,
) {
    val activeCount: Int
        get() = kinds.size + categories.size + sourceNames.size + qualities.size +
            languages.size + sourceTags.size +
            (if (yearMin != null || yearMax != null) 1 else 0) +
            (if (minSizeBytes != null) 1 else 0) +
            (if (favoritesOnly) 1 else 0) + (if (dualAudioOnly) 1 else 0)
}

@Immutable
data class Facets(
    val categories: List<String> = emptyList(),
    val sourceNames: List<String> = emptyList(),
    val qualities: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val sourceTags: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
)

/**
 * Pure filtering, sorting, and dynamic facet computation for live catalog cards.
 * Kept Android-free so every filter/sort edge case is covered by JVM unit tests.
 */
object TitleFilterEngine {
    private val qualityOrder = listOf("2160p", "1080p", "720p", "480p", "360p", "CAM")

    fun enrich(
        title: LiveTitle,
        favoriteUrls: Set<String>,
        downloadedUrls: Set<String>,
    ): LiveTitle {
        val dlCount = if (title.cachedFiles.isEmpty()) {
            title.downloadedCount
        } else {
            title.cachedFiles.count { it.fileUrl in downloadedUrls }
        }
        return title.copy(
            favorite = title.folderUrl in favoriteUrls,
            downloadedCount = dlCount,
        )
    }

    fun filterAndSort(
        pool: List<LiveTitle>,
        filter: CatalogFilter,
        favoriteUrls: Set<String>,
        downloadedUrls: Set<String>,
    ): List<LiveTitle> {
        val filtered = pool.asSequence()
            .map { enrich(it, favoriteUrls, downloadedUrls) }
            .filter { item ->
                (filter.kinds.isEmpty() || item.kind in filter.kinds) &&
                    (filter.categories.isEmpty() || item.category in filter.categories) &&
                    (filter.sourceNames.isEmpty() || item.sourceName in filter.sourceNames) &&
                    (filter.qualities.isEmpty() || item.bestQuality in filter.qualities) &&
                    (filter.languages.isEmpty() || item.language in filter.languages) &&
                    (filter.sourceTags.isEmpty() || item.bestSourceTag in filter.sourceTags) &&
                    (filter.yearMin == null || (item.year != null && item.year >= filter.yearMin)) &&
                    (filter.yearMax == null || (item.year != null && item.year <= filter.yearMax)) &&
                    (filter.minSizeBytes == null || item.maxSizeBytes >= filter.minSizeBytes) &&
                    (!filter.favoritesOnly || item.favorite) &&
                    (!filter.dualAudioOnly || item.hasDualAudio)
            }
            .toList()

        return when (filter.sort) {
            SortBy.NEWEST -> filtered
            SortBy.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortBy.YEAR_DESC -> filtered.sortedWith(
                compareByDescending<LiveTitle> { it.year ?: Int.MIN_VALUE }.thenBy { it.title.lowercase() }
            )
            SortBy.SIZE_DESC -> filtered.sortedWith(
                compareByDescending<LiveTitle> { it.maxSizeBytes }.thenBy { it.title.lowercase() }
            )
            SortBy.SIZE_ASC -> filtered.sortedWith(
                compareBy<LiveTitle> { if (it.maxSizeBytes < 0L) Long.MAX_VALUE else it.maxSizeBytes }
                    .thenBy { it.title.lowercase() }
            )
        }
    }

    fun buildFacets(pool: List<LiveTitle>): Facets = Facets(
        categories = pool.map { it.category }.filter { it.isNotBlank() }.distinct().sorted(),
        sourceNames = pool.map { it.sourceName }.filter { it.isNotBlank() }.distinct().sorted(),
        qualities = pool.mapNotNull { it.bestQuality }.distinct()
            .sortedBy { q -> qualityOrder.indexOf(q).let { if (it < 0) qualityOrder.size else it } },
        languages = pool.mapNotNull { it.language }.distinct().sorted(),
        sourceTags = pool.mapNotNull { it.bestSourceTag }.distinct().sorted(),
        years = pool.mapNotNull { it.year }.distinct().sorted(),
    )
}
