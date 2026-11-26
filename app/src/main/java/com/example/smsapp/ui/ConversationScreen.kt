package com.example.smsapp.ui

import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.flow.collectLatest
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smsapp.data.SmsRepository
import com.example.smsapp.data.getContactName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ConversationScreen(repo: SmsRepository, address: String, privateMode: Boolean = false, onBack: () -> Unit, onBlock: () -> Unit = {}, onUnvault: () -> Unit = {}, onArchive: () -> Unit = {}, onDelete: () -> Unit = {}) {
    // null until the first database result, so the "new conversation" hint never flashes while an existing chat loads.
    val loadedMsgs: List<MessageEntity>? by (if (privateMode) repo.privateThread(address) else repo.thread(address)).collectAsState(initial = null)
    val msgs = loadedMsgs ?: emptyList()
    var draft by remember { mutableStateOf("") }
    var draftLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val deliveryReports = remember { contextPrefs(context).getBoolean("delivery_reports", false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    // Undo send: the message waits [sendDelaySecs] seconds (Settings) before it really goes, and can be taken back into the box.
    val sendDelaySecs = remember { contextPrefs(context).getInt("send_delay_secs", 0) }
    var pendingBody by remember { mutableStateOf<String?>(null) }
    var pendingLeft by remember { mutableIntStateOf(0) }
    var pendingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Long-press on the send button: schedule this text for this number.
    var showQuickSchedule by remember { mutableStateOf(false) }
    var scheduleBody by remember { mutableStateOf("") }
    // A message that failed to send: "See options" opens Retry / Edit / Delete.
    var optionsMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    // Search inside this conversation.
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var matchPos by remember { mutableStateOf(0) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var forwardNumber by remember { mutableStateOf("") }
    var lastSelectedMessageId by remember { mutableStateOf<Long?>(null) }
    var showForwardDialog by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    // Tap a message to reveal its time and status; long-press for its info; "Select" in the info dialog starts bulk selection.
    var revealed by remember { mutableStateOf(setOf<Long>()) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var infoMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var menuMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var menuBounds by remember { mutableStateOf<Rect?>(null) }
    // Where each visible bubble is on screen (window coordinates); read only when a message is long-pressed.
    val bubbleCoords = remember { HashMap<Long, LayoutCoordinates>() }
    var showDeleteSelected by remember { mutableStateOf(false) }
    var convPrefs by remember { mutableStateOf<ConversationPrefsEntity?>(null) }
    val selecting = selectedIds.isNotEmpty()

    val use24h = android.text.format.DateFormat.is24HourFormat(context)
    // The first message that was unread when the chat was opened. Captured once, before the thread is marked read.
    var unreadAnchor by remember(address) { mutableStateOf<Long?>(null) }
    var anchorResolved by remember(address) { mutableStateOf(false) }
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
    LaunchedEffect(address, msgs.size, started, loadedMsgs != null) {
        val loaded = loadedMsgs
        if (started && loaded != null) {
            if (!anchorResolved) {
                anchorResolved = true
                unreadAnchor = loaded.firstOrNull { !it.outgoing && !it.isRead }?.id
            }
            runCatching { repo.markRead(address) }
            if (privateMode) NotificationHelper.cancelPrivate(context) else NotificationHelper.cancel(context, address)
        }
    }

    LaunchedEffect(address) {
        draftLoaded = false
        draft = repo.loadDraft(address).orEmpty()
        draftLoaded = true
    }
    // Re-checked whenever the screen comes back (e.g. after adding the number to contacts).
    var isKnownContact by remember { mutableStateOf(true) }
    LaunchedEffect(address, started) {
        val name = withContext(Dispatchers.IO) { getContactName(context, address) }
        headerName = name ?: address
        isKnownContact = name != null
    }

    // draft is read inside snapshotFlow, not here, so typing recomposes only the message box and not the whole chat.
    LaunchedEffect(address, draftLoaded) {
        if (draftLoaded) {
            androidx.compose.runtime.snapshotFlow { draft }.collectLatest { text ->
                kotlinx.coroutines.delay(500)
                runCatching { repo.saveDraft(address, text) }
            }
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
    val simName = sims.firstOrNull { sim -> sim.subscriptionId == effectiveSim }?.let { sim -> sim.carrier.ifBlank { sim.name } }
        ?: sims.singleOrNull()?.let { sim -> sim.carrier.ifBlank { sim.name } }
    val onSimClick: (() -> Unit)? = if (sims.size > 1) ({ showSimPicker = true }) else null
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

    val canReply = isReplyableAddress(address)
    val canCall = canReply && address.any { it.isDigit() }
    val isPhone = isPhoneNumber(address)
    // "Unknown number" popup: only when the newest message is one I received from an unsaved number, and only for a few seconds.
    // Sending a message (the newest one is then mine) hides it, so it never shows because of something I typed.
    var showUnknownPopup by remember(address) { mutableStateOf(false) }
    var showReplyHelp by remember { mutableStateOf(false) }
    val newestId = loadedMsgs?.lastOrNull()?.id
    LaunchedEffect(address, newestId, isPhone, isKnownContact) {
        val last = loadedMsgs?.lastOrNull()
        if (last != null && !last.outgoing && isPhone && !isKnownContact) {
            showUnknownPopup = true
            kotlinx.coroutines.delay(8000)
        }
        showUnknownPopup = false
    }
    // Oldest first: [date header] [Texting with / Sending with] [Unread line] message ... then reversed for the bottom-anchored list.
    val rows = remember(msgs, unreadAnchor, use24h, headerName, simName, canReply) {
        buildList<ChatRow> {
            var previous: MessageEntity? = null
            for (m in msgs) {
                if (m.id == unreadAnchor) add(UnreadRow)
                val before = previous
                if (before == null || ChatTime.needsSeparator(before.timestamp, m.timestamp)) add(DateRow(ChatTime.separator(m.timestamp, use24h), m.id))
                if (before == null && canReply) add(IntroRow(headerName, simName))
                add(MessageRow(m))
                previous = m
            }
        }.asReversed()
    }
    val activeQuery = if (searching) searchQuery.trim() else ""
    // Matching messages, oldest first. The search starts on the newest match; the arrows step to older / newer ones.
    val matches = remember(msgs, activeQuery) {
        if (activeQuery.isEmpty()) emptyList() else msgs.filter { it.body.contains(activeQuery, ignoreCase = true) }.map { it.id }
    }
    val currentMatchId = matches.getOrNull(matchPos.coerceIn(0, (matches.size - 1).coerceAtLeast(0)))
    fun stepMatch(older: Boolean) {
        if (matches.isEmpty()) return
        val cur = matchPos.coerceIn(0, matches.lastIndex)
        matchPos = if (older) (if (cur > 0) cur - 1 else matches.lastIndex) else (if (cur < matches.lastIndex) cur + 1 else 0)
    }
    fun closeSearch() { searching = false; searchQuery = "" }
    fun addContact() {
        runCatching {
            context.startActivity(Intent(ContactsContract.Intents.Insert.ACTION).apply {
                type = ContactsContract.RawContacts.CONTENT_TYPE
                putExtra(ContactsContract.Intents.Insert.PHONE, address)
            })
        }
    }
    fun dial(number: String) { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", dialableNumber(number), null))) } }
    fun copyNumber(number: String) {
        context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Phone number", dialableNumber(number)))
        Toast.makeText(context, "Number copied", Toast.LENGTH_SHORT).show()
    }
    val bubbleMax = (LocalConfiguration.current.screenWidthDp * 0.75f).dp
    fun retry(id: Long) {
        scope.launch {
            runCatching { repo.retrySend(id, deliveryReports) }
                .onSuccess { sendError = null }
                .onFailure { sendError = it.message ?: "Unable to resend" }
        }
    }
    fun callNumber() { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", address, null))) } }
    fun toggleSelected(id: Long) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }
    fun deliver(body: String) {
        scope.launch { runCatching { repo.send(address, body, deliveryReports); repo.saveDraft(address, "") }.onFailure { sendError = it.message ?: "Unable to send SMS"; if (draft.isBlank()) draft = body } }
    }
    fun startSend() {
        val body = draft
        if (body.isBlank()) return
        // Sending again while an earlier message is still counting down: the earlier one goes now.
        pendingJob?.cancel(); pendingJob = null
        pendingBody?.let { earlier -> pendingBody = null; deliver(earlier) }
        draft = ""
        if (sendDelaySecs <= 0) { deliver(body); return }
        pendingBody = body
        pendingLeft = sendDelaySecs
        pendingJob = scope.launch {
            for (left in sendDelaySecs downTo 1) { pendingLeft = left; kotlinx.coroutines.delay(1000) }
            val ready = pendingBody
            pendingBody = null; pendingJob = null
            if (ready != null) deliver(ready)
        }
    }
    fun undoSend() {
        pendingJob?.cancel(); pendingJob = null
        val body = pendingBody ?: return
        pendingBody = null
        draft = if (draft.isBlank()) body else body + "\n" + draft
    }
    // Leaving the chat during the countdown sends the message straight away rather than losing it.
    DisposableEffect(Unit) {
        onDispose {
            val waiting = pendingBody
            if (waiting != null) {
                pendingJob?.cancel()
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { repo.send(address, waiting, deliveryReports); repo.saveDraft(address, "") } }
            }
        }
    }

    BackHandler(enabled = showDetails) { showDetails = false }
    BackHandler(enabled = infoMessage != null) { infoMessage = null }
    BackHandler(enabled = selecting && !showDetails) { selectedIds = emptySet() }
    BackHandler(enabled = searching && !selecting && !showDetails) { closeSearch() }

    // Declared here, outside the screen branches below, so opening Details and coming back keeps the reader where they were.
    val listState = rememberLazyListState()
    val newest = msgs.lastOrNull()
    // The list is anchored to the bottom (newest message last on screen). Follow new messages unless the reader scrolled up.
    // Keep the waiting message in view when it appears.
    LaunchedEffect(pendingBody != null) { if (pendingBody != null) listState.scrollToItem(0) }
    // Search: new text starts at the newest match, and the list scrolls to whichever match is current.
    LaunchedEffect(activeQuery) { matchPos = (matches.size - 1).coerceAtLeast(0) }
    LaunchedEffect(currentMatchId) {
        val id = currentMatchId ?: return@LaunchedEffect
        val at = rows.indexOfFirst { it is MessageRow && it.m.id == id }
        if (at >= 0) listState.animateScrollToItem((if (pendingBody != null) 1 else 0) + at, -(listState.layoutInfo.viewportSize.height / 3))
    }
    LaunchedEffect(newest?.id) {
        if (newest != null && (newest.outgoing || listState.firstVisibleItemIndex <= 2)) listState.scrollToItem(0)
    }
    val detailsFor = infoMessage?.let { shown -> msgs.firstOrNull { it.id == shown.id } ?: shown }
    if (detailsFor != null) {
        val shownSim = sims.firstOrNull { sim -> sim.subscriptionId == detailsFor.subscriptionId }?.let { sim -> sim.carrier.ifBlank { sim.name } }
        MessageDetailsScreen(
            message = detailsFor, address = address, simLabel = shownSim,
            bubbleColor = bubbleColor(look, detailsFor.outgoing), bubbleShape = bubbleShape(look.bubbleStyle, detailsFor.outgoing), textFont = look.messageFont.family(),
            onBack = { infoMessage = null },
            onRetry = if (detailsFor.outgoing && detailsFor.status.startsWith("failed")) ({ retry(detailsFor.id); infoMessage = null }) else null,
            onSelect = { selectedIds = setOf(detailsFor.id); infoMessage = null },
            onCopy = {
                context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("SMS message", detailsFor.body))
                infoMessage = null
            },
            onForward = { lastSelectedMessageId = detailsFor.id; forwardNumber = ""; showForwardDialog = true; infoMessage = null },
            onStar = { scope.launch { repo.star(detailsFor.id, !detailsFor.isStarred) } },
            onDelete = { scope.launch { repo.deleteMessage(detailsFor.id) }; infoMessage = null }
        )
    } else if (showDetails) {
        ConversationDetailsScreen(
            repo = repo, address = address, displayName = headerName, messageCount = msgs.size, privateMode = privateMode, canCall = canCall, canAddContact = isPhone && !isKnownContact,
            onBack = { showDetails = false }, onCall = ::callNumber, onAddContact = ::addContact,
            onNotifications = { showNotificationSettings = true },
            onBlock = onBlock, onArchive = onArchive, onUnvault = onUnvault, onDelete = { showDeleteConfirm = true }
        )
    } else {
        val showJump by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
        Scaffold(
            // Blur (Android 12+) only while the long-press menu is open; older phones just get the dim.
            modifier = Modifier.imePadding().then(if (menuMessage != null && android.os.Build.VERSION.SDK_INT >= 31) Modifier.blur(12.dp) else Modifier),
            containerColor = chatBackgroundColor(look),
            topBar = {
                if (searching && !selecting) {
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    TopAppBar(
                        colors = smsTopBarColors(),
                        navigationIcon = { IconButton(onClick = ::closeSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
                        title = {
                            TextField(
                                value = searchQuery, onValueChange = { searchQuery = it }, singleLine = true,
                                placeholder = { Text("Search this conversation") },
                                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { stepMatch(older = true) }),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                            )
                        },
                        actions = {
                            if (activeQuery.isNotEmpty()) Text(
                                if (matches.isEmpty()) "0" else "${matchPos.coerceIn(0, matches.lastIndex) + 1}/${matches.size}",
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(onClick = { stepMatch(older = true) }, enabled = matches.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowUp, "Previous match (older)") }
                            IconButton(onClick = { stepMatch(older = false) }, enabled = matches.isNotEmpty()) { Icon(Icons.Default.KeyboardArrowDown, "Next match (newer)") }
                        }
                    )
                } else if (selecting) {
                    TopAppBar(
                        colors = smsTopBarColors(),
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
                        colors = smsTopBarColors(),
                        title = {
                            Row(Modifier.fillMaxWidth().clickable { showDetails = true }, verticalAlignment = Alignment.CenterVertically) {
                                ContactAvatar(name = headerName, isContact = headerName != address, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(headerName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                        },
                        actions = {
                            IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, contentDescription = "Search conversation") }
                            if (canCall) IconButton(onClick = ::callNumber) { Icon(Icons.Default.Call, contentDescription = "Call") }
                            IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Conversation options") }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(text = { Text("Search") }, onClick = { menuExpanded = false; searching = true })
                                DropdownMenuItem(text = { Text("Contact details") }, onClick = { menuExpanded = false; showDetails = true })
                                DropdownMenuItem(text = { Text("Notification settings") }, onClick = { menuExpanded = false; showNotificationSettings = true })
                                if (!privateMode) DropdownMenuItem(
                                    text = { Text(if (convPrefs?.isPinned == true) "Unpin conversation" else "Pin conversation") },
                                    onClick = {
                                        menuExpanded = false
                                        scope.launch { repo.setPinned(address, convPrefs?.isPinned != true); convPrefs = repo.conversationPrefs(address) }
                                    }
                                )
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
                if (!canReply) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val linkColor = MaterialTheme.colorScheme.primary
                        val helpText = buildAnnotatedString {
                            append("Sender can't accept replies. Contact them directly. ")
                            pushStringAnnotation("learn", "learn")
                            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append("Learn more") }
                            pop()
                        }
                        ClickableText(
                            text = helpText,
                            style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
                        ) { offset -> if (helpText.getStringAnnotations("learn", offset, offset).isNotEmpty()) showReplyHelp = true }
                    }
                } else {
                val hint = when {
                    !simName.isNullOrBlank() -> "$simName • Text message"
                    else -> "Text message"
                }
                Column(Modifier.background(chatBackgroundColor(look))) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {}, enabled = false) {
                        Icon(Icons.Default.AddCircleOutline, contentDescription = "MMS attachments not yet available")
                    }
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        enabled = canReply,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        shape = RoundedCornerShape(26.dp),
                        maxLines = 5,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        ),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                    )
                    Spacer(Modifier.width(8.dp))
                    // Switch SIM: only on dual-SIM phones, and only until the user starts typing.
                    if (canReply && draft.isEmpty() && sims.size > 1) TextButton(onClick = { showSimPicker = true }) { Text(simShort) }
                    // Characters left in the current SMS and the number of SMS parts, shown near the end of a part (like Google Messages).
                    val counter: String? = if (draft.isEmpty() || !look.showCounter) null else {
                        val info = android.telephony.SmsMessage.calculateLength(draft, false)
                        val parts = info[0]; val remaining = info[2]
                        if (parts > 1 || remaining <= 10) (if (look.showMultipart) "$remaining/$parts" else "$remaining") else null
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        counter?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        // Tap sends; a long press schedules this text for this number.
                        val sendEnabled = canReply && draft.isNotBlank()
                        val haptic = LocalHapticFeedback.current
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (sendEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                                .combinedClickable(
                                    enabled = sendEnabled,
                                    onClickLabel = "Send",
                                    onLongClickLabel = "Schedule message",
                                    onClick = { startSend() },
                                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); scheduleBody = draft; showQuickSchedule = true }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Send, contentDescription = "Send. Long press to schedule",
                                tint = if (sendEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                        }
                    }
                }
                }
                }
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
            AnimatedVisibility(
                visible = showUnknownPopup,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text("Unknown number", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(
                                "This number isn't in your contacts. Be careful with links and with requests for money or codes.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Row {
                                TextButton(onClick = { showUnknownPopup = false; addContact() }) { Text("Add contact") }
                                TextButton(onClick = { showUnknownPopup = false; onBlock() }) { Text("Block number") }
                            }
                        }
                        IconButton(onClick = { showUnknownPopup = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
            }
            sendError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
            Box(Modifier.weight(1f).fillMaxWidth().roundedTop().background(chatBackgroundColor(look))) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(look.spacing.gap.dp),
                contentPadding = PaddingValues(8.dp)
            ) {
                // Undo send: the waiting message looks exactly like a sent one (newest, at the bottom). Its status line
                // reads "Double tap to withdraw · 3s" and counts down; a double tap on the bubble takes it back into the box.
                if (pendingBody != null) item(key = "pending-send") {
                    val bubbleBg = bubbleColor(look, true)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalAlignment = Alignment.End) {
                        Surface(
                            modifier = Modifier.widthIn(max = bubbleMax)
                                .semantics { customActions = listOf(CustomAccessibilityAction("Withdraw message") { undoSend(); true }) }
                                .combinedClickable(
                                    onClick = {},
                                    onDoubleClick = { undoSend() }
                                ),
                            color = bubbleBg,
                            shape = bubbleShape(look.bubbleStyle, true)
                        ) {
                            Text(
                                pendingBody.orEmpty(), color = contrastingText(bubbleBg), fontFamily = look.messageFont.family(),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = look.spacing.pad.dp)
                            )
                        }
                        Row(Modifier.padding(top = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Double tap to withdraw", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                " · ${pendingLeft}s", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                items(rows, key = { it.key }) { row ->
                  when (row) {
                    is DateRow -> DateHeader(row.text)
                    is UnreadRow -> UnreadDivider()
                    is IntroRow -> ConversationIntro(row.name, row.simName, onSimClick)
                    is MessageRow -> {
                    val m = row.m
                    val bubbleBg = bubbleColor(look, m.outgoing)
                    val fg = contrastingText(bubbleBg)
                    val isSelected = m.id in selectedIds
                    val failed = m.outgoing && m.status.startsWith("failed")
                    val showDetail = m.id in revealed
                    val bubbleTap: () -> Unit = {
                        if (selecting) toggleSelected(m.id)
                        else revealed = if (showDetail) revealed - m.id else revealed + m.id
                    }
                    val bubbleLong: () -> Unit = {
                        if (selecting) toggleSelected(m.id)
                        else bubbleCoords[m.id]?.takeIf { it.isAttached }?.boundsInWindow()?.let { bounds -> menuBounds = bounds; menuMessage = m }
                    }
                    val numbers = remember(m.body) { findPhoneNumbers(m.body) }
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                            .padding(horizontal = 4.dp),
                        horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Surface(
                                modifier = Modifier.widthIn(max = bubbleMax)
                                    .onGloballyPositioned { coords ->
                                        bubbleCoords[m.id] = coords
                                        if (bubbleCoords.size > 64) bubbleCoords.values.removeAll { stale -> !stale.isAttached }
                                    }
                                    .combinedClickable(onClick = bubbleTap, onLongClick = bubbleLong),
                                color = bubbleBg,
                                shape = bubbleShape(look.bubbleStyle, m.outgoing)
                            ) {
                                if (numbers.isEmpty() && activeQuery.isEmpty()) {
                                    Text(
                                        m.body, color = fg, fontFamily = look.messageFont.family(),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = look.spacing.pad.dp)
                                    )
                                } else {
                                    // Phone numbers in the text: tap dials, long-press copies. Anywhere else on the bubble behaves as usual.
                                    var layout by remember(m.id) { mutableStateOf<TextLayoutResult?>(null) }
                                    val isCurrentMatch = m.id == currentMatchId
                                    val styled = remember(m.body, numbers, activeQuery, isCurrentMatch) {
                                        buildAnnotatedString {
                                            append(m.body)
                                            numbers.forEach { addStyle(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold), it.first, it.last + 1) }
                                            highlightMatches(m.body, activeQuery, isCurrentMatch)
                                        }
                                    }
                                    Text(
                                        styled, color = fg, fontFamily = look.messageFont.family(),
                                        onTextLayout = { layout = it },
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = look.spacing.pad.dp)
                                            .pointerInput(m.id, selecting, showDetail, numbers) {
                                                detectTapGestures(
                                                    onTap = { pos ->
                                                        val hit = if (selecting) null else numberAt(layout, pos, m.body, numbers)
                                                        if (hit != null) dial(hit) else bubbleTap()
                                                    },
                                                    onLongPress = { pos ->
                                                        val hit = if (selecting) null else numberAt(layout, pos, m.body, numbers)
                                                        if (hit != null) copyNumber(hit) else bubbleLong()
                                                    }
                                                )
                                            }
                                    )
                                }
                            }
                            if (m.isStarred) Text(" ★", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFA000))
                        }
                        // Which SIM the message used, on dual-SIM phones. Shown on the date/time line when the message is tapped.
                        val simTag = if (sims.size > 1) sims.firstOrNull { sim -> sim.subscriptionId == m.subscriptionId }?.let { sim -> sim.carrier.ifBlank { sim.name } } else null
                        // Time and status stay hidden until the message is tapped. A message that failed to send is the one exception.
                        if (showDetail || failed) {
                            val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(m.timestamp))
                            Row(Modifier.padding(start = if (m.outgoing) 0.dp else 4.dp, top = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (showDetail) listOfNotNull(time, if (m.outgoing) statusLabel(m.status) else null, simTag).joinToString(" · ") else "Not sent",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (failed) Text(
                                    "  See options", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { optionsMessage = m }.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                    }
                  }
                }
            }
            if (loadedMsgs != null && msgs.isEmpty()) Column(
                Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DateHeader(ChatTime.separator(System.currentTimeMillis(), use24h))
                if (canReply) ConversationIntro(headerName, simName, onSimClick)
            }
            if (showJump) SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
            ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Jump to latest message") }
            }
            }
        }
    }
    if (showNotificationSettings) ConversationNotificationDialog(repo = repo, address = address, displayName = headerName, privateMode = privateMode, onDismiss = { showNotificationSettings = false })
    if (showSimPicker) SimPickerDialog(
        title = "Switch SIM", sims = sims, current = effectiveSim, nullLabel = null, showSystemOption = false,
        onSelect = { choice -> scope.launch { repo.setConversationSim(address, choice) }; convoSim = choice; showSimPicker = false },
        onDismiss = { showSimPicker = false }
    )
    menuMessage?.let { target ->
        val live = msgs.firstOrNull { it.id == target.id } ?: target
        MessageMenu(
            message = live, anchor = menuBounds ?: Rect.Zero, secure = privateMode, verticalPad = look.spacing.pad.dp,
            bubbleColor = bubbleColor(look, live.outgoing), bubbleShape = bubbleShape(look.bubbleStyle, live.outgoing), textFont = look.messageFont.family(),
            canRetry = live.outgoing && live.status.startsWith("failed"),
            onDismiss = { menuMessage = null },
            onRetry = { retry(live.id); menuMessage = null },
            onForward = { lastSelectedMessageId = live.id; forwardNumber = ""; showForwardDialog = true; menuMessage = null },
            onCopy = {
                context.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("SMS message", live.body))
                menuMessage = null
            },
            onStar = { scope.launch { repo.star(live.id, !live.isStarred) }; menuMessage = null },
            onDelete = { selectedIds = setOf(live.id); showDeleteSelected = true; menuMessage = null },
            onSelectMore = { selectedIds = setOf(live.id); menuMessage = null },
            onInfo = { infoMessage = live; menuMessage = null }
        )
    }
    if (showQuickSchedule) ScheduleEditorDialog(
        repo = repo, existing = null, prefillAddress = address, prefillBody = scheduleBody,
        onScheduled = {
            if (draft == scheduleBody) draft = ""
            Toast.makeText(context, "Message scheduled", Toast.LENGTH_SHORT).show()
        },
        onDismiss = { showQuickSchedule = false }
    )
    optionsMessage?.let { target ->
        AlertDialog(
            onDismissRequest = { optionsMessage = null },
            title = { Text("Message not sent") },
            text = {
                Column {
                    Text(target.body, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    FailedOptionRow(Icons.Default.Refresh, "Retry") { optionsMessage = null; retry(target.id) }
                    FailedOptionRow(Icons.Default.Edit, "Edit") {
                        optionsMessage = null
                        // The text goes back into the box to change and send again; the failed copy is removed.
                        draft = if (draft.isBlank()) target.body else target.body + "\n" + draft
                        scope.launch { runCatching { repo.deleteMessage(target.id) } }
                    }
                    FailedOptionRow(Icons.Default.Delete, "Delete") { optionsMessage = null; scope.launch { runCatching { repo.deleteMessage(target.id) } } }
                }
            },
            confirmButton = { TextButton(onClick = { optionsMessage = null }) { Text("Cancel") } }
        )
    }
    if (showReplyHelp) AlertDialog(
        onDismissRequest = { showReplyHelp = false },
        text = { Text("Short codes are local or personalized numbers for sending international SMS messages.\n\nYou can reply to numbers-only short codes, but not to codes with letters and numbers, like “MSG007.”") },
        confirmButton = { TextButton(onClick = { showReplyHelp = false }) { Text("OK") } }
    )
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
        text = { Text(if (privateMode) "All messages in this private conversation are erased and cannot be recovered." else "The conversation moves to the Bin and is erased for good after 30 days. You can restore it from the Bin until then.") },
        confirmButton = { TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
    )
}

internal fun statusLabel(status: String): String = when {
    status == "sending" -> "Sending"
    status == "sent" || status == "synced" -> "Sent"
    status == "delivered" -> "Delivered"
    status == "delivery failed" -> "Delivery failed"
    status == "failed" -> "Failed"
    status.startsWith("failed") -> "Failed (${status.removePrefix("failed:").trim()})"
    else -> status.replaceFirstChar { it.uppercase() }
}

private fun contextPrefs(context: android.content.Context) = context.getSharedPreferences("sms_settings", android.content.Context.MODE_PRIVATE)


/** Marks every occurrence of the search text in a message; the message the search is currently on gets the stronger colour. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.highlightMatches(body: String, query: String, current: Boolean) {
    if (query.isEmpty()) return
    val background = if (current) Color(0xFFFFB300) else Color(0xFFFFE082)
    var from = 0
    while (true) {
        val at = body.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        addStyle(SpanStyle(background = background, color = Color.Black), at, at + query.length)
        from = at + query.length
    }
}

private sealed interface ChatRow { val key: String }
private data class MessageRow(val m: MessageEntity) : ChatRow { override val key: String get() = "m${m.id}" }
private data class DateRow(val text: String, val id: Long) : ChatRow { override val key: String get() = "d$id" }
private object UnreadRow : ChatRow { override val key: String = "unread" }
private data class IntroRow(val name: String, val simName: String?) : ChatRow { override val key: String get() = "intro" }

@Composable
private fun FailedOptionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (label == "Delete") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(20.dp))
        Text(label, color = if (label == "Delete") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DateHeader(text: String) {
    Text(
        text, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** "Texting with Mula Jose (SMS/MMS)" and "Sending with Airtel" (the SIM is a link that opens the SIM picker on dual-SIM phones). */
@Composable
private fun ConversationIntro(name: String, simName: String?, onSimClick: (() -> Unit)?) {
    val tint = MaterialTheme.colorScheme.primary
    // A soft strip that fades out at both ends, behind the "Texting with / Sending with" lines.
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, tint.copy(alpha = 0.10f), Color.Transparent)))
            .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Texting with $name (SMS/MMS)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (!simName.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            Row {
                Text("Sending with ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    simName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline,
                    modifier = if (onSimClick != null) Modifier.clickable(onClick = onSimClick) else Modifier
                )
            }
        }
    }
}

@Composable
private fun UnreadDivider() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
        Text("Unread", modifier = Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
    }
}
