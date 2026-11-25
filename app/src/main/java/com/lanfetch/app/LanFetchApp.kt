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
import com.lanfetch.app.net.CleartextHostPolicy
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Application-scoped service locator.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDb = AppDb.build(appContext)
    val settingsStore = SettingsStore(appContext)
    val sourceRepository = SourceRepository(db)
    private val cleartextHostPolicy = CleartextHostPolicy()
    val httpClient = defaultHttpClient(cleartextHostPolicy)

    /**
     * Transfers get their own OkHttp dispatcher (audit B3): the browse client
     * caps at 14 requests/host, but settings allow up to 16 lanes x 5
     * downloads. A separate dispatcher (32/host, 64 total) means segment
     * traffic can neither starve nor be starved by browsing and posters.
     * Connection pool and interceptors are shared via newBuilder.
     */
    val transferClient = httpClient.newBuilder()
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 32
            },
        )
        .build()
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
        client = transferClient,
    )

    init {
        appScope.launch(Dispatchers.IO) {
            sourceRepository.seedDefaults()
        }
        appScope.launch(Dispatchers.IO) {
            sourceRepository.observeAll()
                .map { sources -> sources.filter { it.enabled }.map { it.rootUrl } }
                .collect(cleartextHostPolicy::updateSources)
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
