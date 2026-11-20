package com.example.smsapp.appearance

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }
enum class BubbleStyle(val label: String) { CLASSIC("Classic"), ROUNDED("Rounded"), PILL("Pill"), SQUARE("Square") }
enum class MessageSpacing(val label: String, val gap: Int, val pad: Int) { COMPACT("Compact", 3, 4), NORMAL("Normal", 8, 8), RELAXED("Relaxed", 14, 12) }
enum class MessageFont(val label: String) {
    DEFAULT("Default"), SERIF("Serif"), MONOSPACE("Monospace"), CURSIVE("Handwriting");
    fun family(): FontFamily = when (this) {
        DEFAULT -> FontFamily.Default
        SERIF -> FontFamily.Serif
        MONOSPACE -> FontFamily.Monospace
        CURSIVE -> FontFamily.Cursive
    }
}
enum class ChatBackground(val label: String, private val light: Long?, private val dark: Long?) {
    NONE("Theme", null, null),
    SAND("Sand", 0xFFF5EFE6, 0xFF1E1B16),
    SAGE("Sage", 0xFFE8F0E8, 0xFF161C17),
    SKY("Sky", 0xFFE6F0FA, 0xFF121A22),
    ROSE("Rose", 0xFFFAEAF0, 0xFF221419),
    NIGHT("Night", 0xFF101820, 0xFF101820);
    fun color(isDark: Boolean): Color? = (if (isDark) dark else light)?.let { Color(it) }
}

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentArgb: Int = 0xFF1565C0.toInt(),
    val outgoingBubbleArgb: Int? = null,
    val incomingBubbleArgb: Int? = null,
    val bubbleStyle: BubbleStyle = BubbleStyle.CLASSIC,
    val background: ChatBackground = ChatBackground.NONE,
    val fontScale: Float = 1f,
    val messageFont: MessageFont = MessageFont.DEFAULT,
    val spacing: MessageSpacing = MessageSpacing.NORMAL,
    val showCounter: Boolean = true,
    val showMultipart: Boolean = true
)

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance_v2")

private object K {
    val theme = stringPreferencesKey("theme_mode")
    val accent = intPreferencesKey("accent")
    val outgoing = intPreferencesKey("bubble_outgoing")
    val incoming = intPreferencesKey("bubble_incoming")
    val style = stringPreferencesKey("bubble_style")
    val background = stringPreferencesKey("chat_background")
    val fontScale = floatPreferencesKey("font_scale")
    val font = stringPreferencesKey("message_font")
    val spacing = stringPreferencesKey("message_spacing")
    val counter = booleanPreferencesKey("show_counter")
    val multipart = booleanPreferencesKey("show_multipart")
}

private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
    runCatching { enumValueOf<T>(name!!) }.getOrDefault(fallback)

private fun Preferences.toSettings(): AppearanceSettings {
    val d = AppearanceSettings()
    return AppearanceSettings(
        themeMode = enumOr(this[K.theme], d.themeMode),
        accentArgb = this[K.accent] ?: d.accentArgb,
        outgoingBubbleArgb = this[K.outgoing],
        incomingBubbleArgb = this[K.incoming],
        bubbleStyle = enumOr(this[K.style], d.bubbleStyle),
        background = enumOr(this[K.background], d.background),
        fontScale = (this[K.fontScale] ?: d.fontScale).coerceIn(0.85f, 1.5f),
        messageFont = enumOr(this[K.font], d.messageFont),
        spacing = enumOr(this[K.spacing], d.spacing),
        showCounter = this[K.counter] ?: d.showCounter,
        showMultipart = this[K.multipart] ?: d.showMultipart
    )
}

private fun MutablePreferences.write(s: AppearanceSettings) {
    this[K.theme] = s.themeMode.name
    this[K.accent] = s.accentArgb
    if (s.outgoingBubbleArgb != null) this[K.outgoing] = s.outgoingBubbleArgb else remove(K.outgoing)
    if (s.incomingBubbleArgb != null) this[K.incoming] = s.incomingBubbleArgb else remove(K.incoming)
    this[K.style] = s.bubbleStyle.name
    this[K.background] = s.background.name
    this[K.fontScale] = s.fontScale.coerceIn(0.85f, 1.5f)
    this[K.font] = s.messageFont.name
    this[K.spacing] = s.spacing.name
    this[K.counter] = s.showCounter
    this[K.multipart] = s.showMultipart
}

/** Appearance preferences in Preferences DataStore. Corrupt or missing values fall back to defaults. */
class AppearanceStore(private val context: Context) {
    val flow: Flow<AppearanceSettings> = context.appearanceDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    suspend fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        context.appearanceDataStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    suspend fun reset() { context.appearanceDataStore.edit { it.clear() } }
}

// ---- Contrast ----------------------------------------------------------------------------------

fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** Black or white, whichever reads better on [background]. Always at least 4.5:1 for any colour. */
fun contrastingText(background: Color): Color =
    if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) Color.Black else Color.White

fun adjustForContrast(color: Color, background: Color, minRatio: Float): Color {
    var c = color
    val target = if (background.luminance() > 0.5f) Color.Black else Color.White
    var i = 0
    while (contrastRatio(c, background) < minRatio && i < 12) { c = lerp(c, target, 0.12f); i++ }
    return c
}

fun buildColorScheme(accent: Color, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val primary = adjustForContrast(accent, base.background, 3f)
    val container = lerp(primary, base.surface, if (dark) 0.65f else 0.8f)
    return base.copy(
        primary = primary, onPrimary = contrastingText(primary),
        primaryContainer = container, onPrimaryContainer = contrastingText(container)
    )
}

fun parseHexColor(text: String): Int? {
    val t = text.trim().removePrefix("#")
    if (t.length != 6 || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return (0xFF000000L or t.toLong(16)).toInt()
}

// ---- Theme -------------------------------------------------------------------------------------

val LocalAppearance = staticCompositionLocalOf { AppearanceSettings() }
val LocalIsDark = staticCompositionLocalOf { false }

fun bubbleShape(style: BubbleStyle, outgoing: Boolean): Shape = when (style) {
    BubbleStyle.CLASSIC -> RoundedCornerShape(
        topStart = 16.dp, topEnd = 16.dp,
        bottomStart = if (outgoing) 16.dp else 4.dp,
        bottomEnd = if (outgoing) 4.dp else 16.dp
    )
    BubbleStyle.ROUNDED -> RoundedCornerShape(22.dp)
    BubbleStyle.PILL -> RoundedCornerShape(28.dp)
    BubbleStyle.SQUARE -> RoundedCornerShape(4.dp)
}

@Composable
fun bubbleColor(s: AppearanceSettings, outgoing: Boolean): Color =
    (if (outgoing) s.outgoingBubbleArgb else s.incomingBubbleArgb)?.let { Color(it) }
        ?: if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant

@Composable
fun chatBackgroundColor(s: AppearanceSettings): Color =
    s.background.color(LocalIsDark.current) ?: MaterialTheme.colorScheme.background

@Composable
fun SmsTheme(settings: AppearanceSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = remember(settings.accentArgb, dark) { buildColorScheme(Color(settings.accentArgb), dark) }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.decorView.setBackgroundColor(scheme.background.toArgb())
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val density = LocalDensity.current
    val scaled = remember(density, settings.fontScale) { Density(density.density, density.fontScale * settings.fontScale) }
    CompositionLocalProvider(LocalAppearance provides settings, LocalIsDark provides dark, LocalDensity provides scaled) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
