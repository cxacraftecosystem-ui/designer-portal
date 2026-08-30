package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DraftRow
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.StageDraft
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StagePush
import com.designprototype.workshop.data.StageSaveResultDto
import com.designprototype.workshop.data.dwRowId
import com.designprototype.workshop.data.entityKey
import com.designprototype.workshop.data.rowsFor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * A FAILED LOAD MAY NOT BE WORDED AS AN ANSWER — the silent-emptiness class, on the Sketches &
 * prototypes screen — plus the pure rules its Upload tab writes rows through.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE DEFECT THE FIRST HALF PINS
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `SketchesAndPrototypesScreen` wrote a failed list as `emptyList()`, which fell into the `isEmpty()`
 * branch, which renders [DW_SKETCH_CHOOSER_NO_WORKSHOPS]. So a designer on twelve design workshops,
 * standing in a courtyard with no signal, was told *"You are not on any design workshop yet. Once an
 * administrator adds you to one…"* — told they had none, and sent to ask an administrator for the
 * twelve they already had. The failure itself went to the host's transient message line at the bottom
 * of the scrolling column, under the placeholder that had just said the opposite.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THE WORDS, AND NOT THE BRANCHES
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The branch order is now the guard — `listFailure` is tested before `isEmpty()` — and a JVM test
 * cannot compose a `@Composable` to check it; that is the instrumented suite's ground. What a JVM test
 * CAN pin is the property that made the miswiring harmful rather than merely untidy: these are three
 * different facts, so they must be three different sentences, and only one of them is entitled to send
 * anybody to an administrator. Written the other way round — one sentence reused for two states — the
 * branch order stops mattering and the defect can come back through any of them.
 *
 * Pinned in the spirit of `DwRefusalSentenceTruthTest`: the property, not the prose.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * AND WHAT THE SECOND HALF PINS, WHICH IS NEW ON 2026-08-28
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The screen now WRITES: it mints sketch and prototype rows into the stage's own draft and attaches
 * photographs to them. Every decision on the way to that write is pure, lives in
 * `DwSketchChooserRows.kt`, and is asserted here with no Context, no Compose and no filesystem —
 * which is the same bargain `data/DwDesignRatings.kt` makes for the review half. The three that
 * matter most are the ones whose failure is silent on the device and only visible in a document a
 * ministry receives a fortnight later: the row identity, the entity-scoped row replacement, and the
 * single-versus-list shape of a media value.
 */
class DwSketchChooserSentenceTest {

    private val failures = listOf(
        "offline" to DW_SKETCH_CHOOSER_OFFLINE,
        "refused" to DW_SKETCH_CHOOSER_REFUSED,
        "nothing lost" to DW_SKETCH_CHOOSER_NOTHING_LOST,
    )

    /**
     * THE REGRESSION ITSELF, as a rule about words. Only the state that actually got an answer may
     * name an administrator; a sentence about a request that never landed sends a designer on an
     * errand invented out of a failure.
     */
    @Test
    fun `only the answered-and-none sentence names an administrator`() {
        assertTrue(DW_SKETCH_CHOOSER_NO_WORKSHOPS.contains("administrator"))
        failures.forEach { (name, sentence) ->
            assertFalse(
                "the '$name' sentence sends a designer to an administrator about a request that " +
                    "failed — that is the defect this test exists for",
                sentence.contains("administrator", ignoreCase = true),
            )
        }
    }

    /**
     * AND THE CONVERSE. The empty-state sentence must not hedge into failure language either: a
     * newly onboarded designer being told something "could not" happen would read a correct, ordinary
     * answer as a broken app, and would keep pressing.
     */
    @Test
    fun `the answered-and-none sentence never claims a failure`() {
        listOf("could not", "no connection", "failed", "try again").forEach { hedge ->
            assertFalse(
                "the empty-state sentence contains failure language: '$hedge'",
                DW_SKETCH_CHOOSER_NO_WORKSHOPS.contains(hedge, ignoreCase = true),
            )
        }
    }

    /**
     * THE DISTINCTION ITSELF IS SAID OUT LOUD in the offline sentence, because a reader cannot infer
     * it: an empty list and an unaskable one look identical on screen unless one of them says which
     * it is.
     */
    @Test
    fun `the offline sentence separates an empty list from an unaskable one`() {
        assertTrue(DW_SKETCH_CHOOSER_OFFLINE.contains("could not be asked for"))
        assertTrue(DW_SKETCH_CHOOSER_OFFLINE.contains("not a list with nothing in it"))
        // The Upload tab's own stage-read failure has to draw the same distinction, for the same
        // reason and about a smaller list. Two sibling sentences, one rule.
        assertTrue(DW_SKETCH_CHOOSER_STAGE_OFFLINE.contains("could not be asked for"))
        assertTrue(DW_SKETCH_CHOOSER_STAGE_OFFLINE.contains("not a list with nothing in it"))
        // And so does the round, which is the third list on this screen that can fail to be asked.
        assertTrue(DW_SKETCH_CHOOSER_ROUND_OFFLINE.contains("could not be asked for"))
        assertTrue(DW_SKETCH_CHOOSER_ROUND_OFFLINE.contains("not a round with nothing in it"))
    }

