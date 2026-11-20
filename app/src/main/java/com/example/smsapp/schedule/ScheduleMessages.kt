package com.example.smsapp.schedule

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.smsapp.SmsApp
import com.example.smsapp.data.ScheduledSmsEntity
import com.example.smsapp.security.VaultCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Scheduling rules:
 *  - Room is written first and is the source of truth; every state change is a guarded UPDATE
 *    (WHERE status IN ...) so the UI, the worker and reconcile() cannot overwrite each other.
 *  - One unique WorkManager job per message ("scheduled_sms_<id>"), so editing replaces and
 *    re-enqueueing can never create a second live job.
 *  - The worker claims a row (pending -> sending) before sending, which makes sending idempotent.
 */
object Scheduler {
    const val TAG = "scheduled_sms"
    private const val STUCK_SENDING_MILLIS = 10 * 60_000L
    private fun workName(id: String) = "scheduled_sms_$id"
    private fun app(context: Context) = context.applicationContext as SmsApp
    private fun dao(context: Context) = app(context).db.messageDao()

    suspend fun create(context: Context, address: String, body: String, sendAtMillis: Long, subscriptionId: Int?): String {
        val to = address.trim()
        require(to.isNotBlank()) { "Recipient is required" }
        require(body.isNotBlank()) { "Message cannot be empty" }
        require(sendAtMillis > System.currentTimeMillis()) { "Choose a future time" }
        val stored = if (app(context).repo.isVaulted(to)) VaultCrypto.encrypt(body) else body
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao(context).insertScheduled(ScheduledSmsEntity(id = id, address = to, body = stored, sendAtMillis = sendAtMillis, createdAtMillis = now, subscriptionId = subscriptionId, updatedAtMillis = now))
        enqueue(context, id, sendAtMillis, replace = true)
        return id
    }

    /** address/body null = change only time and SIM and keep the stored (possibly private) content untouched. */
    suspend fun update(context: Context, id: String, address: String?, body: String?, sendAtMillis: Long, subscriptionId: Int?) {
        require(sendAtMillis > System.currentTimeMillis()) { "Choose a future time" }
        val now = System.currentTimeMillis()
        val changed = if (address != null && body != null) {
            val to = address.trim()
            require(to.isNotBlank()) { "Recipient is required" }
            require(body.isNotBlank()) { "Message cannot be empty" }
            val stored = if (app(context).repo.isVaulted(to)) VaultCrypto.encrypt(body) else body
            dao(context).updateScheduledContent(id, to, stored, sendAtMillis, subscriptionId, now)
        } else dao(context).rescheduleScheduled(id, sendAtMillis, subscriptionId, now)
        check(changed == 1) { "This message is already being sent or was sent, so it can no longer be edited" }
        enqueue(context, id, sendAtMillis, replace = true)
    }

    suspend fun cancel(context: Context, id: String) {
        check(dao(context).cancelScheduledRow(id, System.currentTimeMillis()) == 1) { "This message is already being sent or was sent" }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    suspend fun retryNow(context: Context, id: String) {
        val now = System.currentTimeMillis()
        check(dao(context).retryScheduledNow(id, now) == 1) { "Only failed messages can be retried" }
        enqueue(context, id, now, replace = true)
    }

    suspend fun delete(context: Context, id: String) {
        dao(context).deleteScheduledFinished(id)
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    suspend fun clearFinished(context: Context) { dao(context).clearFinishedScheduled() }

    /**
     * Makes WorkManager match Room after an app update, restart, restored data or a failed enqueue:
     * pending rows without live work are re-enqueued, stale 'sending' rows are marked failed (never
     * auto-resent, because the SMS may already have left the phone), and orphan jobs are cancelled.
     * Overdue pending messages are sent as soon as the app next runs.
     */
    suspend fun reconcile(context: Context) = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val work = WorkManager.getInstance(context)
        val now = System.currentTimeMillis()
        val active = dao.activeScheduled()
        active.forEach { row ->
            if (row.status == "sending") {
                if (now - row.updatedAtMillis > STUCK_SENDING_MILLIS) {
                    dao.markScheduledFailed(row.id, "Interrupted while sending. Not resent automatically so it is not sent twice; check the conversation, then retry if needed.", now)
                }
            } else {
                val alive = work.getWorkInfosForUniqueWork(workName(row.id)).get().any { !it.state.isFinished }
                if (!alive) enqueue(context, row.id, row.sendAtMillis, replace = false)
            }
        }
        val activeIds = active.map { it.id }.toSet()
        work.getWorkInfosByTag(TAG).get().filter { !it.state.isFinished }.forEach { info ->
            val sid = info.tags.firstOrNull { it.startsWith("sid:") }?.removePrefix("sid:")
            if (sid != null && sid !in activeIds) work.cancelWorkById(info.id)
        }
    }

    private fun enqueue(context: Context, id: String, sendAtMillis: Long, replace: Boolean) {
        val delay = (sendAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ScheduledSmsWorker>()
            .setInputData(workDataOf("scheduled_id" to id))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag("sid:$id")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }
}
