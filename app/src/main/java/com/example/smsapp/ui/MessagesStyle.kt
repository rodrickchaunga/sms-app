package com.example.smsapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smsapp.appearance.contrastingText

/** Shared look of the main screens: a lifted top bar with the content sheet rounded underneath it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun smsTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
)

fun Modifier.roundedTop(radius: Dp = 28.dp): Modifier = clip(RoundedCornerShape(topStart = radius, topEnd = radius))

private val AvatarPalette = listOf(
    Color(0xFFB061F8), Color(0xFF4AD1E7), Color(0xFFFFC82D), Color(0xFFFF8A4C), Color(0xFF5BBF6A), Color(0xFFF2709C)
)

fun avatarColorFor(key: String): Color = AvatarPalette[Math.floorMod(key.hashCode(), AvatarPalette.size)]

/** Saved contact: first letter of the name. Anyone else (unsaved number, sender name): a person icon. */
@Composable
fun ContactAvatar(name: String, isContact: Boolean, size: Dp, modifier: Modifier = Modifier, selected: Boolean = false) {
    val base = remember(name) { avatarColorFor(name) }
    val background = if (selected) MaterialTheme.colorScheme.primary else base
    val foreground = contrastingText(background)
    Box(modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        when {
            selected -> Icon(Icons.Default.Check, contentDescription = "Selected", tint = foreground)
            isContact -> Text(name.firstOrNull()?.uppercase() ?: "?", color = foreground, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium)
            else -> Icon(Icons.Default.Person, contentDescription = null, tint = foreground, modifier = Modifier.size(size * 0.58f))
        }
    }
}
