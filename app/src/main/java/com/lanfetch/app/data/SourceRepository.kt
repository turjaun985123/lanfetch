package com.lanfetch.app.data

import androidx.room.withTransaction
import com.lanfetch.app.net.SeedSources
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SourceRepository(private val db: AppDb) {
    fun observeAll(): Flow<List<Source>> =
        db.sources().observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun all(): List<Source> = db.sources().all().map { it.toModel() }

    suspend fun byId(id: Long): Source? = db.sources().byId(id)?.toModel()

    suspend fun seedDefaults() {
        db.withTransaction {
            // Remove the retired bundled server from existing installations.
            val retired = db.sources().byRootUrl("http://172.16.50.9/DHAKA-FLIX-9/")
            if (retired?.name == "DHAKA-FLIX-9") {
                db.favorites().deleteForSource(retired.id)
                db.liveCache().deleteCategoriesForSource(retired.id)
                db.liveCache().deleteFeedForSource(retired.id)
                db.sources().delete(retired.id)
            }
            if (db.sources().count() > 0) return@withTransaction
            SeedSources.defaults.forEach { db.sources().insert(SourceEntity.from(it)) }
        }
    }

    suspend fun add(name: String, rawUrl: String): Long {
        val clean = normalizeUrl(rawUrl)
        val existing = db.sources().byRootUrl(clean)
        if (existing != null) return existing.id
        return db.sources().insert(
            SourceEntity(name = name.trim().ifBlank { clean }, rootUrl = clean)
        )
    }

    suspend fun update(source: Source) {
        db.sources().update(SourceEntity.from(source))
    }

    suspend fun setEnabled(source: Source, enabled: Boolean) {
        update(source.copy(enabled = enabled))
        db.liveCache().clearAllFeeds()
    }

    suspend fun delete(sourceId: Long) {
        db.withTransaction {
            db.favorites().deleteForSource(sourceId)
            db.liveCache().deleteCategoriesForSource(sourceId)
            db.liveCache().deleteFeedForSource(sourceId)
            db.sources().delete(sourceId)
        }
    }

    companion object {
        fun normalizeUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        }
    }
}
