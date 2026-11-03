package com.designprototype.workshop.ui

import kotlin.math.floor

const val CM_PER_INCH: Double = 2.54

private val NUMERIC_RE = Regex("""^[ \t]*([0-9]+\.?[0-9]*|\.[0-9]+)[ \t]*$""")

private const val DECIMAL_10_2_MAX_SCALED: Long = 9_999_999_999L

fun parseDimension(text: String): Double? {
    if (!NUMERIC_RE.matches(text)) return null
    val value = text.trim().toDoubleOrNull() ?: return null
    if (!value.isFinite()) return null
    return value
}

private fun cmScaledFromInches(text: String): Long? {
    val value = parseDimension(text) ?: return null
    val scaled = floor(value * CM_PER_INCH * 100.0 + 0.5)
    if (scaled > DECIMAL_10_2_MAX_SCALED) return null
    return scaled.toLong()
}

private fun inchesScaledFromCm(text: String): Long? {
    val value = parseDimension(text) ?: return null
    val scaled = floor(value / CM_PER_INCH * 100.0 + 0.5)
    if (scaled > DECIMAL_10_2_MAX_SCALED) return null
    return scaled.toLong()
}

private fun renderScaled(scaled: Long): String {
    val whole = scaled / 100
    val frac = scaled % 100
    return when {
        frac == 0L -> whole.toString()
        frac % 10L == 0L -> "$whole.${frac / 10}"
        else -> "$whole." + frac.toString().padStart(2, '0')
    }
}

fun cmTextFromInches(text: String): String? = cmScaledFromInches(text)?.let(::renderScaled)

fun inchesTextFromCm(text: String): String? = inchesScaledFromCm(text)?.let(::renderScaled)

fun propagateDimension(text: String, convert: (String) -> String?, setPartner: (String) -> Unit) {
    if (text.isBlank()) {
        setPartner("")
        return
    }
    val next = convert(text) ?: return
    setPartner(next)
}
