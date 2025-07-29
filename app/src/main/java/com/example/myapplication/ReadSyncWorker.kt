package com.example.myapplication

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.myapplication.service.SppServiceConnector

class ReadSyncWorker(ctx: Context, params: WorkerParameters)
: CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ok = SppServiceConnector.withService(applicationContext) { svc ->
            svc.syncReadStatus()          // suspend fun
            true
        } ?: false
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        fun enqueue(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "readSync",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReadSyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                            .build()
                    ).build()
            )
        }
    }
}
