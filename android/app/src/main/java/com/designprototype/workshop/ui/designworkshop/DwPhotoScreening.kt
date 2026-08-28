package com.designprototype.workshop.ui.designworkshop

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.AttachedImage
import com.designprototype.workshop.data.DwImageDecode
import com.designprototype.workshop.data.DwPhotoGate
import com.designprototype.workshop.data.ImageMeasurement
import com.designprototype.workshop.ui.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **THE DOOR THE GATE STANDS IN** — every photograph a designer chooses waits here, visible and
 * counted, until it has been measured and judged.
 *
 * The judgement itself is [DwPhotoGate]'s and none of it is repeated below; this file is the place
 * the judgement happens and the way a refusal is said.
 *
 * ── WHY THE SCREENING RUNS BEFORE THE IMPORT, WHICH IS THE WHOLE POINT ON A HANDSET ───────────
 *
 * [WorkshopDraftStore.importMedia] copies every byte into the workshop's media directory, hashes it,
 * `fd.sync()`s it and registers a descriptor before it hands back an id — and from that moment the
 * photograph is IN the draft: counted by the field, walked by the sync pass, carried into the report.
 * A check that ran after it would be reporting on a photograph that is already on its way to the
 * server, which is the exact thing the owner's instruction is about, and undoing it would mean
 * writing a descriptor and then deleting it for every photograph a designer chose.
 *
 * So the candidate is measured from the Uri, through [DwImageDecode.screen], while it is still
 * nothing but a file somebody picked. A refused photograph is never copied, gets no descriptor, no
 * id, no row, no thumbnail, and nothing on the server ever hears about it. It is exactly the same
 * placement as the ceiling trim in `DwMediaCaptureCard`'s `adopt`, one step earlier, and for the
 * same reason that comment already gives: trimming after the import "would leave orphaned copies of
 * the photographs it refused — files the UI has no row for and therefore no way to delete".
 *
 * ── WHY THE STATE IS HOISTED OUT OF THE CAPTURE CARD ──────────────────────────────────────────
 *
 * Measuring a 12 MP frame costs a few hundred milliseconds and sometimes approaches a second (see
 * [DwImageDecode]'s measured budget), so twenty-five photographs is a wait a designer will scroll
 * during — and scrolling a stage collapses collection rows and takes the capture card out of the
 * composition with them. A `rememberCoroutineScope` inside the card would be cancelled there, and
 * the photographs would be neither imported nor refused: no row, no sentence, nothing anywhere
 * saying they had been chosen at all. That is a silent loss of work, which is the one outcome this
 * repository refuses everywhere.
 *
 * So the store belongs to the STAGE, exactly as [DwMediaBridge.attach] does and for the identical
 * reason its own KDoc gives about the recorder: the card is gone, the stage is not.
 *
 * ── AND THE REFUSAL OUTLIVES THE CARD TOO ─────────────────────────────────────────────────────
 *
 * A refusal held in the card's own state would vanish with it. Since the store keeps it per slot, a
 * designer who collapsed the row while the check was running finds the sentence waiting when they
 * open it again. It is cleared by the next import into that slot and by nothing else — a receipt
 * that disappears on its own is a receipt nobody read.
 */

/** One photograph offered to the gate: where its bytes are, what to call it, and what to tidy up. */
data class DwScreenCandidate(
    val uri: Uri,
    /**
     * What the refusal calls it.
     *
     * The capture card has this and `dwCapNotice` does not, which is why that notice counts where
     * this one names: the ceiling trims content Uris it has resolved nothing about, whereas the
     * gate is handed a display name for every candidate before a byte moves.
     */
    val displayName: String,
    /**
     * The staging file the camera or recorder wrote, when there is one.
     *
     * Deleted by the store when the gate refuses, for the reason `adopt` deletes it when the ceiling
     * does: a camera capture that will never be imported is a file whose only purpose has just
     * ended, and nothing downstream will ever reference it. A gallery pick has none — those bytes
     * belong to the gallery and this app must not touch them.
     */
    val staging: File? = null,
)

/** A candidate the gate let through, with whatever it still had to say about it. */
data class DwAdmittedPhoto(
    val candidate: DwScreenCandidate,
    /** Non-refusing faults only — in practice a near-duplicate, which is admitted on purpose. */
    val faults: List<DwPhotoGate.GateFault>,
    /** What the measurement was, so nothing downstream has to decode these bytes a second time. */
    val measurement: ImageMeasurement?,
)

/** What one field's slot is doing right now. */
data class DwFieldScreening(
    /** How many candidates are still being measured. Counted on screen, never in the gallery total. */
    val screening: Int = 0,
    /**
     * What was turned away, in the order it was judged.
     *
     * Cleared when an import into this slot begins with nothing already in flight — that receipt
     * describes a pass the designer has finished reading. It ACCUMULATES across passes that overlap,
     * because a refusal from a pass still running is the only record that photograph was ever
     * chosen. See [DwScreeningStore.screen].
     */
    val refused: List<DwPhotoGate.RefusedPhoto> = emptyList(),
) {
    companion object {
        val EMPTY = DwFieldScreening()
    }
}

/**
 * The stage's screening desk: one per stage screen, shared by every media field on it.
 *
 * @param scope the STAGE's coroutine scope, not a card's. See this file's header.
 */
@Stable
class DwScreeningStore(private val scope: CoroutineScope) {

    private val slots = mutableStateMapOf<String, DwFieldScreening>()

    /**
     * Measurements banked by media id, so the advisory card below the rows does not decode the same
     * twenty-five photographs a second time.
     *
     * THE WEB CANNOT DO THIS AND SAYS SO IN ITS OWN RESIDUALS: its capture card measures every file
     * again once it lands, because there is no prop to hand it a measurement. Here the screening and
     * the advisory are both inside one feature, so the reading taken at the door is the reading the
     * card reports — which also means the two can never disagree about one photograph, which is a
     * better property than the saved decode.
     *
     * Keyed by MEDIA ID and filled only where the import's answer lines up with what was screened;
     * see [bankMeasurements]. A miss simply costs a decode, which is what used to happen always.
     */
    private val measuredByMediaId = mutableStateMapOf<String, ImageMeasurement>()

    /**
     * ONE DECODE AT A TIME ACROSS THE WHOLE STAGE.
     *
     * Two decoded bitmaps must never coexist on a phone that is already the cheapest thing in the
     * room, and a stage form can hold half a dozen image fields. The same discipline
     * [DwPhotoQualityAdvisories] applies to its own loop, moved up a level because there is now more
     * than one thing measuring.
     */
    private val decodeLock = Mutex()

    fun stateFor(slot: String): DwFieldScreening = slots[slot] ?: DwFieldScreening.EMPTY

    fun measurementFor(mediaId: String): ImageMeasurement? = measuredByMediaId[mediaId]

    /**
     * Judge [candidates] and hand back the ones that may be imported.
     *
     * @param attached what the field already holds, in attachment order, so an exact duplicate is
     *   refused and the photograph already there is never accused.
     * @param onAdmitted called on the main dispatcher, ONCE, with everything that got through — in
     *   the order it was chosen. Never once per file: the caller writes the field's id list from a
     *   snapshot, so two writes in one frame both start from the same stale snapshot and the second
     *   erases the first. That is the same lost-update `DwMediaBridge.attach` is shaped against.
     *   Not called at all when nothing survives, so a caller need not handle an empty import.
     */
    fun screen(
        slot: String,
        resolver: ContentResolver,
        candidates: List<DwScreenCandidate>,
        attached: List<AttachedImage>,
        onAdmitted: (List<DwAdmittedPhoto>) -> Unit,
    ) {
        if (candidates.isEmpty()) return
        /*
          THE STATE IS ADDITIVE, BECAUSE TWO PASSES CAN OVERLAP ON ONE FIELD.

          A designer picks nine photographs, waits four seconds, and taps the camera again while the
          ninth is still being measured. Written as "this pass owns the slot", the second call would
          reset the count to one — losing eight photographs from the readout while they were still in
          flight — and clear a refusal the first pass had already produced, which is the only record
          that photograph was ever chosen. So the count is a SUM and refusals ACCUMULATE.

          THE ONE THING THAT IS RESET IS A COMPLETED PASS'S RECEIPT. When nothing is in flight, the
          refusals on screen describe an import the designer has finished with, and leaving them
          beside a fresh count is a receipt for the wrong import. When something IS in flight they
          belong to a pass that has not finished being read yet, so they stay.
        */
        val current = stateFor(slot)
        slots[slot] = DwFieldScreening(
            screening = current.screening + candidates.size,
            refused = if (current.screening == 0) emptyList() else current.refused,
        )

        scope.launch {
            val admitted = mutableListOf<DwAdmittedPhoto>()
            // Grows as the pass proceeds, so picking one file twice inside a single selection is
            // caught on the second copy. Starts from what the field already holds.
            val seen = attached.toMutableList()

            for (candidate in candidates) {
                val screened = decodeLock.withLock {
                    withContext(Dispatchers.Default) { DwImageDecode.screen(resolver, candidate.uri) }
                }

                if (screened == null) {
                    /*
                      IT FAILS OPEN, AND THIS IS THE BRANCH THAT PROVES IT.

                      A HEIC the platform will not decode, a truncated file, a permission that expired
                      between the pick and the read, a bitmap the GPU refused — none of those is a bad
                      photograph, and refusing them would make both motif galleries unfillable on a
                      handset whose decoder differs from the one this was calibrated on. It is
                      admitted with no findings, which is exactly what everything downstream already
                      does with an unmeasurable file.
                    */
                    admitted += DwAdmittedPhoto(candidate, emptyList(), null)
                    // It is still an entry a LATER photograph is compared against, with NEITHER
                    // handle: [DwImageDecode.screen] answers null for the whole reading rather than
                    // a partial one, so there is no hash and no perceptual hash here. A null of
                    // either is "unknown" and never "unique", so it produces no duplicate claim in
                    // either direction — which is the same silence the rest of this branch is.
                    seen += AttachedImage(label = candidate.displayName, checksum = null)
                    withContext(Dispatchers.Main) { settle(slot, null) }
                    continue
                }

                val verdict = DwPhotoGate.judge(
                    measurement = screened.measurement,
                    checksum = screened.sha256,
                    attached = seen.toList(),
                )

                var refusal: DwPhotoGate.RefusedPhoto? = null
                if (verdict.admitted) {
                    admitted += DwAdmittedPhoto(
                        candidate = candidate,
                        faults = verdict.faults,
                        measurement = screened.measurement,
                    )
                    seen += AttachedImage(
                        label = candidate.displayName,
                        checksum = screened.sha256,
                        perceptualHash = screened.measurement.perceptualHash,
                    )
                } else {
                    refusal = DwPhotoGate.RefusedPhoto(candidate.displayName, verdict.faults)
                    // A refused camera capture's staging file has no future. A refused GALLERY pick
                    // has no staging file and the gallery's own copy is untouched — this app deletes
                    // nothing it did not create.
                    candidate.staging?.let { file -> runCatching { file.delete() } }
                }

                // PUBLISHED PER PHOTOGRAPH, NOT IN A BATCH AT THE END. On twenty-five 12 MP frames
                // that is up to twenty seconds of measuring, and a designer watching a count that
                // does not move has no way to tell a slow check from a stuck one — the first
                // refusal reaches the screen while the twentieth is still being read.
                withContext(Dispatchers.Main) { settle(slot, refusal) }
            }

            withContext(Dispatchers.Main) {
                if (admitted.isNotEmpty()) onAdmitted(admitted.toList())
            }
        }
    }

    /**
     * One photograph finished being judged: take it off the in-flight count, and add its refusal to
     * the receipt if it has one.
     *
     * APPENDS RATHER THAN REPLACES, so two overlapping passes on one field cannot erase each other's
     * refusals — see the note in [screen] on why the state is additive. The count floors at zero
     * rather than going negative: a decrement that arrives after the slot was reset is a bookkeeping
     * error, and a NEGATIVE in-flight count would print "-1 is being checked" at a designer.
     */
    private fun settle(slot: String, refusal: DwPhotoGate.RefusedPhoto?) {
        val current = stateFor(slot)
        slots[slot] = DwFieldScreening(
            screening = (current.screening - 1).coerceAtLeast(0),
            refused = if (refusal == null) current.refused else current.refused + refusal,
        )
    }

    /**
     * Remember what each newly imported photograph measured — BY POSITION, and only when the
     * positions are beyond doubt.
     *
     * `DwMediaBridge.attach` reports the ids it managed to write and nothing else: an unreadable file
     * out of five is skipped so the other four survive, and the answer is then four ids for five
     * inputs with no mapping between them. Lining those up by index would file one photograph's
     * reading — and, downstream, one photograph's quality flag — against a different photograph's
     * id. That is the defect the web names by hand ("one identity card's digits under another card's
     * photograph"), and it is worse here because the wrong answer is durable.
     *
     * So the mapping is taken only when the counts agree, which is every ordinary import, and a
     * partial one banks nothing. The cost of banking nothing is a decode the advisory card was
     * paying anyway before any of this existed.
     */
    fun bankMeasurements(admitted: List<DwAdmittedPhoto>, mediaIds: List<String>) {
        if (admitted.size != mediaIds.size) return
        admitted.forEachIndexed { index, photo ->
            photo.measurement?.let { measurement -> measuredByMediaId[mediaIds[index]] = measurement }
        }
    }
}

/**
 * WHAT WAS TURNED AWAY, NAMED FILE BY FILE, IN THE ONE PLACE IT WILL EVER BE SAID.
 *
 * ── THIS SENTENCE IS THE ONLY RECORD THERE IS ────────────────────────────────────────────────
 *
 * A refused photograph was never imported: no descriptor, no id, no thumbnail, no row, nothing in
 * the draft and nothing on the server. There is no other artefact anywhere that says it happened.
 * The same argument `dwCapNotice`'s region carries one door later, with more weight, because a
 * ceiling refusal is about surplus and this one is about a photograph the designer meant to keep.
 *
 * ── ASSERTIVE, AND THE REGION EXISTS BEFORE THERE IS ANYTHING IN IT ──────────────────────────
 *
 * Assistive technology announces a CHANGE inside a region that already existed, so a region created
 * in the same breath as its first message is a region whose first message is never announced — and
 * the first message is the only one most imports produce. `mergeDescendants` is what makes it work:
 * a live region announces a change to its OWN semantics and this node has no text of its own, so
 * merged, the children's sentences ARE this node's text. The same idiom, for the same two reasons,
 * as `DwMediaCaptureCard`'s cap notice and `DwRankableList`'s move announcer; do not invent a third.
 *
 * ASSERTIVE and not polite, chosen by meaning: the pause a polite region waits for is a designer
 * walking away believing all twenty-five photographs are attached, and this is a refusal with a
 * remedy in it about work that has just been declined.
 *
 * ── ONE LINE PER FILE ────────────────────────────────────────────────────────────────────────
 *
 * See [DwPhotoGate.refusalLines] for why the handset breaks what the web writes as one paragraph:
 * the file names are the one thing a designer has to pick out, and at 12sp on a 360dp screen a
 * four-file refusal written as prose buries all four of them mid-sentence.
 */
@Composable
internal fun DwPhotoRefusalNotice(refused: List<DwPhotoGate.RefusedPhoto>, modifier: Modifier = Modifier) {
    val lines = DwPhotoGate.refusalLines(refused)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
    ) {
        if (lines.isEmpty()) return@Column
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    // Decorative. The heading carries the meaning, so the refusal survives greyscale,
                    // colour-blindness and a reader that never sees the icon.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        DwPhotoGate.refusalHeading(lines.size),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 18.sp,
                    )
                    lines.forEach { line ->
                        Text(
                            line,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    Text(
                        // Said outright, every time. The gate is narrower than a designer would
                        // otherwise assume from watching it turn photographs away, and somebody who
                        // believes the app is checking exposure will stop checking it themselves.
                        DwPhotoGate.scopeSentence(),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}
