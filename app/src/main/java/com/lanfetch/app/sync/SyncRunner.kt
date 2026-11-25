package com.lanfetch.app.sync

import com.lanfetch.app.data.AppDb
import com.lanfetch.app.data.CatalogRepository
import com.lanfetch.app.data.FolderIndexEntity
import com.lanfetch.app.data.SourceRepository
import com.lanfetch.app.net.FolderMeta
import com.lanfetch.app.net.Scraper
import com.lanfetch.app.net.SyncProgress
import com.lanfetch.app.net.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs one source sync (crawl -> batched DB writes -> prune -> aggregates).
 * Always on IO; batches are written in transactions and the deletion pass
 * protects pruned, failed and truncated folders.
 */
class SyncRunner(
    private val db: AppDb,
    private val sources: SourceRepository,
    private val catalog: CatalogRepository,
    private val scraper: Scraper,
) {
    private val sourceLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun run(
        sourceId: Long,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult = withContext(Dispatchers.IO) {
        val mutex = sourceLocks.computeIfAbsent(sourceId) { Mutex() }
        mutex.withLock {
            runLocked(sourceId, onProgress)
        }
    }

    private suspend fun runLocked(
        sourceId: Long,
        onProgress: (SyncProgress) -> Unit,
    ): SyncResult {
        val source = sources.byId(sourceId)
            ?: return SyncResult.Failed("Server no longer exists")

        val known = db.folders().forSource(sourceId).associate {
            it.url to FolderMeta(it.modified, it.isLeaf, it.hasVideos)
        }
        val freshUrls = Collections.synchronizedSet(HashSet<String>())
        var lastProgressAt = 0L

        val result = try {
            scraper.syncSource(
                source = source,
                selectedCategories = source.selectedCategories,
                knownFolders = known,
                onProgress = { progress ->
                    val now = System.currentTimeMillis()
                    if (now - lastProgressAt >= 400) {
                        lastProgressAt = now
                        onProgress(progress)
                    }
                },
                onBatch = { batch ->
                    freshUrls += batch.map { it.fileUrl }
                    catalog.upsertBatch(batch)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Failed(e.message ?: "Sync failed")
        }

        when (result) {
            is SyncResult.Failed -> {
                sources.markSynced(sourceId, db.files().countForSource(sourceId), result.message)
            }

            is SyncResult.Ok -> {
                val kept = HashSet<String>()
                result.prunedFolders.forEach { url -> kept += "${url.trimEnd('/')}/" }
                result.failedFolders.forEach { url -> kept += "${url.trimEnd('/')}/" }
                catalog.finishSync(
                    sourceId = sourceId,
                    freshUrls = freshUrls,
                    keptPrefixes = kept,
                    skipDeletion = result.truncated,
                )

                val merged = HashMap<String, FolderMeta>()
                result.prunedFolders.forEach { url ->
                    merged[url] = known[url] ?: FolderMeta(null, isLeaf = false, hasVideos = false)
                }
                result.failedFolders.forEach { url ->
                    merged[url] = known[url] ?: FolderMeta(null, isLeaf = false, hasVideos = false)
                }
                merged.putAll(result.folderMtimes)
                db.folders().replaceForSource(
                    sourceId,
                    merged.map { (url, meta) ->
                        FolderIndexEntity(sourceId, url, meta.modified, meta.isLeaf, meta.hasVideos)
                    },
                )

                sources.markSynced(
                    sourceId = sourceId,
                    itemCount = db.files().countForSource(sourceId),
                    error = null,
                )
            }
        }
        return result
    }
}
