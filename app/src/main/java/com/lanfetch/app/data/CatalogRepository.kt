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

    /**
     * Removes files the server no longer has and refreshes aggregate cards.
     * [keptPrefixes] covers pruned and failed folders; [skipDeletion] is set
     * when the crawl hit a global cap so a partial scan never destroys data.
     */
    suspend fun finishSync(
        sourceId: Long,
        freshUrls: Set<String>,
        keptPrefixes: Set<String>,
        skipDeletion: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        db.withTransaction {
            if (!skipDeletion) {
                val handle = db.openHelper.writableDatabase
                handle.execSQL("CREATE TEMP TABLE IF NOT EXISTS fresh_files (url TEXT PRIMARY KEY)")
                handle.execSQL("DELETE FROM fresh_files")
                handle.execSQL("CREATE TEMP TABLE IF NOT EXISTS kept_dirs (prefix TEXT PRIMARY KEY)")
                handle.execSQL("DELETE FROM kept_dirs")

                val fresh = handle.compileStatement("INSERT OR IGNORE INTO fresh_files(url) VALUES (?)")
                try {
                    for (url in freshUrls) {
                        fresh.bindString(1, url)
                        fresh.executeInsert()
                    }
                } finally {
                    fresh.close()
                }
                val kept = handle.compileStatement("INSERT OR IGNORE INTO kept_dirs(prefix) VALUES (?)")
                try {
                    for (prefix in keptPrefixes) {
                        kept.bindString(1, prefix)
                        kept.executeInsert()
                    }
                } finally {
                    kept.close()
                }

                handle.execSQL(
                    """
                    DELETE FROM files
                    WHERE sourceId = ?
                      AND fileUrl NOT IN (SELECT url FROM fresh_files)
                      AND NOT EXISTS (
                        SELECT 1 FROM kept_dirs k
                        WHERE substr(files.dirUrl || '/', 1, length(k.prefix)) = k.prefix
                      )
                    """.trimIndent(),
                    arrayOf(sourceId),
                )
                handle.execSQL(
                    "DELETE FROM titles WHERE sourceId = ? AND id NOT IN (SELECT DISTINCT titleId FROM files)",
                    arrayOf(sourceId),
                )
            }
            titleDao.recomputeForSource(sourceId, now)
        }
    }

    suspend fun deleteSourceData(sourceId: Long) {
        db.withTransaction {
            fileDao.deleteForSource(sourceId)
            titleDao.deleteForSource(sourceId)
            db.folders().deleteForSource(sourceId)
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
}
