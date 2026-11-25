package com.lanfetch.app.download

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Destination abstraction: a plain file, or a pending MediaStore entry. */
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

object DiskSpace {
    private const val MARGIN = 64L * 1024 * 1024

    fun hasPublicStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED

    suspend fun hasRoom(
        context: Context,
        totalBytes: Long,
        alreadyDownloadedBytes: Long = 0L,
    ): Boolean = withContext(Dispatchers.IO) {
        if (totalBytes <= 0) return@withContext true
        val needed = (totalBytes - alreadyDownloadedBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
        val dir = if (hasPublicStoragePermission(context)) {
            Environment.getExternalStorageDirectory()
        } else {
            context.getExternalFilesDir(null) ?: context.filesDir
        }
        val stat = StatFs(dir.absolutePath)
        stat.availableBytes > needed + MARGIN
    }
}

class FileSink(private val file: File) : DownloadSink {
    private var raf: RandomAccessFile? = null
    private var channel: FileChannel? = null

    override val displayPath: String get() = file.absolutePath

    override suspend fun open(): Boolean = withContext(Dispatchers.IO) {
        val existed = file.exists() && file.length() > 0
        file.parentFile?.mkdirs()
        val handle = RandomAccessFile(file, "rw")
        raf = handle
        channel = handle.channel
        existed
    }

    override suspend fun preallocate(totalBytes: Long) = withContext(Dispatchers.IO) {
        val handle = raf ?: return@withContext
        if (handle.length() != totalBytes) handle.setLength(totalBytes)
    }

    override fun write(position: Long, data: ByteArray, offset: Int, length: Int) {
        val ch = channel ?: throw IOException("Sink is not open")
        val buffer = ByteBuffer.wrap(data, offset, length)
        var at = position
        while (buffer.hasRemaining()) {
            val written = ch.write(buffer, at)
            if (written <= 0) throw IOException("Cannot write at $at")
            at += written
        }
    }

    override suspend fun sync(): Unit = withContext(Dispatchers.IO) {
        channel?.force(false)
    }

    override suspend fun truncate(length: Long): Unit = withContext(Dispatchers.IO) {
        raf?.setLength(length)
    }

    override suspend fun finish(): String = file.absolutePath

    override fun close() {
        runCatching { channel?.close() }
        runCatching { raf?.close() }
        channel = null
        raf = null
    }

