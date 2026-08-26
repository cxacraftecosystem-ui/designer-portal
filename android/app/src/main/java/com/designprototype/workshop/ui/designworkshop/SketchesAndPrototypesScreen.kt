package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.isConnectionFailure
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException

/**
 * Sketches and prototypes, reached without a workshop already open — the handset's chooser.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS ADDS, AND WHAT WAS ALREADY HERE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The owner's instruction of 2026-08-25: *"The recently added designer options available on the web
 * application — including Sketches, Prototypes, Designer Profile, and all other recently introduced
 * designer-related options — should also be implemented on the Android application."*
 *
 * For sketches, the gap was NARROWER than it looked and it is worth being exact, because the
 * frontend contract's own note on this row was over-claimed once and sent readers hunting for the
 * wrong thing. The WORK has been on this handset since the sketch wave: `DwSketchRectifyField`
 * straightens a photographed sketch into a plate, `DwSketchPlate` and `DwSketchRectify` carry the
 * geometry, `FieldRenderer.dwOffersSketchRectify` mounts the panel on the two FILE fields that want
 * it, and `ReportFigures` counts the plates into the report. What did not exist was any way to REACH
 * that without first opening a workshop and walking to stage 11 — no destination, no menu row, no
 * card. So this is the CHOOSER and not a second sketch screen.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY IT HANDS OVER TO THE STAGE RATHER THAN OWNING ANY OF THE WORK
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A sketch is a `DwSketch` row under stage 11 and a prototype is a `DwPrototype` row under stage 13.
 * That is where their fields, their plates, their captions and their report figures are filed, and
 * `InlineRecordDialog`'s header already refuses the obvious alternative by name: there must not be
 * "a second, parallel way to add a prototype". A screen here that let a designer add a sketch would
 * be one feature with two stores, and the one it wrote to would be the one the report did not read.
 *
 * So every row on this screen is two buttons that navigate: one to stage 11, one to stage 13.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHERE THE REVIEW HALF NOW LIVES — AND THIS SECTION USED TO SAY IT DID NOT EXIST
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The web's page has two tabs, Upload and Review, and this section carried the honest state of the
 * handset at the time: the REVIEW half — rating a colleague's work qualitatively and quantitatively,
 * ranking it, and the two rounds of it — had no implementation here at all, no DTO for the three
 * `/design-ratings` endpoints and no repository method. It shipped with one sentence on screen saying
 * so, rather than a tab that opened nothing.
 *
 * IT EXISTS NOW, at [NavDestination.DESIGN_REVIEW] and `DesignReviewScreen`, so the sentence has been
 * changed to point at it. It is a DESTINATION OF ITS OWN rather than a second tab here, for the same
 * permission reason the web made it a page: the pool round is read by designers `load_workshop_or_404`
 * turns away, and this screen's whole job is handing a workshop it CAN open to a stage screen gated by
 * exactly that helper. Folding the two together would put one screen behind two different doors.
 *
 * A STALE "this does not exist" NOTE IS NOT A HARMLESS ONE, which is why this is rewritten rather
 * than deleted: a comment that names a missing feature is how a reader comes to look for the wrong
 * gap, and the frontend contract's §16 records the same over-claim about this very pair of screens
 * costing every reader of it exactly that.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THREE ANSWERS ABOUT THE LIST, AND WHY TWO OF THEM USED TO SHARE ONE SENTENCE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Until 2026-08-26 this screen had TWO states where it needed three. A failed load was written as
 * `emptyList()` — under a comment claiming "the sentence names the failure instead", which it did
 * not — and `emptyList()` falls into the `isEmpty()` branch. So a designer standing in a courtyard
 * with no signal, on twelve workshops, read *"You are not on any design workshop yet. Once an
 * administrator adds you to one, its sketch and prototype stages open from here."* and was sent to
 * ask an administrator for the twelve they already had. The real failure went to `onError`, which is
 * the HOST's transient line at the bottom of the scrolling column — underneath the placeholder that
 * had just told them the opposite, and gone four seconds later while the placeholder stayed.
 *
 * That is the silent-emptiness class this repository keeps having to un-ship: a FAILURE drawn as an
 * ordinary empty state. The three answers are now three sentences that cannot be mistaken for each
 * other, and they are the three a reader actually needs told apart:
 *
 *  * STILL ASKING — the spinner, `workshops == null`.
 *  * ANSWERED, AND THE ANSWER IS NONE — the ordinary state of a newly onboarded designer, and not a
 *    fault. This is the only state that may name an administrator.
 *  * COULD NOT ASK — `listFailure`, worded for the cause, with a "Try again" beside it and a
 *    sentence saying that nothing is lost. It can promise that because this screen is a READ: it
 *    writes nothing, anywhere, so a failed list has cost the designer no work.
 *
 * `DesignReviewScreen`'s `listFailure` is the shape this follows rather than a third one — same
 * `isConnectionFailure` split between "could not be reached" and a refusal in the repository's own
 * words, same "a list that could not be loaded, not a list with nothing in it" distinction.
 */
