package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.ui.SelectCreateAction
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "NAME OF WORKSHOP" ON THE HANDSET — the creatable combo, and the four things that make it safe.
 *
 * ── WHAT THIS FIELD IS AND WHY IT GOT A CONTROL AT ALL ────────────────────────────────────────
 *
 * `workshopSetup.workshopTitle` is the design workshop's OWN name. It is promoted onto
 * `DesignWorkshop.title` by `PROMOTED_COLUMNS` and printed on the cover of a document a ministry
 * receives, and a stage entry is a frozen copy that nothing re-resolves — so "Bagru Block Print
 * Workshop 2025" and "Bagru block-printing workshop, 2025" are one fortnight to a reader and two
 * different strings to every group-by, and the one in the file is whichever was typed.
 *
 * The browser answered that with [DwWorkshopNameField]'s twin, `StageWorkshopNameField.tsx`, in the
 * same wave that put the same offer on this client's create dialog. It did not reach the STAGE field
 * on this client, so a designer moving between the two met two different controls for one box — and
 * met the plain one on the client they use in the courtyard, on the field the report cover reads.
 *
 * ── WHY A JUNIT TEST AND NOT A SCREENSHOT ─────────────────────────────────────────────────────
 *
 * `app/build.gradle.kts` carries no `ui-test-junit4` and no Robolectric, so the JVM suite cannot
 * compose a picker and look at it. Every ruling worth pinning was therefore lifted out of the
 * composable into a pure function it calls — the same trade `SearchableSelectEmptyStateTest`,
 * `dwSketchSourceFields` and the report planner already made in this app.
 *
 * ── THE FOUR THINGS BELOW, AND THE DEFECT EACH ONE HOLDS SHUT ─────────────────────────────────
 *
 * 1. [dwOwnWorkshopTitleRole] matches exactly one field in the shipped registry. A predicate that
 *    silently stops matching does not raise, warn or log: it returns false, the ordinary box is
 *    drawn, and the loss is invisible to everything except the designer. A predicate that starts
 *    matching a SECOND field is worse — it would put a list of workshop names on a box about
 *    something else.
 * 2. The create row NAMES THE TERM BACK, and draws nothing at all for an empty box. Without the
 *    first, a designer types a new name and is offered a button that does not say what it will
 *    store; without the second, a surface with no filter box would offer *Use “” as the name*.
 * 3. The name already on the workshop is ALWAYS a row. A picker that cannot draw its own current
 *    value reads as blank, and the obvious repair for a blank box is to answer it again — which on
 *    this field overwrites a true answer with a guess.
 * 4. The offer line states BOTH numbers when the page is cut, and says typing reaches the rest.
 *    R3 and R4: a narrowing nobody announced is absence reading as non-existence, and "Showing 80"
 *    alone leaves a reader guessing whether that is most of their workshops or a sixth of them.
 */
class DwWorkshopNameFieldTest {

    /** Matches the app's own decoder: the registry carries keys the DTOs here do not model. */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * THE SHIPPED ASSET AND NOT THE LIVE SERVER, for the reason `DwPhotoMeasureFieldTest` gives:
     * `design-workshop-schema.json` is what a handset renders from before it has ever had a
     * connection, so it is the copy that decides what a courtyard sees.
     */
    private val schema: SchemaResponse by lazy {
        val asset = File("src/main/assets/design-workshop-schema.json")
        assertTrue(
            "the bundled registry is missing — it is what the handset renders from on first launch",
            asset.exists()
        )
        json.decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
    }

    private val allLiveFields: List<Pair<String, FieldDto>>
        get() = schema.stages.flatMap { stage ->
            stage.entities.flatMap { ent: EntityDto -> ent.liveFields.map { "${ent.key}.${it.key}" to it } }
        }

    // ---------------------------------------------------------------------------------------
    // 1. The role
    // ---------------------------------------------------------------------------------------

    /**
     * A STANDING TRIPWIRE, read off the registry rather than off a memory of it.
     *
     * The browser's `ownWorkshopTitleRole` matches on the KEY ALONE and says why that is affordable:
     * `workshopTitle` is declared once, on `workshopSetup`, and the control refuses nothing, so the
     * worst outcome of a false positive is a box that offers some names beside it. "Declared once" is
     * a fact about the registry and not about the code, which is exactly the kind of fact that goes
     * stale without anything going red — the same way `interviewDocumentedAtWorkshop` arrived with
     * the sixth reference model while the web's sibling key list said "two".
     *
     * So this fails HERE, with the key named, on the day a second entity declares one.
     */
    @Test
    fun `the role matches exactly one field in the shipped registry`() {
        val matched = allLiveFields.filter { (_, field) -> dwOwnWorkshopTitleRole(field) }.map { it.first }
        assertEquals(
            "a second `workshopTitle` would put a list of design-workshop names on a box about " +
                "something else — read `dwOwnWorkshopTitleRole` before widening this",
            listOf("workshopSetup.workshopTitle"),
            matched
        )
    }

