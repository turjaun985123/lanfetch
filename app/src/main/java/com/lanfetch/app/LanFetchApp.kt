package com.lanfetch.app

import android.app.Application
import com.lanfetch.app.data.AppDb
import com.lanfetch.app.data.SettingsStore
import com.lanfetch.app.download.DownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LanFetchApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var db: AppDb
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var downloadManager: DownloadManager
        private set

    override fun onCreate() {
        super.onCreate()
        db = AppDb.build(this)
        settingsStore = SettingsStore(this)
        downloadManager = DownloadManager(this, db, settingsStore, appScope)
    }
}
