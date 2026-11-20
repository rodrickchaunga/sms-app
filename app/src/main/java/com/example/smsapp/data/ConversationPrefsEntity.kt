package com.example.smsapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Per-conversation settings: SIM, mute, and custom notification sound/vibration (null = follow app-wide). */
@Entity(tableName = "conversation_prefs")
data class ConversationPrefsEntity(
    @PrimaryKey val address: String,
    val simSubscriptionId: Int? = null,
    val muted: Boolean = false,
    val notifSound: String? = null,
    val notifVibration: String? = null
)
