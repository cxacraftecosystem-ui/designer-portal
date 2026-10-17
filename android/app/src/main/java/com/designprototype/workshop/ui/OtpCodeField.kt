package com.designprototype.workshop.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The pure rules of a six-digit code, the web's `components/ui/otpCode.ts` restated — pinned by
 * `OtpCodeTest`, so one sequence of keys leaves the same six boxes on both clients.
 *
 * DIGITS ONLY, ASCII ONLY: a typed letter, space, hyphen or full-width "１" is refused and the code
 * does not change; the same characters inside a PASTE (or an SMS autofill) are stripped, because
 * "123 456" and "123-456" are how authenticators and messages print a code. Never more than six.
 */
object OtpCode {
    const val LENGTH = 6

    fun onlyDigits(text: String): String = text.filter { it in '0'..'9' }

    fun isComplete(value: String): Boolean = value.length == LENGTH && value.all { it in '0'..'9' }

    /**
     * What the field holds after the text box reported [incoming], given it held [previous].
     * Returns [previous] unchanged when the edit was ONE non-digit character typed (refused); otherwise
     * every digit, in order, at most six — which covers typing, Backspace, and a paste of the whole code.
     */
    fun accept(previous: String, incoming: String): String {
        val digits = onlyDigits(incoming)
        val typedOneNonDigit = incoming.length == previous.length + 1 && digits.length == onlyDigits(previous).length
        if (typedOneNonDigit) return onlyDigits(previous).take(LENGTH)
        return digits.take(LENGTH)
    }

    /** The accessible name of box [index] (zero-based): "Digit 3 of 6". */
    fun digitLabel(index: Int): String = "Digit ${index + 1} of $LENGTH"

    /** What TalkBack hears for the whole row: the label, then how far along the code is. */
    fun spoken(label: String, value: String): String =
        if (value.isEmpty()) "$label, 6 boxes, empty" else "$label, ${value.length} of $LENGTH digits entered"

    /** The refusal shake's offsets in dp, or empty under reduced motion (the red is the whole signal). */
    fun shake(reducedMotion: Boolean): List<Float> = if (reducedMotion) emptyList() else listOf(0f, -6f, 6f, -6f, 6f, 0f)
}

/**
 * `OtpCodeField` — the ONE six-digit code entry on the handset. The web's twin is `OtpCodeInput`.
 *
 * Six separate boxes, digits only, in Plus Jakarta Sans ([FieldDisplayFontFamily], bundled in
 * res/font, the owner's choice for the code) with tabular figures so the digits sit still. Typing
 * advances, Backspace walks back, a paste or an SMS autofill of the code fills all six, and
 * [onComplete] fires once when the sixth digit lands. [isError] paints every box red; bumping
 * [errorKey] shakes the row once — never under the app's or the system's reduced motion.
 *
 * ONE TEXT FIELD UNDER SIX DRAWN BOXES, deliberately, rather than six fields: the keyboard, paste,
 * autofill and Backspace across boxes then come from the platform instead of six focus hand-offs, and
 * TalkBack meets one editable field whose description says how many digits are in — each box also
 * carries "Digit n of 6" for exploration by touch. `KeyboardType.NumberPassword` opens the number pad
 * with no suggestions strip; nothing is masked, because the boxes draw the digits themselves.
 *
 * A recovery code is NOT a six-digit code and never goes in here.
 */
@Composable
fun OtpCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onComplete: ((String) -> Unit)? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    errorKey: Int = 0,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
) {
    val code = OtpCode.onlyDigits(value).take(OtpCode.LENGTH)
    val reducedMotion = LocalAppPreferences.current.reducedMotion
    val shakeX = remember { Animatable(0f) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(errorKey) {
        if (errorKey == 0) return@LaunchedEffect
        val offsets = OtpCode.shake(reducedMotion)
        if (offsets.isEmpty()) return@LaunchedEffect
        shakeX.animateTo(0f, keyframes {
            durationMillis = 360
            offsets.forEachIndexed { i, x -> x at (i * 360 / (offsets.size - 1)) }
        })
    }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }

    val red = MaterialTheme.colorScheme.error
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.field.muted)
        BasicTextField(
            value = TextFieldValue(code, selection = TextRange(code.length)),
            onValueChange = { edit ->
                val next = OtpCode.accept(code, edit.text)
                if (next != code) {
                    onValueChange(next)
                    if (OtpCode.isComplete(next) && !OtpCode.isComplete(code)) onComplete?.invoke(next)
                }
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (OtpCode.isComplete(code)) onComplete?.invoke(code) }),
            textStyle = TextStyle(color = androidx.compose.ui.graphics.Color.Transparent),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Transparent),
            modifier = Modifier
                .focusRequester(focus)
                .offset { IntOffset(shakeX.value.dp.roundToPx(), 0) }
                .semantics {
                    contentDescription = OtpCode.spoken(label, code)
                    if (isError) error(supportingText ?: "That code was not accepted")
                },
            decorationBox = { inner ->
                Box {
                    // The real text is transparent and sits underneath: it owns the caret, paste and IME.
                    Box(Modifier.height(1.dp)) { inner() }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(OtpCode.LENGTH) { index ->
                            val digit = code.getOrNull(index)?.toString().orEmpty()
                            val current = index == code.length.coerceAtMost(OtpCode.LENGTH - 1) && enabled
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .widthIn(max = 56.dp)
                                    .height(60.dp)
                                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
                                    .border(
                                        width = if (current || isError) 2.dp else 1.dp,
                                        color = when {
                                            isError -> red
                                            current -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.field.hairline
                                        },
                                        shape = MaterialTheme.shapes.medium
                                    )
                                    .semantics { contentDescription = "${OtpCode.digitLabel(index)}${if (digit.isEmpty()) ", empty" else ", $digit"}" }
                            ) {
                                Text(
                                    digit,
                                    fontFamily = FieldDisplayFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum")
                                )
                            }
                        }
                    }
                }
            }
        )
        supportingText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp,
                color = if (isError) red else MaterialTheme.field.muted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}
