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
            if (db.sources().count() > 0) return@withTransaction
            SeedSources.defaults.forEach { db.sources().insert(SourceEntity.from(it)) }
        }
    }

    suspend fun add(name: String, rawUrl: String): Long {
        val clean = if (rawUrl.endsWith("/")) rawUrl else "$rawUrl/"
        val existing = db.sources().byRootUrl(clean)
        if (existing != null) return existing.id
        return db.sources().insert(
            SourceEntity(name = name.ifBlank { clean }, rootUrl = clean)
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
            db.files().deleteForSource(sourceId)
            db.titles().deleteForSource(sourceId)
            db.sources().delete(sourceId)
        }
    }
}
