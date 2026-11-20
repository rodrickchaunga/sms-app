package com.example.smsapp.data

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

fun getContactName(context: Context, phoneNumber: String): String? {
    val uri = Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
        Uri.encode(phoneNumber)
    )
    val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

    try {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
    } catch (e: Exception) {
        // Ignore and fall back to number if permission is denied or error occurs
    }
    return null
}