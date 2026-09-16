package com.designprototype.workshop.ui

import kotlin.math.floor

/*
 * CENTIMETRES ↔ INCHES FOR THE RECORD FORMS — the Kotlin half of
 * `frontend/components/forms/dimensionUnits.ts`, arithmetic for arithmetic.
 *
 * ── WHAT THIS IS FOR ──────────────────────────────────────────────────────────────────────────
 *
 * `ToolDocumentation` carries `height` and `width` as bare `Decimal(10,2)` columns and
 * `heightInches` / `breadthInches` / `lengthInches` beside them. Since 2026-09-15 the first two are
 * CENTIMETRES and each pairs 1:1 with one of the inch boxes — Height (cm) ↔ Height (inches), Width
 * (cm) ↔ Breadth (inches) — so a designer fills either box of a pair and the other follows. Length
 * (inches) keeps no partner and gets no centimetre column.
 *
 * ── WHY IT LIVES IN `ui/` AND NOT IN `MainActivity.kt` ────────────────────────────────────────
 *
 * The same reason `ui/RecordPickers.kt` gives for `craftsChangeClearsArtisans`: this is where a JVM
 * unit test can reach it. `DimensionUnitsTest` drives every row of the cross-surface table, and a
 * judgement written inside a `@Composable` in `MainActivity.kt` could not be exercised at all —
 * there is no `ui-test-junit4` and no Robolectric in `app/build.gradle.kts`.
 *
 * ── WHY IT IS A SECOND IMPLEMENTATION AND NOT A CALL TO THE SERVER'S ─────────────────────────
 *
 * `backend/app/services/design_workshops._inches_to_cm` converts too, and the two MUST NOT be
 * unified. That one is `round(float(v) * 2.54, 2)` — Python's round-half-to-EVEN — and it converts a
 * DIFFERENT number for a different reader: a tool's inch height into a design-workshop stage's
 * `heightCm` box, on the server, after the record is saved. This one is round-half-UP and runs
 * against the keystrokes of somebody typing into a record form. Unifying them would change every
 * workshop carry figure that lands on a halfway value, silently, in documents that have already been
 * printed. Two jobs, two rules, and a comment on each naming the other.
 *
 * `ui/designworkshop/DwReferenceField.kt`'s "NO UNIT CONVERSION HAPPENS HERE, AND NONE MAY EVER BE
 * ADDED" is a third thing again and is unaffected: that rule is about the STAGE side, where the
 * server has already converted and a second conversion would double it.
 *
 * ── THE PARITY RULES, WHICH ARE WHY THIS FILE LOOKS OVER-ENGINEERED ──────────────────────────
 *
 * Four clients implement this — two browsers and two handsets — and they must agree to the last
 * hundredth on the same typed string, because the number they write is the number a ministry reads.
 * Three properties carry that guarantee, and every one of them is a line somebody could "tidy":
 *
 *   1. ONE GRAMMAR, ASSERTED BY REGEX, because the two languages' own parsers disagree at the
 *      edges. `Number("0x1A")` is 26 while `"0x1A".toDouble()` throws; `"1.5f".toDouble()` is 1.5
 *      while `Number("1.5f")` is NaN; both accept "Infinity" and "NaN". A regex both languages
 *      implement identically is the only shape that cannot drift.
 *   2. ONE ROUNDING, `floor(x + 0.5)`, spelled out rather than delegated. `kotlin.math.round` is
 *      half-away-from-zero and JVM `Math.round` is `floor(x+0.5)`; the two differ for negatives, and
 *      leaving that latent in a file whose grammar happens to admit no sign today is how it becomes
 *      a defect the day the grammar changes.
 *   3. NO FLOAT-TO-STRING. [renderScaled] builds the text out of the scaled INTEGER, because JS
 *      prints "3" for an integral 3.0 where Kotlin prints "3.0", and the two disagree again on large
 *      magnitudes. The digits a designer sees are assembled, never formatted.
 *
 * Association order is load-bearing too. `v * 2.54 * 100.0` must not become `v * 254.0`, and
 * `v / 2.54 * 100.0` must not become `v * (100.0 / 2.54)`: each rewrite moves the last ulp and can
 * flip a hundredth. Kotlin's `*` and `/` are left-associative and so are ECMAScript's, so the two
 * expressions below are the same sequence of correctly-rounded binary64 operations on both sides.
 */

/**
 * Centimetres in one inch, EXACT.
 *
 * The inch has been defined as 25.4 mm since the 1959 international yard-and-pound agreement, so
 * this is a definition rather than a measurement and there is no "2.5 for readability" available.
 * Named to match `dimensionUnits.ts`'s `CM_PER_INCH` and the backend's
 * `design_workshops._CM_PER_INCH`, so a grep for the constant finds all three.
 */
const val CM_PER_INCH: Double = 2.54

/**
 * The one grammar all four clients admit: optional space/tab padding around an unsigned decimal.
 *
 * `1`, `1.`, `.5`, `0.50` are numbers. `-1`, `1e3`, `0x1A`, `1.5f`, `Infinity`, `NaN`, `1,5` and
 * `1 000` are not, on every client, whatever the local parser would have made of them. No sign is
 * admitted at all, which is what makes "non-negative" a property of the grammar rather than a check
 * somebody has to remember to write.
 */
private val NUMERIC_RE = Regex("""^[ \t]*([0-9]+\.?[0-9]*|\.[0-9]+)[ \t]*$""")

