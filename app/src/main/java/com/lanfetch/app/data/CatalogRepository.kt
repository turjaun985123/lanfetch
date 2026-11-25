package com.lanfetch.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class CachedCategoriesSnapshot(
    val categories: List<LiveCategory>,
    val cachedAt: Long,
)

data class CachedFeedSnapshot(
    val items: List<LiveTitle>,
    val cachedAt: Long,
)

/**
 * Persistence gateway for local user Favorites, completed download URL tracking,
 * and stale-while-revalidate Room caches of live server categories & feeds.
 */
class CatalogRepository(private val db: AppDb) {

    fun completedDownloadUrls(): Flow<Set<String>> =
        db.downloads().observeCompletedUrls().map { it.toSet() }

    fun observeFavorites(): Flow<List<LiveTitle>> =
        db.favorites().observeAll().map { rows -> rows.map { it.toLiveTitle() } }

    fun observeFavoriteUrls(): Flow<Set<String>> =
        db.favorites().observeUrls().map { it.toSet() }

    suspend fun toggleFavorite(title: LiveTitle) {
        val dao = db.favorites()
        if (dao.byUrl(title.folderUrl) != null) {
            dao.delete(title.folderUrl)
        } else {
            dao.upsert(FavoriteEntity.from(title, System.currentTimeMillis()))
        }
    }

    suspend fun loadCachedCategories(enabledSources: List<Source>): CachedCategoriesSnapshot? {
        val bySourceId = enabledSources.associateBy { it.id }
        if (bySourceId.isEmpty()) return null
        val rows = db.liveCache().allCategories().filter { it.sourceId in bySourceId }
        if (rows.isEmpty()) return null

        val grouped = LinkedHashMap<String, MutableList<CategoryLocation>>()
        for (row in rows) {
            val source = bySourceId[row.sourceId] ?: continue
            grouped.getOrPut(row.categoryName) { mutableListOf() } +=
                CategoryLocation(source = source, url = row.locationUrl)
        }
        if (grouped.isEmpty()) return null
        val cats = grouped.entries.map { (name, locs) -> LiveCategory(name = name, locations = locs) }
        return CachedCategoriesSnapshot(
            categories = cats,
            cachedAt = rows.minOf { it.cachedAt },
        )
    }

    suspend fun saveCachedCategories(
        categories: List<LiveCategory>,
        cachedAt: Long = System.currentTimeMillis(),
    ) {
        val rows = ArrayList<CachedCategoryEntity>()
        categories.forEachIndexed { idx, cat ->
            for (loc in cat.locations) {
                rows += CachedCategoryEntity(
                    locationUrl = loc.url,
                    categoryName = cat.name,
                    sourceId = loc.source.id,
                    sourceName = loc.source.name,
                    sourceRootUrl = loc.source.rootUrl,
                    categoryOrder = idx,
                    cachedAt = cachedAt,
                )
            }
        }
        db.withTransaction {
            db.liveCache().clearCategories()
            if (rows.isNotEmpty()) db.liveCache().insertCategories(rows)
        }
    }

    suspend fun loadCachedFeed(feedKey: String, enabledSourceIds: Set<Long>): CachedFeedSnapshot? {
        if (enabledSourceIds.isEmpty()) return null
        val rows = db.liveCache().feedItems(feedKey).filter { it.sourceId in enabledSourceIds }
        if (rows.isEmpty()) return null
        return CachedFeedSnapshot(
            items = rows.map { it.toLiveTitle() },
            cachedAt = rows.maxOf { it.cachedAt },
        )
    }

    suspend fun saveCachedFeed(
        feedKey: String,
        items: List<LiveTitle>,
        cachedAt: Long = System.currentTimeMillis(),
    ) {
        val rows = items.take(MAX_CACHED_FEED_ITEMS).mapIndexed { idx, title ->
            CachedFeedItemEntity.from(feedKey, idx, title, cachedAt)
        }
        db.withTransaction {
            db.liveCache().clearFeed(feedKey)
            if (rows.isNotEmpty()) db.liveCache().insertFeedItems(rows)
        }
    }

    suspend fun clearAllLiveCache() {
        db.withTransaction {
            db.liveCache().clearCategories()
            db.liveCache().clearAllFeeds()
        }
    }

    companion object {
        private const val MAX_CACHED_FEED_ITEMS = 54
    }
}