    /**
     * RULE 10's other half: a failure must say what it cost, and this one costs nothing.
     *
     * ── THE REASON CHANGED ON 2026-08-28 AND THIS TEST IS WHY IT CANNOT SILENTLY CHANGE BACK ──────
     *
     * The sentence used to justify itself with "this screen only reads, so nothing on this handset
     * has changed". That was true of the chooser this screen was, and it is FALSE of the screen it
     * now is: the Upload tab mints rows and attaches files. The promise survives on a different
     * mechanism — nothing on the screen can write until a workshop has been chosen, and the list
     * failing is the list nothing was chosen from — so the old clause is asserted ABSENT as well as
     * the new one present. A promise resting on a mechanism that no longer exists is the same defect
     * as a stale "this does not exist" comment, and this screen's own KDoc has had to record both.
     */
    @Test
    fun `the failure block says that nothing is lost, and why it can`() {
        assertTrue(DW_SKETCH_CHOOSER_NOTHING_LOST.startsWith("Nothing is lost:"))
        // THE REASON, not just the reassurance — a promise with no mechanism behind it is one a
        // reader has no way to weigh. The mechanism is the picker: nothing below it is composed, and
        // the tabs are not enabled, until a workshop has been chosen.
        assertTrue(
            "the promise must name the mechanism that actually carries it",
            DW_SKETCH_CHOOSER_NOTHING_LOST.contains("has to be chosen before"),
        )
        assertFalse(
            "this screen writes now — the retired 'only reads' justification must not come back",
            DW_SKETCH_CHOOSER_NOTHING_LOST.contains("only reads", ignoreCase = true),
        )
        // And the route that still works with no signal, because "nothing is lost" is cold comfort
        // to somebody who came here to open a stage.
        assertTrue(DW_SKETCH_CHOOSER_NOTHING_LOST.contains("Design workshops"))
    }

    /** Three states, three sentences. Any two of them being one is how the defect happened. */
    @Test
    fun `the three answers are three different sentences`() {
        val all = listOf(
            DW_SKETCH_CHOOSER_NO_WORKSHOPS,
            DW_SKETCH_CHOOSER_OFFLINE,
            DW_SKETCH_CHOOSER_REFUSED,
        )
        assertEquals("two of the three answers share a sentence", all.size, all.toSet().size)
        all.forEach { assertTrue("an answer is blank", it.isNotBlank()) }
    }

    /**
     * THE PREFILL EXPLAINS ITSELF, AND THE TWO EXPLANATIONS ARE NOT INTERCHANGEABLE.
     *
     * `designWorkshopPrefillNote` reports what `GET /design-workshops/default-for-me` decided — "most
     * recently added to" or "most recently opened". [DW_SKETCH_CHOOSER_FALLBACK_PREFILL] is what the
     * screen says when that request had nothing to say, and all it can honestly claim is the ordering
     * of the list it took the row from. Borrowing the server's wording there would tell a designer
     * they had recently been added to a workshop on the strength of a request that never answered.
     */
    @Test
    fun `the fallback prefill does not borrow the server's reason`() {
        assertFalse(
            "the fallback must not claim an allocation the repository never reported",
            DW_SKETCH_CHOOSER_FALLBACK_PREFILL.contains("added to", ignoreCase = true),
        )
        assertTrue(
            "a box that fills itself in must say why",
            DW_SKETCH_CHOOSER_FALLBACK_PREFILL.contains("because"),
        )
        assertTrue(
            "and must offer the way out of a wrong guess",
            DW_SKETCH_CHOOSER_FALLBACK_PREFILL.contains("Change it"),
        )
    }

    /**
     * A DISABLED CONTROL WITH NO EXPLANATION IS THE SHAPE OF A SCREEN THAT LOOKS BROKEN.
     *
     * Three controls on this screen can be off for a reason that is not a permission and not a
     * failure — the tabs before a workshop is chosen, and the capture cards before the repository's
     * copy of a stage has been read. Each has a sentence, and none of them may read as a refusal.
     */
    @Test
    fun `the two not-yet states are explained and are not worded as refusals`() {
        listOf(
            "pick a workshop first" to DW_SKETCH_CHOOSER_PICK_FIRST,
            "stage not yet read" to DW_SKETCH_CHOOSER_STALE,
        ).forEach { (name, sentence) ->
            assertTrue("the '$name' state says nothing at all", sentence.isNotBlank())
            listOf("not allowed", "permission", "you may not").forEach { refusal ->
                assertFalse(
                    "the '$name' state is worded as a refusal ('$refusal'), which it is not",
                    sentence.contains(refusal, ignoreCase = true),
                )
            }
        }
        // The stale sentence must ALSO say what it costs, which is nothing — the same duty the list
        // failure has, one level down.
        assertTrue(DW_SKETCH_CHOOSER_STALE.contains("Nothing has been lost"))
    }

