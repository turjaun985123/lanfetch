package com.lanfetch.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import androidx.room.withTransaction
import com.lanfetch.app.data.AppDb
import com.lanfetch.app.data.AppSettings
import com.lanfetch.app.data.CatalogFile
import com.lanfetch.app.data.CatalogRepository
import com.lanfetch.app.data.DownloadEntity
import com.lanfetch.app.data.DownloadState
import com.lanfetch.app.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private data class LiveProgress(
    val downloaded: Long,
    val speedBps: Double,
    val segments: List<Segment>,
    val totalBytes: Long,
)

/**
 * Single owner of the download queue and its state machine. Persists segment
 * maps, coalesces progress into one DB write per 2 s, and resumes queued work
 * on app start / network return.
 */
class DownloadCoordinator(
    private val context: Context,
    private val db: AppDb,
    private val settingsStore: SettingsStore,
    private val catalog: CatalogRepository,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val engine: DownloadEngine = DownloadEngine(client),
) {
    private val downloadDao = db.downloads()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val activeSinks = ConcurrentHashMap<Long, DownloadSink>()
    private val pauseReasons = ConcurrentHashMap<Long, String?>()
    private val live = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    private val pumpMutex = Mutex()
    @Volatile private var settings = AppSettings()

    init {
        scope.launch {
            settings = settingsStore.flow.first()
            // Anything RUNNING at process start was interrupted; requeue it.
            downloadDao.requeueAllRunning()
            pump()
            settingsStore.flow.collect { value ->
                val wasWifiOnly = settings.wifiOnly
                val oldConcurrency = settings.maxConcurrent
                settings = value
                if (!wasWifiOnly && value.wifiOnly && !isOnWifi()) {
                    pauseAll("Waiting for Wi-Fi (Wi-Fi only)")
                } else if (wasWifiOnly && !value.wifiOnly) {
                    resumeWaiting()
                }
                if (value.maxConcurrent != oldConcurrency) pump()
            }
        }
        scope.launch { progressWriter() }
        registerNetworkCallback()
    }

    fun observeUi(): Flow<List<DownloadUi>> =
        combine(downloadDao.observeAll(), live) { records, snapshot ->
            records.map { rec ->
                val lp = snapshot[rec.id]
                DownloadUi(
                    id = rec.id,
                    title = rec.title,
                    fileName = rec.fileName,
                    url = rec.url,
                    totalBytes = lp?.totalBytes?.takeIf { it > 0 } ?: rec.totalBytes,
                    downloadedBytes = lp?.downloaded ?: rec.downloadedBytes,
                    state = rec.state,
                    speedBps = if (rec.state == DownloadState.RUNNING) lp?.speedBps ?: 0.0 else 0.0,
                    segments = lp?.segments ?: SegmentPlanner.decode(rec.segments),
                    error = rec.error,
                    savedPath = rec.savedPath,
                    mediaUri = rec.mediaUri,
                    mimeType = rec.mimeType,
                )
            }
        }.distinctUntilChanged()

    fun observeActive(): Flow<List<DownloadUi>> =
        observeUi().map { rows ->
            rows.filter { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }
        }

    fun activeCount(): Flow<Int> = downloadDao.observeActiveCount()

    suspend fun enqueue(file: CatalogFile, title: String): Long {
        val existing = downloadDao.byUrl(file.fileUrl)
        if (existing != null) {
            if (existing.state == DownloadState.FAILED || existing.state == DownloadState.PAUSED) {
                downloadDao.update(
                    existing.copy(
                        state = DownloadState.QUEUED,
                        error = null,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                pump()
            }
            return existing.id
        }
        val now = System.currentTimeMillis()
        val id = downloadDao.insert(
            DownloadEntity(
                url = file.fileUrl,
                fileName = file.fileName,
                title = title,
                totalBytes = file.sizeBytes,
                mimeType = mimeFor(file.fileName),
                state = DownloadState.QUEUED,
                createdAt = now,
                updatedAt = now,
            )
        )
        pump()
        return id
    }

    suspend fun enqueueAll(files: List<CatalogFile>, title: String): Int {
        var added = 0
        val now = System.currentTimeMillis()
        for (file in files) {
            val existing = downloadDao.byUrl(file.fileUrl)
            if (existing != null) {
                if (existing.state == DownloadState.FAILED || existing.state == DownloadState.PAUSED) {
                    downloadDao.update(
                        existing.copy(state = DownloadState.QUEUED, error = null, updatedAt = now)
                    )
                    added++
                }
                continue
            }
            downloadDao.insert(
                DownloadEntity(
                    url = file.fileUrl,
                    fileName = file.fileName,
                    title = title,
                    totalBytes = file.sizeBytes,
                    mimeType = mimeFor(file.fileName),
                    state = DownloadState.QUEUED,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            added++
        }
        if (added > 0) pump()
        return added
    }

    fun pause(id: Long) {
        pauseReasons[id] = null
        val job = jobs[id]
        job?.cancel()
        scope.launch {
            pumpMutex.withLock {
                job?.join()
                pauseReasons.remove(id)
                downloadDao.pauseIfActive(id, System.currentTimeMillis())
            }
        }
    }

    fun pauseAll(reason: String? = null) {
        val resolved = reason ?: "Paused"
        val snapshot = jobs.toMap()
        snapshot.keys.forEach { pauseReasons[it] = resolved }
        snapshot.values.forEach { it.cancel() }
        scope.launch {
            pumpMutex.withLock {
                snapshot.values.forEach { it.join() }
                snapshot.keys.forEach { pauseReasons.remove(it) }
                downloadDao.pauseAllActive(resolved, System.currentTimeMillis())
            }
        }
    }

    fun resume(id: Long) {
        scope.launch {
            val rec = downloadDao.byId(id) ?: return@launch
            if (rec.state == DownloadState.PAUSED || rec.state == DownloadState.FAILED) {
                downloadDao.update(
                    rec.copy(state = DownloadState.QUEUED, error = null, updatedAt = System.currentTimeMillis())
                )
                pump()
            }
        }
    }

    fun retry(id: Long) = resume(id)

    fun cancelAndDelete(id: Long) {
        val job = jobs[id]
        scope.launch {
            job?.cancelAndJoin()
            val rec = downloadDao.byId(id) ?: return@launch
            deleteArtifacts(rec)
            downloadDao.delete(id)
            live.update { it - id }
            pump()
        }
    }

    fun removeRecord(id: Long) {
        scope.launch {
            downloadDao.delete(id)
            live.update { it - id }
        }
    }

    private fun pump() {
        scope.launch {
            pumpMutex.withLock {
                val it = jobs.entries.iterator()
                while (it.hasNext()) {
                    if (!it.next().value.isActive) it.remove()
                }
                val max = settings.maxConcurrent.coerceAtLeast(1)
                var running = jobs.values.count { it.isActive }
                if (running >= max) return@withLock
                for (rec in downloadDao.queued()) {
                    if (running >= max) break
                    if (jobs[rec.id]?.isActive == true) continue
                    startLocked(rec)
                    running++
                }
            }
        }
    }

    private fun startLocked(rec: DownloadEntity) {
        DownloadService.start(context)
        val job = scope.launch { runDownload(rec.id) }
        jobs[rec.id] = job
        job.invokeOnCompletion {
            jobs.remove(rec.id, job)
            scope.launch { pump() }
        }
    }

    private suspend fun runDownload(id: Long) {
        var rec = downloadDao.byId(id) ?: return
        if (rec.state != DownloadState.QUEUED && rec.state != DownloadState.RUNNING) return

        if (settings.wifiOnly && !isOnWifi()) {
            setWaiting(rec, "Waiting for Wi-Fi (Wi-Fi only)")
            return
        }

        rec = rec.copy(state = DownloadState.RUNNING, error = null, updatedAt = System.currentTimeMillis())
        downloadDao.update(rec)

        var sink: DownloadSink? = null
        var latest: ProgressSnapshot? = null
        var total = rec.totalBytes
        var segments = SegmentPlanner.decode(rec.segments)

        try {
            val probe = engine.probe(rec.url)
            val sizeChanged = rec.totalBytes > 0 && probe.totalBytes > 0 && probe.totalBytes != rec.totalBytes
            val validatorChanged =
                (rec.etag != null && probe.etag != null && rec.etag != probe.etag) ||
                    (rec.lastModified != null && probe.lastModified != null && rec.lastModified != probe.lastModified)
            if (sizeChanged || validatorChanged) {
                segments = emptyList()
                total = probe.totalBytes
                rec = rec.copy(segments = "", downloadedBytes = 0, totalBytes = total)
            }
            if (probe.totalBytes > 0) total = probe.totalBytes

            if (segments.isEmpty() || total <= 0) {
                segments = when {
                    probe.acceptRanges && total > 0 ->
                        SegmentPlanner.plan(total, settings.segmentsPerDownload)
                    total > 0 -> listOf(Segment(0, 0, total - 1))
                    else -> listOf(Segment(0, 0, Long.MAX_VALUE, 0))
                }
            } else if (!probe.acceptRanges && segments.size > 1) {
                // The server stopped honouring Range; a segmented resume would
                // mix non-contiguous bytes, so restart as one stream.
                segments = listOf(Segment(0, 0, total - 1, 0))
            } else if (total > 0 && segments.sumOf { it.length } != total) {
                segments = if (probe.acceptRanges) {
                    SegmentPlanner.plan(total, settings.segmentsPerDownload)
                } else {
                    listOf(Segment(0, 0, total - 1, 0))
                }
            }

            if (total > 0 && !DiskSpace.hasRoom(context, total, SegmentPlanner.totalDownloaded(segments))) {
                downloadDao.update(
                    rec.copy(
                        state = DownloadState.FAILED,
                        error = "Not enough free space (${Format.bytes(total)} needed)",
                        segments = SegmentPlanner.encode(segments),
                        totalBytes = total,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                return
            }

            rec = rec.copy(
                totalBytes = total,
                segments = SegmentPlanner.encode(segments),
                etag = probe.etag ?: rec.etag,
                lastModified = probe.lastModified ?: rec.lastModified,
                mimeType = rec.mimeType ?: mimeFor(rec.fileName),
                updatedAt = System.currentTimeMillis(),
            )
            downloadDao.update(rec)

            sink = createSink(rec)
            val preserved = sink.open()
            activeSinks[id] = sink
            if (!preserved && segments.any { it.downloaded > 0 }) {
                segments = segments.map { it.copy(downloaded = 0) }
                rec = rec.copy(
                    downloadedBytes = 0,
                    segments = SegmentPlanner.encode(segments),
                    updatedAt = System.currentTimeMillis(),
                )
                downloadDao.update(rec)
            }
            sink.mediaUri?.let { uri ->
                if (uri != rec.mediaUri) {
                    rec = rec.copy(mediaUri = uri)
                    downloadDao.update(rec)
                }
            }
            if (total > 0) sink.preallocate(total)

            val outcome = engine.run(
                url = rec.url,
                sink = sink,
                plan = segments,
                totalBytes = total,
                validator = rec.etag ?: rec.lastModified,
            ) { snapshot ->
                latest = snapshot
                live.update {
                    it + (id to LiveProgress(snapshot.downloaded, snapshot.speedBps, snapshot.segments, total))
                }
            }

            val snapshot = latest
            when (outcome) {
                is DownloadOutcome.Completed -> {
                    val finalBytes = snapshot?.downloaded ?: SegmentPlanner.totalDownloaded(segments)
                    val knownTotal = if (total > 0) total else finalBytes
                    val finalSegments = snapshot?.segments?.let { plan ->
                        if (plan.size == 1 && knownTotal > 0) {
                            listOf(plan[0].copy(end = knownTotal - 1, downloaded = finalBytes))
                        } else {
                            plan
                        }
                    } ?: segments
                    val path = sink.finish()
                    val mediaUri = sink.mediaUri ?: rec.mediaUri
                    downloadDao.update(
                        rec.copy(
                            state = DownloadState.COMPLETED,
                            downloadedBytes = knownTotal,
                            totalBytes = knownTotal,
                            segments = SegmentPlanner.encode(finalSegments),
                            savedPath = path,
                            mediaUri = mediaUri,
                            error = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    scope.launch { catalog.refreshDownloadedCounts() }
                }

                is DownloadOutcome.Failed -> {
                    val online = isOnline()
                    val wifiOk = !settings.wifiOnly || isOnWifi()
                    val state = if (!online || !wifiOk) DownloadState.PAUSED else DownloadState.FAILED
                    val error = when {
                        !online -> "Waiting for network"
                        !wifiOk -> "Waiting for Wi-Fi (Wi-Fi only)"
                        else -> outcome.message
                    }
                    downloadDao.update(
                        rec.copy(
                            state = state,
                            error = error,
                            downloadedBytes = snapshot?.downloaded ?: SegmentPlanner.totalDownloaded(segments),
                            segments = SegmentPlanner.encode(snapshot?.segments ?: segments),
                            totalBytes = total,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }

                DownloadOutcome.Stale -> {
                    sink.delete()
                    downloadDao.update(
                        rec.copy(
                            state = DownloadState.FAILED,
                            error = "Server file changed — tap Retry",
                            downloadedBytes = 0,
                            totalBytes = -1,
                            segments = "",
                            etag = null,
                            lastModified = null,
                            mediaUri = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            val snapshot = latest
            withContext(NonCancellable) {
                val current = downloadDao.byId(id)
                if (current != null && (current.state == DownloadState.RUNNING || current.state == DownloadState.QUEUED)) {
                    runCatching { sink?.sync() }
                    val reason = pauseReasons.remove(id)
                    downloadDao.update(
                        current.copy(
                            state = DownloadState.PAUSED,
                            downloadedBytes = snapshot?.downloaded ?: current.downloadedBytes,
                            segments = SegmentPlanner.encode(snapshot?.segments ?: segments),
                            totalBytes = if (total > 0) total else current.totalBytes,
                            error = reason,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
            throw e
        } catch (e: Exception) {
            val snapshot = latest
            val online = isOnline()
            val wifiOk = !settings.wifiOnly || isOnWifi()
            val state = if (!online || !wifiOk) DownloadState.PAUSED else DownloadState.FAILED
            val error = when {
                !online -> "Waiting for network"
                !wifiOk -> "Waiting for Wi-Fi (Wi-Fi only)"
                else -> e.message ?: "Download failed"
            }
            downloadDao.update(
                rec.copy(
                    state = state,
                    error = error,
                    downloadedBytes = snapshot?.downloaded ?: SegmentPlanner.totalDownloaded(segments),
                    segments = SegmentPlanner.encode(snapshot?.segments ?: segments),
                    totalBytes = total,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        } finally {
            activeSinks.remove(id)
            runCatching { sink?.close() }
            live.update { it - id }
        }
    }

    private suspend fun setWaiting(rec: DownloadEntity, reason: String) {
        downloadDao.update(
            rec.copy(state = DownloadState.PAUSED, error = reason, updatedAt = System.currentTimeMillis())
        )
    }

    private fun createSink(rec: DownloadEntity): DownloadSink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreSink(
                context = context,
                fileName = rec.fileName,
                mimeType = rec.mimeType ?: mimeFor(rec.fileName),
                existingUri = rec.mediaUri,
            )
        } else {
            LegacySink(context, rec.id, rec.fileName, rec.mimeType ?: mimeFor(rec.fileName))
        }

    private suspend fun deleteArtifacts(rec: DownloadEntity) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && rec.mediaUri != null) {
            runCatching { context.contentResolver.delete(Uri.parse(rec.mediaUri), null, null) }
        }
        val partName = LegacySink.partFileName(rec.id, sanitizeKeepExt(rec.fileName))
        File(LegacySink.publicDownloadsDir(), partName).delete()
        File(LegacySink.appDownloadsDir(context), partName).delete()
        partFile(rec).delete()
    }

    private fun partFile(rec: DownloadEntity): File =
        File(
            File(context.getExternalFilesDir(null) ?: context.filesDir, "inprogress"),
            "${rec.id}_${sanitize(rec.fileName)}.part",
        )

    private suspend fun progressWriter() {
        while (true) {
            delay(2_000)
            val snapshot = live.value
            if (snapshot.isEmpty()) continue
            val now = System.currentTimeMillis()
            runCatching {
                for (id in snapshot.keys) {
                    runCatching { activeSinks[id]?.sync() }
                }
                db.withTransaction {
                    for ((id, progress) in snapshot) {
                        downloadDao.updateProgress(
                            id = id,
                            downloaded = progress.downloaded,
                            segments = SegmentPlanner.encode(progress.segments),
                            total = progress.totalBytes,
                            now = now,
                        )
                    }
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleNetworkChange(cm.getNetworkCapabilities(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                handleNetworkChange(networkCapabilities)
            }

            override fun onLost(network: Network) {
                if (settings.wifiOnly && !isOnWifi()) {
                    pauseAll("Waiting for Wi-Fi (Wi-Fi only)")
                }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
        }
    }

    private fun handleNetworkChange(caps: NetworkCapabilities?) {
        if (caps == null) return
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (settings.wifiOnly && !wifi) {
            pauseAll("Waiting for Wi-Fi (Wi-Fi only)")
        } else if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            scope.launch { resumeWaiting(wifiAvailable = wifi) }
        }
    }

    private suspend fun resumeWaiting(wifiAvailable: Boolean = isOnWifi()) {
        if (settings.wifiOnly && !wifiAvailable) return
        val resumed = downloadDao.requeueWaiting(System.currentTimeMillis())
        if (resumed > 0) pump()
    }

    private fun isOnWifi(): Boolean {
        val caps = activeCapabilities() ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun isOnline(): Boolean {
        val caps = activeCapabilities() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun activeCapabilities(): NetworkCapabilities? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(network)
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
