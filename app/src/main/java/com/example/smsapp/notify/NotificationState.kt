package com.example.smsapp.notify

/** Tracks which conversation is currently on screen so it is not also announced by a notification. */
object NotificationState {
    @Volatile var visibleAddress: String? = null
    fun isViewing(address: String): Boolean = visibleAddress == address
}
