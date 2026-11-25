package com.lanfetch.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lanfetch.app.LanFetchApp
import com.lanfetch.app.net.SyncProgress
import com.lanfetch.app.net.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Owns sync scheduling. WorkManager gives us network constraints, backoff and
 * persistence across process death; the in-memory progress map drives the UI.
 */
class SyncManager(
    private val workManager: WorkManager,
) {
    private val _progress = MutableStateFlow<Map<Long, SyncProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, SyncProgress>> = _progress.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val activeSourceJobs = ConcurrentHashMap<Long, Job>()

    fun syncSource(sourceId: Long) {
        updateProgress(sourceId, SyncProgress(0, 0, 0, "Queued"))
        workManager.enqueueUniqueWork(
            workName(sourceId),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(SyncWorker.KEY_SOURCE_ID to sourceId))
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun syncAll() {
        workManager.enqueueUniqueWork(
            ALL_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncAllWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun cancel(sourceId: Long) {
        workManager.cancelUniqueWork(workName(sourceId))
        activeSourceJobs.remove(sourceId)?.cancel()
        clearProgress(sourceId)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(ALL_WORK)
        activeSourceJobs.keys.toList().forEach { id -> activeSourceJobs.remove(id)?.cancel() }
        _progress.value = emptyMap()
    }

    internal fun attachSourceJob(sourceId: Long, job: Job) {
        activeSourceJobs[sourceId] = job
    }

    internal fun detachSourceJob(sourceId: Long, job: Job) {
        activeSourceJobs.remove(sourceId, job)
    }

    fun updateProgress(sourceId: Long, progress: SyncProgress) {
        _progress.update { it + (sourceId to progress) }
    }

    fun clearProgress(sourceId: Long) {
        _progress.update { it - sourceId }
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    private fun workName(sourceId: Long) = "sync-source-$sourceId"

    companion object {
        const val ALL_WORK = "sync-all"
    }
}

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val sourceId = inputData.getLong(KEY_SOURCE_ID, -1)
        if (sourceId <= 0) return Result.failure()
        val app = applicationContext as? LanFetchApp ?: return Result.failure()
        val manager = app.container.syncManager

        try {
            setForeground(
                app.container.foregroundInfo(
                    notificationId = (1000 + sourceId % 1000).toInt(),
                    title = "Syncing catalog",
                    text = "Scanning directories…",
                )
            )
        } catch (e: Exception) {
            // Foreground start can be denied from the background; WorkManager
            // still runs the worker with its normal time budget.
        }

        manager.updateProgress(sourceId, SyncProgress(0, 0, 0, "Connecting…"))
        return try {
            val result = coroutineScope {
                val job = launch {
                    val outcome = app.container.syncRunner.run(sourceId) { progress ->
                        manager.updateProgress(sourceId, progress)
                    }
                    when (outcome) {
                        is SyncResult.Ok -> manager.message("Sync complete: ${outcome.totalItems} files scanned")
                        is SyncResult.Failed -> manager.message("Sync failed: ${outcome.message}")
                    }
                    if (outcome is SyncResult.Failed && runAttemptCount < 2) {
                        throw RetryableSyncException(outcome.message)
                    }
                }
                manager.attachSourceJob(sourceId, job)
                try {
                    job.join()
                } finally {
                    manager.detachSourceJob(sourceId, job)
                }
            }
            Result.success()
        } catch (e: RetryableSyncException) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            manager.clearProgress(sourceId)
        }
    }

    private class RetryableSyncException(message: String) : Exception(message)

    companion object {
        const val KEY_SOURCE_ID = "source_id"
    }
}

class SyncAllWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? LanFetchApp ?: return Result.failure()
        val container = app.container
        val manager = container.syncManager

        try {
            setForeground(
                container.foregroundInfo(
                    notificationId = 1001,
                    title = "Syncing all servers",
                    text = "Scanning directories…",
                )
            )
        } catch (e: Exception) {
            // See SyncWorker.
        }

        return try {
            val sources = container.sourceRepository.all().filter { it.enabled }
            var failures = 0
            for (source in sources) {
                if (isStopped) break
                manager.updateProgress(source.id, SyncProgress(0, 0, 0, "Connecting…"))
                coroutineScope {
                    val job = launch {
                        val outcome = container.syncRunner.run(source.id) { progress ->
                            manager.updateProgress(source.id, progress)
                        }
                        if (outcome is SyncResult.Failed) failures++
                    }
                    manager.attachSourceJob(source.id, job)
                    try {
                        job.join()
                    } finally {
                        manager.detachSourceJob(source.id, job)
                    }
                }
                manager.clearProgress(source.id)
            }
            if (sources.isNotEmpty()) {
                manager.message(
                    if (failures == 0) "Synced ${sources.size} server(s)"
                    else "Synced ${sources.size} server(s) ($failures unreachable)"
                )
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            manager.progress.value.keys.toList()
                .forEach { manager.clearProgress(it) }
        }
    }
}
