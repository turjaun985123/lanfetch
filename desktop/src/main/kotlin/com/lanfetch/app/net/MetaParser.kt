package com.lanfetch.app.net

import com.lanfetch.app.data.CatalogItem

/**
 * Extracts structured metadata from the messy folder / file names used on
 * LAN media servers — the same release-name grammar LanFlix parsed:
 * "Movie.Name.2023.1080p.WEB-DL.x265.Dual.Audio.mkv" and friends.
 */
object MetaParser {

    private val yearRe = Regex("""\b(19\d\d|20\d\d)\b""")
    private val bracketYearRe = Regex("""[\[(]((?:19|20)\d{2})[\])]""")
    private val seRe = Regex("""[Ss](\d{1,2})[Ee](\d{1,3})""")
    private val epOnlyRe = Regex("""(?i)\b(?:ep(?:isode)?|e)[ ._-]?(\d{1,3})\b""")
    private val seasonDirRe = Regex("""(?:season|s)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
    private val bracketRe = Regex("""\[[^\]]*\]|\([^)]*\)""")

    /**
     * Release tags that end the title. Word boundaries matter: "Isaac" must
     * not be cut at "aac", and "Improper" must not be cut at "proper".
     */
    private val cutMarkerRe = Regex(
        """(?i)\b(?:2160p|1080p|720p|480p|360p|hdcam|hdts|bluray|bdrip|brrip|web[ ._-]?dl|webrip|hdrip|dvdrip|remux|hdtv|x264|x265|h[ .]?264|h[ .]?265|hevc|av1|dual[ ._-]?audio|multi[ ._-]?audio|dubbed|aac|ac3|eac3|dts|proper|repack)\b"""
    )

    private val seasonSuffixRe = Regex(
        """(?i)\b(?:season\s*\d{1,2}|s\d{1,2}|complete\s+series|disc\s*\d{1,2}|vol(?:ume)?\s*\d{1,2})\b.*$"""
    )

    val videoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "webm", "m4v", "ts", "flv", "mpg", "mpeg", "3gp"
    )

    fun isVideoFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in videoExtensions

    fun parseQuality(text: String): String? {
        val t = text.lowercase()
        return when {
            // Audit B13: token boundaries, so names merely containing the
            // letters (e.g. "Sh4ke", "uhd" inside a word) are not misread.
            "2160p" in t || Regex("""(?<![a-z0-9])(4k|uhd)(?![a-z0-9])""").containsMatchIn(t) -> "2160p"
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
            "x264" in t || "h264" in t || "h.264" in t ||
                Regex("""(?<![a-z0-9])avc(?![a-z0-9])""").containsMatchIn(t) -> "x264"
            Regex("""(?<![a-z0-9])av1(?![a-z0-9])""").containsMatchIn(t) -> "AV1"
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

    /** Bracketed year wins; otherwise the last year in the name ("Wonder Woman 1984 (2020)"). */
    fun parseYear(text: String): Int? {
        bracketYearRe.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return yearRe.findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    }

    fun parseSeason(text: String): Int? =
        seasonDirRe.find(text)?.groupValues?.get(1)?.toIntOrNull()

    fun cleanTitle(raw: String): String {
        var t = if (isVideoFile(raw)) raw.substringBeforeLast('.') else raw
        t = t.replace(bracketRe, " ")
        t = t.replace('.', ' ').replace('_', ' ').replace('-', ' ')

        cutMarkerRe.find(t)?.let { t = t.substring(0, it.range.first) }
        t = t.replace(seasonSuffixRe, " ")
        t = t.replace(Regex("""\b(19\d\d|20\d\d)\s*$"""), "")
        return t.replace(Regex("""\s+"""), " ").trim().ifEmpty { raw.trim() }
    }

    fun parseSize(text: String): Long {
        val m = Regex("""([\d.]+)\s*([KMGT])i?B?\b""", RegexOption.IGNORE_CASE).find(text) ?: return -1
        val value = m.groupValues[1].toDoubleOrNull() ?: return -1
        val mult = when (m.groupValues[2].uppercase()) {
            "K" -> 1_024L
            "M" -> 1_024L * 1_024
            "G" -> 1_024L * 1_024 * 1_024
            "T" -> 1_024L * 1_024 * 1_024 * 1_024
            else -> 1L
        }
        return (value * mult).toLong()
    }

    /** nginx autoindex prints raw byte counts with no unit. */
    fun parseRawSize(text: String): Long {
        val cleaned = text.replace(
            Regex("""\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(?::\d{2})?|\d{2}-[A-Za-z]{3}-\d{4}\s+\d{2}:\d{2}(?::\d{2})?"""),
            " ",
        )
        return Regex("""(?<!:)\b(\d{2,})\b""").findAll(cleaned).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: -1
    }

    /** Build a catalog item from a crawled file entry. */
    fun buildItem(
        sourceId: Long,
        sourceName: String,
        category: String,
        titleFolderUrl: String,
        dirUrl: String,
        titleName: String,
        fileName: String,
        fileUrl: String,
        sizeBytes: Long,
        modified: String?,
        addedAt: Long,
        posterUrl: String? = null,
        isSeries: Boolean = false,
        folderSeason: Int? = null,
    ): CatalogItem {
        val haystack = "$titleName $fileName"
        val se = seRe.find(fileName) ?: seRe.find(titleName)
        val season = folderSeason ?: se?.groupValues?.get(1)?.toIntOrNull()
            ?: if (isSeries) parseSeason(titleName) else null
        val episode = se?.groupValues?.get(2)?.toIntOrNull()
            ?: if (isSeries || season != null) {
                epOnlyRe.find(fileName)?.groupValues?.get(1)?.toIntOrNull()
            } else null
        val kind = if (se != null || season != null || episode != null) "episode" else "movie"
        return CatalogItem(
            sourceId = sourceId,
            sourceName = sourceName,
            category = category,
            folderUrl = titleFolderUrl,
            dirUrl = dirUrl,
            fileName = fileName,
            fileUrl = fileUrl,
            title = cleanTitle(titleName),
            titleKind = if (isSeries || kind == "episode") "series" else "movie",
            year = parseYear(haystack),
            quality = parseQuality(haystack),
            sourceTag = parseSourceTag(haystack),
            codec = parseCodec(haystack),
            audio = parseAudio(haystack),
            language = parseLanguage(category),
            kind = kind,
            season = season,
            episode = episode,
            posterUrl = posterUrl,
            sizeBytes = sizeBytes,
            modified = modified,
            addedAt = addedAt,
        )
    }
}
