package com.lanfetch.app.net

/** Picks the highest-signal cover/poster image inside a directory listing. */
object PosterPicker {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    fun pickPoster(entries: List<DirEntry>): String? {
        fun score(name: String): Int {
            val n = name.lowercase()
            return when {
                "poster" in n -> 5
                "folder" in n -> 4
                "cover" in n -> 4
                "fanart" in n -> 3
                "backdrop" in n -> 2
                else -> 1
            }
        }
        return entries
            .filter { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in imageExtensions }
            .maxByOrNull { score(it.name) }
            ?.url
    }
}
