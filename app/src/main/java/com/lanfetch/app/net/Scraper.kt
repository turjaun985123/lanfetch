package com.lanfetch.app.net

import com.lanfetch.app.data.CatalogItem
import com.lanfetch.app.data.Source
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class SyncProgress(
    val foldersScanned: Int,
    val foldersQueued: Int,
    val itemsFound: Int,
    val currentPath: String,
)

sealed interface SyncResult {
    data class Ok(
        val totalItems: Int,
        val prunedFolders: List<String>,
        val folderMtimes: Map<String, String?>,
    ) : SyncResult
    data class Failed(val message: String) : SyncResult
}

/**
 * Parallel crawler for directory servers (v1.1 engine).
 *
 * What makes sync fast now:
 * - **12 worker coroutines** fetch listings concurrently (was: strictly
 *   one folder at a time) over a tuned OkHttp pool (16 connections/host).
 * - **Category selection** — only the top-level categories the user picked
 *   are crawled at all.
 * - **Crawl rules** — junk folders (Sample/Trailer/Subs/…) are skipped, and
 *   a folder that already holds video files is only descended into
 *   season-like subfolders.
 * - **Mtime pruning** — a folder whose modified stamp matches the last
 *   sync is not re-fetched; its catalog entries are kept as-is.
 * - **Streaming batches** — items are handed to the caller in batches of
 *   100 so the catalog fills progressively instead of at the very end.
 */
class Scraper(
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        const val MAX_DEPTH = 4
        const val MAX_FOLDERS = 6000
        const val MAX_ITEMS = 40000
        const val WORKERS = 12
        const val BATCH_SIZE = 100

        private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        /**
         * Best poster candidate in a folder listing. Servers put a photo in
         * almost every movie folder (poster.jpg / folder.jpg / cover.jpg…);
         * prefer those names, else the first image in the folder.
         */
        fun pickPoster(entries: List<DirEntry>): String? {
            fun score(name: String): Int {
                val n = name.lowercase()
                return when {
                    "poster" in n -> 5
                    "folder" in n -> 4
                    "cover" in n -> 4
                    "fanart" in n -> 3
                    "backdrop" in n -> 2
                    else -> 1
                }
            }
            return entries
                .filter { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in imageExtensions }
                .maxByOrNull { score(it.name) }
                ?.url
        }

        fun defaultClient(): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }

    fun fetchListing(url: String): List<DirEntry> {
        val req = Request.Builder().url(url).header("User-Agent", "LanFetch/1.1").build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            val body = res.body?.string() ?: throw IllegalStateException("Empty response")
            return AutoindexParser.parse(body, url)
        }
    }

    /** Just the top-level category names — a single request. */
    fun fetchCategories(source: Source): List<String> =
        fetchListing(source.rootUrl).filter { it.isDir }.map { it.name }

    private data class Node(
        val url: String,
        val category: String,
        val folderName: String,
        val depth: Int,
        val modified: String?,
        val inheritedPoster: String? = null,
    )

    suspend fun syncSource(
        source: Source,
        selectedCategories: Set<String> = emptySet(),
        knownMtimes: Map<String, String> = emptyMap(),
        onProgress: (SyncProgress) -> Unit = {},
        onBatch: suspend (List<CatalogItem>) -> Unit = {},
    ): SyncResult = coroutineScope {
        val now = System.currentTimeMillis()
        val channel = Channel<Node>(Channel.UNLIMITED)
        val pending = AtomicInteger(1)
        val seen = ConcurrentHashMap.newKeySet<String>()
        val scannedMtimes = ConcurrentHashMap<String, String?>()
        val prunedFolders = ConcurrentHashMap.newKeySet<String>()
        val foldersScanned = AtomicInteger(0)
        val itemsFound = AtomicInteger(0)
        val rootError = AtomicReference<String?>(null)
        val buffer = ArrayList<CatalogItem>(BATCH_SIZE)
        val bufferLock = Mutex()
        val seenLock = Mutex()

        suspend fun enqueue(node: Node) {
            val fresh = seenLock.withLock { seen.add(node.url) }
            if (!fresh) return
            pending.incrementAndGet()
            channel.send(node)
        }

        suspend fun flush(final: Boolean) {
            val batch: List<CatalogItem> = bufferLock.withLock {
                if (buffer.isEmpty() || (!final && buffer.size < BATCH_SIZE)) return
                val copy = buffer.toList()
                buffer.clear()
                copy
            }
            onBatch(batch)
        }

        suspend fun process(node: Node) {
            // Mtime pruning: unchanged subtree -> keep old catalog rows, skip fetch.
            if (CrawlRules.shouldPrune(node.depth == 0, knownMtimes[node.url], node.modified)) {
                prunedFolders += node.url
                return
            }
            val entries = try {
                fetchListing(node.url)
            } catch (e: Exception) {
                if (node.depth == 0) rootError.set(e.message ?: "Cannot reach server")
                return
            }
            scannedMtimes[node.url] = node.modified
            val scanned = foldersScanned.incrementAndGet()
            onProgress(SyncProgress(scanned, pending.get(), itemsFound.get(), node.url))
            if (scanned >= MAX_FOLDERS) return

            val hasVideos = entries.any { !it.isDir && MetaParser.isVideoFile(it.name) }
            val folderPoster = pickPoster(entries) ?: node.inheritedPoster

            for (entry in entries) {
                if (entry.isDir) {
                    if (node.depth == 0 && selectedCategories.isNotEmpty() && entry.name !in selectedCategories) continue
                    if (!CrawlRules.shouldDescend(node.depth, hasVideos, entry.name, MAX_DEPTH)) continue
                    enqueue(
                        Node(
                            url = entry.url,
                            category = if (node.depth == 0) entry.name else node.category,
                            folderName = entry.name,
                            depth = node.depth + 1,
                            modified = entry.modified,
                            inheritedPoster = folderPoster,
                        )
                    )
                } else if (MetaParser.isVideoFile(entry.name)) {
                    if (itemsFound.get() >= MAX_ITEMS) continue
                    val item = MetaParser.toItem(
                        sourceId = source.id,
                        sourceName = source.name,
                        category = node.category.ifBlank { "General" },
                        folderUrl = node.url,
                        folderName = node.folderName,
                        fileName = entry.name,
                        fileUrl = entry.url,
                        sizeBytes = entry.sizeBytes,
                        modified = entry.modified,
                        addedAt = now,
                        posterUrl = folderPoster,
                    )
                    itemsFound.incrementAndGet()
                    bufferLock.withLock { buffer += item }
                    flush(final = false)
                }
            }
        }

        seen += source.rootUrl
        channel.send(Node(source.rootUrl, "", source.name, 0, null))

        val workers = List(WORKERS) {
            launch {
                for (node in channel) {
                    try {
                        process(node)
                    } finally {
                        if (pending.decrementAndGet() == 0) channel.close()
                    }
                }
            }
        }
        workers.forEach { it.join() }
        flush(final = true)

        val err = rootError.get()
        if (err != null && foldersScanned.get() == 0) {
            SyncResult.Failed("$err — is this server reachable from your network?")
        } else {
            SyncResult.Ok(
                totalItems = itemsFound.get(),
                prunedFolders = prunedFolders.toList(),
                folderMtimes = scannedMtimes.toMap(),
            )
        }
    }
}
