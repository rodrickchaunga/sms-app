package com.example.smsapp.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SmsMessage
import com.example.smsapp.SmsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsStatusReceiver : BroadcastReceiver() {

    private enum class Delivery { DELIVERED, PENDING, FAILED, UNKNOWN }

    /**
     * The delivery PendingIntent fires with RESULT_OK for every status report, including "the carrier accepted it and is
     * still trying". Only the status-report PDU says whether the handset really received the message, so it is decoded here.
     * Without a readable PDU the result is UNKNOWN and the message is never marked delivered.
     */
    private fun readDelivery(intent: Intent): Delivery {
        val pdu = intent.getByteArrayExtra("pdu") ?: return Delivery.UNKNOWN
        val format = intent.getStringExtra("format")
        val report = runCatching { if (format != null) SmsMessage.createFromPdu(pdu, format) else SmsMessage.createFromPdu(pdu) }.getOrNull()
            ?: return Delivery.UNKNOWN
        val code = report.status
        return when {
            code == 0 -> Delivery.DELIVERED
            code in 1..0x3F -> Delivery.PENDING
            else -> Delivery.FAILED
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("message_id", -1L)
        if (id <= 0L) return
        val isSentCallback = intent.action?.endsWith("SMS_SENT") == true
        val partIndex = intent.getIntExtra("part_index", 0)
        val partCount = intent.getIntExtra("part_count", 1).coerceAtLeast(1)
        val phase = if (isSentCallback) "sent" else "delivered"

        // Decide the outcome of this callback: a part that succeeded, a failure (with the text to store) or nothing to record yet.
        var successful = false
        var failureStatus: String? = null
        if (isSentCallback) {
            when (resultCode) {
                Activity.RESULT_OK -> successful = true
                SmsManager.RESULT_ERROR_NO_SERVICE -> failureStatus = "failed: no service"
                SmsManager.RESULT_ERROR_RADIO_OFF -> failureStatus = "failed: radio off"
                SmsManager.RESULT_ERROR_NULL_PDU -> failureStatus = "failed: invalid PDU"
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> failureStatus = "failed"
                else -> failureStatus = "failed: $resultCode"
            }
        } else {
            if (resultCode != Activity.RESULT_OK) return
            when (readDelivery(intent)) {
                Delivery.DELIVERED -> successful = true
                Delivery.FAILED -> failureStatus = "delivery failed"
                Delivery.PENDING, Delivery.UNKNOWN -> return
            }
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = (context.applicationContext as SmsApp).db.messageDao()
                val prefs = context.getSharedPreferences("sms_callback_state", Context.MODE_PRIVATE)
                val prefix = "${id}_${phase}_"
                if (!successful) {
                    prefs.edit().putBoolean(prefix + "failed", true).apply()
                    dao.updateStatus(id, failureStatus ?: "failed")
                    // A scheduled message whose radio send failed after hand-off becomes 'failed' so the user can retry it.
                    if (isSentCallback) dao.failScheduledByMessage(id, failureStatus ?: "failed", System.currentTimeMillis())
                } else {
                    prefs.edit().putBoolean(prefix + partIndex, true).apply()
                    val allComplete = (0 until partCount).all { prefs.getBoolean(prefix + it, false) }
                    if (allComplete && !prefs.getBoolean(prefix + "failed", false)) {
                        if (isSentCallback) dao.updateStatus(id, "sent")
                        else dao.markDelivered(id, System.currentTimeMillis())
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
