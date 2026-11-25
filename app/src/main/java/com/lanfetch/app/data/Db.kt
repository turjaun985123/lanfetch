package com.lanfetch.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun byId(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE rootUrl = :url LIMIT 1")
    suspend fun byRootUrl(url: String): SourceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(s: SourceEntity): Long

    @Update
    suspend fun update(s: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM sources")
    suspend fun count(): Int
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED', 'RUNNING') ORDER BY createdAt")
    fun observeActive(): Flow<List<DownloadEntity>>

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN ('QUEUED', 'RUNNING')")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun byId(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state = 'QUEUED' ORDER BY createdAt")
    suspend fun queued(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE state IN ('QUEUED', 'RUNNING') ORDER BY createdAt")
    suspend fun active(): List<DownloadEntity>

    @Insert
    suspend fun insert(download: DownloadEntity): Long

    @Update
    suspend fun update(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE downloads SET state = 'PAUSED' WHERE state = 'RUNNING'")
    suspend fun pauseAllRunning()

    @Query("UPDATE downloads SET state = 'QUEUED' WHERE state = 'RUNNING'")
    suspend fun requeueAllRunning()

    @Query("UPDATE downloads SET state = 'PAUSED', error = :reason, updatedAt = :now WHERE state IN ('QUEUED', 'RUNNING')")
    suspend fun pauseAllActive(reason: String?, now: Long)

    @Query("UPDATE downloads SET state = 'PAUSED', error = NULL, updatedAt = :now WHERE id = :id AND state IN ('QUEUED', 'RUNNING')")
    suspend fun pauseIfActive(id: Long, now: Long)

    @Query("UPDATE downloads SET state = 'QUEUED', error = NULL, updatedAt = :now WHERE state = 'PAUSED' AND error LIKE 'Waiting for %'")
    suspend fun requeueWaiting(now: Long): Int

    @Query("UPDATE downloads SET state = :state, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun setState(id: Long, state: DownloadState, error: String?, now: Long)

    @Query(
        "UPDATE downloads SET downloadedBytes = :downloaded, segments = :segments, " +
            "totalBytes = :total, updatedAt = :now WHERE id = :id AND state = 'RUNNING'"
    )
    suspend fun updateProgress(id: Long, downloaded: Long, segments: String, total: Long, now: Long)

    @Query("SELECT * FROM downloads WHERE state = 'COMPLETED' AND url = :url LIMIT 1")
    suspend fun completedByUrl(url: String): DownloadEntity?

    @Query("SELECT url FROM downloads WHERE state = 'COMPLETED'")
    fun observeCompletedUrls(): Flow<List<String>>
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT folderUrl FROM favorites")
    fun observeUrls(): Flow<List<String>>

    @Query("SELECT * FROM favorites WHERE folderUrl = :folderUrl LIMIT 1")
    suspend fun byUrl(folderUrl: String): FavoriteEntity?

    @Upsert
    suspend fun upsert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE folderUrl = :folderUrl")
    suspend fun delete(folderUrl: String)

    @Query("DELETE FROM favorites WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)
}

@Dao
interface LiveCacheDao {
    @Query("SELECT * FROM cached_categories ORDER BY categoryOrder ASC, categoryName ASC")
    suspend fun allCategories(): List<CachedCategoryEntity>

    @Query("DELETE FROM cached_categories")
    suspend fun clearCategories()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(rows: List<CachedCategoryEntity>)

    @Query("DELETE FROM cached_categories WHERE sourceId = :sourceId")
    suspend fun deleteCategoriesForSource(sourceId: Long)

    @Query("SELECT * FROM cached_feed_items WHERE feedKey = :feedKey ORDER BY position ASC")
    suspend fun feedItems(feedKey: String): List<CachedFeedItemEntity>

    @Query("DELETE FROM cached_feed_items WHERE feedKey = :feedKey")
    suspend fun clearFeed(feedKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeedItems(rows: List<CachedFeedItemEntity>)

    @Query("DELETE FROM cached_feed_items WHERE sourceId = :sourceId")
    suspend fun deleteFeedForSource(sourceId: Long)

    @Query("DELETE FROM cached_feed_items")
    suspend fun clearAllFeeds()
}

@Database(
    entities = [
        SourceEntity::class,
        DownloadEntity::class,
        FavoriteEntity::class,
        CachedCategoryEntity::class,
        CachedFeedItemEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun downloads(): DownloadDao
    abstract fun favorites(): FavoriteDao
    abstract fun liveCache(): LiveCacheDao

    companion object {
        fun build(context: Context): AppDb =
            Room.databaseBuilder(context.applicationContext, AppDb::class.java, "lanfetch.db")
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `files`")
                db.execSQL("DROP TABLE IF EXISTS `titles`")
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_categories` (
                      `locationUrl` TEXT NOT NULL,
                      `categoryName` TEXT NOT NULL,
                      `sourceId` INTEGER NOT NULL,
                      `sourceName` TEXT NOT NULL,
                      `sourceRootUrl` TEXT NOT NULL,
                      `categoryOrder` INTEGER NOT NULL,
                      `cachedAt` INTEGER NOT NULL,
                      PRIMARY KEY(`locationUrl`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_categories_categoryOrder_categoryName` ON `cached_categories` (`categoryOrder`, `categoryName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_categories_sourceId` ON `cached_categories` (`sourceId`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_feed_items` (
                      `feedKey` TEXT NOT NULL,
                      `folderUrl` TEXT NOT NULL,
                      `position` INTEGER NOT NULL,
                      `sourceId` INTEGER NOT NULL,
                      `sourceName` TEXT NOT NULL,
                      `category` TEXT NOT NULL,
                      `title` TEXT NOT NULL,
                      `rawTitleName` TEXT NOT NULL,
                      `year` INTEGER,
                      `kind` TEXT NOT NULL,
                      `posterUrl` TEXT,
                      `language` TEXT,
                      `fileCount` INTEGER NOT NULL,
                      `seasonCount` INTEGER NOT NULL,
                      `maxSizeBytes` INTEGER NOT NULL,
                      `bestQuality` TEXT,
                      `bestSourceTag` TEXT,
                      `bestCodec` TEXT,
                      `hasDualAudio` INTEGER NOT NULL,
                      `modified` TEXT,
                      `cachedAt` INTEGER NOT NULL,
                      PRIMARY KEY(`feedKey`, `folderUrl`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_feed_items_feedKey_position` ON `cached_feed_items` (`feedKey`, `position`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_feed_items_sourceId` ON `cached_feed_items` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_feed_items_cachedAt` ON `cached_feed_items` (`cachedAt`)")
            }
        }

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorites` (
                      `folderUrl` TEXT NOT NULL,
                      `sourceId` INTEGER NOT NULL,
                      `sourceName` TEXT NOT NULL,
                      `category` TEXT NOT NULL,
                      `title` TEXT NOT NULL,
                      `rawTitleName` TEXT NOT NULL,
                      `year` INTEGER,
                      `kind` TEXT NOT NULL,
                      `posterUrl` TEXT,
                      `language` TEXT,
                      `fileCount` INTEGER NOT NULL,
                      `seasonCount` INTEGER NOT NULL,
                      `maxSizeBytes` INTEGER NOT NULL,
                      `bestQuality` TEXT,
                      `bestSourceTag` TEXT,
                      `bestCodec` TEXT,
                      `hasDualAudio` INTEGER NOT NULL,
                      `modified` TEXT,
                      `addedAt` INTEGER NOT NULL,
                      PRIMARY KEY(`folderUrl`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorites_addedAt` ON `favorites` (`addedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_favorites_sourceId` ON `favorites` (`sourceId`)")
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `folder_index`")
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloads` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `url` TEXT NOT NULL,
                      `fileName` TEXT NOT NULL,
                      `title` TEXT NOT NULL,
                      `totalBytes` INTEGER NOT NULL,
                      `downloadedBytes` INTEGER NOT NULL,
                      `segments` TEXT NOT NULL,
                      `state` TEXT NOT NULL,
                      `error` TEXT,
                      `savedPath` TEXT,
                      `sha256` TEXT,
                      `etag` TEXT,
                      `lastModified` TEXT,
                      `mediaUri` TEXT,
                      `mimeType` TEXT,
                      `createdAt` INTEGER NOT NULL,
                      `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_downloads_url` ON `downloads` (`url`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_state` ON `downloads` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_createdAt` ON `downloads` (`createdAt`)")
            }
        }
    }
}
