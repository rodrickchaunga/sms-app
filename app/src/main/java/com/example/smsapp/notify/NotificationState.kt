package com.example.smsapp.notify

/** Tracks what is on screen so it is not also announced by a notification. */
object NotificationState {
    @Volatile var visibleAddress: String? = null
    /** True while the private space list is visible; its unread badges are enough, so no notification is posted. */
    @Volatile var privateSpaceVisible: Boolean = false
    fun isViewing(address: String): Boolean = visibleAddress == address
}
