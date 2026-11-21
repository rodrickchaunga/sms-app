package com.example.smsapp.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Date and time labels in the style of Google Messages. */
object ChatTime {
    /** A date header is drawn between two messages on different days, or this far apart on the same day. */
    const val SEPARATOR_GAP_MS = 60L * 60L * 1000L

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun dateOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

    private fun clock(ts: Long, use24h: Boolean): String {
        val pattern = if (use24h) "HH:mm" else "h:mm a"
        val text = DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(Instant.ofEpochMilli(ts).atZone(zone))
        return text.replace("AM", "am").replace("PM", "pm")
    }

    fun needsSeparator(previous: Long, current: Long): Boolean =
        dateOf(previous) != dateOf(current) || current - previous >= SEPARATOR_GAP_MS

    /** Conversation list: "21/11, 4:01 pm". Other years add the year: "21/11/25, 4:01 pm". */
    fun listStamp(ts: Long, use24h: Boolean): String {
        val date = dateOf(ts)
        val pattern = if (date.year == LocalDate.now(zone).year) "dd/MM" else "dd/MM/yy"
        return "${DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(date)}, ${clock(ts, use24h)}"
    }

    /** Message details: "Today, 7:08 am", "Yesterday, 7:08 am", "6 Nov, 9:26 pm" (year added for older years). */
    fun detail(ts: Long, use24h: Boolean): String {
        val date = dateOf(ts)
        val today = LocalDate.now(zone)
        val time = clock(ts, use24h)
        return when {
            date == today -> "Today, $time"
            date == today.minusDays(1) -> "Yesterday, $time"
            date.year == today.year -> "${DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(date)}, $time"
            else -> "${DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(date)}, $time"
        }
    }

    /** Header inside a conversation: "11:36 am" today, "Yesterday • 7:32 am", "Monday • 7:32 am", "12 Nov • 7:32 am". */
    fun separator(ts: Long, use24h: Boolean): String {
        val date = dateOf(ts)
        val today = LocalDate.now(zone)
        val days = ChronoUnit.DAYS.between(date, today)
        val time = clock(ts, use24h)
        return when {
            days == 0L -> time
            days == 1L -> "Yesterday • $time"
            days in 2L..6L -> "${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())} • $time"
            date.year == today.year -> "${DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault()).format(date)} • $time"
            else -> "${DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale.getDefault()).format(date)} • $time"
        }
    }
}
