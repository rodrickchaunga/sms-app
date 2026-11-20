package com.example.smsapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Registered for the default-SMS-app WAP_PUSH_DELIVER role contract.
 *
 * MMS is intentionally not parsed here yet. A correct implementation must download the
 * notification-indicated PDU using the carrier APN, handle carrier-specific network policy,
 * store message/part rows in Telephony.Mms, and persist attachment bytes. Merely reading the
 * broadcast extras is not enough to implement MMS safely or reliably across carriers.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // TODO: implement carrier-tested MMS download and provider insertion.
    }
}
