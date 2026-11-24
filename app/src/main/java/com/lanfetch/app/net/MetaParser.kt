package com.lanfetch.app.net

import com.lanfetch.app.data.CatalogItem

/**
 * Extracts structured metadata from the messy folder / file names used on
 * LAN media servers — the same release-name grammar LanFlix parsed:
 * "Movie.Name.2023.1080p.WEB-DL.x265.Dual.Audio.mkv" and friends.
 */
object MetaParser {

    private val yearRe = Regex("""\b(19\d\d|20\d\d)\b""")
    private val seRe = Regex("""[Ss](\d{1,2})[Ee](\d{1,3})""")
    private val seasonDirRe = Regex("""[Ss]eason\s*(\d{1,2})""")
    private val bracketRe = Regex("""\[[^\]]*\]|\([^)]*\)""")

    val videoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "webm", "m4v", "ts", "flv", "mpg", "mpeg", "3gp"
    )

    fun isVideoFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in videoExtensions

    fun parseQuality(text: String): String? {
        val t = text.lowercase()
        return when {
            "2160p" in t || "4k" in t || "uhd" in t -> "2160p"
            "1080p" in t -> "1080p"
            "720p" in t -> "720p"
            "480p" in t -> "480p"
            "360p" in t -> "360p"
            "hdcam" in t || Regex("""\bcam\b""").containsMatchIn(t) || "hdts" in t -> "CAM"
            else -> null
        }
    }

    fun parseSourceTag(text: String): String? {
        val t = text.lowercase().replace("-", "").replace(".", "")
        return when {
            "remux" in t -> "REMUX"
            "bluray" in t || "bdrip" in t || "brrip" in t -> "BluRay"
            "webdl" in t -> "WEB-DL"
            "webrip" in t -> "WEBRip"
            "hdrip" in t -> "HDRip"
            "dvdrip" in t -> "DVDRip"
            "hdtv" in t -> "HDTV"
            else -> null
        }
    }

    fun parseCodec(text: String): String? {
        val t = text.lowercase()
        return when {
            "x265" in t || "h265" in t || "h.265" in t || "hevc" in t -> "x265"
            "x264" in t || "h264" in t || "h.264" in t || "avc" in t -> "x264"
            "av1" in t -> "AV1"
            else -> null
        }
    }

    fun parseAudio(text: String): String? {
        val t = text.lowercase()
        return when {
            "multi audio" in t || "multi-audio" in t -> "Multi Audio"
            "dual audio" in t || "dual-audio" in t -> "Dual Audio"
            "dubbed" in t || "dubbing" in t -> "Dubbed"
            else -> null
        }
    }

    /** Language/region hint from a category path like "Foreign Language Movies/Korean Language". */
    fun parseLanguage(categoryPath: String): String? {
        val known = listOf(
            "Bangla", "Bengali", "Hindi", "English", "Korean", "Japanese", "Chinese",
            "Tamil", "Telugu", "Malayalam", "Kannada", "Punjabi", "Urdu", "Arabic",
            "French", "German", "Spanish", "Italian", "Russian", "Turkish", "Thai",
            "Indonesian", "Brazilian", "Danish", "Dutch", "Norwegian", "Swedish",
            "Polish", "Iranian", "Pakistani",
        )
        val hay = categoryPath.lowercase()
        return known.firstOrNull { it.lowercase() in hay }
    }

    fun cleanTitle(raw: String): String {
        var t = raw.substringBeforeLast('.', raw) // drop extension if present
        t = t.replace(bracketRe, " ")
        t = t.replace('.', ' ').replace('_', ' ').replace('-', ' ')
        // cut everything from the first release tag onward
        val cutMarkers = listOf(
            "2160p", "1080p", "720p", "480p", "360p", "bluray", "bdrip", "brrip", "web dl",
            "webrip", "hdrip", "dvdrip", "remux", "hdtv", "x264", "x265", "hevc", "h 264",
            "h 265", "dual audio", "multi audio", "aac", "ac3", "dts", "proper", "repack",
        )
        val lower = t.lowercase()
        var cut = t.length
        for (m in cutMarkers) {
            val i = lower.indexOf(m)
            if (i in 1 until cut) cut = i
        }
        t = t.substring(0, cut)
        // drop a trailing year (kept separately)
        t = t.replace(Regex("""\b(19\d\d|20\d\d)\s*$"""), "")
        return t.replace(Regex("""\s+"""), " ").trim().ifEmpty { raw }
    }

    fun parseSize(text: String): Long {
        val m = Regex("""([\d.]+)\s*([KMGT])i?B?\b""", RegexOption.IGNORE_CASE).find(text) ?: return -1
        val value = m.groupValues[1].toDoubleOrNull() ?: return -1
        val mult = when (m.groupValues[2].uppercase()) {
            "K" -> 1_000L
            "M" -> 1_000_000L
            "G" -> 1_000_000_000L
            "T" -> 1_000_000_000_000L
            else -> 1L
        }
        return (value * mult).toLong()
    }

    /** Build a catalog item from a crawled file entry. */
    fun toItem(
        sourceId: Long,
        sourceName: String,
        category: String,
        folderUrl: String,
        folderName: String,
        fileName: String,
        fileUrl: String,
        sizeBytes: Long,
        modified: String?,
        addedAt: Long,
        posterUrl: String? = null,
    ): CatalogItem {
        val haystack = "$folderName $fileName"
        val se = seRe.find(fileName) ?: seRe.find(folderName)
        val seasonDir = seasonDirRe.find(folderName)
        return CatalogItem(
            sourceId = sourceId,
            sourceName = sourceName,
            category = category,
            folderUrl = folderUrl,
            fileUrl = fileUrl,
            fileName = fileName,
            title = cleanTitle(folderName.ifBlank { fileName }),
            year = yearRe.find(haystack)?.groupValues?.get(1)?.toIntOrNull(),
            quality = parseQuality(haystack),
            sourceTag = parseSourceTag(haystack),
            codec = parseCodec(haystack),
            audio = parseAudio(haystack),
            language = parseLanguage(category),
            kind = if (se != null || seasonDir != null) "episode" else "movie",
            season = se?.groupValues?.get(1)?.toIntOrNull() ?: seasonDir?.groupValues?.get(1)?.toIntOrNull(),
            episode = se?.groupValues?.get(2)?.toIntOrNull(),
            posterUrl = posterUrl,
            sizeBytes = sizeBytes,
            modified = modified,
            addedAt = addedAt,
        )
    }
}
