package com.example.smsapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smsapp.data.SmsRepository
import com.example.smsapp.data.getContactName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val list by if (searchQuery.isBlank()) {
        repo.conversations.collectAsState(initial = emptyList())
    } else {
        repo.search(searchQuery).collectAsState(initial = emptyList())
    }

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

    Scaffold(
        modifier = modifier,
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "Clear selection") } },
                    actions = {
                        IconButton(onClick = { selected = list.map { it.address }.toSet() }) { Icon(Icons.Default.SelectAll, "Select all") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                )
            } else if (isSearching) {
                TopAppBar(
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
                    title = { Text("Messages", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                    actions = {
                        IconButton(onClick = { isSearching = true }) { Icon(Icons.Default.Search, "Search") }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!selecting) {
                val haptic = LocalHapticFeedback.current
                val shape = RoundedCornerShape(16.dp)
                Box(
                    Modifier.size(56.dp).shadow(6.dp, shape).clip(shape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .combinedClickable(
                            onClickLabel = "New conversation",
                            onClick = onNewConversation,
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onPrivateSpace() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AddComment, contentDescription = "New conversation", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            if (list.isEmpty()) {
                item {
                    Text(
                        if (searchQuery.isBlank()) "No messages yet. Start a conversation with the + button." else "No matching messages.",
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(list, key = { it.address }) { c ->
                // Swipe left to archive, swipe right to delete (asks first, then the row snaps back).
                val dismissState = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                    when (value) {
                        SwipeToDismissBoxValue.EndToStart -> { scope.launch { repo.archive(c.address, true) }; true }
                        SwipeToDismissBoxValue.StartToEnd -> { pendingSwipeDelete = c.address; false }
                        else -> false
                    }
                })
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
                            address = c.address, body = c.body, timestamp = c.timestamp, unread = c.unread,
                            selected = c.address in selected,
                            onClick = { if (selecting) toggle(c.address) else onOpen(c.address) },
                            onLongClick = { toggle(c.address) }
                        )
                        HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.5f))
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
    address: String, body: String, timestamp: Long, unread: Int = 0, selected: Boolean = false,
    onClick: () -> Unit, onLongClick: () -> Unit = {}
) {
    val displayName = rememberDisplayName(address)
    val colors = listOf(Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFF9C27B0), Color(0xFFFF9800), Color(0xFFF44336))
    val avatarColor = remember(displayName) { colors[Math.floorMod(displayName.hashCode(), colors.size)] }
    val initial = remember(displayName) { displayName.firstOrNull()?.uppercase() ?: "?" }

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(12.dp)) {
            if (unread > 0) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }

        Box(
            modifier = Modifier.size(50.dp).clip(CircleShape).background(if (selected) MaterialTheme.colorScheme.primary else avatarColor),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.onPrimary)
            else Text(initial, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(timestamp)), fontSize = 12.sp, color = Color.Gray)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(body, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal)
        }
    }
}
