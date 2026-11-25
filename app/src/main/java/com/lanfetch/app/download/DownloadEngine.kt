package com.lanfetch.app.download

import com.lanfetch.app.net.withCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.coroutines.coroutineContext

private data class ContentRange(val start: Long, val end: Long, val total: Long)

private class StaleFileException(message: String = "Server file changed") : IOException(message)

/**
 * Segmented HTTP download engine.
 *
 * - OkHttp calls are async and cancelled with the coroutine, so Pause is
 *   immediate even mid-read.
 * - Segment counters are atomic; progress is coalesced by one reporter.
 * - Retry counters reset after real progress, and every resume validates
 *   Content-Range / If-Range / total size.
 * - The caller owns the [DownloadSink], so the same engine writes to a plain
 *   file or straight into a pending MediaStore entry.
 */
class DownloadEngine(
    private val client: OkHttpClient,
) {
    companion object {
        const val MAX_RETRIES = 6
        const val BUFFER = 256 * 1024
        const val USER_AGENT = "LanFetch/1.3"
    }

    suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        val rangeRequest = Request.Builder().url(url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", USER_AGENT)
            .build()

        try {
            return@withContext client.withCall(rangeRequest) { res ->
                val etag = res.header("ETag")
                val lastModified = res.header("Last-Modified")
                if (res.code == 206) {
                    val range = parseContentRange(res.header("Content-Range"))
                    return@withCall ProbeResult(
                        totalBytes = range?.total ?: -1,
                        acceptRanges = true,
                        etag = etag,
                        lastModified = lastModified,
                    )
                }
                if (res.code == 416) {
                    val total = parseContentRange(res.header("Content-Range"))?.total ?: 0
                    return@withCall ProbeResult(total, true, etag, lastModified)
                }
                if (res.isSuccessful) {
                    val length = res.header("Content-Length")?.toLongOrNull() ?: -1
                    return@withCall ProbeResult(length, false, etag, lastModified)
                }
                throw IllegalStateException("HTTP ${res.code}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Some servers reject Range on GET but answer HEAD; try once.
            val head = Request.Builder().url(url).head()
                .header("User-Agent", USER_AGENT).build()
            client.withCall(head) { res ->
                val etag = res.header("ETag")
                val lastModified = res.header("Last-Modified")
                val length = res.header("Content-Length")?.toLongOrNull() ?: -1
                val ranges = res.header("Accept-Ranges")?.contains("bytes", true) == true
                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                ProbeResult(length, ranges, etag, lastModified)
            }
        }
    }

    /**
     * Downloads [plan] into [sink]. Cancellation is a pause: the final counter
     * snapshot is reported before the CancellationException propagates.
     */
    suspend fun run(
        url: String,
        sink: DownloadSink,
        plan: List<Segment>,
        totalBytes: Long,
        validator: String?,
        onProgress: suspend (ProgressSnapshot) -> Unit,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        if (plan.isEmpty()) return@withContext DownloadOutcome.Failed("No segment plan")
        val counters = AtomicLongArray(plan.size).apply {
            plan.forEachIndexed { i, segment -> set(i, segment.downloaded) }
        }
        val total = AtomicLong(SegmentPlanner.totalDownloaded(plan))
        val knownTotal = totalBytes

        coroutineScope {
            var lastBytes = total.get()
            var lastTime = System.currentTimeMillis()
            var speed = 0.0
            var lastSync = 0L

            suspend fun report(force: Boolean = false) {
                val now = System.currentTimeMillis()
                val downloaded = total.get()
                val elapsed = now - lastTime
                if (force || elapsed >= 500) {
                    val instant = if (elapsed > 0) (downloaded - lastBytes) * 1000.0 / elapsed else 0.0
                    speed = if (speed <= 0.0) instant else speed * 0.7 + instant * 0.3
                    lastBytes = downloaded
                    lastTime = now
                }
                val snapshot = ProgressSnapshot(
                    downloaded = downloaded,
                    speedBps = speed.coerceAtLeast(0.0),
                    segments = List(plan.size) { i -> plan[i].withDownloaded(counters.get(i)) },
                )
                try {
                    onProgress(snapshot)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // UI / DB progress must never abort a download.
                }
                if (now - lastSync >= 5_000) {
                    lastSync = now
                    runCatching { sink.sync() }
                }
            }

            val reporter = launch {
                while (isActive) {
                    delay(500)
                    report()
                }
            }

            try {
                val outcome = if (plan.size <= 1 || plan.any { it.openEnded }) {
                    runSingleStream(url, sink, plan[0], counters, total, knownTotal, validator)
                } else {
                    runSegmented(url, sink, plan, counters, total, knownTotal, validator)
                }
                reporter.cancelAndJoin()
                report(force = true)
                sink.sync()
                outcome
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    reporter.cancelAndJoin()
                    report(force = true)
                    runCatching { sink.sync() }
                }
                throw e
            } catch (e: StaleFileException) {
                DownloadOutcome.Stale
            } catch (e: Exception) {
                DownloadOutcome.Failed(e.message ?: "Download failed")
            } finally {
                reporter.cancel()
            }
        }
    }

    private suspend fun runSegmented(
        url: String,
        sink: DownloadSink,
        plan: List<Segment>,
        counters: AtomicLongArray,
        total: AtomicLong,
        totalBytes: Long,
        validator: String?,
    ): DownloadOutcome {
        coroutineScope {
            plan.mapIndexed { index, segment ->
                async {
                    if (counters.get(index) < segment.length) {
                        downloadSegment(url, sink, segment, counters, total, totalBytes, validator, plan.size)
                    }
                }
            }.awaitAll()
        }
        val downloaded = total.get()
        return if (totalBytes > 0 && downloaded >= totalBytes) {
            DownloadOutcome.Completed
        } else {
            DownloadOutcome.Failed("Incomplete: $downloaded / $totalBytes bytes")
        }
    }

    private suspend fun downloadSegment(
        url: String,
        sink: DownloadSink,
        segment: Segment,
        counters: AtomicLongArray,
        total: AtomicLong,
        totalBytes: Long,
        validator: String?,
        segmentCount: Int,
    ) {
        val index = segment.index
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            val current = counters.get(index)
            if (current >= segment.length) return
            val from = segment.start + current
            var bytesThisAttempt = 0L

            try {
                val rangeHeader = if (segment.openEnded) "bytes=$from-" else "bytes=$from-${segment.end}"
                val builder = Request.Builder().url(url)
                    .header("Range", rangeHeader)
                    .header("User-Agent", USER_AGENT)
                validator?.let { builder.header("If-Range", it) }
                client.withCall(builder.build()) { res ->
                    when {
                        res.code == 200 -> throw StaleFileException("Server ignored Range")
                        res.code == 206 -> {
                            val range = parseContentRange(res.header("Content-Range"))
                                ?: throw IllegalStateException("Missing Content-Range")
                            if (range.start != from) throw StaleFileException("Server returned a different range")
                            if (range.total > 0 && totalBytes > 0 && range.total != totalBytes) {
                                throw StaleFileException("Server file size changed")
                            }
                        }
                        res.code == 416 -> throw StaleFileException("Requested range no longer exists")
                        else -> throw IllegalStateException("HTTP ${res.code}")
                    }

                    val body = res.body ?: throw IllegalStateException("Empty body")
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            val done = counters.get(index)
                            val writable = minOf(read.toLong(), segment.length - done).toInt()
                            if (writable <= 0) break
                            sink.write(segment.start + done, buffer, 0, writable)
                            counters.addAndGet(index, writable.toLong())
                            total.addAndGet(writable.toLong())
                            bytesThisAttempt += writable
                            if (bytesThisAttempt > 0) attempt = 0
                        }
                    }
                }

                if (counters.get(index) >= segment.length) return
                attempt++
                if (attempt > MAX_RETRIES) {
                    throw IOException("Segment ${segment.index} stalled at ${counters.get(index)}/${segment.length}")
                }
                delay(backoff(attempt))
            } catch (e: StaleFileException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                attempt++
                if (attempt > MAX_RETRIES) throw e
                delay(backoff(attempt))
            }
        }
    }

    private suspend fun runSingleStream(
        url: String,
        sink: DownloadSink,
        segment: Segment,
        counters: AtomicLongArray,
        total: AtomicLong,
        totalBytes: Long,
        validator: String?,
    ): DownloadOutcome {
        var existing = counters.get(0)
        if (totalBytes > 0 && existing > totalBytes) {
            sink.truncate(0)
            existing = 0
            counters.set(0, 0)
            total.set(0)
        }
        var attempt = 0

        while (totalBytes <= 0 || existing < totalBytes) {
            coroutineContext.ensureActive()
            var bytesThisAttempt = 0L
            try {
                val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
                if (existing > 0) {
                    builder.header("Range", "bytes=$existing-")
                    validator?.let { builder.header("If-Range", it) }
                }
                client.withCall(builder.build()) { res ->
                    if (res.code == 200 && existing > 0) {
                        // Server cannot resume: restart cleanly.
                        sink.truncate(0)
                        existing = 0
                        counters.set(0, 0)
                        total.set(0)
                    } else if (res.code == 206) {
                        val range = parseContentRange(res.header("Content-Range"))
                            ?: throw IllegalStateException("Missing Content-Range")
                        if (range.start != existing) throw StaleFileException("Server returned a different range")
                        if (range.total > 0 && totalBytes > 0 && range.total != totalBytes) {
                            throw StaleFileException("Server file size changed")
                        }
                    } else if (res.code == 416) {
                        throw StaleFileException("Requested range no longer exists")
                    } else if (!res.isSuccessful) {
                        throw IllegalStateException("HTTP ${res.code}")
                    }

                    val body = res.body ?: throw IllegalStateException("Empty body")
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
            val writable = if (totalBytes > 0) {
                                minOf(read.toLong(), totalBytes - existing).toInt()
                            } else {
                                read
                            }
                            if (writable <= 0) break
                            sink.write(existing, buffer, 0, writable)
                            existing += writable
                            counters.set(0, existing)
                            total.set(existing)
                            bytesThisAttempt += writable
                            if (bytesThisAttempt > 0) attempt = 0
                        }
                    }
                }

                if (totalBytes > 0 && existing >= totalBytes) break
                if (totalBytes <= 0) break
                attempt++
                if (attempt > MAX_RETRIES) {
                    return DownloadOutcome.Failed("Stream stalled at $existing / $totalBytes")
                }
                delay(backoff(attempt))
            } catch (e: StaleFileException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                attempt++
                if (attempt > MAX_RETRIES) return DownloadOutcome.Failed(e.message ?: "Download failed")
                delay(backoff(attempt))
            }
        }
        return DownloadOutcome.Completed
    }

    private fun parseContentRange(value: String?): ContentRange? {
        if (value == null) return null
        val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
            .find(value) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: -1
        return ContentRange(start, end, total)
    }

    private fun backoff(attempt: Int): Long =
        minOf(30_000L, 1_000L shl minOf(attempt, 5))
}
