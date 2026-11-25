package com.lanfetch.app.net

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
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
 *
 * <pre> listings put every field on one text line after the anchor. Reading
 * only the text that follows the anchor keeps this O(n) and stops every entry
 * from inheriting the first date/size in the block.
 */
object AutoindexParser {

    private val dateRe = Regex("""\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}|\d{2}-[A-Za-z]{3}-\d{4}\s+\d{2}:\d{2}|\d{2}-[A-Za-z]{3}-\d{4}""")

    fun parse(html: String, baseUrl: String): List<DirEntry> {
        val doc = Jsoup.parse(html, baseUrl)
        val out = LinkedHashMap<String, DirEntry>()
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val baseHost = URI(base).host

        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (href.isBlank() || href.startsWith("?") || href.startsWith("#") ||
                href.startsWith("mailto:") || href == "../" || href.equals("..", true)
            ) continue
            val resolved = try { URI(base).resolve(href) } catch (e: Exception) { continue }
            val abs = resolved.toString()
            // stay on the same host
            if (resolved.host != baseHost) continue
            // must be inside (or equal to a child of) the base directory
            if (!abs.startsWith(base) && abs != base.trimEnd('/')) continue

            val rawPath = resolved.rawPath.orEmpty()
            val rawName = rawPath.trimEnd('/').substringAfterLast('/')
            val decoded = try {
                URLDecoder.decode(rawName.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) { rawName }
            val isDir = rawPath.endsWith("/") || href.endsWith("/")
            val name = decoded.trimEnd('/')
            if (name.isBlank()) continue
            if (name.equals("Parent Directory", true)) continue

            val row = a.closest("tr")
            val pre = a.closest("pre")
            val fromPre = row == null && pre != null
            val siblingCells = row?.select("td, th")?.filter { cell -> a !in cell.select("a") }
            val hasSeparateMetaCells = !siblingCells.isNullOrEmpty()
            val rowText = when {
                hasSeparateMetaCells -> siblingCells!!.joinToString(" ") { it.text() }
                row != null -> row.text()
                fromPre -> textAfterAnchor(a)
                else -> a.parent()?.text()?.takeIf { it.length < 300 }.orEmpty()
            }
            val size = if (isDir) -1 else {
                MetaParser.parseSize(rowText).takeIf { it >= 0 }
                    ?: if (fromPre || hasSeparateMetaCells) MetaParser.parseRawSize(rowText) else -1
            }
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

    /** The rest of the anchor's line, stopping at <br>, <hr>, the next link, or a newline. */
    private fun textAfterAnchor(a: Element): String {
        val sb = StringBuilder()
        var node: Node? = a.nextSibling()
        while (node != null) {
            when {
                node is Element -> {
                    val tag = node.tagName()
                    if (tag == "br" || tag == "hr" || tag == "a") break
                    sb.append(' ').append(node.text())
                }
                node is TextNode -> {
                    val text = node.text()
                    val newline = text.indexOf('\n')
                    if (newline >= 0) {
                        sb.append(text, 0, newline)
                        break
                    }
                    sb.append(text)
                }
                else -> sb.append(' ').append(node.toString())
            }
            node = node.nextSibling()
        }
        return sb.toString()
    }
}
