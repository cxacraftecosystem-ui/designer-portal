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

internal object DwFieldDetails {
    fun label(fieldLabel: String): String = "About $fieldLabel"

    const val ACTION_LABEL = "Show help"
    const val CLOSE_LABEL = "Close help"

    fun has(field: FieldDto): Boolean = field.details.isNotBlank()

    fun holdMillis(longPressTimeoutMillis: Long): Long = (longPressTimeoutMillis - 80L).coerceAtLeast(300L)

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

internal class DwHoldClaim {
    @Volatile
    var claimedDownAt: Long = -1L
}

internal val LocalDwHoldClaim = compositionLocalOf<DwHoldClaim?> { null }

internal val LocalDwFieldHelp = compositionLocalOf<(() -> Unit)?> { null }

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
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

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
