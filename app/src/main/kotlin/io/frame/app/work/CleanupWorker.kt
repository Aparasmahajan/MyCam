package io.frame.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.frame.app.FrameApp
import io.frame.core.retention.CleanupTrigger
import java.util.concurrent.TimeUnit

/** Time-based expiry while the app is idle. While recording, the engine cleans up after every segment. */
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as FrameApp).container
        if (container.engine.isRecording) return Result.success()
        container.recoveryGate.await()
        return runCatching { container.retention.runCleanup(CleanupTrigger.PERIODIC) }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("frame-cleanup", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
