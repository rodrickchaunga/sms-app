package com.example.smsapp.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

data class SimInfo(val subscriptionId: Int, val slotIndex: Int, val name: String, val carrier: String) {
    val label: String get() = "SIM ${slotIndex + 1} · ${carrier.ifBlank { name }.ifBlank { "Unknown carrier" }}"
}

/** Thrown before anything is stored or sent, so a missing SIM never creates a half-sent message. */
class SimUnavailableException(message: String) : Exception(message)

/**
 * Subscription-aware SIM helpers. Subscription IDs are opaque; a slot index is never used as an ID.
 * Listing SIMs needs READ_PHONE_STATE; without it the list is empty and callers must not guess.
 */
object SimManager {
    /** Explicit "ignore my preferences and let Android choose". */
    const val SYSTEM_DEFAULT = -2

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun activeSims(context: Context): List<SimInfo> {
        if (!hasPermission(context)) return emptyList()
        return try {
            val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            manager.activeSubscriptionInfoList.orEmpty()
                .map { SimInfo(it.subscriptionId, it.simSlotIndex, it.displayName?.toString().orEmpty(), it.carrierName?.toString().orEmpty()) }
                .sortedBy { it.slotIndex }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    fun smsManager(context: Context, subscriptionId: Int?): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subscriptionId == null) base else base.createForSubscriptionId(subscriptionId)
        } else {
            if (subscriptionId == null) SmsManager.getDefault() else SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
}
