package com.example.smsapp.ui

import android.provider.ContactsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactEntry(val name: String, val number: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var contacts by remember { mutableStateOf<List<ContactEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) {
            val result = mutableListOf<ContactEntry>()
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
            context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE")?.use { c ->
                val name = c.getColumnIndexOrThrow(projection[0]); val number = c.getColumnIndexOrThrow(projection[1])
                while (c.moveToNext()) result += ContactEntry(c.getString(name).orEmpty(), c.getString(number).orEmpty())
            }
            result.distinctBy { it.number }
        }
    }
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("Contacts") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), label = { Text("Search contacts") }, singleLine = true)
            LazyColumn {
                val filtered = contacts.filter { it.name.contains(query, true) || it.number.contains(query, true) }
                if (filtered.isEmpty()) item { Text(if (query.isBlank()) "No contacts found or Contacts permission is unavailable." else "No matching contacts.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(filtered, key = { it.number }) { contact ->
                    ListItem(headlineContent = { Text(contact.name) }, supportingContent = { Text(contact.number) }, trailingContent = { TextButton(onClick = { onMessage(contact.number) }) { Text("Message") } }, modifier = Modifier.clickable { onMessage(contact.number) })
                    HorizontalDivider()
                }
            }
        }
    }
}
