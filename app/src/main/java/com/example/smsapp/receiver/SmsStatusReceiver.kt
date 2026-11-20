package com.example.smsapp.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import com.example.smsapp.SmsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("message_id", -1L)
        if (id <= 0L) return
        val isSentCallback = intent.action?.endsWith("SMS_SENT") == true
        val phase = if (isSentCallback) "sent" else "delivered"
        val partIndex = intent.getIntExtra("part_index", 0)
        val partCount = intent.getIntExtra("part_count", 1).coerceAtLeast(1)
        val successful = resultCode == Activity.RESULT_OK
        val status = when {
            successful -> phase
            resultCode == SmsManager.RESULT_ERROR_NO_SERVICE -> "failed: no service"
            resultCode == SmsManager.RESULT_ERROR_RADIO_OFF -> "failed: radio off"
            resultCode == SmsManager.RESULT_ERROR_NULL_PDU -> "failed: invalid PDU"
            resultCode == SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "failed"
            else -> "failed: $resultCode"
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = context.getSharedPreferences("sms_callback_state", Context.MODE_PRIVATE)
                val prefix = "${id}_${phase}_"
                if (!successful) {
                    prefs.edit().putBoolean(prefix + "failed", true).apply()
                    (context.applicationContext as SmsApp).db.messageDao().updateStatus(id, status)
                    // A scheduled message whose radio send failed after hand-off becomes 'failed' so the user can retry it.
                    if (isSentCallback) (context.applicationContext as SmsApp).db.messageDao().failScheduledByMessage(id, status, System.currentTimeMillis())
                } else {
                    prefs.edit().putBoolean(prefix + partIndex, true).apply()
                    val allComplete = (0 until partCount).all { prefs.getBoolean(prefix + it, false) }
                    if (allComplete && !prefs.getBoolean(prefix + "failed", false)) {
                        (context.applicationContext as SmsApp).db.messageDao().updateStatus(id, phase)
                        if (phase == "delivered") prefs.edit().remove("${id}_sent_failed").apply()
                    }
                }
            } catch (_: Exception) {
                // Callback delivery must not crash the application process.
            } finally {
                pending.finish()
            }
        }
    }
}
