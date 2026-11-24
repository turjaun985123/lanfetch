package com.lanfetch.app.data

/** A downloadable video file discovered on a directory server. */
data class CatalogItem(
    val id: Long = 0,
    val sourceId: Long,
    val sourceName: String,
    val category: String,
    val folderUrl: String,
    val fileUrl: String,
    val fileName: String,
    val title: String,
    val year: Int? = null,
    val quality: String? = null,      // 2160p / 1080p / 720p / 480p / CAM ...
    val sourceTag: String? = null,    // BluRay / WEB-DL / WEBRip / HDRip ...
    val codec: String? = null,        // x264 / x265 / AV1
    val audio: String? = null,        // Dual Audio / Multi Audio / Dubbed
    val language: String? = null,     // derived from category path
    val kind: String = "movie",       // movie / episode
    val season: Int? = null,
    val episode: Int? = null,
    val sizeBytes: Long = -1,
    val modified: String? = null,
    val favorite: Boolean = false,
    val addedAt: Long = 0,
)

/** A configured directory server. */
data class Source(
    val id: Long = 0,
    val name: String,
    val rootUrl: String,
    val enabled: Boolean = true,
    val lastSynced: Long = 0,
    val lastError: String? = null,
    val itemCount: Int = 0,
)
