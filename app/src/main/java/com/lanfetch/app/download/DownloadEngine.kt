package com.lanfetch.app.download

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

data class ProbeResult(val totalBytes: Long, val acceptRanges: Boolean)

sealed interface DownloadOutcome {
    data object Completed : DownloadOutcome
    data class Failed(val message: String) : DownloadOutcome
}

/**
 * Segmented HTTP download engine — the heart of LanFetch.
 *
 * - Probes the server for Range support and total size.
 * - Splits the file into segments downloaded in parallel (OkHttp),
 *   each written at its own offset with RandomAccessFile.
 * - Every segment retries with exponential backoff; a segment that the
 *   server answers with 200 instead of 206 restarts cleanly.
 * - Progress is reported from the persisted [Segment] states, so pausing
 *   (cancelling the coroutine) and resuming loses nothing.
 * - Servers without Range support fall back to a single resumable stream.
 */
class DownloadEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    companion object {
        const val MAX_RETRIES = 6
        const val BUFFER = 256 * 1024
    }

    fun probe(url: String): ProbeResult {
        // HEAD first
        try {
            val head = Request.Builder().url(url).head()
                .header("User-Agent", "LanFetch/1.0").build()
            client.newCall(head).execute().use { res ->
                if (res.isSuccessful) {
                    val len = res.header("Content-Length")?.toLongOrNull() ?: -1
                    val ranges = res.header("Accept-Ranges")?.contains("bytes", true) == true
                    if (len > 0) return ProbeResult(len, ranges)
                }
            }
        } catch (e: Exception) { /* fall through to range probe */ }
        // Range probe: bytes=0-0 -> Content-Range: bytes 0-0/TOTAL
        val req = Request.Builder().url(url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", "LanFetch/1.0").build()
        client.newCall(req).execute().use { res ->
            val cr = res.header("Content-Range")
            val total = cr?.substringAfterLast('/')?.toLongOrNull() ?: -1
            return ProbeResult(total, res.code == 206)
        }
    }

    /**
     * Download [url] into [destFile] following [segments] (mutated in place
     * as bytes land). Suspends until done; cancellation = pause.
     */
    suspend fun run(
        url: String,
        destFile: File,
        segments: MutableList<Segment>,
        totalBytes: Long,
        onProgress: (downloaded: Long) -> Unit,
    ): DownloadOutcome {
        val downloadedCounter = AtomicLong(SegmentPlanner.totalDownloaded(segments))
        onProgress(downloadedCounter.get())

        if (segments.size <= 1) {
            return runSingleStream(url, destFile, segments.firstOrNull(), totalBytes, downloadedCounter, onProgress)
        }

        return try {
            coroutineScope {
                val gate = Semaphore(segments.size)
                val jobs = segments.filter { !it.done }.map { seg ->
                    async {
                        gate.withPermit { downloadSegment(url, destFile, seg, downloadedCounter, onProgress) }
                    }
                }
                jobs.awaitAll()
            }
            val dl = SegmentPlanner.totalDownloaded(segments)
            if (totalBytes > 0 && dl >= totalBytes) DownloadOutcome.Completed
            else DownloadOutcome.Failed("Incomplete: $dl / $totalBytes bytes")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            DownloadOutcome.Failed(e.message ?: "Download failed")
        }
    }

    private suspend fun downloadSegment(
        url: String,
        destFile: File,
        seg: Segment,
        counter: AtomicLong,
        onProgress: (Long) -> Unit,
    ) {
        var attempt = 0
        while (!seg.done) {
            coroutineContext.ensureActive()
            try {
                val from = seg.start + seg.downloaded
                val req = Request.Builder().url(url)
                    .header("Range", "bytes=$from-${seg.end}")
                    .header("User-Agent", "LanFetch/1.0")
                    .build()
                client.newCall(req).execute().use { res ->
                    if (res.code == 200) {
                        // Server ignored Range. Only safe if this segment starts at 0
                        // and nothing of it was written yet; otherwise treat as retryable.
                        if (seg.start != 0L || seg.downloaded != 0L) {
                            throw IllegalStateException("Server ignored Range header")
                        }
                    } else if (res.code != 206) {
                        throw IllegalStateException("HTTP ${res.code}")
                    }
                    val body = res.body ?: throw IllegalStateException("Empty body")
                    RandomAccessFile(destFile, "rw").use { raf ->
                        raf.seek(from)
                        val buf = ByteArray(BUFFER)
                        body.byteStream().use { input ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                val writable = minOf(n.toLong(), seg.remaining).toInt()
                                if (writable <= 0) break
                                raf.write(buf, 0, writable)
                                seg.downloaded += writable
                                onProgress(counter.addAndGet(writable.toLong()))
                            }
                        }
                    }
                }
                if (!seg.done && seg.remaining > 0) {
                    // stream ended early — retry resumes from seg.downloaded
                    attempt++
                    if (attempt > MAX_RETRIES) throw IllegalStateException("Segment ${seg.index} stalled")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                attempt++
                if (attempt > MAX_RETRIES) throw e
                delay(minOf(30_000L, 1000L * (1L shl minOf(attempt, 5))))
            }
        }
    }

    private suspend fun runSingleStream(
        url: String,
        destFile: File,
        seg: Segment?,
        totalBytes: Long,
        counter: AtomicLong,
        onProgress: (Long) -> Unit,
    ): DownloadOutcome {
        var attempt = 0
        // The segment's persisted counter is authoritative (the file may have
        // been preallocated); fall back to the file length only without one.
        var existing = seg?.downloaded ?: if (destFile.exists()) destFile.length() else 0L
        if (totalBytes > 0 && existing > totalBytes) { destFile.delete(); existing = 0; seg?.downloaded = 0 }
        while (totalBytes <= 0 || existing < totalBytes) {
            coroutineContext.ensureActive()
            try {
                val builder = Request.Builder().url(url).header("User-Agent", "LanFetch/1.0")
                if (existing > 0) builder.header("Range", "bytes=$existing-")
                client.newCall(builder.build()).execute().use { res ->
                    if (res.code == 200 && existing > 0) {
                        // no resume support: restart from scratch
                        existing = 0; seg?.downloaded = 0; counter.set(0)
                    } else if (!res.isSuccessful && res.code != 206) {
                        throw IllegalStateException("HTTP ${res.code}")
                    }
                    val body = res.body ?: throw IllegalStateException("Empty body")
                    RandomAccessFile(destFile, "rw").use { raf ->
                        raf.seek(existing)
                        val buf = ByteArray(BUFFER)
                        body.byteStream().use { input ->
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                existing += n
                                seg?.downloaded = existing
                                onProgress(counter.addAndGet(n.toLong()))
                            }
                        }
                    }
                }
                if (totalBytes > 0 && existing >= totalBytes) break
                if (totalBytes <= 0) break
                attempt++
                if (attempt > MAX_RETRIES) return DownloadOutcome.Failed("Stream stalled at $existing / $totalBytes")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                attempt++
                if (attempt > MAX_RETRIES) return DownloadOutcome.Failed(e.message ?: "Download failed")
                delay(minOf(30_000L, 1000L * (1L shl minOf(attempt, 5))))
            }
        }
        return DownloadOutcome.Completed
    }
}
