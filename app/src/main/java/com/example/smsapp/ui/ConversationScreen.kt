package com.example.smsapp.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.smsapp.notify.NotificationHelper
import com.example.smsapp.appearance.ChatBackground
import com.example.smsapp.appearance.LocalAppearance
import com.example.smsapp.appearance.bubbleColor
import com.example.smsapp.appearance.bubbleShape
import com.example.smsapp.appearance.chatBackgroundColor
import com.example.smsapp.appearance.contrastingText
import com.example.smsapp.data.ConversationPrefsEntity
import com.example.smsapp.data.MessageEntity
import com.example.smsapp.data.SimInfo
import com.example.smsapp.data.SimManager
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smsapp.data.SmsRepository
import com.example.smsapp.data.getContactName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ConversationScreen(repo: SmsRepository, address: String, privateMode: Boolean = false, onBack: () -> Unit, onBlock: () -> Unit = {}, onUnvault: () -> Unit = {}, onArchive: () -> Unit = {}, onDelete: () -> Unit = {}) {
    val msgs by (if (privateMode) repo.privateThread(address) else repo.thread(address)).collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    var draftLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val deliveryReports = remember { contextPrefs(context).getBoolean("delivery_reports", false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var forwardNumber by remember { mutableStateOf("") }
    var lastSelectedMessageId by remember { mutableStateOf<Long?>(null) }
    var showForwardDialog by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    // Tap a message to reveal its time and status; long-press for its info; "Select" in the info dialog starts bulk selection.
    var revealed by remember { mutableStateOf(setOf<Long>()) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var infoMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var showDeleteSelected by remember { mutableStateOf(false) }
    var convPrefs by remember { mutableStateOf<ConversationPrefsEntity?>(null) }
    val selecting = selectedIds.isNotEmpty()

    var headerName by remember { mutableStateOf(address) }

    // Mark read and clear the notification only while the screen is actually visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    var started by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) started = true else if (event == Lifecycle.Event.ON_STOP) started = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(address, msgs.size, started) {
        if (started) {
            runCatching { repo.markRead(address) }
            if (privateMode) NotificationHelper.cancelPrivate(context) else NotificationHelper.cancel(context, address)
        }
    }

    LaunchedEffect(address) {
        draftLoaded = false
        draft = repo.loadDraft(address).orEmpty()
        draftLoaded = true
        withContext(Dispatchers.IO) {
            val name = getContactName(context, address)
            if (name != null) headerName = name
        }
    }

    LaunchedEffect(address, draft, draftLoaded) {
        if (draftLoaded) {
            kotlinx.coroutines.delay(500)
            runCatching { repo.saveDraft(address, draft) }
        }
    }

    // Reloaded when the details screen closes, so a theme change applies straight away.
    LaunchedEffect(address, showDetails) { convPrefs = repo.conversationPrefs(address) }
    // Forget selected messages that no longer exist.
    LaunchedEffect(msgs) { if (selectedIds.isNotEmpty()) selectedIds = selectedIds.intersect(msgs.map { it.id }.toSet()) }

    // SIM selection: conversation preference, falling back to the app default. Refreshed whenever the screen resumes.
    var sims by remember { mutableStateOf(emptyList<SimInfo>()) }
    var convoSim by remember { mutableStateOf<Int?>(null) }
    var defaultSim by remember { mutableStateOf<Int?>(null) }
    var showSimPicker by remember { mutableStateOf(false) }
    LaunchedEffect(address, started, showSimPicker) {
        val listed = withContext(Dispatchers.IO) { SimManager.activeSims(context) }
        val chosen = repo.conversationSim(address)
        sims = listed; convoSim = chosen; defaultSim = repo.defaultSim()
    }
    val effectiveSim = convoSim ?: defaultSim
    val simLabel: String? = when {
        sims.size < 2 && effectiveSim == null -> null
        effectiveSim == null || effectiveSim == SimManager.SYSTEM_DEFAULT -> "System default SIM"
        else -> sims.firstOrNull { it.subscriptionId == effectiveSim }?.label ?: "Selected SIM unavailable"
    }
    val simShort = sims.firstOrNull { it.subscriptionId == effectiveSim }?.let { "SIM ${it.slotIndex + 1}" } ?: "SIM"

    // App-wide look, with this conversation's own theme on top of it.
    val baseLook = LocalAppearance.current
    val look = remember(baseLook, convPrefs) {
        val p = convPrefs
        baseLook.copy(
            background = p?.themeBackground?.let { name -> ChatBackground.values().firstOrNull { it.name == name } } ?: baseLook.background,
            outgoingBubbleArgb = p?.themeOutgoing ?: baseLook.outgoingBubbleArgb,
            incomingBubbleArgb = p?.themeIncoming ?: baseLook.incomingBubbleArgb
        )
    }
    var showNotificationSettings by remember { mutableStateOf(false) }
    val colors = listOf(Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFF9C27B0), Color(0xFFFF9800), Color(0xFFF44336))
    val avatarColor = remember(headerName) { colors[Math.floorMod(headerName.hashCode(), colors.size)] }
    val initial = remember(headerName) { headerName.firstOrNull()?.uppercase() ?: "?" }

    val canCall = address.any { it.isDigit() }
    fun callNumber() { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", address, null))) } }
    fun toggleSelected(id: Long) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }

    BackHandler(enabled = showDetails) { showDetails = false }
    BackHandler(enabled = selecting && !showDetails) { selectedIds = emptySet() }

    if (showDetails) {
        ConversationDetailsScreen(
            repo = repo, address = address, displayName = headerName, messageCount = msgs.size, privateMode = privateMode, canCall = canCall,
            onBack = { showDetails = false }, onCall = ::callNumber,
            onNotifications = { showNotificationSettings = true },
            onBlock = onBlock, onArchive = onArchive, onUnvault = onUnvault, onDelete = { showDeleteConfirm = true }
        )
    } else {
        val listState = rememberLazyListState()
        val newest = msgs.lastOrNull()
        // The list is anchored to the bottom (newest message last on screen). Follow new messages unless the reader scrolled up.
        LaunchedEffect(newest?.id) {
            if (newest != null && (newest.outgoing || listState.firstVisibleItemIndex <= 2)) listState.scrollToItem(0)
        }
        Scaffold(
            topBar = {
                if (selecting) {
                    TopAppBar(
                        title = { Text("${selectedIds.size} selected") },
                        navigationIcon = { IconButton(onClick = { selectedIds = emptySet() }) { Icon(Icons.Default.Close, "Clear selection") } },
                        actions = {
                            IconButton(onClick = {
                                val text = msgs.filter { it.id in selectedIds }.joinToString("\n") { it.body }
                                context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("SMS messages", text))
                                selectedIds = emptySet()
                            }) { Icon(Icons.Default.ContentCopy, "Copy") }
                            IconButton(onClick = { selectedIds = msgs.map { it.id }.toSet() }) { Icon(Icons.Default.SelectAll, "Select all") }
                            IconButton(onClick = { showDeleteSelected = true }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    )
                } else {
                    TopAppBar(
                        title = { Text(headerName, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().clickable { showDetails = true }) },
                        navigationIcon = {
                            TextButton(onClick = onBack) { Text("← Back", fontWeight = FontWeight.Bold) }
                        },
                        actions = {
                            if (canCall) IconButton(onClick = ::callNumber) { Icon(Icons.Default.Call, contentDescription = "Call") }
                            if (sims.size > 1) TextButton(onClick = { showSimPicker = true }) { Text(simShort) }
                            IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Conversation options") }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(text = { Text("Contact details") }, onClick = { menuExpanded = false; showDetails = true })
                                DropdownMenuItem(text = { Text("Notification settings") }, onClick = { menuExpanded = false; showNotificationSettings = true })
                                DropdownMenuItem(text = { Text("Block number") }, onClick = { menuExpanded = false; onBlock() })
                                DropdownMenuItem(text = { Text("Archive conversation") }, onClick = { menuExpanded = false; onArchive() })
                                if (privateMode) DropdownMenuItem(text = { Text("Remove private lock") }, onClick = { menuExpanded = false; onUnvault() })
                                DropdownMenuItem(text = { Text("Delete conversation") }, onClick = { menuExpanded = false; showDeleteConfirm = true })
                            }
                        }
                    )
                }
            },
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {}, enabled = false) {
                        Icon(Icons.Default.AttachFile, contentDescription = "MMS attachments not yet available")
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Write a message...") },
                        shape = RoundedCornerShape(24.dp),
                        supportingText = { Text(run {
                            val parts = if (draft.isEmpty()) 0 else android.telephony.SmsMessage.calculateLength(draft, false)[0]
                            buildList {
                                if (look.showCounter) add("${draft.length} characters")
                                if (look.showMultipart && parts > 1) add("$parts-part SMS")
                                if (simLabel != null) add(simLabel)
                            }.joinToString(" · ")
                        }) }
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        enabled = draft.isNotBlank(),
                        onClick = {
                            val body = draft
                            draft = ""
                            scope.launch { runCatching { repo.send(address, body, deliveryReports); repo.saveDraft(address, "") }.onFailure { sendError = it.message ?: "Unable to send SMS"; draft = body } }
                        }
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
            sendError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().background(chatBackgroundColor(look)),
                state = listState,
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(look.spacing.gap.dp),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(msgs.asReversed(), key = { it.id }) { m ->
                    val bubbleBg = bubbleColor(look, m.outgoing)
                    val fg = contrastingText(bubbleBg)
                    val isSelected = m.id in selectedIds
                    val failed = m.outgoing && m.status.startsWith("failed")
                    val showDetail = m.id in revealed
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                            .padding(horizontal = 4.dp),
                        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            if (!m.outgoing) {
                                Box(
                                    modifier = Modifier.size(28.dp).clip(CircleShape).background(avatarColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(initial, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Surface(
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        if (selecting) toggleSelected(m.id)
                                        else revealed = if (showDetail) revealed - m.id else revealed + m.id
                                    },
                                    onLongClick = { if (selecting) toggleSelected(m.id) else infoMessage = m }
                                ),
                                color = bubbleBg,
                                shape = bubbleShape(look.bubbleStyle, m.outgoing)
                            ) {
                                Text(
                                    m.body, color = fg, fontFamily = look.messageFont.family(),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = look.spacing.pad.dp)
                                )
                            }
                            if (m.isStarred) Text(" ★", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFA000))
                        }
                        // Time and status stay hidden until the message is tapped. A message that failed to send is the one exception.
                        if (showDetail || failed) {
                            val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(m.timestamp))
                            Text(
                                if (showDetail) (if (m.outgoing) "$time · ${statusLabel(m.status)}" else time) else "Not sent",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = if (m.outgoing) 0.dp else 34.dp, top = 2.dp, end = 4.dp)
                            )
                        }
                    }
                }
            }
            }
        }
    }
    if (showNotificationSettings) ConversationNotificationDialog(repo = repo, address = address, displayName = headerName, privateMode = privateMode, onDismiss = { showNotificationSettings = false })
    if (showSimPicker) SimPickerDialog(
        title = "SIM for this conversation", sims = sims, current = convoSim, nullLabel = "Use app default", showSystemOption = true,
        onSelect = { choice -> scope.launch { repo.setConversationSim(address, choice) }; convoSim = choice; showSimPicker = false },
        onDismiss = { showSimPicker = false }
    )
    infoMessage?.let { m ->
        val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM)
        val lines = buildList {
            if (m.outgoing) {
                add("To" to address)
                add("Status" to statusLabel(m.status))
                add("Sent" to format.format(java.util.Date(m.timestamp)))
                if (m.status == "delivered") add("Delivered" to (m.deliveredAt?.let { format.format(java.util.Date(it)) } ?: "Time not recorded"))
            } else {
                add("From" to address)
                add("Sent" to format.format(java.util.Date(m.timestamp)))
                m.receivedAt?.let { add("Received" to format.format(java.util.Date(it))) }
            }
        }
        AlertDialog(
            onDismissRequest = { infoMessage = null },
            title = { Text("Message info") },
            text = {
                Column {
                    lines.forEach { (label, value) ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text("$label: ", fontWeight = FontWeight.Bold)
                            Text(value)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { selectedIds = setOf(m.id); infoMessage = null }) { Text("Select") }
                        TextButton(onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("SMS message", m.body))
                            infoMessage = null
                        }) { Text("Copy") }
                        TextButton(onClick = { lastSelectedMessageId = m.id; forwardNumber = ""; showForwardDialog = true; infoMessage = null }) { Text("Forward") }
                        TextButton(onClick = { scope.launch { repo.star(m.id, !m.isStarred) }; infoMessage = null }) { Text(if (m.isStarred) "Unstar" else "Star") }
                        TextButton(onClick = { scope.launch { repo.deleteMessage(m.id) }; infoMessage = null }) { Text("Delete") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { infoMessage = null }) { Text("Close") } }
        )
    }
    if (showDeleteSelected) AlertDialog(
        onDismissRequest = { showDeleteSelected = false },
        title = { Text(if (selectedIds.size == 1) "Delete message?" else "Delete ${selectedIds.size} messages?") },
        text = { Text("This cannot be undone.") },
        confirmButton = { TextButton(onClick = {
            val ids = selectedIds
            showDeleteSelected = false; selectedIds = emptySet()
            scope.launch { ids.forEach { runCatching { repo.deleteMessage(it) } } }
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { showDeleteSelected = false }) { Text("Cancel") } }
    )
    if (showForwardDialog) AlertDialog(
        onDismissRequest = { showForwardDialog = false },
        title = { Text("Forward message") },
        text = { OutlinedTextField(value = forwardNumber, onValueChange = { forwardNumber = it }, label = { Text("Recipient number") }) },
        confirmButton = { TextButton(onClick = {
            val target = forwardNumber.trim()
            val idToForward = lastSelectedMessageId
            if (target.isNotBlank() && idToForward != null) scope.launch {
                runCatching { repo.forwardMessage(idToForward, target, deliveryReports) }
                    .onFailure { sendError = it.message ?: "Could not forward message" }
            }
            showForwardDialog = false
        }) { Text("Send") } },
        dismissButton = { TextButton(onClick = { showForwardDialog = false }) { Text("Cancel") } }
    )
    if (showDeleteConfirm) AlertDialog(
        onDismissRequest = { showDeleteConfirm = false },
        title = { Text("Delete conversation?") },
        text = { Text("This removes the conversation from this app and, for non-private conversations, its SMS provider rows. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
    )
}

private fun statusLabel(status: String): String = when {
    status == "sending" -> "Sending"
    status == "sent" || status == "synced" -> "Sent"
    status == "delivered" -> "Delivered"
    status == "delivery failed" -> "Delivery failed"
    status == "failed" -> "Failed"
    status.startsWith("failed") -> "Failed (${status.removePrefix("failed:").trim()})"
    else -> status.replaceFirstChar { it.uppercase() }
}

private fun contextPrefs(context: android.content.Context) = context.getSharedPreferences("sms_settings", android.content.Context.MODE_PRIVATE)