@Composable
fun SketchesAndPrototypesScreen(
    repository: WorkshopRepository,
    /** Open one workshop at one stage. The stage screen owns everything from there. */
    onOpenStage: (workshopId: String, stageKey: String) -> Unit,
    /**
     * The host's transient message line — AND NOTHING ON THIS SCREEN CALLS IT ANY MORE.
     *
     * This screen is a READ that never writes, so it has exactly one failure to report: the list
     * request. That failure is now rendered IN PLACE, where the list would have been, above the
     * "Try again" it needs — because the host's line slides away after four seconds while a wrong
     * placeholder underneath it stays, which is precisely how the defect above was invisible.
     * `DwProvenanceScreen` is the same shape and MainActivity's arm for it records the resolution in
     * its own words: it "took an `onError` it never called — a channel that looks live to whoever
     * wires up the next failure path and goes nowhere — so it does not take one now."
     *
     * THE ARM AT THE CALL SITE SHOULD GO THE SAME WAY. It is left in place here only because
     * `MainActivity.kt` is not this change's to edit; nothing routes on it (the siblings take what
     * they use), so dropping `onError = { showMessage(it) }` from the `Screen.SketchesAndPrototypes`
     * arm and this parameter with it is a two-line change for whoever is next in that file. Until
     * then: do not wire a new failure path through here without first asking whether the failure
     * belongs beside the control that caused it, which on this screen it always has.
     */
    @Suppress("UNUSED_PARAMETER") onError: (String) -> Unit,
) {
    var workshops by remember { mutableStateOf<List<DesignWorkshopDto>?>(null) }
    var truncated by remember { mutableStateOf(0) }
    /**
     * Why the list is not here, in words — or null when it IS here.
     *
     * THE WHOLE POINT OF A SEPARATE STATE. `emptyList()` cannot carry this: "I asked and you are on
     * none" and "I could not ask" are different facts that need different sentences and different
     * next moves, and collapsing them into one list value is what drew a failed load as "you have
     * none". Same name and same shape as `DesignReviewScreen`'s, so the two sibling screens have one
     * idiom between them rather than two.
     */
    var listFailure by remember { mutableStateOf<String?>(null) }
    /** Bumped by "Try again", which is the only thing that re-runs the load. */
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        /*
          EVERY ATTEMPT STARTS FROM THE LOADING STATE, and this is not tidying. A retry that fails
          identically would otherwise mutate NOTHING on screen — same sentence, same button — so the
          designer who pressed it, and in particular the reader who cannot see the button, gets the
          "nothing happened" that the button exists to end. Clearing these three sends the status
          region through the spinner and back, which is a change assistive technology announces.
        */
        workshops = null
        listFailure = null
        truncated = 0
        runCatching { repository.designWorkshops(page = 1, pageSize = PAGE_SIZE) }
            .onSuccess { page ->
                workshops = page.items
                // RULE 10: EVERY CAP SAYS SO. A designer with forty workshops must not read a list of
                // twenty as "these are my workshops" — an absence that reads as non-existence is the
                // single most repeated defect class in this repository.
                truncated = (page.total - page.items.size).coerceAtLeast(0)
            }
            .onFailure { error ->
                /*
                  LEAVING THE SCREEN IS NOT A FAILURE, AND NEITHER IS PRESSING "Try again".
                  `runCatching` catches `Throwable`, so both of those arrive here as a
                  `CancellationException`: the designer who walks on before the list lands, and — now
                  that this effect is keyed on `attempt` rather than on `Unit` — the run this retry
                  replaced, cancelled in place while the screen stays exactly where it is.

                  Handled, that second one is worse than the first: the cancelled run would write its
                  `listFailure` after the replacement run had already cleared it, so the sentence
                  "could not be listed" would sit over a load that is at that moment in flight, and
                  no further attempt would clear it because the spinner it belongs to has already
                  been and gone. Rethrown, as `loadMyActivity`'s caller in MainActivity and
                  `dwReadQrPicture` both do — which is also what stops a dead composable writing
                  `workshops`.
                */
                if (error is CancellationException) throw error
                // `emptyList()` and NOT left null, exactly as `DesignReviewScreen` does it: null is
                // "still asking" below, and a failed load that stayed null would spin for ever. What
                // stops that empty list being READ as "you are on none" is `listFailure` and the
                // branch order in the status region — not this value.
                workshops = emptyList()
                listFailure = if (repository.isConnectionFailure(error)) {
                    /*
                      THE OFFLINE HALF OF THE SPLIT, in the sibling screen's construction. A refusal
                      wearing an offline sentence sends a designer to look for a bar of signal they
                      already have, and an outage wearing a refusal sends them to an administrator —
                      which is the errand the old empty-state placeholder sent them on.
                    */
                    DW_SKETCH_CHOOSER_OFFLINE
                } else {
                    // The repository's own words where it gave any, because a 403 and a 500 need
                    // different things from the reader and only the server knows which this was.
                    error.apiErrorMessage(DW_SKETCH_CHOOSER_REFUSED)
                }
            }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Sketches & prototypes",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )
        Text(
            "Sketch and prototype work is filed against the workshop it belongs to — stage 11 for " +
                "sketches, stage 13 for prototypes — so pick a workshop to open it there. Plates " +
                "straightened from a photograph, captions and report figures all live on those stages.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        // Said once, plainly, and pointing at the destination that now holds it. This line used to
        // read "on the web only for now", which stopped being true the day Design review landed — and
        // a screen telling a designer a feature is absent while it sits two rows up the same menu is
        // worse than saying nothing. See the class KDoc.
        Text(
            "Rating and ranking a colleague's sketches and prototypes is Design review, in the menu.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp
        )

        /*
          ══════════════════════════════════════════════════════════════════════════════════════════
          THE STATUS OF THE LIST — ONE NODE, THREE SENTENCES, AND A LIVE REGION AROUND IT
          ══════════════════════════════════════════════════════════════════════════════════════════

          COMPOSED WHETHER OR NOT THERE IS ANYTHING IN IT. Assistive technology announces a CHANGE
          inside a region that ALREADY EXISTED; a region created in the same frame as its first
          sentence is a region whose first sentence is never announced. So this Box is always here
          and only its contents swap — the same construction `DwRankableList` uses for its move
          announcements and `DwDocumentPreview` uses for its Open refusal, and the same rule the web's
          `aria-live` paragraph follows. Do not "simplify" it into the branches themselves.

          `mergeDescendants` IS WHAT MAKES IT WORK: a live region announces a change to its OWN
          semantics, and this node has no text of its own — the sentence is always in a child. Merged,
          the child's text IS this node's text, so replacing it is the change that gets announced.

          THE THREE BRANCHES ARE ORDERED, AND THE ORDER IS THE FIX. `listFailure` is tested FIRST, so
          "answered, and the answer is none" can no longer draw over a load that never answered —
          which is the defect this section was rewritten for. Spinner second, so a failure cannot be
          hidden behind one either.

          THE WORKSHOP ROWS ARE DELIBERATELY NOT IN HERE. Twenty rows inside a polite live region are
          twenty rows read out on arrival, and `mergeDescendants` over a row would flatten its two
          navigation buttons into the paragraph. This region carries the STATUS of the list; the list
          itself is below.
        */
        Box(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            }
        ) {
            val failure = listFailure
            val list = workshops
            when {
                // COULD NOT ASK. Two sentences and never one: the first says what happened, the
                // second says what it costs — which is nothing, and a reader who is not told that
                // has to assume the worst about a screen full of their own work.
                failure != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        failure,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Text(
                        DW_SKETCH_CHOOSER_NOTHING_LOST,
                        color = MaterialTheme.field.muted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }

                // STILL ASKING.
                list == null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text("Loading your workshops…", color = MaterialTheme.field.muted, fontSize = 13.sp)
                }

                // ANSWERED, AND THE ANSWER IS NONE — the ordinary state of a newly onboarded designer
                // rather than a fault, and worded as such. The same distinction the web's own page
                // draws. THE ONLY STATE THAT MAY NAME AN ADMINISTRATOR: sending a designer on that
                // errand about a request that failed is the whole of the defect above.
                list.isEmpty() -> Text(
                    DW_SKETCH_CHOOSER_NO_WORKSHOPS,
                    color = MaterialTheme.field.muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                // A list arrived. It is drawn below, outside this region.
                else -> Unit
            }
        }

        // OUTSIDE the region above, not inside it: a merging live region is the wrong parent for a
        // control, and the sentence is what has to be announced rather than the button.
        if (listFailure != null) {
            OutlinedButton(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
        }

        // THE ROWS, GATED ON THERE BEING NO FAILURE. `onFailure` writes `emptyList()`, so today this
        // draws nothing in that state anyway — it is stated as a condition rather than left to that
        // coincidence, because the next person to give a failed load a partial list would otherwise
        // leave rows standing under a sentence saying the list could not be loaded.
        if (listFailure == null) {
            workshops?.forEach { workshop ->
                WorkshopRow(workshop = workshop, onOpenStage = onOpenStage)
            }
            if (truncated > 0) {
                Text(
                    "$truncated more workshop${if (truncated == 1) "" else "s"} not shown. " +
                        "Open Design workshops to search the whole list.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/*
  ══════════════════════════════════════════════════════════════════════════════════════════════════
  THE THREE ANSWERS, AS THREE NAMED SENTENCES
  ══════════════════════════════════════════════════════════════════════════════════════════════════

  Named, and `internal`, for the same reason `DW_PROVENANCE_LOAD_FAILED` and its siblings are: the
  defect these exist to prevent is a WORDING one, and a wording defect cannot be caught by a test that
  cannot see the wording. `DwSketchChooserSentenceTest` asserts the property that failed here — only
  the answered-and-none sentence may send a designer to an administrator, and no sentence about a
  request that failed may read as an answer about their workshops.

  They are also three sentences that must stay three. Collapsing any two of them is how this screen
  came to greet a designer with twelve workshops, standing in a courtyard, by telling them they had
  none.
*/

/**
 * ANSWERED, AND THE ANSWER IS NONE. The ordinary state of a newly onboarded designer.
 *
 * THE ONLY SENTENCE ON THIS SCREEN THAT MAY NAME AN ADMINISTRATOR, because it is the only one that
 * knows the answer: the repository was asked, it replied, and the reply was an empty page. Reached by
 * any other route it is an errand invented out of a failed request.
 */
internal const val DW_SKETCH_CHOOSER_NO_WORKSHOPS: String =
    "You are not on any design workshop yet. Once an administrator adds you to one, its sketch and " +
        "prototype stages open from here."

/**
 * COULD NOT ASK, with no signal — `isConnectionFailure`'s half of the split.
 *
 * The second clause is the whole point and is deliberately the sibling screen's, near enough word for
 * word: a designer has to be told which of the two questions went unanswered, because "you are on no
 * workshops" and "I could not find out" call for completely different next moves.
 */
internal const val DW_SKETCH_CHOOSER_OFFLINE: String =
    "No connection, so your design workshops could not be listed — a list that could not be asked " +
        "for, not a list with nothing in it."

/** COULD NOT ASK, and the repository said why. The fallback for when it did not. */
internal const val DW_SKETCH_CHOOSER_REFUSED: String =
    "The repository could not list your design workshops."

/**
 * WHAT A FAILED LIST COSTS, which is nothing, said under both failure sentences.
 *
 * RULE 10 CUTS BOTH WAYS: a screen must say when it is showing less than the truth, and it must say
 * when a failure has taken nothing from the reader. Silence on the second point is read as the worst
 * case by anyone whose fortnight of fieldwork is in the app — and here the truth is unusually clean,
 * because this chooser only ever READS.
 *
 * The route it names is true offline: `WorkshopListScreen` falls back to the drafts on this handset
 * with "Showing what is stored on this device", and the two stages open from a workshop opened from
 * there — which is how sketch work was reached before this screen existed at all.
 */
internal const val DW_SKETCH_CHOOSER_NOTHING_LOST: String =
    "Nothing is lost: this screen only reads, so nothing on this handset has changed. Design " +
        "workshops still lists what is stored on this device, and the sketch and prototype stages " +
        "open from a workshop opened there."

/**
 * How many workshops this screen asks for.
 *
 * The same 20 the list screen's first page uses. Deliberately NOT the server's ceiling: a longer list
 * on a chooser is a longer scroll to the same two buttons, and the row below the list names what was
 * left out and where to search for it.
 */
private const val PAGE_SIZE = 20

/** The two stages sketch and prototype work is filed under. Registry keys, never numbers. */
private const val SKETCH_STAGE = "SKETCH_DEVELOPMENT"
private const val PROTOTYPE_STAGE = "PROTOTYPE_DEVELOPMENT"

@Composable
private fun WorkshopRow(
    workshop: DesignWorkshopDto,
    onOpenStage: (String, String) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            workshop.title.ifBlank { "Untitled workshop" },
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        // The three facts that tell two workshops apart on a phone: what craft, where, and when. Any
        // of them may be blank on a workshop whose stage 1 is unfinished, so the line is assembled
        // from what is there rather than printed with empty gaps.
        val subtitle = listOfNotNull(
            workshop.craftName?.takeIf { it.isNotBlank() },
            workshop.clusterName?.takeIf { it.isNotBlank() } ?: workshop.state?.takeIf { it.isNotBlank() },
            workshop.startDate?.take(10)?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = MaterialTheme.field.muted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onOpenStage(workshop.id, SKETCH_STAGE) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Brush, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Sketches", fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(
                onClick = { onOpenStage(workshop.id, PROTOTYPE_STAGE) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Category, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Prototypes", fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
