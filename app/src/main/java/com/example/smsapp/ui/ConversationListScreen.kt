package com.example.smsapp.ui

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smsapp.data.ConversationPreview
import com.example.smsapp.data.SmsRepository
import com.example.smsapp.data.getContactName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Contact name for a number, falling back to the number itself until (or unless) the lookup finds one. */
@Composable
fun rememberDisplayName(address: String): String {
    val context = LocalContext.current
    var name by remember(address) { mutableStateOf(address) }
    LaunchedEffect(address) {
        withContext(Dispatchers.IO) { getContactName(context, address) }?.let { name = it }
    }
    return name
}

/**
 * Tap the button for a new conversation (contacts or a typed number). A long press opens the private space;
 * the button gives no hint of it. A long press on a conversation starts bulk selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    repo: SmsRepository,
    onOpen: (String) -> Unit,
    onNewConversation: () -> Unit,
    onPrivateSpace: () -> Unit,
    onArchived: () -> Unit = {},
    onSpam: () -> Unit = {},
    onPersonalize: () -> Unit = {},
    onSettings: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // null until the first load, so the list is not built empty (which would reset its scroll position) on the way back from a chat.
    val listFlow = remember(searchQuery) { if (searchQuery.isBlank()) repo.conversations else repo.search(searchQuery) }
    val loadedList: List<ConversationPreview>? by listFlow.collectAsState(initial = null as List<ConversationPreview>?)
    val list = loadedList ?: emptyList()
    // Search results get their own scroll state; the hoisted one belongs to the full list.
    val searchListState = rememberLazyListState()
    val activeListState = if (searchQuery.isBlank()) listState else searchListState

    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingSwipeDelete by remember { mutableStateOf<String?>(null) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }
    // Forget selected conversations that disappear from the list (deleted, or filtered out by a new search).
    LaunchedEffect(list) {
        if (selected.isNotEmpty()) selected = selected.intersect(list.map { it.address }.toSet())
    }
    fun toggle(address: String) { selected = if (address in selected) selected - address else selected + address }
    // A swipe has to be pulled at least this far to count, so a light touch or a quick flick does nothing.
    val swipeMinPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() } * 0.4f

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            if (selecting) {
                TopAppBar(
                    colors = smsTopBarColors(),
                    title = { Text("${selected.size} selected") },
                    navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "Clear selection") } },
                    actions = {
                        IconButton(onClick = { selected = list.map { it.address }.toSet() }) { Icon(Icons.Default.SelectAll, "Select all") }
                        val allPinned = list.filter { it.address in selected }.all { it.isPinned }
                        IconButton(onClick = {
                            val targets = selected
                            selected = emptySet()
                            scope.launch { targets.forEach { runCatching { repo.setPinned(it, !allPinned) } } }
                        }) { Icon(Icons.Default.PushPin, if (allPinned) "Unpin" else "Pin") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                )
            } else if (isSearching) {
                TopAppBar(
                    colors = smsTopBarColors(),
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search messages...") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            )
                        )
                    },
                    navigationIcon = { TextButton(onClick = { isSearching = false; searchQuery = "" }) { Text("Cancel") } }
                )
            } else {
                TopAppBar(
                    colors = smsTopBarColors(),
                    title = { Text("Messages", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface) },
                    actions = {
                        IconButton(onClick = { isSearching = true }) { Icon(Icons.Default.Search, "Search") }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More options") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Mark all as read") }, onClick = { menuOpen = false; scope.launch { runCatching { repo.markAllRead() } } })
                                DropdownMenuItem(text = { Text("Archived") }, onClick = { menuOpen = false; onArchived() })
                                DropdownMenuItem(text = { Text("Spam and blocked") }, onClick = { menuOpen = false; onSpam() })
                                DropdownMenuItem(text = { Text("Personalize") }, onClick = { menuOpen = false; onPersonalize() })
                                DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; onSettings() })
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selecting) {
                val haptic = LocalHapticFeedback.current
                val shape = RoundedCornerShape(20.dp)
                Row(
                    Modifier.height(56.dp).shadow(6.dp, shape).clip(shape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .combinedClickable(
                            onClickLabel = "New conversation",
                            onClick = onNewConversation,
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onPrivateSpace() }
                        )
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text("Start chat", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Medium, fontSize = 16.sp)
                }
            }
        }
    ) { pad ->
        if (loadedList == null) {
            Box(Modifier.padding(pad).fillMaxSize().roundedTop().background(MaterialTheme.colorScheme.background))
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize().roundedTop().background(MaterialTheme.colorScheme.background), state = activeListState) {
            if (list.isEmpty()) {
                item {
                    Text(
                        if (searchQuery.isBlank()) "No messages yet. Tap Start chat to begin." else "No matching messages.",
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(list, key = { it.address }) { c ->
                // Swipe left to archive, swipe right to delete (asks first, then the row snaps back).
                val stateHolder = remember { arrayOfNulls<SwipeToDismissBoxState>(1) }
                val dismissState = rememberSwipeToDismissBoxState(
                    positionalThreshold = { total -> total * 0.45f },
                    confirmValueChange = { value ->
                        val pulled = runCatching { abs(stateHolder[0]!!.requireOffset()) }.getOrDefault(0f)
                        if (pulled < swipeMinPx) false
                        else when (value) {
                            SwipeToDismissBoxValue.EndToStart -> { scope.launch { repo.archive(c.address, true) }; true }
                            SwipeToDismissBoxValue.StartToEnd -> { pendingSwipeDelete = c.address; false }
                            else -> false
                        }
                    }
                )
                stateHolder[0] = dismissState
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = !selecting,
                    enableDismissFromEndToStart = !selecting,
                    backgroundContent = {
                        val toDelete = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                        Box(
                            Modifier.fillMaxSize()
                                .background(if (toDelete) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = 24.dp),
                            contentAlignment = if (toDelete) Alignment.CenterStart else Alignment.CenterEnd
                        ) {
                            Icon(if (toDelete) Icons.Default.Delete else Icons.Default.Archive, contentDescription = if (toDelete) "Delete" else "Archive")
                        }
                    }
                ) {
                    Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                        ContactListItem(
                            address = c.address, body = c.body, timestamp = c.timestamp, unread = c.unread, pinned = c.isPinned, lastOutgoing = c.lastOutgoing, lastStatus = c.lastStatus, draft = c.draft,
                            selected = c.address in selected,
                            onClick = { if (selecting) toggle(c.address) else onOpen(c.address) },
                            onLongClick = { toggle(c.address) }
                        )
                    }
                }
            }
        }
    }

    pendingSwipeDelete?.let { address ->
        AlertDialog(
            onDismissRequest = { pendingSwipeDelete = null },
            title = { Text("Delete conversation?") },
            text = { Text("This removes it from this app and its SMS provider rows. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { pendingSwipeDelete = null; scope.launch { runCatching { repo.deleteConversation(address) } } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingSwipeDelete = null }) { Text("Cancel") } }
        )
    }
    if (confirmDelete) {
        val count = selected.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (count == 1) "Delete conversation?" else "Delete $count conversations?") },
            text = { Text("This removes them from this app and their SMS provider rows. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    val targets = selected
                    confirmDelete = false
                    selected = emptySet()
                    scope.launch { targets.forEach { runCatching { repo.deleteConversation(it) } } }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun ContactListItem(
    address: String, body: String, timestamp: Long, unread: Int = 0, selected: Boolean = false, pinned: Boolean = false,
    lastOutgoing: Boolean = false, lastStatus: String = "", draft: String? = null, onClick: () -> Unit, onLongClick: () -> Unit = {}
) {
    val displayName = rememberDisplayName(address)
    val isContact = displayName != address
    val use24h = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val stamp = remember(timestamp, use24h) { ChatTime.listStamp(timestamp, use24h) }
    // Last message sent by me: "You: Hi". A received message shows just its text.
    // An unsent draft wins over the last message: "Draft: <text>" with the text in italics.
    val draftColor = MaterialTheme.colorScheme.error
    val preview = remember(body, lastOutgoing, draft, draftColor) {
        if (!draft.isNullOrBlank()) buildAnnotatedString {
            withStyle(SpanStyle(color = draftColor, fontStyle = FontStyle.Italic)) { append("Draft: ") }
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(draft.replace('\n', ' ')) }
        } else AnnotatedString(if (lastOutgoing && body.isNotBlank()) "You: $body" else body)
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(name = displayName, isContact = isContact, size = 48.dp, selected = selected)
        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp,
                fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                preview, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
                color = if (unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (unread > 0) FontWeight.Medium else FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    Icon(Icons.Default.PushPin, contentDescription = "Pinned", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    stamp, fontSize = 12.sp,
                    color = if (unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (unread > 0) FontWeight.Medium else FontWeight.Normal
                )
            }
            if (lastOutgoing && draft.isNullOrBlank() && body.isNotBlank()) {
                // My message is the latest: show how it went (sent / delivered / failed) where the unread badge would be.
                Spacer(Modifier.height(6.dp))
                DeliveryBadge(lastStatus)
            } else if (unread > 0) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.defaultMinSize(minWidth = 22.dp, minHeight = 22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (unread > 99) "99+" else unread.toString(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
