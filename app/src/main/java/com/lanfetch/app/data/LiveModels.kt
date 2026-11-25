package com.lanfetch.app.data

import androidx.compose.runtime.Immutable

/** A category discovered live from one or more LAN servers. */
@Immutable
data class LiveCategory(
    val name: String,
    val locations: List<CategoryLocation>,
)

@Immutable
data class CategoryLocation(
    val source: Source,
    val url: String,
)

/**
 * A movie or series card fetched live from a server directory.
 * Files are loaded directly when a movie folder is inspected, or lazily on tap
 * for multi-season series.
 */
@Immutable
data class LiveTitle(
    val folderUrl: String,
    val sourceId: Long,
    val sourceName: String,
    val category: String,
    val title: String,
    val rawTitleName: String,
    val year: Int?,
    val kind: String,                 // "movie" / "series"
    val posterUrl: String?,
    val language: String?,
    val fileCount: Int,
    val seasonCount: Int = 0,
    val maxSizeBytes: Long,
    val bestQuality: String?,
    val bestSourceTag: String?,
    val bestCodec: String?,
    val hasDualAudio: Boolean,
    val modified: String?,
    val favorite: Boolean = false,
    val downloadedCount: Int = 0,
    val filesLoaded: Boolean = false,
    val cachedFiles: List<CatalogFile> = emptyList(),
    val posterResolved: Boolean = true,
)
