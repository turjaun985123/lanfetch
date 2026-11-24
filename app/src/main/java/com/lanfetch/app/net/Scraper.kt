package com.lanfetch.app.net

import com.lanfetch.app.data.CatalogItem
import com.lanfetch.app.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class SyncProgress(val foldersScanned: Int, val itemsFound: Int, val currentPath: String)

sealed interface SyncResult {
    data class Ok(val items: List<CatalogItem>) : SyncResult
    data class Failed(val message: String) : SyncResult
}

/**
 * Crawls a directory server: root -> category folders -> title folders
 * (-> season folders) -> video files. Depth-limited and count-limited so a
 * huge server can't run away with the sync.
 */
class Scraper(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    companion object {
        const val MAX_DEPTH = 4
        const val MAX_FOLDERS = 4000
        const val MAX_ITEMS = 30000
    }

    private fun fetchListing(url: String): List<DirEntry> {
        val req = Request.Builder().url(url).header("User-Agent", "LanFetch/1.0").build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            val body = res.body?.string() ?: throw IllegalStateException("Empty response")
            return AutoindexParser.parse(body, url)
        }
    }

    suspend fun syncSource(
        source: Source,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult = withContext(Dispatchers.IO) {
        try {
            val items = mutableListOf<CatalogItem>()
            val now = System.currentTimeMillis()
            var foldersScanned = 0

            // queue of (url, category, folderName, depth)
            data class Node(val url: String, val category: String, val folderName: String, val depth: Int)
            val queue = ArrayDeque<Node>()
            queue.add(Node(source.rootUrl, "", source.name, 0))
            val seen = HashSet<String>()

            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (!seen.add(node.url)) continue
                if (foldersScanned >= MAX_FOLDERS || items.size >= MAX_ITEMS) break
                val entries = try { fetchListing(node.url) } catch (e: Exception) {
                    if (node.depth == 0) throw e else continue
                }
                foldersScanned++
                onProgress(SyncProgress(foldersScanned, items.size, node.url))

                for (entry in entries) {
                    if (entry.isDir) {
                        if (node.depth < MAX_DEPTH) {
                            val category = when {
                                node.depth == 0 -> entry.name
                                node.category.isBlank() -> entry.name
                                else -> node.category
                            }
                            queue.add(Node(entry.url, category, entry.name, node.depth + 1))
                        }
                    } else if (MetaParser.isVideoFile(entry.name)) {
                        items += MetaParser.toItem(
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
                        )
                    }
                }
            }
            SyncResult.Ok(items)
        } catch (e: Exception) {
            SyncResult.Failed(e.message ?: "Network error — is this server reachable from your network?")
        }
    }
}
