package com.lanfetch.app.net

import com.lanfetch.app.data.CatalogItem
import com.lanfetch.app.data.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        val failedFolders: List<String>,
        val folderMtimes: Map<String, FolderMeta>,
        val truncated: Boolean,
    ) : SyncResult

    data class Failed(val message: String) : SyncResult
}

/**
 * Parallel crawler for directory servers.
 *
 * All blocking HTTP and Jsoup work happens on [Dispatchers.IO]; the 12 workers
 * use OkHttp's async API so pausing a sync cancels the in-flight calls instead
 * of waiting for a read timeout. Mtime pruning only applies to leaf folders.
 */
class Scraper(
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        const val MAX_DEPTH = 4
        const val MAX_FOLDERS = 6000
        const val MAX_ITEMS = 40000
        const val WORKERS = 12
        const val BATCH_SIZE = 500
        private const val USER_AGENT = "LanFetch/1.3"

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
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    suspend fun fetchListing(url: String): List<DirEntry> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        client.withCall(req) { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            val body = res.body?.string() ?: throw IllegalStateException("Empty response")
            AutoindexParser.parse(body, url)
        }
    }

    /** Just the top-level category names — a single request. */
    suspend fun fetchCategories(source: Source): List<String> =
        fetchListing(source.rootUrl).filter { it.isDir }.map { it.name }

    private data class Node(
        val url: String,
        val category: String,
        val folderName: String,
        val groupUrl: String,
        val groupTitle: String,
        val season: Int?,
        val isSeries: Boolean,
        val depth: Int,
        val modified: String?,
        val inheritedPoster: String? = null,
    )

    suspend fun syncSource(
        source: Source,
        selectedCategories: Set<String> = emptySet(),
        knownFolders: Map<String, FolderMeta> = emptyMap(),
        onProgress: (SyncProgress) -> Unit = {},
        onBatch: suspend (List<CatalogItem>) -> Unit = {},
    ): SyncResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val now = System.currentTimeMillis()
            val channel = Channel<Node>(Channel.UNLIMITED)
            val pending = AtomicInteger(1)
            val seen = ConcurrentHashMap.newKeySet<String>()
            val scannedMtimes = ConcurrentHashMap<String, FolderMeta>()
            val prunedFolders = ConcurrentHashMap.newKeySet<String>()
            val failedFolders = ConcurrentHashMap.newKeySet<String>()
            val foldersScanned = AtomicInteger(0)
            val itemsFound = AtomicInteger(0)
            val rootError = AtomicReference<String?>(null)
            val truncated = AtomicBoolean(false)
            val buffer = ArrayList<CatalogItem>(BATCH_SIZE)
            val bufferLock = Mutex()
            val batchLock = Mutex()

            suspend fun enqueue(node: Node) {
                if (!seen.add(node.url)) return
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
                batchLock.withLock { onBatch(batch) }
            }

            suspend fun process(node: Node) {
                if (CrawlRules.shouldPrune(node.depth == 0, knownFolders[node.url], node.modified)) {
                    prunedFolders += node.url
                    return
                }

                val entries = try {
                    fetchListing(node.url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failedFolders += node.url
                    if (node.depth == 0) rootError.set(e.message ?: "Cannot reach server")
                    return
                }

                val hasVideos = entries.any { !it.isDir && MetaParser.isVideoFile(it.name) }
                val seasonDirs = entries.filter { it.isDir && CrawlRules.isSeasonLike(it.name) }
                val parentIsTitle = node.depth > 0 && (hasVideos || seasonDirs.isNotEmpty() || node.isSeries)
                val childDirs = entries.filter {
                    it.isDir && CrawlRules.shouldDescend(node.depth, hasVideos, it.name, MAX_DEPTH, parentIsTitle)
                }
                scannedMtimes[node.url] = FolderMeta(
                    modified = node.modified,
                    isLeaf = childDirs.isEmpty(),
                    hasVideos = hasVideos,
                )

                val scanned = foldersScanned.incrementAndGet()
                onProgress(SyncProgress(scanned, pending.get(), itemsFound.get(), node.url))
                if (scanned >= MAX_FOLDERS) {
                    truncated.set(true)
                    failedFolders += node.url
                    return
                }

                val folderPoster = pickPoster(entries) ?: node.inheritedPoster
                val titleName = if (node.groupUrl == node.url) node.folderName else node.groupTitle

                for (entry in entries) {
                    if (entry.isDir) continue
                    if (!MetaParser.isVideoFile(entry.name)) continue
                    if (itemsFound.get() >= MAX_ITEMS) {
                        truncated.set(true)
                        continue
                    }
                    val item = MetaParser.buildItem(
                        sourceId = source.id,
                        sourceName = source.name,
                        category = node.category.ifBlank { "General" },
                        titleFolderUrl = node.groupUrl,
                        dirUrl = node.url,
                        titleName = titleName,
                        fileName = entry.name,
                        fileUrl = entry.url,
                        sizeBytes = entry.sizeBytes,
                        modified = entry.modified,
                        addedAt = now,
                        posterUrl = folderPoster,
                        isSeries = node.isSeries || seasonDirs.isNotEmpty() ||
                            MetaParser.parseSeason(titleName) != null,
                        folderSeason = node.season,
                    )
                    itemsFound.incrementAndGet()
                    bufferLock.withLock { buffer += item }
                    flush(final = false)
                }

                for (entry in entries) {
                    if (!entry.isDir) continue
                    if (node.depth == 0 && selectedCategories.isNotEmpty() && entry.name !in selectedCategories) continue
                    if (!CrawlRules.shouldDescend(node.depth, hasVideos, entry.name, MAX_DEPTH, parentIsTitle)) continue

                    val childIsSeason = CrawlRules.isSeasonLike(entry.name)
                    val seasonOfShow = childIsSeason && parentIsTitle
                    enqueue(
                        if (seasonOfShow) {
                            Node(
                                url = entry.url,
                                category = node.category,
                                folderName = entry.name,
                                groupUrl = node.groupUrl,
                                groupTitle = rawGroupTitle(node),
                                season = MetaParser.parseSeason(entry.name) ?: node.season,
                                isSeries = true,
                                depth = node.depth + 1,
                                modified = entry.modified,
                                inheritedPoster = folderPoster,
                            )
                        } else {
                            Node(
                                url = entry.url,
                                category = if (node.depth == 0) entry.name else node.category,
                                folderName = entry.name,
                                groupUrl = entry.url,
                                groupTitle = entry.name,
                                season = null,
                                isSeries = false,
                                depth = node.depth + 1,
                                modified = entry.modified,
                                inheritedPoster = folderPoster,
                            )
                        }
                    )
                }
            }

            seen += source.rootUrl
            channel.send(
                Node(
                    url = source.rootUrl,
                    category = "",
                    folderName = source.name,
                    groupUrl = source.rootUrl,
                    groupTitle = source.name,
                    season = null,
                    isSeries = false,
                    depth = 0,
                    modified = null,
                )
            )

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
                    failedFolders = failedFolders.toList(),
                    folderMtimes = scannedMtimes.toMap(),
                    truncated = truncated.get(),
                )
            }
        }
    }

    private fun rawGroupTitle(node: Node): String =
        if (node.groupUrl == node.url) node.folderName else node.groupTitle
}
