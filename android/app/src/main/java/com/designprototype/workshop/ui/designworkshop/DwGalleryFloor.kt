package com.designprototype.workshop.ui.designworkshop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwPhotoGate
import com.designprototype.workshop.ui.LocalAppPreferences
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

/**
 * **"18 OF 25"** — how full a gallery with a declared floor is, said three ways at once, plus the
 * standing sentence that explains what the number is for.
 *
 * Drawn only where the registry declares a [com.designprototype.workshop.data.FieldDto.minItems],
 * which today is the two motif galleries and nothing else. Every other gallery gets no bar and makes
 * no demand — the gate applies to every photograph, the FLOOR applies to the two fields that declare
 * one, and the two scopes are deliberately different.
 *
 * ── WHY THREE ────────────────────────────────────────────────────────────────────────────────
 *
 * A filled bar is a picture of a ratio and nothing else. It is unreadable at a glance in greyscale,
 * unreadable to anybody using TalkBack, and it is the half a designer photographing motifs in bright
 * sun outdoors can see least well. The same argument [DwMediaCarousel]'s header makes about a
 * POSITION — "the slide is the ornament, the readout is the state" — applies without alteration to a
 * LENGTH. So the bar carries the shape, the digits carry the number, and the sentence carries
 * everything the number leaves out: what is still being checked, what has not reached the server,
 * and how many are left. Any one of the three could be removed and the field would still be
 * answerable.
 *
 * ── REDUCED MOTION IS HONOURED AT THE SOURCE, NOT PAINTED OVER ───────────────────────────────
 *
 * `LocalAppPreferences.current.reducedMotion` collapses the fill's tween to [snap], read exactly as
 * [DwMediaCarousel] and `DwRankableList` read it. With the animation gone the bar still shows the
 * right length — it simply arrives there without travelling — because the LENGTH is the state and the
 * movement was only ever the ornament. That is non-negotiable 5 of the frontend contract applied to
 * this client: nothing here exists only as motion.
 *
 * The COLOUR does not change at twenty-five either. A fill that turned green would be saying "done"
 * in the one way that is invisible to a reader in greyscale, in a high-contrast theme, or listening;
 * the sentence says it in words instead.
 *
 * ── IT IS A LEVEL, SO IT IS DESCRIBED AND NEVER ANNOUNCED ────────────────────────────────────
 *
 * No live region, deliberately. This number moves on every single attach and every single remove,
 * from the first photograph to the twenty-fifth, and a region would re-read the whole sentence each
 * time — twenty-five interruptions for information the reader can already query. `stateDescription`
 * is what makes that query answer in words rather than in a bare percentage. The one event that MUST
 * interrupt is a refused photograph, and that has its own assertive region in [DwPhotoRefusalNotice].
 */
@Composable
internal fun DwGalleryFloor(
    /** The registry's declared floor. Never a literal — read it through `dwDeclaredMinItems`. */
    floor: Int,
    label: String,
    counts: DwPhotoGate.GalleryCounts,
    modifier: Modifier = Modifier,
) {
    val progress = DwPhotoGate.galleryProgress(counts, floor)
    val reduceMotion = LocalAppPreferences.current.reducedMotion
    val fraction by animateFloatAsState(
        targetValue = progress.percent / 100f,
        animationSpec = if (reduceMotion) snap() else tween(durationMillis = 300),
        label = "galleryFloor",
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            // The bar's own caption, muted: the field already has a label above it and a second
            // heading inside one control's block is how a form grows a second outline.
            Text(label, color = MaterialTheme.field.muted, fontSize = 11.sp)
            // THE DIGITS — the number the designer came to read, so they carry the weight and the
            // foreground colour while everything else on the row is context for them.
            Text(
                progress.readout,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                // The same words the sighted reader gets below, so neither reader is working from a
                // smaller truth than the other. Without it TalkBack announces a bare percentage and
                // nothing about what is still being checked or what has not left the handset.
                .semantics { stateDescription = progress.words },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.field.surface100,
        )
        Text(
            progress.words,
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Text(
            // THE STANDING SENTENCE, present from first paint and not only once the count moves.
            // It is what stops a designer meeting the requirement at the twentieth photograph, and
            // it is the only place that says a short gallery still saves — which is the claim that
            // keeps the floor from reading as a threat to the work already done.
            DwPhotoGate.galleryFloorSentence(floor = floor, label = label),
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}
