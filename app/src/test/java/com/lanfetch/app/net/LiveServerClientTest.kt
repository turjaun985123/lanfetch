package com.lanfetch.app.net

import com.lanfetch.app.data.Source
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LiveServerClientTest {

    @Test fun discoversCategoriesAndLoadsPagesProgressivelyNewestFirst() = runBlocking {
        val pages = mapOf(
            "/SRV/" to """
                <a href="/SRV/Hindi%20Movies/">Hindi Movies</a>
                <a href="/SRV/PC%20Games/">PC Games</a>
            """.trimIndent(),
            "/SRV/Hindi%20Movies/" to """
                <a href="/SRV/Hindi%20Movies/%282024%29/">(2024)</a>
                <a href="/SRV/Hindi%20Movies/%282026%29/">(2026)</a>
            """.trimIndent(),
            "/SRV/Hindi%20Movies/%282026%29/" to """
                <a href="/SRV/Hindi%20Movies/%282026%29/Alpha%20%282026%29/">Alpha (2026)</a>
                <a href="/SRV/Hindi%20Movies/%282026%29/Beta%20%282026%29/">Beta (2026)</a>
            """.trimIndent(),
            "/SRV/Hindi%20Movies/%282026%29/Alpha%20%282026%29/" to """
                <a href="/SRV/Hindi%20Movies/%282026%29/Alpha%20%282026%29/Alpha.2026.1080p.mkv">Alpha.2026.1080p.mkv</a> 2.0G
                <a href="/SRV/Hindi%20Movies/%282026%29/Alpha%20%282026%29/poster.jpg">poster.jpg</a> 40K
            """.trimIndent(),
            "/SRV/Hindi%20Movies/%282026%29/Beta%20%282026%29/" to """
                <a href="/SRV/Hindi%20Movies/%282026%29/Beta%20%282026%29/Beta.2026.720p.mkv">Beta.2026.720p.mkv</a> 1.1G
            """.trimIndent(),
            "/SRV/Hindi%20Movies/%282024%29/" to """
                <a href="/SRV/Hindi%20Movies/%282024%29/Old%20%282024%29/">Old (2024)</a>
            """.trimIndent(),
            "/SRV/Hindi%20Movies/%282024%29/Old%20%282024%29/" to """
                <a href="/SRV/Hindi%20Movies/%282024%29/Old%20%282024%29/Old.2024.1080p.mkv">Old.2024.1080p.mkv</a> 1.5G
            """.trimIndent(),
        )

        FakeH5aiServer(pages).use { server ->
            val client = LiveServerClient(OkHttpClient())
            val source = Source(id = 1, name = "SRV", rootUrl = server.url("/SRV/"))
            val categories = client.discoverCategories(listOf(source)).last()
            assertEquals(listOf("Hindi Movies", "PC Games"), categories.map { it.name })

            val session = LiveFeedSession(client, categories, selectedCategory = "Hindi Movies")
            val page1 = session.loadNextPage(pageSize = 2)
            assertEquals(2, page1.size)
            assertEquals(listOf(2026, 2026), page1.map { it.year })
            assertTrue(session.hasMore)
            // (2024) folder must NOT be fetched until page 2!
            assertFalse("/SRV/Hindi%20Movies/%282024%29/" in server.requestedPaths)

            val page2 = session.loadNextPage(pageSize = 2)
            assertEquals(1, page2.size)
            assertEquals("Old", page2.single().title)
            assertEquals(2024, page2.single().year)
            assertFalse(session.hasMore)

            // "All" mode skips non-media category (PC Games) and returns titles only from media categories.
            val allSession = LiveFeedSession(client, categories, selectedCategory = null)
            val allPage1 = allSession.loadNextPage(pageSize = 2)
            assertEquals(2, allPage1.size)
            assertTrue(allPage1.all { it.category == "Hindi Movies" })
        }
    }

    @Test fun directH5aiSearchGroupsSeasonsAndMovies() = runBlocking {
        val searchJson = """
            {"search":[
              {"href":"/SRV/Hindi%20Movies/%282024%29/Dune%20%282024%29/Dune.2024.1080p.mkv","time":1,"size":2000000000},
              {"href":"/SRV/TV-WEB-Series/Dark%20%282017%29/Season%201%20%28Sub%29/Dark.S01E01.720p.mkv","time":2,"size":700000000}
            ]}
        """.trimIndent()
        FakeH5aiServer(emptyMap(), searchResponse = searchJson).use { server ->
            val client = LiveServerClient(OkHttpClient())
            val source = Source(id = 1, name = "SRV", rootUrl = server.url("/SRV/"))
            val hits = client.searchDirect(listOf(source), "d").last()
            assertEquals(2, hits.size)
            val movie = hits.first { it.kind == "movie" }
            assertEquals("Dune", movie.title)
            assertEquals("Hindi Movies", movie.category)
            val series = hits.first { it.kind == "series" }
            assertEquals("Dark", series.title)
            assertEquals(server.url("/SRV/TV-WEB-Series/Dark%20%282017%29/"), series.folderUrl)
            assertEquals(1, series.cachedFiles.first().season)

            // Repeated identical search serves from in-memory TTL cache with zero extra requests.
            val reqsAfterFirstSearch = server.requestCount.get()
            val cachedHits = client.searchDirect(listOf(source), "d").last()
            assertEquals(hits.size, cachedHits.size)
            assertEquals(reqsAfterFirstSearch, server.requestCount.get())

            // Clearing caches bypasses cache on subsequent search.
            client.clearAllCaches()
            client.searchDirect(listOf(source), "d").last()
            assertTrue(server.requestCount.get() > reqsAfterFirstSearch)

            // forceRefresh bypasses search TTL cache directly.
            val reqsBeforeForce = server.requestCount.get()
            client.searchDirect(listOf(source), "d", forceRefresh = true).last()
            assertTrue(server.requestCount.get() > reqsBeforeForce)
        }
    }

    @Test fun safeResolveHandlesRawSpacesAndProbeSupportsHeadOr405Fallback() = runBlocking {
        val searchJson = """
            {"search":[
              {"href":"/SRV/Hindi Movies/(2025)/Movie Name (2025)/Movie Name.1080p.mkv","time":1,"size":1500000000}
            ]}
        """.trimIndent()
        FakeH5aiServer(
            pages = mapOf("/SRV/" to "<html>ok</html>"),
            searchResponse = searchJson,
            headStatus = 405,
        ).use { server ->
            val client = LiveServerClient(OkHttpClient())
            val source = Source(id = 7, name = "SRV7", rootUrl = server.url("/SRV/"))

            // 405 on HEAD falls back to GET and marks source reachable.
            val probeOk = client.probeServer(source)
            assertTrue(probeOk.reachable)
            assertTrue(probeOk.latencyMs >= 1L)

            // Unencoded space in h5ai search href resolves cleanly without exception.
            val hits = client.searchDirect(listOf(source), "movie").last()
            assertEquals(1, hits.size)
            assertEquals("Movie Name", hits.single().title)
        }

        // Unreachable closed port populates failure result and temporary cooldown.
        val deadPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val client = LiveServerClient(OkHttpClient())
        val deadSource = Source(id = 99, name = "Dead", rootUrl = "http://127.0.0.1:$deadPort/SRV/")
        val probeDead = client.probeServer(deadSource)
        assertFalse(probeDead.reachable)
        assertTrue(client.isTemporarilyUnreachable(deadSource.id))
    }

    @Test fun cacheServesRepeatedListingsAndInitialEmittedSkipsPage1() = runBlocking {
        val pages = mapOf(
            "/SRV/" to """<a href="/SRV/Hindi%20Movies/">Hindi Movies</a>""",
            "/SRV/Hindi%20Movies/" to """
                <a href="/SRV/Hindi%20Movies/%282026%29/">(2026)</a>
            """.trimIndent(),
            "/SRV/Hindi%20Movies/%282026%29/" to """
                <a href="/SRV/Hindi%20Movies/%282026%29/Alpha%20%282026%29/">Alpha (2026)</a>
                <a href="/SRV/Hindi%20Movies/%282026%29/Beta%20%282026%29/">Beta (2026)</a>
            """.trimIndent(),
            "/SRV/Hindi%20Movies/%282026%29/Alpha%20%282026%29/" to """
                <a href="/SRV/Hindi%20Movies/%282026%29/Alpha%20%282026%29/Alpha.2026.1080p.mkv">Alpha.2026.1080p.mkv</a> 2.0G
            """.trimIndent(),
            "/SRV/Hindi%20Movies/%282026%29/Beta%20%282026%29/" to """
                <a href="/SRV/Hindi%20Movies/%282026%29/Beta%20%282026%29/Beta.2026.720p.mkv">Beta.2026.720p.mkv</a> 1.1G
            """.trimIndent(),
        )
        FakeH5aiServer(pages).use { server ->
            val client = LiveServerClient(OkHttpClient())
            val source = Source(id = 1, name = "SRV", rootUrl = server.url("/SRV/"))
            val categories = client.discoverCategories(listOf(source)).last()
            val afterCat = server.requestCount.get()
            client.discoverCategories(listOf(source)).last()
            assertEquals(afterCat, server.requestCount.get())

            // Resuming with Alpha already cached in Room skips requesting Alpha's folder completely.
            server.requestedPaths.clear()
            val alphaUrl = server.url("/SRV/Hindi%20Movies/%282026%29/Alpha%20%282026%29/")
            val session = LiveFeedSession(
                client = client,
                categories = categories,
                selectedCategory = "Hindi Movies",
                initialEmittedUrls = setOf(alphaUrl),
            )
            val nextPage = session.loadNextPage(pageSize = 2)
            assertEquals(listOf("Beta"), nextPage.map { it.title })
            assertFalse("/SRV/Hindi%20Movies/%282026%29/Alpha%20%282026%29/" in server.requestedPaths)
        }
    }

    private class FakeH5aiServer(
        private val pages: Map<String, String>,
        private val searchResponse: String? = null,
        private val headStatus: Int = 200,
    ) : Closeable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        val requestedPaths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val requestCount = AtomicInteger(0)

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
                        val reqLine = readHeaderBlock(input) ?: return@use
                        val parts = reqLine.split(" ")
                        val method = parts.getOrNull(0) ?: "GET"
                        val path = parts.getOrNull(1) ?: "/"
                        requestedPaths += path
                        requestCount.incrementAndGet()
                        val out = s.getOutputStream()
                        if (method == "HEAD") {
                            out.write("HTTP/1.1 $headStatus Status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else if (method == "POST" && searchResponse != null) {
                            val bytes = searchResponse.toByteArray()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json;charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(bytes)
                        } else {
                            val body = pages[path]
                            if (body != null) {
                                val bytes = body.toByteArray()
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html;charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                out.write(bytes)
                            } else {
                                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            }
                        }
                        out.flush()
                    }
                }
            }
        }

        fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"

        private fun readHeaderBlock(input: BufferedInputStream): String? {
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
