package com.example.smsapp.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.smsapp.data.ScheduledSmsEntity
import com.example.smsapp.data.SimManager
import com.example.smsapp.data.SmsRepository
import com.example.smsapp.schedule.Scheduler
import kotlinx.coroutines.launch
import java.util.Calendar

private const val VAULT_PREFIX = "vault:v1:"

private fun statusText(item: ScheduledSmsEntity): String = when (item.status) {
    "pending" -> "Pending"
    "sending" -> "Sending…"
    "sent" -> "Sent to carrier"
    "cancelled" -> "Cancelled"
    "failed" -> "Failed" + (item.failureReason?.let { ": $it" } ?: "")
    else -> item.status
}

/** Lists every scheduled message with its state and the actions that are valid for that state. */
@Composable
fun ScheduledMessagesDialog(repo: SmsRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by repo.scheduledMessages().collectAsState(initial = emptyList())
    // Fail closed: until we know which numbers are private, everything is masked.
    val privateAddresses by produceState<Set<String>?>(initialValue = null, items) { value = repo.vaultedAmong(items.map { it.address }) }
    var editing by remember { mutableStateOf<ScheduledSmsEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    fun act(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { Toast.makeText(context, it.message ?: "Action failed", Toast.LENGTH_LONG).show() } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scheduled messages") },
        text = {
            if (items.isEmpty()) Text("Nothing scheduled yet.")
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(items, key = { it.id }) { item ->
                    val hidden = privateAddresses.let { it == null || item.address in it } || item.body.startsWith(VAULT_PREFIX)
                    val title = if (hidden) "Private number ••${item.address.takeLast(2)}" else item.address
                    val preview = if (hidden) "Private message" else item.body.take(90)
                    ListItem(
                        headlineContent = { Text(title) },
                        supportingContent = { Text("${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(item.sendAtMillis))} · ${statusText(item)}\n$preview") },
                        trailingContent = {
                            if (item.status != "sending") {
                                IconButton(onClick = { menuFor = item.id }) { Icon(Icons.Default.MoreVert, contentDescription = "Actions") }
                                DropdownMenu(expanded = menuFor == item.id, onDismissRequest = { menuFor = null }) {
                                    if (item.status == "failed") DropdownMenuItem(text = { Text("Retry now") }, onClick = { menuFor = null; act { Scheduler.retryNow(context, item.id) } })
                                    if (item.status != "sent") DropdownMenuItem(text = { Text(if (item.status == "pending") "Edit / reschedule" else "Edit and reschedule") }, onClick = { menuFor = null; editing = item })
                                    if (item.status == "pending" || item.status == "failed") DropdownMenuItem(text = { Text("Cancel sending") }, onClick = { menuFor = null; act { Scheduler.cancel(context, item.id) } })
                                    if (item.status != "pending") DropdownMenuItem(text = { Text("Remove from list") }, onClick = { menuFor = null; act { Scheduler.delete(context, item.id) } })
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            Row {
                TextButton(onClick = { act { Scheduler.clearFinished(context) } }) { Text("Clear finished") }
                TextButton(onClick = { creating = true }) { Text("New") }
            }
        }
    )
    if (creating || editing != null) ScheduleEditorDialog(repo = repo, existing = editing, onDismiss = { creating = false; editing = null })
}

/** Create (existing == null) or edit/reschedule. Private messages keep their content hidden; only time and SIM can change. */
@Composable
fun ScheduleEditorDialog(repo: SmsRepository, existing: ScheduledSmsEntity?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPrivate by produceState<Boolean?>(initialValue = if (existing == null) false else null) {
        value = existing?.let { it.body.startsWith(VAULT_PREFIX) || repo.isVaulted(it.address) } ?: false
    }
    var recipient by remember { mutableStateOf(existing?.address.orEmpty()) }
    var message by remember { mutableStateOf(existing?.body.orEmpty()) }
    var sendAt by remember { mutableStateOf(existing?.sendAtMillis?.takeIf { it > System.currentTimeMillis() } ?: (System.currentTimeMillis() + 10 * 60_000L)) }
    var sim by remember { mutableStateOf(existing?.subscriptionId) }
    var showSimPicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sims = remember { SimManager.activeSims(context) }

    fun pickDate() {
        val c = Calendar.getInstance().apply { timeInMillis = sendAt }
        DatePickerDialog(context, { _, y, m, d ->
            sendAt = Calendar.getInstance().apply { timeInMillis = sendAt; set(y, m, d) }.timeInMillis
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }
    fun pickTime() {
        val c = Calendar.getInstance().apply { timeInMillis = sendAt }
        TimePickerDialog(context, { _, h, min ->
            sendAt = Calendar.getInstance().apply { timeInMillis = sendAt; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), DateFormat.is24HourFormat(context)).show()
    }

    val simText = when {
        sim == null -> "Follow conversation / app setting"
        sim == SimManager.SYSTEM_DEFAULT -> "System default SIM"
        else -> sims.firstOrNull { it.subscriptionId == sim }?.label ?: "Selected SIM unavailable"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Schedule SMS" else "Edit scheduled SMS") },
        text = {
            Column {
                when (isPrivate) {
                    null -> Text("Checking privacy…")
                    true -> Text("Private conversation: recipient and text stay hidden. You can change the time and SIM.")
                    false -> {
                        OutlinedTextField(recipient, { recipient = it }, label = { Text("Phone number") }, singleLine = true)
                        OutlinedTextField(message, { message = it }, label = { Text("Message") })
                    }
                }
                Row(Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { pickDate() }) { Text(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(sendAt))) }
                    OutlinedButton(onClick = { pickTime() }, modifier = Modifier.padding(start = 8.dp)) { Text(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(sendAt))) }
                }
                if (sims.size > 1 || sim != null) OutlinedButton(onClick = { showSimPicker = true }, modifier = Modifier.padding(top = 8.dp)) { Text(simText) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = isPrivate != null, onClick = {
                scope.launch {
                    runCatching {
                        if (existing == null) Scheduler.create(context, recipient, message, sendAt, sim)
                        else if (isPrivate == true) Scheduler.update(context, existing.id, null, null, sendAt, sim)
                        else Scheduler.update(context, existing.id, recipient, message, sendAt, sim)
                    }.onSuccess { onDismiss() }.onFailure { error = it.message ?: "Could not save" }
                }
            }) { Text(if (existing == null) "Schedule" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (showSimPicker) SimPickerDialog("SIM for this message", sims, sim, "Follow conversation / app setting", true, { sim = it; showSimPicker = false }, { showSimPicker = false })
}