    /**
     * THE REFERENCE ROLE'S THREE KEYS ARE NOT THIS ONE, and must never become it.
     *
     * `stageFieldRoles.ts` rules that `documentedAtWorkshop`, `craftDocumentedAtWorkshop` and
     * `interviewDocumentedAtWorkshop` hold a REFERENCED RECORD's `Workshop` title, and that
     * `workshopSetup.workshopTitle` "is not a reference to a `Workshop` row at all". Two roles over
     * two different tables; folding them together would put a closed picker of `Workshop` rows on the
     * one box that has to accept a workshop nobody has filed yet.
     */
    @Test
    fun `the reference role's keys are not the workshop's own name`() {
        for (key in listOf(
            "documentedAtWorkshop",
            "craftDocumentedAtWorkshop",
            "interviewDocumentedAtWorkshop"
        )) {
            assertFalse(key, dwOwnWorkshopTitleRole(FieldDto(key = key, type = "TEXT")))
        }
    }

    /**
     * THE RAW TOKEN, NOT [com.designprototype.workshop.data.DwFieldType.of]'s answer.
     *
     * `of` degrades an unrecognised token to TEXT — deliberately, so one new server type cannot blank
     * 22 stages on a handset that has not updated — and a field whose type this build has never heard
     * of is not one this build should be putting a workshop list on. A deprecated field gets no input
     * at all, so it gets no control either.
     */
    @Test
    fun `the role reads the raw type token and skips a retired field`() {
        assertTrue(dwOwnWorkshopTitleRole(FieldDto(key = "workshopTitle", type = "TEXT")))
        assertFalse(dwOwnWorkshopTitleRole(FieldDto(key = "workshopTitle", type = "LONG_TEXT")))
        assertFalse(
            "an unknown token degrades to TEXT at render time and must not reach this control",
            dwOwnWorkshopTitleRole(FieldDto(key = "workshopTitle", type = "COLOUR_PICKER"))
        )
        assertFalse(
            dwOwnWorkshopTitleRole(FieldDto(key = "workshopTitle", type = "TEXT", deprecated = true))
        )
    }

    // ---------------------------------------------------------------------------------------
    // 2. The create row
    // ---------------------------------------------------------------------------------------

    /**
     * THE ROW SAYS WHAT IT WILL STORE, IN QUOTATION MARKS AND WITHOUT PARAPHRASE.
     *
     * The browser's rule, kept word for word: a reader has to be able to see the exact string that
     * would be stored — the capitals, the punctuation, the double space they did not mean to type —
     * and a summary is the one shape that cannot show them. Trimmed on the way in AND on the way out
     * of the quotes, so what is shown is byte-identical to what is committed: quoting the raw box
     * while storing a trimmed one shows a name with a trailing space and stores one without.
     */
    @Test
    fun `the create row quotes the term it will store`() {
        assertEquals("Use “Bagru winter 2026” as the name", dwWorkshopNameCreateLabel("Bagru winter 2026"))
        assertEquals("Use “Bagru winter 2026” as the name", dwWorkshopNameCreateLabel("  Bagru winter 2026 "))
        // "Use" and never "Create": nothing is created by answering this box.
        assertFalse(dwWorkshopNameCreateLabel("Ajrakh")!!.startsWith("Create"))
    }

    /**
     * NOTHING TYPED, NOTHING OFFERED — and this is also what keeps the row off the anchored menu.
     *
     * [SelectCreateAction]'s contract is that a `null` label draws no row. The anchored menu has no
     * filter box at all and therefore asks with `""`, so a term-aware action answers null there and
     * a fixed-label one ("Create a new artisan") answers as it always did. Both halves matter: a
     * button reading *Use “” as the name* can only do harm, and a record-making action must go on
     * being offered before a letter is typed.
     */
    @Test
    fun `an empty box draws no create row, and a fixed label still does`() {
        assertNull(dwWorkshopNameCreateLabel(""))
        assertNull(dwWorkshopNameCreateLabel("   "))

        val fixed = SelectCreateAction("Create a new artisan") { }
        assertEquals("Create a new artisan", fixed.label(""))
        assertEquals("Create a new artisan", fixed.label("Ram Kumar"))
    }

    // ---------------------------------------------------------------------------------------
    // 3. The options
    // ---------------------------------------------------------------------------------------

    /**
     * THE NAME ALREADY ON THIS WORKSHOP IS ALWAYS A ROW, AND IT IS FIRST.
     *
     * One page is at most eighty titles ordered newest first, so a workshop named two seasons ago is
     * very often not among them. A picker that cannot draw its own value reads as blank, and the
     * obvious repair for a blank box is to answer it again. The hint says where the row came from so
     * nobody reads their own answer as a workshop the server has just offered.
     */
    @Test
    fun `the stored name is always the first row, with a hint saying where it came from`() {
        val rows = dwWorkshopNameOptions("Kutch spring sitting", listOf("Ajrakh 2026", "Bagru 2025"))
        assertEquals(listOf("Kutch spring sitting", "Ajrakh 2026", "Bagru 2025"), rows.map { it.label })
        assertEquals("already on this workshop", rows.first().hint)
        // The value stored is the bare title — never the label plus its hint.
        assertEquals("Kutch spring sitting", rows.first().value)
    }

