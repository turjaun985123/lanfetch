package com.lanfetch.app.net

import com.lanfetch.app.data.CatalogFile
import com.lanfetch.app.data.CategoryLocation
import com.lanfetch.app.data.LiveCategory
import com.lanfetch.app.data.LiveTitle
import com.lanfetch.app.data.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ServerProbeResult(
    val sourceId: Long,
    val reachable: Boolean,
    val latencyMs: Long = 0L,
    val detail: String = "",
)

private const val MAX_SERVER_BODY_BYTES = 4_000_000L

private fun Response.readBoundedBody(): String {
    val declaredLength = header("Content-Length")?.toLongOrNull()
    require(declaredLength == null || declaredLength <= MAX_SERVER_BODY_BYTES) {
        "Server response is too large"
    }
    val body = peekBody(MAX_SERVER_BODY_BYTES + 1).string()
    require(body.toByteArray(Charsets.UTF_8).size <= MAX_SERVER_BODY_BYTES) {
        "Server response is too large"
    }
    return body
}

/**
 * Live directory browser and direct server search client.
 *
 * - Categories are fetched with a single request per enabled server.
 * - Direct search uses h5ai's native JSON `search` endpoint (`POST ?`) when
 *   supported, falling back to shallow live breadth-first matching on plain
 *   Apache/nginx servers.
 * - Posters and episode files are loaded lazily per visible page / tapped card.
 */
