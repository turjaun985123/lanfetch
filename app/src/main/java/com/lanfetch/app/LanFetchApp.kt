package com.lanfetch.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.lanfetch.app.data.AppDb
import com.lanfetch.app.data.CatalogRepository
import com.lanfetch.app.data.SettingsStore
import com.lanfetch.app.data.SourceRepository
import com.lanfetch.app.download.DownloadCoordinator
import com.lanfetch.app.net.LiveServerClient
import com.lanfetch.app.net.defaultHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Application-scoped service locator.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDb = AppDb.build(appContext)
    val settingsStore = SettingsStore(appContext)
    val httpClient = defaultHttpClient()

    val sourceRepository = SourceRepository(db)
    val catalogRepository = CatalogRepository(db)
    val liveClient = LiveServerClient(httpClient)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _cacheClearedToken = MutableStateFlow(0)
    val cacheClearedToken: StateFlow<Int> = _cacheClearedToken.asStateFlow()

    val downloadCoordinator = DownloadCoordinator(
        context = appContext,
        db = db,
        settingsStore = settingsStore,
        scope = appScope,
        client = httpClient,
    )

    init {
        appScope.launch(Dispatchers.IO) {
            sourceRepository.seedDefaults()
        }
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    fun notifyCacheCleared() {
        _cacheClearedToken.update { it + 1 }
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
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.18)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
}
