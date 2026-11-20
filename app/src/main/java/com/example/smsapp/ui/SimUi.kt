package com.example.smsapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smsapp.data.SimInfo
import com.example.smsapp.data.SimManager

/** null = "no explicit choice" (labelled by [nullLabel]); SimManager.SYSTEM_DEFAULT = let Android choose. */
@Composable
fun SimPickerDialog(
    title: String,
    sims: List<SimInfo>,
    current: Int?,
    nullLabel: String?,
    showSystemOption: Boolean,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    val options = buildList<Pair<Int?, String>> {
        if (nullLabel != null) add(Pair(null, nullLabel))
        if (showSystemOption) add(Pair(SimManager.SYSTEM_DEFAULT, "System default SIM"))
        sims.forEach { add(Pair(it.subscriptionId, it.label)) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (sims.isEmpty()) Text("No SIM list available. Allow phone access in Settings to choose a specific SIM.", modifier = Modifier.padding(bottom = 8.dp))
                options.forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = current == value, onClick = { onSelect(value) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
