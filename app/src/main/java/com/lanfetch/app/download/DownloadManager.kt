package com.lanfetch.app.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.lanfetch.app.data.AppDb
import com.lanfetch.app.data.AppSettings
import com.lanfetch.app.data.CatalogItem
import com.lanfetch.app.data.DownloadEntity
import com.lanfetch.app.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

enum class DlState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

data class DownloadUi(
    val id: Long,
    val title: String,
    val fileName: String,
    val url: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val state: DlState,
    val speedBps: Double = 0.0,
    val segments: List<Segment> = emptyList(),
    val error: String? = null,
    val savedPath: String? = null,
    val sha256: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (downloadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
    val etaSeconds: Long
        get() = if (speedBps > 1 && totalBytes > downloadedBytes) ((totalBytes - downloadedBytes) / speedBps).toLong() else -1
}

private data class LiveProgress(val downloaded: Long, val speedBps: Double, val segments: List<Segment>)

/**
 * Queue + lifecycle owner for downloads. Persists every download in Room
 * (with its segment map) so pause / process-death / reboot lose nothing:
 * resuming re-reads the segment states and continues byte-exact.
 */
class DownloadManager(
    private val context: Context,
    private val db: AppDb,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
    private val engine: DownloadEngine = DownloadEngine(),
) {
    private val jobs = HashMap<Long, Job>()
    private val live = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    private val pumpMutex = Mutex()
    @Volatile private var settings = AppSettings()

    init {
        scope.launch { settingsStore.flow.collect { settings = it } }
        scope.launch {
            db.downloads().pauseAllRunning() // anything RUNNING at startup was interrupted
        }
    }

    fun observeUi(): Flow<List<DownloadUi>> =
        combine(db.downloads().observeAll(), live) { records, liveMap ->
            records.map { rec ->
                val lp = liveMap[rec.id]
                DownloadUi(
                    id = rec.id,
                    title = rec.title,
                    fileName = rec.fileName,
                    url = rec.url,
                    totalBytes = rec.totalBytes,
                    downloadedBytes = lp?.downloaded ?: rec.downloadedBytes,
                    state = runCatching { DlState.valueOf(rec.state) }.getOrDefault(DlState.PAUSED),
                    speedBps = if (rec.state == "RUNNING") lp?.speedBps ?: 0.0 else 0.0,
                    segments = lp?.segments ?: SegmentPlanner.decode(rec.segments),
                    error = rec.error,
                    savedPath = rec.savedPath,
                    sha256 = rec.sha256,
                )
            }
        }

    private fun downloadDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "inprogress").apply { mkdirs() }

    private fun partFile(id: Long, fileName: String): File =
        File(downloadDir(), "${id}_${sanitize(fileName)}.part")

    suspend fun enqueue(item: CatalogItem): Long {
        val existing = db.downloads().resumable().firstOrNull { it.url == item.fileUrl }
        if (existing != null) { pump(); return existing.id }
        val id = db.downloads().insert(
            DownloadEntity(
                url = item.fileUrl,
                fileName = item.fileName,
                title = item.title,
                totalBytes = item.sizeBytes,
                state = "QUEUED",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        )
        pump()
        return id
    }

    fun pause(id: Long) {
        jobs[id]?.cancel()
    }

    fun pauseAll() {
        jobs.values.forEach { it.cancel() }
    }

    fun resume(id: Long) {
        scope.launch {
            db.downloads().get(id)?.let {
                db.downloads().update(it.copy(state = "QUEUED", error = null, updatedAt = System.currentTimeMillis()))
            }
            pump()
        }
    }

    fun retry(id: Long) = resume(id)

    fun cancelAndDelete(id: Long) {
        jobs[id]?.cancel()
        scope.launch {
            val rec = db.downloads().get(id)
            if (rec != null) partFile(id, rec.fileName).delete()
            db.downloads().delete(id)
            pump()
        }
    }

    fun removeRecord(id: Long) {
        scope.launch { db.downloads().delete(id) }
    }

    private fun pump() {
        scope.launch {
            pumpMutex.withLock {
                val max = settings.maxConcurrent
                val running = jobs.values.count { it.isActive }
                if (running >= max) return@withLock
                val queued = db.downloads().resumable()
                    .filter { it.state == "QUEUED" }
                    .sortedBy { it.createdAt }
                for (rec in queued) {
                    if (jobs.values.count { it.isActive } >= max) break
                    if (jobs[rec.id]?.isActive == true) continue
                    startLocked(rec)
                }
            }
        }
    }

    private fun startLocked(rec: DownloadEntity) {
        DownloadService.start(context)
        val job = scope.launch {
            runDownload(rec.id)
        }
        jobs[rec.id] = job
        job.invokeOnCompletion { scope.launch { pump() } }
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private suspend fun runDownload(id: Long) {
        var rec = db.downloads().get(id) ?: return
        if (settings.wifiOnly && !isOnWifi()) {
            db.downloads().update(
                rec.copy(state = "PAUSED", error = "Waiting for Wi-Fi (Wi-Fi only is on in Settings)", updatedAt = System.currentTimeMillis())
            )
            return
        }
        rec = rec.copy(state = "RUNNING", error = null, updatedAt = System.currentTimeMillis())
        db.downloads().update(rec)
        val part = partFile(id, rec.fileName)
        var segments: MutableList<Segment> = SegmentPlanner.decode(rec.segments).toMutableList()
        var total = rec.totalBytes
        try {
            if (segments.isEmpty() || total <= 0) {
                val probe = engine.probe(rec.url)
                if (probe.totalBytes > 0) total = probe.totalBytes
                segments = if (probe.acceptRanges && total > 0) {
                    SegmentPlanner.plan(total, settings.segmentsPerDownload).toMutableList()
                } else if (total > 0) {
                    mutableListOf(Segment(0, 0, total - 1, if (part.exists()) part.length() else 0))
                } else {
                    mutableListOf()
                }
                rec = rec.copy(totalBytes = total, segments = SegmentPlanner.encode(segments))
                db.downloads().update(rec)
            }
            // Preallocate only for segmented downloads: parallel writers seek
            // to their offsets, and segment counters (not file length) track
            // what has actually arrived.
            if (segments.size > 1 && total > 0 && (!part.exists() || part.length() < total)) {
                RandomAccessFile(part, "rw").use { it.setLength(total) }
            }

            var lastSave = 0L
            var lastBytes = SegmentPlanner.totalDownloaded(segments)
            var lastTime = System.currentTimeMillis()
            var speed = 0.0

            val outcome = engine.run(rec.url, part, segments, total) { downloaded ->
                val now = System.currentTimeMillis()
                if (now - lastTime >= 500) {
                    val inst = (downloaded - lastBytes) * 1000.0 / maxOf(1, now - lastTime)
                    speed = if (speed == 0.0) inst else speed * 0.7 + inst * 0.3
                    lastBytes = downloaded; lastTime = now
                }
                live.value = live.value + (id to LiveProgress(downloaded, speed, segments.map { it.copy() }))
                if (now - lastSave >= 1000) {
                    lastSave = now
                    scope.launch {
                        db.downloads().get(id)?.let { cur ->
                            db.downloads().update(
                                cur.copy(
                                    downloadedBytes = downloaded,
                                    segments = SegmentPlanner.encode(segments),
                                    totalBytes = total,
                                    updatedAt = System.currentTimeMillis(),
                                )
                            )
                        }
                    }
                    DownloadService.update(context, rec.fileName, downloaded, total, speed)
                }
            }

            when (outcome) {
                is DownloadOutcome.Completed -> {
                    val completedBytes = if (total > 0) total else part.length()
                    val sha = sha256(part)
                    val saved = publishToDownloads(part, rec.fileName)
                    part.delete()
                    db.downloads().update(
                        rec.copy(
                            state = "COMPLETED",
                            downloadedBytes = completedBytes,
                            segments = SegmentPlanner.encode(segments),
                            savedPath = saved,
                            sha256 = sha,
                            error = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
                is DownloadOutcome.Failed -> {
                    db.downloads().update(
                        rec.copy(
                            state = "FAILED",
                            error = outcome.message,
                            downloadedBytes = SegmentPlanner.totalDownloaded(segments),
                            segments = SegmentPlanner.encode(segments),
                            totalBytes = total,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            db.downloads().get(id)?.let { cur ->
                db.downloads().update(
                    cur.copy(
                        state = "PAUSED",
                        downloadedBytes = SegmentPlanner.totalDownloaded(segments),
                        segments = SegmentPlanner.encode(segments),
                        totalBytes = total,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        } catch (e: Exception) {
            db.downloads().get(id)?.let { cur ->
                db.downloads().update(
                    cur.copy(
                        state = "FAILED",
                        error = e.message ?: "Download failed",
                        segments = SegmentPlanner.encode(segments),
                        totalBytes = total,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            }
        } finally {
            live.value = live.value - id
        }
    }

    /** Copy a finished file into the public Downloads/LanFetch folder. Returns a display path. */
    private fun publishToDownloads(file: File, fileName: String): String {
        val mime = mimeFor(fileName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanFetch")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return file.absolutePath
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Downloads/LanFetch/$fileName"
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LanFetch")
        dir.mkdirs()
        val dest = File(dir, fileName)
        file.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun sanitize(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)

        fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "m4v" -> "video/x-m4v"
            "wmv" -> "video/x-ms-wmv"
            else -> "video/*"
        }
    }
}
