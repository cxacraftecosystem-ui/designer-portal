package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwDisplayImage
import com.designprototype.workshop.data.DwImageDecode
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * **THE ONE PHOTOGRAPH THE DERIVATION CARDS WORK FROM — requirements 5, 18 and 20 on the handset.**
 *
 * ── THE REPORT THIS ANSWERS, AND WHAT IT ACTUALLY WAS ON THIS CLIENT ──────────────────────────
 *
 * "A designer photographs a sketch once and then has to upload it twice." On the web that was
 * literally true and `SharedPhotoField.tsx`'s header records the shape of it: the tracing panel owned
 * the only `<input type="file">` on the Upload tab and the measuring card owned none, so the same
 * bytes took two routes — once into the trace decoder, and again, after an attach and a sync and a
 * reload, into `useMeasurablePhotos`.
 *
 * **IT WAS NEVER TRUE HERE, AND THAT IS WHY THIS FILE IS A CHOOSER AND NOT A PICKER.** There is no
 * file dialog in any of the three derivation panels — `grep -c rememberLauncherForActivityResult`
 * over `DwSketchTracePanel.kt`, `DwSketchRectifyField.kt` and `DwPhotoMeasureField.kt` is 0, 0, 0 —
 * because on this client a photograph reaches a panel by being IMPORTED first: `DwMediaBridge.attach`
 * copies the bytes into the workshop's own directory under `filesDir` and hands back an id, for the
 * reason `FieldRenderer.kt:126-134` gives (cacheDir is reclaimed silently under storage pressure and
 * a content Uri is a grant scoped to the task that received it). So the handset's "one upload" is one
 * ordinary capture into one media field, and what was genuinely missing was **one answer to WHICH of
 * those photographs the cards are working from, said out loud, in one place, with a picture of it.**
 *
 * That is what this file is. It owns the choice; the cards read it.
 *
 * ── WHAT IS SHARED IS THE FILE, NOT A DECODED BITMAP, AND THAT IS THE WHOLE MEMORY ARGUMENT ───
 *
 * The obvious reading of "decode once" is one `Bitmap` handed to both cards. **On this feature that
 * raises the peak rather than lowering it, and it is the one refactor that could turn a working
 * courtyard into an OutOfMemoryError**, for two independent reasons:
 *
 *  1. **The two decodes are not the same decode and cannot be made one.** The tracing engine needs
 *     ARGB_8888 up to `DW_TRACE_DECODE_MAX_EDGE_PX` = 4096 (`DwSketchTrace.kt:203-217` spends a
 *     paragraph on why reusing the display decode would put this client a hundredfold outside the
 *     cross-client parity budget the vendored engine is held to); the marking surface needs RGB_565
 *     at `DwImageDecode.DISPLAY_EDGE_PX` = 2400, where 565 is honest precisely because nothing
 *     downstream reads a pixel VALUE. A config difference is a correctness difference here, not a
 *     tuning knob.
 *  2. **A second holder would defeat the one recycle this feature depends on.**
 *     `DwTraceKotlinRuntime.kt:1021-1027` recycles the trace decode the instant `readRgba` has copied
 *     it out, because it is up to 67 MB and the engine is about to allocate a 73-278 MB working
 *     plane. A card holding a reference to that same bitmap would keep 67 MB alive across exactly
 *     that allocation — and if the runtime recycled it anyway, the other card would draw a recycled
 *     bitmap, which is `Canvas: trying to use a recycled bitmap` in the middle of an unsaved stage.
 *
 * ── SO: WHO OWNS A BITMAP, AND WHO RECYCLES ONE ───────────────────────────────────────────────
 *
 * | Bitmap | Owner | Size | Recycled by |
 * |---|---|---|---|
 * | The trace decode | `DwTraceKotlinRuntime`, inside one run | ARGB_8888 ≤ 4096 px | **`DwTraceKotlinRuntime`, immediately after `readRgba`** |
 * | The marking working copy | `DwPhotoMeasureOpen` / `DwSketchRectifyOpen`, dropped on collapse | RGB_565 ≤ 2400 px | nobody, and nobody may |
 * | The comparison plates | `DwSketchTraceOpen`, dropped with the result | ARGB_8888 1024 px | nobody |
 * | **This card's preview** | **[DwSketchSharedPhotograph], dropped when the choice changes** | **RGB_565 ≤ [DW_SHARED_PREVIEW_EDGE_PX]** | **nobody** |
 *
 * **THERE IS EXACTLY ONE RECYCLER IN THE WHOLE FEATURE AND IT IS THE TRACE RUNTIME**, on a bitmap no
 * composable has ever seen. Every other bitmap here is held by Compose through an `ImageBitmap` for
 * as long as a frame is on screen, and `DwImageDecode`'s header (`ImageQualityDecode.kt:168-175`) is
 * the standing rule: dropping the reference is the release, and recycling one that is still being
 * drawn throws. Nothing in this file recycles anything.
 *
 * ── AND WHAT "DECODE ONCE" MEANS HERE, WHICH IS A PROPERTY THAT CAN ACTUALLY BE HELD ──────────
 *
 * **At most one FULL-SIZE decode of the chosen photograph exists at any instant.** The marking
 * copy lives in an open half and dies with it; the trace copy lives inside one run and is recycled
 * inside it; and this card — which is on screen the whole time, above both — is capped at
 * [DW_SHARED_PREVIEW_EDGE_PX] so that adding a permanent preview did not add a permanent 6 MB. The
 * decode goes through `DwImageDecode.decodeForDisplay`, which reads the header with
 * `inJustDecodeBounds` and picks a power-of-two `inSampleSize` BEFORE allocating anything, so a
 * 12 MP frame never exists in memory at 12 MP on the way to a 64 dp square. That sampling discipline
 * is the repository's, not this file's: see `ImageQualityDecode.kt:183-196` and `DwSketchTrace.kt:252-266`,
 * which sample in opposite directions on purpose.
 *
 * ── THE ESCAPE HATCH, AND THE ONE PLACE IT IS DIFFERENT FROM THE WEB'S ────────────────────────
 *
 * `MeasureFromPhotoCard.tsx:233-251` is right and is ported: *"the sheet worth TRACING is the drawing
 * itself, flat and filling the frame, and the photograph worth MEASURING is the one with a ruler or a
 * scale card lying beside the object"*. So the measuring card may be pointed somewhere else, it is an
 * explicit press, and what it is looking at is named on screen so the two panels can never quietly
 * diverge. **What differs is the SET it chooses from**: the web offers a file that is on no record at
 * all, because its shared pick is an unfiled `File` in memory; here every photograph a panel can see
 * has already been imported, so the choice is among the record's own — which is a stronger position,
 * not a weaker one (a reload loses the web's; nothing loses this one). The act and its words are the
 * same, and that is what requirement 20 is about.
 *
 * ── AND WHAT IS DELIBERATELY NOT PORTED: "PUT THIS PHOTOGRAPH AWAY" ───────────────────────────
 *
 * The web needs it because its card holds ONE unfiled pick and, when there is no second file to
 * choose, putting it away is the only way out of a wrong one. Here the card holds a list the record
 * already owns: there is always another to choose, choosing is one press, and a control that left
 * both cards pointing at nothing would be a dead end nobody wants. Absent by argument, not by
 * oversight.
 *
 * ── AND WHERE THIS CARD IS NOT DRAWN AT ALL: PROTOTYPES — requirement 7 ───────────────────────
 *
 * [dwSharesOnePhotograph] gates it, and the short version is that a prototype has one derivation
 * card rather than three. `dwOffersSketchRectify` refuses both FILE fields a prototype declares —
 * "Measurement sheet" and "3D model" are not the home of a plate — so there is no straightening
 * panel and no tracing panel on that half, and a card whose one sentence is *which photograph are the
 * cards below all working from* would be answering a question with one card in it. Worse, it would be
 * a chooser sitting on top of the chooser the measuring card already owns.
 *
 * **THE HALF OF THE SHAPE THAT DOES TRANSFER IS THE LIST, NOT THE CARD.** A prototype declares two
 * image fields and `dwOffersPhotoMeasure` answers true for both, so the stage form gives one
 * prototype TWO measuring cards, each blind to the other field's photographs.
 * [DwSketchDerivationSection] hands one card the whole of `sources` instead. That is one upload
 * feeding one card that can actually see it — which is what requirement 5 amounts to on a record with
 * one consumer, and it is a real gain rather than a symmetry.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Numbers this surface owns
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The long edge the shared preview is decoded to.
 *
 * The preview is a 64 dp square and it is on screen for as long as the section is, which is the whole
 * reason this number is not `DwImageDecode.DISPLAY_EDGE_PX`. At 2400 px the permanent preview would
 * be about 6 MB of RGB_565 held behind two cards that are already the largest allocators in the app;
 * at 256 it is under 100 KB, and 64 dp at a 4x density is 256 px, so this is the smallest decode that
 * is still exactly sharp on the densest screen the fleet carries rather than one that visibly softens
 * the one picture whose whole job is to say WHICH photograph this is.
 */
internal const val DW_SHARED_PREVIEW_EDGE_PX: Int = 256

/* ────────────────────────────────────────────────────────────────────────────
 * The three ways a card can be told which photograph to use
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Where a derivation card's photograph comes from — one value, three states, and it cannot contradict
 * itself.
 *
 * ── THIS IS `SketchTraceField.tsx:278-302` IN KOTLIN, INCLUDING THE ARGUMENT ──────────────────
 *
 * The web writes it as `photograph?: ChosenPhotograph | null` and spends a paragraph on why the
 * ABSENCE is the switch rather than a `hasOwnPicker` flag: *"A boolean and a value can disagree, and
 * the disagreement here is silent and expensive in both directions: a flag saying 'the host owns it'
 * with no value gives a designer no picker at all, and a flag saying 'you own it' beside a value
 * gives them two pickers for one photograph — which is the duplication the whole change exists to
 * end. One prop cannot contradict itself."*
 *
 * Kotlin has no `undefined`, so the three states are three values of one sealed type. They mean
 * exactly what the web's three mean:
 *
 *  * [OwnChoice] — the card chooses for itself out of the list it was handed, exactly as it always
 *    has. That is the stage form's mount (`FieldRenderer.kt`), where each card sits on its own field,
 *    and every existing call site gets it by default. **Nothing about that path changed.**
 *  * [Hosted] with a null source — a host above owns the choice and nothing is chosen yet. The card
 *    draws no chooser of its own and says where the one chooser is, rather than offering a second.
 *  * [Hosted] with a source — the host owns the choice and this is it.
 */
@Immutable
internal sealed interface DwSketchPhotographSupply {

    /** The card chooses for itself. The default, and the stage form's mount. */
    @Immutable
    data object OwnChoice : DwSketchPhotographSupply

    /** A host above owns the choice; [source] is what it chose, or null for "nothing yet". */
    @Immutable
    data class Hosted(val source: DwSketchSource?) : DwSketchPhotographSupply
}

/* ────────────────────────────────────────────────────────────────────────────
 * The owner
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Which photograph the derivation cards are working from, and the one place that answers it.
 *
 * ── ONE STRING, AND WHY IT RESOLVES RATHER THAN BEING BELIEVED ────────────────────────────────
 *
 * The field is not read as a fact about the world. It is resolved against the photographs the
 * record ACTUALLY holds on this composition, and a chosen id that is no longer there falls back to
 * the first — the same discipline `DwMeasureConfig.usePhotograph` documents for its own chooser
 * chips: a photograph can be DETACHED while the cards are on screen, and a selection that survived
 * its subject would leave every chip unmarked with a card claiming to work from something that is
 * not in the list. Resolving on read makes that state unrepresentable instead of handled.
 *
 * ── `rememberSaveable`, WHICH THE PANELS THEMSELVES CANNOT USE ────────────────────────────────
 *
 * `DwMeasureConfig`'s header explains why it is a plain `remember`: `Map<DwMarkId, DwMark>` is not
 * `Bundle`-writable, so a saver would be a second, silently-versioned description of what a mark is.
 * **Neither objection applies to two strings.** So the CHOICE survives process death even though the
 * marks and the drawing do not, and that is the honest split: after a kill the designer comes back to
 * the same photograph under both cards and re-does the work, rather than coming back to the first
 * photograph in the list under a card that has forgotten which one they were looking at.
 *
 * Rotation loses nothing at all here, and not because of this class: `.MainActivity` declares
 * `configChanges="orientation|screenSize|…"` (`AndroidManifest.xml:98-101`), so the Activity is not
 * recreated and every `remember` in this subtree survives a turn.
 */
@Stable
internal class DwSketchDerivationPhoto(chosenId: String) {

    /**
     * The id the designer chose, or "" for "they have not said".
     *
     * "" IS NOT A THIRD STATE, it is the absence of the first: with nothing said, [chosen] answers
     * with the record's first photograph, which is what both cards did on their own before this
     * class existed and is what makes the section useful with no presses at all.
     */
    private var chosenId by mutableStateOf(chosenId)

    /** The photograph both cards work from, resolved against what the record holds now. */
    fun chosen(sources: List<DwSketchSource>): DwSketchSource? =
        sources.firstOrNull { it.item.id == chosenId } ?: sources.firstOrNull()

    /**
     * Work from this photograph — every card under this owner.
     *
     * ── AND THE MEASURING CARD'S ESCAPE HATCH IS NOT HERE, DELIBERATELY ───────────────────────
     *
     * "The measuring card is pointed somewhere else" is that card's own fact and lives in
     * `DwMeasureConfig` beside the marks it invalidates. Held here it would be a second field that
     * every consumer has to remember to ask about, and the state it would let through — a shared
     * choice moving while an override is in force — is one the panel has to reason about anyway,
     * because it owns the marks that a change of photograph destroys. One owner per fact.
     */
    fun choose(id: String) {
        chosenId = id
    }

    companion object {
        /**
         * One string, so this needs no hand-written `Saver` of its own shape.
         *
         * `listSaver` over `Bundle`-writable primitives is the cheap end of `rememberSaveable`, and it
         * is available here for exactly the reason it is not available to `DwMeasureConfig`: nothing
         * in this class is a domain object with an error bar attached to it. See the class header.
         */
        val Saver = listSaver<DwSketchDerivationPhoto, String>(
            save = { listOf(it.chosenId) },
            restore = { DwSketchDerivationPhoto(it.getOrElse(0) { "" }) },
        )
    }
}

/** The single owner, remembered across the open/close of every card under it and across a kill. */
@Composable
internal fun rememberDwSketchDerivationPhoto(): DwSketchDerivationPhoto =
    rememberSaveable(saver = DwSketchDerivationPhoto.Saver) { DwSketchDerivationPhoto("") }

/* ────────────────────────────────────────────────────────────────────────────
 * The words
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What the card is called.
 *
 * VERBATIM FROM `SharedPhotoField.tsx:88` — `label="Photograph of the sketch"`. This repository keeps
 * the export-format strings identical across the two clients and treats that as the standard; a card
 * that does the same job under a different name is the same defect as a format list that does.
 */
internal const val DW_SHARED_PHOTOGRAPH_LABEL: String = "Photograph of the sketch"

/**
 * The idle sentence, before anything is chosen — **verbatim from `SharedPhotoField.tsx`.**
 *
 * Every clause of it is true on this client: both panels below do work from the same photograph, and
 * neither of them files anything until a button in it is pressed (the tracing panel's "Add the line
 * art to …" and the measuring card's "…: 24.0 cm" are the only writes either of them has).
 *
 * ── AND IT IS NOT RENDERED HERE, WHICH IS THE POINT OF IT — DO NOT "WIRE IT UP" ───────────────
 *
 * **This client has no idle state for it to be said in.** The other client's card starts empty and
 * fills when a file dialog returns, so "nothing chosen yet" is a real screen there. Here the card is
 * only ever composed over a non-empty `sources` and [DwSketchDerivationPhoto.chosen] falls back to
 * the record's first photograph, so something is always chosen — and the state that LOOKS like idle,
 * a record with no photographs at all, is a different fact with a different remedy and gets
 * [dwNoPhotographSentence] instead.
 *
 * So this constant exists to be COMPARED, not printed: `DwSketchDerivationPhotoTest` asserts the
 * portal's source still contains it character for character, which is the only mechanical check that
 * the sentence this client deliberately diverged from has not moved underneath the divergence. A
 * reader who "fixes" the unused constant by rendering it would put a card-is-empty sentence over a
 * card that is not.
 */
internal const val DW_SHARED_PHOTOGRAPH_IDLE: String =
    // Re-copied verbatim 2026-09-03, when the portal's copy was shortened under the owner's
    // one-line rule (SharedPhotoField.tsx). DwSketchDerivationPhotoTest holds the two equal.
    "Choose it once. Both panels below work from it; neither files anything until you press a " +
        "button there."

/**
 * What the section says when there is no photograph on the record yet — **naming only the acts THIS
 * record can actually perform on one.**
 *
 * THE WEB HAS NO SENTENCE FOR THIS STATE AND DOES NOT NEED ONE — its card carries a file dialog, so
 * "nothing chosen" is one press from over. Here the photograph has to exist on the record first, so
 * the empty state has a different remedy and gets its own words.
 *
 * ── WHY THIS IS A FUNCTION AND NOT THE CONSTANT IT WAS — requirement 7 ─────────────────────
 *
 * The constant read *"…so there is nothing to trace, straighten or measure against"* on every record
 * that reached it, and **it reaches prototypes.** [dwOffersSketchRectify] refuses both FILE fields a
 * prototype declares — "Measurement sheet" and "3D model" are not the home of a plate — so that half
 * of the Upload tab mounts no straightening panel and no tracing panel at all. A designer standing on
 * an empty prototype row was therefore being told, by the only sentence on that screen, about two
 * capabilities the record does not have and no amount of scrolling will find.
 *
 * That is the same class of defect as a sentence pointing at a control that is not there, which this
 * pair of surfaces has already paid for once on the other client — `MeasureFromPhotoCard.tsx` fixed
 * exactly this sentence for exactly this half and wrote down why: *"a sentence pointing at the wrong
 * place is the defect this tab has already paid for once"*. It is worse in one direction. A control
 * that is missing can be looked for and not found; a CAPABILITY that was never offered on this
 * entity, named in the one sentence a designer has to go on, reads as a build that is broken.
 *
 * The constant's own note argued that it "names no panels, which is a correction and not a
 * vagueness", and that was half of a fix. It stopped COUNTING the panels, which is what the note
 * claimed. It went on naming the three ACTS, which is the same assertion again in verbs. So the acts
 * are read off the two offers the section has already worked out — the same two booleans
 * [dwSharesOnePhotograph] is handed, from the same call, so the sentence and the cards below it
 * cannot come to different answers about what this record can do.
 *
 * ── AND IT NAMES THE FIELDS A PHOTOGRAPH WOULD GO IN, WHICH IS THE OTHER CLIENT'S OWN CORRECTION ─
 *
 * `MeasureFromPhotoCard.tsx`'s empty branch names them — *"Attach one in {fieldsPhrase} — from a panel
 * above, where this tab offers that field, or on the {what}'s own stage form, which offers all of
 * them"* — and the comment above it records why the un-named version was wrong on precisely this
 * half: a prototype's photographs live in `prototypePhotos` AND `turntablePhotos`, and only the
 * second of those has a capture card on this tab. Naming both fields, and naming both places they can
 * be attached, is the whole of it. ONE SUBSTITUTION: the other client says "from a panel above" where
 * this one says "with the capture cards above", because a capture card is what is above here.
 *
 * @param offersPlate whether this record offers the straightening and tracing panels — which is
 *   [dwOffersSketchRectify]'s answer about a FILE field, asked once by the caller and not guessed at
 *   again here.
 * @param offersMeasure whether the entity records a length a photograph could be measured into.
 * @param photoFieldLabels the registry labels of every image field this entity declares, in registry
 *   order. Empty is an honest state and not an error: a caller that does not know says so, and gets a
 *   sentence that still points somewhere rather than one naming a field nobody looked up.
 */
internal fun dwNoPhotographSentence(
    offersPlate: Boolean,
    offersMeasure: Boolean,
    photoFieldLabels: List<String>,
): String {
    val acts = when {
        offersPlate && offersMeasure -> "trace, straighten or measure against"
        offersPlate -> "trace or straighten"
        offersMeasure -> "measure against"
        // NOT REACHABLE FROM THE ONE CALLER, AND ANSWERED ANYWAY RATHER THAN LEFT TO FALL INTO THE
        // NEAREST BRANCH. [DwSketchDerivationSection] returns before it reaches this function on a
        // record that offers neither, so this arm is a guard against a future caller — and the one
        // thing it must not do is name an act, because naming an act the record has none of is
        // precisely the defect this whole function was written to end.
        else -> "work from"
    }
    val where = if (photoFieldLabels.isEmpty()) {
        // NO LABELS IS "THE CALLER DID NOT SAY", NOT "THERE ARE NO FIELDS". The generic phrase is
        // still true and still points at somewhere a designer can go; inventing a field name here
        // would be printing a label this function never read.
        "one of this record's image fields"
    } else {
        photoFieldLabels.joinToString(" or ") { "“${it.trim()}”" }
    }
    return "There is no photograph on this record yet, so there is nothing to $acts. Attach one in " +
        "$where — with the capture cards above, where this tab offers that field, or on this " +
        "record's own stage form, which offers all of them. Every panel here reads the photographs " +
        "that are already on the record, so nothing needs uploading twice."
}

/** The escape hatch, by the name it has on the other client. `MeasureFromPhotoCard.tsx:886`. */
internal const val DW_MEASURE_DIFFERENT_PHOTOGRAPH: String = "Measure a different photograph"

/** The way back out of it. `MeasureFromPhotoCard.tsx:864`. */
internal const val DW_MEASURE_BACK_TO_SHARED: String = "Go back to the photograph chosen above"

/** The heading over the escape hatch while it is in force. `MeasureFromPhotoCard.tsx:849`. */
internal const val DW_MEASURE_ELSEWHERE_TITLE: String = "Measuring a different photograph"

/**
 * Why the two panels may want two photographs, in the other client's own words.
 *
 * `MeasureFromPhotoCard.tsx:891` reads *"The sheet worth tracing and the photograph with a ruler
 * beside the object are often two different pictures. This measures one that is not the one being
 * traced, and files neither."* The first sentence is carried across unchanged because it is the whole
 * argument. The second is not: "files neither" is a promise about an unfiled browser `File`, and on
 * this client both photographs are already on the record — so the true clause is that this changes
 * nothing about them, which is what is written instead.
 */
internal const val DW_MEASURE_DIFFERENT_WHY: String =
    "The sheet worth tracing and the photograph with a ruler beside the object are often two " +
        "different pictures. This measures one that is not the one being traced. Neither photograph " +
        "is changed, moved or detached by anything on this card."

/**
 * What the shared card says once a photograph is chosen.
 *
 * ── THE ONE SENTENCE THAT IS NOT THE WEB'S, AND THE REASON IS NOT TASTE ───────────────────────
 *
 * `SharedPhotoField.tsx:178` reads *"Both panels below work from this photograph. Nothing has been
 * filed yet — “Sketch image” on “Untitled 1” is written only when a button in one of them is
 * pressed."* The first sentence is true here and is kept word for word. **The second is false here**,
 * and loudly so: the photograph a panel can see on this client is one that `DwMediaBridge.attach`
 * has already imported into `filesDir` and written into the row — that is the whole reason a card
 * can be handed a path at all. Printing "Nothing has been filed yet" over a photograph that IS filed
 * would be the receipt-that-understates version of the failure the other client's own header calls
 * "the receipt-that-overstates", and a designer who believed it would go looking for a save button
 * that has already been pressed.
 *
 * So the shape of the sentence is kept — what the panels below are working from, then what has and
 * has not been written — and the second clause tells this client's truth.
 *
 * ── "EVERY PANEL" AND NOT "BOTH PANELS", WHICH IS THE SECOND DIVERGENCE AND A SMALLER ONE ─────
 *
 * The other client has exactly two panels under its card, so "Both panels below" is a true count
 * there. **This client has three on the same surface** — [DwSketchRectifyPanel],
 * [DwSketchTracePanel] and [DwPhotoMeasurePanel], because `dwOffersSketchRectify` mounts the
 * straightening panel that the Upload tab in a browser has no equivalent of. A word that counted to
 * two under three cards would leave a designer working out which two, on the one sentence whose whole
 * job is to say that they are all following one photograph. Nothing is enumerated instead of counted:
 * the section knows what it drew and this function does not, and a list written here would be a
 * second description of the mount that goes stale the day a fourth card appears.
 *
 * Pure, and taking strings rather than a [DwSketchSource] or a [FieldDto], so
 * `DwSketchDerivationPhotoTest` can pin the wording on the JVM with no composition to run it in.
 *
 * @param imageLabel the registry label of the field this photograph is attached to.
 * @param rowName what the row is called on screen, or null where the caller has no name for it.
 */
internal fun dwSharedPhotographSentence(imageLabel: String, rowName: String?): String {
    val where = if (rowName.isNullOrBlank()) {
        "“${imageLabel.trim()}” on this record"
    } else {
        "“${imageLabel.trim()}” on “${rowName.trim()}”"
    }
    return "Every panel below works from this photograph. It is already attached as $where — nothing " +
        "further is written until a button in one of the panels below is pressed, and none of " +
        "them ever changes the photograph itself."
}

/**
 * The size line under the file name — this client's answer to `SharedPhotoField.sizeSentence`.
 *
 * ── IT NAMES THE FRAME AND THE FILE, AND DELIBERATELY NOT "WHAT WAS READ" ─────────────────────
 *
 * The web can say "Read at 2048x1536, reduced from 4032x3024" because there is ONE decode on that tab
 * and both panels are downstream of it. Here there are three, at three ceilings, for the reasons the
 * file header sets out — so a single "read at" figure would be true of no card on the screen. Each
 * card states its own working copy where that matters and where it changes what the number means:
 * `DwPhotoMeasureOpen` prints "Marked on a 2000×1500 working copy of a 4032×3024 photograph" beside
 * the error bar that is worked out in those pixels, which is the place a reduction is load-bearing
 * rather than trivia.
 *
 * What this line owes the designer is only "which photograph is this" — the frame it was shot at and
 * how big the file is, which is what tells two photographs of one sheet apart at a glance.
 *
 * @param sourceWidth 0 when the header has not been read yet, which is a real state for the few
 *   hundred milliseconds the decode takes and must not print "0×0".
 */
internal fun dwPhotographSizeSentence(sourceWidth: Int, sourceHeight: Int, sizeBytes: Long): String {
    val file = if (sizeBytes > 0L) humanSize(sizeBytes) else null
    if (sourceWidth < 1 || sourceHeight < 1) {
        return file?.let { "$it. Opening the photograph…" } ?: "Opening the photograph…"
    }
    val frame = "$sourceWidth×$sourceHeight"
    return if (file == null) "$frame." else "$frame · $file."
}

/* ────────────────────────────────────────────────────────────────────────────
 * The card
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The shared preview: WHICH photograph both cards below are working from, as a picture and as words.
 *
 * ── WHY THE PICTURE IS NOT DECORATION ─────────────────────────────────────────────────────────
 *
 * `SharedPhotoField.tsx:120-131` states it and it is the same here: *"Two panels now work from one
 * pick, and the failure that arrangement invites is a designer tracing one photograph while measuring
 * another without either card saying so."* On a 6" screen the two cards can be a scroll apart, so the
 * cheapest possible answer to "which one am I working from" is the picture itself, with the file name
 * beside it for the reader who cannot see it.
 *
 * The thumbnail carries NO content description, deliberately and for the web's own reason: the file
 * name sits immediately beside it as text, so a description on the image would be the same fact
 * announced twice. The signal has to exist as words; it does not have to exist twice.
 *
 * ── THE CHOOSER IS CHIPS AND NOT A DIALOG, AND THAT IS A REAL DIFFERENCE BETWEEN THE CLIENTS ──
 *
 * The web's control opens a file dialog because its card holds an unfiled pick. This one chooses
 * among the photographs the record already holds, because that is what a panel on this client can be
 * handed — see the file header. The BUTTON WORDING follows the same rule the web states: a control
 * over a card that already holds a photograph must say which of the two acts it is, so the label
 * changes with the state rather than reading "Choose a photograph" over one that is already chosen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DwSketchSharedPhotograph(
    sources: List<DwSketchSource>,
    choice: DwSketchDerivationPhoto,
    /** What the row is called on screen, or null where the caller has no name for it. */
    rowName: String?,
    /**
     * What to say if this card is ever composed over a record with no photographs — the caller's
     * [dwNoPhotographSentence], not a second one worked out here.
     *
     * A WHOLE SENTENCE AND NOT THE TWO BOOLEANS THAT BUILD IT, because this card is in no position to
     * answer them. It knows what it was handed; it does not know whether a straightening panel or a
     * measuring card was mounted under it, and `dwSharesOnePhotograph(offersPlate = true,
     * offersMeasure = false)` is already two — a record with a plate field and no dimension draws this
     * card over two panels and no measuring card at all. A card that assumed all three would name an
     * act that record cannot perform, which is the exact defect [dwNoPhotographSentence] exists to
     * end. One sentence, computed once, where the offers are known.
     */
    noPhotographSentence: String,
    enabled: Boolean,
) {
    val chosen = choice.chosen(sources)

    /*
      THE ONE DECODE THIS CARD MAKES, HELD HERE RATHER THAN INSIDE THE SQUARE THAT DRAWS IT.

      The picture and the size line are two readings of the same decode — `DwDisplayImage` carries the
      ORIGINAL frame's dimensions with the EXIF rotation already applied to both the bitmap and the
      numbers, which is the only pair that cannot disagree — and `DwMediaItem` does not carry them
      (`DraftMedia` does, but the resolver on this side of the bridge deliberately narrows what a
      panel can see). A second decode purely to read a header would be a second decode of a camera
      image on the surface whose whole argument is that there is only ever one.

      DROPPED, NEVER RECYCLED, when the choice changes. See the file header's table: the only recycler
      in this feature is the trace runtime, on a bitmap no composable has ever seen.
    */
    var preview by remember { mutableStateOf<DwDisplayImage?>(null) }
    LaunchedEffect(chosen?.item?.id) {
        // Cleared before the await so the frame on screen is never the PREVIOUS photograph under this
        // photograph's name — the same one-commit hazard `usePhotoUrl` on the other client is written
        // against ("the new photograph's id over the old photograph's url").
        preview = null
        val path = chosen?.item?.absolutePath ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) {
            DwImageDecode.decodeForDisplay(path, DW_SHARED_PREVIEW_EDGE_PX)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface100, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.field.muted,
                modifier = Modifier.size(16.dp),
            )
            Text(
                DW_SHARED_PHOTOGRAPH_LABEL,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (chosen == null) {
            // NOT REACHED FROM [DwSketchDerivationSection], WHICH DRAWS THIS STATE ITSELF and returns
            // before it composes anything — the sentence has to be said on a prototype too, and this
            // card is not drawn there. The guard stays for a future caller that composes the card
            // directly, and it prints the caller's own sentence rather than a second one, so the two
            // cannot come to different answers about what this record can do with a photograph.
            Text(
                noPhotographSentence,
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            return@Column
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(8.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            DwSharedPhotographPreview(preview)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    chosen.item.displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    // ONE LINE AND AN ELLIPSIS IS WRONG HERE and is not what this does: `softWrap`
                    // is left alone so a long filename wraps onto a second line. Two photographs
                    // taken thirty seconds apart differ in the last four characters of their names,
                    // which is exactly what a truncation removes.
                    lineHeight = 17.sp,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    dwPhotographSizeSentence(
                        sourceWidth = preview?.sourceWidth ?: 0,
                        sourceHeight = preview?.sourceHeight ?: 0,
                        sizeBytes = chosen.item.sizeBytes,
                    ),
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        Text(
            // THE FIELD LABEL COMES OFF THE CHOSEN PHOTOGRAPH AND IS NOT A PARAMETER. The chooser
            // below spans every image field this entity declares, so "which field is this filed in"
            // is a property of the photograph rather than of the section — and a section-level label
            // would print "Sketch image" under a photograph attached to something else the moment an
            // entity declared two.
            dwSharedPhotographSentence(imageLabel = chosen.fieldLabel, rowName = rowName),
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        if (sources.size > 1) {
            DwPanelLabel(
                // The web's button says which of the two acts a press is; this says it over a row of
                // chips instead, because the act here is choosing a different one of several rather
                // than opening a dialog. Same distinction, same reason: pressing one REPLACES what
                // every panel below is working from, and that is a thing to know before pressing.
                "Choose a different photograph — every panel below follows this"
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                sources.forEach { candidate ->
                    DwPanelChip(
                        // The image field's own label is on the chip, because two photographs of one
                        // record often differ only in which slot they were attached to — the same
                        // reason `DwSketchRectifyPanel`'s own chips carry it.
                        label = "${candidate.fieldLabel} · ${candidate.item.displayName}",
                        selected = candidate.item.id == chosen.item.id,
                        enabled = enabled,
                        onClick = { choice.choose(candidate.item.id) },
                    )
                }
            }
        }
    }
}

/**
 * The 64 dp square, drawn from the card's own single decode.
 *
 * ── A FAILED DECODE IS A BLANK FRAME AND NOT A SENTENCE, WHICH IS THE ONE PLACE THIS PANEL IS
 *    QUIETER THAN THE REST OF THE FEATURE ─────────────────────────────────────────────────────
 *
 * Everywhere else in these three cards an unreadable photograph gets a sentence, because there it
 * stops the designer doing the thing they came for. Here it does not: the name, the size and both
 * cards below still work, and the panels each say in their own words that this device could not open
 * the bytes at the point where that actually costs something (`DwPhotoMeasureOpen`'s "This photograph
 * could not be opened on this device", `dwTraceSentence(IMAGE_UNREADABLE, …)`). A third copy of that
 * refusal over a decorative square would be the same news three times.
 */
@Composable
private fun DwSharedPhotographPreview(preview: DwDisplayImage?) {
    /** Wrapped once per decode rather than once per frame — the wrap is not free. */
    val bitmap = remember(preview) { preview?.bitmap?.asImageBitmap() }
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.field.surface100)
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                // Decorative: the file name is beside it in words. See the card's header.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * How many cards read the photograph — requirement 7's "where applicable"
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * How many derivation cards this record's registry entry puts under one photograph.
 *
 * ── WHY THIS IS A COUNT AND NOT A LIST OF ENTITY KEYS ─────────────────────────────────────────
 *
 * "Sketches get a shared photograph and prototypes do not" is the right ANSWER on the bundled
 * registry and the wrong RULE, because it is a fact about two entity keys that this file would then
 * be asserting without reading. The rule is about arithmetic a designer can see on the screen in
 * front of them: a photograph is SHARED when more than one card is looking at it, and it is simply
 * a photograph when one card is. Both halves of the Upload tab go through this, and the answer falls
 * out of [dwOffersSketchRectify] and [dwOffersPhotoMeasure] rather than out of a name.
 *
 * ── AND WHY A PLATE FIELD COUNTS AS TWO ───────────────────────────────────────────────────────
 *
 * Because it mounts two cards, not one. [DwSketchDerivationSection] draws [DwSketchRectifyPanel] AND
 * [DwSketchTracePanel] off the same FILE field — `dwOffersSketchTrace` is a one-line delegation to
 * `dwOffersSketchRectify` precisely so the two can never be offered in different places — so a
 * record with a plate field and no dimensions still has two cards reading one photograph, and still
 * wants the shared card above them. Counting the FIELDS rather than the CARDS would get that record
 * wrong in the direction nobody would notice: one field, one card by mistake, no shared photograph,
 * and a straightening panel and a tracing panel quietly working from two different chip selections.
 *
 * Pure booleans rather than a `FieldDto?` and a list, so `DwSketchDerivationPhotoTest` can state the
 * four cases as arithmetic with no registry to load and no composition to run it in.
 */
internal fun dwDerivationCardCount(offersPlate: Boolean, offersMeasure: Boolean): Int =
    (if (offersPlate) 2 else 0) + (if (offersMeasure) 1 else 0)

/**
 * **IS THERE ANYTHING HERE TO SHARE? — the whole of requirement 7's "where applicable" on this
 * client, in one predicate.**
 *
 * ── WHAT A SHARED PHOTOGRAPH CARD IS FOR, AND WHAT IT COSTS WHERE IT IS NOT ───────────────────
 *
 * [DwSketchSharedPhotograph] answers one question: *which photograph are the cards below all working
 * from*. With two or three cards under it that question is real, it is expensive to answer any other
 * way (on a 6" screen those cards are a scroll apart), and getting it wrong is the failure the whole
 * arrangement exists to prevent — tracing one sheet while measuring another with neither card saying
 * so.
 *
 * **With ONE card under it the question has no content, and the card stops being an answer and
 * becomes a rival.** The measuring card already owns a chooser over exactly the same photographs; a
 * second chooser above it, holding a choice the card then has to follow, is two pickers for one
 * photograph — which is the literal duplication this whole change was reported to remove. It would
 * also print, on a prototype, a card called "Photograph of the sketch" over sentences naming a
 * tracing panel that `dwOffersSketchRectify` refuses to offer on any FILE field a prototype declares.
 *
 * ── THE OTHER CLIENT REACHED THIS ANSWER FIRST, AND SAYS SO TWICE ─────────────────────────────
 *
 * `UploadTabPanel.tsx:391-407` — *"NO SHARED PICKER ON THIS HALF, AND THAT IS AN ANSWER RATHER THAN
 * AN OMISSION. The Sketches section puts one `SharedPhotoField` above both of its panels because two
 * of them read the same photograph … Nothing on this half has that shape."* And
 * `UploadTabHost.tsx:1499-1530` carries the consequence at the mount. Requirement 20 is about a
 * designer moving between a laptop and a handset without relearning the screen; the two clients
 * disagreeing about whether the Prototypes half has a shared photograph card would be exactly the
 * kind of disagreement it names, and this is the side that had it wrong for one commit.
 */
internal fun dwSharesOnePhotograph(offersPlate: Boolean, offersMeasure: Boolean): Boolean =
    dwDerivationCardCount(offersPlate, offersMeasure) > 1

/* ────────────────────────────────────────────────────────────────────────────
 * The section: one photograph, then the cards that derive from it
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The shared photograph and every derivation card that is offered on this record, under one choice.
 *
 * ── THIS IS THE HANDSET'S `UploadTabHost`, AND THE SHAPE IS DELIBERATELY THE SAME ─────────────
 *
 * `UploadTabHost.tsx` owns one pick, `SharedPhotoField` shows it, and `SketchTraceField` and
 * `MeasureFromPhotoCard` are both mounted under it and both told what it is. This is that, with the
 * one substitution the file header argues: the owned value is a photograph the record already holds
 * rather than an unfiled `File`.
 *
 * ── WHICH CARDS APPEAR IS THE REGISTRY'S ANSWER AND NOT THIS FUNCTION'S ───────────────────────
 *
 * Every offer goes through the same two predicates the stage form uses — [dwOffersSketchRectify] /
 * [dwOffersSketchTrace] on a FILE field that looks like the home of a plate, [dwOffersPhotoMeasure]
 * on an image field of an entity that records a length. **Nothing here decides anything a second
 * time**, which is the rule `dwOffersSketchTrace`'s own KDoc states for the trace/rectify pair: a
 * second copy of "which fields are safe" would eventually disagree with the first. On the bundled
 * registry that means a sketch gets all three cards and a prototype gets the measuring card only —
 * its two FILE fields are "Measurement sheet" and "3D model", neither of which is a plate's home —
 * and that falls out of the predicates rather than being written here.
 *
 * ── AND THE SHARED PHOTOGRAPH CARD IS NOT DRAWN ON EVERY RECORD THAT REACHES THIS — req 7 ─────
 *
 * [dwSharesOnePhotograph] decides it, and the whole argument is at that function. In one line: a
 * shared photograph card answers *which photograph are the cards below all working from*, and a
 * record with ONE card has no such question — there the card would be a second chooser over the one
 * the measuring card already owns, which is the duplication being removed rather than a fix for it.
 *
 * On the bundled registry that means the Sketches half of the Upload tab gets the shared card over
 * three panels and **the Prototypes half gets none**, which is what the other client does and says
 * at `UploadTabPanel.tsx:391-407`. Nothing else about the two halves differs: the same section, the
 * same predicates, the same measuring card, the same words.
 *
 * ── ONE DERIVATION OF "WHICH PHOTOGRAPHS", WHICH THE STAGE FORM STILL HAS TWO OF ──────────────
 *
 * [sources] is built once by the caller and handed to every card. That is the property
 * `FieldRenderer.kt:1898-1919` argues for and then only half keeps: there, the tracing and
 * straightening panels share one `sketchDerivationSources` list while the measuring card sixty lines
 * above reads its own field's ids instead. On this surface there is one list and one chooser over it.
 *
 * **THIS IS THE HALF OF REQUIREMENT 5 THAT DOES REACH PROTOTYPES, AND IT IS NOT A SMALL ONE.** A
 * prototype declares two image lists — `prototypePhotos` and `turntablePhotos` — and
 * `dwOffersPhotoMeasure` answers true for both, so the stage form mounts TWO measuring cards on one
 * prototype, each able to see only its own field's photographs. A designer who shot the ruler frame
 * into the turn and the plain frames into the photographs then has the dimension they want on one
 * card and the picture they want on the other. Here [sources] spans both fields, one card reads all
 * of it, and the second card is gone — one upload, one chooser, whichever field the frame landed in.
 */
@Composable
internal fun DwSketchDerivationSection(
    /** Every photograph on this record that a derivation card may read. Built ONCE by the caller. */
    sources: List<DwSketchSource>,
    /**
     * The registry labels of every image field this entity declares, in registry order — WHERE a
     * photograph would go, as opposed to [sources], which is what is there now.
     *
     * ── THE ONE STATE [sources] CANNOT DESCRIBE, WHICH IS THE STATE THAT NEEDS DESCRIBING ─────
     *
     * `DwSketchSource` carries its own `fieldLabel`, so on a record that HAS photographs this list is
     * derivable from that one and would be a second description of it. It is not derivable in the
     * only branch that reads it: with no photograph attached, `sources` is empty and carries no label
     * at all, and "attach one in “Prototype photographs” or “360° capture”" is exactly what that
     * screen owes a designer. So this is the FIELDS and that is the PHOTOGRAPHS, and the two are only
     * ever read in states where the other has nothing to say.
     *
     * It is the other client's `photoFieldLabels`, built the same way from the same predicate —
     * `imageFieldsOf(entity)` there, [dwSketchSourceFields] here — and passed for the same reason.
     */
    photoFieldLabels: List<String>,
    /** What the row is called on screen, or null where the caller has no name for it. */
    rowName: String?,
    /** The FILE field a plate or line art would be attached to, or null where the entity has none. */
    plateField: FieldDto?,
    /** What that FILE field holds now, so the cards can say what attaching would replace. */
    plateFileName: String?,
    /** The length fields a measurement may be proposed into. Empty where the entity records none. */
    targets: List<DwMeasureTarget>,
    /** The row's own values — what a field already holds, before a proposal replaces it. */
    rowValues: Map<String, JsonElement>,
    /** The record's own `category`, for seeding the trace subject. Null where there is none. */
    recordCategory: String?,
    media: DwMediaBridge,
    runtime: DwTraceRuntime,
    enabled: Boolean,
    onAttachedToPlate: (String) -> Unit,
    onPropose: (String, JsonElement?, String?) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit,
    /** Where a host wires `DwSketchTraceExportCard`, if it can. See [DwTraceExportSlot]. */
    exportCard: (@Composable (DwTraceExportSlot) -> Unit)? = null,
) {
    // Nothing to derive from and nothing to derive into: the section is not drawn, and the capture
    // card above it is the whole of what this record can do today. An empty section with a heading
    // would be a place a designer looks for a feature that is not offered on this entity at all.
    if (plateField == null && targets.isEmpty()) return

    /*
      THE OWNER IS REMEMBERED UNCONDITIONALLY AND USED CONDITIONALLY, WHICH IS DELIBERATE.

      `shares` comes off the registry and cannot change while one record is on screen, so a
      `rememberSaveable` inside the branch would in practice be safe — but "in practice" is the word
      that makes a Compose slot bug: a remember that appears and disappears with a condition loses
      its value on every flip, and the value here is the one thing in this section that survives
      process death. It costs two strings on a record that never reads it. See
      [rememberDwSketchDerivationPhoto].
    */
    val choice = rememberDwSketchDerivationPhoto()

    /*
      ── IS THERE ANYTHING HERE TO SHARE? — requirement 7's "where applicable", decided once ─────

      The whole argument is at [dwSharesOnePhotograph]; this is the one place it is asked, so the
      shared card and the three `supply` values below cannot come to different answers. That pairing
      is the load-bearing half: a shared card drawn WITHOUT a hosted supply would be a chooser the
      cards ignore, and a hosted supply WITHOUT the card would have three panels each saying "chosen
      in “Photograph of the sketch” above" over a screen with no such card on it. Both panels' hosted
      sentences name that card BY ITS CONSTANT, so neither can be true unless this pairing holds.
    */
    val shares = dwSharesOnePhotograph(
        offersPlate = plateField != null,
        offersMeasure = targets.isNotEmpty(),
    )

    /*
      ── AND WHAT AN EMPTY RECORD IS TOLD, OFF THE SAME TWO OFFERS — requirement 7 ───────────────

      THE SAME PAIR OF BOOLEANS AS `shares`, READ IN THE SAME PLACE, AND THAT IS THE POINT. This
      sentence names the acts a photograph could be put to on THIS record, so it has to be built where
      the offers are known and nowhere else. On a prototype `plateField` is null — `dwOffersSketchRectify`
      refuses "Measurement sheet" and "3D model", neither being the home of a plate — and the sentence
      says "nothing to measure against" rather than naming a tracing panel and a straightening panel
      that half of the tab does not mount. The whole argument is at [dwNoPhotographSentence].

      BUILT UNCONDITIONALLY, LIKE `choice` ABOVE, and for a plainer reason: it is a string over two
      booleans and a short list, and hoisting it out of both branches that need it is what keeps the
      shared card's guard and this section's empty branch from ever printing two different accounts of
      one record.
    */
    val nothingToWorkFrom = dwNoPhotographSentence(
        offersPlate = plateField != null,
        offersMeasure = targets.isNotEmpty(),
        photoFieldLabels = photoFieldLabels,
    )

    /*
      WHAT EACH CARD IS TOLD ABOUT WHERE ITS PHOTOGRAPH COMES FROM, WORKED OUT ONCE.

      `OwnChoice` is not a degraded mode and nothing is lost in it — it is what every one of these
      cards did before this section existed and still does at the stage form's mount. On a record with
      one card it is the RIGHT answer: that card's own chooser is already a chooser over `sources`,
      which is the same list this section would be choosing from, so hosting it would add a second
      control and no information.
    */
    val supply = if (shares) {
        DwSketchPhotographSupply.Hosted(choice.chosen(sources))
    } else {
        DwSketchPhotographSupply.OwnChoice
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (sources.isEmpty()) {
            /*
              ── A RECORD WITH NO PHOTOGRAPH SAYS SO, RATHER THAN SHOWING NOTHING ────────────────

              EVERY CARD BELOW RETURNS EARLY WITH NO PHOTOGRAPHS — `DwSketchTracePanel` on
              `sources.isEmpty()`, `DwPhotoMeasurePanel` on `photos.isEmpty()` — which is right on a
              stage form, where the field they sit on is on screen with its own capture card and its
              own emptiness is self-evident. **It is wrong here.** On this tab the cards are the only
              sign the capability exists at all, and a section that renders nothing is
              indistinguishable from a build that does not have the feature — which the other client
              records as "precisely how this surface came to be reported as 'completely missing'"
              (`UploadTabHost.tsx:1449-1453`), and which it fixed by rendering the card and letting it
              explain itself.

              ONE SENTENCE, IN ONE PLACE. This is the section's answer and not the shared card's,
              because the shared card is not always drawn ([dwSharesOnePhotograph]) and this state has
              to be said on a prototype too. `DwSketchSharedPhotograph` keeps its own guard for a
              future caller that composes it directly; nothing reaches it from here with an empty
              list, because of this branch.
            */
            Text(
                nothingToWorkFrom,
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            return@Column
        }

        if (shares) {
            DwSketchSharedPhotograph(
                sources = sources,
                choice = choice,
                rowName = rowName,
                // Unreachable with an empty list from here — the branch above returned — and handed
                // over anyway, so the card's own guard cannot grow a second account of this state.
                noPhotographSentence = nothingToWorkFrom,
                enabled = enabled,
            )
        }

        if (plateField != null) {
            DwSketchRectifyPanel(
                field = plateField,
                sources = sources,
                media = media,
                currentFileName = plateFileName,
                enabled = enabled,
                // THE HOST OWNS THE CHOICE, so the panel draws no chooser of its own. `Hosted(null)`
                // and `Hosted(source)` are two different sentences on that panel, which is why the
                // absence is carried as a value rather than as an empty list.
                //
                // A plate field is [dwDerivationCardCount]'s two, so `supply` is always `Hosted` by
                // the time this line is reached — but it is read from the one value rather than
                // written out as `Hosted(...)` again, because a second construction here is a second
                // place for the shared card and the panels to fall out of step.
                supply = supply,
                onAttached = onAttachedToPlate,
                onMessage = onMessage,
                onError = onError,
            )
            DwSketchTracePanel(
                field = plateField,
                sources = sources,
                runtime = runtime,
                media = media,
                recordCategory = recordCategory,
                currentFileName = plateFileName,
                enabled = enabled,
                supply = supply,
                onAttached = onAttachedToPlate,
                onMessage = onMessage,
                onError = onError,
                exportCard = exportCard,
            )
        }

        if (targets.isNotEmpty()) {
            DwPhotoMeasurePanel(
                // THE WHOLE LIST, NOT THE ONE CHOSEN. The escape hatch chooses out of it, and a card
                // handed only the shared photograph could not offer one — see the file header for
                // why the two panels genuinely want two pictures some of the time.
                //
                // IT IS ALSO THE WHOLE LIST ON A RECORD WITH NO SHARED CARD, and there it is the
                // whole of what requirement 5 means for prototypes: `sources` spans every image field
                // the entity declares, so one measuring card sees `prototypePhotos` AND
                // `turntablePhotos` where the stage form mounts two cards that each see one. See this
                // function's header.
                photos = sources.map { it.item },
                // ── THE MERGE, SAID OUT LOUD ON THE HALF THAT HAS ONE — requirement 7 ──────────
                //
                // The line above is where a prototype's two image lists become one card's list, and
                // this is the line that lets the card SAY so. `sources` spans every image field the
                // entity declares, so on a prototype "these photographs" is `prototypePhotos` AND
                // `turntablePhotos` together — which is the whole gain on that half and is invisible
                // in a row of file names. The card prints the field names only where there is more
                // than one of them; see [dwMeasureSpansFieldsClause] for why one is not a merge.
                photoFieldLabels = photoFieldLabels,
                targets = targets,
                rowValues = rowValues,
                enabled = enabled,
                // `Hosted` on a sketch, `OwnChoice` on a prototype — the one decision above, not a
                // second reading of it. `OwnChoice` is what this card does at the stage form's mount
                // and at `RecordMeasureField`'s, so the prototypes half of this tab is running the
                // card's oldest and most-used path rather than a special case written for it.
                supply = supply,
                onPropose = onPropose,
            )
        }
    }
}
