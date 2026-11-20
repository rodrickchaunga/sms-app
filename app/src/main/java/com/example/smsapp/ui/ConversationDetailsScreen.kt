package com.example.smsapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smsapp.appearance.ChatBackground
import com.example.smsapp.data.ConversationPrefsEntity
import com.example.smsapp.data.SmsRepository
import kotlinx.coroutines.launch

/** Opened by tapping the name at the top of a chat: who the person is, notification and theme settings, and conversation actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailsScreen(
    repo: SmsRepository, address: String, displayName: String, messageCount: Int, privateMode: Boolean, canCall: Boolean,
    onBack: () -> Unit, onCall: () -> Unit, onNotifications: () -> Unit,
    onBlock: () -> Unit, onArchive: () -> Unit, onUnvault: () -> Unit, onDelete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var theme by remember { mutableStateOf<ConversationPrefsEntity?>(null) }
    LaunchedEffect(address) { theme = repo.conversationPrefs(address) ?: ConversationPrefsEntity(address) }
    fun saveTheme(background: String?, outgoing: Int?, incoming: Int?) {
        theme = theme?.copy(themeBackground = background, themeOutgoing = outgoing, themeIncoming = incoming)
        scope.launch { repo.saveConversationTheme(address, background, outgoing, incoming) }
    }
    val avatarColors = listOf(Color(0xFF2196F3), Color(0xFF4CAF50), Color(0xFF9C27B0), Color(0xFFFF9800), Color(0xFFF44336))
    val avatarColor = remember(displayName) { avatarColors[Math.floorMod(displayName.hashCode(), avatarColors.size)] }
    val initial = remember(displayName) { displayName.firstOrNull()?.uppercase() ?: "?" }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Contact details", fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(84.dp).clip(CircleShape).background(avatarColor), contentAlignment = Alignment.Center) {
                    Text(initial, color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                }
                Text(displayName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                if (displayName != address) Text(address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (canCall) FilledTonalButton(onClick = onCall, modifier = Modifier.padding(top = 12.dp)) {
                    Icon(Icons.Default.Call, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Call")
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            Text("Details", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            ListItem(headlineContent = { Text("Name") }, supportingContent = { Text(if (displayName == address) "Not in your contacts" else displayName) })
            ListItem(headlineContent = { Text("Phone number") }, supportingContent = { Text(address) })
            ListItem(headlineContent = { Text("Messages") }, supportingContent = { Text("$messageCount in this conversation") })
            HorizontalDivider()

            Text("Notifications", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
            ListItem(
                headlineContent = { Text("Notification settings") },
                supportingContent = { Text(if (privateMode) "Mute this conversation" else "Mute, sound and vibration for this conversation") },
                trailingContent = { TextButton(onClick = onNotifications) { Text("Open") } }
            )
            HorizontalDivider()

            Text("Theme", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            theme?.let { t ->
                Text("Only affects this conversation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                Text("Background", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 6.dp))
                ChoiceChips(listOf<String?>(null) + ChatBackground.values().map { it.name }, t.themeBackground, { name -> if (name == null) "App default" else ChatBackground.valueOf(name).label }) { choice ->
                    saveTheme(choice, t.themeOutgoing, t.themeIncoming)
                }
                Text("Your bubbles", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                FilterChip(selected = t.themeOutgoing == null, onClick = { saveTheme(t.themeBackground, null, t.themeIncoming) }, label = { Text("App default") }, modifier = Modifier.padding(bottom = 6.dp))
                SwatchRow(BubbleColors, t.themeOutgoing) { saveTheme(t.themeBackground, it, t.themeIncoming) }
                Text("Their bubbles", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                FilterChip(selected = t.themeIncoming == null, onClick = { saveTheme(t.themeBackground, t.themeOutgoing, null) }, label = { Text("App default") }, modifier = Modifier.padding(bottom = 6.dp))
                SwatchRow(BubbleColors, t.themeIncoming) { saveTheme(t.themeBackground, t.themeOutgoing, it) }
                if (t.themeBackground != null || t.themeOutgoing != null || t.themeIncoming != null) {
                    TextButton(onClick = { saveTheme(null, null, null) }) { Text("Reset theme") }
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))

            Text("Conversation", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
            ListItem(headlineContent = { Text("Block number") }, modifier = Modifier.clickable(onClick = onBlock))
            ListItem(headlineContent = { Text("Archive conversation") }, modifier = Modifier.clickable(onClick = onArchive))
            if (privateMode) ListItem(headlineContent = { Text("Remove private lock") }, modifier = Modifier.clickable(onClick = onUnvault))
            ListItem(headlineContent = { Text("Delete conversation", color = MaterialTheme.colorScheme.error) }, modifier = Modifier.clickable(onClick = onDelete))
        }
    }
}
