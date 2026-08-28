package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.isConnectionFailure
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.designWorkshopPrefillNote
import com.designprototype.workshop.ui.field
import kotlinx.coroutines.CancellationException

/**
 * Sketches and prototypes, reached without a workshop already open — the handset's own workspace.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS SCREEN IS, AND WHAT IT USED TO BE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * It ASKS WHICH WORKSHOP, ONCE, at the top — and then everything below it is that one workshop's
 * sketch and prototype work, under two tabs: **Upload** and **Review**. That is the web's shape
 * (`frontend/app/(protected)/sketches-and-prototypes/page.tsx` for the chooser,
 * `frontend/components/sketches/SketchesWorkspace.tsx` for the two tabs), and until 2026-08-28 the
 * handset did not have it: this screen listed EVERY workshop with two navigating buttons apiece, so
 * a designer with a drawing in their hand scrolled a list of twelve to press one of twenty-four
 * buttons, and the same screen on the two clients answered two different questions.
 *
 * The owner's instruction of 2026-08-28, which this rewrite is: *"Ensure complete parity between the
 * Android screens and web screens, especially for Sketches and Prototypes … Users must be able to
 * select the relevant workshop from the Sketches and Prototypes screen … The screen must contain two
 * tabs, Upload and Review … Provide an option to add a Sketch or Prototype directly to the selected
 * workshop from this screen. A workshop can contain multiple sketches and multiple prototypes; the
 * implementation and data model must account for that."*
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE ARGUMENT THIS FILE USED TO MAKE AGAINST ADDING A SKETCH, AND WHY IT IS STILL HONOURED
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * **THIS SECTION REPLACES ONE THAT SAID THE OPPOSITE, AND IT IS REWRITTEN RATHER THAN DELETED** for
 * the reason this file's own history keeps demonstrating: a comment that names a missing feature is
 * how the next reader comes to look for the wrong gap. The paragraph that stood here said —
 *
 * > "A screen here that let a designer add a sketch would be one feature with two stores, and the one
 * > it wrote to would be the one the report did not read. So every row on this screen is two buttons
 * > that navigate."
 *
 * — and the FIRST sentence of it is still exactly right. What it forbids is a SECOND STORE, and the
 * capability the owner asked for does not need one. The web had already found the resolution and
 * `docs/SKETCHES-PROTOTYPES-PARITY.md` records it in as many words: *"The web's upload tab does not
 * add one either — `frontend/components/sketches/UploadTabHost.tsx` picks an existing row and writes
 * through the same draft store the stage form uses, so the web is not the thing this comment
 * forbids."*
 *
 * The handset now has the same shape, and the refusal holds because every write goes to the ONE place
 * a sketch has always lived:
 *
 *  * a sketch is a row of the `sketch` collection under `SKETCH_DEVELOPMENT` and a prototype is a row
 *    of `prototype` under `PROTOTYPE_DEVELOPMENT` — the stage keys, from the registry, never numbers;
 *  * the row is written with `WorkshopDraftStore.updateStage`, whose own KDoc reads *"This is what a
 *    stage screen should call"*, into the same `StageDraft.rows` the stage form edits;
 *  * a photograph is copied by `WorkshopDraftStore.importMedia` into the workshop's own media
 *    directory, exactly as `StageScreen`'s bridge does;
 *  * and it reaches the repository through `WorkshopSyncEngine.pushStage`, the one place a stage
 *    becomes a payload.
 *
 * **THERE IS NO SECOND STORE, NO PARALLEL COLLECTION AND NO NEW ENDPOINT.** A sketch added here is
 * byte for byte the row a designer would have created by walking to stage 11, which is why
 * `ReportFigures` finds it. The detail, including why a draft record is only ever seeded after the
 * repository has answered, is at [DwSketchChooserUploadTab].
 *
 * WHAT THE TAB STILL HANDS OVER is everything that is not a file: naming a sketch, its caption, its
 * measurements, straightening a photographed sketch into a plate with `DwSketchRectifyField`, a
 * prototype's materials and its stage log. Those are the stage form's, both tabs say so, and both
 * offer the button.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHICH WORKSHOP THE SCREEN OPENS ON
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The most recently accessed one, and the derivation is stated because no client can see the column
 * that would answer it directly: `DesignWorkshopViewer.createdAt` is not on [DesignWorkshopDto] and
 * no endpoint publishes it per row. `GET /design-workshops/default-for-me` answers instead — the same
 * request `DesignWorkshopPicker.kt` makes for every record form, whose `reason` is `"GRANTED"` (the
 * workshop you were most recently added to) or `"CREATED"` (the one you most recently opened) — and
 * the sentence under the picker is `designWorkshopPrefillNote`'s, so the app never fills a box in
 * without saying why. When that request has no answer, the fallback is the first row of
 * `GET /design-workshops`, which is ordered newest-first: a DIFFERENT question, so it gets a
 * different sentence rather than the server's. See [dwChooserDefaultWorkshop].
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THREE ANSWERS ABOUT THE LIST, AND WHY TWO OF THEM USED TO SHARE ONE SENTENCE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Until 2026-08-26 this screen had TWO states where it needed three. A failed load was written as
 * `emptyList()` — under a comment claiming "the sentence names the failure instead", which it did
 * not — and `emptyList()` falls into the `isEmpty()` branch. So a designer standing in a courtyard
 * with no signal, on twelve workshops, read *"You are not on any design workshop yet. Once an
 * administrator adds you to one…"* and was sent to ask an administrator for the twelve they already
 * had. The real failure went to `onError`, which is the HOST's transient line at the bottom of the
 * scrolling column — underneath the placeholder that had just told them the opposite, and gone four
 * seconds later while the placeholder stayed.
 *
 * That is the silent-emptiness class this repository keeps having to un-ship: a FAILURE drawn as an
 * ordinary empty state. The three answers are three sentences that cannot be mistaken for each other,
 * and the rewrite above did not touch them:
 *
 *  * STILL ASKING — the spinner, `workshops == null`.
 *  * ANSWERED, AND THE ANSWER IS NONE — the ordinary state of a newly onboarded designer, and not a
 *    fault. This is the only state that may name an administrator.
 *  * COULD NOT ASK — `listFailure`, worded for the cause, with a "Try again" beside it and a
 *    sentence saying that nothing is lost.
 *
 * **THAT LAST SENTENCE HAD TO CHANGE, AND THE CHANGE IS THE POINT OF WRITING THIS DOWN.** It used to
 * justify itself with "this screen only reads, so nothing on this handset has changed", which was
 * true of the chooser and is FALSE of this screen. The promise still holds — a workshop has to be
 * chosen before anything can be added, and this failure happens before that — but the reason had to
 * be replaced rather than left standing, because a promise resting on a mechanism that no longer
 * exists is the same defect as a stale absence note, one paragraph further on.
 * `DwSketchChooserSentenceTest` pins the new mechanism and pins that the old claim is gone.
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
     * The host's transient message line — AND NOTHING ON THIS SCREEN CALLS IT.
     *
     * Every failure this screen can have is now rendered IN PLACE, beside the control that caused it:
     * the workshop list below its own picker, a stage that could not be read above the cards it would
     * have enabled, a row that could not be written under the button that asked for it. Each of those
     * has a different next move, and the host's line slides away after four seconds while a wrong
     * placeholder underneath it stays — which is precisely how the defect in the class KDoc was
     * invisible. `DwProvenanceScreen` is the same shape and MainActivity's arm for it records the
     * resolution in its own words: it "took an `onError` it never called — a channel that looks live
     * to whoever wires up the next failure path and goes nowhere — so it does not take one now."
     *
     * THE ARM AT THE CALL SITE SHOULD GO THE SAME WAY. It is left in place only because
     * `MainActivity.kt` is not this change's to edit; nothing routes on it, so dropping
     * `onError = { showMessage(it) }` from the `Screen.SketchesAndPrototypes` arm and this parameter
     * with it is a two-line change for whoever is next in that file.
     */
    @Suppress("UNUSED_PARAMETER") onError: (String) -> Unit,
) {
    var workshops by remember { mutableStateOf<List<DesignWorkshopDto>?>(null) }
    var truncated by remember { mutableIntStateOf(0) }
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
    /** The workshop everything below the picker is scoped to. Blank until the list lands. */
    var chosen by remember { mutableStateOf("") }
    /**
     * Why the picker filled itself in, or null. Cleared the moment the designer picks.
     *
     * A DROPDOWN THAT FILLS ITSELF IN AND CANNOT SAY WHY READS AS A BUG — `DesignWorkshopPickerState`
     * carries the same field for the same reason. It is retired on a tap because the explanation is
     * about a choice that is no longer the app's.
     */
    var prefillNote by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(DwSketchChooserTab.UPLOAD) }

    LaunchedEffect(attempt) {
        /*
          EVERY ATTEMPT STARTS FROM THE LOADING STATE, and this is not tidying. A retry that failed
          identically would otherwise mutate NOTHING on screen — same sentence, same button — so the
          designer who pressed it, and in particular the reader who cannot see the button, gets the
          "nothing happened" that the button exists to end. Clearing these sends the status region
          through the spinner and back, which is a change assistive technology announces.
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
                  LEAVING THE SCREEN IS NOT A FAILURE, AND NEITHER IS PRESSING "Try again". Both
                  arrive here as a `CancellationException`, and the second is the dangerous one: the
                  cancelled run would write its `listFailure` after the replacement run had already
                  cleared it, so "could not be listed" would sit over a load that is at that moment in
                  flight, and no further attempt would clear it because the spinner it belongs to has
                  already been and gone. Rethrown, as `dwReadQrPicture` and MainActivity's
                  `loadMyActivity` caller both do.
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

        /*
          THE DEFAULT, ASKED SEPARATELY AND ONLY WHEN THERE IS A LIST TO APPLY IT TO.

          `runCatching` of its own, because the two requests fail for different reasons: the list is a
          scoped read a designer always passes, the default is a newer endpoint an older deployment
          may not have at all. A 404 from a server that predates it must leave the picker perfectly
          usable, unprefilled — which is `rememberDesignWorkshopPicker`'s own argument, kept.

          NOTHING IS PREFILLED OVER A CHOICE THE DESIGNER HAS ALREADY MADE. `dwChooserDefaultWorkshop`
          honours `chosen` whenever it is still in the list, so a "Try again" cannot move the
          selection out from under somebody mid-attachment.
        */
        val rows = workshops.orEmpty()
        if (rows.isNotEmpty()) {
            val answer = runCatching { repository.designWorkshopDefaultForMe() }
                .onFailure { error -> if (error is CancellationException) throw error }
                .getOrNull()
            val fromServer = answer?.workshopId?.trim().orEmpty()
            val wasBlank = chosen.isBlank()
            val next = dwChooserDefaultWorkshop(rows, fromServer, chosen)
            chosen = next
            prefillNote = when {
                // The designer's own choice, or a re-read that kept it. Nothing to explain.
                !wasBlank -> prefillNote
                // THE SERVER DECIDED, so the server's sentence — the two doors need different words
                // and only `reason` knows which this was.
                next.isNotBlank() && next == fromServer ->
                    designWorkshopPrefillNote(answer?.reason, answer?.accessAt)
                // THE FALLBACK, which answers a DIFFERENT question and must not borrow the sentence
                // above. See [dwChooserDefaultWorkshop].
                next.isNotBlank() -> DW_SKETCH_CHOOSER_FALLBACK_PREFILL
                else -> null
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
            "Pick a workshop, then add and file its sketches and prototypes under Upload, or see " +
                "how its peer round has ranked them under Review. The work itself lives on the " +
                "workshop's own sketch and prototype stages, which both tabs open.",
            color = MaterialTheme.field.muted,
            fontSize = 12.sp,
            lineHeight = 17.sp
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

          THE PICKER IS DELIBERATELY NOT IN HERE. A merging live region is the wrong parent for a
          control — it would flatten the trigger into the paragraph and read the whole thing out on
          arrival. This region carries the STATUS of the list; the control is below.
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

                // A list arrived. The picker is below, outside this region.
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

        /*
          THE SELECTOR, WHICH IS THE WHOLE DIFFERENCE BETWEEN THIS SCREEN AND THE LIST IT REPLACED.

          `SearchableSelectField` rather than a hand-rolled dropdown, because it is what every other
          long list in this app uses and it grows its own filter box at eight options — a designer on
          twenty is not scrolling blind, and one who was sent a workshop code in a message can type it
          straight into the filter, because `hint` is SEARCHED as well as shown.

          `includeNone = false`: emptying the picker would leave the tabs below scoped to nothing and
          a control implying that is a state worth choosing. The way to a different workshop is
          another workshop.
        */
        val rows = workshops.orEmpty()
        SearchableSelectField(
            label = "Which design workshop",
            options = rows.map { workshop ->
                SelectOption(
                    value = workshop.id,
                    label = dwChooserWorkshopLabel(workshop),
                    // The workshop CODE and the three telling facts. Two workshops that share a title
                    // and a date render as two identical rows, and an identical row is a choice a
                    // reader cannot make.
                    hint = listOfNotNull(
                        workshop.workshopCode?.takeIf { it.isNotBlank() },
                        dwChooserWorkshopHint(workshop).takeIf { it.isNotBlank() },
                    ).joinToString(" · ").takeIf { it.isNotBlank() },
                )
            },
            selectedValue = chosen,
            placeholder = when {
                workshops == null -> "Looking for your workshops…"
                listFailure != null -> "This list could not be loaded"
                rows.isEmpty() -> "No workshops are listed for this account"
                else -> "Choose one of your design workshops"
            },
            includeNone = false,
            enabled = rows.isNotEmpty(),
            onSelect = { id ->
                chosen = id
                // A person picked. Retires the explanation, which was about a choice that is no
                // longer the app's.
                prefillNote = null
            },
        )
        prefillNote?.let { note ->
            Text(note, color = MaterialTheme.field.muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
        if (truncated > 0) {
            // RULE 10: EVERY CAP SAYS SO — and only when it bites, so an ordinary designer on four
            // workshops never reads a sentence about a ceiling they cannot reach.
            Text(
                "$truncated more workshop${if (truncated == 1) "" else "s"} not shown, so a " +
                    "workshop missing from this picker is not a workshop you cannot open. Design " +
                    "workshops searches the whole list, and its sketch and prototype stages open " +
                    "from a workshop opened there.",
                color = MaterialTheme.field.muted,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }

        DwSketchChooserTabStrip(
            selected = tab,
            onSelect = { tab = it },
            // THE TABS DESCRIBE WORK ON A WORKSHOP, so they are off until there is one. The sentence
            // below says why — a disabled control with no explanation is the shape of a screen that
            // looks broken.
            enabled = chosen.isNotBlank(),
        )

        if (chosen.isBlank()) {
            Text(
                DW_SKETCH_CHOOSER_PICK_FIRST,
                color = MaterialTheme.field.muted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        } else {
            /*
              RE-KEYED ON THE WORKSHOP, AND THIS IS LOAD-BEARING RATHER THAN TIDY.
              `SketchesWorkspace.tsx`'s header carries the whole argument and it ports exactly: the
              review panel coalesces its arrangement push behind a quiet second and flushes on
              unmount, and the stage key is the SAME STRING in every workshop. A designer who nudged
              a card in workshop A and then changed the picker to workshop B inside that window would
              have A's arrangement written into B's draft — a wrong ordinal in a real record with
              nothing on screen to say it happened. `key` turns the change into a dispose, whose
              cleanup runs before the new mount's effects, so the flush still lands where it was meant
              to. The Upload tab needs it for the same reason: its media bridge closes over the
              workshop it was built for.
            */
            key(chosen) {
                DwSketchChooserTabPanel(tab) {
                    when (tab) {
                        // THE TWO PANELS ARE MOUNTED EXCLUSIVELY. Keeping the review panel composed
                        // behind the upload tab would change WHEN an arrangement is offered to the
                        // repository, which is the web's stated reason for the same construction.
                        DwSketchChooserTab.UPLOAD -> DwSketchChooserUploadTab(
                            repository = repository,
                            workshopId = chosen,
                            onOpenStage = onOpenStage,
                        )

                        DwSketchChooserTab.REVIEW -> DwSketchChooserReviewTab(
                            repository = repository,
                            workshopId = chosen,
                            onOpenStage = onOpenStage,
                        )
                    }
                }
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
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * ITS REASON WAS REWRITTEN ON 2026-08-28 AND THE OLD ONE MUST NOT COME BACK
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * It used to read "this screen only reads, so nothing on this handset has changed", and that was a
 * true statement about a chooser that navigated. **This screen now writes** — it mints sketch and
 * prototype rows and attaches photographs to them — so the old justification is a claim about a
 * mechanism that no longer exists, which is exactly the failure class the class KDoc's stale-absence
 * paragraph is about.
 *
 * THE PROMISE ITSELF STILL HOLDS, and the new reason is the one that actually carries it: the picker
 * is what scopes every control below it, so **nothing on this screen can write until a workshop has
 * been chosen**, and a list that failed is a list nothing was chosen from. That is checkable in the
 * code above rather than merely asserted — the tabs are `enabled = chosen.isNotBlank()` and the
 * panels are not composed at all until then.
 *
 * RULE 10 CUTS BOTH WAYS: a screen must say when it is showing less than the truth, and it must say
 * when a failure has taken nothing from the reader. Silence on the second point is read as the worst
 * case by anyone whose fortnight of fieldwork is in the app.
 *
 * The route it names is true offline: `WorkshopListScreen` falls back to the drafts on this handset
 * with "Showing what is stored on this device", and the two stages open from a workshop opened from
 * there — which is how sketch work was reached before this screen existed at all.
 */
internal const val DW_SKETCH_CHOOSER_NOTHING_LOST: String =
    "Nothing is lost: a workshop has to be chosen before anything here can be added or attached, " +
        "and this list failed before that, so nothing on this handset has changed. Design workshops " +
        "still lists what is stored on this device, and the sketch and prototype stages open from a " +
        "workshop opened there."

/**
 * WHY THE PICKER FILLED ITSELF IN WHEN THE SERVER HAD NO ANSWER.
 *
 * A DIFFERENT SENTENCE FROM `designWorkshopPrefillNote`'S, deliberately, because it is the answer to
 * a different question. That one reports what `GET /design-workshops/default-for-me` decided — "most
 * recently added to", or "most recently opened". This one is the fallback for when that request had
 * nothing to say, and all it can honestly claim is the ordering of the list it took the row from:
 * `list_design_workshops` is newest-first. Borrowing the server's wording here would tell a designer
 * they were recently added to a workshop on the strength of a request that never answered.
 */
internal const val DW_SKETCH_CHOOSER_FALLBACK_PREFILL: String =
    "Filled in with your most recently created design workshop, because the repository did not say " +
        "which one you last worked on. Change it if this is not the one."

/**
 * WHY THE TABS ARE OFF WHILE NO WORKSHOP IS CHOSEN.
 *
 * Not a permission and not a failure — the ordinary state of a screen whose first question has not
 * been answered yet. It is said because a disabled control with no explanation is the shape of a
 * screen that looks broken, which is `DwRankableList.disabledReason`'s rule and the reason that
 * parameter is a sentence rather than a boolean.
 */
internal const val DW_SKETCH_CHOOSER_PICK_FIRST: String =
    "Choose a workshop above and its Upload and Review tabs open here. Both are about one workshop " +
        "at a time, because a sketch belongs to the workshop that made it."

/*
  ══════════════════════════════════════════════════════════════════════════════════════════════════
  WHAT A PROTOTYPE'S 3D WORK ACTUALLY BECOMES IN THE DOCUMENT THE OFFICER RECEIVES
  ══════════════════════════════════════════════════════════════════════════════════════════════════

  THE DEFECT. A designer standing at a workshop can attach a .glb to a prototype on this handset and
  be told nothing whatsoever about what happens to it. Stage 13's prototype entity declares BOTH
  halves of the 3D record and they do not end up in the same place:

      f("turntablePhotos", "360° capture", IMGS, A, phase_note="Reviewer: “Kumar da team”."),
      f("modelFile",       "3D model",     FILE, A, phase_note="Reviewer: “Kumar da team”."),

  (`backend/app/services/stage_definitions.py`, the `STAGE_13` / `prototype` block — read 2026-08-27.
  Re-check: `grep -n 'turntablePhotos\|modelFile' backend/app/services/stage_definitions.py`.)

  ── WHERE THESE TWO SENTENCES ARE NOW DRAWN, AND WHY THAT MOVED ────────────────────────────────────

  They used to be printed at the top of this screen, above a list of navigation buttons, under the
  argument that "this screen is the door" — a designer who read it on the way in could photograph the
  piece while it was still in front of them, and one told after the .glb had gone up had been told too
  late to act on it. That argument is unchanged and is now better served: the Upload tab draws them
  IMMEDIATELY ABOVE THE TWO CAPTURE CARDS THEY ARE ABOUT (see `DwChooserHalf.mediaNotes`), which is
  where the web puts the identical wording — `frontend/components/sketches/upload/
  PrototypeModelField.tsx` renders it on the turntable card. Same words, same moment, one fewer
  paragraph between a designer and the picker they came to use.

  ── THE THREE FILES THAT DECIDE THIS, AND WHY NO CLIENT CAN ────────────────────────────────────────

  `ReportBuilder.attachments_named_but_not_carried` states the rule this comment is under, and it is
  a rule about SHAPE rather than about any one sentence: *a claim about what a report CONTAINS cannot
  be verified from any client*, both clients render their help straight off the published registry,
  so one wrong sentence can be written any number of times without a single surface disagreeing with
  it. That module counts the passes — `report_annexures` and `report_custom_sections` each record
  "three surfaces told the designer the office's copy would carry it", `stage_definitions` records the
  identical false claim made three times in one wave at `surveyDocument`, and correcting two sentences
  in the builder itself was the fourth pass over one claim in a day. So this copy is written from the
  three files that ARE the authority, each read on 2026-08-27, and from nothing else:

   1. `backend/app/services/report_builder.py`
      * `format_value`'s media branch: FILE, AUDIO and VIDEO have no image path to be placed by, so
        their stored ids become the literal string `"{count} document(s) attached"` and nothing more
        — deliberately not even a filename, because that module is ALSO the on-device report builder
        and may not query for one. `"3D model"` is a FILE, so the officer's page reads
        **3D model — 1 document attached**. That is the whole of it.
      * `_image_sources` — pass one filters on `FieldType.IMAGE` and `FieldType.IMAGE_LIST` and on
        the tier, and on NOTHING ELSE. `turntablePhotos` is an IMAGE_LIST, so its frames really are
        placed on the page, as a named plate captioned with the field's own label. `_images` is "the
        only placement path there is", which is why the model file has none.
   2. `backend/app/services/report_templates.py`
      * `SpecialSection.ANNEXURE_MEDIA` is photographs only and says so at length; it gathers through
        `_images`, so the contact sheet cannot carry a model file either. A FILE annexure is recorded
        there as a DECISION with its two reasons, not as an oversight.
      * `TEMPLATES`: `max_tier` defaults to ADVANCED and only COMPACT_SUMMARY is BASIC, and both of
        these fields are tier A. `PROTOTYPE_DEVELOPMENT` is a section of DCH_STANDARD, DIC_STANDARD
        and DETAILED_TECHNICAL (all via `_standard_sections`, where `include_photos` is true and
        `max_photos` is 0 — uncapped) and of no other template. So on Compact summary, Implementing
        agency format and Photo catalogue, NEITHER the frames NOR the “1 document attached” line
        appear at all — the whole prototype stage is absent from those three. The sentence below does
        not try to say that on a phone: it is the smaller claim, which is true under every template
        that prints stage 13 at all.
   3. `backend/app/services/report_annexures.py`
      * The one annexure that carries something a writer cannot draw carries TRANSCRIPTS of AUDIO,
        and only under the stage-20 toggle. There is no sibling for FILE. A .glb has no transcript.

  ── WHY THE COPY DOES NOT PROMISE A VIEWER ─────────────────────────────────────────────────────────

  Nothing in this application draws a 3D model, on either surface, and the sentence says so in the
  web's own words. On this handset that is checkable: `android/app/build.gradle.kts` has no 3D
  dependency (no sceneview, filament, glTF or model-viewer — checked 2026-08-27; re-check with
  `grep -in 'sceneview\|filament\|gltf\|model-viewer' android/app/build.gradle.kts`). Coil draws
  images and video thumbnails and media3 plays video; neither opens a mesh. And a viewer would not
  change the sentence anyway — the limit is in the document generator, and the person the turntable
  is for is the officer reading the .docx, not the designer holding the phone.

  ── WHY THE WORDING IS THE WEB'S, WORD FOR WORD ────────────────────────────────────────────────────

  Android owns wording generally. It does not here, because the web already had the accurate sentence
  and this handset had none: `frontend/components/sketches/upload/PrototypeModelField.tsx` (read
  2026-08-27) says it on the turntable card, argued from the same three files. Two surfaces telling a
  designer two different things about one ministry document is the exact failure the rule above
  exists to prevent, and re-phrasing a correct sentence is how the second version comes to be
  slightly wrong. ONE substitution was unavoidable — the web ends "rather than in the browser", which
  is false on a handset — and it is the only word that differs. The handset's own established phrasing
  for this class of fact is `DesignerProfileScreen`'s CV line — “The report NAMES it — “1 document
  attached” — but a report file cannot carry a document, so send the CV alongside the report” — and
  these agree with it rather than inventing a third account.
*/

/**
 * WHAT REACHES THE PRINTED PAGE OFF A PROTOTYPE, AND WHAT DOES NOT.
 *
 * The middle of this is `PrototypeModelField.tsx`'s sentence verbatim, for the reason set out above;
 * the last clause reads "rather than in the app" where the web reads "rather than in the browser".
 *
 * IT NAMES THE FIELDS BY THEIR REGISTRY LABELS — “3D model” and “360° capture” — because those are
 * the words the designer will meet on the capture cards immediately below it and on the stage 13 form
 * one tap from here. A sentence about "the model file" would be advice about a box they cannot find.
 *
 * The closing clause is the ACTION, and it is the whole point of saying any of this before the
 * upload: a designer who knows can photograph the prototype as well, and then the officer sees the
 * piece.
 */
internal const val DW_PROTOTYPE_3D_IN_THE_REPORT: String =
    "On stage 13 a prototype takes a “3D model” file and a “360° capture”, and only one of them " +
        "prints. The ministry document places image fields as pictures and prints every other kind " +
        "of attachment as a count — a 3D model appears in it as the words “1 document attached”, " +
        "and no viewer built into this application can change that, because the limit is in the " +
        "document generator rather than in the app. The file is kept with the record and stays " +
        "downloadable for the next designer, but a turn of photographs is the only form of this " +
        "prototype that reaches the printed page — so photograph the piece as well."

/**
 * HOW TO SHOOT A TURN THAT IS WORTH PRINTING. `PrototypeModelField.tsx`'s second paragraph, verbatim.
 *
 * THE TWO NUMBERS ARE STATED AND NOT ENFORCED, which is that panel's argument and holds here: twelve
 * frames is one photograph every thirty degrees, the coarsest capture that still reads as rotation
 * rather than as a handful of unrelated views; twenty-four is every fifteen degrees and is what a
 * reviewer can actually judge a form from. A prototype photographed eight times is still better than
 * one photographed never, so nothing on this handset refuses a short turn.
 *
 * SEPARATE FROM [DW_PROTOTYPE_3D_IN_THE_REPORT] rather than one long paragraph, because they are two
 * different kinds of statement: one is a fact about the delivered document that must not drift, the
 * other is craft advice. A reader who disagrees with the advice must not be given a reason to doubt
 * the fact, and a test that pins the fact must not be pinning the advice.
 */
internal const val DW_TURNTABLE_CAPTURE_ADVICE: String =
    "Stand the piece still and move around it, one photograph every 30° for 12 frames, or every 15° " +
        "for 24 — enough that a reviewer can read the form rather than guess it. Keep the light and " +
        "the background the same for all of them."

/**
 * How many workshops this screen asks for.
 *
 * The same 20 the list screen's first page uses. Deliberately NOT the server's ceiling: a longer list
 * behind one picker is a longer scroll to the same answer, and `SearchableSelectField` grows its own
 * filter box at eight options, so a designer on twenty is not scrolling blind. The sentence below the
 * picker names what was left out and where to search for it.
 */
private const val PAGE_SIZE = 20

/**
 * The two stages sketch and prototype work is filed under. Registry keys, never numbers.
 *
 * KEPT AS NAMED CONSTANTS EVEN THOUGH THE TABS NOW RESOLVE THE STAGE THROUGH THE REGISTRY
 * (`dwStageKeyForEntity`, which is what both clients use and what survives `SKETCH_REVIEW` being
 * dropped). They are the screen's own statement of which two stages it is about, they are what
 * `frontend/e2e/sketches-parity-matrix-unit.spec.ts` asserts this file still names, and they are the
 * fallback a reader looks for when asking "which stages is this screen for?" — a question the answer
 * to which should not require reading a registry lookup three files away.
 */
internal const val SKETCH_STAGE = "SKETCH_DEVELOPMENT"
internal const val PROTOTYPE_STAGE = "PROTOTYPE_DEVELOPMENT"
