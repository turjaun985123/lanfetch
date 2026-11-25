package com.lanfetch.app.data

import androidx.compose.runtime.Immutable

/** Intermediate parsed file record built by [com.lanfetch.app.net.MetaParser]. */
data class CatalogItem(
    val sourceId: Long,
    val sourceName: String,
    val category: String,
    val folderUrl: String,
    val dirUrl: String,
    val fileName: String,
    val fileUrl: String,
    val title: String,
    val titleKind: String = "movie",   // movie / series
    val year: Int? = null,
    val quality: String? = null,       // 2160p / 1080p / 720p / 480p / CAM ...
    val sourceTag: String? = null,     // BluRay / WEB-DL / WEBRip / HDRip ...
    val codec: String? = null,         // x264 / x265 / AV1
    val audio: String? = null,         // Dual Audio / Multi Audio / Dubbed
    val language: String? = null,      // derived from the category path
    val kind: String = "movie",        // movie / episode
    val season: Int? = null,
    val episode: Int? = null,
    val posterUrl: String? = null,
    val sizeBytes: Long = -1,
    val modified: String? = null,
    val addedAt: Long = 0,
)

/** One downloadable file inside a title folder or season subfolder. */
@Immutable
data class CatalogFile(
    val id: Long,
    val titleId: Long,
    val fileName: String,
    val fileUrl: String,
    val sizeBytes: Long,
    val modified: String?,
    val quality: String?,
    val sourceTag: String?,
    val codec: String?,
    val audio: String?,
    val season: Int?,
    val episode: Int?,
    val addedAt: Long,
)

/** A configured HTTP directory server. */
@Immutable
data class Source(
    val id: Long = 0,
    val name: String,
    val rootUrl: String,
    val enabled: Boolean = true,
    val lastSynced: Long = 0,
    val lastError: String? = null,
    val itemCount: Int = 0,
    val selectedCategories: Set<String> = emptySet(),
)
