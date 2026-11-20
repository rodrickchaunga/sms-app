package com.example.smsapp.receiver

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.smsapp.SmsApp
import com.example.smsapp.notify.NotificationHelper
import com.example.smsapp.notify.NotificationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sender = messages.firstOrNull()?.originatingAddress ?: return
        val body = messages.joinToString(separator = "") { it.messageBody.orEmpty() }
        val timestamp = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as SmsApp
                val privateNumber = app.repo.isVaulted(sender)
                val blockedNumber = app.repo.isBlocked(sender)
                // Keep private and blocked content out of the public system SMS provider.
                val providerId = if (privateNumber || blockedNumber) null else {
                    val values = ContentValues().apply {
                        put(Telephony.Sms.ADDRESS, sender); put(Telephony.Sms.BODY, body)
                        put(Telephony.Sms.DATE, timestamp); put(Telephony.Sms.READ, 0)
                        put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                    }
                    context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
                }
                val result = app.repo.ingest(sender, body, timestamp, providerId)
                if (result != null && !result.blocked) {
                    if (NotificationState.isViewing(sender)) app.repo.markRead(sender)
                    else if (result.vaulted && NotificationState.privateSpaceVisible) {
                        // The private list shows the unread badge; nothing to announce.
                    } else {
                        val convPrefs = app.db.messageDao().conversationPrefs(sender)
                        if (convPrefs?.muted != true) NotificationHelper.showIncoming(context, sender, body, timestamp, result.vaulted, convPrefs)
                    }
                }
            } catch (_: SecurityException) {
                // The platform may revoke SMS role/permissions while this process is alive.
            } catch (_: Exception) {
                // A malformed or temporarily unavailable provider row must not crash the receiver.
            } finally { pending.finish() }
        }
    }
}
