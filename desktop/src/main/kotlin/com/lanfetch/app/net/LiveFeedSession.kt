package com.lanfetch.app.net

import com.lanfetch.app.data.CategoryLocation
import com.lanfetch.app.data.LiveCategory
import com.lanfetch.app.data.LiveTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * YouTube-style progressive page cursor over live server category folders.
 *
 * Never crawls an entire server up front: each `loadNextPage(pageSize)` expands
 * only enough folders (newest year / modified first, round-robin across
 * categories in "All" mode) to fill one screen page and then pauses.
 */
class LiveFeedSession(
    private val client: LiveServerClient,
    categories: List<LiveCategory>,
    selectedCategory: String?,
    private val kindFilter: String? = null, // null = all, "movie", "series"
    initialEmittedUrls: Set<String> = emptySet(),
) {
    private data class Node(
        val location: CategoryLocation,
        val categoryName: String,
        val url: String,
        val name: String,
        val modified: String?,
        val depth: Int,
    )

    private class CategoryStream(
        val name: String,
        val stack: ArrayDeque<Node>,
    )

    private val mutex = Mutex()
    private val seenUrls = HashSet<String>()
    private val emittedTitles = HashSet<String>()
    private val streams: ArrayDeque<CategoryStream>

    // Audit B14: the getter used to walk the deques from whatever thread
    // asked, while page loads mutate them under the mutex. The flag is only
    // written under the mutex (and in init) and read atomically.
    private val hasMoreFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    val hasMore: Boolean get() = hasMoreFlag.get()

    private fun refreshHasMore() {
        hasMoreFlag.set(streams.any { it.stack.isNotEmpty() })
    }

    init {
        emittedTitles.addAll(initialEmittedUrls)
        val chosen = if (selectedCategory == null) {
            categories.filterNot { CrawlRules.isNonMediaCategory(it.name) }
        } else {
            categories.filter { it.name.equals(selectedCategory, ignoreCase = true) }
        }

        streams = ArrayDeque()
        for (cat in chosen) {
            val stack = ArrayDeque<Node>()
            for (loc in cat.locations) {
                if (seenUrls.add(loc.url)) {
                    stack.addLast(
                        Node(
                            location = loc,
                            categoryName = cat.name,
                            url = loc.url,
                            name = cat.name,
                            modified = null,
                            depth = 0,
                        )
                    )
                }
            }
            if (stack.isNotEmpty()) streams.addLast(CategoryStream(cat.name, stack))
        }
        refreshHasMore()
    }

    suspend fun loadNextPage(pageSize: Int = 18): List<LiveTitle> = mutex.withLock {
        val collected = ArrayList<LiveTitle>(pageSize)
        var requestsMade = 0
        val maxRequestsPerPage = pageSize * 6

        while (collected.size < pageSize && requestsMade < maxRequestsPerPage && hasMore) {
            val needed = pageSize - collected.size
            val batch = popRoundRobinBatch(limit = minOf(BATCH_CONCURRENCY, needed))
            if (batch.isEmpty()) break
            requestsMade += batch.size

            val fetched = coroutineScope {
                batch.map { (stream, node) ->
                    async {
                        val entries = if (client.isTemporarilyUnreachable(node.location.source.id)) {
                            null
                        } else {
                            try {
                                client.fetchListing(node.url, node.location.source.id)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                currentCoroutineContext().ensureActive()
                                null
                            }
                        }
                        Triple(stream, node, entries)
                    }
                }.awaitAll()
            }

            for ((stream, node, entries) in fetched) {
                if (entries == null) continue

                if (node.depth > 0 && !isContainerFolderName(node.name)) {
                    val asTitle = client.buildLiveTitleFromEntries(
                        sourceId = node.location.source.id,
                        sourceName = node.location.source.name,
                        category = node.categoryName,
                        folderUrl = node.url,
                        rawTitleName = node.name,
                        entries = entries,
                        modified = node.modified,
                    )
                    if (asTitle != null) {
                        if ((kindFilter == null || asTitle.kind == kindFilter) &&
                            emittedTitles.add(asTitle.folderUrl)
                        ) {
                            collected += asTitle
                        }
                        continue
                    }
                }

                if (node.depth >= MAX_DEPTH) continue
                val childDirs = entries
                    .filter { it.isDir && !CrawlRules.isJunkFolder(it.name) && !CrawlRules.isSeasonLike(it.name) }
                    .let { sortNewestFirst(it) }

                // Push in reverse so the newest folder is popped first from the front.
                for (child in childDirs.asReversed()) {
                    if (child.url !in emittedTitles && seenUrls.add(child.url)) {
                        stream.stack.addFirst(
                            Node(
                                location = node.location,
                                categoryName = node.categoryName,
                                url = child.url,
                                name = child.name,
                                modified = child.modified,
                                depth = node.depth + 1,
                            )
                        )
                    }
                }
            }

            // Remove streams only after fetched children have been pushed onto their stacks.
            val remaining = ArrayDeque<CategoryStream>()
            while (streams.isNotEmpty()) {
                val s = streams.removeFirst()
                if (s.stack.isNotEmpty()) remaining.addLast(s)
            }
            streams.addAll(remaining)
        }
        refreshHasMore()
        return collected
    }

    private fun popRoundRobinBatch(limit: Int): List<Pair<CategoryStream, Node>> {
        val batch = ArrayList<Pair<CategoryStream, Node>>(limit)
        val exhaustedThisTurn = HashSet<CategoryStream>()
        var rotations = 0
        while (batch.size < limit && streams.isNotEmpty() && rotations < streams.size) {
            val stream = streams.removeFirst()
            streams.addLast(stream)

            if (stream in exhaustedThisTurn || stream.stack.isEmpty()) {
                rotations++
                continue
            }

            val next = stream.stack.first()
            val isContainer = next.depth == 0 || isContainerFolderName(next.name)
            val node = stream.stack.removeFirst()
            batch += stream to node
            rotations = 0
            // Wait on container folders (e.g. (2026) before descending into (2025))
            // so we expand the newest container before fetching older year folders.
            if (isContainer) {
                exhaustedThisTurn += stream
            }
        }
        return batch
    }

    private fun sortNewestFirst(dirs: List<DirEntry>): List<DirEntry> =
        dirs.sortedWith(
            compareByDescending<DirEntry> { extractFolderYear(it.name) ?: Int.MIN_VALUE }
                .thenByDescending { it.modified.orEmpty() }
                .thenBy { it.name.lowercase() }
        )

    private fun isContainerFolderName(name: String): Boolean =
        extractFolderYear(name) != null || ALPHA_CONTAINER_RE.matches(name.trim())

    private fun extractFolderYear(name: String): Int? {
        // Prefer year container folders like "(2026)" or "(2025) 1080p" or "(1995) & Before" or "2026".
        val m = YEAR_CONTAINER_RE.find(name.trim()) ?: return null
        return (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()
    }

    companion object {
        private const val MAX_DEPTH = 4
        private const val BATCH_CONCURRENCY = 8
        private val YEAR_CONTAINER_RE = Regex("""^(?:\(((?:19|20)\d{2})\)|((?:19|20)\d{2})$)""")
        private val ALPHA_CONTAINER_RE = Regex("""^(?:0-9|A-Z|[A-Z]\s*[-–]\s*[A-Z])$""", RegexOption.IGNORE_CASE)
    }
}
