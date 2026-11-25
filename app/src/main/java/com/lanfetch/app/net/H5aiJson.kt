package com.lanfetch.app.net

/** Raw entry returned by h5ai's JSON POST API (`?`, action=get). */
data class H5aiItem(
    val href: String,
    val timeMillis: Long,
    val sizeBytes: Long,
) {
    val isDir: Boolean get() = href.endsWith("/")
}

/**
 * Dependency-free JSON builder & parser for h5ai's `search` and `items` APIs.
 * Kept pure Kotlin so it works identically in Android runtime and JVM unit tests.
 */
object H5aiJson {
    private const val MAX_JSON_CHARS = 2_000_000
    private const val MAX_DEPTH = 16

    fun buildSearchRequest(href: String, pattern: String): String =
        """{"action":"get","search":{"href":"${escape(href)}","pattern":"${escape(pattern)}","ignorecase":true}}"""

    fun buildItemsRequest(href: String): String =
        """{"action":"get","items":{"href":"${escape(href)}","what":1}}"""

    fun parseSearch(json: String): List<H5aiItem>? = parseArrayField(json, "search")

    fun parseItems(json: String): List<H5aiItem>? = parseArrayField(json, "items")

    private fun escape(value: String): String = buildString(value.length + 8) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                in '\u0000'..'\u001F' -> append("\\u00").append(ch.code.toString(16).padStart(2, '0'))
                else -> append(ch)
            }
        }
    }

    private fun parseArrayField(json: String, field: String): List<H5aiItem>? {
        if (json.length > MAX_JSON_CHARS) return null
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching {
            val parser = Parser(trimmed)
            val root = parser.readValue() as? Map<*, *> ?: return null
            val rawList = root[field] as? List<*> ?: return null
            val out = ArrayList<H5aiItem>(rawList.size)
            for (entry in rawList) {
                val obj = entry as? Map<*, *> ?: continue
                val href = obj["href"] as? String ?: continue
                val time = (obj["time"] as? Number)?.toLong() ?: 0L
                val size = (obj["size"] as? Number)?.toLong() ?: -1L
                out += H5aiItem(href = href, timeMillis = time, sizeBytes = size)
            }
            out
        }.getOrNull()
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun readValue(depth: Int = 0): Any? {
            require(depth <= MAX_DEPTH) { "JSON nesting exceeds $MAX_DEPTH levels" }
            skipWs()
            if (pos >= s.length) return null
            return when (s[pos]) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                '-', in '0'..'9' -> readNumber()
                else -> null
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (pos < s.length) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') break
                val key = readString()
                skipWs()
                if (pos < s.length && s[pos] == ':') pos++
                val value = readValue(depth)
                map[key] = value
                skipWs()
                if (pos < s.length && s[pos] == ',') {
                    pos++
                    continue
                }
                if (pos < s.length && s[pos] == '}') {
                    pos++
                    break
                }
                break
            }
            return map
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (pos < s.length) {
                list += readValue(depth)
                skipWs()
                if (pos < s.length && s[pos] == ',') {
                    pos++
                    continue
                }
                if (pos < s.length && s[pos] == ']') {
                    pos++
                    break
                }
                break
            }
            return list
        }

        private fun readString(): String {
            pos++ // opening '"'
            val sb = StringBuilder()
            while (pos < s.length) {
                val ch = s[pos++]
                if (ch == '"') return sb.toString()
                if (ch == '\\' && pos < s.length) {
                    val esc = s[pos++]
                    when (esc) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> if (pos + 4 <= s.length) {
                            val code = s.substring(pos, pos + 4).toIntOrNull(16)
                            if (code != null) sb.append(code.toChar())
                            pos += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }

        private fun readNumber(): Number? {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isDouble = false
            if (pos < s.length && s[pos] == '.') {
                isDouble = true
                pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val raw = s.substring(start, pos)
            return if (isDouble) raw.toDoubleOrNull() else raw.toLongOrNull()
        }

        private fun readBoolean(): Boolean? {
            return if (s.startsWith("true", pos)) {
                pos += 4; true
            } else if (s.startsWith("false", pos)) {
                pos += 5; false
            } else null
        }

        private fun readNull(): Any? {
            if (s.startsWith("null", pos)) pos += 4
            return null
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
    }
}
