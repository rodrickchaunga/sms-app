package com.example.smsapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Per-conversation settings: SIM, mute, custom notification sound/vibration and chat theme (null = follow app-wide). */
@Entity(tableName = "conversation_prefs")
data class ConversationPrefsEntity(
    @PrimaryKey val address: String,
    val simSubscriptionId: Int? = null,
    val muted: Boolean = false,
    val notifSound: String? = null,
    val notifVibration: String? = null,
    val themeBackground: String? = null,
    val themeOutgoing: Int? = null,
    val themeIncoming: Int? = null
)
