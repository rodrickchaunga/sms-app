package com.example.smsapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Room is the source of truth for scheduled messages; WorkManager only wakes the app up.
 * Status: pending -> sending -> sent | failed, or cancelled. Bodies for private numbers are encrypted.
 * subscriptionId null = follow conversation/app SIM preference at send time.
 */
@Entity(tableName = "scheduled_sms", indices = [Index(value = ["sendAtMillis"]), Index(value = ["status"])])
data class ScheduledSmsEntity(
    @PrimaryKey val id: String,
    val address: String,
    val body: String,
    val sendAtMillis: Long,
    val status: String = "pending",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val failureReason: String? = null,
    val subscriptionId: Int? = null,
    val attempts: Int = 0,
    val messageId: Long? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
)
