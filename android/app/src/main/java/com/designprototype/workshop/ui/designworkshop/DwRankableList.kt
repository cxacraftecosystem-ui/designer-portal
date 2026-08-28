package com.designprototype.workshop.ui.designworkshop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.designprototype.workshop.data.dwMoveBy
import com.designprototype.workshop.data.dwMoveTo
import com.designprototype.workshop.ui.LocalAppPreferences
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * A list a designer can rearrange — with the ARROWS and by DRAGGING, because both are required.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY BOTH, AND WHY THE ARROWS ARE THE PRIMARY PATH
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The owner asked for ranking "using both drag-and-drop and the up/down arrows on the card". That is
 * not two ways of saying one thing. A drag is a pointer gesture: it is unreachable from TalkBack,
 * from a switch device and from an external keyboard, so drag ALONE would put the one judgement this
 * whole feature exists to record — the designer's final say — behind a fingertip. The arrows are
 * therefore always rendered, always enabled while the list is writable, and are what the assistive
 * layer drives. The drag handle is an accelerator on top of them, and both write through the same two
 * pure functions (`dwMoveBy`, `dwMoveTo`) so that a list cannot behave differently depending on which
 * control a designer reached for.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE GESTURE: `detectDragGestures` ON THE HANDLE, NOT `detectDragGesturesAfterLongPress` ON THE ROW
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Both were considered and the choice is deliberate.
 *
 * `detectDragGesturesAfterLongPress` is the right detector when the DRAGGABLE TARGET IS THE ROW
 * ITSELF, because a row inside a vertically scrolling page is already claimed by two other gestures —
 * the scroll and the tap — and a long press is the only unambiguous way to take it from them. That is
 * not this list. Every row here is a whole review card with a five-way score control and two text
 * boxes in it; making the card draggable would mean a designer resting a thumb on it while reading
 * picks it up, and it would put a 500 ms delay in front of every reorder for no gain.
 *
 * So the gesture is bound to a DEDICATED GRIP, exactly as the web's is, and a dedicated grip needs no
 * long press to disambiguate: nothing else on that 48dp square does anything. `change.consume()` in
 * `onDrag` is what stops the movement ALSO being read as a page scroll by the parent — the same
 * consume, for the same reason, that `DwMarkHandle` applies to a mark being dragged across a
 * photograph inside a pannable viewport.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE FIVE RULES THAT MAKE THE DRAG HONEST — ported from `components/hooks/useDragReorder.ts`
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Ported as rules and not as code: the web hook measures DOM rectangles and this measures Compose
 * layout, so the arithmetic is different and every one of the five reasons is identical. Each of them
 * is a defect that has already been paid for once on the other client.
 *
 *  1. **THE GEOMETRY IS SNAPSHOTTED ONCE, AT DRAG START.** [DwDragState.heights] holds every row's
 *     measured height as it stood when the finger went down. Re-reading them during the gesture would
 *     feed the neighbours' shift back into the measurement and the target index would oscillate under
 *     the thumb. It is also why the rows move by `graphicsLayer { translationY }` rather than by
 *     `offset`: a graphics-layer translation is a DRAW-time transform, so nothing in the layout
 *     actually moves and no row is re-measured because of it.
 *
 *  2. **THE ARRANGEMENT IS SNAPSHOTTED WITH THEM, AND A GESTURE WHOSE GROUND MOVED IS ABANDONED.**
 *     `from` and `to` are indices into the order AS IT STOOD at drag start. If the list changed
 *     mid-gesture — a refresh landed, a colleague's row arrived on a sync — those indices address
 *     different pieces than the ones the thumb was over, and committing them moves the wrong piece
 *     and then stamps the result as a deliberate arrangement with the designer's name on it. The
 *     tempting repair, re-deriving `from` by id and keeping `to`, is the same guess in a smaller coat:
 *     it keeps half a stale measurement. So the gesture is dropped and SAID to have been dropped.
 *
 *  3. **NOTHING IS COMMITTED UNTIL THE POINTER IS RELEASED, AND A CANCELLED DRAG COMMITS NOTHING.**
 *     A reorder is a write with a person's name on it, so a stray swipe across a card must not be
 *     able to stamp an arrangement. `onDragCancel` is the phone's equivalent of the web's Escape key:
 *     there is no Escape here, and what actually interrupts a drag on a handset — a phone call, the
 *     back gesture, another finger taking the gesture — arrives through that callback.
 *
 *  4. **EVERY MOVE IS ANNOUNCED IN WORDS**, through a polite live region that is present from the
 *     first composition. A rank that exists only as a place in a visual list is a rank a TalkBack
 *     user cannot read back. The arrows announce through the same string as the drag, so the two
 *     paths cannot drift into two different sentences for one act.
 *
 *  5. **A DRAG CANNOT OUTLIVE ITS LIST, AND A LIVE GESTURE IS PROTECTED BY THE DETECTOR'S KEYS.**
 *     Two different worries, and they have two different answers. A screen left MID-GESTURE is the
 *     easy one: `drag` is plain `remember` state owned by this composable and hoisted nowhere, so it
 *     is discarded with the composition and the next one starts from `mutableStateOf(null)` — there
 *     is no path by which a lifted card nobody is touching survives to be drawn. The one that needs
 *     care is a `pointerInput` coroutine cancelled WHILE THIS LIST IS STILL COMPOSED: a cancelled
 *     coroutine runs NEITHER `onDragEnd` NOR `onDragCancel`, so that drag state would stay set and
 *     drawn with nothing moving it. What prevents it is the detector's narrow key list — the row id
 *     and `locked`, nothing that changes during a gesture — argued where the keys are chosen, over
 *     `currentOrder` below. The [DisposableEffect] further down is NOT what makes this rule true;
 *     it says so itself. The auto-scroll loop added below needs no third mechanism: a
 *     [LaunchedEffect] is cancelled with the composition that launched it, so a screen left
 *     mid-gesture cannot leave a list scrolling itself. It is keyed on WHETHER a drag exists and
 *     never on the drag, which matters for the same reason the detector's keys do — `drag` is a new
 *     object on every pointer sample, and an effect keyed on it would be torn down and relaunched
 *     dozens of times a second, losing its place each time.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * EDGE AUTO-SCROLL, AND THE RECONCILIATION THAT MAKES IT LEGAL
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Added 2026-08-27, on both clients at once. Until then the gesture could only reach a destination
 * ALREADY ON SCREEN, and on a 360×640 handset — where one review card is most of the viewport — that
 * is exactly ONE position, which is what the arrow above the grip already does with a bigger target.
 * `change.consume()` in `onDrag` is what stops the page beneath reading the movement as a scroll, so
 * the list will not travel of its own accord either. The gesture has to do the scrolling.
 *
 * **THE HARD PART IS RULE 1, AND THE ANSWER IS ONE TERM — the web hook's, to the letter.** A scroll
 * moves every row on screen, which looks like it must invalidate the snapshot rule 1 forbids
 * re-taking. It does not, because [advancedBy] derives every row's top from the SNAPSHOTTED HEIGHTS
 * and compares rows only with each other: a scroll translates all of them equally, and adding a
 * constant to both sides of a comparison changes neither side. The one thing a scroll genuinely
 * changes is where the THUMB is relative to the list — and that is a single number. So:
 *
 *     a scroll of N pixels is arithmetically the same event as the thumb travelling N pixels
 *
 * and it is fed in through the same [advancedBy] the pointer uses, from [onGloballyPositioned] on
 * this list's own root. That callback is normally the wrong tool here — the comment over `heights`
 * below rejects it for exactly the reason it is right for this: it fires on every scroll of the
 * page, which is noise for a size and is the signal for a movement. It reads the ROOT's position and
 * never a row's, so no `graphicsLayer` translation this file hands out can feed back into it, and it
 * writes only when the number actually changed, so the recomposition it schedules cannot re-trigger
 * itself. It also correctly absorbs a layout shift ABOVE the list (a banner arriving) that is not a
 * scroll at all: what it measures is how far the list moved under the thumb, whatever moved it.
 *
 * **HOW THE SCROLL IS CAUSED: a [BringIntoViewRequester] on the GRIP, and no ScrollState anywhere.**
 * This composable does not own the scroll — `MainActivity` does, one `pageScroll` per screen — and
 * taking one as a parameter would put the mechanism in the caller's hands and leave every future
 * caller to remember it. A requester asks the nearest scrollable ancestor to reveal a rectangle,
 * whoever that ancestor turns out to be, which is the same reason `MapScreen` gives for using one
 * over arithmetic on a ScrollState.
 *
 * The rectangle is the trick. It is NOT the card — a card taller than the viewport already fills it,
 * and the default bring-into-view spec correctly does nothing for a rectangle that does. It is a
 * [DW_AUTOSCROLL_LOOKAHEAD]-tall BAND just beyond the GRIP, on the side the thumb is pushing (see
 * [dwAutoScrollBand]). The grip is where the thumb is, by construction, and it is inside the row's
 * `graphicsLayer`, so it tracks the thumb rather than the row's resting place. That single fact
 * gives an edge zone and a taper for free and without measuring a viewport this composable cannot
 * see: the band is fully visible while the thumb is well clear of the edge (so nothing moves at
 * all), and the shortfall the ancestor has to close — which is exactly how far it scrolls — grows in
 * proportion as the thumb approaches. With a 96dp lookahead and the 48dp grip, the zone runs from
 * about 120dp off the edge (96 to 144, depending where on the grip the thumb landed) and the
 * shortfall at its deepest is the same distance.
 *
 * **ONE NUMBER SETS BOTH THE ZONE AND THE SPEED HERE, AND THE WEB'S TWO ARE INDEPENDENT.** The
 * shortfall the ancestor closes is what makes the list travel, and the shortfall at its deepest IS
 * the zone's depth — they are the same quantity read twice, so [DW_AUTOSCROLL_LOOKAHEAD] cannot be
 * tuned for one without moving the other. That is why it is not the web's `EDGE_ZONE` of 72: a zone
 * matching it to the pixel would also cap the travel at 72dp an arrival, which on a list of a dozen
 * review cards is a designer waiting. Do not "restore parity" by copying that number across — the
 * two clients are the same BEHAVIOUR reached by different mechanisms, and their constants are not
 * each other's.
 *
 * **IT IS CORRECT WHETHER OR NOT `bringIntoView` KEEPS SCROLLING.** Because the grip is pinned to the
 * thumb, satisfying the request does not move the target, so a responder that re-reads the rectangle
 * scrolls continuously until the thumb leaves the edge or the list ends — and one that scrolls once
 * per call is re-asked by the loop below on the next frame. Both give a sustained travel and neither
 * runs away; nothing here depends on which it is.
 *
 * **AND THERE IS NO RUNAWAY TO GUARD AGAINST, WHICH THE WEB CANNOT SAY.** A CSS transform contributes
 * to its scroll container's overflow, so the web hook has to bound the travel by the extent it
 * snapshotted or the drag scrolls into space it created itself. `graphicsLayer` is a DRAW-time
 * transform: it changes no measurement and extends no scroll range, so the ancestor's own extent is
 * already the right bound and this file states the difference rather than porting a guard that would
 * be inert.
 *
 * **REDUCED MOTION.** The preference changes HOW the list travels and never WHETHER the destination
 * is reachable — switching auto-scroll off for it would take a capability away from exactly the
 * readers who asked for less movement, while everyone else kept it. So the loop puts a
 * [DW_AUTOSCROLL_DWELL_MILLIS] beat between arrivals instead of re-asking every frame, and the travel
 * reads as a series of stops rather than one unbroken glide. **Said plainly rather than overclaimed:
 * this is a weaker treatment than the web's.** The platform animates the hop itself, and the only
 * knob that would make it instant — `BringIntoViewSpec` — is experimental; when it is not, the beat
 * should become a snap.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THREE PLATFORM DIFFERENCES FROM THE WEB, ALL THREE THE PHONE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 *  * **NO LazyColumn.** This list is drawn inside the shared scrolling Column that `HomeScreen` owns,
 *    and a lazy list measured inside a parent that scrolls the same way is measured with an infinite
 *    height budget and throws at layout. A ranking list is a dozen cards, so a plain Column costs
 *    nothing — and `StageScreen`'s own collection list is built the same way for the same reason.
 *  * **THE ANNOUNCEMENT IS VISIBLE, where the web's is `sr-only`.** On a laptop the moved card is
 *    almost always still on screen, so the sentence is only for a reader who cannot see it. On a
 *    phone a card is most of the viewport and a move can put it out of sight entirely, so the one line
 *    is useful to everybody. It is not furniture: it is empty until something happens.
 *  * **THE EDGE ZONE IS GEOMETRY, NOT A MEASUREMENT.** The web reads the pointer's `clientY` against
 *    the scroller's own rectangle and works out how deep into a 72px strip it is. Nothing here can:
 *    the scrolling ancestor is somebody else's and its bounds are not on offer. The band above does
 *    the same job by construction — see the auto-scroll section — so the two clients arrive at the
 *    same behaviour from opposite directions, and neither one's numbers can be read off the other's.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DwRankableList(
    /** The current arrangement, as subject ids. The caller owns it; this composable never stores it. */
    order: List<String>,
    /** A human name for one id — used by the arrow labels and by every announcement. */
    labelFor: (String) -> String,
    /** Called with the WHOLE new arrangement. Never called when the move would change nothing. */
    onReorder: (List<String>) -> Unit,
    /**
     * Why this list cannot be rearranged, or null when it can.
     *
     * A SENTENCE RATHER THAN A BOOLEAN, because every caller that disables these controls has a
     * reason a designer needs to read — a pool reviewer is not a member of the workshop that owns the
     * ordinal, this phone has never opened the stage, there is no name to record. Disabled controls
     * with no explanation are the shape of a screen that looks broken.
     */
    disabledReason: String?,
    /** One row's card. [position] is 1-based and is PRINTED, not merely implied by the layout. */
    row: @Composable (id: String, position: Int, total: Int, dragging: Boolean) -> Unit,
) {
    val total = order.size
    val locked = disabledReason != null
    val reduceMotion = LocalAppPreferences.current.reducedMotion
    val density = LocalDensity.current
    val gapPx = with(density) { ROW_GAP.toPx() }
    val gripPx = with(density) { GRIP_TOUCH_TARGET.toPx() }
    val lookaheadPx = with(density) { DW_AUTOSCROLL_LOOKAHEAD.toPx() }
    val armPx = with(density) { DW_AUTOSCROLL_ARM.toPx() }

    /*
      THE MEASURED HEIGHT OF EVERY ROW, KEYED BY ID AND NOT BY POSITION. Keyed by position it would be
      wrong for exactly one frame after every reorder — the frame in which the cards have swapped and
      the heights have not — and one frame is all it takes for the next drag to compute its target
      against the wrong card, because a review card's height depends on whether its owner has written
      a paragraph into it.
    */
    val heights = remember { mutableStateMapOf<String, Float>() }
    var drag by remember { mutableStateOf<DwDragState?>(null) }
    var announcement by remember { mutableStateOf("") }

    /*
      THE AUTO-SCROLL'S TWO PIECES OF BOOKKEEPING, and neither of them is Compose state on purpose.

      [DwAutoScroll.lastTop] is where this list's root sat in the window when it was last laid out —
      the raw material for "how far did the list move under the thumb". [DwAutoScroll.grip] is the
      requester belonging to the row a gesture is holding, parked here by `onDragStart` so the loop
      below can find it without every row publishing itself.

      A plain holder rather than `mutableStateOf` because NOTHING DRAWS EITHER OF THEM. `lastTop` is
      written from `onGloballyPositioned` on every scrolled frame, and as snapshot state that would
      be a recomposition per frame for a number no composable reads — written and read inside the
      same layout pass, it is also the shortest route to an invalidation that re-triggers itself.
    */
    val autoScroll = remember { DwAutoScroll() }

    /*
      READ THROUGH `rememberUpdatedState` SO THE GESTURE NEVER SEES A STALE LIST.

      The `pointerInput` below is keyed on the row's id and the locked flag ONLY. Keying it on `order`
      as well would look safer and would be worse: the order changes on every committed reorder, so
      the detector would be torn down and rebuilt in the middle of the very gesture that changed it —
      and a torn-down detector runs neither of its end callbacks (rule 5). These two hold the current
      values for a lambda that outlives the composition it was written in.
    */
    val currentOrder by rememberUpdatedState(order)
    val currentReorder by rememberUpdatedState(onReorder)
    val currentLabel by rememberUpdatedState(labelFor)

    /** Rule 4, once, so the arrows and the drag cannot come to say two different things. */
    fun announceMove(id: String, index: Int) {
        announcement = "${currentLabel(id)} moved to position ${index + 1} of ${currentOrder.size}."
    }

    /** One arrow press, or one keyboard-driven step. */
    fun step(id: String, delta: Int) {
        if (locked) return
        val next = dwMoveBy(currentOrder, id, delta)
        if (next == currentOrder) return
        currentReorder(next)
        val landed = next.indexOf(id)
        if (landed >= 0) announceMove(id, landed)
    }

    // BELT AND BRACES, AND A NO-OP TODAY — recorded as such rather than left looking load-bearing.
    // `drag` is `remember`-scoped state of this composable (just above), so this `onDispose` and the
    // MutableState it writes to leave the composition together: it clears an object that is being
    // thrown away in the same breath. It is kept for the day someone hoists `drag` into a holder
    // that OUTLIVES the list, when it becomes the line that clears it, and it costs one no-op
    // dispose until then. That hoist must not be `rememberSaveable`: [DwDragState] is pointer
    // geometry, meaningless after a rotation, and restoring it is how you get the lifted card
    // nobody is touching. The rule-5 case that is actually live — a detector cancelled under a
    // finger — is handled by the pointerInput's keys, not here.
    DisposableEffect(Unit) { onDispose { drag = null } }

    /*
      THE AUTO-SCROLL CLOCK. Keyed on WHETHER a drag exists and never on the drag itself — rule 5
      says why, and it is the same argument the detector's own keys rest on.

      IT HAS TO BE A CLOCK AND NOT A POINTER CALLBACK, because the case it exists for is a thumb held
      STILL against the edge of the screen: no drag event fires, and the list still has to keep
      coming. It asks for the band beyond the grip (see [dwAutoScrollBand]); when the thumb is not at
      an edge that band is already on screen and the ancestor does nothing at all, so an ordinary drag
      between two visible rows costs one request per frame and moves nothing.

      `heading` and not the sign of `offset`: after a long auto-scroll `offset` is hundreds of pixels
      positive, and a designer who then drags back up towards the top of the screen would still be
      asking to scroll DOWN. What the band must follow is the way the THUMB is pushing now, which is
      what [DwDragState.heading] holds and what the arming rule in [draggedBy] withholds until a
      press has actually become a drag.

      `reduceMotion` IS CAPTURED AND IS DELIBERATELY NOT A KEY. Adding it would tear the loop down
      and relaunch it the moment the preference settled — which, on a phone that reads it from
      storage a tick after composition, is a real possibility mid-gesture. A drag that started
      before the preference arrived travelling at the wrong cadence for its own duration is a far
      smaller loss than a drag whose scroll stops. The web hook reads the same preference through a
      ref for the same reason, at greater length.
    */
    val gestureInFlight = drag != null
    LaunchedEffect(gestureInFlight) {
        if (!gestureInFlight) return@LaunchedEffect
        while (isActive) {
            val heading = drag?.heading ?: 0
            val grip = autoScroll.grip
            if (heading != 0 && grip != null) {
                try {
                    grip.bringIntoView(dwAutoScrollBand(heading, gripPx, lookaheadPx))
                } catch (e: CancellationException) {
                    // Rethrown rather than swallowed: this is the gesture ending or the designer
                    // leaving, and a `runCatching` here would eat the cancellation and go round
                    // again on a screen that has gone. `StageScreen`'s arrival does the same.
                    throw e
                } catch (_: Throwable) {
                    // The row went out of composition between the read above and this call. Not
                    // scrolling is a small failure; crashing during a drag is not.
                }
            }
            // The reduced-motion beat, or the next frame. See the header's last paragraph.
            if (reduceMotion) delay(DW_AUTOSCROLL_DWELL_MILLIS) else withFrameNanos { }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            /*
              WHERE A SCROLL BECOMES DRAG TRAVEL. The header's reconciliation, in five lines: the
              list's root moved by `previous - top` since the last layout, the thumb did not, and to
              the arithmetic those are the same event — so it goes through the same [advancedBy] a
              pointer sample does.

              THE ROOT AND NEVER A ROW. A row carries the `graphicsLayer` translation this file hands
              out, so measuring one would feed the neighbours' shift back into the measurement and
              oscillate the target under the thumb, which is rule 1's whole subject. This node has no
              layer of its own and none of its ancestors moves for a drag, so what it reports is the
              scroll and nothing else.

              AND IT CANNOT LOOP. The write happens only when the number CHANGED; the recomposition
              it schedules re-runs this callback with the same position, `previous == top`, and
              nothing is written the second time.
            */
            .onGloballyPositioned { coordinates ->
                val top = coordinates.positionInWindow().y
                val previous = autoScroll.lastTop
                autoScroll.lastTop = top
                val current = drag
                if (current != null && !previous.isNaN() && previous != top) {
                    drag = current.advancedBy(previous - top, gapPx)
                }
            },
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        /*
          THE LIVE REGION IS COMPOSED WHETHER OR NOT THERE IS ANYTHING IN IT. Assistive technology
          announces a CHANGE inside a region that already existed; a region created at the same moment
          as its first message is a region whose first message is not announced. The same rule the
          web's `aria-live` paragraph follows, and the same one this app's toast host follows.
        */
        Box(
            /*
              `mergeDescendants` IS WHAT MAKES THE REGION WORK. A live region announces a change to
              ITS OWN semantics, and this node has no text of its own — the sentence is in the child.
              Merged, the child's text IS this node's text, so replacing it is the change that gets
              announced. Unmerged, this is a live region over nothing and the announcement is silent.
            */
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        ) {
            // Composed only when there is something to say. An always-present empty Text still
            // occupies a line of leading above the list, which is furniture — and the region itself
            // is what has to exist from first paint, not its content.
            if (announcement.isNotEmpty()) {
                Text(
                    announcement,
                    color = MaterialTheme.field.muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }

        order.forEachIndexed { index, id ->
            val dragging = drag?.key == id
            val target = drag.shiftFor(index, gapPx)
            /*
              ONE BRING-INTO-VIEW REQUESTER PER SLOT, attached UNCONDITIONALLY to the grip below.

              Unconditionally, and that is the load-bearing half. A `Modifier` applied only while a
              row is being dragged changes the SHAPE of the chain the detector sits in, in the middle
              of the very gesture it is detecting — and a `pointerInput` node that is torn down and
              rebuilt runs neither `onDragEnd` nor `onDragCancel`, which is rule 5's live hazard
              arrived at by a different road. A requester nobody calls costs a node and nothing else,
              so the chain is the same shape from first composition to last.

              `remember(id)` and not `remember`: the rows are a `forEachIndexed`, so a slot's remembered
              values belong to the SLOT and not to the piece in it (the `key(id)` note further down is
              the same fact, at greater length). Re-keyed on the id, the requester a gesture parks in
              `autoScroll.grip` always belongs to the card the thumb is actually on.
            */
            val gripRequester = remember(id) { BringIntoViewRequester() }
            /*
              THE SHIFT IS ANIMATED FOR THE NEIGHBOURS AND NOT FOR THE CARD UNDER THE THUMB. A card
              being dragged has to sit exactly where the finger is — an eased follow reads as lag, not
              as polish — while the neighbours opening a gap is the one thing the animation is for.

              180ms AND COMPOSE'S DEFAULT EASING, both off what this client already uses rather than
              invented here: the ladder's own layout-change tween is 0.18s, and the neighbouring
              `DwMediaCarousel` runs its slides on a bare `tween(...)` with no easing argument. The web
              leaves this transition to Tailwind's default, which is not a token this app has.

              ZERO UNDER REDUCED MOTION, where the row jumps straight to its place. The gap still
              opens — that is the information — it simply does not travel.
            */
            val shift by animateFloatAsState(
                targetValue = target,
                animationSpec = if (dragging || reduceMotion) tween(0) else tween(180),
                label = "dwRankShift",
            )
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    // The lifted card draws over its neighbours rather than under them.
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = shift }
                    .onSizeChanged { size ->
                        // Rule 1's raw material. Written only when it CHANGES — `onSizeChanged`
                        // rather than `onGloballyPositioned`, which fires on every scroll of the page
                        // and would rewrite this map continuously for no new information.
                        heights[id] = size.height.toFloat()
                    },
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    /*
                      THE PLACE IS A NUMBER ON THE CARD, not merely a position in a list. It is what a
                      designer says out loud ("prototype 3"), what the report prints, and the only form
                      of the rank available to a reader who cannot see the arrangement.
                    */
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .background(MaterialTheme.field.surface200, CircleShape),
                    ) {
                        Text(
                            "${index + 1}",
                            color = MaterialTheme.field.body,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    IconButton(
                        onClick = { step(id, -1) },
                        enabled = !locked && index > 0,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            // The web's own aria-label, which names the PIECE and not the direction
                            // alone: a TalkBack user swiping down a list of eight cards hears "Move
                            // up" eight times otherwise, with nothing saying what would move.
                            contentDescription = "Move ${labelFor(id)} up",
                            tint = MaterialTheme.field.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { step(id, 1) },
                        enabled = !locked && index < total - 1,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.ArrowDownward,
                            contentDescription = "Move ${labelFor(id)} down",
                            tint = MaterialTheme.field.muted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    /*
                      THE GRIP. A plain Box and not an IconButton, because an IconButton owns a click
                      and a ripple and would compete with the drag detector for the same pointer — the
                      shape `DwMarkHandle` settled on for a mark dragged across a photograph.

                      ITS DESCRIPTION NAMES THE OTHER ROUTE. A reader who has found the grip — it is
                      the affordance that LOOKS like reordering — must not have to go back and find two
                      other buttons, and a handle that announced itself as draggable to somebody who
                      cannot drag would be an instruction they cannot carry out.
                    */
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(GRIP_TOUCH_TARGET)
                            /*
                              THE ANCHOR THE AUTO-SCROLL ASKS ABOUT, and it is on the GRIP rather
                              than on the card for one reason: the thumb is here. This Box sits
                              inside the row's `graphicsLayer`, so its coordinates carry the drag
                              translation and it travels with the thumb rather than staying at the
                              card's resting place — which is what turns "reveal the band beyond it"
                              into an edge zone with no viewport measurement anywhere. See the
                              auto-scroll section of the header.
                            */
                            .bringIntoViewRequester(gripRequester)
                            .semantics {
                                contentDescription = if (locked) {
                                    "Reordering is not available here. ${disabledReason.orEmpty()}"
                                } else {
                                    "Reorder ${labelFor(id)}. Drag this handle, or use the move up " +
                                        "and move down buttons above it."
                                }
                            }
                            .pointerInput(id, locked) {
                                if (locked) return@pointerInput
                                detectDragGestures(
                                    onDragStart = {
                                        val snapshot = currentOrder
                                        val from = snapshot.indexOf(id)
                                        // Rules 1 and 2, taken together and in one place: a gesture
                                        // is measured against the list it started on, or it is not
                                        // committed at all. A row that is not in the current order
                                        // starts no drag rather than one anchored at -1.
                                        drag = if (from < 0) {
                                            null
                                        } else {
                                            DwDragState(
                                                key = id,
                                                from = from,
                                                to = from,
                                                offset = 0f,
                                                travel = 0f,
                                                heading = 0,
                                                snapshot = snapshot,
                                                heights = snapshot.map { heights[it] ?: 0f },
                                            )
                                        }
                                        // WHOSE GRIP THE AUTO-SCROLL SHOULD FOLLOW. Parked here and
                                        // not derived from `drag.key` in the loop, because a
                                        // requester is a per-slot object and the loop has no way to
                                        // reach into a row's composition for it. Cleared on both
                                        // ends below, so nothing is asked to scroll on behalf of a
                                        // gesture that is over.
                                        autoScroll.grip = if (from < 0) null else gripRequester
                                    },
                                    onDrag = { change, amount ->
                                        // Consumed so the page beneath does not read the same
                                        // movement as a scroll and slide the list out from under the
                                        // card being dragged.
                                        change.consume()
                                        // `draggedBy` and not `advancedBy`: this is the THUMB
                                        // moving, so it also decides the heading the auto-scroll
                                        // follows. The scroll's own contribution goes through
                                        // `advancedBy` on the list root and deliberately does not
                                        // touch the heading — a list that re-asserted its own
                                        // direction could never be turned round.
                                        drag = drag?.draggedBy(amount.y, gapPx, armPx)
                                    },
                                    onDragEnd = {
                                        val current = drag
                                        drag = null
                                        autoScroll.grip = null
                                        // A gesture that ended where it began is not a reorder, and
                                        // it must not become one: a thumb resting on the grip while
                                        // the page settles produces exactly this.
                                        if (current != null && current.to != current.from) {
                                            if (current.snapshot != currentOrder) {
                                                // Rule 2, said out loud rather than swallowed. The
                                                // arrangement this gesture was measured against is
                                                // gone, so its indices address other pieces now;
                                                // committing them would move the wrong one and then
                                                // stamp the result with this designer's name on it.
                                                announcement =
                                                    "${currentLabel(current.key)} was not moved: " +
                                                    "the list changed while it was being dragged. " +
                                                    "Try again."
                                            } else {
                                                val next =
                                                    dwMoveTo(currentOrder, current.from, current.to)
                                                if (next != currentOrder) {
                                                    currentReorder(next)
                                                    announceMove(current.key, current.to)
                                                }
                                            }
                                        }
                                    },
                                    // Rule 3. Nothing is written by a gesture that was taken away —
                                    // and nothing is scrolled on its behalf either.
                                    onDragCancel = {
                                        drag = null
                                        autoScroll.grip = null
                                    },
                                )
                            },
                    ) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = null,
                            tint = if (locked) {
                                MaterialTheme.field.placeholder
                            } else {
                                MaterialTheme.field.muted
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            // The lifted card is outlined while it is held. A shadow alone would be
                            // the whole signal carried by depth, which is exactly what a
                            // forced-colours or high-contrast reader loses.
                            if (dragging) {
                                Modifier.border(
                                    2.dp,
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(12.dp),
                                )
                            } else {
                                Modifier
                            }
                        ),
                ) {
                    /*
                      ══════════════════════════════════════════════════════════════════════════════
                      `key(id)` IS LOAD-BEARING, AND WITHOUT IT THIS SCREEN SHOWS ONE PIECE'S REVIEW
                      UNDER ANOTHER PIECE'S NAME.
                      ══════════════════════════════════════════════════════════════════════════════

                      This is deliberately NOT a `LazyColumn` (see the note at the top of the list —
                      the parent scrolls the same axis), so the rows are an ordinary
                      `forEachIndexed`. Compose then identifies each call by its POSITION in the
                      composition, not by the piece it is drawing. Every `remember` inside the row
                      therefore belongs to the SLOT.

                      That is fine for a list that never reorders. This list's whole purpose is
                      reordering. The row content here is `DwReviewCard`, which remembers `saving`,
                      `problem`, `saved`, `ledgerOpen`, `ledger`, `ledgerProblem` and
                      `ledgerLoading` — so after a single nudge or drag, slot 3 keeps slot 3's
                      remembered state while now drawing a different prototype:

                        · the ledger guard sees a non-null `ledger` left by the slot's previous
                          occupant and returns early, so the card prints ANOTHER PIECE'S ratings,
                          reviewer names and timestamps under this piece's title — a confident wrong
                          answer on the screen that decides a ministry ranking;
                        · `score`, `comment` and `suggestion` are remembered against the loaded
                          VALUE rather than the subject, so between two unrated pieces (both null) a
                          typed, unsubmitted review follows the slot — and Submit files it against
                          the piece that moved into it.

                      `key(id)` binds the whole subtree to the SUBJECT, so state moves with the
                      piece and a reorder carries each card's own draft and ledger with it. It is
                      the non-lazy equivalent of `LazyColumn`'s `key =` parameter, and it is exactly
                      the reason that parameter exists.

                      DO NOT "SIMPLIFY" THIS AWAY. It reads as a redundant wrapper and it is the
                      only thing making the identity of a row the piece rather than its position.
                    */
                    key(id) {
                        row(id, index + 1, total, dragging)
                    }
                }
            }
        }

        if (locked) {
            Text(
                disabledReason.orEmpty(),
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/** The gap between rows. Read in px by the drag arithmetic, so it lives in one place. */
private val ROW_GAP = 12.dp

/**
 * The grip's touch target.
 *
 * 48dp, the floor this app applies wherever a control was thought about (see `ISLAND_TOUCH_TARGET`),
 * and a control that has to be found and held by a thumb while the eye is on something else is the
 * last place to make an exception. The glyph inside it is 20dp: small enough not to crowd the two
 * arrows above it, inside a square large enough to grab.
 */
private val GRIP_TOUCH_TARGET = 48.dp

/**
 * How far beyond the grip the auto-scroll asks the ancestor to reveal.
 *
 * IT IS THE EDGE ZONE, EXPRESSED AS A DISTANCE INSTEAD OF A MEASUREMENT — and it is also the travel
 * per arrival, which is the constraint that picked the number. The band is fully on screen, so
 * nothing moves, until the grip comes within this of the edge; from there the shortfall the ancestor
 * must close grows in proportion, and that shortfall is the scroll. With [GRIP_TOUCH_TARGET] at 48dp
 * the zone therefore begins about 120dp off the edge and the deepest arrival travels the same.
 *
 * The web hook's `EDGE_ZONE` is 72 and this is deliberately not it: there the zone and the speed are
 * two constants, and here they are one quantity read twice. 72dp would give a zone matching the web
 * to the pixel and cap the travel at 72dp an arrival, which on a list of a dozen review cards is a
 * designer waiting for a page to go by. See the header.
 */
private val DW_AUTOSCROLL_LOOKAHEAD = 96.dp

/**
 * How far the thumb must travel before the auto-scroll will run at all.
 *
 * A PRESS IS NOT A DRAG. The last row's grip on a phone is always near the bottom of the screen, so
 * without this a thumb merely resting on it would start the list moving before anybody had asked for
 * anything — and let go of it somewhere else. The web hook arms on the same 8px of pointer travel.
 */
private val DW_AUTOSCROLL_ARM = 8.dp

/**
 * The beat between arrivals under reduced motion, in milliseconds.
 *
 * See the header: the preference changes the character of the travel, never whether the destination
 * can be reached. Short enough that a reduced-motion drag is a series of steps rather than a crawl.
 */
private const val DW_AUTOSCROLL_DWELL_MILLIS = 140L

/**
 * The auto-scroll's mutable bookkeeping — deliberately NOT Compose state; the composable says why.
 *
 * [lastTop] starts as NaN rather than 0 because "not laid out yet" and "at the top of the window"
 * are different facts and only one of them is a scroll of zero. The first layout therefore
 * establishes a baseline and moves nothing.
 */
@OptIn(ExperimentalFoundationApi::class)
private class DwAutoScroll {
    var lastTop: Float = Float.NaN
    var grip: BringIntoViewRequester? = null
}

/**
 * One drag in flight — and, deliberately, the LIST IT STARTED ON.
 *
 * [snapshot] and [heights] are rule 1 and rule 2 of [DwRankableList]'s header made into fields. They
 * are what let the release ask a question no amount of care during the gesture can answer: is the
 * list I measured still the list in front of me? If it is not, the gesture is abandoned rather than
 * applied to whatever now occupies index 3.
 *
 * `internal` RATHER THAN `private`, ALONG WITH THE FOUR FUNCTIONS BELOW, so the drag arithmetic can
 * be exercised by `DwRankableListDragTest`. Nothing in this project can compose a Compose tree in a
 * unit test, so a rule left inside a gesture callback is only ever checked by somebody looking at a
 * screen — which is how this gesture reached 2026-08-27 with no test of any kind. Same split, same
 * reason, as `numberedRowsAfterEdit` in `DwBulletListField`.
 */
internal data class DwDragState(
    val key: String,
    val from: Int,
    val to: Int,
    /**
     * How far the CARD has travelled from where it was picked up: the thumb's movement plus the
     * list's. The two are one quantity here — the header's reconciliation is exactly the claim that
     * nothing downstream may tell them apart.
     */
    val offset: Float,
    /**
     * The THUMB's share of [offset] alone, which is a different question and has one answer: has this
     * gesture become a drag yet? Kept apart because [offset] grows with every auto-scrolled pixel, so
     * after a long travel it says nothing at all about what the thumb has done.
     */
    val travel: Float,
    /**
     * Which way the thumb last pushed: -1 up, +1 down, 0 while the gesture is still a press.
     *
     * WHAT THE AUTO-SCROLL FOLLOWS, and it must not be the sign of [offset]. After a long travel down
     * a list, [offset] is hundreds of pixels positive; a designer who then drags back towards the top
     * of the screen would still be asking for the band BELOW the grip, and the list would refuse to
     * come back up.
     */
    val heading: Int,
    val snapshot: List<String>,
    /** Every row's height at drag start, in the snapshot's order. */
    val heights: List<Float>,
)

/**
 * Where the dragged row's centre now is, and which index that puts it at.
 *
 * THE TOPS ARE DERIVED FROM THE HEIGHTS RATHER THAN MEASURED. A measured top is a moving target while
 * the neighbours are shifting; a cumulative sum of the snapshotted heights plus the fixed gap is the
 * layout as it stood when the finger went down, which is the only frame of reference in which `from`
 * and `to` mean anything.
 *
 * The comparison is the web hook's, clause for clause: a row above the dragged one takes the target
 * when the dragged CENTRE passes ITS centre, and a row below does the same in the other direction.
 * Comparing centres rather than edges is what stops the target flipping back and forth while a tall
 * card is half-way past a short one.
 *
 * [delta] IS PIXELS AND NOT "PIXELS OF THUMB". It is called with a pointer sample from `onDrag` and
 * with a scroll from `onGloballyPositioned`, and the header's reconciliation is precisely the claim
 * that this function may not tell them apart: the tops it derives are all in one frame taken at drag
 * start, a scroll translates every row in that frame equally, and a comparison of two rows cannot
 * see a constant added to both. What a scroll changes is where the thumb is relative to the list,
 * which is this argument.
 */
internal fun DwDragState.advancedBy(delta: Float, gap: Float): DwDragState {
    val moved = offset + delta
    val tops = ArrayList<Float>(heights.size)
    var running = 0f
    for (height in heights) {
        tops.add(running)
        running += height + gap
    }
    val ownHeight = heights.getOrNull(from) ?: return copy(offset = moved)
    val ownTop = tops.getOrNull(from) ?: return copy(offset = moved)
    val centre = ownTop + ownHeight / 2f + moved
    var next = from
    heights.indices.forEach { index ->
        if (index == from) return@forEach
        val otherCentre = (tops[index]) + (heights[index] / 2f)
        if (index < from && centre < otherCentre) next = minOf(next, index)
        if (index > from && centre > otherCentre) next = maxOf(next, index)
    }
    return copy(offset = moved, to = next)
}

/**
 * One pointer sample: [advancedBy], plus the two things only the THUMB may decide.
 *
 * THE HEADING IS TAKEN FROM THE SIGN OF THIS SAMPLE and not from anything cumulative, so a designer
 * who has auto-scrolled half a list and then pushes back up is heading up from that moment. And it
 * is WITHHELD until the thumb has travelled [arm], which is the arming rule: a press that has not
 * moved has no direction, and a grip resting near the bottom of the screen must not be able to start
 * the list travelling on its own.
 *
 * A gesture that comes back to within [arm] of where it started is a press again, and stops. That is
 * deliberate rather than incidental — it is the one way to call off an auto-scroll without lifting
 * the thumb, and lifting the thumb would commit the arrangement.
 */
internal fun DwDragState.draggedBy(delta: Float, gap: Float, arm: Float): DwDragState {
    val moved = advancedBy(delta, gap)
    val travelled = travel + delta
    val nextHeading = when {
        abs(travelled) < arm -> 0
        delta > 0f -> 1
        delta < 0f -> -1
        else -> heading
    }
    return moved.copy(travel = travelled, heading = nextHeading)
}

/**
 * The rectangle the auto-scroll asks its ancestor to reveal, in the GRIP's own coordinates.
 *
 * A band of [lookahead] just beyond the grip, on the side [heading] is pushing — never the grip
 * itself and never the card. Two reasons, and both of them are failures the obvious version has:
 *
 *  * **A rectangle that already fills the viewport is a request the platform correctly ignores.** A
 *    review card can be taller than a handset screen, so asking to reveal the card would do nothing
 *    at exactly the sizes where the drag needs the most help.
 *  * **A band that included the grip would fight the thumb.** The grip is pinned under the thumb; a
 *    request to also keep it fully visible is a request the ancestor can satisfy by scrolling the
 *    OTHER way when the thumb is near an edge.
 *
 * The width is nominal — the ancestor scrolls vertically and reads only the two vertical edges — and
 * is 1px rather than 0 so nothing downstream has to have an opinion about an empty rectangle.
 */
internal fun dwAutoScrollBand(heading: Int, gripHeight: Float, lookahead: Float): Rect = when {
    heading > 0 -> Rect(left = 0f, top = gripHeight, right = 1f, bottom = gripHeight + lookahead)
    heading < 0 -> Rect(left = 0f, top = -lookahead, right = 1f, bottom = 0f)
    // Unreachable from the loop, which checks the heading first. Zero rather than an exception: a
    // drag is the wrong place to discover a programming error by crashing.
    else -> Rect.Zero
}

/**
 * How far the row at [index] is pushed while a drag is in flight, in pixels.
 *
 * The dragged row follows the thumb; every row between where it came from and where it is going moves
 * by one dragged-row height, in the direction that opens the gap. Nothing else moves. An extension on
 * the NULLABLE state so the call site reads as one expression and cannot forget the at-rest case.
 *
 * THE DRAGGED ROW GETS [DwDragState.offset], WHICH ALREADY CARRIES BOTH HALVES OF ITS TRAVEL. The row
 * is laid out normally and so it moves WITH the list when the auto-scroll runs; the scrolled pixels
 * folded into `offset` are what put it back under a thumb that did not move. Take them out and the
 * card slides away upward at exactly the speed the list is travelling — which reads as the gesture
 * having been dropped, at the moment the designer is furthest from where they started.
 */
internal fun DwDragState?.shiftFor(index: Int, gap: Float): Float {
    val drag = this ?: return 0f
    val ownHeight = drag.heights.getOrNull(drag.from) ?: return 0f
    // The gap travels with the row: a card sliding past its neighbour has to clear the neighbour AND
    // the space between them, or the two overlap by 12dp at every swap.
    val step = ownHeight + gap
    return when {
        index == drag.from -> drag.offset
        index > drag.from && index <= drag.to -> -step
        index < drag.from && index >= drag.to -> step
        else -> 0f
    }
}
