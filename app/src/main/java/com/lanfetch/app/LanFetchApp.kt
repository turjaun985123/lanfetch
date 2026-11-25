package com.lanfetch.app

import android.app.Application
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.lanfetch.app.data.AppDb
import com.lanfetch.app.data.CatalogRepository
import com.lanfetch.app.data.SettingsStore
import com.lanfetch.app.data.SourceRepository
import com.lanfetch.app.download.DownloadCoordinator
import com.lanfetch.app.net.Scraper
import com.lanfetch.app.sync.SyncManager
import com.lanfetch.app.sync.SyncNotifications
import com.lanfetch.app.sync.SyncRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Application-scoped service locator. Everything long-lived (database,
 * repositories, HTTP client, sync and download owners) is created once here;
 * screens get per-screen ViewModels and never touch the database directly.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDb = AppDb.build(appContext)
    val settingsStore = SettingsStore(appContext)
    val httpClient = Scraper.defaultClient()

    val sourceRepository = SourceRepository(db)
    val catalogRepository = CatalogRepository(db)

    private val scraper = Scraper(httpClient)
    val syncRunner = SyncRunner(db, sourceRepository, catalogRepository, scraper)
    val syncManager = SyncManager(WorkManager.getInstance(appContext))

    val downloadCoordinator = DownloadCoordinator(
        context = appContext,
        db = db,
        settingsStore = settingsStore,
        catalog = catalogRepository,
        scope = appScope,
        client = httpClient,
    )

    init {
        appScope.launch(Dispatchers.IO) {
            sourceRepository.seedDefaults()
        }
    }

    fun scraper(): Scraper = scraper

    /** Notification + type wrapper for WorkManager foreground workers. */
    fun foregroundInfo(notificationId: Int, title: String, text: String): ForegroundInfo {
        val notification = SyncNotifications.build(appContext, title, text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}

class LanFetchApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                container.httpClient.newBuilder()
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
            }
            .crossfade(true)
            .build()
}
