package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwFieldType
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.entity
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.ui.joinNumbered
import com.designprototype.workshop.ui.numberedRowsAfterEdit
import com.designprototype.workshop.ui.splitNumbered
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WHICH BOXES ON THE STAGE FORM ARE LISTS, AND WHICH ONE IS A NUMBER PAD — pinned against the
 * bundled registry rather than against a memory of it.
 *
 * ── WHY A TEST FOR AN AFFORDANCE ──────────────────────────────────────────────────────────────
 *
 * Both behaviours this file covers are reached by a PREDICATE over a declaration, and a predicate
 * that stops matching does not raise, warn or log. It returns false, the ordinary control is drawn,
 * and the loss is invisible to everything except the designer — who has no way to know the app ever
 * intended to offer them anything else. That is exactly how `participant.dos` came to be a single
 * undifferentiated textarea on this surface while the artisan record form two taps away collected
 * the same fact as numbered points: nothing was broken, so nothing said anything.
 *
 * IT PINS THE SHIPPED ASSET AND NOT THE LIVE SERVER, for the reason `DwPhotoMeasureFieldTest`
 * gives: `design-workshop-schema.json` is what a handset renders from before it has ever had a
 * connection, so it is the copy that decides what a courtyard sees.
 *
 * ── WHAT IT DOES NOT CLAIM ────────────────────────────────────────────────────────────────────
 *
 * Nothing here composes anything. A unit test cannot see that the numbered rows were drawn; what it
 * can see is that the registry still says what [FieldRenderer]'s LONG_TEXT arm and
 * [dwNumericTextField] read, that the stored-string codec both surfaces share still round-trips, and
 * that one keystroke in one row still produces the rows it should. Those are the three things that go
 * quietly wrong.
 *
 * THAT THIRD ONE IS ASSERTABLE ONLY BECAUSE THE RULE WAS LIFTED OUT OF THE LAMBDA. `numberedRowsAfterEdit`
 * exists as a function so the Enter-splits-a-row behaviour and the row-count lock that protects an
 * in-flight dictation can be checked here rather than by reading a `TextField` callback nothing in this
 * project can compose. The greying of the microphones and of the remove button is still reading-only,
 * and is stated as such in the report rather than claimed as covered.
 */
class DwBulletListFieldTest {

    /** Matches the app's own decoder: the registry carries keys the DTOs here do not model. */
    private val json = Json { ignoreUnknownKeys = true }

    private val schema: SchemaResponse by lazy {
        val asset = File("src/main/assets/design-workshop-schema.json")
        assertTrue(
            "the bundled registry is missing — it is what the handset renders from on first launch",
            asset.exists()
        )
        json.decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
    }

    private fun entityOf(stageKey: String, entityKey: String): EntityDto =
        schema.stages.firstOrNull { it.key == stageKey }?.entity(entityKey)
            ?: throw AssertionError("the registry declares no `$stageKey`.`$entityKey`")

    private val allLiveFields: List<Pair<String, FieldDto>>
        get() = schema.stages.flatMap { stage ->
            stage.entities.flatMap { ent -> ent.liveFields.map { "${ent.key}.${it.key}" to it } }
        }

    /** Exactly what [FieldRenderer]'s LONG_TEXT arm routes to the numbered control: the role, not a key. */
    private val bulletedLongText: List<Pair<String, FieldDto>>
        get() = allLiveFields
            .filter { (_, field) -> DwFieldType.of(field.type) == DwFieldType.LONG_TEXT }
            .filter { (_, field) -> field.reportRole == "BULLETS" }

    /**
     * Every LONG_TEXT field the report prints as bullets, named rather than counted.
     *
     * These four are what [FieldRenderer]'s LONG_TEXT arm draws through the record form's numbered
     * control. Named because a rename or a `report_role` change takes the control away in silence,
     * and because the list is short enough that a reader can check each one against its own help
     * text — all four say "one per line" or "one point per line", which is the promise the control
     * keeps and the plain textarea did not.
     *
     * DELIBERATELY NOT AN EXACT-SET ASSERTION. The rule is the ROLE and not the key, so a BULLETS
     * field added on the server gets the control with no client change — which is the property worth
     * having, and an equality assertion here would turn every such addition into a red Android build
     * for a feature that had just correctly widened. So the four are asserted PRESENT, and the
     * over-match direction is covered instead by the promise check below, which every member of the
     * set has to keep however large the set grows.
     */
    @Test
    fun `the registry's bulleted long-text fields include the four this surface was written for`() {
        val bulleted = bulletedLongText.map { it.first }
        listOf(
            "participant.dos",
            "participant.donts",
            "traditionalProcess.documentedSteps",
            "tool.usedByArtisans",
        ).forEach { key ->
            assertTrue("$key must still be a bulleted LONG_TEXT field — it is $bulleted", key in bulleted)
        }
    }

