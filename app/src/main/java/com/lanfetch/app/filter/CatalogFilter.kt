package com.lanfetch.app.filter

enum class SortBy { TITLE, YEAR_DESC, SIZE_DESC, NEWEST, SIZE_ASC }

data class CatalogFilter(
    val query: String = "",
    val kinds: Set<String> = emptySet(),       // movie / series
    val categories: Set<String> = emptySet(),
    val sourceNames: Set<String> = emptySet(),
    val qualities: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
    val sourceTags: Set<String> = emptySet(),
    val yearMin: Int? = null,
    val yearMax: Int? = null,
    val minSizeBytes: Long? = null,
    val favoritesOnly: Boolean = false,
    val dualAudioOnly: Boolean = false,
    val sort: SortBy = SortBy.TITLE,
) {
    val activeCount: Int
        get() = kinds.size + categories.size + sourceNames.size + qualities.size +
            languages.size + sourceTags.size +
            (if (yearMin != null || yearMax != null) 1 else 0) +
            (if (minSizeBytes != null) 1 else 0) +
            (if (favoritesOnly) 1 else 0) + (if (dualAudioOnly) 1 else 0)
}

/** A SQL statement plus its bind arguments, kept free of Android classes so it is unit-testable. */
data class QuerySpec(val sql: String, val args: List<Any?>)

/**
 * Builds the paged library query. Filtering, searching and sorting all happen
 * in SQLite; the UI only ever receives one page of title rows.
 */
object CatalogQuery {

    fun build(f: CatalogFilter): QuerySpec {
        val where = mutableListOf("1 = 1")
        val args = mutableListOf<Any?>()

        val query = f.query.trim()
        if (query.isNotEmpty()) {
            val pattern = "%${escapeLike(query.lowercase())}%"
            where += """(t.searchText LIKE ? ESCAPE '\' OR EXISTS (
                SELECT 1 FROM files f
                WHERE f.titleId = t.id AND f.searchText LIKE ? ESCAPE '\'
            ))"""
            args += pattern
            args += pattern
        }
        addIn(where, args, "t.kind", f.kinds)
        addIn(where, args, "t.category", f.categories)
        addIn(where, args, "t.sourceName", f.sourceNames)
        addIn(where, args, "t.bestQuality", f.qualities)
        addIn(where, args, "t.language", f.languages)
        addIn(where, args, "t.bestSourceTag", f.sourceTags)
        f.yearMin?.let { where += "t.year >= ?"; args += it }
        f.yearMax?.let { where += "t.year <= ?"; args += it }
        f.minSizeBytes?.let { where += "t.maxSizeBytes >= ?"; args += it }
        if (f.favoritesOnly) where += "t.favorite = 1"
        if (f.dualAudioOnly) where += "t.hasDualAudio = 1"

        val order = when (f.sort) {
            SortBy.TITLE -> "t.title COLLATE NOCASE ASC, t.id ASC"
            SortBy.YEAR_DESC -> "t.year IS NULL ASC, t.year DESC, t.title COLLATE NOCASE ASC"
            SortBy.SIZE_DESC -> "t.maxSizeBytes DESC, t.title COLLATE NOCASE ASC"
            SortBy.SIZE_ASC -> "CASE WHEN t.maxSizeBytes < 0 THEN 1 ELSE 0 END, t.maxSizeBytes ASC, t.title COLLATE NOCASE ASC"
            SortBy.NEWEST -> "t.addedAt DESC, t.id DESC"
        }

        val sql = buildString {
            append("SELECT t.* FROM titles t WHERE ")
            append(where.joinToString(" AND "))
            append(" ORDER BY ")
            append(order)
        }
        return QuerySpec(sql, args)
    }

    private fun addIn(where: MutableList<String>, args: MutableList<Any?>, column: String, values: Set<String>) {
        if (values.isEmpty()) return
        val placeholders = values.joinToString(", ") { "?" }
        where += "$column IN ($placeholders)"
        args.addAll(values)
    }

    fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
