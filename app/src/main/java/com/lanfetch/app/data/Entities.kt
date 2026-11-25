package com.lanfetch.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class TitleKind(val stored: String) {
    MOVIE("movie"),
    SERIES("series");

    companion object {
        fun from(value: String): TitleKind =
            entries.firstOrNull { it.stored.equals(value, ignoreCase = true) } ?: MOVIE
    }
}

enum class DownloadState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

class Converters {
    @TypeConverter
    fun titleKindToString(value: TitleKind): String = value.stored

    @TypeConverter
    fun stringToTitleKind(value: String): TitleKind = TitleKind.from(value)

    @TypeConverter
    fun downloadStateToString(value: DownloadState): String = value.name

    @TypeConverter
    fun stringToDownloadState(value: String): DownloadState =
        runCatching { DownloadState.valueOf(value) }.getOrDefault(DownloadState.PAUSED)
}

@Entity(
    tableName = "sources",
    indices = [Index(value = ["rootUrl"], unique = true)],
)
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val rootUrl: String,
    val enabled: Boolean = true,
    val lastSynced: Long = 0,
    val lastError: String? = null,
    val itemCount: Int = 0,
    val selectedCategories: String = "",
) {
    fun toModel() = Source(
        id, name, rootUrl, enabled, lastSynced, lastError, itemCount,
        selectedCategories = if (selectedCategories.isBlank()) emptySet()
        else selectedCategories.split("\n").filter { it.isNotBlank() }.toSet(),
    )

    companion object {
        fun from(s: Source) = SourceEntity(
            s.id, s.name, s.rootUrl, s.enabled, s.lastSynced, s.lastError, s.itemCount,
            selectedCategories = s.selectedCategories.joinToString("\n"),
        )
    }
}

/**
 * One row per title folder. Aggregate fields are refreshed after a sync so the
 * library can page and filter without loading the 40k-row file table.
 */
@Entity(
    tableName = "titles",
    indices = [
        Index(value = ["folderUrl"], unique = true),
        Index(value = ["sourceId"]),
        Index(value = ["title"]),
        Index(value = ["kind"]),
        Index(value = ["favorite"]),
        Index(value = ["year"]),
        Index(value = ["totalSizeBytes"]),
        Index(value = ["maxSizeBytes"]),
        Index(value = ["addedAt"]),
        Index(value = ["bestQuality"]),
        Index(value = ["bestSourceTag"]),
        Index(value = ["language"]),
        Index(value = ["category"]),
        Index(value = ["sourceName"]),
    ],
)
data class TitleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val sourceName: String,
    val category: String,
    val folderUrl: String,
    val title: String,
    val year: Int?,
    val kind: TitleKind,
    val posterUrl: String?,
    val favorite: Boolean = false,
    val language: String?,
    val fileCount: Int = 0,
    val totalSizeBytes: Long = 0,
    val maxSizeBytes: Long = 0,
    val bestQuality: String? = null,
    val bestSourceTag: String? = null,
    val bestCodec: String? = null,
    val hasDualAudio: Boolean = false,
    val downloadedCount: Int = 0,
    val addedAt: Long = 0,
    val updatedAt: Long = 0,
    val searchText: String = "",
) {
    fun toModel() = CatalogTitle(
        id = id,
        sourceId = sourceId,
        sourceName = sourceName,
        category = category,
        folderUrl = folderUrl,
        title = title,
        year = year,
        kind = kind.stored,
        posterUrl = posterUrl,
        favorite = favorite,
        language = language,
        fileCount = fileCount,
        totalSizeBytes = totalSizeBytes,
        maxSizeBytes = maxSizeBytes,
        bestQuality = bestQuality,
        bestSourceTag = bestSourceTag,
        bestCodec = bestCodec,
        hasDualAudio = hasDualAudio,
        downloadedCount = downloadedCount,
        addedAt = addedAt,
    )
}

@Entity(
    tableName = "files",
    indices = [
        Index(value = ["fileUrl"], unique = true),
        Index(value = ["titleId"]),
        Index(value = ["dirUrl"]),
        Index(value = ["sourceId"]),
    ],
)
data class FileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titleId: Long,
    val sourceId: Long,
    val dirUrl: String,
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
    val searchText: String,
) {
    fun toModel() = CatalogFile(
        id, titleId, fileName, fileUrl, sizeBytes, modified, quality, sourceTag,
        codec, audio, season, episode, addedAt,
    )
}

