package com.example.smsapp.ui

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.example.smsapp.data.ConversationPrefsEntity
import com.example.smsapp.data.SmsRepository
import com.example.smsapp.notify.NotificationHelper
import kotlinx.coroutines.launch

/**
 * Sound + vibration controls shared by Settings (allowDefault = false) and per-conversation settings
 * (allowDefault = true, where null means "use the app-wide setting").
 */
@Composable
fun NotificationAlertControls(
    sound: String?, vibration: String?, allowDefault: Boolean,
    onSoundChange: (String?) -> Unit, onVibrationChange: (String?) -> Unit
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            onSoundChange(when {
                uri == null -> NotificationHelper.SOUND_SILENT
                uri == defaultUri -> NotificationHelper.SOUND_DEFAULT
                else -> uri.toString()
            })
        }
    }
    Column {
        ListItem(
            headlineContent = { Text("Sound") },
            supportingContent = { Text(if (sound == null && allowDefault) "Use app-wide sound" else NotificationHelper.soundTitle(context, sound)) },
            trailingContent = {
                Row {
                    if (allowDefault && sound != null) TextButton(onClick = { onSoundChange(null) }) { Text("Reset") }
                    TextButton(onClick = {
                        val existing: Uri? = NotificationHelper.soundUri(sound)
                        picker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
                        })
                    }) { Text("Choose") }
                }
            }
        )
        Text("Vibration (tap to preview)", modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (allowDefault) FilterChip(selected = vibration == null, onClick = { onVibrationChange(null) }, label = { Text("Use app-wide") })
            NotificationHelper.VibrationPattern.values().forEach { pattern ->
                FilterChip(
                    selected = vibration == pattern.id,
                    onClick = { onVibrationChange(pattern.id); NotificationHelper.previewVibration(context, pattern) },
                    label = { Text(pattern.label) }
                )
            }
        }
    }
}

/** Mute, sound and vibration for one conversation. Private conversations only get Mute (shared private channel). */
@Composable
fun ConversationNotificationDialog(repo: SmsRepository, address: String, displayName: String, privateMode: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loaded by produceState<ConversationPrefsEntity?>(initialValue = null) { value = repo.conversationPrefs(address) ?: ConversationPrefsEntity(address) }
    var muted by remember(loaded) { mutableStateOf(loaded?.muted ?: false) }
    var sound by remember(loaded) { mutableStateOf(loaded?.notifSound) }
    var vibration by remember(loaded) { mutableStateOf(loaded?.notifVibration) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notifications for this conversation") },
        text = {
            if (loaded == null) Text("Loading…")
            else Column {
                ListItem(headlineContent = { Text("Mute") }, supportingContent = { Text("No alerts. Messages still arrive and count as unread.") }, trailingContent = { Switch(muted, { muted = it }) })
                if (!privateMode) NotificationAlertControls(sound, vibration, true, { sound = it }, { vibration = it })
                else Text("Private conversations share one private notification channel, so only muting applies.", modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = loaded != null, onClick = {
                scope.launch {
                    repo.saveConversationNotifications(address, muted, if (privateMode) null else sound, if (privateMode) null else vibration)
                    if (!privateMode) NotificationHelper.applyConversationChannel(context, address, displayName, repo.conversationPrefs(address))
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