/** 99,999,999.99 × 100 — the largest value `Decimal(10,2)` can hold, in hundredths. */
private const val DECIMAL_10_2_MAX_SCALED: Long = 9_999_999_999L

/**
 * [text] as a finite non-negative number, or null when it is not one.
 *
 * NULL IS NOT AN ERROR; it is the answer for every string a partner box must not be written from —
 * an empty box, a lone "-", a half-typed "1.2.", a word. The caller decides what to do with it, and
 * for the record forms the answer is "write nothing", which is the only answer that cannot put a
 * number the designer never typed into a column a ministry reads.
 *
 * THE `isFinite` CHECK IS REACHABLE, contrary to what a first reading of the grammar suggests: a
 * four-hundred-digit integer matches [NUMERIC_RE] perfectly and `Double.parseDouble` answers
 * `Infinity` for it, exactly as JavaScript's `Number` does. Keeping the check is what makes this a
 * total function rather than one that returns an infinity for somebody to multiply.
 */
fun parseDimension(text: String): Double? {
    if (!NUMERIC_RE.matches(text)) return null
    val value = text.trim().toDoubleOrNull() ?: return null
    if (!value.isFinite()) return null
    return value
}

/**
 * [text] read as inches, in hundredths of a centimetre, or null when there is nothing to convert.
 *
 * ONE ROUNDING, NOT TWO. Converting and then formatting from a re-derived double would round twice
 * and can move the last hundredth, which is the whole reason the scaled integer — not the converted
 * double — is what leaves this function.
 */
private fun cmScaledFromInches(text: String): Long? {
    val value = parseDimension(text) ?: return null
    val scaled = floor(value * CM_PER_INCH * 100.0 + 0.5)
    if (scaled > DECIMAL_10_2_MAX_SCALED) return null
    return scaled.toLong()
}

/** [text] read as centimetres, in hundredths of an inch. See [cmScaledFromInches]. */
private fun inchesScaledFromCm(text: String): Long? {
    val value = parseDimension(text) ?: return null
    val scaled = floor(value / CM_PER_INCH * 100.0 + 0.5)
    if (scaled > DECIMAL_10_2_MAX_SCALED) return null
    return scaled.toLong()
}

/**
 * Hundredths as the shortest honest decimal: `254` → "2.54", `2540` → "25.4", `25400` → "254".
 *
 * BUILT FROM THE INTEGER, never from `Double.toString()`. The trailing zero is dropped because that
 * is what a person typing "10" and seeing "25.4" expects, and because the browser's twin drops it —
 * the two clients must produce the same STRING, not merely the same quantity, or a designer moving
 * between them reads a record as edited when nothing changed.
 */
private fun renderScaled(scaled: Long): String {
    val whole = scaled / 100
    val frac = scaled % 100
    return when {
        frac == 0L -> whole.toString()
        frac % 10L == 0L -> "$whole.${frac / 10}"
        else -> "$whole." + frac.toString().padStart(2, '0')
    }
}

/** Inches as centimetres, rounded to two decimals, or null when [text] is not a number. */
fun cmTextFromInches(text: String): String? = cmScaledFromInches(text)?.let(::renderScaled)

/** Centimetres as inches, rounded to two decimals, or null when [text] is not a number. */
fun inchesTextFromCm(text: String): String? = inchesScaledFromCm(text)?.let(::renderScaled)

/**
 * Write [text]'s partner box, by the three rules that stop this pairing corrupting data.
 *
 * Called from the change handler of the box the designer is ACTUALLY TYPING IN, and from nowhere
 * else. That is not a style preference, it is the whole safety property:
 *
 *   ONE-DIRECTIONAL PER KEYSTROKE. The box being written must never turn round and re-derive its
 *   source. `1 cm → 0.39 in → 0.99 cm` is how a round trip eats a value, and a watcher — a
 *   `LaunchedEffect(height, heightInches)` — fires for BOTH values and is exactly that round trip.
 *   Compose state assignment does not re-invoke the target control's `onValueChange`, so writing the
 *   partner from inside the source's own handler is already the correct shape; there is nothing to
 *   add and there is a great deal to avoid adding.
 *
 *   CLEARING PROPAGATES. An empty box means "no value", and leaving a stale converted number
 *   standing beside it is a lie the next reader has no way to detect. This is the ONE case where a
 *   string that is not a number still writes the partner, which is why it is its own branch above
 *   the parse rather than a special case inside it.
 *
 *   A NON-NUMBER WRITES NOTHING. A partially typed decimal is a number, not a mistake: "1." parses
 *   and converts, so the partner tracks the keystrokes instead of blanking and refilling. Only a
 *   string that cannot be a number at all leaves the partner alone — and only an EMPTY box clears
 *   it, because empty is the one input that means "no value". So `1` → `1.` → `1.5` gives the
 *   partner `2.54` → `2.54` → `3.81` with no flicker, and `1.2.` holds `3.05` for the one keystroke
 *   it takes to finish typing. Stale for a moment beats blank-and-back, because blank is a claim.
 *
 * NEVER CALLED ON LOAD. Opening an existing record must not rewrite either box: historic rows
 * genuinely hold unrelated numbers in `height` and `heightInches` — the schema comment and the tool
 * form's own note both say so — and a load-time conversion would destroy them on the next save.
 */
fun propagateDimension(text: String, convert: (String) -> String?, setPartner: (String) -> Unit) {
    if (text.isBlank()) {
        setPartner("")
        return
    }
    val next = convert(text) ?: return
    setPartner(next)
}
