package com.lanfetch.app.data

/**
 * One video file discovered by the crawler. [folderUrl] is the *title* folder
 * (the show root for episodes), while [dirUrl] is the listing folder the file
 * actually came from. Keeping both is what lets a show group all of its
 * seasons under one card.
 */
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

/** A card in the library: one title folder, with aggregate metadata. */
data class CatalogTitle(
    val id: Long,
    val sourceId: Long,
    val sourceName: String,
    val category: String,
    val folderUrl: String,
    val title: String,
    val year: Int?,
    val kind: String,                  // movie / series
    val posterUrl: String?,
    val favorite: Boolean,
    val language: String?,
    val fileCount: Int,
    val totalSizeBytes: Long,
    val maxSizeBytes: Long,
    val bestQuality: String?,
    val bestSourceTag: String?,
    val bestCodec: String?,
    val hasDualAudio: Boolean,
    val downloadedCount: Int,
    val addedAt: Long,
)

/** One downloadable file inside a [CatalogTitle]. */
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

/** A configured directory server. */
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
