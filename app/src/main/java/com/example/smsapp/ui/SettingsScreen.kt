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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smsapp.security.VaultSecurity
import com.example.smsapp.data.SmsRepository
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repo: SmsRepository, onOpenConversation: (String) -> Unit, onOpenPersonalize: () -> Unit = {}, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("sms_settings", Context.MODE_PRIVATE) }
    val security = remember { VaultSecurity(context) }
    var deliveryReports by remember { mutableStateOf(prefs.getBoolean("delivery_reports", false)) }
    var lockPreviews by remember { mutableStateOf(prefs.getBoolean("lock_screen_previews", false)) }
    var currentPin by remember { mutableStateOf("") }
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
    var vaultBiometric by remember { mutableStateOf(prefs.getBoolean("vault_biometric", true)) }
    fun checkDefaultSms(): Boolean = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)
        else Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
    var isDefaultSms by remember { mutableStateOf(checkDefaultSms()) }
    var storageRefresh by remember { mutableStateOf(0) }
    var storageText by remember { mutableStateOf("Calculating…") }
    LaunchedEffect(storageRefresh) { storageText = runCatching { repo.storageSummary() }.getOrDefault("Unavailable") }
    val versionText = remember { runCatching { val info = context.packageManager.getPackageInfo(context.packageName, 0); "Version ${info.versionName} (build ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})" }.getOrDefault("Version unknown") }
    var showPinDialog by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var pinMessage by remember { mutableStateOf("") }
    var showSchedule by remember { mutableStateOf(false) }
    var showScheduled by remember { mutableStateOf(false) }
    val scheduledItems by repo.scheduledMessages().collectAsState(initial = emptyList())
    var showSpam by remember { mutableStateOf(false) }
    var showStarred by remember { mutableStateOf(false) }
    val starredMessages by repo.starred().collectAsState(initial = emptyList())
    var showVault by remember { mutableStateOf(false) }
    val vaultItems by repo.vaultedConversations().collectAsState(initial = emptyList())
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
            ListItem(headlineContent = { Text("Show previews on lock screen") }, supportingContent = { Text("Private conversations never show content or sender") }, trailingContent = { Switch(lockPreviews, { lockPreviews = it; prefs.edit().putBoolean("lock_screen_previews", it).apply() }) })
            ListItem(headlineContent = { Text("Notification sound and vibration") }, supportingContent = { Text("Applies to all conversations without their own setting") })
            NotificationAlertControls(
                sound = alertSound, vibration = alertVibration, allowDefault = false,
                onSoundChange = { alertSound = it ?: "default"; prefs.edit().putString("notif_sound", alertSound).apply(); com.example.smsapp.notify.NotificationHelper.applyGlobalAlertSettings(context) },
                onVibrationChange = { alertVibration = it ?: "default"; prefs.edit().putString("notif_vibration", alertVibration).apply(); com.example.smsapp.notify.NotificationHelper.applyGlobalAlertSettings(context) }
            )
            ListItem(headlineContent = { Text("Notification content") }, supportingContent = { Text("What notifications reveal. Private conversations never reveal anything.") })
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
            Text("Privacy and security", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
            ListItem(headlineContent = { Text("Private conversation PIN") }, supportingContent = { Text(if (security.hasPin()) "PIN configured" else "Set a PIN before locking conversations") }, trailingContent = { TextButton(onClick = { showPinDialog = true }) { Text(if (security.hasPin()) "Change" else "Set up") } })
            ListItem(headlineContent = { Text("Fingerprint / device unlock") }, supportingContent = { Text("Offer biometric or device credentials to open private conversations. The PIN always works.") }, trailingContent = { Switch(vaultBiometric, { vaultBiometric = it; prefs.edit().putBoolean("vault_biometric", it).apply() }) })
            ListItem(headlineContent = { Text("Private conversations") }, supportingContent = { Text("${vaultItems.size} locked conversations") }, trailingContent = { TextButton(onClick = { showVault = true }) { Text("Open vault") } })
            Text("Long-press a conversation in Messages to lock it. Private conversations require PIN or biometric/device authentication.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Tools", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
            ListItem(headlineContent = { Text("Starred messages") }, supportingContent = { Text("${starredMessages.size} saved messages") }, trailingContent = { TextButton(onClick = { showStarred = true }) { Text("View") } })
            ListItem(headlineContent = { Text("Schedule a message") }, supportingContent = { Text("${scheduledItems.count { it.status == "pending" || it.status == "sending" }} pending, ${scheduledItems.count { it.status == "failed" }} failed") }, trailingContent = { Row { TextButton(onClick = { showScheduled = true }) { Text("View") }; TextButton(onClick = { showSchedule = true }) { Text("New") } } })
            ListItem(headlineContent = { Text("Spam and blocked conversations") }, supportingContent = { Text("View and unblock blocked numbers") }, trailingContent = { TextButton(onClick = { showSpam = true }) { Text("Manage") } })
            ListItem(headlineContent = { Text("MMS") }, supportingContent = { Text("Not implemented yet: picture, video and group MMS are planned") })
            HorizontalDivider()
            Text("Storage and about", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
            ListItem(headlineContent = { Text("Storage") }, supportingContent = { Text(storageText) }, trailingContent = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { (context.applicationContext as com.example.smsapp.SmsApp).db.openHelper.writableDatabase.execSQL("VACUUM") }.isSuccess }
                        android.widget.Toast.makeText(context, if (ok) "Database compacted" else "Could not compact the database", android.widget.Toast.LENGTH_SHORT).show()
                        storageRefresh++
                    }
                }) { Text("Compact") }
            })
            ListItem(headlineContent = { Text("About SMS App") }, supportingContent = { Text(versionText) })
        }
    }
    if (showPinDialog) AlertDialog(onDismissRequest = { showPinDialog = false }, title = { Text("Set vault PIN") }, text = {
        Column {
            if (security.hasPin()) OutlinedTextField(currentPin, { currentPin = it.filter(Char::isDigit).take(12) }, label = { Text("Current PIN") })
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("New PIN (6+ digits)") })
            OutlinedTextField(pinConfirm, { pinConfirm = it.filter(Char::isDigit).take(12) }, label = { Text("Confirm PIN") })
            if (pinMessage.isNotBlank()) Text(pinMessage, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        if (pin.length < 6) pinMessage = "PIN must be at least 6 digits"
        else if (pin != pinConfirm) pinMessage = "PINs do not match"
        else {
            val value = pin; val old = currentPin
            scope.launch {
                val ok = withContext(Dispatchers.IO) { if (security.hasPin()) security.changePin(old, value) else { security.setPin(value); true } }
                if (ok) { showPinDialog = false; pin = ""; pinConfirm = ""; currentPin = ""; pinMessage = "" }
                else { val wait = security.lockRemainingSeconds(); pinMessage = if (wait > 0) "Too many attempts. Try again in ${wait}s" else "Current PIN is incorrect" }
            }
        }
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = { showPinDialog = false }) { Text("Cancel") } })
    if (showScheduled) ScheduledMessagesDialog(repo = repo, onDismiss = { showScheduled = false })
    if (showSchedule) ScheduleEditorDialog(repo = repo, existing = null, onDismiss = { showSchedule = false })
    if (showSimPicker) SimPickerDialog(
        title = "Default SIM", sims = simList, current = defaultSim, nullLabel = "System default", showSystemOption = false,
        onSelect = { choice -> repo.setDefaultSim(choice); defaultSim = choice; showSimPicker = false },
        onDismiss = { showSimPicker = false }
    )
    if (showVault) AlertDialog(onDismissRequest = { showVault = false }, title = { Text("Private conversations") }, text = {
        if (vaultItems.isEmpty()) Text("No private conversations yet")
        else LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(vaultItems, key = { it.address }) { item ->
                ListItem(headlineContent = { Text("Private conversation") }, supportingContent = { Text("Number ending ••${item.address.takeLast(2)}") }, trailingContent = {
                    TextButton(onClick = { showVault = false; onOpenConversation(item.address) }) { Text("Open") }
                })
            }
        }
    }, confirmButton = { TextButton(onClick = { showVault = false }) { Text("Done") } })
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

