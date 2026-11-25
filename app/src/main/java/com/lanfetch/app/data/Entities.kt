package com.lanfetch.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

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
    fun toModel(): Source = Source(
        id = id,
        name = name,
        rootUrl = rootUrl,
        enabled = enabled,
        lastSynced = lastSynced,
        lastError = lastError,
        itemCount = itemCount,
        selectedCategories = selectedCategories
            .split('\n')
            .filter { it.isNotBlank() }
            .toSet(),
    )

    companion object {
        fun from(source: Source): SourceEntity = SourceEntity(
            id = source.id,
            name = source.name,
            rootUrl = source.rootUrl,
            enabled = source.enabled,
            lastSynced = source.lastSynced,
            lastError = source.lastError,
            itemCount = source.itemCount,
            selectedCategories = source.selectedCategories
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .sorted()
                .joinToString("\n"),
        )
    }
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
