package com.example.smsapp.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.example.smsapp.SmsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles Reply and Mark-as-read from notifications. Not exported; only our own PendingIntents reach it. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(NotificationHelper.EXTRA_ADDRESS) ?: return
        val action = intent.action ?: return
        val replyText = if (action == NotificationHelper.ACTION_REPLY)
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NotificationHelper.KEY_REPLY)?.toString()?.trim()
        else null
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as SmsApp
                when (action) {
                    NotificationHelper.ACTION_REPLY -> {
                        if (!replyText.isNullOrBlank()) {
                            val reports = context.getSharedPreferences("sms_settings", Context.MODE_PRIVATE).getBoolean("delivery_reports", false)
                            try {
                                app.repo.send(address, replyText, reports)
                                app.repo.markRead(address)
                            } catch (_: Exception) {
                                NotificationHelper.showReplyFailed(context, address)
                                return@launch
                            }
                        }
                        NotificationHelper.cancel(context, address)
                    }
                    NotificationHelper.ACTION_MARK_READ -> {
                        app.repo.markRead(address)
                        NotificationHelper.cancel(context, address)
                    }
                }
            } catch (_: Exception) {
                // Never crash the process from a notification action.
            } finally {
                pending.finish()
            }
        }
    }
}
