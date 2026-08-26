package com.designprototype.workshop.ui.designworkshop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.designprototype.workshop.ui.LocalAppPreferences
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import java.io.File

/**
 * A gallery browsed one picture at a time — the handset's half of the motif carousel.
 *
 * Asked for on 2026-08-25 for the two new motif galleries on stage 4, and built to the same
 * description as the web's `components/media/MediaCarousel.tsx`: *"Uploaded Traditional Motif images
 * should be viewable through a carousel so that users can visually browse the uploaded
 * references."* A column of 52dp attachment rows answers "how many did I attach"; it does not answer
 * "is this the right motif", which needs one image big enough to see.
 *
 * ── WHAT IS DELIBERATELY DIFFERENT FROM THE WEB, AND WHY ────────────────────────────────────────
 *
 * The frontend contract's rule is that a platform difference is COMMENTED, never a paraphrase of the
 * copy. Two things differ here and both are the phone:
 *
 * 1. **The frame is 220dp, not a viewport fraction.** The web sizes its frame `h-72 sm:h-96` against
 *    a window; a handset has one column and a soft keyboard that takes half of it, so a proportional
 *    frame would push the bullet list the designer is writing off the screen exactly when they are
 *    comparing the two. 220dp is the same bounded-height reasoning `MapScreen`'s `LIST_MAX_HEIGHT`
 *    is built on.
 *
 * 2. **The arrows are real IconButtons beside the readout rather than overlays on the image.** A
 *    44dp overlay target on a phone sits under the thumb that is trying to look at the picture, and
 *    an image is also the thing a designer wants to tap to open full screen. On a mouse the two can
 *    share the frame; on a thumb they cannot.
 *
 * Everything else is held identical on purpose, because a researcher moves between the two apps
 * mid-workshop: the position is PRINTED as "3 of 12", the active thumbnail carries a border, the
 * strip scrolls to follow the selection, and tapping the big frame opens the existing full-screen
 * viewer rather than a second one written here.
 *
 * ── THE POSITION IS A NUMBER, NOT A PLACE IN A ROW OF DOTS ──────────────────────────────────────
 *
 * Same rule as everywhere else in this app: a signal that exists only as motion or only as position
 * is a signal a reduced-motion reader, or a screen-reader user, never gets. The slide is the
 * ornament; the readout is the state — which is also why the slide collapses to a plain fade under
 * [LocalAppPreferences]`.reducedMotion` and the readout does not change at all.
 */

/** The height of the big frame. See the header for why this is a fixed dp and not a fraction. */
private val FRAME_HEIGHT = 220.dp

/**
 * A trailing picture word, and ONLY the one [dwDescribeSubject] writes itself.
 *
 * `(?:^|\s)` rather than a bare suffix test: with `\s*` a label reading "microphotographs" would be
 * stripped to "micro", which is a word taken out of somebody's label to fix a stutter that was never
 * there. Either the string IS the picture word or the picture word follows a space. The cost of that
 * choice, stated: such a label doubles instead ("microphotographs photographs"). No registry label
 * looks like this, and mangling a real word is the worse of the two failures.
 *
 * A CHARACTER-FOR-CHARACTER PORT of `TRAILING_PICTURE_WORD` in
 * `frontend/components/media/MediaCarousel.tsx`. `String.replace(Regex, …)` in Kotlin replaces every
 * match where JavaScript's non-global `.replace` replaces the first — which cannot matter for a
 * pattern anchored at `$`, and is noted so nobody "fixes" one client to match the other's semantics.
 */
private val TRAILING_PICTURE_WORD = Regex("(?:^|\\s)photographs?\$", RegexOption.IGNORE_CASE)

/** Runs of whitespace, collapsed before the suffix test so " Traditional  motif " behaves. */
private val WHITESPACE_RUN = Regex("\\s+")

/** What a gallery holds, in the singular and the plural. See [dwDescribeSubject]. */
internal data class DwCarouselSubject(val one: String, val many: String)

