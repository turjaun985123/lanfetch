package com.lanfetch.app.download

/** One byte range of a file. [end] is inclusive, like HTTP Range. Immutable by design. */
data class Segment(
    val index: Int,
    val start: Long,
    val end: Long,
    val downloaded: Long = 0,
) {
    val length: Long get() = end - start + 1
    val remaining: Long get() = length - downloaded
    val done: Boolean get() = downloaded >= length

    fun withDownloaded(bytes: Long): Segment = copy(downloaded = bytes.coerceIn(0, length))
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
        return text.split(";").mapIndexedNotNull { i, part ->
            val nums = part.split("-").mapNotNull { it.toLongOrNull() }
            if (nums.size != 3) null
            else Segment(i, nums[0], nums[1], nums[2].coerceIn(0, nums[1] - nums[0] + 1))
        }
    }

    fun totalDownloaded(segments: List<Segment>): Long = segments.sumOf { it.downloaded }
}
