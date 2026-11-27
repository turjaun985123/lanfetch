package com.lanfetch.app.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DownloadEngineTest {

    @Test fun probeUsesByteRangeEvenWithoutAcceptRangesHeader() = runBlocking {
        val payload = ByteArray(1024) { (it % 251).toByte() }
        RangeServer(payload, sendAcceptRanges = false).use { server ->
            val engine = DownloadEngine(OkHttpClient())
            val probe = engine.probe(server.url("/file.bin"))
            assertTrue(probe.acceptRanges)
            assertEquals(1024L, probe.totalBytes)
            assertEquals("\"v1\"", probe.etag)
        }
    }

    @Test fun probeFallsBackWhenServerIgnoresRange() = runBlocking {
        val payload = ByteArray(512) { 7 }
        RangeServer(payload, supportRange = false).use { server ->
            val engine = DownloadEngine(OkHttpClient())
            val probe = engine.probe(server.url("/file.bin"))
            assertFalse(probe.acceptRanges)
            assertEquals(512L, probe.totalBytes)
        }
    }

    @Test fun segmentedDownloadProducesExactFile() = runBlocking {
        val payload = ByteArray(1_600_000) { (it * 31 % 251).toByte() }
        val tmp = Files.createTempFile("lanfetch-dl-", ".bin").toFile()
        tmp.deleteOnExit()
        RangeServer(payload).use { server ->
            val engine = DownloadEngine(OkHttpClient())
            val plan = SegmentPlanner.plan(payload.size.toLong(), 3)
            assertEquals(3, plan.size)
            val sink = FileSink(tmp)
            sink.open()
            sink.preallocate(payload.size.toLong())
            val outcome = engine.run(server.url("/movie.mkv"), sink, plan, payload.size.toLong(), "\"v1\"") {}
            sink.finish()
            sink.close()
            assertEquals(DownloadOutcome.Completed, outcome)
            assertArrayEquals(payload, tmp.readBytes())
        }
    }

    @Test fun tricklingServerExhaustsRetryBudget() = runBlocking {
        // Regression: the server delivers 4 KiB then drops the
        // connection, on every attempt. Before the fix this test
        // never returns — every attempt delivered > 0 bytes, which
        // reset the retry counter to zero inside the read loop,
        // so MAX_RETRIES was never reached and the segment (and
        // the wakelock) was held forever. Now a trickle attempt
        // consumes budget: after MAX_RETRIES + 1 attempts the
        // download must fail. Backoff across 7 attempts totals
        // ~90 s, hence the generous timeout.
        val payload = ByteArray(1_000_000) { (it % 251).toByte() }
        val tmp = Files.createTempFile("lanfetch-trickle-", ".bin").toFile()
        tmp.deleteOnExit()
        RangeServer(payload, truncateAfterBytes = 4096).use { server ->
            val engine = DownloadEngine(OkHttpClient())
            val plan = SegmentPlanner.plan(payload.size.toLong(), 1)
            assertEquals(1, plan.size)
            val sink = FileSink(tmp)
            sink.open()
            sink.preallocate(payload.size.toLong())
            val outcome = withTimeout(240_000) {
                engine.run(server.url("/file.bin"), sink, plan, payload.size.toLong(), "\"v1\"") {}
            }
            sink.close()
            assertTrue(outcome is DownloadOutcome.Failed)
        }
    }

    @Test fun tricklingServerExhaustsSegmentRetryBudget() = runBlocking {
        // Segmented counterpart of tricklingServerExhaustsRetryBudget:
        // a multi-segment plan dispatches to downloadSegment (the
        // 1-segment test above routes to runSingleStream), whose read
        // loop carried the identical budget-refund bug and fix. Three
        // segments trickle in parallel, so wall time stays ~90 s.
        val payload = ByteArray(3_000_000) { (it % 251).toByte() }
        val tmp = Files.createTempFile("lanfetch-trickle-seg-", ".bin").toFile()
        tmp.deleteOnExit()
        RangeServer(payload, truncateAfterBytes = 4096).use { server ->
            val engine = DownloadEngine(OkHttpClient())
            val plan = SegmentPlanner.plan(payload.size.toLong(), 3)
            assertEquals(3, plan.size)
            val sink = FileSink(tmp)
            sink.open()
            sink.preallocate(payload.size.toLong())
            val outcome = withTimeout(240_000) {
                engine.run(server.url("/file.bin"), sink, plan, payload.size.toLong(), "\"v1\"") {}
            }
            sink.close()
            assertTrue(outcome is DownloadOutcome.Failed)
        }
    }

    @Test fun unknownLengthChunkedDownloadCompletesAtEof() = runBlocking {
        val payload = ByteArray(128_321) { (it * 7 % 251).toByte() }
        val tmp = Files.createTempFile("lanfetch-unknown-", ".bin").toFile()
        tmp.deleteOnExit()
        RangeServer(payload, supportRange = false, sendContentLength = false).use { server ->
            val engine = DownloadEngine(OkHttpClient())
            val probe = engine.probe(server.url("/file.bin"))
            assertEquals(-1L, probe.totalBytes)
            val sink = FileSink(tmp)
            sink.open()
            val plan = listOf(Segment(0, 0, Segment.OPEN_END))
            val outcome = engine.run(server.url("/file.bin"), sink, plan, -1, null) {}
            sink.finish()
            sink.close()
            assertEquals(DownloadOutcome.Completed, outcome)
            assertArrayEquals(payload, tmp.readBytes())
        }
    }

    @Test fun resumeSendsIfRangeAndCompletesRemainingBytes() = runBlocking {
        val payload = ByteArray(1_200_000) { (it * 17 % 251).toByte() }
        val tmp = Files.createTempFile("lanfetch-resume-", ".bin").toFile()
        tmp.deleteOnExit()
        val plan = SegmentPlanner.plan(payload.size.toLong(), 2)
        val halfSeg0 = plan[0].length / 2
        val resumedPlan = listOf(plan[0].withDownloaded(halfSeg0), plan[1])

        val sink = FileSink(tmp)
        sink.open()
        sink.preallocate(payload.size.toLong())
        sink.write(0, payload, 0, halfSeg0.toInt())

        RangeServer(payload).use { server ->
            val engine = DownloadEngine(OkHttpClient())
            val outcome = engine.run(server.url("/movie.mkv"), sink, resumedPlan, payload.size.toLong(), "\"v1\"") {}
            sink.finish()
            sink.close()
            assertEquals(DownloadOutcome.Completed, outcome)
            assertArrayEquals(payload, tmp.readBytes())
            assertTrue(server.rangeHeaders.any { it == "bytes=$halfSeg0-${plan[0].end}" })
            assertTrue(server.ifRangeHeaders.all { it == "\"v1\"" })
        }
    }

    @Test fun staleValidatorReturnsStaleOutcome() = runBlocking {
        val payload = ByteArray(1_200_000) { 3 }
        val tmp = Files.createTempFile("lanfetch-stale-", ".bin").toFile()
        tmp.deleteOnExit()
        val plan = SegmentPlanner.plan(payload.size.toLong(), 2)
        val sink = FileSink(tmp)
        sink.open()
        RangeServer(payload, etag = "\"v2\"").use { server ->
            val engine = DownloadEngine(OkHttpClient())
            val outcome = engine.run(server.url("/movie.mkv"), sink, plan, payload.size.toLong(), "\"v1\"") {}
            sink.close()
            assertEquals(DownloadOutcome.Stale, outcome)
        }
    }

    @Test fun cancellationStopsStalledReadImmediately() = runBlocking {
        val payload = ByteArray(1_200_000) { 1 }
        val tmp = Files.createTempFile("lanfetch-cancel-", ".bin").toFile()
        tmp.deleteOnExit()
        val firstChunkSent = CompletableDeferred<Unit>()
        RangeServer(payload, stallAfterBytes = 16 * 1024, onFirstChunkSent = { firstChunkSent.complete(Unit) }).use { server ->
            val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
            val engine = DownloadEngine(client)
            val plan = listOf(Segment(0, 0, payload.size.toLong() - 1))
            val sink = FileSink(tmp)
            sink.open()
            val started = System.currentTimeMillis()
            val job = async(Dispatchers.IO) {
                engine.run(server.url("/movie.mkv"), sink, plan, payload.size.toLong(), null) {}
            }
            withTimeout(3_000) { firstChunkSent.await() }
            delay(100)
            job.cancel()
            var cancelled = false
            try {
                job.await()
            } catch (_: CancellationException) {
                cancelled = true
            } finally {
                sink.close()
            }
            val elapsed = System.currentTimeMillis() - started
            assertTrue(cancelled)
            assertTrue("Cancellation took too long: ${elapsed}ms", elapsed < 5_000)
        }
    }

    private class RangeServer(
        private val data: ByteArray,
        private val supportRange: Boolean = true,
        private val sendAcceptRanges: Boolean = true,
        private val sendContentLength: Boolean = true,
        private val etag: String = "\"v1\"",
        private val stallAfterBytes: Int = -1,
        private val truncateAfterBytes: Int = -1,
        private val onFirstChunkSent: (() -> Unit)? = null,
    ) : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        val rangeHeaders = Collections.synchronizedList(mutableListOf<String>())
        val ifRangeHeaders = Collections.synchronizedList(mutableListOf<String>())

        private val acceptThread = thread(isDaemon = true) {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { handle(client) }
            }
        }

        fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"

        private fun handle(client: Socket) {
            client.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val headers = readHeaders(input) ?: return
                val rangeHeader = headers["range"]
                val ifRange = headers["if-range"]
                rangeHeader?.let { rangeHeaders += it }
                ifRange?.let { ifRangeHeaders += it }

                val out = s.getOutputStream()
                val validatorMatches = ifRange == null || ifRange == etag
                if (supportRange && rangeHeader != null && validatorMatches) {
                    val spec = rangeHeader.removePrefix("bytes=").split("-")
                    val start = spec[0].toLong()
                    val end = spec.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLong() ?: (data.size - 1L)
                    val len = (end - start + 1).toInt()
                    val header = buildString {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        if (sendContentLength) append("Content-Length: $len\r\n")
                        append("Content-Range: bytes $start-$end/${data.size}\r\n")
                        if (sendAcceptRanges) append("Accept-Ranges: bytes\r\n")
                        append("ETag: $etag\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(header.toByteArray())
                    writeBody(out, start.toInt(), len)
                } else {
                    val header = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        if (sendContentLength) append("Content-Length: ${data.size}\r\n")
                        append("ETag: $etag\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(header.toByteArray())
                    writeBody(out, 0, data.size)
                }
                out.flush()
            }
        }

        private fun writeBody(out: java.io.OutputStream, offset: Int, len: Int) {
            if (truncateAfterBytes in 1 until len) {
                // Send a few bytes, then let the socket close (the
                // caller's use block) while Content-Length promised
                // more — an abrupt mid-body drop, every time.
                out.write(data, offset, truncateAfterBytes)
                out.flush()
                return
            }
            if (stallAfterBytes in 1 until len) {
                out.write(data, offset, stallAfterBytes)
                out.flush()
                onFirstChunkSent?.invoke()
                val deadline = System.currentTimeMillis() + 10_000
                while (running.get() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                }
            } else {
                out.write(data, offset, len)
            }
        }

        private fun readHeaders(input: BufferedInputStream): Map<String, String>? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                sb.append(b.toChar())
                if (sb.endsWith("\r\n\r\n")) break
            }
            val lines = sb.toString().split("\r\n").drop(1)
            val map = LinkedHashMap<String, String>()
            for (line in lines) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    map[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }
            return map
        }

        override fun close() {
            running.set(false)
            runCatching { socket.close() }
            acceptThread.join(1000)
        }
    }
}
