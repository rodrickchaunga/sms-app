package com.example.smsapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smsapp.data.SmsRepository
import kotlinx.coroutines.launch

/** Blocked numbers (opened from the conversation list menu). Unblocking moves the conversation back to the list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpamScreen(repo: SmsRepository, onBack: () -> Unit) {
    val messages by repo.spam().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }
    // Newest message first, so distinctBy keeps each number's latest message.
    val blocked = remember(messages) { messages.sortedByDescending { it.timestamp }.distinctBy { it.address } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                colors = smsTopBarColors(),
                title = { Text("Spam and blocked", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().roundedTop().background(MaterialTheme.colorScheme.background)) {
            if (blocked.isEmpty()) item {
                Text("No blocked conversations.", modifier = Modifier.fillMaxWidth().padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(blocked, key = { it.address }) { m ->
                val name = rememberDisplayName(m.address)
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    leadingContent = { ContactAvatar(name = name, isContact = name != m.address, size = 48.dp) },
                    headlineContent = { Text(name, maxLines = 1) },
                    supportingContent = { Text(m.body, maxLines = 1) },
                    trailingContent = { TextButton(onClick = { scope.launch { runCatching { repo.block(m.address, false) } } }) { Text("Unblock") } }
                )
            }
        }
    }
}