    /** A stored name that IS on offer is not drawn twice: one answer, one row. */
    @Test
    fun `a stored name already on offer is not duplicated`() {
        val rows = dwWorkshopNameOptions("Ajrakh 2026", listOf("Ajrakh 2026", "Bagru 2025"))
        assertEquals(listOf("Ajrakh 2026", "Bagru 2025"), rows.map { it.label })
        assertNull("the offered row keeps its own shape", rows.first().hint)
    }

    /** An unanswered field adds no row of its own. */
    @Test
    fun `an empty field adds no row`() {
        assertEquals(listOf("Ajrakh 2026"), dwWorkshopNameOptions("  ", listOf("Ajrakh 2026")).map { it.label })
    }

    /**
     * DEDUPLICATED, TRIMMED, AND IN THE SERVER'S ORDER.
     *
     * Only the NAME is stored, so two workshops sharing a title are one answer; offering it twice is
     * a control that appears to distinguish two answers it cannot. The order is never re-sorted —
     * `GET /design-workshops` answers newest first, which is the workshop a designer naming one today
     * almost always means, and alphabetical would bury this season's between two from 2019.
     */
    @Test
    fun `the offer is deduplicated and keeps the server's order`() {
        val offer = dwWorkshopNamesOnRecord(
            listOf(" Bagru 2026 ", "Ajrakh 2026", "Bagru 2026", "", "   "),
            maxLength = 0
        )
        assertEquals(listOf("Bagru 2026", "Ajrakh 2026"), offer.names)
        assertEquals(0, offer.withheld)
    }

    /**
     * A NAME THE FIELD COULD NOT STORE IS WITHHELD AND COUNTED, never silently dropped.
     *
     * `DwValues.coerce` refuses an over-length string, so offering one would offer an option that
     * turns the row into a refused answer on save. It cannot happen with today's titles; the sentence
     * exists because the day it does, a designer must not be left hunting for a name that is on
     * screen nowhere and refused by nothing.
     */
    @Test
    fun `an over-length name is withheld and said out loud`() {
        val offer = dwWorkshopNamesOnRecord(listOf("Ajrakh 2026", "A".repeat(40)), maxLength = 20)
        assertEquals(listOf("Ajrakh 2026"), offer.names)
        assertEquals(1, offer.withheld)
        assertEquals("1 name not offered: over 20 characters.", dwWorkshopNamesWithheldLine(1, 20))
        assertEquals("2 names not offered: over 20 characters.", dwWorkshopNamesWithheldLine(2, 20))
        assertNull(dwWorkshopNamesWithheldLine(0, 20))
    }

    /** `maxLength = 0` is "the registry declared no bound", never "nothing may be stored". */
    @Test
    fun `no declared bound withholds nothing`() {
        val offer = dwWorkshopNamesOnRecord(listOf("A".repeat(400)), maxLength = 0)
        assertEquals(1, offer.names.size)
        assertEquals(0, offer.withheld)
    }

    // ---------------------------------------------------------------------------------------
    // 4. The sentence under the box
    // ---------------------------------------------------------------------------------------

    /**
     * WHAT THE LIST IS (R3) AND WHAT IT LEFT OUT (R4), and that typing reaches the rest.
     *
     * A designer cannot tell "the workshops I can open" from "every workshop there is" by looking at
     * a dropdown. The type clause is printed only when a type is chosen, because a sentence about a
     * narrowing that is not applied is a sentence about nothing. The cut prints BOTH numbers or
     * neither — the browser's rule and this repository's, everywhere.
     */
    @Test
    fun `the offer line names the scope, the escape and both numbers`() {
        assertEquals(
            "Names from workshops you can open. Type a new one if it is not here.",
            dwWorkshopNameOfferLine(workshopKind = "", shown = 12, total = 12)
        )
        assertEquals(
            "Names from workshops of this type. Type a new one if it is not here.",
            dwWorkshopNameOfferLine(workshopKind = "DESIGN_INTERVENTION", shown = 12, total = 12)
        )
        assertEquals(
            "Names from workshops you can open. Type a new one if it is not here. Showing 80 of 196.",
            dwWorkshopNameOfferLine(workshopKind = "", shown = 80, total = 196)
        )
    }

    /**
     * NO CUT SENTENCE WHERE THERE IS NO CUT, and none from a read that has not answered.
     *
     * `total` is 0 while the state is Loading or Failed, and "Showing 0 of 0" over a list that is
     * still coming is a truncation claim made from a read that never arrived — rule 10 in the one
     * place this control could still get it wrong.
     */
    @Test
    fun `an unanswered read states no cut`() {
        assertFalse(dwWorkshopNameOfferLine("", shown = 0, total = 0).contains("Showing"))
        assertFalse(dwWorkshopNameOfferLine("", shown = 0, total = 196).contains("Showing"))
        assertFalse(dwWorkshopNameOfferLine("", shown = 12, total = 0).contains("Showing"))
    }
}
