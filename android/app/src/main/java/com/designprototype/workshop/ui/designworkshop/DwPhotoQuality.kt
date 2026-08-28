package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.designprototype.workshop.data.DwImageQuality
import com.designprototype.workshop.data.ImageMeasurement
import com.designprototype.workshop.data.QualityFinding
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Check this photograph" — the visible half of [DwImageQuality], at the moment it is worth anything.
 *
 * ── WHY IT IS HERE AND NOT ANYWHERE ELSE IN THE APP ───────────────────────────────────────────
 *
 * A blurred, tiny or duplicated photograph is the one loss in this application that cannot be undone.
 * Everything else a designer gets wrong can be corrected from a desk a fortnight later; a photograph
 * cannot, because by then the cluster is two hundred kilometres away and the object has been sold.
 * The only moment the finding is worth anything is while the designer is still standing in front of
 * the thing and can simply take the picture again — which is the instant it lands in this card. The
 * web has had this since imageQuality.ts was written. The handset, which is where the photographs are
 * actually taken, did not.
 *
 * ── NOTHING HERE CAN REFUSE ANYTHING, AND THAT IS STRUCTURAL ──────────────────────────────────
 *
 * By the time this composable runs, [DwMediaBridge.attach] has already copied the bytes into the
 * workshop's media directory, hashed them and written the descriptor into the draft. A finding is a
 * sentence added to the screen afterwards. There is no path from anything below to a delete, a
 * replace or a cancel, and there must never be one: a designer may have deliberately photographed a
 * loom in motion, or may be holding the only photograph that will ever exist of an object about to
 * leave the village. Blocking that is a worse failure than every problem this can detect, together.
 *
 * ── AND SINCE 2026-08-28 SOMETHING UPSTREAM CAN ──────────────────────────────────────────────
 *
 * [DwPhotoGate] refuses a blurred, under-resolution or byte-identical photograph BEFORE the import
 * copies anything, on an owner's instruction. That does not change a line of this file, but it
 * changes what this file has left to say, so it is worth knowing which photographs still reach it:
 *
 *  * the NEAR-DUPLICATE the gate admits deliberately — which, in practice, is now most of what this
 *    card reports;
 *  * anything that never passed the gate at all: imported by an older build, attached in the
 *    browser and synced down, or written by a panel that derives its own file (a rectified sketch,
 *    a traced export).
 *
 * For every one of those the card's "keep it" advice is not a hedge, it is the only correct answer
 * — the photograph is here, it is referenced, and there is nothing to do but decide. The paragraph
 * above therefore still holds in full for everything this composable will ever be shown.
 *
 * ── BIAS TOWARD SILENCE ───────────────────────────────────────────────────────────────────────
 *
 * A designer wrongly warned twice stops reading warnings, and then the real one goes past unread too.
 * So: the thresholds are set hard against the failing population rather than in the middle of the gap
 * (see [DwImageQuality.BLUR_VARIANCE_FLOOR]); a photograph too flat for the blur measure to judge
 * produces nothing rather than a guess; a file this device cannot decode produces nothing rather than
 * an error; and every card here can be dismissed with one tap and stays dismissed.
 */

/** One photograph's worth of advice, keyed by the media id so a dismissal survives recomposition. */
private data class DwPhotoFindings(
    val mediaId: String,
    val displayName: String,
    val findings: List<QualityFinding>,
)

/**
 * Measure every photograph in [ids] and show what is worth saying about it.
 *
 * @param ids the field's attachments, in the order they were attached — which is also the order the
 *   duplicate check uses. Each photograph is compared only against the ones attached BEFORE it, so
 *   attaching a second copy flags the second one; it never retroactively accuses the first.
 */
