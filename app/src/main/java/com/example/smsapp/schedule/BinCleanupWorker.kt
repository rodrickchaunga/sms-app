package com.example.smsapp.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.smsapp.SmsApp
import java.util.concurrent.TimeUnit

/** Once a day: permanently delete conversations that have been in the Bin for more than 30 days (if auto-delete is on). */
class BinCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { (applicationContext as SmsApp).repo.purgeExpiredBin() }
        return Result.success()
    }

    companion object {
        private const val NAME = "bin_cleanup"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BinCleanupWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
