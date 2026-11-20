package com.example.smsapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/** Draft text is encrypted when its conversation is in the private vault. */
@Entity(tableName = "drafts", indices = [Index(value = ["updatedAt"])])
data class DraftEntity(
    @PrimaryKey val address: String,
    val body: String,
    val updatedAt: Long
)
