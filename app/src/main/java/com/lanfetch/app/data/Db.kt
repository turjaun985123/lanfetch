package com.lanfetch.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val rootUrl: String,
    val enabled: Boolean = true,
    val lastSynced: Long = 0,
    val lastError: String? = null,
    val itemCount: Int = 0,
    val selectedCategories: String = "",
) {
    fun toModel() = Source(
        id, name, rootUrl, enabled, lastSynced, lastError, itemCount,
        selectedCategories = if (selectedCategories.isBlank()) emptySet()
        else selectedCategories.split("\n").filter { it.isNotBlank() }.toSet(),
    )
    companion object {
        fun from(s: Source) = SourceEntity(
            s.id, s.name, s.rootUrl, s.enabled, s.lastSynced, s.lastError, s.itemCount,
            selectedCategories = s.selectedCategories.joinToString("\n"),
        )
    }
}

@Entity(tableName = "items", indices = [Index(value = ["fileUrl"], unique = true), Index(value = ["sourceId"])])
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val sourceName: String,
    val category: String,
    val folderUrl: String,
    val fileUrl: String,
    val fileName: String,
    val title: String,
    val year: Int?,
    val quality: String?,
    val sourceTag: String?,
    val codec: String?,
    val audio: String?,
    val language: String?,
    val kind: String,
    val season: Int?,
    val episode: Int?,
    val posterUrl: String? = null,
    val sizeBytes: Long,
    val modified: String?,
    val favorite: Boolean = false,
    val addedAt: Long = 0,
) {
    fun toModel() = CatalogItem(
        id, sourceId, sourceName, category, folderUrl, fileUrl, fileName, title, year,
        quality, sourceTag, codec, audio, language, kind, season, episode, posterUrl,
        sizeBytes, modified, favorite, addedAt,
    )
    companion object {
        fun from(m: CatalogItem) = ItemEntity(
            m.id, m.sourceId, m.sourceName, m.category, m.folderUrl, m.fileUrl, m.fileName,
            m.title, m.year, m.quality, m.sourceTag, m.codec, m.audio, m.language, m.kind,
            m.season, m.episode, m.posterUrl, m.sizeBytes, m.modified, m.favorite, m.addedAt,
        )
    }
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val title: String,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val segments: String = "",
    val state: String = "QUEUED", // QUEUED RUNNING PAUSED COMPLETED FAILED
    val error: String? = null,
    val savedPath: String? = null,
    val sha256: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Entity(tableName = "folder_index", primaryKeys = ["sourceId", "url"])
data class FolderIndexEntity(
    val sourceId: Long,
    val url: String,
    val modified: String?,
)

@Dao
interface FolderIndexDao {
    @Query("SELECT * FROM folder_index WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: Long): List<FolderIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<FolderIndexEntity>)

    @Query("DELETE FROM folder_index WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Transaction
    suspend fun replaceForSource(sourceId: Long, rows: List<FolderIndexEntity>) {
        deleteForSource(sourceId)
        insertAll(rows)
    }
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY name")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY name")
    suspend fun all(): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: SourceEntity): Long

    @Update
    suspend fun update(s: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM sources")
    suspend fun count(): Int
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: Long): List<ItemEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Query("UPDATE items SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Transaction
    suspend fun replaceForSource(sourceId: Long, items: List<ItemEntity>, favoritesByUrl: Map<String, Boolean>) {
        val merged = items.map { e ->
            val fav = favoritesByUrl[e.fileUrl] ?: false
            if (fav) e.copy(favorite = true) else e
        }
        deleteForSource(sourceId)
        insertAll(merged)
    }
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED','RUNNING','PAUSED','FAILED') ORDER BY createdAt")
    suspend fun resumable(): List<DownloadEntity>

    @Insert
    suspend fun insert(d: DownloadEntity): Long

    @Update
    suspend fun update(d: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE downloads SET state = 'PAUSED' WHERE state = 'RUNNING'")
    suspend fun pauseAllRunning()
}

@Database(
    entities = [SourceEntity::class, ItemEntity::class, DownloadEntity::class, FolderIndexEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun folders(): FolderIndexDao
    abstract fun items(): ItemDao
    abstract fun downloads(): DownloadDao

    companion object {
        fun build(context: Context): AppDb =
            Room.databaseBuilder(context.applicationContext, AppDb::class.java, "lanfetch.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
