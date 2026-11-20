package com.example.smsapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.smsapp.notify.NotificationHelper
import com.example.smsapp.appearance.LocalAppearance
import com.example.smsapp.appearance.bubbleColor
import com.example.smsapp.appearance.bubbleShape
import com.example.smsapp.appearance.chatBackgroundColor
import com.example.smsapp.appearance.contrastingText
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

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    var selectedMessage by remember { mutableStateOf<com.example.smsapp.data.MessageEntity?>(null) }
    var forwardNumber by remember { mutableStateOf("") }
    var lastSelectedMessageId by remember { mutableStateOf<Long?>(null) }
    var showForwardDialog by remember { mutableStateOf(false) }

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

    val look = LocalAppearance.current
    var showNotificationSettings by remember { mutableStateOf(false) }
    val colors = listOf(Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFF9C27B0), Color(0xFFFF9800), Color(0xFFF44336))
    val avatarColor = remember(headerName) { colors[Math.floorMod(headerName.hashCode(), colors.size)] }
    val initial = remember(headerName) { headerName.firstOrNull()?.uppercase() ?: "?" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(headerName, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Back", fontWeight = FontWeight.Bold) }
                },
                actions = {
                    if (sims.size > 1) TextButton(onClick = { showSimPicker = true }) { Text(simShort) }
                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Conversation options") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Notification settings") }, onClick = { menuExpanded = false; showNotificationSettings = true })
                        DropdownMenuItem(text = { Text("Block number") }, onClick = { menuExpanded = false; onBlock() })
                        DropdownMenuItem(text = { Text("Archive conversation") }, onClick = { menuExpanded = false; onArchive() })
                        if (privateMode) DropdownMenuItem(text = { Text("Remove private lock") }, onClick = { menuExpanded = false; onUnvault() })
                        DropdownMenuItem(text = { Text("Delete conversation") }, onClick = { menuExpanded = false; showDeleteConfirm = true })
                    }
                }
            )
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
            reverseLayout = false,
            verticalArrangement = Arrangement.spacedBy(look.spacing.gap.dp),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(msgs, key = { it.id }) { m ->
                val bubbleBg = bubbleColor(look, m.outgoing)
                val fg = contrastingText(bubbleBg)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.Bottom
                ) {
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
                        modifier = Modifier.combinedClickable(onClick = { selectedMessage = m }, onLongClick = { scope.launch { repo.star(m.id, !m.isStarred) } }),
                        color = bubbleBg,
                        shape = bubbleShape(look.bubbleStyle, m.outgoing)
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = look.spacing.pad.dp)) {
                            Text(m.body, color = fg, fontFamily = look.messageFont.family())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(m.timestamp)), style = MaterialTheme.typography.labelSmall, color = fg)
                                if (m.outgoing) Text(" · ${m.status}", style = MaterialTheme.typography.labelSmall, color = fg)
                                if (m.isStarred) Text(" ★", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFA000))
                            }
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
    selectedMessage?.let { selected ->
        AlertDialog(
            onDismissRequest = { selectedMessage = null },
            title = { Text("Message actions") },
            text = { Text(selected.body.take(240)) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("SMS message", selected.body))
                        selectedMessage = null
                    }) { Text("Copy") }
                    TextButton(onClick = { lastSelectedMessageId = selected.id; forwardNumber = ""; showForwardDialog = true; selectedMessage = null }) { Text("Forward") }
                    TextButton(onClick = { scope.launch { repo.star(selected.id, !selected.isStarred) }; selectedMessage = null }) { Text(if (selected.isStarred) "Unstar" else "Star") }
                    TextButton(onClick = { scope.launch { repo.deleteMessage(selected.id) }; selectedMessage = null }) { Text("Delete") }
                }
            },
            dismissButton = { TextButton(onClick = { selectedMessage = null }) { Text("Close") } }
        )
    }
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

private fun contextPrefs(context: android.content.Context) = context.getSharedPreferences("sms_settings", android.content.Context.MODE_PRIVATE)