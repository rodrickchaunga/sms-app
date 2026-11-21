package com.example.smsapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smsapp.data.SmsRepository
import kotlinx.coroutines.launch

/** Archived conversations (opened from Settings). Tap to read; long-press to select, then unarchive or delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedScreen(repo: SmsRepository, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val list by repo.archivedConversations().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    BackHandler { if (selecting) selected = emptySet() else onBack() }
    LaunchedEffect(list) { if (selected.isNotEmpty()) selected = selected.intersect(list.map { it.address }.toSet()) }
    fun toggle(address: String) { selected = if (address in selected) selected - address else selected + address }

    Scaffold(topBar = {
        if (selecting) {
            TopAppBar(
                title = { Text("${selected.size} selected") },
                navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "Clear selection") } },
                actions = {
                    IconButton(onClick = { selected = list.map { it.address }.toSet() }) { Icon(Icons.Default.SelectAll, "Select all") }
                    IconButton(onClick = {
                        val targets = selected
                        selected = emptySet()
                        scope.launch { targets.forEach { runCatching { repo.archive(it, false) } } }
                    }) { Icon(Icons.Default.Unarchive, "Unarchive") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                }
            )
        } else {
            TopAppBar(
                title = { Text("Archived", fontWeight = FontWeight.Medium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            if (list.isEmpty()) item {
                Text("No archived conversations.", modifier = Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(list, key = { it.address }) { c ->
                ContactListItem(
                    address = c.address, body = c.body, timestamp = c.timestamp, lastOutgoing = c.lastOutgoing, selected = c.address in selected,
                    onClick = { if (selecting) toggle(c.address) else onOpen(c.address) },
                    onLongClick = { toggle(c.address) }
                )
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(if (selected.size == 1) "Delete conversation?" else "Delete ${selected.size} conversations?") },
        text = { Text("This removes them from this app and their SMS provider rows. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = {
                val targets = selected
                confirmDelete = false; selected = emptySet()
                scope.launch { targets.forEach { runCatching { repo.deleteConversation(it) } } }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}
