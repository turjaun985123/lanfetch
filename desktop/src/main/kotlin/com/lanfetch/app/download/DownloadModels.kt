package com.lanfetch.app.download

import androidx.compose.runtime.Immutable
import com.lanfetch.app.data.DownloadState

@Immutable
data class DownloadUi(
    val id: Long,
    val title: String,
    val fileName: String,
    val url: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val state: DownloadState,
    val speedBps: Double = 0.0,
    val segments: List<Segment> = emptyList(),
    val error: String? = null,
    val savedPath: String? = null,
    val mediaUri: String? = null,
    val mimeType: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes > 0) {
            (downloadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        } else 0f

    val etaSeconds: Long
        get() = if (speedBps > 1 && totalBytes > downloadedBytes) {
            ((totalBytes - downloadedBytes) / speedBps).toLong()
        } else -1
}

data class ProgressSnapshot(
    val downloaded: Long,
    val speedBps: Double,
    val segments: List<Segment>,
)

data class ProbeResult(
    val totalBytes: Long,
    val acceptRanges: Boolean,
    val etag: String? = null,
    val lastModified: String? = null,
) {
    val validator: String? get() = etag ?: lastModified
}

sealed interface DownloadOutcome {
    data object Completed : DownloadOutcome
    data class Failed(val message: String) : DownloadOutcome
    data object Stale : DownloadOutcome
}
