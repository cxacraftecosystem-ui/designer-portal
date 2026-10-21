package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.ui.field

/*
 * A FIELD'S EXTENDED HELP: HOLD THE BOX TO READ IT (owner, 2026-10-21).
 *
 * The "i" toggle that used to sit at the end of the caption row cost a 48dp row under ninety-nine
 * boxes whose caption was nothing but that "i" — white space between every field on the stage form.
 * The details are now opened by a long press anywhere on the field, and shown in a card anchored
 * to it; Back, a tap outside or the card's close button dismisses it. TalkBack reaches the same
 * card through a "Show help" custom action on the caption (or, with no caption, a focusable
 * "About <label>" line drawn only while touch exploration is on).
 */

/** The words of the help gesture, one table, pure so `DwFieldHelpTest` holds them without a device. */
internal object DwFieldDetails {
    /** The card's pane title and the screen-reader caption for a field with no help line. */
    fun label(fieldLabel: String): String = "About $fieldLabel"

    const val ACTION_LABEL = "Show help"
    const val CLOSE_LABEL = "Close help"

    /** Whether a field has anything to disclose. Whitespace is nothing. */
    fun has(field: FieldDto): Boolean = field.details.isNotBlank()

    /**
     * How long a hold opens the help, from the platform's long-press timeout.
     *
     * A little SHORTER than the platform's, so the help wins the race against the text box's own
     * long press (text selection, the paste menu): once it has fired, the rest of the gesture is
     * consumed and the box never sees a long press at all. Never under 300 ms, which is still a
     * deliberate hold rather than a slow tap.
     */
    fun holdMillis(longPressTimeoutMillis: Long): Long = (longPressTimeoutMillis - 80L).coerceAtLeast(300L)

    /**
     * Where the card goes: under the field when it fits, over it when that fits, otherwise pinned to
     * the bottom of the window. Horizontally it starts at the field's edge, kept inside the window.
     */
    fun cardOffset(
        anchorLeft: Int,
        anchorTop: Int,
        anchorBottom: Int,
        windowWidth: Int,
        windowHeight: Int,
        cardWidth: Int,
        cardHeight: Int,
        gap: Int,
    ): Pair<Int, Int> {
        val x = anchorLeft.coerceIn(0, (windowWidth - cardWidth).coerceAtLeast(0))
        val below = anchorBottom + gap
        val above = anchorTop - gap - cardHeight
        val y = when {
            below + cardHeight <= windowHeight -> below
            above >= 0 -> above
            else -> (windowHeight - cardHeight).coerceAtLeast(0)
        }
        return x to y
    }
}

/**
 * Who pressed: a control INSIDE a field that has its own long press (the dictation microphone's
 * language menu) marks the down it saw, and the field's hold leaves that gesture alone.
 */
internal class DwHoldClaim {
    @Volatile
    var claimedDownAt: Long = -1L
}

internal val LocalDwHoldClaim = compositionLocalOf<DwHoldClaim?> { null }

/** Opens the help of the field being drawn, or null where the field has none. Read by [FieldCaption]. */
internal val LocalDwFieldHelp = compositionLocalOf<(() -> Unit)?> { null }

/** Mark a control whose own long press must win over the field's help. */
internal fun Modifier.dwClaimsHold(): Modifier = composed {
    val claim = LocalDwHoldClaim.current
    if (claim == null) {
        this
    } else {
        pointerInput(claim) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                claim.claimedDownAt = down.uptimeMillis
            }
        }
    }
}

/**
 * The hold itself, watched on the Initial pass so it sees the gesture before the box does, and never
 * consuming anything until it fires: a tap, a scroll or a drag reaches the field untouched.
 */
private fun Modifier.dwHoldForHelp(claim: DwHoldClaim, onHold: () -> Unit): Modifier =
    pointerInput(claim) {
        val hold = DwFieldDetails.holdMillis(viewConfiguration.longPressTimeoutMillis)
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val ended = withTimeoutOrNull(hold) {
                var over = false
                while (!over) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    over = change == null ||
                        !change.pressed ||
                        event.changes.size > 1 ||
                        (change.position - down.position).getDistance() > slop
                }
                true
            }
            if (ended != null || claim.claimedDownAt == down.uptimeMillis) return@awaitEachGesture
            onHold()
            // The rest of this gesture is the help's: no tap, selection or menu underneath.
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

/**
 * Wraps one field: holding anywhere on it opens its details in a card anchored to it. A field
 * without details is drawn exactly as before, with no gesture attached.
 */
@Composable
internal fun DwFieldHelpHost(
    field: FieldDto,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!DwFieldDetails.has(field)) {
        Box(modifier) { content() }
        return
    }
    var open by rememberSaveable(field.key) { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(0) }
    val claim = remember { DwHoldClaim() }
    Box(
        modifier
            .onGloballyPositioned { widthPx = it.size.width }
            .dwHoldForHelp(claim) { open = true }
    ) {
        CompositionLocalProvider(
            LocalDwHoldClaim provides claim,
            LocalDwFieldHelp provides { open = true },
        ) {
            content()
        }
        if (open) {
            DwFieldHelpCard(field = field, widthPx = widthPx, onDismiss = { open = false })
        }
    }
}

private class DwHelpCardPosition(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val (x, y) = DwFieldDetails.cardOffset(
            anchorLeft = anchorBounds.left,
            anchorTop = anchorBounds.top,
            anchorBottom = anchorBounds.bottom,
            windowWidth = windowSize.width,
            windowHeight = windowSize.height,
            cardWidth = popupContentSize.width,
            cardHeight = popupContentSize.height,
            gap = gapPx,
        )
        return IntOffset(x, y)
    }
}

@Composable
private fun DwFieldHelpCard(field: FieldDto, widthPx: Int, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val gapPx = with(density) { 6.dp.roundToPx() }
    val position = remember(gapPx) { DwHelpCardPosition(gapPx) }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        // Focusable, so the system Back closes the card before it reaches the screen.
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        val width = with(density) { widthPx.toDp() }
        Surface(
            modifier = (if (widthPx > 0) Modifier.width(width) else Modifier.fillMaxWidth())
                .semantics { paneTitle = DwFieldDetails.label(field.label) },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.field.hairline),
        ) {
            Column(modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        field.label,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = DwFieldDetails.CLOSE_LABEL,
                            tint = MaterialTheme.field.muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Text(
                    field.details.trim(),
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
        }
    }
}

/** True while a screen reader is exploring by touch; tracks the setting as it changes. */
@Composable
internal fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * The caption's half of the help, for a screen reader: the help line carries a "Show help" action,
 * and a field with no help line gets a focusable "About <label>" line while touch exploration is on.
 * Nothing extra is drawn for anybody else.
 */
@Composable
internal fun DwHelpCaptionText(help: String, field: FieldDto, openHelp: () -> Unit) {
    val screenReader = rememberTouchExplorationEnabled()
    if (help.isBlank() && !screenReader) return
    val action = CustomAccessibilityAction(DwFieldDetails.ACTION_LABEL) { openHelp(); true }
    Text(
        help.ifBlank { DwFieldDetails.label(field.label) },
        color = MaterialTheme.field.muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (screenReader) Modifier.clickable(onClickLabel = DwFieldDetails.ACTION_LABEL, onClick = openHelp) else Modifier
            )
            .semantics { customActions = listOf(action) },
    )
}
