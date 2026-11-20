package com.example.smsapp.ui

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
fun SettingsScreen(repo: SmsRepository, onOpenConversation: (String) -> Unit, onOpenPersonalize: () -> Unit = {}, modifier: Modifier = Modifier) {
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

    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("Settings", fontWeight = FontWeight.Bold) }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Messaging", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            ListItem(headlineContent = { Text("Default SMS application") }, supportingContent = { Text(if (isDefaultSms) "Active: this app is your default SMS app" else "Not the default app. Required to receive and manage messages") }, trailingContent = {
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 29) {
                        val manager = context.getSystemService(RoleManager::class.java)
                        if (manager.isRoleAvailable(RoleManager.ROLE_SMS)) roleLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_SMS))
                    } else {
                        roleLauncher.launch(Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName))
                    }
                }) { Text("Set") }
            })
            ListItem(headlineContent = { Text("Delivery reports") }, supportingContent = { Text("Request carrier delivery receipts when supported") }, trailingContent = { Switch(deliveryReports, { deliveryReports = it; prefs.edit().putBoolean("delivery_reports", it).apply() }) })
            ListItem(headlineContent = { Text("Default SIM") }, supportingContent = {
                Text(when {
                    !simPermission -> "Allow phone access to list your SIMs"
                    defaultSim == null || defaultSim == com.example.smsapp.data.SimManager.SYSTEM_DEFAULT -> "System default"
                    else -> simList.firstOrNull { it.subscriptionId == defaultSim }?.label ?: "Selected SIM unavailable"
                })
            }, trailingContent = {
                TextButton(onClick = { if (simPermission) showSimPicker = true else simPermissionLauncher.launch(android.Manifest.permission.READ_PHONE_STATE) }) { Text(if (simPermission) "Choose" else "Allow") }
            })
            ListItem(headlineContent = { Text("Notifications") }, supportingContent = { Text(if (notificationsAllowed) "Allowed. Sound and vibration are managed per channel." else "Not allowed yet") }, trailingContent = {
                TextButton(onClick = {
                    if (!notificationsAllowed && Build.VERSION.SDK_INT >= 33) notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else runCatching { context.startActivity(notifSettingsIntent) }
                }) { Text(if (notificationsAllowed) "Channels" else "Allow") }
            })
            ListItem(headlineContent = { Text("Show previews on lock screen") }, supportingContent = { Text("Show message text in notifications on the lock screen") }, trailingContent = { Switch(lockPreviews, { lockPreviews = it; prefs.edit().putBoolean("lock_screen_previews", it).apply() }) })
            ListItem(headlineContent = { Text("Notification sound and vibration") }, supportingContent = { Text("Applies to all conversations without their own setting") })
            NotificationAlertControls(
                sound = alertSound, vibration = alertVibration, allowDefault = false,
                onSoundChange = { alertSound = it ?: "default"; prefs.edit().putString("notif_sound", alertSound).apply(); com.example.smsapp.notify.NotificationHelper.applyGlobalAlertSettings(context) },
                onVibrationChange = { alertVibration = it ?: "default"; prefs.edit().putString("notif_vibration", alertVibration).apply(); com.example.smsapp.notify.NotificationHelper.applyGlobalAlertSettings(context) }
            )
            ListItem(headlineContent = { Text("Notification content") }, supportingContent = { Text("What notifications reveal") })
            Box(Modifier.padding(horizontal = 16.dp)) {
                ChoiceChips(listOf("full", "sender", "hidden"), privacyLevel, { when (it) { "full" -> "Sender and text"; "sender" -> "Sender only"; else -> "Hidden" } }) { level ->
                    privacyLevel = level; prefs.edit().putString("notification_privacy", level).apply()
                }
            }
            HorizontalDivider()
            Text("Messages and appearance", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
            ListItem(headlineContent = { Text("Theme, colours, bubbles and font size") }, supportingContent = { Text("Light, dark or system, accent colour, bubble style, background") }, trailingContent = { TextButton(onClick = onOpenPersonalize) { Text("Open") } })
            ListItem(headlineContent = { Text("Character counter") }, supportingContent = { Text("Show the character count while typing") }, trailingContent = { Switch(look.showCounter, { value -> scope.launch { store.update { it.copy(showCounter = value) } } }) })
            ListItem(headlineContent = { Text("Multipart SMS indicator") }, supportingContent = { Text("Show how many SMS parts a long message needs") }, trailingContent = { Switch(look.showMultipart, { value -> scope.launch { store.update { it.copy(showMultipart = value) } } }) })
            ListItem(headlineContent = { Text("Read reports") }, supportingContent = { Text("Not available: plain SMS has no read receipts. They exist only for MMS (planned).") })
            HorizontalDivider()
            Text("Tools", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
            ListItem(headlineContent = { Text("Starred messages") }, supportingContent = { Text("${starredMessages.size} saved messages") }, trailingContent = { TextButton(onClick = { showStarred = true }) { Text("View") } })
            ListItem(headlineContent = { Text("Schedule a message") }, supportingContent = { Text("${scheduledItems.count { it.status == "pending" || it.status == "sending" }} pending, ${scheduledItems.count { it.status == "failed" }} failed") }, trailingContent = { Row { TextButton(onClick = { showScheduled = true }) { Text("View") }; TextButton(onClick = { showSchedule = true }) { Text("New") } } })
            ListItem(headlineContent = { Text("Spam and blocked conversations") }, supportingContent = { Text("View and unblock blocked numbers") }, trailingContent = { TextButton(onClick = { showSpam = true }) { Text("Manage") } })
            ListItem(headlineContent = { Text("MMS") }, supportingContent = { Text("Not implemented yet: picture, video and group MMS are planned") })
            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
            ListItem(headlineContent = { Text("About SMS App") }, supportingContent = { Text(versionText) })
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

