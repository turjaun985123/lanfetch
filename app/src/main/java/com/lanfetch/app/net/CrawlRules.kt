package com.lanfetch.app.net

/** What the previous sync learned about a folder. */
data class FolderMeta(
    val modified: String?,
    val isLeaf: Boolean,
    val hasVideos: Boolean,
)

/**
 * Rules that keep the crawl away from the folder explosion on real servers:
 * junk folders are never entered, and once a folder actually contains video
 * files (a title folder) we only go deeper into season-like subfolders.
 * Pure + unit-tested.
 */
object CrawlRules {

    private val junkNames = setOf(
        "sample", "samples", "trailer", "trailers", "proof", "proofs",
        "subs", "subtitles", "subtitle", "screenshot", "screenshots",
        "cover", "covers", "poster", "posters", "artwork", "extras",
        "@eadir", "#recycle", "#snapshot", ".trash", "deleted",
        "sample video", "sample videos", "behind the scenes",
    )

    private val seasonRe = Regex(
        """^(season[\s._-]*\d{1,2}|s\d{1,2}([\s._-]*-\s*\d{1,2})?|complete[\s._-]*series|disc[\s._-]*\d{1,2}|volume[\s._-]*\d{1,2}|vol[\s._-]*\d{1,2})$""",
        RegexOption.IGNORE_CASE,
    )

    fun isJunkFolder(name: String): Boolean =
        name.trim().lowercase() in junkNames || name.startsWith(".")

    fun isSeasonLike(name: String): Boolean = seasonRe.matches(name.trim())

    /**
     * Should the crawler enter [childName] from a folder at [parentDepth]
     * that contains [parentHasVideos] direct video files?  [parentIsTitle]
     * also covers show roots that hold only season folders.
     */
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

    /**
     * A folder is prunable only when it was a leaf last time. A parent folder's
     * mtime does not change when a file is added inside a grandchild, so
     * pruning a non-leaf would miss new episodes.
     */
    fun shouldPrune(isRoot: Boolean, known: FolderMeta?, currentModified: String?): Boolean =
        !isRoot &&
            known != null &&
            known.isLeaf &&
            known.modified != null &&
            currentModified != null &&
            known.modified == currentModified
}
