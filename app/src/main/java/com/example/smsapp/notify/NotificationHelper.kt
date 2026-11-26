package com.example.smsapp.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.example.smsapp.MainActivity
import com.example.smsapp.data.ConversationPrefsEntity
import com.example.smsapp.data.getContactName

/**
 * All notification construction lives here.
 *
 * Privacy rules enforced in this file:
 *  - Private (vaulted) conversations never put sender, number or text into a notification,
 *    never get Reply / Mark-as-read actions or an address in their tap intent, and use the same
 *    channel and the same generic "New message" text as ordinary hidden notifications, so nothing
 *    in the notification (or in the system notification settings) reveals that a private space exists.
 *  - Ordinary conversations hide their text on a secure lock screen unless the user opted in.
 */
object NotificationHelper {
    const val SOUND_DEFAULT = "default"
    const val SOUND_SILENT = "silent"
    const val ACTION_REPLY = "com.example.smsapp.action.REPLY"
    const val ACTION_MARK_READ = "com.example.smsapp.action.MARK_READ"
    const val KEY_REPLY = "key_text_reply"
    const val EXTRA_ADDRESS = "com.example.smsapp.extra.ADDRESS"
    const val EXTRA_OPEN_ADDRESS = "com.example.smsapp.extra.OPEN_ADDRESS"
    private const val GROUP_KEY = "com.example.smsapp.MESSAGES"
    private const val SUMMARY_ID = 1
    private const val PRIVATE_ID = 2
    const val PRIVATE_NOTIFICATIONS_KEY = "private_notifications"

    enum class VibrationPattern(val id: String, val label: String, val pattern: LongArray?) {
        DEFAULT("default", "Default", longArrayOf(0, 250, 250, 250)),
        SHORT("short", "Short", longArrayOf(0, 120)),
        LONG("long", "Long", longArrayOf(0, 700)),
        DOUBLE("double", "Double", longArrayOf(0, 200, 150, 200)),
        TRIPLE("triple", "Triple", longArrayOf(0, 150, 120, 150, 120, 150)),
        OFF("off", "Off", null);

        companion object { fun fromId(id: String?): VibrationPattern = values().firstOrNull { it.id == id } ?: DEFAULT }
    }

    private fun settings(context: Context) = context.getSharedPreferences("sms_settings", Context.MODE_PRIVATE)
    private fun hash(vararg parts: String?): String = Integer.toHexString(parts.joinToString("|").hashCode())

    fun soundUri(sound: String?): Uri? = when (sound) {
        null, SOUND_DEFAULT -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        SOUND_SILENT -> null
        else -> Uri.parse(sound.orEmpty())
    }

    fun soundTitle(context: Context, sound: String?): String = when (sound) {
        null, SOUND_DEFAULT -> "Default"
        SOUND_SILENT -> "Silent"
        else -> runCatching { RingtoneManager.getRingtone(context, Uri.parse(sound.orEmpty())).getTitle(context) }.getOrNull() ?: "Custom sound"
    }

