package com.lanfetch.app.net

import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder

/** One row of a directory listing. */
data class DirEntry(
    val name: String,
    val url: String,
    val isDir: Boolean,
    val sizeBytes: Long = -1,
    val modified: String? = null,
)

/**
 * Parses HTTP directory listings in the formats these servers actually use:
 * Apache mod_autoindex (fancy table + plain), nginx autoindex (<pre>),
 * and lighttpd / IIS-style simple anchor lists.
 */
object AutoindexParser {

    private val sizeNearRe = Regex("""([\d.]+)\s*([KMGT])\b""")
    private val dateRe = Regex("""\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}|\d{2}-[A-Za-z]{3}-\d{4}\s+\d{2}:\d{2}""")

    fun parse(html: String, baseUrl: String): List<DirEntry> {
        val doc = Jsoup.parse(html, baseUrl)
        val out = LinkedHashMap<String, DirEntry>()
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (href.isBlank() || href.startsWith("?") || href.startsWith("#") ||
                href.startsWith("mailto:") || href == "../" || href.equals("..", true)
            ) continue
            val abs = try { URI(base).resolve(href).toString() } catch (e: Exception) { continue }
            // stay on the same host
            if (URI(abs).host != URI(base).host) continue
            // must be inside (or equal to a child of) the base directory
            if (!abs.startsWith(base) && abs != base.trimEnd('/')) continue

            val decoded = try {
                URLDecoder.decode(abs.substringAfterLast('/', abs), "UTF-8")
            } catch (e: Exception) { abs.substringAfterLast('/') }
            val isDir = href.endsWith("/") || abs.endsWith("/")
            val name = decoded.trimEnd('/').ifBlank { continue }
            if (name.equals("Parent Directory", true)) continue

            // size / date usually live in the same table row or the same <pre> line
            val rowText = a.parent()?.text().orEmpty()
            val size = if (isDir) -1 else (MetaParser.parseSize(rowText).takeIf { it >= 0 }
                ?: sizeNearRe.find(rowText)?.let { MetaParser.parseSize(it.value) } ?: -1)
            val modified = dateRe.find(rowText)?.value

            out[abs] = DirEntry(
                name = name,
                url = abs,
                isDir = isDir,
                sizeBytes = size,
                modified = modified,
            )
        }
        return out.values.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }
}
