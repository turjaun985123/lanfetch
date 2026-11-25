package com.lanfetch.app.net

import com.lanfetch.app.data.CatalogItem
import com.lanfetch.app.data.Source
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ScraperTest {

    @Test fun syncGroupsSeasonsUnderShowAndIncrementalSyncFindsNewSeason() = runBlocking {
        val pages = Collections.synchronizedMap(
            linkedMapOf(
                "/" to """
                    <html><body><pre>
                    <a href="TV%20Series/">TV Series/</a>      01-Jan-2024 10:00  -
                    <a href="Movies/">Movies/</a>             01-Jan-2024 10:00  -
                    <a href="Sample/">Sample/</a>             01-Jan-2024 10:00  -
                    </pre></body></html>
                """.trimIndent(),
                "/Sample/" to """
                    <pre><a href="ignore.mkv">ignore.mkv</a> 01-Jan-2024 10:00 1048576</pre>
                """.trimIndent(),
                "/TV%20Series/" to """
                    <pre><a href="Breaking%20Bad%20%282008%29/">Breaking Bad (2008)/</a> 01-Jan-2024 10:00 -</pre>
                """.trimIndent(),
                "/TV%20Series/Breaking%20Bad%20%282008%29/" to """
                    <pre>
                    <a href="Season%201/">Season 1/</a> 01-Jan-2024 10:00 -
                    <a href="Sample/">Sample/</a>     01-Jan-2024 10:00 -
                    <a href="poster.jpg">poster.jpg</a> 01-Jan-2024 10:00 45000
                    </pre>
                """.trimIndent(),
                "/TV%20Series/Breaking%20Bad%20%282008%29/Season%201/" to """
                    <pre><a href="Breaking.Bad.S01E01.720p.mkv">Breaking.Bad.S01E01.720p.mkv</a> 01-Jan-2024 10:00 734003200</pre>
                """.trimIndent(),
                "/Movies/" to """
                    <pre><a href="Dune.2021.1080p/">Dune.2021.1080p/</a> 01-Jan-2024 10:00 -</pre>
                """.trimIndent(),
                "/Movies/Dune.2021.1080p/" to """
                    <pre>
                    <a href="Dune.2021.1080p.WEB-DL.mkv">Dune.2021.1080p.WEB-DL.mkv</a> 01-Jan-2024 10:00 2147483648
                    <a href="cover.jpg">cover.jpg</a> 01-Jan-2024 10:00 50000
                    </pre>
                """.trimIndent(),
            )
        )

        ListingServer(pages).use { server ->
            val scraper = Scraper(OkHttpClient())
            val source = Source(id = 1, name = "LAN", rootUrl = server.url("/"))
            val firstItems = Collections.synchronizedList(mutableListOf<CatalogItem>())

            val firstResult = scraper.syncSource(
                source = source,
                onBatch = { firstItems += it },
            ) as SyncResult.Ok

            assertEquals(2, firstItems.size)
            val ep = firstItems.first { it.kind == "episode" }
            assertEquals("Breaking Bad", ep.title)
            assertEquals(2008, ep.year)
            assertEquals(1, ep.season)
            assertEquals(1, ep.episode)
            assertEquals("series", ep.titleKind)
            assertEquals(server.url("/TV%20Series/Breaking%20Bad%20%282008%29/"), ep.folderUrl)
            assertEquals(server.url("/TV%20Series/Breaking%20Bad%20%282008%29/Season%201/"), ep.dirUrl)
            assertEquals(server.url("/TV%20Series/Breaking%20Bad%20%282008%29/poster.jpg"), ep.posterUrl)

            val movie = firstItems.first { it.kind == "movie" }
            assertEquals("Dune", movie.title)
            assertEquals(2021, movie.year)
            assertEquals(server.url("/Movies/Dune.2021.1080p/cover.jpg"), movie.posterUrl)

            // Add Season 2 without changing the show root's mtime; parent must NOT be pruned.
            pages["/TV%20Series/Breaking%20Bad%20%282008%29/"] = """
                <pre>
                <a href="Season%201/">Season 1/</a> 01-Jan-2024 10:00 -
                <a href="Season%202/">Season 2/</a> 05-Feb-2024 12:00 -
                <a href="poster.jpg">poster.jpg</a> 01-Jan-2024 10:00 45000
                </pre>
            """.trimIndent()
            pages["/TV%20Series/Breaking%20Bad%20%282008%29/Season%202/"] = """
                <pre><a href="Breaking.Bad.S02E01.720p.mkv">Breaking.Bad.S02E01.720p.mkv</a> 05-Feb-2024 12:00 800000000</pre>
            """.trimIndent()

            val secondItems = Collections.synchronizedList(mutableListOf<CatalogItem>())
            val secondResult = scraper.syncSource(
                source = source,
                knownFolders = firstResult.folderMtimes,
                onBatch = { secondItems += it },
            ) as SyncResult.Ok

            assertTrue(server.url("/Movies/Dune.2021.1080p/") in secondResult.prunedFolders)
            assertTrue(server.url("/TV%20Series/Breaking%20Bad%20%282008%29/Season%201/") in secondResult.prunedFolders)
            val ep2 = secondItems.single()
            assertEquals("Breaking Bad", ep2.title)
            assertEquals(2, ep2.season)
            assertEquals(1, ep2.episode)
        }
    }

    private class ListingServer(private val pages: Map<String, String>) : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)

        private val acceptThread = thread(isDaemon = true) {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) {
                    client.use { s ->
                        val input = BufferedInputStream(s.getInputStream())
                        val requestLine = readRequestLine(input) ?: return@use
                        val path = requestLine.split(" ").getOrNull(1) ?: "/"
                        val body = pages[path]
                        val out = s.getOutputStream()
                        if (body != null) {
                            val bytes = body.toByteArray()
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            out.write(header.toByteArray())
                            out.write(bytes)
                        } else {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                        out.flush()
                    }
                }
            }
        }

        fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"

        private fun readRequestLine(input: BufferedInputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return null
                sb.append(b.toChar())
                if (sb.endsWith("\r\n\r\n")) break
            }
            return sb.toString().lineSequence().firstOrNull()
        }

        override fun close() {
            running.set(false)
            runCatching { socket.close() }
            acceptThread.join(1000)
        }
    }
}