    @Suppress("DEPRECATION")
    fun previewVibration(context: Context, pattern: VibrationPattern) {
        val waveform = pattern.pattern ?: return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java).defaultVibrator
            else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(VibrationEffect.createWaveform(waveform, -1))
        }
    }

    // Android 8+ stores sound and vibration on the channel and apps cannot edit them afterwards,
    // so each distinct sound/vibration combination gets its own channel ID.
    private fun ensureChannel(context: Context, id: String, name: String, sound: String?, vibration: String?, showBadge: Boolean): String {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return id
        if (manager.getNotificationChannel(id) == null) {
            val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(showBadge)
                val uri = soundUri(sound)
                if (uri == null) setSound(null, null)
                else setSound(uri, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                val waveform = VibrationPattern.fromId(vibration).pattern
                if (waveform == null) enableVibration(false) else { enableVibration(true); vibrationPattern = waveform }
            }
            manager.createNotificationChannel(channel)
        }
        return id
    }

    fun globalChannelId(context: Context): String {
        val p = settings(context)
        val sound = p.getString("notif_sound", SOUND_DEFAULT)
        val vibration = p.getString("notif_vibration", VibrationPattern.DEFAULT.id)
        return ensureChannel(context, "messages_v2_" + hash(sound, vibration), "Messages", sound, vibration, true)
    }

    /** A dedicated channel only exists for conversations with a custom sound or vibration. */
    fun conversationChannelId(context: Context, address: String, displayName: String, prefs: ConversationPrefsEntity?): String? {
        if (prefs == null || (prefs.notifSound == null && prefs.notifVibration == null)) return null
        val p = settings(context)
        val sound = prefs.notifSound ?: p.getString("notif_sound", SOUND_DEFAULT)
        val vibration = prefs.notifVibration ?: p.getString("notif_vibration", VibrationPattern.DEFAULT.id)
        return ensureChannel(context, "conv_${hash(address)}_${hash(sound, vibration)}", displayName, sound, vibration, true)
    }

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.deleteNotificationChannel("messages_v1")
        manager.deleteNotificationChannel("private_messages_v1")
        // Earlier builds had a separate "Private messages" channel that showed up in system settings; remove it.
        manager.notificationChannels.filter { it.id.startsWith("private_v2_") }.forEach { manager.deleteNotificationChannel(it.id) }
        globalChannelId(context)
    }

    /** Call after the global sound/vibration changes: creates the new channels and removes superseded ones. */
    fun applyGlobalAlertSettings(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val keepGlobal = globalChannelId(context)
        manager.notificationChannels
            .filter { it.id.startsWith("messages_v2_") && it.id != keepGlobal }
            .forEach { manager.deleteNotificationChannel(it.id) }
    }

    fun applyConversationChannel(context: Context, address: String, displayName: String, prefs: ConversationPrefsEntity?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val keep = conversationChannelId(context, address, displayName, prefs)
        manager.notificationChannels
            .filter { it.id.startsWith("conv_${hash(address)}_") && it.id != keep }
            .forEach { manager.deleteNotificationChannel(it.id) }
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun notificationId(address: String): Int = 3 + (address.hashCode() and 0x0FFFFFFF)

    private fun openApp(context: Context, requestCode: Int, address: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (address != null) putExtra(EXTRA_OPEN_ADDRESS, address)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun genericVersion(context: Context, channel: String, title: String): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText("Unlock to read")
            .build()

    const val WAKE_SCREEN_KEY = "wake_screen"

    /** Turns the screen on for a few seconds if it is off; it then goes back to sleep after the normal screen timeout. */
    private fun wakeScreen(context: Context) {
        val power = context.getSystemService(android.os.PowerManager::class.java) ?: return
        if (power.isInteractive) return
        runCatching {
            @Suppress("DEPRECATION")
            val lock = power.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or android.os.PowerManager.ON_AFTER_RELEASE,
                "smsapp:wake-for-message"
            )
            lock.acquire(5_000L) // releases itself
        }
    }

    fun showIncoming(context: Context, address: String, body: String, timestamp: Long, vaulted: Boolean, convPrefs: ConversationPrefsEntity? = null) {
        if (!canNotify(context)) return
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        val prefs = settings(app)
        // Private messages: one setting, kept inside the private space ("generic" is the default, "off" posts nothing).
        if (vaulted && prefs.getString(PRIVATE_NOTIFICATIONS_KEY, "generic") == "off") return
        // Settings > "Wake screen for new messages". Only for messages that actually produce a notification (not muted, not "off").
        if (prefs.getBoolean(WAKE_SCREEN_KEY, false)) wakeScreen(app)
        val showOnLockScreen = prefs.getBoolean("lock_screen_previews", false)
        val privacy = prefs.getString("notification_privacy", "full") ?: "full"

        val builder: NotificationCompat.Builder
        val id: Int
        if (vaulted) {
            id = PRIVATE_ID
            val channel = globalChannelId(app)
            builder = NotificationCompat.Builder(app, channel)
                .setContentTitle("New message")
                .setContentText("Open the app to read it")
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(genericVersion(app, channel, "New message"))
                .setContentIntent(openApp(app, id, null))
        } else {
            id = notificationId(address)
            val displayName = runCatching { getContactName(app, address) }.getOrNull() ?: address
            val channel = conversationChannelId(app, address, displayName, convPrefs) ?: globalChannelId(app)
            if (privacy == "hidden") {
                builder = NotificationCompat.Builder(app, channel)
                    .setContentTitle("New message")
                    .setContentText("Open the app to read it")
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(genericVersion(app, channel, "New message"))
                    .setContentIntent(openApp(app, id, address))
            } else {
                val sender = Person.Builder().setName(displayName).build()
                val me = Person.Builder().setName("Me").build()
                val existing = manager.activeNotifications.firstOrNull { it.id == id }?.notification
                val style = existing?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
                    ?: NotificationCompat.MessagingStyle(me)
                style.addMessage(if (privacy == "sender") "New message" else body, timestamp, sender)

                val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build()
                val replyIntent = Intent(app, NotificationActionReceiver::class.java).setAction(ACTION_REPLY).putExtra(EXTRA_ADDRESS, address)
                // RemoteInput requires a mutable PendingIntent; the intent is explicit so it cannot be redirected.
                val replyPending = PendingIntent.getBroadcast(app, id, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                val replyAction = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send, "Reply", replyPending)
                    .addRemoteInput(remoteInput)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setAllowGeneratedReplies(true)
                    .build()
                val readIntent = Intent(app, NotificationActionReceiver::class.java).setAction(ACTION_MARK_READ).putExtra(EXTRA_ADDRESS, address)
                val readPending = PendingIntent.getBroadcast(app, id, readIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val readAction = NotificationCompat.Action.Builder(android.R.drawable.ic_menu_view, "Mark as read", readPending)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .build()

                builder = NotificationCompat.Builder(app, channel)
                    .setStyle(style)
                    .setContentIntent(openApp(app, id, address))
                    .addAction(replyAction)
                    .addAction(readAction)
                if (showOnLockScreen) builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                else builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(genericVersion(app, channel, "New message"))
            }
        }
        builder.setSmallIcon(android.R.drawable.stat_notify_chat)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setGroup(GROUP_KEY)
            .setWhen(timestamp)
            .setShowWhen(true)

        val summary = NotificationCompat.Builder(app, globalChannelId(app))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Messages")
            .setContentText("New messages")
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openApp(app, SUMMARY_ID, null))
            .build()
        try {
            val compat = NotificationManagerCompat.from(app)
            compat.notify(id, builder.build())
            compat.notify(SUMMARY_ID, summary)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    fun showReplyFailed(context: Context, address: String) {
        if (!canNotify(context)) return
        val app = context.applicationContext
        val notification = NotificationCompat.Builder(app, globalChannelId(app))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Reply not sent")
            .setContentText("Open the app and try again")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openApp(app, notificationId(address), null))
            .build()
        try { NotificationManagerCompat.from(app).notify(notificationId(address), notification) } catch (_: SecurityException) {}
    }

    fun cancel(context: Context, address: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(address))
        removeEmptySummary(context)
    }

    fun cancelPrivate(context: Context) {
        NotificationManagerCompat.from(context).cancel(PRIVATE_ID)
        removeEmptySummary(context)
    }

    private fun removeEmptySummary(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.activeNotifications.none { it.id != SUMMARY_ID }) manager.cancel(SUMMARY_ID)
        }
    }
}
