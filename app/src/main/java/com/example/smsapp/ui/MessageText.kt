package com.example.smsapp.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextLayoutResult

private val phoneRegex = Regex("""(?<![\w+])\+?\(?\d[\d ().-]{5,}\d(?!\w)""")
private val dateLike = Regex("""\d{1,4}[./-]\d{1,2}[./-]\d{1,4}""")
private val thousandsGrouped = Regex("""\d{1,3}( \d{3})+""")

/** A sender you can reply to: a number, as opposed to a sender name such as AIRTEL or AX-HDFCBK. */
fun isReplyableAddress(address: String): Boolean = address.none { it.isLetter() }

/** A normal phone number (not a short code or sender name). */
fun isPhoneNumber(address: String): Boolean = isReplyableAddress(address) && address.count { it.isDigit() } >= 7

/** Digits and a leading plus only, ready for the dialer. */
fun dialableNumber(raw: String): String = raw.filter { it.isDigit() || it == '+' }

private fun looksLikePhone(raw: String): Boolean {
    val digits = raw.count { it.isDigit() }
    if (digits !in 7..15) return false
    if (dateLike.matches(raw) || thousandsGrouped.matches(raw)) return false
    return when {
        raw.startsWith("+") -> digits in 8..15            // international format
        raw.startsWith("0") -> digits in 9..12            // local format with trunk prefix
        raw.any { it == ' ' || it == '-' || it == '(' || it == '.' } -> true   // grouped, e.g. (555) 123-4567
        else -> digits == 10                              // plain 10 digits
    }
}

/** Ranges in [text] that look like phone numbers. Other numbers (codes, amounts, dates, references) are left alone. */
fun findPhoneNumbers(text: String): List<IntRange> =
    phoneRegex.findAll(text).filter { looksLikePhone(it.value) }.map { it.range }.toList()

/** The phone number under a touch position in a laid-out message, or null if the touch is not on one. */
internal fun numberAt(layout: TextLayoutResult?, position: Offset, text: String, ranges: List<IntRange>): String? {
    val l = layout ?: return null
    val offset = l.getOffsetForPosition(position)
    if (offset !in text.indices) return null
    if (!l.getBoundingBox(offset).inflate(16f).contains(position)) return null
    return ranges.firstOrNull { offset in it }?.let { text.substring(it.first, it.last + 1) }
}
