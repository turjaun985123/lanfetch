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
        assertEquals(2_100_000_000L, file.sizeBytes)
        assertEquals("http://172.16.50.14/movies/Dune.Part.Two.2024.1080p.mkv", file.url)
    }

    @Test fun parsesNginxListing() {
        val entries = AutoindexParser.parse(nginx, "http://172.16.50.12/shows/")
        assertEquals(2, entries.size)
        assertTrue(entries.first { it.name == "Dark" }.isDir)
        val ep = entries.first { it.name.contains("S01E01") }
        assertTrue(!ep.isDir)
    }
}
