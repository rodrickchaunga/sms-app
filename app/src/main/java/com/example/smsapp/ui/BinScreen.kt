package com.example.smsapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smsapp.data.BIN_RETENTION_MILLIS
import com.example.smsapp.data.BinItem
import com.example.smsapp.data.SmsRepository
import kotlinx.coroutines.launch

/**
 * The Bin: ordinary conversations deleted from the conversation list. Restore them, delete them for good, or let them
 * clear out by themselves after 30 days. Private conversations never appear here (they are erased when deleted), and
 * single messages deleted inside an open chat are not kept here either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BinScreen(repo: SmsRepository, onBack: () -> Unit) {
    val items by repo.binned.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmPurge by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var autoDelete by remember { mutableStateOf(repo.binAutoDelete()) }
    val selecting = selected.isNotEmpty()
    BackHandler { if (selecting) selected = emptySet() else onBack() }
    // Sweep expired items whenever the Bin is opened (also done on app start and once a day).
    LaunchedEffect(Unit) { runCatching { repo.purgeExpiredBin() } }
    LaunchedEffect(items) { if (selected.isNotEmpty()) selected = selected.intersect(items.map { it.address }.toSet()) }
    fun toggle(address: String) { selected = if (address in selected) selected - address else selected + address }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            if (selecting) {
                TopAppBar(
                    colors = smsTopBarColors(),
                    title = { Text("${selected.size} selected") },
                    navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "Clear selection") } },
                    actions = {
                        IconButton(onClick = { selected = items.map { it.address }.toSet() }) { Icon(Icons.Default.SelectAll, "Select all") }
                        IconButton(onClick = {
                            val targets = selected
                            selected = emptySet()
                            scope.launch { targets.forEach { runCatching { repo.restoreFromBin(it) } } }
                        }) { Icon(Icons.Default.RestoreFromTrash, "Restore") }
                        IconButton(onClick = { confirmPurge = true }) { Icon(Icons.Default.DeleteForever, "Delete permanently") }
                    }
                )
            } else {
                TopAppBar(
                    colors = smsTopBarColors(),
                    title = { Text("Bin", fontWeight = FontWeight.Medium) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                    actions = {
                        if (items.isNotEmpty()) Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More options") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Empty bin") }, onClick = { menuOpen = false; confirmEmpty = true })
                            }
                        }
                    }
                )
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text("Auto-delete after 30 days", fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(if (autoDelete) "Conversations are erased for good 30 days after you delete them" else "Conversations stay here until you delete them permanently") },
                        trailingContent = { Switch(autoDelete, { autoDelete = it; repo.setBinAutoDelete(it) }) }
                    )
                }
            }
            if (items.isEmpty()) item {
                Text(
                    "The bin is empty. Conversations you delete from the list wait here so you can get them back.",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else item {
                Text("Tap a conversation to select it, then restore it or delete it permanently.", modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(items, key = { it.address }) { item ->
                BinRow(item, autoDelete, selected = item.address in selected,
                    onClick = { toggle(item.address) }, onLongClick = { toggle(item.address) })
            }
        }
    }

    if (confirmPurge) AlertDialog(
        onDismissRequest = { confirmPurge = false },
        title = { Text(if (selected.size == 1) "Delete permanently?" else "Delete ${selected.size} conversations permanently?") },
        text = { Text("They will be erased for good and cannot be restored.") },
        confirmButton = {
            TextButton(onClick = {
                val targets = selected
                confirmPurge = false; selected = emptySet()
                scope.launch { targets.forEach { runCatching { repo.purgeFromBin(it) } } }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("Cancel") } }
    )
    if (confirmEmpty) AlertDialog(
        onDismissRequest = { confirmEmpty = false },
        title = { Text("Empty the bin?") },
        text = { Text("Everything in the bin will be erased for good and cannot be restored.") },
        confirmButton = { TextButton(onClick = { confirmEmpty = false; scope.launch { runCatching { repo.emptyBin() } } }) { Text("Empty bin") } },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } }
    )
}

@Composable
private fun BinRow(item: BinItem, autoDelete: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val name = rememberDisplayName(item.address)
    val daysLeft = ((item.deletedAt + BIN_RETENTION_MILLIS - System.currentTimeMillis()) / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
    val count = if (item.messageCount == 1) "1 message" else "${item.messageCount} messages"
    val remaining = when {
        !autoDelete -> "kept until you delete it"
        daysLeft <= 0 -> "deleted soon"
        daysLeft == 1 -> "1 day left"
        else -> "$daysLeft days left"
    }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContactAvatar(name = name, isContact = name != item.address, size = 44.dp, selected = selected)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.body.orEmpty().replace('\n', ' '), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$count · $remaining", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