    /**
     * Every bulleted box tells the designer that a line is a point.
     *
     * THIS IS THE ONE THAT CATCHES A WRONG MEMBER OF THE SET. The control the LONG_TEXT arm draws for
     * these fields affords one point per row and stores them newline-joined, and `report_builder`
     * splits them the same way — so a BULLETS field whose help does NOT say "per line" is either a
     * field that should not be BULLETS, or help text that is not telling the designer what the report
     * will do with their paragraph. All four the feature was written for say it in their own words.
     *
     * A NEW BULLETS FIELD FAILING HERE IS A QUESTION, NOT A VERDICT: either amend its help or reopen
     * the role. Do not "fix" it by loosening this test, which would leave the control affording a
     * structure the box never promised.
     */
    @Test
    fun `a bulleted long-text field says in its help that one line is one point`() {
        bulletedLongText.forEach { (name, field) ->
            assertTrue(
                "$name is printed as bullets but its help never says a line is a point: “${field.help}”",
                field.help.lowercase().contains("per line")
            )
        }
    }

    /**
     * The plain narrative boxes are NOT turned into lists.
     *
     * The refusal is tested as hard as the offer, because the failure is the opposite shape and just
     * as quiet: a narrative field drawn as one-line numbered rows would invite a designer to break a
     * paragraph across bullets, and `report_builder` would then print the paragraph as a list. Stage
     * 5's own `processStep.description` is the case that matters — it is a TABLE_COLUMN whose lines
     * are deliberately flattened, so rows would be actively wrong there.
     */
    @Test
    fun `a long-text field with any other report role keeps the plain box`() {
        val description = entityOf("TRADITIONAL_PROCESS_BASELINE", "processStep").liveFields
            .firstOrNull { it.key == "description" }
            ?: throw AssertionError("stage 5's processStep no longer declares `description`")
        assertEquals("LONG_TEXT", description.type)
        assertFalse(
            "processStep.description is a TABLE_COLUMN — numbered rows would print a paragraph as a list",
            description.reportRole == "BULLETS"
        )
    }

    /**
     * The stored form is one string, newline-joined, and it survives a round trip unchanged.
     *
     * THIS CODEC IS A THREE-WAY CONTRACT and that is the whole reason it is shared rather than
     * reimplemented: the record API writes it, this pair reads it back into rows on both the artisan
     * form and the stage form, and `report_builder` splits it one bullet per line. A blank row that
     * survived the join would print as an empty bullet in a ministry document; a trailing newline
     * that survived would make the SAME answer compare unequal to itself across a save.
     */
    @Test
    fun `the numbered-points codec round-trips and drops blank rows`() {
        assertEquals(listOf("do not wash hot", "dry in shade"), splitNumbered("do not wash hot\ndry in shade"))
        assertEquals("do not wash hot\ndry in shade", joinNumbered(listOf("do not wash hot", "dry in shade")))
        // The trailing row a designer is looking at the moment they press "Add point".
        assertEquals("a\nb", joinNumbered(listOf("a", "b", "")))
        assertEquals("a\nb", joinNumbered(listOf(" a ", "b", "   ")))
        // An empty answer stays empty rather than becoming a one-blank-bullet list.
        assertEquals("", joinNumbered(listOf("")))
        assertEquals("", joinNumbered(emptyList()))
        // Always at least one row, so the control has a box to draw even with nothing stored.
        assertEquals(listOf(""), splitNumbered(null))
        assertEquals(listOf(""), splitNumbered(""))
        // Idempotent: what the control writes is what it reads.
        val stored = "one\ntwo\nthree"
        assertEquals(stored, joinNumbered(splitNumbered(stored)))
    }

    /**
     * ENTER MAKES A NEW BULLET, AND A PASTE FULL OF NEWLINES MAKES SEVERAL.
     *
     * The affordance the whole LONG_TEXT/BULLETS arm exists for: a designer types a point and presses
     * Enter for the next, rather than being handed one box and the words "one point per line". The
     * text before the break stays where it was and the remainder becomes rows under it — trimmed,
     * because a pasted block arrives with indentation that is not part of any point.
     */
    @Test
    fun `enter inside a row pushes the remainder into new bullets`() {
        val rows = listOf("do not wash hot", "dry in shade")
        // An ordinary keystroke, untrimmed — trimming here would eat the space between two words as
        // the designer types it.
        assertEquals(listOf("do not wash hot ", "dry in shade"), numberedRowsAfterEdit(rows, 0, "do not wash hot "))
        // Enter at the end of row 0.
        assertEquals(
            listOf("do not wash hot", "", "dry in shade"),
            numberedRowsAfterEdit(rows, 0, "do not wash hot\n")
        )
        // A pasted block lands as one bullet per line, in order, under the row it was pasted into.
        assertEquals(
            listOf("one", "two", "three", "dry in shade"),
            numberedRowsAfterEdit(rows, 0, "one\n  two  \nthree")
        )
        // An index nothing declares answers the list unchanged rather than throwing — a crash on a
        // keystroke is not a defensible way to find out a caller was wrong.
        assertEquals(rows, numberedRowsAfterEdit(rows, 7, "anything"))
    }

