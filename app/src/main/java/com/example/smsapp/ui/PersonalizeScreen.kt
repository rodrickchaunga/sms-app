package com.example.smsapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smsapp.SmsApp
import com.example.smsapp.appearance.AppearanceSettings
import com.example.smsapp.appearance.BubbleStyle
import com.example.smsapp.appearance.ChatBackground
import com.example.smsapp.appearance.LocalAppearance
import com.example.smsapp.appearance.MessageFont
import com.example.smsapp.appearance.MessageSpacing
import com.example.smsapp.appearance.ThemeMode
import com.example.smsapp.appearance.bubbleColor
import com.example.smsapp.appearance.bubbleShape
import com.example.smsapp.appearance.chatBackgroundColor
import com.example.smsapp.appearance.contrastRatio
import com.example.smsapp.appearance.contrastingText
import com.example.smsapp.appearance.parseHexColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val AccentColors = listOf(
    "Blue" to 0xFF1565C0.toInt(), "Teal" to 0xFF00796B.toInt(), "Green" to 0xFF2E7D32.toInt(), "Orange" to 0xFFE65100.toInt(),
    "Red" to 0xFFC62828.toInt(), "Purple" to 0xFF6A1B9A.toInt(), "Pink" to 0xFFAD1457.toInt(), "Slate" to 0xFF455A64.toInt()
)
internal val BubbleColors = listOf(
    "Sky" to 0xFFBBDEFB.toInt(), "Mint" to 0xFFC8E6C9.toInt(), "Lemon" to 0xFFFFF59D.toInt(), "Rose" to 0xFFF8BBD0.toInt(),
    "Lavender" to 0xFFD1C4E9.toInt(), "Peach" to 0xFFFFCCBC.toInt(), "Grey" to 0xFFE0E0E0.toInt(),
    "Ocean" to 0xFF0277BD.toInt(), "Forest" to 0xFF2E7D32.toInt(), "Charcoal" to 0xFF37474F.toInt()
)

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))

@Composable
private fun ColorSetting(title: String, options: List<Pair<String, Int>>, selected: Int?, allowDefault: Boolean, onSelect: (Int?) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
    if (allowDefault) FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Theme default") }, modifier = Modifier.padding(bottom = 6.dp))
    SwatchRow(options, selected) { onSelect(it) }
    var hex by remember(selected) { mutableStateOf(selected?.let { "#%06X".format(it and 0xFFFFFF) } ?: "") }
    OutlinedTextField(
        value = hex,
        onValueChange = { value -> hex = value; parseHexColor(value)?.let { onSelect(it) } },
        label = { Text("Custom colour (#RRGGBB)") },
        singleLine = true,
        isError = hex.isNotEmpty() && parseHexColor(hex) == null,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
    )
}

@Composable
private fun ChatPreview(s: AppearanceSettings) {
    Surface(color = chatBackgroundColor(s), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(s.spacing.gap.dp)) {
            listOf(false to "Are we still on for lunch?", true to "Yes, see you at 1 pm.").forEach { (outgoing, text) ->
                val bg = bubbleColor(s, outgoing)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
                    Surface(color = bg, shape = bubbleShape(s.bubbleStyle, outgoing)) {
                        Text(text, color = contrastingText(bg), fontFamily = s.messageFont.family(), modifier = Modifier.padding(horizontal = 12.dp, vertical = s.spacing.pad.dp))
                    }
                }
            }
        }
    }
}

/** Every control here is persisted in DataStore and applied app-wide by SmsTheme / the conversation screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as SmsApp).appearance }
    val settings by store.flow.collectAsState(initial = LocalAppearance.current)
    val scope = rememberCoroutineScope()
    fun update(transform: (AppearanceSettings) -> AppearanceSettings) { scope.launch { store.update(transform) } }
    var scale by remember(settings.fontScale) { mutableStateOf(settings.fontScale) }

    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = { Text("Personalize") }, actions = { TextButton(onClick = { scope.launch { store.reset() } }) { Text("Reset") } })
    }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            ChatPreview(settings)

            SectionTitle("Theme")
            ChoiceChips(ThemeMode.values().toList(), settings.themeMode, { it.label }) { mode -> update { it.copy(themeMode = mode) } }

            SectionTitle("Accent colour")
            SwatchRow(AccentColors, settings.accentArgb) { argb -> update { it.copy(accentArgb = argb) } }
            var accentHex by remember(settings.accentArgb) { mutableStateOf("#%06X".format(settings.accentArgb and 0xFFFFFF)) }
            OutlinedTextField(
                value = accentHex,
                onValueChange = { value -> accentHex = value; parseHexColor(value)?.let { argb -> update { s -> s.copy(accentArgb = argb) } } },
                label = { Text("Custom colour (#RRGGBB)") }, singleLine = true,
                isError = parseHexColor(accentHex) == null,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            if (contrastRatio(Color(settings.accentArgb), MaterialTheme.colorScheme.background) < 3f)
                Text("This colour is hard to read on the current theme, so it is lightened or darkened automatically.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))

            SectionTitle("Message bubbles")
            Text("Shape", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 6.dp))
            ChoiceChips(BubbleStyle.values().toList(), settings.bubbleStyle, { it.label }) { style -> update { it.copy(bubbleStyle = style) } }
            ColorSetting("Outgoing bubble colour", BubbleColors, settings.outgoingBubbleArgb, true) { argb -> update { it.copy(outgoingBubbleArgb = argb) } }
            ColorSetting("Incoming bubble colour", BubbleColors, settings.incomingBubbleArgb, true) { argb -> update { it.copy(incomingBubbleArgb = argb) } }
            Text("Text colour on bubbles is chosen automatically for readability.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))

            SectionTitle("Conversation")
            Text("Background", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 6.dp))
            ChoiceChips(ChatBackground.values().toList(), settings.background, { it.label }) { bg -> update { it.copy(background = bg) } }
            Text("Spacing", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            ChoiceChips(MessageSpacing.values().toList(), settings.spacing, { it.label }) { sp -> update { it.copy(spacing = sp) } }
            Text("Message font", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            ChoiceChips(MessageFont.values().toList(), settings.messageFont, { it.label }) { f -> update { it.copy(messageFont = f) } }

            SectionTitle("Font size")
            Text("${(scale * 100).roundToInt()}% of the system size", style = MaterialTheme.typography.bodyMedium)
            Slider(value = scale, onValueChange = { scale = it }, onValueChangeFinished = { update { s -> s.copy(fontScale = scale) } }, valueRange = 0.85f..1.5f, steps = 12)
            // The app-wide scale is already applied to every sp, so divide it out to preview the pending value.
            Text("The quick brown fox jumps over the lazy dog", fontSize = (16f * scale / settings.fontScale).sp)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}
