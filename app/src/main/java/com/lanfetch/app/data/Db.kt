package com.lanfetch.app.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
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
interface TitleDao {
    @RawQuery(observedEntities = [TitleEntity::class, FileEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, TitleEntity>

    @Query("SELECT * FROM titles WHERE id = :id")
    fun observe(id: Long): Flow<TitleEntity?>

    @Query("SELECT * FROM titles WHERE id = :id")
    suspend fun byId(id: Long): TitleEntity?

    @Query("SELECT * FROM titles WHERE folderUrl = :url LIMIT 1")
    suspend fun byFolderUrl(url: String): TitleEntity?

    @Insert
    suspend fun insert(title: TitleEntity): Long

    @Update
    suspend fun update(title: TitleEntity)

    @Query("UPDATE titles SET favorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, now: Long)

    @Query("DELETE FROM titles WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Query("DELETE FROM titles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM titles")
    fun titleCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(fileCount), 0) FROM titles")
    fun fileCount(): Flow<Long>

    @Query("SELECT * FROM titles WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: Long): List<TitleEntity>

    @Query("SELECT DISTINCT category FROM titles WHERE category <> '' ORDER BY category COLLATE NOCASE")
    fun categories(): Flow<List<String>>

    @Query("SELECT DISTINCT sourceName FROM titles WHERE sourceName <> '' ORDER BY sourceName COLLATE NOCASE")
    fun sourceNames(): Flow<List<String>>

    @Query("SELECT DISTINCT bestQuality FROM titles WHERE bestQuality IS NOT NULL")
    fun qualities(): Flow<List<String>>

    @Query("SELECT DISTINCT language FROM titles WHERE language IS NOT NULL ORDER BY language COLLATE NOCASE")
    fun languages(): Flow<List<String>>

    @Query("SELECT DISTINCT bestSourceTag FROM titles WHERE bestSourceTag IS NOT NULL ORDER BY bestSourceTag COLLATE NOCASE")
    fun sourceTags(): Flow<List<String>>

    @Query("SELECT DISTINCT year FROM titles WHERE year IS NOT NULL ORDER BY year")
    fun years(): Flow<List<Int>>

    /** Rebuilds the denormalised card fields from the file rows. Cheap enough after a sync. */
    @Query(
        """
        UPDATE titles SET
          kind = CASE WHEN EXISTS (
              SELECT 1 FROM files f WHERE f.titleId = titles.id AND (f.season IS NOT NULL OR f.episode IS NOT NULL)
          ) THEN 'series' ELSE 'movie' END,
          fileCount = (SELECT COUNT(*) FROM files f WHERE f.titleId = titles.id),
          totalSizeBytes = (SELECT COALESCE(SUM(f.sizeBytes), 0) FROM files f WHERE f.titleId = titles.id),
          maxSizeBytes = (SELECT COALESCE(MAX(f.sizeBytes), 0) FROM files f WHERE f.titleId = titles.id),
          bestQuality = (
              SELECT f.quality FROM files f WHERE f.titleId = titles.id AND f.quality IS NOT NULL
              ORDER BY CASE f.quality
                WHEN '2160p' THEN 0 WHEN '1080p' THEN 1 WHEN '720p' THEN 2
                WHEN '480p' THEN 3 WHEN '360p' THEN 4 WHEN 'CAM' THEN 5 ELSE 9 END
              LIMIT 1
          ),
          bestSourceTag = (SELECT f.sourceTag FROM files f WHERE f.titleId = titles.id AND f.sourceTag IS NOT NULL LIMIT 1),
          bestCodec = (SELECT f.codec FROM files f WHERE f.titleId = titles.id AND f.codec IS NOT NULL LIMIT 1),
          hasDualAudio = EXISTS (SELECT 1 FROM files f WHERE f.titleId = titles.id AND f.audio IS NOT NULL),
          downloadedCount = (
              SELECT COUNT(*) FROM files f JOIN downloads d ON d.url = f.fileUrl
              WHERE f.titleId = titles.id AND d.state = 'COMPLETED'
          ),
          searchText = lower(title || ' ' || category || ' ' || sourceName),
          updatedAt = :now
        WHERE sourceId = :sourceId
        """
    )
    suspend fun recomputeForSource(sourceId: Long, now: Long)

    @Query(
        """
        UPDATE titles SET
          kind = CASE WHEN EXISTS (
              SELECT 1 FROM files f WHERE f.titleId = titles.id AND (f.season IS NOT NULL OR f.episode IS NOT NULL)
          ) THEN 'series' ELSE 'movie' END,
          fileCount = (SELECT COUNT(*) FROM files f WHERE f.titleId = titles.id),
          totalSizeBytes = (SELECT COALESCE(SUM(f.sizeBytes), 0) FROM files f WHERE f.titleId = titles.id),
          maxSizeBytes = (SELECT COALESCE(MAX(f.sizeBytes), 0) FROM files f WHERE f.titleId = titles.id),
          bestQuality = (
              SELECT f.quality FROM files f WHERE f.titleId = titles.id AND f.quality IS NOT NULL
              ORDER BY CASE f.quality
                WHEN '2160p' THEN 0 WHEN '1080p' THEN 1 WHEN '720p' THEN 2
                WHEN '480p' THEN 3 WHEN '360p' THEN 4 WHEN 'CAM' THEN 5 ELSE 9 END
              LIMIT 1
          ),
          bestSourceTag = (SELECT f.sourceTag FROM files f WHERE f.titleId = titles.id AND f.sourceTag IS NOT NULL LIMIT 1),
          bestCodec = (SELECT f.codec FROM files f WHERE f.titleId = titles.id AND f.codec IS NOT NULL LIMIT 1),
          hasDualAudio = EXISTS (SELECT 1 FROM files f WHERE f.titleId = titles.id AND f.audio IS NOT NULL),
          downloadedCount = (
              SELECT COUNT(*) FROM files f JOIN downloads d ON d.url = f.fileUrl
              WHERE f.titleId = titles.id AND d.state = 'COMPLETED'
          ),
          updatedAt = :now
        WHERE id IN (:ids)
        """
    )
    suspend fun recomputeForTitles(ids: List<Long>, now: Long)

    @Query(
        """
        UPDATE titles SET downloadedCount = (
          SELECT COUNT(*) FROM files f JOIN downloads d ON d.url = f.fileUrl
          WHERE f.titleId = titles.id AND d.state = 'COMPLETED'
        )
        """
    )
    suspend fun recomputeDownloadedCounts()
}

@Dao
interface FileDao {
    @Query(
        "SELECT * FROM files WHERE titleId = :titleId " +
            "ORDER BY season IS NULL, season, episode IS NULL, episode, fileName COLLATE NOCASE"
    )
    fun observeForTitle(titleId: Long): Flow<List<FileEntity>>

    @Query(
        "SELECT * FROM files WHERE titleId = :titleId " +
            "ORDER BY season IS NULL, season, episode IS NULL, episode, fileName COLLATE NOCASE"
    )
    suspend fun forTitle(titleId: Long): List<FileEntity>

    @Upsert
    suspend fun upsertAll(files: List<FileEntity>)

    @Query("SELECT * FROM files WHERE fileUrl = :url LIMIT 1")
    suspend fun byUrl(url: String): FileEntity?

    @Query("DELETE FROM files WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Query("DELETE FROM files WHERE titleId = :titleId")
    suspend fun deleteForTitle(titleId: Long)

    @Query("SELECT COUNT(*) FROM files WHERE titleId = :titleId")
    suspend fun countForTitle(titleId: Long): Int

    @Query("SELECT COUNT(*) FROM files WHERE sourceId = :sourceId")
    suspend fun countForSource(sourceId: Long): Int
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
interface FolderIndexDao {
    @Query("SELECT * FROM folder_index WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: Long): List<FolderIndexEntity>

    @Upsert
    suspend fun upsertAll(rows: List<FolderIndexEntity>)

    @Query("DELETE FROM folder_index WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @androidx.room.Transaction
    suspend fun replaceForSource(sourceId: Long, rows: List<FolderIndexEntity>) {
        deleteForSource(sourceId)
        upsertAll(rows)
    }
}

@Database(
    entities = [
        SourceEntity::class,
        TitleEntity::class,
        FileEntity::class,
        DownloadEntity::class,
        FolderIndexEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDb : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun titles(): TitleDao
    abstract fun files(): FileDao
    abstract fun downloads(): DownloadDao
    abstract fun folders(): FolderIndexDao

    companion object {
        fun build(context: Context): AppDb =
            Room.databaseBuilder(context.applicationContext, AppDb::class.java, "lanfetch.db")
                .addMigrations(MIGRATION_3_4)
                .build()

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `titles` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `sourceId` INTEGER NOT NULL,
                      `sourceName` TEXT NOT NULL,
                      `category` TEXT NOT NULL,
                      `folderUrl` TEXT NOT NULL,
                      `title` TEXT NOT NULL,
                      `year` INTEGER,
                      `kind` TEXT NOT NULL,
                      `posterUrl` TEXT,
                      `favorite` INTEGER NOT NULL,
                      `language` TEXT,
                      `fileCount` INTEGER NOT NULL,
                      `totalSizeBytes` INTEGER NOT NULL,
                      `maxSizeBytes` INTEGER NOT NULL,
                      `bestQuality` TEXT,
                      `bestSourceTag` TEXT,
                      `bestCodec` TEXT,
                      `hasDualAudio` INTEGER NOT NULL,
                      `downloadedCount` INTEGER NOT NULL,
                      `addedAt` INTEGER NOT NULL,
                      `updatedAt` INTEGER NOT NULL,
                      `searchText` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_titles_folderUrl` ON `titles` (`folderUrl`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_titles_sourceId` ON `titles` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_titles_title` ON `titles` (`title`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_titles_kind` ON `titles` (`kind`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_titles_favorite` ON `titles` (`favorite`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_titles_year` ON `titles` (`year`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_titles_totalSizeBytes` ON `titles` (`totalSizeBytes`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_titles_maxSizeBytes` ON `titles` (`maxSizeBytes`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_titles_addedAt` ON `titles` (`addedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_titles_bestQuality` ON `titles` (`bestQuality`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_titles_bestSourceTag` ON `titles` (`bestSourceTag`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_titles_language` ON `titles` (`language`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_titles_category` ON `titles` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_titles_sourceName` ON `titles` (`sourceName`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `files` (
                      `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      `titleId` INTEGER NOT NULL,
                      `sourceId` INTEGER NOT NULL,
                      `dirUrl` TEXT NOT NULL,
                      `fileName` TEXT NOT NULL,
                      `fileUrl` TEXT NOT NULL,
                      `sizeBytes` INTEGER NOT NULL,
                      `modified` TEXT,
                      `quality` TEXT,
                      `sourceTag` TEXT,
                      `codec` TEXT,
                      `audio` TEXT,
                      `season` INTEGER,
                      `episode` INTEGER,
                      `addedAt` INTEGER NOT NULL,
                      `searchText` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_files_fileUrl` ON `files` (`fileUrl`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_titleId` ON `files` (`titleId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_dirUrl` ON `files` (`dirUrl`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_files_sourceId` ON `files` (`sourceId`)")

                db.execSQL(
                    """
                    INSERT INTO titles (
                      sourceId, sourceName, category, folderUrl, title, year, kind, posterUrl,
                      favorite, language, fileCount, totalSizeBytes, maxSizeBytes, bestQuality,
                      bestSourceTag, bestCodec, hasDualAudio, downloadedCount, addedAt, updatedAt,
                      searchText
                    )
                    SELECT
                      sourceId, MAX(sourceName), MAX(category), folderUrl, title, MAX(year),
                      CASE WHEN MAX(CASE WHEN kind = 'episode' THEN 1 ELSE 0 END) = 1
                           THEN 'series' ELSE 'movie' END,
                      MAX(posterUrl), MAX(favorite), MAX(language), COUNT(*),
                      COALESCE(SUM(sizeBytes), 0), COALESCE(MAX(sizeBytes), 0),
                      MAX(quality), MAX(sourceTag), MAX(codec),
                      MAX(CASE WHEN audio IS NOT NULL THEN 1 ELSE 0 END),
                      SUM(CASE WHEN EXISTS (
                        SELECT 1 FROM downloads d WHERE d.url = items.fileUrl AND d.state = 'COMPLETED'
                      ) THEN 1 ELSE 0 END),
                      MIN(addedAt), MAX(addedAt),
                      lower(title || ' ' || MAX(category) || ' ' || MAX(sourceName))
                    FROM items
                    GROUP BY folderUrl
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO files (
                      titleId, sourceId, dirUrl, fileName, fileUrl, sizeBytes, modified,
                      quality, sourceTag, codec, audio, season, episode, addedAt, searchText
                    )
                    SELECT t.id, i.sourceId, i.folderUrl, i.fileName, i.fileUrl, i.sizeBytes,
                      i.modified, i.quality, i.sourceTag, i.codec, i.audio, i.season, i.episode,
                      i.addedAt, lower(i.fileName)
                    FROM items i
                    JOIN titles t ON t.folderUrl = i.folderUrl
                    """.trimIndent()
                )

                // Old versions did not enforce a unique source URL. Point every
                // row at the surviving lowest-id source before creating the index.
                db.execSQL("DELETE FROM titles WHERE sourceId NOT IN (SELECT id FROM sources)")
                db.execSQL("DELETE FROM files WHERE sourceId NOT IN (SELECT id FROM sources)")
                db.execSQL(
                    """
                    UPDATE titles SET sourceId = (
                      SELECT MIN(s2.id) FROM sources s2
                      WHERE s2.rootUrl = (SELECT s1.rootUrl FROM sources s1 WHERE s1.id = titles.sourceId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE files SET sourceId = (
                      SELECT MIN(s2.id) FROM sources s2
                      WHERE s2.rootUrl = (SELECT s1.rootUrl FROM sources s1 WHERE s1.id = files.sourceId)
                    )
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS items")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS folder_index_new (
                      sourceId INTEGER NOT NULL,
                      url TEXT NOT NULL,
                      modified TEXT,
                      isLeaf INTEGER NOT NULL DEFAULT 0,
                      hasVideos INTEGER NOT NULL DEFAULT 0,
                      PRIMARY KEY(sourceId, url)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO folder_index_new (sourceId, url, modified, isLeaf, hasVideos)
                    SELECT (
                      SELECT MIN(s.id) FROM sources s
                      WHERE s.rootUrl = (SELECT s1.rootUrl FROM sources s1 WHERE s1.id = fi.sourceId)
                    ), fi.url, fi.modified, 0, 0
                    FROM folder_index fi
                    WHERE EXISTS (SELECT 1 FROM sources s1 WHERE s1.id = fi.sourceId)
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE folder_index")
                db.execSQL("ALTER TABLE folder_index_new RENAME TO folder_index")
                db.execSQL("DELETE FROM sources WHERE id NOT IN (SELECT MIN(id) FROM sources GROUP BY rootUrl)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sources_rootUrl` ON `sources` (`rootUrl`)")

                // De-duplicate download rows before adding the unique URL index.
                db.execSQL("DELETE FROM downloads WHERE id NOT IN (SELECT MIN(id) FROM downloads GROUP BY url)")
                db.execSQL("ALTER TABLE downloads ADD COLUMN etag TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN lastModified TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN mediaUri TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN mimeType TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_downloads_url` ON `downloads` (`url`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_state` ON `downloads` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_createdAt` ON `downloads` (`createdAt`)")
            }
        }
    }
}