class LiveServerClient(
    baseClient: OkHttpClient,
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val unreachableUntil = ConcurrentHashMap<Long, Long>()
    private val listingCache = TtlLruCache<String, List<DirEntry>>(maxEntries = 600, ttlMs = LISTING_TTL_MS)
    private val filesCache = TtlLruCache<String, List<CatalogFile>>(maxEntries = 250, ttlMs = LISTING_TTL_MS)
    private val searchCache = TtlLruCache<String, List<LiveTitle>>(maxEntries = 40, ttlMs = SEARCH_TTL_MS)

    fun clearReachabilityCache() {
        unreachableUntil.clear()
    }

    fun clearAllCaches() {
        unreachableUntil.clear()
        listingCache.clear()
        filesCache.clear()
        searchCache.clear()
    }

    /** Lightweight reachability & latency probe for the Servers screen. */
    suspend fun probeServer(source: Source): ServerProbeResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val req = Request.Builder()
            .url(source.rootUrl)
            .head()
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            client.withCall(req) { res ->
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                if (res.isSuccessful || res.code == 405) {
                    if (res.code == 405) {
                        val getReq = Request.Builder().url(source.rootUrl).header("User-Agent", USER_AGENT).build()
                        val t0 = System.currentTimeMillis()
                        return@withCall client.withCall(getReq) { getRes ->
                            val getMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                            if (getRes.isSuccessful) {
                                markReachable(source.id)
                                ServerProbeResult(source.id, true, getMs, "${getMs} ms")
                            } else {
                                ServerProbeResult(source.id, false, getMs, "HTTP ${getRes.code}")
                            }
                        }
                    }
                    markReachable(source.id)
                    ServerProbeResult(source.id, true, elapsed, "${elapsed} ms")
                } else {
                    ServerProbeResult(source.id, false, elapsed, "HTTP ${res.code}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (isConnectFailure(e)) markUnreachable(source.id)
            ServerProbeResult(
                sourceId = source.id,
                reachable = false,
                detail = e.message?.take(60) ?: "Unreachable",
            )
        }
    }

    fun isTemporarilyUnreachable(sourceId: Long): Boolean {
        val until = unreachableUntil[sourceId] ?: return false
        if (System.currentTimeMillis() < until) return true
        unreachableUntil.remove(sourceId, until)
        return false
    }

    private fun markUnreachable(sourceId: Long) {
        unreachableUntil[sourceId] = System.currentTimeMillis() + UNREACHABLE_COOLDOWN_MS
    }

    private fun markReachable(sourceId: Long) {
        unreachableUntil.remove(sourceId)
    }

    private fun isConnectFailure(e: Exception): Boolean =
        e is ConnectException ||
            e is NoRouteToHostException ||
            e is UnknownHostException ||
            (e is SocketTimeoutException &&
                e.message?.contains("connect", ignoreCase = true) == true)

    suspend fun fetchListing(
        url: String,
        sourceId: Long? = null,
        forceRefresh: Boolean = false,
    ): List<DirEntry> {
        if (!forceRefresh) {
            listingCache.get(url)?.let { return it }
        }
        return withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            try {
                val entries = client.withCall(req) { res ->
                    if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                    val body = res.readBoundedBody().ifBlank { throw IllegalStateException("Empty response") }
                    AutoindexParser.parse(body, url)
                }
                if (sourceId != null) markReachable(sourceId)
                listingCache.put(url, entries)
                entries
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (sourceId != null && isConnectFailure(e)) markUnreachable(sourceId)
                throw e
            }
        }
    }

    /**
     * Streams merged categories from all enabled servers as each server replies,
     * so healthy LAN servers populate the category bar within ~150 ms.
     */
    fun discoverCategories(
        sources: List<Source>,
        forceRefresh: Boolean = false,
    ): Flow<List<LiveCategory>> = channelFlow {
        val enabled = sources.filter { it.enabled }
        if (enabled.isEmpty()) {
            send(emptyList())
            return@channelFlow
        }
        val byName = LinkedHashMap<String, MutableList<CategoryLocation>>()
        val ch = Channel<Pair<Source, List<DirEntry>>>(Channel.UNLIMITED)

        val jobs = enabled.map { source ->
            launch(Dispatchers.IO) {
                val entries = runCatching { fetchListing(source.rootUrl, source.id, forceRefresh) }.getOrNull()
                    ?: emptyList()
                ch.send(source to entries)
            }
        }
        launch {
            jobs.forEach { it.join() }
            ch.close()
        }

        for ((source, entries) in ch) {
            var changed = false
            for (entry in entries) {
                if (!entry.isDir) continue
                if (CrawlRules.isJunkFolder(entry.name)) continue
                val list = byName.getOrPut(entry.name) { mutableListOf() }
                if (list.none { it.url == entry.url }) {
                    list += CategoryLocation(source, entry.url)
                    changed = true
                }
            }
            if (changed) {
                send(sortCategories(byName))
            }
        }
        send(sortCategories(byName))
    }

    /**
     * Direct live search across all enabled servers, streaming merged results
     * incrementally as each server finishes, with short-lived query result caching.
     */
    fun searchDirect(
        sources: List<Source>,
        query: String,
        forceRefresh: Boolean = false,
    ): Flow<List<LiveTitle>> = channelFlow {
        val trimmed = query.trim()
        val enabled = sources.filter { it.enabled && !isTemporarilyUnreachable(it.id) }
        if (trimmed.isEmpty() || enabled.isEmpty()) {
            send(emptyList())
            return@channelFlow
        }

        val cacheKey = "${trimmed.lowercase()}|${enabled.sortedBy { it.rootUrl }.joinToString(",") { it.rootUrl }}"
        if (!forceRefresh) {
            searchCache.get(cacheKey)?.let { cached ->
                send(cached)
                return@channelFlow
            }
        }

        val ch = Channel<List<LiveTitle>>(Channel.UNLIMITED)
        val jobs = enabled.map { source ->
            launch(Dispatchers.IO) {
                val hits = runCatching { searchSingleServer(source, trimmed) }.getOrNull() ?: emptyList()
                ch.send(hits)
            }
        }
        launch {
            jobs.forEach { it.join() }
            ch.close()
        }

        val merged = LinkedHashMap<String, LiveTitle>()
        var latestRanked = emptyList<LiveTitle>()
        for (batch in ch) {
            for (item in batch) {
                val existing = merged[item.folderUrl]
                merged[item.folderUrl] = if (existing == null) item else mergeSearchTitle(existing, item)
            }
            latestRanked = rankSearchResults(merged.values.toList(), trimmed)
            send(latestRanked)
        }
        if (latestRanked.isNotEmpty()) {
            searchCache.put(cacheKey, latestRanked)
        }
    }

    /**
     * Lazily hydrates poster & file counts for one page of search results
     * (like YouTube resolving visible cards as you scroll). Returns null for
     * folders that turned out to have no video or season content.
     */
    suspend fun hydrateTitle(title: LiveTitle): LiveTitle? = withContext(Dispatchers.IO) {
        if (title.posterResolved && title.filesLoaded) return@withContext title
        val entries = runCatching { fetchListing(title.folderUrl, title.sourceId) }.getOrNull()
            ?: return@withContext title.copy(posterResolved = true)
        val built = buildLiveTitleFromEntries(
            sourceId = title.sourceId,
            sourceName = title.sourceName,
            category = title.category,
            folderUrl = title.folderUrl,
            rawTitleName = title.rawTitleName,
            entries = entries,
            modified = title.modified,
        )
        built ?: if (title.cachedFiles.isNotEmpty()) {
            title.copy(posterResolved = true)
        } else {
            null
        }
    }

    /** Loads all downloadable video files inside a title (and its seasons). */
    suspend fun loadFiles(
        title: LiveTitle,
        forceRefresh: Boolean = false,
    ): List<CatalogFile> = withContext(Dispatchers.IO) {
        if (!forceRefresh && title.filesLoaded && title.cachedFiles.isNotEmpty()) {
            return@withContext title.cachedFiles
        }
        if (!forceRefresh) {
            filesCache.get(title.folderUrl)?.let { return@withContext it }
        }

        val rootEntries = fetchListing(title.folderUrl, title.sourceId, forceRefresh)
        val directVideos = rootEntries.filter { !it.isDir && MetaParser.isVideoFile(it.name) }
        val seasonDirs = rootEntries.filter { it.isDir && CrawlRules.isSeasonLike(it.name) }

        val collected = ArrayList<CatalogFile>()
        for (entry in directVideos) {
            collected += toCatalogFile(title.rawTitleName, entry, folderSeason = null)
        }

        if (seasonDirs.isNotEmpty()) {
            val sem = Semaphore(4)
            coroutineScope {
                val nested = seasonDirs.map { seasonDir ->
                    async {
                        sem.withPermit {
                            loadSeasonDirectory(
                                showName = title.rawTitleName,
                                dirEntry = seasonDir,
                                parentSeason = MetaParser.parseSeason(seasonDir.name),
                                depth = 0,
                                forceRefresh = forceRefresh,
                            )
                        }
                    }
                }.awaitAll()
                nested.forEach { collected.addAll(it) }
            }
        }

        val sorted = collected.sortedWith(
            compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.fileName.lowercase() })
        )
        if (sorted.isNotEmpty()) {
            filesCache.put(title.folderUrl, sorted)
        }
        sorted
    }

    private suspend fun loadSeasonDirectory(
        showName: String,
        dirEntry: DirEntry,
        parentSeason: Int?,
        depth: Int,
        forceRefresh: Boolean = false,
    ): List<CatalogFile> {
        val entries = runCatching { fetchListing(dirEntry.url, forceRefresh = forceRefresh) }.getOrNull()
            ?: return emptyList()
        val season = parentSeason ?: MetaParser.parseSeason(dirEntry.name)
        val out = ArrayList<CatalogFile>()
        for (entry in entries) {
            if (!entry.isDir && MetaParser.isVideoFile(entry.name)) {
                out += toCatalogFile(showName, entry, folderSeason = season)
            } else if (entry.isDir && depth < 1 && CrawlRules.isSeasonLike(entry.name)) {
                out += loadSeasonDirectory(showName, entry, season, depth + 1, forceRefresh)
            }
        }
        return out
    }

    private suspend fun searchSingleServer(source: Source, query: String): List<LiveTitle> {
        val rootUri = URI(source.rootUrl)
        val rootPath = rootUri.rawPath.ifBlank { "/" }
        val payload = H5aiJson.buildSearchRequest(rootPath, query)
        val endpoint = "${source.rootUrl.trimEnd('/')}/?"
        val req = Request.Builder()
            .url(endpoint)
            .header("User-Agent", USER_AGENT)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val h5aiItems = try {
            client.withCall(req) { res ->
                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                val contentType = res.header("Content-Type").orEmpty()
                val body = res.readBoundedBody()
                markReachable(source.id)
                if (contentType.contains("json", ignoreCase = true) || body.trimStart().startsWith("{")) {
                    H5aiJson.parseSearch(body)
                } else {
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (isConnectFailure(e)) markUnreachable(source.id)
            return emptyList()
        }

        if (h5aiItems != null) {
            return groupH5aiSearchHits(source, rootUri, h5aiItems)
        }
        return fallbackBreadthSearch(source, query)
    }

    private suspend fun fallbackBreadthSearch(source: Source, query: String): List<LiveTitle> {
        val needle = query.lowercase()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(source.rootUrl to 0)
        val seen = HashSet<String>().apply { add(source.rootUrl) }
        val results = LinkedHashMap<String, LiveTitle>()
        var scanned = 0
        val maxFolders = 60

        while (queue.isNotEmpty() && scanned < maxFolders && results.size < 40) {
            val (url, depth) = queue.removeFirst()
            val entries = runCatching { fetchListing(url, source.id) }.getOrNull() ?: continue
            scanned++

            val category = extractCategory(source.rootUrl, url)
            for (entry in entries) {
                if (entry.isDir) {
                    if (CrawlRules.isJunkFolder(entry.name)) continue
                    if (depth == 0 && CrawlRules.isNonMediaCategory(entry.name)) continue
                    if (entry.name.lowercase().contains(needle) && depth >= 1) {
                        val folderEntries = runCatching { fetchListing(entry.url, source.id) }.getOrNull()
                        if (folderEntries != null) {
                            buildLiveTitleFromEntries(
                                sourceId = source.id,
                                sourceName = source.name,
                                category = category.ifBlank { "General" },
                                folderUrl = entry.url,
                                rawTitleName = entry.name,
                                entries = folderEntries,
                                modified = entry.modified,
                            )?.let { results[entry.url] = it }
                        }
                    } else if (depth < 2 && seen.add(entry.url)) {
                        queue.addLast(entry.url to depth + 1)
                    }
                } else if (MetaParser.isVideoFile(entry.name) && entry.name.lowercase().contains(needle)) {
                    val parentName = decodeLastSegment(url)
                    val title = buildSingleHitTitle(source, category.ifBlank { "General" }, url, parentName, entry)
                    results[url] = title
                }
            }
        }
        return results.values.toList()
    }

    internal fun groupH5aiSearchHits(
        source: Source,
        rootUri: URI,
        items: List<H5aiItem>,
    ): List<LiveTitle> {
        val rootPath = rootUri.rawPath.trimEnd('/') + "/"
        val groupedFiles = LinkedHashMap<String, MutableList<CatalogFile>>()
        val titleDirs = LinkedHashMap<String, String>() // folderUrl -> rawTitleName
        val categories = LinkedHashMap<String, String>()
        val isSeriesSet = HashSet<String>()

        for (item in items) {
            val absUrl = safeResolveUrl(rootUri, item.href) ?: continue
            if (!absUrl.startsWith(source.rootUrl)) continue

            if (item.isDir) {
                val dirName = decodeLastSegment(absUrl)
                if (dirName.isBlank() || CrawlRules.isJunkFolder(dirName)) continue
                val rel = item.href.removePrefix(rootPath).trimEnd('/')
                if ('/' !in rel) continue // skip top-level category root folders themselves
                if (CrawlRules.isNonMediaCategory(rel.substringBefore('/'))) continue

                val (titleUrl, titleName, isSeason) = resolveTitleFolder(absUrl, source.rootUrl)
                if (titleUrl !in titleDirs) titleDirs[titleUrl] = titleName
                if (titleUrl !in categories) categories[titleUrl] = extractCategory(source.rootUrl, titleUrl)
                if (isSeason) isSeriesSet += titleUrl
            } else {
                val fileName = decodeLastSegment(absUrl)
                if (!MetaParser.isVideoFile(fileName)) continue
                val parentDir = absUrl.substringBeforeLast('/') + "/"
                val (titleUrl, titleName, isSeason) = resolveTitleFolder(parentDir, source.rootUrl)
                val seasonDir = if (isSeason) decodeLastSegment(parentDir) else null
                val folderSeason = seasonDir?.let { MetaParser.parseSeason(it) }

                if (titleUrl !in titleDirs) titleDirs[titleUrl] = titleName
                if (titleUrl !in categories) categories[titleUrl] = extractCategory(source.rootUrl, titleUrl)
                if (isSeason) isSeriesSet += titleUrl

                val file = toCatalogFile(
                    titleName = titleName,
                    entry = DirEntry(fileName, absUrl, isDir = false, sizeBytes = item.sizeBytes),
                    folderSeason = folderSeason,
                )
                groupedFiles.getOrPut(titleUrl) { mutableListOf() } += file
            }
        }

        val out = ArrayList<LiveTitle>(titleDirs.size)
        for ((folderUrl, rawTitleName) in titleDirs) {
            val files = groupedFiles[folderUrl].orEmpty()
            val category = categories[folderUrl].orEmpty().ifBlank { "General" }
            val isSeries = folderUrl in isSeriesSet || files.any { it.season != null || it.episode != null }
            val haystack = buildString {
                append(rawTitleName)
                files.firstOrNull()?.let { append(' ').append(it.fileName) }
            }
            out += LiveTitle(
                folderUrl = folderUrl,
                sourceId = source.id,
                sourceName = source.name,
                category = category,
                title = MetaParser.cleanTitle(rawTitleName),
                rawTitleName = rawTitleName,
                year = MetaParser.parseYear(haystack),
                kind = if (isSeries) "series" else "movie",
                posterUrl = null,
                language = MetaParser.parseLanguage(category),
                fileCount = files.size,
                seasonCount = if (isSeries) files.mapNotNull { it.season }.distinct().size else 0,
                maxSizeBytes = files.maxOfOrNull { it.sizeBytes } ?: -1L,
                bestQuality = files.mapNotNull { it.quality }.firstOrNull() ?: MetaParser.parseQuality(rawTitleName),
                bestSourceTag = files.mapNotNull { it.sourceTag }.firstOrNull() ?: MetaParser.parseSourceTag(rawTitleName),
                bestCodec = files.mapNotNull { it.codec }.firstOrNull() ?: MetaParser.parseCodec(rawTitleName),
                hasDualAudio = files.any { it.audio != null } || MetaParser.parseAudio(rawTitleName) != null,
                modified = null,
                filesLoaded = false,
                cachedFiles = files,
                posterResolved = false,
            )
        }
        return out
    }

    internal fun buildLiveTitleFromEntries(
        sourceId: Long,
        sourceName: String,
        category: String,
        folderUrl: String,
        rawTitleName: String,
        entries: List<DirEntry>,
        modified: String?,
    ): LiveTitle? {
        val videos = entries.filter { !it.isDir && MetaParser.isVideoFile(it.name) }
        val seasonDirs = entries.filter { it.isDir && CrawlRules.isSeasonLike(it.name) }
        if (videos.isEmpty() && seasonDirs.isEmpty()) return null

        val files = videos.map { toCatalogFile(rawTitleName, it, folderSeason = null) }
        val isSeries = seasonDirs.isNotEmpty() || files.any { it.season != null || it.episode != null }
        val haystack = buildString {
            append(rawTitleName)
            files.firstOrNull()?.let { append(' ').append(it.fileName) }
        }
        return LiveTitle(
            folderUrl = folderUrl,
            sourceId = sourceId,
            sourceName = sourceName,
            category = category.ifBlank { "General" },
            title = MetaParser.cleanTitle(rawTitleName),
            rawTitleName = rawTitleName,
            year = MetaParser.parseYear(haystack),
            kind = if (isSeries) "series" else "movie",
            posterUrl = PosterPicker.pickPoster(entries),
            language = MetaParser.parseLanguage(category),
            fileCount = files.size,
            seasonCount = seasonDirs.size,
            maxSizeBytes = files.maxOfOrNull { it.sizeBytes } ?: -1L,
            bestQuality = bestQuality(files.mapNotNull { it.quality }) ?: MetaParser.parseQuality(rawTitleName),
            bestSourceTag = files.mapNotNull { it.sourceTag }.firstOrNull() ?: MetaParser.parseSourceTag(rawTitleName),
            bestCodec = files.mapNotNull { it.codec }.firstOrNull() ?: MetaParser.parseCodec(rawTitleName),
            hasDualAudio = files.any { it.audio != null } || MetaParser.parseAudio(rawTitleName) != null,
            modified = modified,
            filesLoaded = seasonDirs.isEmpty(),
            cachedFiles = files,
            posterResolved = true,
        )
    }

    private fun buildSingleHitTitle(
        source: Source,
        category: String,
        folderUrl: String,
        rawTitleName: String,
        entry: DirEntry,
    ): LiveTitle {
        val file = toCatalogFile(rawTitleName, entry, folderSeason = null)
        val isSeries = file.season != null || file.episode != null
        val haystack = "$rawTitleName ${entry.name}"
        return LiveTitle(
            folderUrl = folderUrl,
            sourceId = source.id,
            sourceName = source.name,
            category = category,
            title = MetaParser.cleanTitle(rawTitleName),
            rawTitleName = rawTitleName,
            year = MetaParser.parseYear(haystack),
            kind = if (isSeries) "series" else "movie",
            posterUrl = null,
            language = MetaParser.parseLanguage(category),
            fileCount = 1,
            maxSizeBytes = entry.sizeBytes,
            bestQuality = file.quality ?: MetaParser.parseQuality(rawTitleName),
            bestSourceTag = file.sourceTag ?: MetaParser.parseSourceTag(rawTitleName),
            bestCodec = file.codec ?: MetaParser.parseCodec(rawTitleName),
            hasDualAudio = file.audio != null,
            modified = entry.modified,
            filesLoaded = false,
            cachedFiles = listOf(file),
            posterResolved = false,
        )
    }

    private fun toCatalogFile(titleName: String, entry: DirEntry, folderSeason: Int?): CatalogFile {
        val item = MetaParser.buildItem(
            sourceId = 0L,
            sourceName = "",
            category = "",
            titleFolderUrl = "",
            dirUrl = "",
            titleName = titleName,
            fileName = entry.name,
            fileUrl = entry.url,
            sizeBytes = entry.sizeBytes,
            modified = entry.modified,
            addedAt = 0L,
            isSeries = folderSeason != null,
            folderSeason = folderSeason,
        )
        return CatalogFile(
            id = entry.url.hashCode().toLong() and 0x7FFFFFFFL,
            titleId = 0L,
            fileName = entry.name,
            fileUrl = entry.url,
            sizeBytes = entry.sizeBytes,
            modified = entry.modified,
            quality = item.quality,
            sourceTag = item.sourceTag,
            codec = item.codec,
            audio = item.audio,
            season = item.season,
            episode = item.episode,
            addedAt = 0L,
        )
    }

    private fun resolveTitleFolder(dirUrl: String, rootUrl: String): Triple<String, String, Boolean> {
        val dirName = decodeLastSegment(dirUrl)
        if (CrawlRules.isSeasonLike(dirName)) {
            val parent = dirUrl.trimEnd('/').substringBeforeLast('/') + "/"
            if (parent.length > rootUrl.length) {
                return Triple(parent, decodeLastSegment(parent), true)
            }
        }
        return Triple(dirUrl, dirName, false)
    }

    private fun extractCategory(rootUrl: String, folderUrl: String): String {
        val rel = folderUrl.removePrefix(rootUrl).trim('/')
        val seg = rel.substringBefore('/')
        return decodeSegment(seg)
    }

    private fun decodeLastSegment(url: String): String =
        decodeSegment(url.trimEnd('/').substringAfterLast('/'))

    private fun decodeSegment(raw: String): String = try {
        URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        raw
    }

    private fun mergeSearchTitle(a: LiveTitle, b: LiveTitle): LiveTitle {
        val mergedFiles = (a.cachedFiles + b.cachedFiles).distinctBy { it.fileUrl }
        return a.copy(
            kind = if (a.kind == "series" || b.kind == "series") "series" else "movie",
            fileCount = maxOf(a.fileCount, mergedFiles.size),
            maxSizeBytes = maxOf(a.maxSizeBytes, b.maxSizeBytes),
            bestQuality = a.bestQuality ?: b.bestQuality,
            bestSourceTag = a.bestSourceTag ?: b.bestSourceTag,
            bestCodec = a.bestCodec ?: b.bestCodec,
            hasDualAudio = a.hasDualAudio || b.hasDualAudio,
            cachedFiles = mergedFiles,
        )
    }

    private fun rankSearchResults(items: List<LiveTitle>, query: String): List<LiveTitle> {
        val q = query.lowercase()
        // Audit B15: compile once per query, not once per comparison.
        val wordRegex = Regex("""\b${Regex.escape(q)}\b""")
        return items.sortedWith(
            compareBy<LiveTitle>(
                {
                    val t = it.title.lowercase()
                    when {
                        t == q -> 0
                        t.startsWith(q) -> 1
                        wordRegex.containsMatchIn(t) -> 2
                        t.contains(q) -> 3
                        else -> 4
                    }
                },
                { -(it.year ?: 0) },
                { it.title.lowercase() },
            )
        )
    }

    private fun sortCategories(byName: Map<String, List<CategoryLocation>>): List<LiveCategory> =
        byName.entries
            .map { (name, locs) -> LiveCategory(name = name, locations = locs.toList()) }
            .sortedWith(
                compareBy<LiveCategory>(
                    { CrawlRules.isNonMediaCategory(it.name) },
                    { categoryPriority(it.name) },
                    { it.name.lowercase() },
                )
            )

    private fun categoryPriority(name: String): Int {
        val n = name.lowercase()
        return when {
            "hindi" in n -> 0
            "english" in n -> 1
            "south indian" in n -> 2
            "bangla" in n -> 3
            "animation" in n -> 4
            "imdb" in n -> 5
            "series" in n || "anime" in n -> 6
            else -> 7
        }
    }

    private fun safeResolveUrl(rootUri: URI, rawHref: String): String? {
        return try {
            rootUri.resolve(rawHref).toString()
        } catch (_: Exception) {
            val encoded = rawHref.replace(" ", "%20").replace("[", "%5B").replace("]", "%5D")
            runCatching { rootUri.resolve(encoded).toString() }.getOrNull()
        }
    }

    private fun bestQuality(values: List<String>): String? {
        val order = listOf("2160p", "1080p", "720p", "480p", "360p", "CAM")
        return values.minByOrNull { q -> order.indexOf(q).let { if (it < 0) order.size else it } }
    }

    companion object {
        private val USER_AGENT: String = "LanFetch/" + com.lanfetch.app.BuildConfig.VERSION_NAME
        private const val UNREACHABLE_COOLDOWN_MS = 45_000L
        private const val LISTING_TTL_MS = 10 * 60_000L
        private const val SEARCH_TTL_MS = 5 * 60_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private class TtlLruCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val ttlMs: Long,
) {
    private data class Entry<V>(val value: V, val savedAt: Long)

    private val map = object : LinkedHashMap<K, Entry<V>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? {
        val e = map[key] ?: return null
        if (System.currentTimeMillis() - e.savedAt > ttlMs) {
            map.remove(key)
            return null
        }
        return e.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = Entry(value, System.currentTimeMillis())
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}
