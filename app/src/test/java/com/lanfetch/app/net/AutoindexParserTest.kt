package com.lanfetch.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoindexParserTest {

    private val apache = """
        <html><head><title>Index of /movies</title></head><body>
        <h1>Index of /movies</h1>
        <table>
        <tr><td><a href="?C=N;O=D">Name</a></td></tr>
        <tr><td><a href="/">Parent Directory</a></td><td> - </td></tr>
        <tr><td><a href="Action/">Action</a></td><td align="right">2024-01-02 10:30</td><td> - </td></tr>
        <tr><td><a href="Dune.Part.Two.2024.1080p.mkv">Dune.Part.Two.2024.1080p.mkv</a></td><td align="right">2024-03-01 09:12</td><td align="right">2.1G</td></tr>
        <tr><td><a href="poster.jpg">poster.jpg</a></td><td></td><td align="right">54K</td></tr>
        </table></body></html>
    """.trimIndent()

    private val nginx = """
        <html><head><title>Index of /shows/</title></head><body>
        <h1>Index of /shows/</h1><hr><pre><a href="../">../</a>
        <a href="Dark/">Dark/</a>                                                01-Jan-2024 10:00                   -
        <a href="Dark.S01E01.720p.mkv">Dark.S01E01.720p.mkv</a>               01-Jan-2024 10:00            734003200
        </pre><hr></body></html>
    """.trimIndent()

    @Test fun parsesApacheListing() {
        val entries = AutoindexParser.parse(apache, "http://172.16.50.14/movies/")
        assertEquals(3, entries.size)
        val dir = entries.first { it.name == "Action" }
        assertTrue(dir.isDir)
        val file = entries.first { it.name.startsWith("Dune") }
        assertTrue(!file.isDir)
        assertEquals(2_254_857_830L, file.sizeBytes)
        assertEquals("2024-03-01 09:12", file.modified)
        assertEquals("http://172.16.50.14/movies/Dune.Part.Two.2024.1080p.mkv", file.url)
    }

    @Test fun parsesNginxListing() {
        val entries = AutoindexParser.parse(nginx, "http://172.16.50.12/shows/")
        assertEquals(2, entries.size)
        assertTrue(entries.first { it.name == "Dark" }.isDir)
        val episode = entries.first { it.name.contains("S01E01") }
        assertTrue(!episode.isDir)
        assertEquals(734_003_200L, episode.sizeBytes)
        assertEquals("01-Jan-2024 10:00", episode.modified)
    }

    @Test fun preRowsDoNotInheritTheFirstSize() {
        val html = """
            <html><body><pre>
            <a href="one.mkv">one.mkv</a>  01-Jan-2024 10:00  1048576
            <a href="two.mkv">two.mkv</a>  02-Jan-2024 11:00  2097152
            </pre></body></html>
        """.trimIndent()
        val entries = AutoindexParser.parse(html, "http://x/")
        assertEquals(1_048_576L, entries.first { it.name == "one.mkv" }.sizeBytes)
        assertEquals(2_097_152L, entries.first { it.name == "two.mkv" }.sizeBytes)
    }

    @Test fun stripsQueryFromDisplayNameButKeepsItInUrl() {
        val html = """<a href="movie.mkv?token=abc">movie.mkv?token=abc</a>"""
        val entry = AutoindexParser.parse(html, "http://x/").single()
        assertEquals("movie.mkv", entry.name)
        assertEquals("http://x/movie.mkv?token=abc", entry.url)
    }

    @Test fun tableFilenameContaining4kDoesNotOverrideSizeColumn() {
        val html = """
            <table>
            <tr><td><a href="Dune.4K.2024.mkv">Dune.4K.2024.mkv</a></td><td>2024-03-01 09:12</td><td>2.1G</td></tr>
            </table>
        """.trimIndent()
        val entry = AutoindexParser.parse(html, "http://x/").single()
        assertEquals(2_254_857_830L, entry.sizeBytes)
        assertEquals("2024-03-01 09:12", entry.modified)
    }

    @Test fun nginxPreWithSecondsParsesRawSize() {
        val html = """
            <pre>
            <a href="ep1.mkv">ep1.mkv</a> 01-Jan-2024 10:00:59 734003200
            </pre>
        """.trimIndent()
        val entry = AutoindexParser.parse(html, "http://x/").single()
        assertEquals(734_003_200L, entry.sizeBytes)
        assertEquals("01-Jan-2024 10:00", entry.modified)
    }
}