@Composable
internal fun DwPhotoQualityAdvisories(
    ids: List<String>,
    media: DwMediaBridge,
    modifier: Modifier = Modifier,
) {
    /**
     * Measurements by media id — a cache, not a view.
     *
     * Decoding is the expensive half and a file's pixels never change, so re-composing this card
     * (which happens on every keystroke in a sibling field) must not re-decode a 12 MP JPEG. Keyed on
     * the media id rather than the path because the id is what the draft references.
     */
    val measured = remember { mutableStateMapOf<String, ImageMeasurement>() }

    /**
     * Ids this device could not measure, remembered so it does not try again on every recomposition.
     *
     * A HEIC the platform will not decode, a truncated file, a bitmap the GPU refused. Held
     * separately from [measured] because "measured and fine" and "unmeasurable" must not be confused:
     * an unmeasurable photograph contributes no perceptual hash, so it can never be one half of a
     * duplicate claim.
     */
    val unmeasurable = remember { mutableStateMapOf<String, Boolean>() }

    /**
     * Waved away, by media id.
     *
     * Scoped to this card's composition, which means collapsing a collection row and reopening it
     * brings a dismissed warning back. Stated rather than hidden: it is the honest behaviour for a
     * finding that is still true, and the alternative — persisting a dismissal into the draft —
     * would put a UI preference into the document that gets submitted to a ministry.
     */
    var dismissed by remember { mutableStateOf(emptySet<String>()) }

    /**
     * Measure the ones that have not been measured, one at a time, off the main thread.
     *
     * ONE AT A TIME on purpose: two decoded bitmaps must never coexist on a phone that is already the
     * cheapest thing in the room, and publishing after each one means the first photograph's warning
     * appears while the tenth is still decoding. `Dispatchers.Default` rather than `IO` because the
     * cost is dominated by the convolution rather than by the flash read, and because an unbounded IO
     * pool would happily run several of these at once and undo the point above.
     *
     * Keyed on [ids], so it restarts when the designer attaches or removes something — cheap, because
     * everything already measured is skipped. Cancellation on leaving the composition is the whole
     * reason this is a LaunchedEffect: a stage scrolled away mid-decode must not go on burning CPU.
     *
     * IT MEASURES THE PHOTOGRAPHS THAT WERE ALREADY THERE, not just the ones just attached, and that
     * is load-bearing rather than thorough: a perceptual hash exists only for a photograph that has
     * been measured, so without this the near-duplicate check could only ever fire inside a single
     * multi-select. The case it is actually for is the designer who photographs the same pot again the
     * next morning. The cost is bounded and paid once per stage opening — a 12 MP frame measures in
     * roughly 0.1-0.8 s depending on how busy the phone is (the range is measured, and the reasoning
     * for quoting a range rather than a best case is in [DwImageDecode]), so twenty photographs is a
     * few seconds to about fifteen on one background thread. That is why it runs off the main thread
     * and publishes each result as it lands rather than in a batch: the first photograph's warning is
     * on screen long before the twentieth is decoded, and the whole thing stops the moment the stage
     * leaves the composition.
     */
    LaunchedEffect(ids) {
        for (id in ids) {
            if (measured.containsKey(id) || unmeasurable.containsKey(id)) continue
            val item = media.resolve(id) ?: continue
            if (!item.mediaType.equals("IMAGE", ignoreCase = true)) continue
            /*
              THE READING TAKEN AT THE DOOR, WHERE THERE IS ONE — SO THIS DECODES NOTHING TWICE.

              [DwPhotoGate] measured every photograph a designer chose today, before it was imported,
              and [DwScreeningStore] banked the result against the id the import issued. Re-decoding
              here would cost a few hundred milliseconds per photograph on a handset that has just
              spent the same again — twenty-five 12 MP frames measured twice is a real wait for
              nothing. The web's own residual names this exact double decode as a cost it cannot
              avoid, because its capture card has no way to be handed a measurement.

              THE STRONGER REASON IS AGREEMENT, NOT SPEED. Two decodes of one file on one device do
              produce the same numbers — the arithmetic is deterministic — but only while both paths
              stay identical, and one of them subsamples from a content stream while the other reads
              a file. Consulting the reading the gate acted on means the sentence under the
              photograph can never disagree with the decision that let it in.

              A MISS SIMPLY MEASURES, which is what always happened: a photograph from an older
              build, one attached in the browser, or one whose import lost its position mapping (see
              [DwScreeningStore.bankMeasurements]) has nothing banked and takes the path below.
            */
            val banked = media.screening.measurementFor(id)
            if (banked != null) {
                measured[id] = banked
                continue
            }
            val measurement = withContext(Dispatchers.Default) { DwImageDecode.measure(item.absolutePath) }
            if (measurement == null) unmeasurable[id] = true else measured[id] = measurement
        }
    }

    /**
     * What to say, recomputed from the measurements as they land.
     *
     * Cheap enough to do in composition: a handful of string comparisons and a 16-character Hamming
     * distance per photograph. The expensive part already happened above and is cached.
     */
    val entries = buildList {
        val prior = mutableListOf<AttachedImage>()
        for (id in ids) {
            val item = media.resolve(id) ?: continue
            if (!item.mediaType.equals("IMAGE", ignoreCase = true)) continue
            val measurement = measured[id]
            if (measurement == null) {
                // Not measured yet, or not measurable. It still counts as something a LATER photograph
                // can be an exact duplicate of — the checksum is on the descriptor and needs no pixels
                // — but it offers no perceptual hash, so it can never produce a "same shot" claim from
                // a measurement that does not exist.
                prior += AttachedImage(label = item.displayName, checksum = item.sha256)
                continue
            }
            val findings = DwImageQuality.findQualityIssues(
                measurement = measurement,
                checksum = item.sha256,
                attached = prior.toList(),
            )
            prior += AttachedImage(
                label = item.displayName,
                checksum = item.sha256,
                perceptualHash = measurement.perceptualHash,
            )
            if (findings.isNotEmpty()) add(DwPhotoFindings(id, item.displayName, findings))
        }
    }

    val visible = entries.filterNot { it.mediaId in dismissed }
    if (visible.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        visible.forEach { entry ->
            DwPhotoQualityCard(
                entry = entry,
                onDismiss = { dismissed = dismissed + entry.mediaId },
            )
        }
    }
}

