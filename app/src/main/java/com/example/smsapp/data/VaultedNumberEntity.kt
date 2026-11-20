package com.example.smsapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable record that a number is private. Vault state used to be derived only from message rows,
 * which meant deleting the last private message silently made the number "public" again and the
 * next incoming SMS would be written to the shared system provider in plaintext.
 */
@Entity(tableName = "vaulted_numbers")
data class VaultedNumberEntity(
    @PrimaryKey val address: String,
    val addedAt: Long
)
