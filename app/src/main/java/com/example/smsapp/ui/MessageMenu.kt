package com.example.smsapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.example.smsapp.appearance.contrastingText
import com.example.smsapp.data.MessageEntity

/**
 * Long-press menu: the message is drawn again exactly where it was ([anchor], in window coordinates) and the actions sit
 * under it (above it when there is no room), on the same side. The chat behind is dimmed (and blurred on Android 12+).
 */
@Composable
fun MessageMenu(
    message: MessageEntity,
    anchor: Rect,
    secure: Boolean,
    bubbleColor: Color,
    bubbleShape: Shape,
    textFont: FontFamily,
    verticalPad: Dp,
    canRetry: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onForward: () -> Unit,
    onCopy: () -> Unit,
    onStar: () -> Unit,
    onDelete: () -> Unit,
    onSelectMore: () -> Unit,
    onInfo: () -> Unit
) {
    val density = LocalDensity.current
    val topLeft = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
        }
    }
    Popup(
        popupPositionProvider = topLeft,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit)
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        ) {
            // The message again, at the same place and width as in the chat.
            Surface(
                color = bubbleColor, shape = bubbleShape,
                modifier = Modifier.offset { IntOffset(anchor.left.roundToInt(), anchor.top.roundToInt()) }
                    .width(with(density) { anchor.width.toDp() })
            ) {
                Text(
                    message.body, color = contrastingText(bubbleColor), fontFamily = textFont,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = verticalPad)
                )
            }
            val margin = with(density) { 8.dp.roundToPx() }
            Layout(
                content = {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.width(230.dp)) {
                        Column(Modifier.padding(vertical = 8.dp)) {
                            if (canRetry) MenuRow(Icons.Default.Refresh, "Retry sending", onRetry)
                            MenuRow(Icons.AutoMirrored.Filled.ArrowForward, "Forward", onForward)
                            MenuRow(Icons.Default.ContentCopy, "Copy", onCopy)
                            MenuRow(if (message.isStarred) Icons.Default.Star else Icons.Default.StarBorder, if (message.isStarred) "Unstar" else "Star", onStar)
                            MenuRow(Icons.Default.Delete, "Delete", onDelete)
                            MenuRow(Icons.Default.SelectAll, "Select more", onSelectMore)
                            MenuRow(Icons.Default.Info, "Info", onInfo)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) { measurables, constraints ->
                val card = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
                val below = anchor.bottom.roundToInt() + margin
                val y = if (below + card.height <= constraints.maxHeight - margin) below
                    else (anchor.top.roundToInt() - margin - card.height).coerceAtLeast(margin)
                val wantedX = if (message.outgoing) anchor.right.roundToInt() - card.width else anchor.left.roundToInt()
                val x = wantedX.coerceIn(margin, (constraints.maxWidth - card.width - margin).coerceAtLeast(margin))
                layout(constraints.maxWidth, constraints.maxHeight) { card.place(x, y) }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(20.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface)
    }
}