    /**
     * A WRITE THAT DID NOT HAPPEN SAYS SO IN CAPITALS AND SAYS WHERE THE BYTES WENT.
     *
     * The two device-write failures are the only places on this screen where a designer can lose an
     * action, and both are recoverable — the row can be made on the stage form, and the imported file
     * is already in the workshop's media directory. Silence about either is read as the worst case.
     */
    @Test
    fun `a refused local write names what did not happen and what survived`() {
        assertTrue(DW_SKETCH_CHOOSER_ROW_NOT_ADDED.contains("NOT been added"))
        assertTrue(DW_SKETCH_CHOOSER_ROW_NOT_ADDED.contains("Nothing has changed"))
        assertTrue(DW_SKETCH_CHOOSER_FILE_NOT_ATTACHED.contains("NOT been attached"))
        assertTrue(
            "a designer whose file was copied but not referenced must be told it survived",
            DW_SKETCH_CHOOSER_FILE_NOT_ATTACHED.contains("is not lost"),
        )
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // WHERE A SENTENCE MAY SEND SOMEBODY, NOW THAT THIS TAB DERIVES
    // ══════════════════════════════════════════════════════════════════════════════════════════════
    //
    // THE DEFECT THESE TWO PIN HAS NOW HAPPENED FOUR TIMES ON THESE TWO SURFACES, which is why it is
    // pinned as a rule rather than corrected a fifth time. When `DwSketchDerivationSection` put
    // tracing, straightening and measuring onto the Upload tab, four sentences on the same screen went
    // on saying those acts were somewhere else. Two were corrected in the pass that mounted the
    // section; two — `DwChooserHalf.emptyNote` and this screen's own subtitle — were not, and the
    // subtitle is the first paragraph a designer reads.
    //
    // A SENTENCE THAT SENDS SOMEBODY AWAY FROM A CONTROL THEY ARE LOOKING AT IS WORSE THAN NO
    // SENTENCE: a missing control can be looked for and found, and a capability named as living
    // elsewhere is one nobody scrolls to. The other client wrote the same rule down for the same tab —
    // "a sentence pointing at the wrong place is the defect this tab has already paid for once".
    //
    // THE VOCABULARY IS READ OFF THE CARDS AND NOT TYPED HERE. Each derivation card's title begins
    // with the act it performs, so a card renamed moves both tests with it instead of leaving them
    // pinning a verb nothing on the screen says any more. That is the same discipline
    // `DW_TRACE_EXPORT_FORMAT_COUNT` applies to a number: derive it, never transcribe it.

    /** "Trace", "Straighten", "Measure" — the three acts, in the cards' own words, folded for search. */
    private val derivationActs: List<String> =
        listOf(DW_TRACE_CARD_TITLE, DW_RECTIFY_CARD_TITLE, DW_MEASURE_CARD_TITLE)
            .map { it.substringBefore(' ').lowercase(Locale.ROOT) }

    /**
     * Every sentence on this screen whose job is to say where something ELSE lives.
     *
     * THE SET IS WRITTEN OUT AND THAT IS THE WEAKNESS OF THIS TEST, said plainly: a fifth such
     * sentence added later and not added here is exactly the miss that produced the fourth. It is
     * still worth having — it holds the four that exist, and the block comment above tells the next
     * reader what to add — but the real guard is that these are named constants at all. A private
     * getter on a private data class, which is what two of these were, cannot be reached by any JVM
     * test that could have caught this.
     */
    private val whereTheRestOfTheRecordIs = listOf(
        "the sketches empty note" to DW_SKETCH_CHOOSER_NO_SKETCHES_YET,
        "the prototypes empty note" to DW_SKETCH_CHOOSER_NO_PROTOTYPES_YET,
        "the sketches stage note" to DW_SKETCH_CHOOSER_SKETCH_ELSEWHERE,
        "the prototypes stage note" to DW_SKETCH_CHOOSER_PROTOTYPE_ELSEWHERE,
    )

    /**
     * THE RULE ITSELF. These four sentences exist to list what is NOT on this tab, so naming an act
     * this tab mounts a panel for is not a wording slip — it is the sentence asserting the opposite of
     * what the screen underneath it does.
     *
     * Nothing is asserted about what they DO name. "The name, the caption, the category, typing a
     * dimension in by hand" is a list a reader must stay free to improve; only the one thing it may
     * never contain is fixed here.
     */
    @Test
    fun `a sentence about what is elsewhere never names an act this tab performs`() {
        whereTheRestOfTheRecordIs.forEach { (name, sentence) ->
            assertTrue("$name says nothing at all", sentence.isNotBlank())
            derivationActs.forEach { act ->
                assertFalse(
                    "$name puts '$act' on the stage form, and this tab mounts a card for it — a " +
                        "designer who believes that sentence never scrolls to the control",
                    sentence.contains(act, ignoreCase = true),
                )
            }
        }
    }

    /**
     * AND THE CONVERSE, ON THE ONE SENTENCE THAT IS READ INSTEAD OF SCROLLING.
     *
     * The subtitle is the whole of what a designer knows about this screen before they touch it, and
     * the report that started this work was that the derivation surfaces were "completely missing" on
     * the handset — when in fact all three were built, tested and mounted one tap deeper. A summary
     * that lists Upload and Review and says nothing about tracing is how a finished feature stays
     * invisible, so this asserts that all three acts are named, and leaves the wording alone.
     */
    @Test
    fun `the subtitle names every act the Upload tab can perform on a photograph`() {
        derivationActs.forEach { act ->
            assertTrue(
                "the screen's own subtitle never mentions '$act', which this tab does",
                DW_SKETCH_CHOOSER_SUBTITLE.contains(act, ignoreCase = true),
            )
        }
        assertFalse(
            "the retired clause put every act on the stage form and must not come back",
            DW_SKETCH_CHOOSER_SUBTITLE.contains("The work itself lives on"),
        )
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // WHAT A 3D MODEL DOES IN THE DELIVERED DOCUMENT
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * THE FACT, AND ONLY THE FACT.
     *
     * `DW_PROTOTYPE_3D_IN_THE_REPORT` makes one claim about a file this application does not
     * generate: a 3D model attached on stage 13 appears in the ministry .docx as the words
     * "1 document attached". That is `report_builder.format_value`'s output, and the sentence is
     * worth nothing unless it is exactly right — a designer who reads it as "the model is printed"
     * hands over a document a reviewer cannot see the prototype in, and one who reads it as "the
     * file is carried" hands over a document the file is not inside.
     *
     * So the literal is pinned, and so are the two things the sentence must never come to say. The
     * strings themselves are `report_builder.py`'s; TRUE AS OF 2026-08-27, re-check with:
     *
     *     grep -n "document attached" backend/app/services/report_builder.py
     *
     * A KOTLIN TEST CANNOT CHECK THE PYTHON, and that is the limit of this one. It pins that THIS
     * COPY of the claim still contains the words the generator writes; it cannot pin that the
     * generator still writes them. The only real guard is a backend test asserting the two facts
     * where they are decided — see the note in `docs/SKETCHES-PROTOTYPES-PARITY.md`.
     *
     * NOTHING IS ASSERTED ABOUT [DW_TURNTABLE_CAPTURE_ADVICE], deliberately. That constant is craft
     * advice — how many frames, what light — and a reader who disagrees with it must be free to
     * improve it without a test standing in the way. Its own KDoc makes the same separation, which
     * is why the two are two constants rather than one paragraph.
     */
    @Test
    fun `the prototype sentence names the exact words the document generator writes`() {
        assertTrue(
            "the .docx says this and the sentence must quote it",
            DW_PROTOTYPE_3D_IN_THE_REPORT.contains("1 document attached"),
        )
        // NEVER "printed", "shown", "drawn" or "included" ABOUT THE MODEL. `_images` places IMAGE and
        // IMAGE_LIST fields only; no writer in this product can draw a mesh.
        listOf("is printed", "is drawn", "is shown", "is rendered").forEach { promise ->
            assertFalse(
                "the sentence must not promise the model itself reaches the page: $promise",
                DW_PROTOTYPE_3D_IN_THE_REPORT.contains(promise, ignoreCase = true),
            )
        }
        // And it must go on saying what the designer can DO about it, which is the only reason the
        // fact is on a chooser at all.
        assertTrue(
            "the action is the point of saying any of this",
            DW_PROTOTYPE_3D_IN_THE_REPORT.contains("photograph the piece as well"),
        )
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // THE ROW: WHAT IT IS CALLED, WHAT IT IS, AND WHERE IT IS FILED
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    private fun row(id: String, vararg values: Pair<String, String>): DraftRow =
        DraftRow(
            id = id,
            values = values.associate { (key, value) -> key to JsonPrimitive(value) },
        )

    /**
     * THE LABEL LADDER IS THE WEB'S, AND ITS ORDER IS THE PART THAT MATTERS.
     *
     * `stageRows.ts#rowLabel` tries `name`, then `sketchNo`, then `prototypeCode`. Re-ordering them
     * would make one workshop's picker read differently on the two clients for the same rows, which
     * is the class of divergence this whole change exists to close.
     */
    @Test
    fun `a row is named by its own name, then its identifier, then its position`() {
        assertEquals(
            "Bamboo stool",
            dwChooserRowLabel(row("sketch#a", "name" to "Bamboo stool", "sketchNo" to "S-4"), 0),
        )
        assertEquals("S-4", dwChooserRowLabel(row("sketch#a", "sketchNo" to "S-4"), 0))
        assertEquals("P-7", dwChooserRowLabel(row("prototype#a", "prototypeCode" to "P-7"), 0))
        // 1-BASED, because it is read by a person and it is the number they can see on the stage
        // form. A blank label would be an option nobody can choose deliberately.
        assertEquals("Untitled 3", dwChooserRowLabel(row("sketch#a"), 2))
        // A BLANK IS NOT A NAME. A row whose name was typed and then cleared falls through, rather
        // than being offered on a picker as an option with no text in it.
        assertEquals("Untitled 1", dwChooserRowLabel(row("sketch#a", "name" to "   "), 0))
    }

    /**
     * JsonNull IS A JsonPrimitive, AND READING `.content` OFF ONE GIVES THE STRING "null".
     *
     * The trap `DwDesignRatings.dwEntryId` documents, asserted here because this ladder is a second
     * place it could bite: a row whose `name` came down as JSON null would otherwise be printed on
     * the picker as a sketch called "null" — not blank, matching nothing, and past every emptiness
     * test that does not check the type first.
     */
    @Test
    fun `a JSON null is not a name`() {
        val nulled = DraftRow(id = "sketch#a", values = mapOf("name" to JsonNull))
        assertEquals("Untitled 1", dwChooserRowLabel(nulled, 0))
    }

    /**
     * THE NEW ROW'S IDENTITY IS THE ONE THE WIRE SPLITS BACK APART.
     *
     * `buildStageBody` sends `row.id.substringAfter(DW_ROW_KEY_SEPARATOR)` as the `_clientKey` the
     * server matches a row on, and `rowsFor` filters on the half BEFORE it. A row minted with any
     * other id shape would be a row that syncs as a stranger every time and never matches itself.
     */
    @Test
    fun `a new row is filed under its entity and carries a client key the wire can split out`() {
        val fresh = dwChooserNewRow(DW_CHOOSER_SKETCH_ENTITY, "uuid-1")
        assertEquals(dwRowId("sketch", "uuid-1"), fresh.id)
        assertEquals("sketch", fresh.entityKey())
        assertEquals("uuid-1", fresh.id.substringAfter('#'))
        // EMPTY VALUES. A row seeded with a guessed name would put a value into a real record that no
        // person typed, which `entry_provenance` would then attribute to the designer.
        assertTrue("a minted row must claim nothing", fresh.values.isEmpty())
        // AND THE PROTOCOL'S OWN KEYS ARE NOT INVENTED HERE. `_entryId` is the server's identity and
        // a client that wrote one would be claiming a row the repository has never stored.
        assertNull(fresh.values["_entryId"])
        assertNull(fresh.values["_clientKey"])
    }

    /**
     * APPENDING A ROW MUST NOT DISTURB ANYTHING ELSE ON THE STAGE — INCLUDING THE TWO DELETION
     * RECORDS AND THE AUTHORITY FLAG.
     *
     * `persistLocally` rebuilds the whole `StageDraft` and therefore has to carry nine fields across
     * by hand, each of which is a comment about a defect. `dwChooserAppendRow` copies instead, so
     * there is no field for a future addition to be forgotten in — and this test is what says so out
     * loud, because "it uses copy()" is an implementation detail and "it cannot clear your custom
     * answers" is the property.
     */
    @Test
    fun `appending a row leaves every other fact about the stage exactly as it was`() {
        val spec = StageDto(number = 11, key = "SKETCH_DEVELOPMENT", title = "Sketch development")
        val existing = StageDraft(
            stageId = "SKETCH_DEVELOPMENT",
            title = "Sketch development",
            order = 11,
            values = mapOf("summary" to JsonPrimitive("two weeks of drawing")),
            custom = mapOf("ownQuestion" to JsonPrimitive("answered")),
            customSeen = true,
            rows = listOf(row("sketch#one"), row("prototypeStageLog#log")),
            mediaIds = listOf("m1"),
            stageSeen = true,
            emptiedEntities = listOf("sketchReview"),
            deletedRowKeys = listOf("sketch#gone"),
            notes = "a note",
        )
        val next = dwChooserAppendRow(spec, existing, dwChooserNewRow("sketch", "uuid-2"))

        assertEquals(existing.values, next.values)
        assertEquals(existing.custom, next.custom)
        assertTrue("authority earned by reading must not be dropped by an append", next.stageSeen)
        assertTrue(next.customSeen)
        assertEquals(existing.mediaIds, next.mediaIds)
        assertEquals("a deletion record is the only place a deletion exists", listOf("sketchReview"), next.emptiedEntities)
        assertEquals(listOf("sketch#gone"), next.deletedRowKeys)
        assertEquals("a note", next.notes)
        // The row landed, at the END of its own collection, and the OTHER collection on the stage is
        // untouched — stage 13 carries a prototype's stage logs beside the prototypes themselves.
        assertEquals(listOf("sketch#one", dwRowId("sketch", "uuid-2")), next.rowsFor("sketch").map { it.id })
        assertEquals(1, next.rowsFor("prototypeStageLog").size)
    }

    /**
     * A STAGE THIS DEVICE HAS NEVER READ IS SEEDED WITHOUT AUTHORITY, AND THAT IS LOAD-BEARING.
     *
     * `StageDraft.stageSeen` is what `buildStageBody` reads as `merge = !authoritative`. Seeded true,
     * the payload carrying one new sketch would claim to be the whole collection, and the sweep would
     * delete every row a colleague filed on the web. Only `dwFoldServerStage` may set it, by actually
     * reading the repository's copy.
     */
    @Test
    fun `a stage seeded from nothing claims no reading of the repository`() {
        val spec = StageDto(
            number = 13,
            key = "PROTOTYPE_DEVELOPMENT",
            title = "Prototype development",
            entities = listOf(
                EntityDto(
                    key = "prototypePlan",
                    cardinality = "SINGLETON",
                    fields = listOf(
                        FieldDto(key = "brief", label = "Brief", required = true),
                        FieldDto(key = "aside", label = "Aside", required = false),
                    ),
                ),
            ),
        )
        val seeded = dwChooserAppendRow(spec, null, dwChooserNewRow("prototype", "uuid-3"))
        assertFalse("a seeded stage must never claim to have read the server's copy", seeded.stageSeen)
        assertEquals("PROTOTYPE_DEVELOPMENT", seeded.stageId)
        assertEquals(13, seeded.order)
        assertEquals("Prototype development", seeded.title)
        // `requiredKeys` is stored WITH the stage so the workshop list can score completeness with no
        // registry and no network — the same four facts `persistLocally` seeds.
        assertEquals(listOf("brief"), seeded.requiredKeys)
        assertEquals(1, seeded.rowsFor("prototype").size)
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // THE MEDIA VALUE: SINGLE OR LIST, AND ONLY EVER ON THE ROW THAT WAS NAMED
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * A ONE-ELEMENT ARRAY IN A SINGLE-FILE FIELD IS REFUSED BY `coerce_value`, SILENTLY HERE AND
     * VISIBLY IN A MINISTRY DOCUMENT A FORTNIGHT LATER.
     *
     * So the shape is decided by the registry's field TYPE and never by the count, and both
     * directions are pinned: an IMAGE_LIST holding one file is still an array, and an IMAGE holding
     * one file is still a bare string.
     */
    @Test
    fun `a media value takes the shape the registry declares, not the shape of the count`() {
        val rows = listOf(row("sketch#a"), row("sketch#b"))
        val asList = dwChooserWriteMedia(rows, "sketch#a", "turntablePhotos", listOf("m1"), asList = true)
        assertEquals(JsonArray(listOf(JsonPrimitive("m1"))), asList.first().values["turntablePhotos"])

        val asOne = dwChooserWriteMedia(rows, "sketch#a", "image", listOf("m1"), asList = false)
        assertEquals(JsonPrimitive("m1"), asOne.first().values["image"])
    }

    /**
     * AN EMPTY SELECTION REMOVES THE KEY rather than storing "" or `[]`.
     *
     * `StageScreen.put`'s own rule: hundreds of fields across twenty-two stages would otherwise
     * accumulate a null apiece to be re-sent on every metered sync.
     */
    @Test
    fun `detaching every file removes the key instead of storing an empty one`() {
        val held = listOf(row("sketch#a", "image" to "m1"))
        val cleared = dwChooserWriteMedia(held, "sketch#a", "image", emptyList(), asList = false)
        assertFalse(cleared.first().values.containsKey("image"))
    }

    /** The write lands on the row that was named, and on no other. */
    @Test
    fun `writing media touches only the named row`() {
        val rows = listOf(row("sketch#a", "image" to "old"), row("sketch#b", "image" to "keep"))
        val next = dwChooserWriteMedia(rows, "sketch#a", "image", listOf("new"), asList = false)
        assertEquals(JsonPrimitive("new"), next[0].values["image"])
        assertEquals(JsonPrimitive("keep"), next[1].values["image"])
    }

    /**
     * A ROW KEY THAT NAMES NOTHING CHANGES NOTHING, AND DOES NOT INVENT A ROW.
     *
     * Reachable in ordinary use: the chosen row can be swept out from under the picker by a fold
     * while it was open. A write that manufactured a row to satisfy the reference would be a row
     * nobody asked for, in a real record.
     */
    @Test
    fun `writing media to a row that has gone changes nothing`() {
        val rows = listOf(row("sketch#a", "image" to "keep"))
        assertEquals(rows, dwChooserWriteMedia(rows, "sketch#gone", "image", listOf("m1"), asList = false))
    }

    /**
     * REPLACING ONE COLLECTION'S ROWS MUST NOT DELETE THE STAGE'S OTHER COLLECTIONS.
     *
     * `StageDraft.rows` is ONE flat list with the entity encoded in each row's id. Stage 13 carries a
     * prototype's stage logs and its material lines beside the prototypes themselves, so a naive
     * whole-list write is a fortnight of costing lines destroyed by attaching one photograph.
     */
    @Test
    fun `replacing one entity's rows leaves the stage's other collections alone`() {
        val stage = StageDraft(
            stageId = "PROTOTYPE_DEVELOPMENT",
            rows = listOf(
                row("prototype#a"),
                row("prototypeStageLog#l1"),
                row("prototypeMaterial#m1"),
            ),
        )
        val next = dwChooserReplaceRows(stage, "prototype", listOf(row("prototype#a", "name" to "Stool")))
        assertEquals(1, next.rowsFor("prototypeStageLog").size)
        assertEquals(1, next.rowsFor("prototypeMaterial").size)
        assertEquals("Stool", dwChooserRowLabel(next.rowsFor("prototype").single(), 0))
    }

    /**
     * READING A HELD VALUE ACCEPTS BOTH SHAPES AND REFUSES EVERYTHING ELSE.
     *
     * Both reach this device — the registry decides which — and the capture card speaks lists, so
     * both are read as lists. Anything else is read as nothing held, which is the conservative
     * direction: a card drawn as empty costs a second look at the stage form, where drawing a
     * reference this build cannot parse would offer a "remove" button for something unidentifiable.
     */
    @Test
    fun `a held media value is read from either shape and from nothing else`() {
        assertEquals(listOf("m1"), dwChooserHeldMedia(row("sketch#a", "image" to "m1"), "image"))
        val list = DraftRow(
            id = "prototype#a",
            values = mapOf(
                "turntablePhotos" to JsonArray(listOf(JsonPrimitive("m1"), JsonPrimitive("m2"))),
            ),
        )
        assertEquals(listOf("m1", "m2"), dwChooserHeldMedia(list, "turntablePhotos"))
        assertEquals(emptyList<String>(), dwChooserHeldMedia(row("sketch#a"), "image"))
        assertEquals(emptyList<String>(), dwChooserHeldMedia(null, "image"))
        val nulled = DraftRow(id = "sketch#a", values = mapOf("image" to JsonNull))
        assertEquals("a JSON null is not a media reference", emptyList<String>(), dwChooserHeldMedia(nulled, "image"))
    }

    /**
     * THE FOUR MEDIA FIELDS ARE FOUND BY THE WEB'S KEYS, IN THE CALLER'S ORDER, AND AN ABSENT ONE
     * IS SIMPLY ABSENT.
     *
     * The order is the caller's because the two halves lead with different things — a sketch with the
     * photograph of the drawing, a prototype with the turn of photographs, which is the one form of a
     * prototype that reaches the printed page.
     */
    @Test
    fun `the media fields are found by key, in the order asked for`() {
        val entity = EntityDto(
            key = "prototype",
            cardinality = "COLLECTION",
            fields = listOf(
                FieldDto(key = "name", label = "Name"),
                FieldDto(key = "modelFile", label = "3D model", type = "FILE"),
                FieldDto(key = "turntablePhotos", label = "360° capture", type = "IMAGE_LIST"),
            ),
        )
        assertEquals(
            listOf("360° capture", "3D model"),
            dwChooserMediaFields(entity, DW_CHOOSER_PROTOTYPE_FIELDS).map { it.label },
        )
        // A registry that declares neither draws no card, which is a state and not an error.
        assertEquals(emptyList<FieldDto>(), dwChooserMediaFields(entity, DW_CHOOSER_SKETCH_FIELDS))
        assertEquals(emptyList<FieldDto>(), dwChooserMediaFields(null, DW_CHOOSER_PROTOTYPE_FIELDS))
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // WHICH WORKSHOP, AND WHICH ROW, THE SCREEN SETTLES ON
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    private fun workshop(id: String) = DesignWorkshopDto(id = id, title = "Workshop $id")

    /**
     * A CHOICE ALREADY MADE ALWAYS WINS, AND A CHOICE THAT IS NO LONGER OFFERED IS DROPPED.
     *
     * The first half is what stops a "Try again" moving the selection out from under somebody
     * mid-attachment. The second is what stops the tabs staying scoped to a workshop whose grant has
     * been revoked between two reads, where every request now answers 404.
     */
    @Test
    fun `the chosen workshop survives a re-read and a vanished one does not`() {
        val rows = listOf(workshop("a"), workshop("b"), workshop("c"))
        assertEquals("b", dwChooserDefaultWorkshop(rows, serverDefaultId = "c", chosen = "b"))
        assertEquals("c", dwChooserDefaultWorkshop(rows, serverDefaultId = "c", chosen = "gone"))
    }

    /**
     * THE SERVER DECIDES "MOST RECENT", AND THE FALLBACK IS THE LIST'S OWN ORDER.
     *
     * `DesignWorkshopViewer.createdAt` is on no wire this client can read, so deriving the default
     * here would answer a different question from the one the web answers. When the endpoint has
     * nothing to say — an older deployment, a failed request, a genuine "no default" — the first row
     * of a newest-first list is the honest fallback, and the screen prints a different sentence over
     * it.
     */
    @Test
    fun `the default is the server's answer, then the newest workshop, then nothing`() {
        val rows = listOf(workshop("a"), workshop("b"))
        assertEquals("b", dwChooserDefaultWorkshop(rows, serverDefaultId = "b", chosen = ""))
        assertEquals("a", dwChooserDefaultWorkshop(rows, serverDefaultId = null, chosen = ""))
        // A DEFAULT NAMING A WORKSHOP THIS PAGE CANNOT OFFER IS NOT A DEFAULT. `list_design_workshops`
        // hardcodes `deletedAt: None` while `load_workshop_or_404` admits an admin to a soft-deleted
        // workshop, so the endpoint can honestly name a row the list does not carry.
        assertEquals("a", dwChooserDefaultWorkshop(rows, serverDefaultId = "z", chosen = ""))
        assertEquals("", dwChooserDefaultWorkshop(emptyList(), serverDefaultId = "b", chosen = "b"))
    }

    /** The row picker follows the same rule one level down: keep the choice, or take the first. */
    @Test
    fun `the chosen row survives a re-read and a vanished one falls back to the first`() {
        val rows = listOf(row("sketch#a"), row("sketch#b"))
        assertEquals("sketch#b", dwChooserKeepSelection("sketch#b", rows))
        assertEquals("sketch#a", dwChooserKeepSelection("sketch#gone", rows))
        assertEquals("sketch#a", dwChooserKeepSelection("", rows))
        assertEquals("", dwChooserKeepSelection("sketch#a", emptyList()))
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // WHAT A SAVE ACTUALLY ACHIEVED
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * FIVE ANSWERS AND NOT ONE "SAVED", AND NONE OF THEM CLAIMS THE REPOSITORY HAS IT UNLESS IT DOES.
     *
     * The row is durable on the phone the moment `updateStage` returns, which is why the add does not
     * wait for a connection. Whether the repository has it is a different fact with a different next
     * move, and a single "Saved" would be true of every one of these and useful for none.
     */
    @Test
    fun `only a landed push claims the repository has it`() {
        val sent = dwChooserSaveNote(StagePush.Sent(StageSaveResultDto()), "This sketch")
        assertTrue(sent.contains("reached the repository"))
        assertTrue(dwChooserSaveNote(StagePush.AlreadySent, "This sketch").contains("already holds it"))

        // EVERY OTHER ANSWER IS A LOCAL SUCCESS WITH THE SENDING STILL OPEN, and none of them may
        // read as "the repository has this" — that is the promise this application must not make on
        // a phone's behalf.
        val open = listOf(
            StagePush.HeldBack(files = 2),
            StagePush.NoRemoteYet,
            StagePush.NothingToSend,
            StagePush.NotSent,
            null,
        )
        open.forEach { push ->
            val note = dwChooserSaveNote(push, "This prototype")
            assertTrue("$push must say the work is safe on the phone", note.contains("on this phone"))
            assertFalse(
                "$push must not claim the repository has it",
                note.contains("reached the repository") || note.contains("already holds it"),
            )
            assertTrue("$push must name the thing it is about", note.startsWith("This prototype"))
        }
        // A NULL PUSH IS THE REQUEST THAT THREW, and it is worded as the local success it is rather
        // than as a loss: the background pass owns the retry, and a sentence claiming failure would
        // send a designer to redo work that is already safe.
        assertEquals(dwChooserSaveNote(StagePush.NotSent, "x"), dwChooserSaveNote(null, "x"))
    }

    /**
     * THE INTERIM SENTENCE MAY NOT BORROW A VERDICT FROM A REQUEST THAT HAS NOT BEEN MADE.
     *
     * It is printed between `updateStage` returning and `pushStage` answering, and the tempting
     * shortcut — reusing the `NotSent` branch, which is the same shape — would tell a designer that
     * sending had failed before it had been attempted. That is the same class of untruth as drawing
     * a failed load as an empty list, one screen down.
     */
    @Test
    fun `the interim sentence claims the phone and does not judge the request`() {
        val interim = dwChooserSendingNote("This sketch")
        assertTrue(interim.contains("saved on this phone"))
        assertFalse(
            "the push has not been attempted yet and must not be reported as having failed",
            interim.contains("did not complete"),
        )
        assertFalse(interim.contains("reached the repository"))
        assertTrue("the ellipsis is the only promise it makes about the repository", interim.endsWith("…"))
        // AND IT IS NOT ANY OF THE FIVE VERDICTS. Sharing a string with one of them is how the two
        // states come to be indistinguishable on screen and then in the code.
        listOf(
            StagePush.Sent(StageSaveResultDto()),
            StagePush.AlreadySent,
            StagePush.HeldBack(files = 1),
            StagePush.NoRemoteYet,
            StagePush.NothingToSend,
            StagePush.NotSent,
        ).forEach { push ->
            assertFalse(
                "the interim sentence is the same string as $push's verdict",
                dwChooserSaveNote(push, "This sketch") == interim,
            )
        }
    }
}
