package com.example.smsapp.ui

import android.content.Context
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactEntry(val name: String, val number: String)

suspend fun loadContacts(context: Context): List<ContactEntry> = withContext(Dispatchers.IO) {
    val result = mutableListOf<ContactEntry>()
    val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
    context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE")?.use { c ->
        val name = c.getColumnIndexOrThrow(projection[0]); val number = c.getColumnIndexOrThrow(projection[1])
        while (c.moveToNext()) result += ContactEntry(c.getString(name).orEmpty(), c.getString(number).orEmpty())
    }
    result.distinctBy { it.number }
}

/**
 * Pick a person for a conversation: search the contact list, or (when [allowTypedNumber]) type a number.
 * Used for "new conversation" and for choosing a contact inside the private space.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationScreen(
    title: String = "New conversation",
    allowTypedNumber: Boolean = true,
    onBack: () -> Unit,
    onChoose: (String) -> Unit
) {
    val context = LocalContext.current
    var contacts by remember { mutableStateOf<List<ContactEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { contacts = runCatching { loadContacts(context) }.getOrDefault(emptyList()) }
    BackHandler(onBack = onBack)

    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = onBack) { Text("← Back", fontWeight = FontWeight.Bold) } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(12.dp),
                label = { Text(if (allowTypedNumber) "Name or phone number" else "Search contacts") }, singleLine = true
            )
            val all = contacts
            val typed = query.trim()
            val looksLikeNumber = allowTypedNumber && typed.count(Char::isDigit) >= 3 && typed.all { it.isDigit() || it in "+-() ." }
            LazyColumn {
                if (looksLikeNumber) item(key = "typed-number") {
                    ListItem(headlineContent = { Text("Send to $typed") }, supportingContent = { Text("Use this number") }, modifier = Modifier.clickable { onChoose(typed) })
                    HorizontalDivider()
                }
                if (all == null) item(key = "loading") { Text("Loading contacts…", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else {
                    val filtered = all.filter { it.name.contains(query, true) || it.number.contains(query, true) }
                    if (filtered.isEmpty() && !looksLikeNumber) item(key = "empty") {
                        Text(if (query.isBlank()) "No contacts found or Contacts permission is unavailable." else "No matching contacts.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(filtered, key = { it.number }) { contact ->
                        ListItem(headlineContent = { Text(contact.name) }, supportingContent = { Text(contact.number) }, modifier = Modifier.clickable { onChoose(contact.number) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