@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["url"], unique = true),
        Index(value = ["state"]),
        Index(value = ["createdAt"]),
    ],
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val title: String,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val segments: String = "",
    val state: DownloadState = DownloadState.QUEUED,
    val error: String? = null,
    val savedPath: String? = null,
    val sha256: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val mediaUri: String? = null,
    val mimeType: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Entity(
    tableName = "favorites",
    indices = [
        Index(value = ["addedAt"]),
        Index(value = ["sourceId"]),
    ],
)
data class FavoriteEntity(
    @PrimaryKey val folderUrl: String,
    val sourceId: Long,
    val sourceName: String,
    val category: String,
    val title: String,
    val rawTitleName: String,
    val year: Int? = null,
    val kind: String = "movie",
    val posterUrl: String? = null,
    val language: String? = null,
    val fileCount: Int = 0,
    val seasonCount: Int = 0,
    val maxSizeBytes: Long = -1L,
    val bestQuality: String? = null,
    val bestSourceTag: String? = null,
    val bestCodec: String? = null,
    val hasDualAudio: Boolean = false,
    val modified: String? = null,
    val addedAt: Long = 0L,
) {
    fun toLiveTitle(): LiveTitle = LiveTitle(
        folderUrl = folderUrl,
        sourceId = sourceId,
        sourceName = sourceName,
        category = category,
        title = title,
        rawTitleName = rawTitleName,
        year = year,
        kind = kind,
        posterUrl = posterUrl,
        language = language,
        fileCount = fileCount,
        seasonCount = seasonCount,
        maxSizeBytes = maxSizeBytes,
        bestQuality = bestQuality,
        bestSourceTag = bestSourceTag,
        bestCodec = bestCodec,
        hasDualAudio = hasDualAudio,
        modified = modified,
        favorite = true,
        filesLoaded = false,
        posterResolved = true,
    )

    companion object {
        fun from(title: LiveTitle, addedAt: Long): FavoriteEntity = FavoriteEntity(
            folderUrl = title.folderUrl,
            sourceId = title.sourceId,
            sourceName = title.sourceName,
            category = title.category,
            title = title.title,
            rawTitleName = title.rawTitleName,
            year = title.year,
            kind = title.kind,
            posterUrl = title.posterUrl,
            language = title.language,
            fileCount = title.fileCount,
            seasonCount = title.seasonCount,
            maxSizeBytes = title.maxSizeBytes,
            bestQuality = title.bestQuality,
            bestSourceTag = title.bestSourceTag,
            bestCodec = title.bestCodec,
            hasDualAudio = title.hasDualAudio,
            modified = title.modified,
            addedAt = addedAt,
        )
    }
}

@Entity(
    tableName = "cached_categories",
    indices = [
        Index(value = ["categoryOrder", "categoryName"]),
        Index(value = ["sourceId"]),
    ],
)
data class CachedCategoryEntity(
    @PrimaryKey val locationUrl: String,
    val categoryName: String,
    val sourceId: Long,
    val sourceName: String,
    val sourceRootUrl: String,
    val categoryOrder: Int,
    val cachedAt: Long,
)

@Entity(
    tableName = "cached_feed_items",
    primaryKeys = ["feedKey", "folderUrl"],
    indices = [
        Index(value = ["feedKey", "position"]),
        Index(value = ["sourceId"]),
        Index(value = ["cachedAt"]),
    ],
)
data class CachedFeedItemEntity(
    val feedKey: String,
    val folderUrl: String,
    val position: Int,
    val sourceId: Long,
    val sourceName: String,
    val category: String,
    val title: String,
    val rawTitleName: String,
    val year: Int? = null,
    val kind: String = "movie",
    val posterUrl: String? = null,
    val language: String? = null,
    val fileCount: Int = 0,
    val seasonCount: Int = 0,
    val maxSizeBytes: Long = -1L,
    val bestQuality: String? = null,
    val bestSourceTag: String? = null,
    val bestCodec: String? = null,
    val hasDualAudio: Boolean = false,
    val modified: String? = null,
    val cachedAt: Long = 0L,
) {
    fun toLiveTitle(): LiveTitle = LiveTitle(
        folderUrl = folderUrl,
        sourceId = sourceId,
        sourceName = sourceName,
        category = category,
        title = title,
        rawTitleName = rawTitleName,
        year = year,
        kind = kind,
        posterUrl = posterUrl,
        language = language,
        fileCount = fileCount,
        seasonCount = seasonCount,
        maxSizeBytes = maxSizeBytes,
        bestQuality = bestQuality,
        bestSourceTag = bestSourceTag,
        bestCodec = bestCodec,
        hasDualAudio = hasDualAudio,
        modified = modified,
        filesLoaded = false,
        posterResolved = true,
    )

    companion object {
        fun from(feedKey: String, position: Int, title: LiveTitle, cachedAt: Long): CachedFeedItemEntity =
            CachedFeedItemEntity(
                feedKey = feedKey,
                folderUrl = title.folderUrl,
                position = position,
                sourceId = title.sourceId,
                sourceName = title.sourceName,
                category = title.category,
                title = title.title,
                rawTitleName = title.rawTitleName,
                year = title.year,
                kind = title.kind,
                posterUrl = title.posterUrl,
                language = title.language,
                fileCount = title.fileCount,
                seasonCount = title.seasonCount,
                maxSizeBytes = title.maxSizeBytes,
                bestQuality = title.bestQuality,
                bestSourceTag = title.bestSourceTag,
                bestCodec = title.bestCodec,
                hasDualAudio = title.hasDualAudio,
                modified = title.modified,
                cachedAt = cachedAt,
            )
    }
}
