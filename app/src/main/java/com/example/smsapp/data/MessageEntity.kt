package com.example.smsapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "messages", indices = [Index(value = ["address", "timestamp"]), Index(value = ["providerId"], unique = true)])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val body: String,
    val timestamp: Long,
    val outgoing: Boolean,
    val isVaulted: Boolean = false,
    val providerId: Long? = null,
    val status: String = "received",
    val isStarred: Boolean = false,
    val isArchived: Boolean = false,
    val isSpam: Boolean = false,
    val isRead: Boolean = true,
    /** Outgoing: when the carrier confirmed delivery to the handset. */
    val deliveredAt: Long? = null,
    /** Incoming: when this phone received the message (timestamp is the sender's send time). */
    val receivedAt: Long? = null
)
