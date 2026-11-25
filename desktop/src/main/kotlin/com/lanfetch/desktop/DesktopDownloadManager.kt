package com.lanfetch.desktop

import com.lanfetch.app.data.DownloadState
import com.lanfetch.app.download.DownloadEngine
import com.lanfetch.app.download.DownloadOutcome
import com.lanfetch.app.download.DownloadUi
import com.lanfetch.app.download.FileDownloadSink
import com.lanfetch.app.download.Segment
import com.lanfetch.app.download.SegmentPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Desktop download manager: the shared [DownloadEngine] driven without any
 * Android service machinery. State persists to a small TSV file so segment
 * maps survive restarts and downloads resume byte-exactly, like the phone app.
 */
class DesktopDownloadManager(
    transferClient: OkHttpClient,
    private val downloadDir: File,
    private val stateFile: File,
    private val maxConcurrent: Int = 3,
    private val segmentsPerDownload: Int = 8,
) {
    private data class Record(
        val id: Long,
        val url: String,
        val fileName: String,
        var totalBytes: Long = -1,
        var state: DownloadState = DownloadState.QUEUED,
        var segments: List<Segment> = emptyList(),
        var etag: String? = null,
        var lastModified: String? = null,
        var error: String? = null,
        var savedPath: String? = null,
        var speedBps: Double = 0.0,
    ) {
        val downloaded: Long get() = SegmentPlanner.totalDownloaded(segments)
        fun toUi(): DownloadUi = DownloadUi(
            id = id,
            title = fileName,
            fileName = fileName,
            url = url,
            totalBytes = totalBytes,
            downloadedBytes = downloaded,
            state = state,
            speedBps = if (state == DownloadState.RUNNING) speedBps else 0.0,
            segments = segments,
            error = error,
            savedPath = savedPath,
        )
    }

    private val engine = DownloadEngine(transferClient)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val records = LinkedHashMap<Long, Record>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val nextId = AtomicLong(1)

    private val _downloads = MutableStateFlow<List<DownloadUi>>(emptyList())
    val downloads: StateFlow<List<DownloadUi>> = _downloads.asStateFlow()

    init {
        load()
        scope.launch { emitAndSave() }
    }

    fun enqueue(url: String, fileName: String) {
        scope.launch {
            val id = mutex.withLock {
                val existing = records.values.firstOrNull { it.url == url }
                if (existing != null && existing.state != DownloadState.COMPLETED) {
                    existing.state = DownloadState.QUEUED
                    existing.error = null
                    existing.id
                } else {
                    val rec = Record(id = nextId.getAndIncrement(), url = url, fileName = fileName)
                    records[rec.id] = rec
                    rec.id
                }
            }
            emitAndSave()
            pump()
            jobs[id]?.join()
        }
    }

    fun pause(id: Long) {
        jobs[id]?.cancel()
        scope.launch {
            mutex.withLock {
                records[id]?.let { rec ->
                    if (rec.state == DownloadState.RUNNING || rec.state == DownloadState.QUEUED) {
                        rec.state = DownloadState.PAUSED
                        rec.speedBps = 0.0
                    }
                }
            }
            emitAndSave()
        }
    }

    fun resume(id: Long) {
        scope.launch {
            mutex.withLock {
                records[id]?.let { rec ->
                    if (rec.state == DownloadState.PAUSED || rec.state == DownloadState.FAILED) {
                        rec.state = DownloadState.QUEUED
                        rec.error = null
                    }
                }
            }
            emitAndSave()
            pump()
        }
    }

    fun remove(id: Long) {
        jobs[id]?.cancel()
        scope.launch {
            val rec = mutex.withLock { records.remove(id) }
            if (rec != null) {
                runCatching {
                    FileDownloadSink(downloadDir, rec.fileName).delete()
                }
            }
            emitAndSave()
            pump()
        }
    }

    private suspend fun pump() {
        while (true) {
            val next = mutex.withLock {
                val running = records.values.count { it.state == DownloadState.RUNNING }
                if (running >= maxConcurrent) null
                else records.values.firstOrNull { it.state == DownloadState.QUEUED }?.also {
                    it.state = DownloadState.RUNNING
                }
            } ?: return
            emitAndSave()
            jobs[next.id] = scope.launch { runOne(next.id) }
        }
    }

    private suspend fun runOne(id: Long) {
        val rec = mutex.withLock { records[id] } ?: return
        val sink = FileDownloadSink(downloadDir, rec.fileName)
        try {
            val preserved = sink.open()
            if (!preserved || rec.totalBytes <= 0 || rec.segments.isEmpty()) {
                val probe = engine.probe(rec.url)
                mutex.withLock {
                    rec.totalBytes = probe.totalBytes
                    rec.etag = probe.etag
                    rec.lastModified = probe.lastModified
                    rec.segments = if (probe.totalBytes > 0) {
                        SegmentPlanner.plan(probe.totalBytes, segmentsPerDownload)
                    } else {
                        listOf(Segment(0, 0, Segment.OPEN_END, 0))
                    }
                }
            }
            val plan = mutex.withLock { rec.segments }
            val total = mutex.withLock { rec.totalBytes }
            if (total > 0) sink.preallocate(total)
            val validator = mutex.withLock {
                rec.etag?.takeUnless { it.startsWith("W/") } ?: rec.lastModified
            }
            var lastSave = 0L
            val outcome = engine.run(rec.url, sink, plan, total, validator) { snapshot ->
                mutex.withLock {
                    rec.segments = snapshot.segments
                    rec.speedBps = snapshot.speedBps
                }
                val now = System.currentTimeMillis()
                if (now - lastSave >= 2_000) {
                    lastSave = now
                    emitAndSave()
                } else {
                    emit()
                }
            }
            when (outcome) {
                is DownloadOutcome.Completed -> {
                    val path = sink.finish()
                    mutex.withLock {
                        rec.state = DownloadState.COMPLETED
                        rec.savedPath = path
                        rec.speedBps = 0.0
                        rec.error = null
                    }
                }
                is DownloadOutcome.Stale -> {
                    mutex.withLock {
                        rec.state = DownloadState.FAILED
                        rec.error = "The file changed on the server — download it again."
                        rec.segments = emptyList()
                    }
                    sink.delete()
                }
                is DownloadOutcome.Failed -> {
                    mutex.withLock {
                        if (rec.state == DownloadState.RUNNING) {
                            rec.state = DownloadState.FAILED
                            rec.error = outcome.message
                        }
                        rec.speedBps = 0.0
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            mutex.withLock {
                if (rec.state == DownloadState.RUNNING) rec.state = DownloadState.PAUSED
                rec.speedBps = 0.0
            }
            sink.close()
        } catch (e: Exception) {
            mutex.withLock {
                if (rec.state == DownloadState.RUNNING) {
                    rec.state = DownloadState.FAILED
                    rec.error = e.message ?: "Download failed"
                }
                rec.speedBps = 0.0
            }
            sink.close()
        } finally {
            jobs.remove(id)
            emitAndSave()
            pump()
        }
    }

    private fun emit() {
        _downloads.value = records.values.sortedBy { it.id }.map { it.toUi() }
    }

    private suspend fun emitAndSave() {
        mutex.withLock {
            emit()
            saveLocked()
        }
    }

    // ---- persistence: TSV, URL-encoded fields; segment plan via SegmentPlanner.encode

    private fun saveLocked() {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val sb = StringBuilder()
            for (rec in records.values) {
                val fields = listOf(
                    rec.id.toString(),
                    rec.state.name,
                    enc(rec.url),
                    enc(rec.fileName),
                    rec.totalBytes.toString(),
                    enc(if (rec.segments.isEmpty()) "" else SegmentPlanner.encode(rec.segments)),
                    enc(rec.etag ?: ""),
                    enc(rec.lastModified ?: ""),
                    enc(rec.error ?: ""),
                    enc(rec.savedPath ?: ""),
                )
                sb.append(fields.joinToString("\t")).append('\n')
            }
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(sb.toString())
            tmp.renameTo(stateFile)
        }
    }

    private fun load() {
        runCatching {
            if (!stateFile.exists()) return
            for (line in stateFile.readLines()) {
                if (line.isBlank()) continue
                val f = line.split('\t')
                if (f.size < 10) continue
                val state = runCatching { DownloadState.valueOf(f[1]) }.getOrDefault(DownloadState.PAUSED)
                val rec = Record(
                    id = f[0].toLong(),
                    url = dec(f[2]),
                    fileName = dec(f[3]),
                    totalBytes = f[4].toLongOrNull() ?: -1,
                    state = when (state) {
                        DownloadState.RUNNING, DownloadState.QUEUED -> DownloadState.PAUSED
                        else -> state
                    },
                    segments = dec(f[5]).takeIf { it.isNotEmpty() }
                        ?.let { runCatching { SegmentPlanner.decode(it) }.getOrDefault(emptyList()) }
                        ?: emptyList(),
                    etag = dec(f[6]).ifEmpty { null },
                    lastModified = dec(f[7]).ifEmpty { null },
                    error = dec(f[8]).ifEmpty { null },
                    savedPath = dec(f[9]).ifEmpty { null },
                )
                records[rec.id] = rec
                nextId.updateAndGet { maxOf(it, rec.id + 1) }
            }
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)
    private fun dec(s: String): String = URLDecoder.decode(s, Charsets.UTF_8)
}
