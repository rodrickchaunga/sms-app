package com.example.smsapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.smsapp.appearance.contrastingText
import com.example.smsapp.data.MessageEntity

/** What a long-press on a message opens: the message, when it was received/sent, its type, and the message actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailsScreen(
    message: MessageEntity,
    address: String,
    simLabel: String?,
    bubbleColor: Color,
    bubbleShape: Shape,
    textFont: FontFamily,
    onBack: () -> Unit,
    onRetry: (() -> Unit)?,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onStar: () -> Unit,
    onDelete: () -> Unit
) {
    val use24h = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)
    val rows = buildList {
        if (message.outgoing) {
            val inProgressOrFailed = message.status == "sending" || (message.status.startsWith("failed") && message.status != "delivery failed")
            if (inProgressOrFailed) {
                add("Status" to statusLabel(message.status))
                add("Time" to ChatTime.detail(message.timestamp, use24h))
            } else {
                add("Sent" to ChatTime.detail(message.timestamp, use24h))
                if (message.status == "delivered") add("Delivered" to (message.deliveredAt?.let { ChatTime.detail(it, use24h) } ?: "Time not recorded"))
                else if (message.status == "delivery failed") add("Status" to statusLabel(message.status))
            }
        } else {
            add("Received" to ChatTime.detail(message.receivedAt ?: message.timestamp, use24h))
            add("Sent" to ChatTime.detail(message.timestamp, use24h))
        }
    }
    val card = MaterialTheme.colorScheme.background

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                title = { Text("Details") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), color = card, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), contentAlignment = Alignment.Center) {
                    Surface(color = bubbleColor, shape = bubbleShape) {
                        Text(
                            message.body, color = contrastingText(bubbleColor), fontFamily = textFont,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
            }

            SectionLabel("Status")
            Column(Modifier.clip(RoundedCornerShape(28.dp)), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                rows.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().background(card).padding(horizontal = 20.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.weight(1f), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            SectionLabel("Type")
            Text("Text message", modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurface)
            SectionLabel(if (message.outgoing) "To" else "From")
            Text(address, modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurface)
            if (simLabel != null) {
                SectionLabel("SIM")
                Text(simLabel, modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurface)
            }

            SectionLabel("Actions")
            val none = ListItemDefaults.colors(containerColor = Color.Transparent)
            if (onRetry != null) ListItem(headlineContent = { Text("Retry sending") }, colors = none, modifier = Modifier.clickable(onClick = onRetry))
            ListItem(headlineContent = { Text("Select") }, colors = none, modifier = Modifier.clickable(onClick = onSelect))
            ListItem(headlineContent = { Text("Copy text") }, colors = none, modifier = Modifier.clickable(onClick = onCopy))
            ListItem(headlineContent = { Text("Forward") }, colors = none, modifier = Modifier.clickable(onClick = onForward))
            ListItem(headlineContent = { Text(if (message.isStarred) "Unstar" else "Star") }, colors = none, modifier = Modifier.clickable(onClick = onStar))
            ListItem(headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) }, colors = none, modifier = Modifier.clickable(onClick = onDelete))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 12.dp)
    )
}