/**
 * THE SUBJECT OF THE GALLERY, IN SINGULAR AND PLURAL, DERIVED FROM WHATEVER THE CALLER PASSED.
 *
 * ── THE DEFECT: THE ACCESSIBLE NAME SAID "PHOTOGRAPHS" TWICE, AND HERE IT SAID A PLURAL ────────
 *
 * `noun`'s doc asks for the field's whole label — *"traditional motif photographs"* — and
 * `DwMediaCapture.kt` duly passes `field.label.lowercase()`. A carousel is mounted only on a CAPPED
 * gallery, and the two capped galleries in the registry are labelled "Traditional motif
 * photographs" and "Contemporary motif photographs", so until this function was wired into the
 * composable below every arrow announced "Previous traditional motif photographs" — a PLURAL for a
 * control that moves by exactly one picture, on every single step. The frame was never LITERALLY
 * nameless, which is the half of this account that was itself wrong: `clickable` makes the frame a
 * merging semantics node, so its whole accessible name was the child `AsyncImage`'s
 * `contentDescription`, the FILENAME of whichever picture happened to be showing. A reader arriving
 * there was told "IMG_2384.jpg" — the one thing the readout row was already printing beside it — and
 * nothing at all about what they had arrived at.
 *
 * The web twin had the mirror-image symptom from the same prop: it appended a picture word of its
 * own, so the same label produced "traditional motif photographs photographs" on the region and
 * "Previous traditional motif photographs photograph" on each arrow. Its own doc asked for a bare
 * noun; NEITHER of the two call sites in the repository ever passed one. One prop, two contradictory
 * contracts, neither honoured, and two clients wrong in different directions.
 *
 * ── WHY THE RULE LIVES HERE AND NOT AT THE CALL SITES ───────────────────────────────────────────
 *
 * Fixing the callers would mean making the same edit once per client, by somebody who has to notice
 * it is due — and the evidence that they would not is that two independent call sites already made
 * the identical mistake in the same words. This file is also the only place that knows HOW the word
 * gets used: as the frame's name, and in "Previous {subject}", where one step needs the SINGULAR and
 * the name needs the plural. A caller cannot supply a form it is never told about.
 *
 * So either shape works and both produce the same strings: the field's own label ("Traditional motif
 * photographs") or a bare noun ("traditional motif"). The empty stem is a real case and not
 * defensiveness — three IMAGE_LIST fields in the registry are labelled exactly "Photographs"
 * (`productPhotos`, `responsePhotos`, `logPhotos`), and any of them reaches this component the day it
 * declares a cap. Whitespace is COLLAPSED rather than only trimmed, because on the web an empty stem
 * also printed a doubled space inside its empty-gallery sentence.
 *
 * ── WHAT THIS DELIBERATELY DOES NOT DO (rule 10) ────────────────────────────────────────────────
 *
 * It recognises "photograph"/"photographs" and no other picture word. "Photos", "images" and
 * "pictures" are left alone because no registry label uses them, and a stripper guessing at synonyms
 * eventually eats a real word. A label ending in anything else keeps all of it: "360° capture"
 * becomes "360° capture photograph", which is what such a gallery holds.
 *
 * It also never re-cases. `DwMediaCapture.kt` lowercases the label so the sentence around it reads as
 * a sentence; title case would suit the frame's name better, but the lowering that turns
 * "Traditional" into "traditional" would flatten a proper noun in a label the registry has not
 * written yet, and case is not something a screen reader pronounces.
 *
 * ── THE ONE STRING THIS CLIENT DOES NOT HAVE ────────────────────────────────────────────────────
 *
 * The web derives FOUR strings from the subject; this file derives three. The fourth is its
 * empty-gallery sentence, and there is deliberately no counterpart: this composable returns at
 * `items.isEmpty()` because `DwMediaCaptureCard` prints "No photographs yet." for an empty field
 * itself, one level up, and a second sentence here would say it twice under the same heading. That
 * is a platform difference in WHERE the sentence lives, not in whether it exists.
 *
 * `internal` so `DwMediaCarouselSubjectTest` can exercise the rule without a Compose renderer —
 * exactly as `dwOpenTarget` and `dwDocCacheName` are, and for the same reason: a judgement written
 * inside a composable is only ever checked by somebody looking at a screen. The web exports its twin
 * for the same purpose.
 */
