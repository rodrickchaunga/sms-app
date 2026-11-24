package com.example.smsapp.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.smsapp.data.SmsRepository
import com.example.smsapp.data.getContactName
import com.example.smsapp.notify.NotificationHelper
import com.example.smsapp.security.VaultSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun getTransparentColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** Set a first PIN, or change the existing one (which needs the current PIN). */
@Composable
fun PinDialog(security: VaultSecurity, title: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val needsCurrent = remember { security.hasPin() }
    var current by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (needsCurrent) OutlinedTextField(current, { current = it.filter(Char::isDigit).take(12) }, label = { Text("Current PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = keyboard)
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("New PIN (6+ digits)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = keyboard)
                OutlinedTextField(confirm, { confirm = it.filter(Char::isDigit).take(12) }, label = { Text("Confirm PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = keyboard)
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pin.length < 6) message = "PIN must be at least 6 digits"
                else if (pin != confirm) message = "PINs do not match"
                else {
                    val value = pin; val old = current
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { if (needsCurrent) security.changePin(old, value) else { security.setPin(value); true } }
                        if (ok) onSaved()
                        else { val wait = security.lockRemainingSeconds(); message = if (wait > 0) "Too many attempts. Try again in ${wait}s" else "Current PIN is incorrect" }
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The private space. It is reached only by long-pressing the new-conversation button and unlocking it;
 * everything about it (PIN, biometrics, notifications, which conversations are in it) lives here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateSpaceScreen(repo: SmsRepository, security: VaultSecurity, onOpen: (String) -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("sms_settings", Context.MODE_PRIVATE) }
    val convos by repo.privateConversations().collectAsState(initial = emptyList())

    var selected by remember { mutableStateOf(setOf<String>()) }
    val selecting = selected.isNotEmpty()
    var menuExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showChangePin by remember { mutableStateOf(false) }
    var addStep by remember { mutableIntStateOf(0) } // 0 none, 1 choose source, 2 conversation list, 3 contacts, 4 manual
    var manualNumber by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var vaultBiometric by remember { mutableStateOf(prefs.getBoolean("vault_biometric", true)) }
    var notifyMode by remember { mutableStateOf(prefs.getString(NotificationHelper.PRIVATE_NOTIFICATIONS_KEY, "generic") ?: "generic") }

    BackHandler { if (selecting) selected = emptySet() else onExit() }
    LaunchedEffect(Unit) { runCatching { repo.repairPrivateAddresses() } }
    LaunchedEffect(convos) { if (selected.isNotEmpty()) selected = selected.intersect(convos.map { it.address }.toSet()) }
    fun toggle(address: String) { selected = if (address in selected) selected - address else selected + address }

    fun addPrivate(address: String) {
        if (address.isBlank()) return
        scope.launch {
            runCatching { repo.moveToPrivate(address) }
                .onSuccess { addStep = 0; manualNumber = ""; onOpen(it) }
                .onFailure { addStep = 0; Toast.makeText(context, "Could not add this conversation", Toast.LENGTH_LONG).show() }
        }
    }

    if (addStep == 3) {
        NewConversationScreen(title = "Choose contact", allowTypedNumber = false, onBack = { addStep = 0 }, onChoose = { addPrivate(it) })
    } else {
        Scaffold(
            topBar = {
                if (selecting) {
                    TopAppBar(
                        title = { Text("${selected.size} selected") },
                        navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "Clear selection") } },
                        actions = {
                            IconButton(onClick = { selected = convos.map { it.address }.toSet() }) { Icon(Icons.Default.SelectAll, "Select all") }
                            IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Default.LockOpen, "Remove from private") }
                            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete messages") }
                        }
                    )
                } else {
                    TopAppBar(
                        title = { Text("Private", fontWeight = FontWeight.Medium) },
                        navigationIcon = { IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                        actions = {
                            IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(text = { Text("Private settings") }, onClick = { menuExpanded = false; showSettings = true })
                            }
                        }
                    )
                }
            },
            floatingActionButton = {
                if (!selecting) FloatingActionButton(onClick = { addStep = 1 }) { Icon(Icons.Default.Add, "Add private conversation") }
            }
        ) { pad ->
            LazyColumn(Modifier.padding(pad)) {
                if (convos.isEmpty()) item {
                    Text("No private conversations yet. Tap + to add one.", modifier = Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(convos, key = { it.address }) { c ->
                    ContactListItem(
                        address = c.address, body = c.body, timestamp = c.timestamp, unread = c.unread, lastOutgoing = c.lastOutgoing, lastStatus = c.lastStatus,
                        selected = c.address in selected,
                        onClick = { if (selecting) toggle(c.address) else onOpen(c.address) },
                        onLongClick = { toggle(c.address) }
                    )
                    }
            }
        }
    }

    if (addStep == 1) AlertDialog(
        onDismissRequest = { addStep = 0 },
        title = { Text("Add private conversation") },
        text = {
            Column {
                ListItem(headlineContent = { Text("From conversation list") }, colors = getTransparentColors(), modifier = Modifier.clickable { addStep = 2 })
                ListItem(headlineContent = { Text("From contacts") }, colors = getTransparentColors(), modifier = Modifier.clickable { addStep = 3 })
                ListItem(headlineContent = { Text("Enter number manually") }, colors = getTransparentColors(), modifier = Modifier.clickable { addStep = 4 })
            }
        },
        confirmButton = { TextButton(onClick = { addStep = 0 }) { Text("Cancel") } }
    )
    if (addStep == 2) {
        val existing by repo.conversations.collectAsState(initial = emptyList())
        var query by remember { mutableStateOf("") }
        // Contact names are looked up in the background; until a name arrives the number is shown (and searched).
        val names = remember { mutableStateMapOf<String, String>() }
        LaunchedEffect(existing) {
            withContext(Dispatchers.IO) {
                existing.forEach { c -> if (c.address !in names) getContactName(context, c.address)?.let { names[c.address] = it } }
            }
        }
        val q = query.trim()
        val shown = if (q.isEmpty()) existing else existing.filter { c ->
            c.address.contains(q, true) || (names[c.address]?.contains(q, true) == true) || c.body.contains(q, true)
        }
        AlertDialog(
            onDismissRequest = { addStep = 0 },
            title = { Text("Move a conversation") },
            text = {
                Column {
                    OutlinedTextField(
                        query, { query = it }, Modifier.fillMaxWidth(),
                        label = { Text("Search name, number or message") }, singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    if (existing.isEmpty()) Text("No conversations to move.")
                    else if (shown.isEmpty()) Text("No matching conversations.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(shown, key = { it.address }) { c ->
                            ListItem(headlineContent = { Text(names[c.address] ?: c.address) }, supportingContent = { Text(c.body, maxLines = 1) }, colors = getTransparentColors(), modifier = Modifier.clickable { addPrivate(c.address) })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { addStep = 0 }) { Text("Cancel") } }
        )
    }
    if (addStep == 4) AlertDialog(
        onDismissRequest = { addStep = 0 },
        title = { Text("Enter number") },
        text = { OutlinedTextField(manualNumber, { manualNumber = it }, label = { Text("Phone number") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)) },
        confirmButton = { TextButton(enabled = manualNumber.isNotBlank(), onClick = { addPrivate(manualNumber) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = { addStep = 0 }) { Text("Cancel") } }
    )

    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text(if (selected.size == 1) "Remove from private?" else "Remove ${selected.size} from private?") },
        text = { Text("Their messages move back to your normal conversation list, and new messages from these numbers arrive there.") },
        confirmButton = {
            TextButton(onClick = {
                val targets = selected
                confirmRemove = false; selected = emptySet()
                scope.launch {
                    val failed = targets.count { runCatching { repo.setVaulted(it, false) }.isFailure }
                    if (failed > 0) Toast.makeText(context, "Could not move $failed conversation(s)", Toast.LENGTH_LONG).show()
                }
            }) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(if (selected.size == 1) "Delete messages?" else "Delete messages in ${selected.size} conversations?") },
        text = { Text("All messages are deleted and cannot be recovered. The numbers stay private, so future messages from them stay protected; use Remove from private to move a number out.") },
        confirmButton = {
            TextButton(onClick = {
                val targets = selected
                confirmDelete = false; selected = emptySet()
                scope.launch { targets.forEach { runCatching { repo.deleteConversation(it) } } }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )

    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false },
        title = { Text("Private settings") },
        text = {
            Column {
                ListItem(headlineContent = { Text("PIN") }, supportingContent = { Text("Change the PIN that opens this space") }, colors = getTransparentColors(),
                    trailingContent = { TextButton(onClick = { showChangePin = true }) { Text("Change") } })
                ListItem(headlineContent = { Text("Fingerprint / device unlock") }, supportingContent = { Text("The PIN always works") }, colors = getTransparentColors(),
                    trailingContent = { Switch(vaultBiometric, { vaultBiometric = it; prefs.edit().putBoolean("vault_biometric", it).apply() }) })
                ListItem(headlineContent = { Text("Notifications") }, supportingContent = { Text("Generic shows only \"New message\" with no name or text") }, colors = getTransparentColors())
                Box(Modifier.padding(horizontal = 16.dp)) {
                    ChoiceChips(listOf("generic", "off"), notifyMode, { if (it == "generic") "Generic" else "None" }) { mode ->
                        notifyMode = mode
                        prefs.edit().putString(NotificationHelper.PRIVATE_NOTIFICATIONS_KEY, mode).apply()
                        if (mode == "off") NotificationHelper.cancelPrivate(context)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Done") } }
    )
    if (showChangePin) PinDialog(security, "Change PIN", onDismiss = { showChangePin = false }, onSaved = { showChangePin = false })
}
