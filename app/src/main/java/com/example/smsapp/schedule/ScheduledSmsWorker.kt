package com.example.smsapp.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smsapp.SmsApp
import com.example.smsapp.data.SimUnavailableException
import com.example.smsapp.security.VaultCrypto

class ScheduledSmsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("scheduled_id") ?: return Result.failure()
        val app = applicationContext as SmsApp
        val dao = app.db.messageDao()
        val row = dao.scheduledById(id) ?: return Result.success()
        // Anything other than 'pending' was cancelled, edited, already claimed or finished: do nothing.
        if (row.status != "pending") return Result.success()
        if (row.sendAtMillis > System.currentTimeMillis() + EARLY_SLACK_MILLIS) return Result.success()
        // Atomic claim: only one worker can move pending -> sending, so a message is never sent twice.
        if (dao.claimScheduled(id, System.currentTimeMillis()) != 1) return Result.success()
        return try {
            val reports = applicationContext.getSharedPreferences("sms_settings", Context.MODE_PRIVATE).getBoolean("delivery_reports", false)
            val messageId = app.repo.send(row.address, VaultCrypto.decrypt(row.body), reports, row.subscriptionId)
            dao.markScheduledSent(id, messageId, System.currentTimeMillis())
            Result.success()
        } catch (e: SimUnavailableException) {
            dao.markScheduledFailed(id, e.message ?: "Selected SIM is unavailable", System.currentTimeMillis())
            Result.success()
        } catch (_: SecurityException) {
            dao.markScheduledFailed(id, "SMS permission missing", System.currentTimeMillis())
            Result.success()
        } catch (_: IllegalArgumentException) {
            dao.markScheduledFailed(id, "Invalid recipient or message", System.currentTimeMillis())
            Result.success()
        } catch (_: Exception) {
            // The send call itself threw, so nothing left the phone and a bounded automatic retry is safe.
            if (row.attempts + 1 < MAX_ATTEMPTS) {
                dao.revertScheduledToPending(id, System.currentTimeMillis())
                Result.retry()
            } else {
                dao.markScheduledFailed(id, "Could not send after $MAX_ATTEMPTS attempts (no service?)", System.currentTimeMillis())
                Result.success()
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val EARLY_SLACK_MILLIS = 60_000L
    }
}