internal fun dwDescribeSubject(noun: String): DwCarouselSubject {
    val stem = noun
        .replace(WHITESPACE_RUN, " ")
        .trim()
        .replace(TRAILING_PICTURE_WORD, "")
        .trim()
    if (stem.isEmpty()) return DwCarouselSubject(one = "photograph", many = "photographs")
    return DwCarouselSubject(one = "$stem photograph", many = "$stem photographs")
}

@Composable
internal fun DwMediaCarousel(
    /** The IMAGE attachments of one field, in the order the field holds them. */
    items: List<DwMediaItem>,
    /** What these pictures ARE, in the reader's words — "traditional motif photographs". */
    noun: String,
    /** Open one full screen. The card above already owns a viewer; this does not write a second. */
    onOpen: (DwMediaItem) -> Unit,
) {
    if (items.isEmpty()) return

    val reduceMotion = LocalAppPreferences.current.reducedMotion

    /*
      THE ONE PLACE THE CALLER'S WORDS BECOME THE TWO FORMS THIS SCREEN NEEDS. Derived once per
      `noun` rather than at each of the three use sites below, so a plural cannot reappear on an
      arrow because somebody added a fourth. See [dwDescribeSubject] for why the rule lives in this
      file rather than at the call sites that pass `noun`.
    */
    val subject = remember(noun) { dwDescribeSubject(noun) }

    /**
     * WHERE THE READER IS AND WHICH WAY THEY CAME — one piece of state, not two.
     *
     * The direction decides which way the slide leans and is read during composition, so keeping it
     * beside the index is what makes the two impossible to set apart. An index without its direction
     * is a slide that leans at random. The web file makes the same choice for the same reason.
     */
    var position by remember(items.size) { mutableStateOf(0 to 1) }
    val (rawIndex, direction) = position

    /*
      CLAMPED HERE, NEVER "FIXED UP" AFTERWARDS. The list shrinks under this composable in the
      ordinary course of use — a designer removes the photograph they are looking at and `items`
      arrives one shorter on the next composition. Reading `items[7]` of a seven-item list throws
      during composition, which takes the whole stage screen down rather than losing a carousel.
    */
    val index = rawIndex.coerceIn(0, items.lastIndex)
    val current = items[index]

    fun go(to: Int) {
        val wrapped = ((to % items.size) + items.size) % items.size
        // Leaning the way the reader PRESSED rather than the way the index jumped: a wrap forward is
        // a step forward that happens to land at zero.
        val heading = when {
            index == items.lastIndex && wrapped == 0 -> 1
            index == 0 && wrapped == items.lastIndex -> -1
            else -> if (wrapped > index) 1 else -1
        }
        position = wrapped to heading
    }

    val stripState = rememberLazyListState()
    /*
      Keep the active thumbnail in view. `animateScrollToItem` under motion, a hard jump under
      reduced motion — the same split every other animated scroll in this app makes, and the reason
      the preference is read rather than the animation being unconditional.
    */
    LaunchedEffect(index, reduceMotion) {
        if (reduceMotion) stripState.scrollToItem(index) else stripState.animateScrollToItem(index)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        /*
          THE FRAME IS NAMED WITH THE SUBJECT, AND THE PICTURE INSIDE IT IS NOW DECORATIVE — ONE
          LABEL PER STOP, NOT TWO.

          `clickable` below already makes this Box a MERGING semantics node, so the frame has always
          had a name; it was just the wrong one. It inherited the child `AsyncImage`'s
          `contentDescription`, the filename of whichever picture was showing — which the readout row
          below already prints as ordinary text — so the frame said the one fact that was said twice,
          and never said what the gallery holds.

          Naming the frame WITHOUT taking the filename off the image would not have replaced that, it
          would have APPENDED to it: `ContentDescription`'s merge policy concatenates a merging
          node's own value with its descendants', so every arrow press would still have read a
          filename out. Hence `contentDescription = null` on the image. The filename is said once, by
          the `Text` that sits beside the "3 of 12" readout; the frame says what this IS.

          The plural and not the singular: this names a gallery the reader has arrived at, and the
          readout says which of it they are on. The singular belongs to the arrows, which move by
          exactly one — that split is the whole reason [dwDescribeSubject] returns a pair.
        */
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(FRAME_HEIGHT)
                .background(MaterialTheme.field.surface50, RoundedCornerShape(10.dp))
                .clickable { onOpen(current) }
                .semantics { contentDescription = subject.many }
        ) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    if (reduceMotion) {
                        // A plain cross-fade at the app's shortest duration. NOT zero: an instant
                        // swap of a 220dp picture reads as a glitch, and reduced motion asks for no
                        // MOVEMENT rather than for no change at all.
                        fadeIn(tween(90)) togetherWith fadeOut(tween(90))
                    } else {
                        val offset = 40
                        (slideInHorizontally(tween(200)) { if (direction >= 0) offset else -offset } +
                            fadeIn(tween(200))) togetherWith
                            (slideOutHorizontally(tween(160)) { if (direction >= 0) -offset else offset } +
                                fadeOut(tween(160)))
                    }
                },
                label = "carousel-frame"
            ) { item ->
                val file = remember(item.absolutePath) { File(item.absolutePath) }
                if (file.exists()) {
                    AsyncImage(
                        model = file,
                        // DECORATIVE INSIDE A NAMED FRAME. The Box above carries the name and the
                        // readout row carries this file's own name; see the frame's comment for why
                        // leaving `item.displayName` here would have appended a filename to the
                        // frame's label rather than replaced it.
                        contentDescription = null,
                        // `Fit` and not `Crop`: this frame is for JUDGING a motif, and a crop that
                        // takes the border off a butidar plate answers the question wrongly. The
                        // thumbnails below crop, because there the job is telling them apart.
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp)
                    )
                } else {
                    // The bytes have gone. Said in words rather than drawn as an empty frame — the
                    // attachment row above says the same thing about the same file.
                    //
                    // AND SAID AS A `contentDescription`, NOT ONLY AS TEXT, now that the frame above
                    // has a name. For a screen reader a merging node's own `contentDescription`
                    // REPLACES its descendants' text, while merging with their
                    // `contentDescription`s — so left as plain text this sentence would still be on
                    // the screen and no longer in the announcement: the frame would say what the
                    // gallery holds and never that THIS one cannot be shown, which is the only news
                    // here. One `val` and not the literal twice, because a sentence that drifted
                    // between the eye and the ear would be a defect nobody could see.
                    val missing = "This file is no longer on this device."
                    Text(
                        missing,
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.semantics { contentDescription = missing }
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { go(index - 1) }, enabled = items.size > 1) {
                Icon(
                    Icons.Filled.ChevronLeft,
                    contentDescription = "Previous ${subject.one}",
                    modifier = Modifier.size(20.dp)
                )
            }
            // THE READOUT. The whole reason the arrows and the strip are not the only state.
            Text(
                "${index + 1} of ${items.size}",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
            IconButton(onClick = { go(index + 1) }, enabled = items.size > 1) {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Next ${subject.one}",
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                current.displayName,
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
        }

        if (items.size > 1) {
            LazyRow(
                state = stripState,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { at, item ->
                    val active = at == index
                    val file = remember(item.absolutePath) { File(item.absolutePath) }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.field.surface100)
                            .border(
                                width = if (active) 2.dp else 1.dp,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.field.hairline,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { position = at to (if (at > index) 1 else -1) }
                    ) {
                        if (file.exists()) {
                            AsyncImage(
                                model = file,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("${at + 1}", color = MaterialTheme.field.muted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}
