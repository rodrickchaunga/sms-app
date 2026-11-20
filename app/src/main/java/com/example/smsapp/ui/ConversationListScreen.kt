package com.example.smsapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext // <-- The missing import!
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smsapp.data.SmsRepository
import com.example.smsapp.data.getContactName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ConversationListScreen(
    repo: SmsRepository, 
    onOpen: (String) -> Unit,
    onLongPress: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    
    val list by if (searchQuery.isBlank()) {
        repo.conversations.collectAsState(initial = emptyList())
    } else {
        repo.search(searchQuery).collectAsState(initial = emptyList())
    }

    var showDialog by remember { mutableStateOf(false) }
    var phoneNumber by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (isSearching) {
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
            FloatingActionButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.AddComment, contentDescription = "New Message")
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
                ContactListItem(address = c.address, body = c.body, timestamp = c.timestamp, unread = c.unread, onClick = { onOpen(c.address) }, onLongClick = { onLongPress(c.address) })
                HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.5f))
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("New Message") },
            text = {
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = { Text("Phone Number") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (phoneNumber.isNotBlank()) {
                        onOpen(phoneNumber)
                        showDialog = false
                        phoneNumber = ""
                    }
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun ContactListItem(address: String, body: String, timestamp: Long, unread: Int = 0, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    val context = LocalContext.current
    var displayName by remember { mutableStateOf(address) }

    LaunchedEffect(address) {
        withContext(Dispatchers.IO) {
            val name = getContactName(context, address)
            if (name != null) displayName = name
        }
    }

    val colors = listOf(Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFF9C27B0), Color(0xFFFF9800), Color(0xFFF44336))
    val avatarColor = remember(displayName) { colors[Math.floorMod(displayName.hashCode(), colors.size)] }
    val initial = remember(displayName) { displayName.firstOrNull()?.uppercase() ?: "?" }

    Row(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(12.dp)) {
            if (unread > 0) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        }

        Box(
            modifier = Modifier.size(50.dp).clip(CircleShape).background(avatarColor),
            contentAlignment = Alignment.Center
        ) {
            Text(initial, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(timestamp)), fontSize = 12.sp, color = Color.Gray) 
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(body, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Normal)
        }
    }
}