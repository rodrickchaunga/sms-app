package com.example.smsapp.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.app.role.RoleManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sms
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smsapp.data.SmsRepository
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repo: SmsRepository, onOpenConversation: (String) -> Unit, onOpenPersonalize: () -> Unit = {}, onOpenArchived: () -> Unit = {}, onOpenBin: () -> Unit = {}, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("sms_settings", Context.MODE_PRIVATE) }
    var deliveryReports by remember { mutableStateOf(prefs.getBoolean("delivery_reports", false)) }
    var lockPreviews by remember { mutableStateOf(prefs.getBoolean("lock_screen_previews", false)) }
    var notificationsAllowed by remember { mutableStateOf(com.example.smsapp.notify.NotificationHelper.canNotify(context)) }
    val notifSettingsIntent = remember { Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = com.example.smsapp.notify.NotificationHelper.canNotify(context)
        // After repeated denials Android no longer shows the dialog; send the user to the system page instead.
        if (!notificationsAllowed) runCatching { context.startActivity(notifSettingsIntent) }
    }
    var showSimPicker by remember { mutableStateOf(false) }
    var simPermission by remember { mutableStateOf(com.example.smsapp.data.SimManager.hasPermission(context)) }
    var defaultSim by remember { mutableStateOf<Int?>(null) }
    val simPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { simPermission = com.example.smsapp.data.SimManager.hasPermission(context) }
    LaunchedEffect(simPermission, showSimPicker) { defaultSim = repo.defaultSim() }
    val simList = remember(simPermission, showSimPicker) { com.example.smsapp.data.SimManager.activeSims(context) }
    val store = remember { (context.applicationContext as com.example.smsapp.SmsApp).appearance }
    val look by store.flow.collectAsState(initial = com.example.smsapp.appearance.LocalAppearance.current)
    var alertSound by remember { mutableStateOf(prefs.getString("notif_sound", "default") ?: "default") }
    var alertVibration by remember { mutableStateOf(prefs.getString("notif_vibration", "default") ?: "default") }
    var privacyLevel by remember { mutableStateOf(prefs.getString("notification_privacy", "full") ?: "full") }
    fun checkDefaultSms(): Boolean = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
        else Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    var isDefaultSms by remember { mutableStateOf(checkDefaultSms()) }
    val versionText = remember { runCatching { val info = context.packageManager.getPackageInfo(context.packageName, 0); "Version ${info.versionName} (build ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})" }.getOrDefault("Version unknown") }
    var showSchedule by remember { mutableStateOf(false) }
    var showScheduled by remember { mutableStateOf(false) }
    val scheduledItems by repo.scheduledMessages().collectAsState(initial = emptyList())
    var showSpam by remember { mutableStateOf(false) }
    var showStarred by remember { mutableStateOf(false) }
    val starredMessages by repo.starred().collectAsState(initial = emptyList())
    val spamMessages by repo.spam().collectAsState(initial = emptyList())
    var recipient by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var delayMinutes by remember { mutableStateOf("10") }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { isDefaultSms = checkDefaultSms() }

    val tc = ListItemDefaults.colors(containerColor = Color.Transparent)
    var sendDelay by remember { mutableStateOf(prefs.getInt("send_delay_secs", 0)) }
    val binCount by repo.binnedCount.collectAsState(initial = 0)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { TopAppBar(colors = smsTopBarColors(), title = { Text("Settings", fontWeight = FontWeight.Medium) }, navigationIcon = { onBack?.let { back -> IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } } }) }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsCard("Messaging", Icons.Default.Sms) {
                ListItem(colors = tc, headlineContent = { Text("Default SMS application") }, supportingContent = { Text(if (isDefaultSms) "Active: this app is your default SMS app" else "Not the default app. Required to receive and manage messages") }, trailingContent = {
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= 29) {
                            val manager = context.getSystemService(RoleManager::class.java)
                            if (manager.isRoleAvailable(RoleManager.ROLE_SMS)) roleLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_SMS))
                        } else {
                            roleLauncher.launch(Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName))
                        }
                    }) { Text("Set") }
                })
                ListItem(colors = tc, headlineContent = { Text("Delivery reports") }, supportingContent = { Text("Request carrier delivery receipts when supported") }, trailingContent = { Switch(deliveryReports, { deliveryReports = it; prefs.edit().putBoolean("delivery_reports", it).apply() }) })
                ListItem(colors = tc, headlineContent = { Text("Default SIM") }, supportingContent = {
                    Text(when {
                        !simPermission -> "Allow phone access to list your SIMs"
                        defaultSim == null || defaultSim == com.example.smsapp.data.SimManager.SYSTEM_DEFAULT -> "System default"
                        else -> simList.firstOrNull { it.subscriptionId == defaultSim }?.label ?: "Selected SIM unavailable"
                    })
                }, trailingContent = {
                    TextButton(onClick = { if (simPermission) showSimPicker = true else simPermissionLauncher.launch(android.Manifest.permission.READ_PHONE_STATE) }) { Text(if (simPermission) "Choose" else "Allow") }
                })
                ListItem(colors = tc, headlineContent = { Text("Undo send delay") }, supportingContent = {
                    Text(if (sendDelay == 0) "Off: messages send immediately" else "After you tap send, wait $sendDelay seconds. Double tap the message to withdraw it and bring the text back into the box")
                })
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ChoiceChips(listOf(0, 3, 5, 10, 15, 30), sendDelay, { if (it == 0) "Off" else "${it}s" }) { secs ->
                        sendDelay = secs; prefs.edit().putInt("send_delay_secs", secs).apply()
                    }
                }
            }

            SettingsCard("Notifications", Icons.Default.Notifications) {
                ListItem(colors = tc, headlineContent = { Text("Notifications") }, supportingContent = { Text(if (notificationsAllowed) "Allowed. Sound and vibration are managed per channel." else "Not allowed yet") }, trailingContent = {
                    TextButton(onClick = {
                        if (!notificationsAllowed && Build.VERSION.SDK_INT >= 33) notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        else runCatching { context.startActivity(notifSettingsIntent) }
                    }) { Text(if (notificationsAllowed) "Channels" else "Allow") }
                })
                ListItem(colors = tc, headlineContent = { Text("Show previews on lock screen") }, supportingContent = { Text("Show message text in notifications on the lock screen") }, trailingContent = { Switch(lockPreviews, { lockPreviews = it; prefs.edit().putBoolean("lock_screen_previews", it).apply() }) })
                ListItem(colors = tc, headlineContent = { Text("Notification sound and vibration") }, supportingContent = { Text("Applies to all conversations without their own setting") })
                NotificationAlertControls(
                    sound = alertSound, vibration = alertVibration, allowDefault = false,
                    onSoundChange = { alertSound = it ?: "default"; prefs.edit().putString("notif_sound", alertSound).apply(); com.example.smsapp.notify.NotificationHelper.applyGlobalAlertSettings(context) },
                    onVibrationChange = { alertVibration = it ?: "default"; prefs.edit().putString("notif_vibration", alertVibration).apply(); com.example.smsapp.notify.NotificationHelper.applyGlobalAlertSettings(context) }
                )
                ListItem(colors = tc, headlineContent = { Text("Notification content") }, supportingContent = { Text("What notifications reveal") })
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ChoiceChips(listOf("full", "sender", "hidden"), privacyLevel, { when (it) { "full" -> "Sender and text"; "sender" -> "Sender only"; else -> "Hidden" } }) { level ->
                        privacyLevel = level; prefs.edit().putString("notification_privacy", level).apply()
                    }
                }
            }

            SettingsCard("Messages and appearance", Icons.Default.Palette) {
                ListItem(colors = tc, headlineContent = { Text("Theme, colours, bubbles and font size") }, supportingContent = { Text("Light, dark or system, accent colour, bubble style, background") }, trailingContent = { TextButton(onClick = onOpenPersonalize) { Text("Open") } })
                ListItem(colors = tc, headlineContent = { Text("Character counter") }, supportingContent = { Text("Show the character count while typing") }, trailingContent = { Switch(look.showCounter, { value -> scope.launch { store.update { it.copy(showCounter = value) } } }) })
                ListItem(colors = tc, headlineContent = { Text("Multipart SMS indicator") }, supportingContent = { Text("Show how many SMS parts a long message needs") }, trailingContent = { Switch(look.showMultipart, { value -> scope.launch { store.update { it.copy(showMultipart = value) } } }) })
                ListItem(colors = tc, headlineContent = { Text("Read reports") }, supportingContent = { Text("Not available: plain SMS has no read receipts. They exist only for MMS (planned).") })
            }

            SettingsCard("Tools", Icons.Default.Build) {
                ListItem(colors = tc, headlineContent = { Text("Starred messages") }, supportingContent = { Text("${starredMessages.size} saved messages") }, trailingContent = { TextButton(onClick = { showStarred = true }) { Text("View") } })
                ListItem(colors = tc, headlineContent = { Text("Archived conversations") }, supportingContent = { Text("View and unarchive") }, trailingContent = { TextButton(onClick = onOpenArchived) { Text("Open") } })
                ListItem(colors = tc, headlineContent = { Text("Bin") }, supportingContent = { Text(if (binCount == 0) "Restore deleted conversations. Empty right now" else "$binCount deleted conversation${if (binCount == 1) "" else "s"}. Restore, delete for good, or auto-delete after 30 days") }, trailingContent = { TextButton(onClick = onOpenBin) { Text("Open") } })
                ListItem(colors = tc, headlineContent = { Text("Schedule a message") }, supportingContent = { Text("${scheduledItems.count { it.status == "pending" || it.status == "sending" }} pending, ${scheduledItems.count { it.status == "failed" }} failed. Tip: long-press the send button in a chat to schedule that text") }, trailingContent = { Row { TextButton(onClick = { showScheduled = true }) { Text("View") }; TextButton(onClick = { showSchedule = true }) { Text("New") } } })
                ListItem(colors = tc, headlineContent = { Text("Spam and blocked conversations") }, supportingContent = { Text("View and unblock blocked numbers") }, trailingContent = { TextButton(onClick = { showSpam = true }) { Text("Manage") } })
                ListItem(colors = tc, headlineContent = { Text("MMS") }, supportingContent = { Text("Not implemented yet: picture, video and group MMS are planned") })
            }

            SettingsCard("About", Icons.Default.Info) {
                ListItem(colors = tc, headlineContent = { Text("About SMS App") }, supportingContent = { Text(versionText) })
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (showScheduled) ScheduledMessagesDialog(repo = repo, onDismiss = { showScheduled = false })
    if (showSchedule) ScheduleEditorDialog(repo = repo, existing = null, onDismiss = { showSchedule = false })
    if (showSimPicker) SimPickerDialog(
        title = "Default SIM", sims = simList, current = defaultSim, nullLabel = "System default", showSystemOption = false,
        onSelect = { choice -> repo.setDefaultSim(choice); defaultSim = choice; showSimPicker = false },
        onDismiss = { showSimPicker = false }
    )
    if (showStarred) AlertDialog(onDismissRequest = { showStarred = false }, title = { Text("Starred messages") }, text = {
        if (starredMessages.isEmpty()) Text("No starred messages")
        else LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(starredMessages, key = { it.id }) { item ->
                ListItem(headlineContent = { Text(item.address) }, supportingContent = { Text(item.body.take(100)) }, trailingContent = {
                    TextButton(onClick = { showStarred = false; onOpenConversation(item.address) }) { Text("Open") }
                })
            }
        }
    }, confirmButton = { TextButton(onClick = { showStarred = false }) { Text("Done") } })
    if (showSpam) AlertDialog(onDismissRequest = { showSpam = false }, title = { Text("Blocked numbers") }, text = {
        if (spamMessages.isEmpty()) Text("No blocked conversations")
        else LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(spamMessages.distinctBy { it.address }, key = { it.address }) { item ->
                ListItem(headlineContent = { Text(item.address) }, supportingContent = { Text(item.body.take(70)) }, trailingContent = {
                    TextButton(onClick = { scope.launch { repo.block(item.address, false) } }) { Text("Unblock") }
                })
            }
        }
    }, confirmButton = { TextButton(onClick = { showSpam = false }) { Text("Done") } })
}


/** A settings section as a rounded card: a small icon and title on top, the rows below. */
@Composable
private fun SettingsCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(bottom = 8.dp)) {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}
