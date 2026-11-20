package com.example.smsapp.ui

/** The nonce makes a repeated request for the same address (e.g. a second notification tap) a new event. */
data class LaunchRequest(val address: String, val nonce: Long)
