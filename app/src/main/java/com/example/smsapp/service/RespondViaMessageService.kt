package com.example.smsapp.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.smsapp.SmsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.data?.schemeSpecificPart?.substringBefore('?')
            ?: intent?.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT)
        CoroutineScope(Dispatchers.IO).launch {
            try { if (!address.isNullOrBlank() && !body.isNullOrBlank()) (application as SmsApp).repo.send(address, body) }
            finally { stopSelf(startId) }
        }
        return START_NOT_STICKY
    }
}
