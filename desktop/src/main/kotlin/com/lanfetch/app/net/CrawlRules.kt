package com.lanfetch.app.net

/**
 * Rules that keep folder browsing/traversal away from junk subfolders and
 * recognize season subfolders cleanly (including DhakaFlix variants like
 * "Season 1 (Part-1)", "Season 1 (Hindi Language)", "Season ALL").
 */
object CrawlRules {

    private val junkNames = setOf(
        "sample", "samples", "trailer", "trailers", "proof", "proofs",
        "subs", "subtitles", "subtitle", "screenshot", "screenshots",
        "cover", "covers", "poster", "posters", "artwork", "extras",
        "@eadir", "#recycle", "#snapshot", ".trash", "deleted",
        "sample video", "sample videos", "behind the scenes",
    )

    private val nonMediaCategories = setOf(
        "android games", "console games", "pc games", "software", "games",
    )

    private val seasonRe = Regex(
        """^(?:season[\s._-]*(?:\d{1,2}(?:\.\d+)?|all)\b.*|s\d{1,2}\b(?:\s*[-&]\s*s?\d{1,2}\b)?(?:\s*[\(\[-].*)?|complete[\s._-]*series\b.*|disc[\s._-]*\d{1,2}\b.*|vol(?:ume)?[\s._-]*\d{1,2}\b.*)$""",
        RegexOption.IGNORE_CASE,
    )

    fun isJunkFolder(name: String): Boolean =
        name.trim().lowercase() in junkNames || name.startsWith(".")

    fun isNonMediaCategory(name: String): Boolean =
        name.trim().lowercase() in nonMediaCategories

    fun isSeasonLike(name: String): Boolean = seasonRe.matches(name.trim())

    fun shouldDescend(
        parentDepth: Int,
        parentHasVideos: Boolean,
        childName: String,
        maxDepth: Int,
        parentIsTitle: Boolean = parentHasVideos,
    ): Boolean {
        if (parentDepth >= maxDepth) return false
        if (isJunkFolder(childName)) return false
        if (parentIsTitle && !isSeasonLike(childName)) return false
        return true
    }
}