    override suspend fun delete(): Unit = withContext(Dispatchers.IO) {
        close()
        file.delete()
    }
}

/** Android 10 (API 29) through latest Android: writes directly into public Downloads/LanFetch via MediaStore. */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreSink(
    private val context: Context,
    private val fileName: String,
    private val mimeType: String,
    existingUri: String?,
) : DownloadSink {
    private var uri: Uri? = existingUri?.let(Uri::parse)
    private var pfd: ParcelFileDescriptor? = null
    private var channel: FileChannel? = null

    override val displayPath: String get() = "Downloads/LanFetch/$fileName"
    override val mediaUri: String? get() = uri?.toString()

    override suspend fun open(): Boolean = withContext(Dispatchers.IO) {
        var preserved = uri != null
        if (uri == null) uri = insertPending()
        val target = uri ?: throw IOException("Cannot create MediaStore entry")
        val descriptor = try {
            context.contentResolver.openFileDescriptor(target, "rw")
        } catch (_: Exception) {
            null
        } ?: run {
            preserved = false
            uri = insertPending()
            context.contentResolver.openFileDescriptor(uri!!, "rw")
        } ?: throw IOException("Cannot open MediaStore entry")
        pfd = descriptor
        val ch = FileOutputStream(descriptor.fileDescriptor).channel
        channel = ch
        preserved && ch.size() > 0
    }

    private fun insertPending(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, sanitizeKeepExt(fileName))
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanFetch")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create MediaStore entry")
    }

    override suspend fun preallocate(totalBytes: Long) = withContext(Dispatchers.IO) {
        val ch = channel ?: return@withContext
        if (totalBytes > 0 && ch.size() < totalBytes) {
            // Extending through the FUSE provider is not supported everywhere;
            // sparse writes still work when it is not.
            runCatching {
                val one = ByteBuffer.allocate(1)
                var at = totalBytes - 1
                while (one.hasRemaining()) {
                    val written = ch.write(one, at)
                    if (written <= 0) break
                    at += written
                }
            }
        }
    }

    override fun write(position: Long, data: ByteArray, offset: Int, length: Int) {
        val ch = channel ?: throw IOException("Sink is not open")
        val buffer = ByteBuffer.wrap(data, offset, length)
        var at = position
        while (buffer.hasRemaining()) {
            val written = ch.write(buffer, at)
            if (written <= 0) throw IOException("Cannot write at $at")
            at += written
        }
    }

    override suspend fun sync(): Unit = withContext(Dispatchers.IO) {
        channel?.force(false)
    }

    override suspend fun truncate(length: Long): Unit = withContext(Dispatchers.IO) {
        channel?.truncate(length)
    }

    override suspend fun finish(): String = withContext(Dispatchers.IO) {
        runCatching { channel?.force(true) }
        close()
        val target = uri ?: return@withContext displayPath
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        context.contentResolver.update(target, values, null, null)
        val actualName = resolveDisplayName(target) ?: fileName
        "Downloads/LanFetch/$actualName"
    }

    private fun resolveDisplayName(target: Uri): String? = runCatching {
        context.contentResolver.query(
            target,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    override fun close() {
        runCatching { channel?.close() }
        runCatching { pfd?.close() }
        channel = null
        pfd = null
    }

    override suspend fun delete(): Unit = withContext(Dispatchers.IO) {
        close()
        uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
    }
}

/**
 * Android 6 – 9 (API 23 – 28) sink: writes straight into public
 * `Phone storage/Download/LanFetch/` (when WRITE_EXTERNAL_STORAGE is granted)
 * as a hidden `.part`, atomically renames on completion, and triggers MediaScanner
 * so file managers & video players see it immediately.
 */
class LegacySink(
    private val context: Context,
    private val id: Long,
    private val fileName: String,
    private val mimeType: String,
) : DownloadSink {
    private val safeName = sanitizeKeepExt(fileName)
    private val targetDir = resolveDirectory(context)
    private val part = File(targetDir, partFileName(id, safeName))
    private val inner = FileSink(part)

    override val displayPath: String get() = File(targetDir, safeName).absolutePath

    override suspend fun open(): Boolean = inner.open()

    override suspend fun preallocate(totalBytes: Long) = inner.preallocate(totalBytes)

    override fun write(position: Long, data: ByteArray, offset: Int, length: Int) =
        inner.write(position, data, offset, length)

    override suspend fun sync() = inner.sync()

    override suspend fun truncate(length: Long) = inner.truncate(length)

    override suspend fun finish(): String = withContext(Dispatchers.IO) {
        inner.sync()
        inner.close()
        val dest = uniqueDestination(targetDir, safeName)
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = false)
            part.delete()
        }
        MediaScannerConnection.scanFile(
            context.applicationContext,
            arrayOf(dest.absolutePath),
            arrayOf(mimeType),
            null,
        )
        dest.absolutePath
    }

    override fun close() = inner.close()

    override suspend fun delete() {
        inner.delete()
    }

    companion object {
        fun partFileName(id: Long, safeName: String): String = ".lanfetch_${id}_${safeName}.part"

        fun publicDownloadsDir(): File {
            @Suppress("DEPRECATION")
            return File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LanFetch",
            )
        }

        fun appDownloadsDir(context: Context): File =
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, "LanFetch")

        fun resolveDirectory(context: Context): File {
            if (DiskSpace.hasPublicStoragePermission(context)) {
                val dir = publicDownloadsDir()
                if (dir.isDirectory || dir.mkdirs()) return dir
            }
            val fallback = appDownloadsDir(context)
            fallback.mkdirs()
            return fallback
        }

        private fun uniqueDestination(dir: File, name: String): File {
            val direct = File(dir, name)
            if (!direct.exists()) return direct
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            for (i in 1..999) {
                val candidate = File(dir, "$stem ($i)$ext")
                if (!candidate.exists()) return candidate
            }
            return File(dir, "${System.currentTimeMillis()}_$name")
        }
    }
}

internal fun sanitizeKeepExt(name: String): String {
    val clean = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    if (clean.length <= 120) return clean.ifEmpty { "download.bin" }
    val dot = clean.lastIndexOf('.')
    if (dot in (clean.length - 12)..(clean.length - 2)) {
        val ext = clean.substring(dot)
        val stem = clean.substring(0, dot).take((120 - ext.length).coerceAtLeast(1))
        return stem + ext
    }
    return clean.take(120)
}