@Composable
private fun DwPhotoQualityCard(entry: DwPhotoFindings, onDismiss: () -> Unit) {
    Card(
        // Polite rather than assertive: TalkBack should mention this when it reaches a natural pause,
        // not interrupt the designer mid-sentence in the caption box next to it.
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.field.warningContainer),
        border = BorderStroke(1.dp, MaterialTheme.field.warning),
    ) {
        Column(
            modifier = Modifier.padding(start = 10.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.WarningAmber,
                    // Decorative. The heading below carries the meaning, so the warning survives
                    // greyscale, colour-blindness and a screen reader that never sees the icon.
                    contentDescription = null,
                    tint = MaterialTheme.field.onWarningContainer,
                    modifier = Modifier.size(18.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Check this photograph — ${entry.displayName}",
                        color = MaterialTheme.field.onWarningContainer,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    entry.findings.forEach { finding ->
                        Text(
                            finding.message,
                            color = MaterialTheme.field.onWarningContainer,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    // The 48dp floor this app applies wherever a control was thought about — see
                    // ISLAND_TOUCH_TARGET in ui/AppNavigation.kt. A dismiss the designer has to aim at
                    // is a warning they will learn to resent.
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Dismiss", color = MaterialTheme.field.onWarningContainer, fontSize = 12.sp)
                }
            }
            Text(
                // Said outright, every time. A warning a designer cannot tell the difference between
                // "advice" and "refusal" about is a warning they will stop attaching photographs over.
                "This photograph is already saved on this device. Nothing here removes it.",
                color = MaterialTheme.field.onWarningContainer,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * "2 of 3 views captured — no back view."
 *
 * Drawn at the TOP of the entity, outside the "More detail" disclosure, even though the three view
 * slots themselves live inside it. The whole value of this hint is being seen while the product is
 * still on the table; behind a collapsed disclosure it would be read only by somebody who had already
 * gone looking, which is the person who does not need it.
 *
 * Not dismissible, and drawn as a line of prose rather than on a warning fill: unlike a blur finding
 * it states a fact about the form that stops being true the moment the designer acts on it, so it
 * removes itself and never needs waving away. The amber icon is decoration; the sentence carries it.
 */
@Composable
internal fun DwMissingViewsNote(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.field.warning,
            modifier = Modifier.size(16.dp),
        )
        Text(message, color = MaterialTheme.field.body, fontSize = 12.sp, lineHeight = 17.sp)
    }
}