    /**
     * **WHILE A ROW IS DICTATING, NOTHING MAY CHANGE THE ROW COUNT — AND THIS IS THE ASSERTION THAT
     * SAYS SO.**
     *
     * A row's identity in `NumberedListInput` is its INDEX: the loop is `rows.forEachIndexed` with no
     * `key()`, and `DwDictationButton` is handed that index when it is created. So an insert above the
     * dictating row shifts its data down while the in-flight button keeps its position, and the commit
     * merges the spoken sentence into a point the designer never opened — verbatim the failure that
     * button's own comment names, *"which for a collection row means writing into whichever row was
     * open when the recogniser started"*. `rememberUpdatedState` does not help: it keeps the lambda
     * current, and the current lambda faithfully writes to the wrong point.
     *
     * THE CHARACTERS ARE ALL KEPT. Folding the break to a space rather than dropping the tail is the
     * difference between a designer pressing Enter again in a second and a designer losing what they
     * typed — and losing it silently, which is the shape this repository keeps re-fixing.
     *
     * THIS IS THE TEST THAT FAILS IF THE LOCK IS REMOVED, which is the point of writing it: without
     * the `rowsLocked` branch the first assertion below returns three rows instead of two.
     */
    @Test
    fun `a locked row set never grows or shifts under an in-flight dictation`() {
        val rows = listOf("soaking", "printing")
        val locked = numberedRowsAfterEdit(rows, 0, "soaking\nrinsing", rowsLocked = true)
        // The row count may not change while a recogniser is running.
        assertEquals(rows.size, locked.size)
        // Folded to a space, not dropped: every character the designer typed survives.
        assertEquals(listOf("soaking rinsing", "printing"), locked)
        // And the row that was NOT edited is untouched, so no later index moves.
        assertEquals("printing", locked[1])
        // Unlocked, the same keystroke splits — so the lock is what changed the answer and not the
        // input. Without this pair the assertions above would also pass on a control that had simply
        // stopped splitting at all.
        assertEquals(
            listOf("soaking", "rinsing", "printing"),
            numberedRowsAfterEdit(rows, 0, "soaking\nrinsing", rowsLocked = false)
        )
        // A locked ordinary keystroke is completely unaffected: the lock is about the row COUNT, not
        // about typing, and a designer may go on editing another point while one is dictating.
        assertEquals(listOf("soaking", "printing now"), numberedRowsAfterEdit(rows, 1, "printing now", rowsLocked = true))
    }

    /**
     * The number pad, and the microphone taken off the same three boxes.
     *
     * [dwNumericTextField] is the one predicate on this surface that reads a KEY rather than a
     * declaration, which is why it is pinned here by name in both directions. Over-matching is the
     * expensive direction: a name or a place behind a number pad is a box a designer cannot answer.
     */
    @Test
    fun `the pincode boxes get the number pad and nothing shaped unlike one does`() {
        val numeric = allLiveFields.filter { (_, field) -> dwNumericTextField(field) }.map { it.first }
        listOf(
            "participant.pincode",
            "tool.recordPincode",
            "existingProduct.recordPincode",
        ).forEach { key ->
            assertTrue("$key must open the number pad — it is $numeric", key in numeric)
        }
        // Not an equality assertion, for the reason the bulleted test gives: a field NAMED `pincode`
        // that appears later is a PIN code, and the predicate answering "numeric" for it is correct
        // rather than a regression. What must not happen is a box of some other shape being swept in,
        // which is what the named refusals below and the key set's own exactness cover.
        numeric.forEach { name ->
            assertTrue(
                "$name is not a pincode-shaped key and must not be forced onto the number pad",
                name.lowercase().endsWith("pincode")
            )
        }
    }

    /**
     * The neighbours on the very same address block keep the ordinary keyboard.
     *
     * Named individually rather than left to the count above, because these are the fields a
     * key-pattern would have swept up if it had been written as a pattern — and a state or a
     * district behind a number pad is unanswerable, not merely inconvenient.
     */
    @Test
    fun `the address boxes beside a pincode are not numeric`() {
        val tool = entityOf("TRADITIONAL_PROCESS_BASELINE", "tool").liveFields.associateBy { it.key }
        listOf("recordState", "recordDistrict", "recordVillage", "name", "place").forEach { key ->
            val field = tool[key] ?: throw AssertionError("stage 5's tool no longer declares `$key`")
            assertFalse("tool.$key must keep the ordinary keyboard", dwNumericTextField(field))
        }
    }

    /**
     * A non-TEXT field never qualifies, whatever it is called.
     *
     * The type test is first in the predicate for a reason: a DECIMAL already gets the decimal pad
     * from its own arm of the renderer, and answering "numeric" for it here would mean two different
     * arms both claiming to choose the keyboard.
     */
    @Test
    fun `the type test comes before the key test`() {
        assertFalse(dwNumericTextField(FieldDto(key = "pincode", type = "DECIMAL")))
        assertFalse(dwNumericTextField(FieldDto(key = "pincode", type = "LONG_TEXT")))
        assertTrue(dwNumericTextField(FieldDto(key = "pincode", type = "TEXT")))
        // Case-folded, because the registry writes `recordPincode` and a future one might not.
        assertTrue(dwNumericTextField(FieldDto(key = "RecordPincode", type = "TEXT")))
        assertFalse(dwNumericTextField(FieldDto(key = "pincodeNotes", type = "TEXT")))
    }
}
