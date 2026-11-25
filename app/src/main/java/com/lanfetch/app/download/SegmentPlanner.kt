package com.lanfetch.app.download

import androidx.compose.runtime.Immutable

/** One byte range of a file. [end] is inclusive, like HTTP Range. Immutable by design. */
@Immutable
data class Segment(
    val index: Int,
    val start: Long,
    val end: Long,
    val downloaded: Long = 0,
) {
    val openEnded: Boolean get() = end == OPEN_END
    val length: Long get() = if (openEnded) Long.MAX_VALUE else end - start + 1
    val remaining: Long get() = length - downloaded
    val done: Boolean get() = downloaded >= length

    fun withDownloaded(bytes: Long): Segment = copy(
        downloaded = if (openEnded) bytes.coerceAtLeast(0) else bytes.coerceIn(0, length),
    )

    companion object {
        const val OPEN_END = -1L
    }
}

/**
 * Pure segment planning + state encoding for resumable downloads. The plan is
 * persisted ("start-end-downloaded;...") so a pause, process death or reboot
 * resumes byte-exactly.
 */
object SegmentPlanner {

    const val MIN_SEGMENT_BYTES = 512 * 1024L // never make segments smaller than 512 KB

    fun plan(totalBytes: Long, requestedSegments: Int): List<Segment> {
        if (totalBytes <= 0) return emptyList()
        val maxBySize = maxOf(1L, totalBytes / MIN_SEGMENT_BYTES)
        val count = minOf(requestedSegments.toLong(), maxBySize, totalBytes).toInt().coerceAtLeast(1)
        val base = totalBytes / count
        val rem = totalBytes % count
        val out = ArrayList<Segment>(count)
        var pos = 0L
        for (i in 0 until count) {
            val len = base + if (i < rem) 1 else 0
            out += Segment(index = i, start = pos, end = pos + len - 1)
            pos += len
        }
        return out
    }

    fun encode(segments: List<Segment>): String =
        segments.joinToString(";") { "${it.start}-${it.end}-${it.downloaded}" }

    fun decode(text: String): List<Segment> {
        if (text.isBlank()) return emptyList()
        val parts = text.split(";")
        val decoded = parts.mapIndexed { i, part ->
            val match = Regex("^(\\d+)-(-?\\d+)-(\\d+)$").matchEntire(part) ?: return emptyList()
            val start = match.groupValues[1].toLongOrNull() ?: return emptyList()
            val end = match.groupValues[2].toLongOrNull() ?: return emptyList()
            val downloaded = match.groupValues[3].toLongOrNull() ?: return emptyList()
            if (start < 0 || (end != Segment.OPEN_END && end < start) || (end == Segment.OPEN_END && i != 0)) {
                return emptyList()
            }
            val segment = Segment(i, start, end)
            segment.withDownloaded(downloaded)
        }
        if (decoded.zipWithNext().any { (a, b) -> a.openEnded || b.start != a.end + 1 }) return emptyList()
        return decoded
    }

    fun totalDownloaded(segments: List<Segment>): Long = segments.sumOf { it.downloaded }
}
