package com.lanfetch.app.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Desktop edition of the sink layer: the [DownloadSink] contract is the same
 * one the Android app implements (the engine is shared verbatim); here the
 * bytes land in a plain file — `<name>.part` while downloading, renamed to
 * the final name on [finish].
 */
interface DownloadSink {
    val displayPath: String
    val mediaUri: String? get() = null

    /** Opens the sink and returns true if existing partial bytes were preserved. */
    suspend fun open(): Boolean
    suspend fun preallocate(totalBytes: Long)
    fun write(position: Long, data: ByteArray, offset: Int, length: Int)
    suspend fun sync()
    suspend fun truncate(length: Long)
    suspend fun finish(): String
    fun close()
    suspend fun delete()
}

/** The audit-hardened file-name sanitizer, shared with the Android app (S5). */
internal fun sanitizeKeepExt(name: String): String {
    val stripped = name.filterNot { ch ->
        ch.code < 0x20 || ch.code == 0x7F ||
            ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069'
    }
    val clean = stripped.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().trimEnd('.', ' ')
    if (clean.isEmpty() || clean == "..") return "download.bin"
    if (clean.toByteArray(Charsets.UTF_8).size <= 120) return clean
    val dot = clean.lastIndexOf('.')
    val ext = if (dot in (clean.length - 12)..(clean.length - 2)) clean.substring(dot) else ""
    val stem = clean.substring(0, clean.length - ext.length)
    var bytes = 120 - ext.toByteArray(Charsets.UTF_8).size
    val sb = StringBuilder()
    for (ch in stem) {
        val n = ch.toString().toByteArray(Charsets.UTF_8).size
        if (bytes - n < 0) break
        bytes -= n
        sb.append(ch)
    }
    return sb.toString().trimEnd('.', ' ') + ext
}

class FileDownloadSink(
    private val directory: File,
    fileName: String,
) : DownloadSink {
    private val safeName = sanitizeKeepExt(fileName)
    private val partFile = File(directory, "$safeName.part")
    private val finalFile = File(directory, safeName)
    private var raf: RandomAccessFile? = null

    override val displayPath: String get() = finalFile.absolutePath

    override suspend fun open(): Boolean = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val existed = partFile.exists() && partFile.length() > 0
        raf = RandomAccessFile(partFile, "rw")
        existed
    }

    override suspend fun preallocate(totalBytes: Long) {
        withContext(Dispatchers.IO) {
            val r = raf ?: return@withContext
            if (totalBytes > 0 && r.length() < totalBytes) r.setLength(totalBytes)
        }
    }

    override fun write(position: Long, data: ByteArray, offset: Int, length: Int) {
        val r = raf ?: return
        // FileChannel positional writes are thread-safe: segment workers write
        // disjoint ranges concurrently without locking.
        val channel = r.channel
        val buffer = ByteBuffer.wrap(data, offset, length)
        var pos = position
        while (buffer.hasRemaining()) {
            pos += channel.write(buffer, pos)
        }
    }

    override suspend fun sync() {
        withContext(Dispatchers.IO) {
            runCatching { raf?.channel?.force(true) }
        }
    }

    override suspend fun truncate(length: Long) {
        withContext(Dispatchers.IO) {
            val r = raf ?: return@withContext
            if (length >= 0 && r.length() != length) r.setLength(length)
        }
    }

    override suspend fun finish(): String = withContext(Dispatchers.IO) {
        close()
        if (finalFile.exists()) finalFile.delete()
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
        finalFile.absolutePath
    }

    override fun close() {
        runCatching { raf?.close() }
        raf = null
    }

    override suspend fun delete() {
        withContext(Dispatchers.IO) {
            close()
            runCatching { partFile.delete() }
        }
    }
}
