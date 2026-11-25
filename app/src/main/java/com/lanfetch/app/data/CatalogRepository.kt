package com.lanfetch.app.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.lanfetch.app.filter.CatalogFilter
import com.lanfetch.app.filter.CatalogQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class Facets(
    val categories: List<String> = emptyList(),
    val sourceNames: List<String> = emptyList(),
    val qualities: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val sourceTags: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
)

data class CatalogCounts(val titles: Int = 0, val files: Long = 0)

data class CachedCategoriesSnapshot(
    val categories: List<LiveCategory>,
    val cachedAt: Long,
)

data class CachedFeedSnapshot(
    val items: List<LiveTitle>,
    val cachedAt: Long,
)

class CatalogRepository(private val db: AppDb) {

    private val titleDao = db.titles()
    private val fileDao = db.files()

    fun titles(filter: CatalogFilter): Flow<PagingData<CatalogTitle>> {
        val spec = CatalogQuery.build(filter)
        val query = SimpleSQLiteQuery(spec.sql, spec.args.toTypedArray())
        return Pager(
            PagingConfig(
                pageSize = 60,
                prefetchDistance = 30,
                initialLoadSize = 120,
                enablePlaceholders = true,
            )
        ) {
            titleDao.pagingSource(query)
        }.flow.map { page -> page.map { it.toModel() } }
    }

    fun observeTitle(id: Long): Flow<CatalogTitle?> =
        titleDao.observe(id).map { it?.toModel() }

    fun files(titleId: Long): Flow<List<CatalogFile>> =
        fileDao.observeForTitle(titleId).map { rows -> rows.map { it.toModel() } }

    suspend fun filesOnce(titleId: Long): List<CatalogFile> =
        fileDao.forTitle(titleId).map { it.toModel() }

    fun facets(): Flow<Facets> {
        val partial = combine(
            titleDao.categories(),
            titleDao.sourceNames(),
            titleDao.qualities(),
            titleDao.languages(),
            titleDao.sourceTags(),
        ) { categories, sources, qualities, languages, tags ->
            Facets(
                categories = categories,
                sourceNames = sources,
                qualities = sortQualities(qualities),
                languages = languages,
                sourceTags = tags,
            )
        }
        return combine(partial, titleDao.years()) { f, years -> f.copy(years = years) }
    }

    fun counts(): Flow<CatalogCounts> =
        combine(titleDao.titleCount(), titleDao.fileCount()) { titles, files ->
            CatalogCounts(titles, files)
        }

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

    suspend fun toggleFavorite(id: Long) {
        val current = titleDao.byId(id) ?: return
        titleDao.setFavorite(id, !current.favorite, System.currentTimeMillis())
    }

    suspend fun refreshDownloadedCounts() {
        titleDao.recomputeDownloadedCounts()
    }

    /** Writes one crawler batch in a single transaction. */
    suspend fun upsertBatch(items: List<CatalogItem>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        db.withTransaction {
            val touched = ArrayList<Long>()
            for ((folderUrl, group) in items.groupBy { it.folderUrl }) {
                val first = group.first()
                val existing = titleDao.byFolderUrl(folderUrl)
                val titleId: Long
                if (existing == null) {
                    titleId = titleDao.insert(
                        TitleEntity(
                            sourceId = first.sourceId,
                            sourceName = first.sourceName,
                            category = first.category,
                            folderUrl = folderUrl,
                            title = first.title,
                            year = first.year,
                            kind = kindOf(first.titleKind),
                            posterUrl = first.posterUrl,
                            language = first.language,
                            addedAt = first.addedAt,
                            updatedAt = now,
                            searchText = searchText(first.title, first.category, first.sourceName),
                        )
                    )
                } else {
                    titleId = existing.id
                    titleDao.update(
                        existing.copy(
                            sourceId = first.sourceId,
                            sourceName = first.sourceName,
                            category = first.category,
                            title = first.title,
                            year = first.year ?: existing.year,
                            kind = kindOf(first.titleKind),
                            posterUrl = first.posterUrl ?: existing.posterUrl,
                            language = first.language ?: existing.language,
                            updatedAt = now,
                            searchText = searchText(first.title, first.category, first.sourceName),
                        )
                    )
                }
                fileDao.upsertAll(
                    group.map { item ->
                        FileEntity(
                            titleId = titleId,
                            sourceId = item.sourceId,
                            dirUrl = item.dirUrl,
                            fileName = item.fileName,
                            fileUrl = item.fileUrl,
                            sizeBytes = item.sizeBytes,
                            modified = item.modified,
                            quality = item.quality,
                            sourceTag = item.sourceTag,
                            codec = item.codec,
                            audio = item.audio,
                            season = item.season,
                            episode = item.episode,
                            addedAt = item.addedAt,
                            searchText = item.fileName.lowercase(),
                        )
                    }
                )
                touched += titleId
            }
            titleDao.recomputeForTitles(touched, now)
        }
    }

    suspend fun deleteSourceData(sourceId: Long) {
        db.withTransaction {
            fileDao.deleteForSource(sourceId)
            titleDao.deleteForSource(sourceId)
        }
    }

    private fun kindOf(value: String): TitleKind =
        if (value.equals("series", true) || value.equals("episode", true)) TitleKind.SERIES else TitleKind.MOVIE

    private fun searchText(title: String, category: String, sourceName: String): String =
        "$title $category $sourceName".lowercase()

    private fun sortQualities(values: List<String>): List<String> {
        val order = listOf("2160p", "1080p", "720p", "480p", "360p", "CAM")
        return values.sortedBy { order.indexOf(it).let { i -> if (i < 0) order.size else i } }
    }

    companion object {
        private const val MAX_CACHED_FEED_ITEMS = 54
    }
}
