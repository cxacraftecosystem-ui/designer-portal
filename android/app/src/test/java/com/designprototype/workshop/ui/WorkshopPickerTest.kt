package com.designprototype.workshop.ui

import com.designprototype.workshop.data.DW_LOCAL_DESIGN_WORKSHOPS
import com.designprototype.workshop.data.DW_LOCAL_FIELD_WORKSHOPS
import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.DesignWorkshopPageDto
import com.designprototype.workshop.data.DwReferenceStore
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.UNFILED_BY_CHOICE
import com.designprototype.workshop.data.UNFILED_NO_OPTIONS
import com.designprototype.workshop.data.WorkshopDetailDto
import com.designprototype.workshop.data.WorkshopTypeOptionDto
import com.designprototype.workshop.data.dwCachedAllottedWorkshops
import com.designprototype.workshop.data.dwLoadAllottedDesignWorkshops
import com.designprototype.workshop.data.dwLocalDesignWorkshopToOption
import com.designprototype.workshop.data.dwLocalFieldWorkshopToOption
import com.designprototype.workshop.data.dwLocalStored
import com.designprototype.workshop.data.dwLocalWorkshopKey
import com.designprototype.workshop.data.unfiledLinkReason
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * TWO DROPDOWNS, NEVER THREE — the handset half of the owner's ruling, pinned.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS FILE IS FOR, AND WHAT IT REPLACES
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `DesignWorkshopPickerTest` pins the THREE-control design this release removes: an ordinary
 * `Workshop` picker, a `WORKSHOP_KIND` box that narrowed a second picker and saved nothing, and that
 * second picker. Every ruling in it that is still true is re-asserted here against the control that
 * exists now; the ones that are not are the point of the change. **That file is outside this change's
 * slice and is therefore still in the tree, still asserting the old shape.** It should be deleted
 * with `frontend/e2e/design-workshop-cascade-unit.spec.ts`, its browser twin — see the report that
 * accompanied this change, which names the assertions in it that now fail and why each one had to.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE SEVEN RULINGS, AND WHERE EACH IS ASSERTED
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 *  R2  TWO DROPDOWNS. §1 sweeps `MainActivity.kt` for a form that grew a box of its own and for the
 *      mounts of the control; §5 pins the labels against the browser's.
 *  R3  WHERE IT SAVES. §2 — the destination is the row's FLAG, at most one column is ever non-null,
 *      and the type is never in a payload.
 *  R4  DEFAULTS. §3 — a designer opens on Design & Prototype, everyone else on the first ordinary
 *      type, and an EXISTING record opens on the column it already names whatever either of those
 *      would have said.
 *  R5  "NOT LINKED" SURVIVES. §4.
 *  R1/R7 are not this control's to assert: both tables staying is a schema fact, and one save path
 *      is `test_workshop_creation_rights.py`'s and the record routes'.
 *
 * ── WHY SO MUCH OF IT READS SOURCE ─────────────────────────────────────────────────────────────
 *
 * `app/build.gradle.kts` carries no `ui-test-junit4` and no Robolectric, so the JVM suite cannot
 * compose a picker and look at it — the same constraint `WorkshopOptionsTest`, `RecordPickersTest`
 * and `SearchableSelectEmptyStateTest` open with, and the same answer: the rulings that can be pure
 * functions are asserted as pure functions, and the ones that are WIRING — which form mounts what,
 * which value reaches which body — are asserted against the source, which is where wiring lives.
 */
class WorkshopPickerTest {

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 1. R2 — TWO DROPDOWNS, NEVER THREE, AND ONE MOUNT PER FORM
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * The five forms that carry BOTH id columns mount the pair, and each mounts it exactly once.
     *
     * The field repository shipped its tracer form by form, missed four of nine mounts, and a
     * researcher reported the feature as simply absent. A control on four of five forms is that bug
     * again, and the odd one out reads as the feature being gone rather than as one screen differing.
     *
     * CraftForm and AndroidMediaForm are deliberately NOT in this list and each says why at its own
     * mount: `Craft` has no `designWorkshopId` and a media row has no `workshopId`, so neither has a
     * second destination for a type box to route to. A type box with one reachable answer is the
     * complexity R2 forbids, not the parity it asks for.
     */
    @Test
    fun `the pair is on exactly the five forms whose records carry both columns`() {
        val expected = listOf(
            "ArtisanForm", "ProcessForm", "ProductForm", "QuestionnaireForm", "ToolForm",
        )
        assertEquals(
            "the forms mounting RecordWorkshopField are no longer the five whose tables carry both " +
                "workshopId and designWorkshopId",
            expected,
            enclosingFunctionsCalling("RecordWorkshopField(").sorted(),
        )
        assertEquals(
            "a form is building the control out of parts instead of mounting it",
            expected,
            enclosingFunctionsCalling("rememberRecordWorkshopLink(").sorted(),
        )
    }

    /**
     * AND THE TWO HALVES ARE MOUNTED NOWHERE ELSE — except the two screens that have only one.
     *
     * `WorkshopField` and `DesignWorkshopField` are drawn by [RecordWorkshopField] and by the two
     * single-destination screens. A sixth caller would be a record form that had quietly gone back to
     * picking a table for itself, with its own default and its own idea of which column to clear.
     */
    @Test
    fun `neither half is mounted outside the control and the two single-destination screens`() {
        assertEquals(
            "WorkshopField is mounted somewhere that is not the control or CraftForm",
            listOf("CraftForm", "RecordWorkshopField"),
            enclosingFunctionsCalling("WorkshopField(state = ").sorted(),
        )
        assertEquals(
            "DesignWorkshopField is mounted somewhere that is not the control or AndroidMediaForm",
            listOf("AndroidMediaForm", "RecordWorkshopField"),
            enclosingFunctionsCalling("DesignWorkshopField(").sorted(),
        )
    }

    /**
     * NO FORM GROWS A TYPE BOX OF ITS OWN.
     *
     * The whole argument for putting the type in one state object is that a decision with one right
     * answer and several wrong ones should be made once. A form that built its own "Type of workshop"
     * control would be making it a sixth time, with its own default, its own idea of what a type
     * change does to a chosen workshop, and nothing forcing the six to agree.
     */
    @Test
    fun `no record form grows a type box of its own`() {
        assertFalse(
            "MainActivity.kt has grown its own \"Type of workshop\" control",
            withoutComments(MAIN).contains("\"Type of workshop\""),
        )
    }

    /**
     * AND THE THIRD BOX IS GONE FROM THE PICKER ITSELF.
     *
     * The retired control narrowed its list with a `workshopKind` query parameter. R2's third box
     * went with the ruling, and so did the parameter: the type's narrowing IS the choice of table.
     * Sending it as well would show a designer the 24 rows of a 13,871-row register that happen to
     * carry the token, and say nothing about the rest.
     */
    @Test
    fun `the record form picker sends no workshopKind to the server`() {
        val code = withoutComments(PICKER)
        assertFalse(
            "the record-form picker is narrowing the design workshop list by workshopKind again. " +
                "The type chooses the TABLE; DesignWorkshop.workshopKind is NULL on 13,847 of " +
                "13,871 rows, so that filter answers \"none of this type\" about a full register.",
            code.contains("workshopKind ="),
        )
        // The word survives in this file exactly once, and it is the registry vocabulary the OFFLINE
        // FLOOR is authored from — `workshopKindOptions`, off the bundled asset, never off the wire.
        assertEquals(
            "workshopKind is being read somewhere other than the offline floor",
            1,
            Regex("workshopKind").findAll(code).count(),
        )
        assertTrue(code.contains("workshopKindOptions(schema)"))
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 2. R3 — WHERE IT SAVES: THE FLAG DECIDES, AND ONLY ONE COLUMN IS EVER FILLED
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * The routing is read off the ROW'S FLAG and never off its key.
     *
     * `lib/workshopTypes.ts` is emphatic about this and the reason is not hypothetical: nothing in
     * the database constrains "exactly one true row", deliberately, so that an administrator can
     * announce a second design-workshop-backed programme without a migration on every deployment and
     * without either client shipping. A `key == "DESIGN_PROTOTYPE_DEVELOPMENT"` test in a picker is
     * the bug that decision exists to permit avoiding.
     */
    @Test
    fun `a second design-workshop-backed type routes correctly without any client change`() {
        val types = listOf(
            type("DESIGN_PROTOTYPE_DEVELOPMENT", 10, routes = true),
            type("SKILL_UPGRADATION", 20, routes = false),
            // The administrator added this one this morning. No client has shipped since.
            type("PROTOTYPE_RESIDENCY", 25, routes = true),
        )
        assertTrue(routesToDesignWorkshopFor(types, "PROTOTYPE_RESIDENCY", storedRouting = null))
        assertFalse(routesToDesignWorkshopFor(types, "SKILL_UPGRADATION", storedRouting = null))
    }

    /**
     * THE ONE PLACE A KEY MAY APPEAR IS THE OFFLINE FLOOR, AND IT APPEARS EXACTLY THERE.
     *
     * A floor row has no server to read a flag from and somebody has to author it; a floor with the
     * flag false on all six would silently take the design branch off every offline record form,
     * which is the branch a designer in a courtyard most needs. So the constant is used once, in
     * [workshopTypeFloor], and the moment a served list arrives the floor is replaced whole.
     */
    @Test
    fun `the design key decides nothing except how the offline floor is authored`() {
        val code = withoutComments(PICKER)
        val uses = Regex("DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY").findAll(code).count()
        assertEquals(
            "DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY is being read somewhere new. It may be DECLARED, " +
                "used once to author the offline floor, and returned once to name a programme an " +
                "administrator has retired — and nowhere else, because routing is the per-row flag. " +
                "It was FOUR until 2026-09-16: the fourth was the retired cascade's " +
                "DEFAULT_WORKSHOP_KIND alias, which existed only so that DesignWorkshopPickerTest " +
                "would keep COMPILING while it asserted the three-control design. That file was " +
                "deleted with the design it pinned, and the alias went with it, exactly as its own " +
                "doc comment said it should.",
            3,
            uses,
        )
    }

    /**
     * AT MOST ONE OF THE TWO COLUMNS IS EVER NON-NULL — asserted on the source of the two accessors,
     * because they are two lines and the whole of R3.
     */
    @Test
    fun `the type names one destination and nulls the other`() {
        val code = withoutComments(MAIN)
        assertTrue(
            "RecordWorkshopLink.workshopId() no longer refuses the ordinary column under a design type",
            code.contains("fun workshopId(): String? = if (routesToDesignWorkshop) null else field.value()"),
        )
        assertTrue(
            "RecordWorkshopLink.designWorkshopId() no longer refuses the design column under an " +
                "ordinary type",
            code.contains("fun designWorkshopId(): String? = if (routesToDesignWorkshop) design.value() else null"),
        )
    }

    /**
     * AND NO FORM REACHES PAST THE CONTROL FOR EITHER ID.
     *
     * The day a save handler calls `design.value()` or `field.value()` directly is the day one form
     * starts writing both columns, and nothing on screen would say which workshop the record belongs
     * to. The two exceptions are the two single-destination screens, which have no routing to honour.
     */
    @Test
    fun `no save handler reaches past the control for an id`() {
        val code = withoutComments(MAIN)
        assertFalse(
            "a form is reading link.design.value() instead of link.designWorkshopId()",
            code.contains("link.design.value()"),
        )
        assertFalse(
            "a form is reading link.field.value() instead of link.workshopId()",
            code.contains("link.field.value()"),
        )
    }

    /**
     * THE TYPE IS NEVER SAVED. It is not on any request model and no form can read it as one.
     *
     * A record's type is a fact about the workshop it is filed under — `DesignWorkshop.workshopKind`,
     * answered in stage 1 — so a second copy beside the record disagrees with its own source the
     * first time somebody corrects that stage, and nothing would ever read the two together to
     * notice. The day a body grows a `workshopType` or a `workshopKind` is the day a lens became a
     * column.
     */
    @Test
    fun `no record body carries the chosen type`() {
        val code = withoutComments(MAIN)
        for (leak in listOf("link.type.typeKey", "workshopType = link", "workshopKind = link")) {
            assertFalse("a record form is sending the type: $leak", code.contains(leak))
        }
    }

    /**
     * THE COLUMN THE RECORD IS NOT FILED IN IS CLEARED ONLY WHEN IT HELD SOMETHING.
     *
     * This is the one rule with no browser twin, because a browser has no outbox. A non-blank
     * baseline over a null value is a real un-filing — somebody moved the type box off the column
     * their record was filed in — and it has to reach the server as an explicit null or the save
     * answers 200 and changes nothing. A BLANK baseline is not a clearance of anything, and naming
     * it would manufacture a decision nobody took.
     */
    @Test
    fun `the displaced column is cleared only when it held a workshop`() {
        val code = withoutComments(MAIN)
        assertTrue(
            "RecordWorkshopLink.displaced no longer guards on the baseline, so a form is claiming a " +
                "clearance for a column that was already empty",
            code.contains("if (baselineId.isNotBlank()) UNFILED_BY_CHOICE else null"),
        )
        // And the rule the two pickers share is untouched by any of this.
        assertEquals(UNFILED_BY_CHOICE, unfiledLinkReason(selectedId = "", baselineId = "w1", hadOptions = false))
        assertEquals(UNFILED_NO_OPTIONS, unfiledLinkReason(selectedId = "", baselineId = "", hadOptions = false))
        assertNull(unfiledLinkReason(selectedId = "w1", baselineId = "", hadOptions = true))
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 3. R4 — WHICH TYPE THE BOX OPENS ON
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /** A DESIGNER opens a new record on Design & Prototype. The owner's ruling, stated. */
    @Test
    fun `a designer opens a new record on the design and prototype type`() {
        assertEquals(
            "DESIGN_PROTOTYPE_DEVELOPMENT",
            openingWorkshopTypeKey(SEEDED, storedRouting = null, preferDesignWorkshops = true),
        )
    }

    /**
     * AND EVERYBODY ELSE OPENS ON THE FIRST TYPE IN THE ADMINISTRATOR'S ORDER.
     *
     * Not null, and not an empty box. A form that opened with no type selected would draw an empty
     * workshop box, which reads as *there are no workshops* rather than as *choose a type first*.
     */
    @Test
    fun `everybody else opens on the first type in the administrator's order`() {
        assertEquals(
            "DESIGN_PROTOTYPE_DEVELOPMENT",
            openingWorkshopTypeKey(SEEDED, storedRouting = null, preferDesignWorkshops = false),
        )
        // …which on the seeded list happens to be the same row. Move it and the answers separate.
        val reordered = SEEDED.map { if (it.key == "DESIGN_PROTOTYPE_DEVELOPMENT") it.copy(sortOrder = 99) else it }
        assertEquals(
            "SKILL_UPGRADATION",
            openingWorkshopTypeKey(reordered, storedRouting = null, preferDesignWorkshops = false),
        )
        assertEquals(
            "a designer must reach the design type wherever the administrator has put it",
            "DESIGN_PROTOTYPE_DEVELOPMENT",
            openingWorkshopTypeKey(reordered, storedRouting = null, preferDesignWorkshops = true),
        )
    }

    /**
     * AN EXISTING RECORD OPENS ON THE COLUMN IT ALREADY NAMES, whatever the default would have said.
     *
     * "The most recent workshop the account can reach" is the default for a NEW record. Applying it
     * to an existing one would re-file historic records under whatever is newest — invisibly,
     * because nothing on screen would say a link had moved.
     */
    @Test
    fun `an existing record opens on its own routing and not on the default`() {
        assertEquals(
            "a record filed under an ordinary workshop must not open a designer on the design list",
            "SKILL_UPGRADATION",
            openingWorkshopTypeKey(SEEDED, storedRouting = false, preferDesignWorkshops = true),
        )
        assertEquals(
            "a record filed under a design workshop must open on the design list for anybody",
            "DESIGN_PROTOTYPE_DEVELOPMENT",
            openingWorkshopTypeKey(SEEDED, storedRouting = true, preferDesignWorkshops = false),
        )
    }

    /** And the stored routing is read off the two columns, design first. */
    @Test
    fun `stored routing is read off the record's own columns`() {
        assertNull(storedWorkshopRouting(null, null))
        assertNull(storedWorkshopRouting("", "  "))
        assertEquals(false, storedWorkshopRouting("w1", null))
        assertEquals(true, storedWorkshopRouting(null, "dw1"))
        assertEquals(
            "a legacy row carrying both opens on the narrower, grant-gated link",
            true,
            storedWorkshopRouting("w1", "dw1"),
        )
    }

    /**
     * A RECORD KEEPS ITS PROGRAMME EVEN AFTER AN ADMINISTRATOR RETIRES IT.
     *
     * If the one type that routes at `DesignWorkshop` is deactivated, falling through to the ordinary
     * list would flip the routing and clear `designWorkshopId` on the next save — the silent
     * re-filing the whole defaulting rule exists to prevent. The key is returned anyway, the box
     * draws it as a row of its own, and the routing falls back to the stored column.
     */
    @Test
    fun `a retired programme still names itself and still routes where the record is filed`() {
        val withoutDesign = SEEDED.map {
            if (it.routesToDesignWorkshop) it.copy(isActive = false) else it
        }
        val key = openingWorkshopTypeKey(withoutDesign, storedRouting = true, preferDesignWorkshops = false)
        assertEquals("DESIGN_PROTOTYPE_DEVELOPMENT", key)
        assertTrue(
            "the record's designWorkshopId would be cleared on the next save",
            routesToDesignWorkshopFor(withoutDesign, key, storedRouting = true),
        )
        val labels = workshopTypeOptions(withoutDesign, key, FLOOR).map { it.label }
        assertTrue(
            "the type box would render blank, which is how somebody repairs it by picking something " +
                "else and re-files the record",
            labels.contains("Design & Prototype Development"),
        )
    }

    /** An active type that IS served is never duplicated by the recovery row. */
    @Test
    fun `the recovery row appears only for a type the list no longer carries`() {
        assertEquals(SEEDED.size, workshopTypeOptions(SEEDED, "SKILL_UPGRADATION", FLOOR).size)
        assertEquals(SEEDED.size, workshopTypeOptions(SEEDED, "", FLOOR).size)
        assertEquals(SEEDED.size + 1, workshopTypeOptions(SEEDED, "GONE", FLOOR).size)
        assertEquals(
            "a token with no floor row prints as itself, never as \"Unknown\" and never as blank",
            "GONE",
            workshopTypeOptions(SEEDED, "GONE", FLOOR).last().label,
        )
    }

    /** Retired types are absent from the box, which is the entire meaning of retiring one. */
    @Test
    fun `a deactivated type is not offered`() {
        val retired = SEEDED.map { if (it.key == "CLUSTER_DEVELOPMENT") it.copy(isActive = false) else it }
        assertFalse(workshopTypeOptions(retired, "SKILL_UPGRADATION", FLOOR).any { it.value == "CLUSTER_DEVELOPMENT" })
        assertNull(defaultWorkshopType(emptyList(), preferDesignWorkshops = true))
    }

    /**
     * THE ORDER IS TOTAL, because `sortOrder` is deliberately not unique on that table.
     *
     * A unique ordinal makes a swap a three-statement dance through a temp value that half-completes
     * on an interrupted request, so the migration left it non-unique and the API breaks the tie with
     * `key`. A client that did not would render two rows in whatever order the wire happened to carry
     * them, and a dropdown whose rows move between two openings is one a designer re-reads.
     */
    @Test
    fun `two types sharing a position are broken apart by their key`() {
        val tied = listOf(type("ZEBRA", 10, routes = false), type("ALPHA", 10, routes = false))
        assertEquals(listOf("ALPHA", "ZEBRA"), sortWorkshopTypes(tied).map { it.key })
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 4. R5 — "NOT LINKED TO A WORKSHOP" SURVIVES
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Both halves of the second box keep the row, under the same words.
     *
     * A record may still have no workshop; existing NULL records stay valid and editable; and nothing
     * forces a researcher to invent a workshop to save work done outside one. The design half's own
     * default row said "Not filed under a design workshop", which is right when it is one of two
     * boxes on a form and wrong when it is the only box for one question.
     */
    @Test
    fun `the not-linked row is offered by both halves under one wording`() {
        assertEquals("Not linked to a workshop", NO_FIELD_WORKSHOP)
        val picker = withoutComments(PICKER)
        assertTrue(
            "DesignWorkshopField's default none-row is no longer the record form's wording",
            picker.contains("noneLabel: String = NO_FIELD_WORKSHOP"),
        )
        assertTrue("DesignWorkshopField dropped includeNone", picker.contains("includeNone = true"))
        assertTrue(
            "WorkshopField dropped includeNone or its placeholder",
            withoutComments(MAIN).contains("placeholder = NO_FIELD_WORKSHOP"),
        )
    }

    /**
     * AND THE TYPE BOX HAS NO SUCH ROW, which is the opposite ruling and the right one.
     *
     * The retired kind box carried an "Any type of workshop" row because it was a FILTER and "no
     * filter" was a real answer. This box decides where the record saves, so "no type" is not an
     * answer a form can act on — it would leave the second box with no list to draw.
     */
    @Test
    fun `the type box offers no empty row`() {
        assertTrue(withoutComments(PICKER).contains("label = \"Type of workshop\",\n            options = options,\n            selectedValue = state.typeKey,\n            includeNone = false,"))
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 5. THE TWO CLIENTS SAY THE SAME WORDS — read off the browser's own file
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /** The key both clients name the design-workshop programme by. */
    @Test
    fun `both clients name the design programme by the same key`() {
        val web = Regex("""DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY = "([A-Z_]+)"""")
            .find(webFile("lib/workshopTypes.ts"))?.groupValues?.get(1)
        assertEquals(web, DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY)
    }

    /**
     * THE FIVE STRINGS ON SCREEN, BYTE FOR BYTE.
     *
     * A designer who meets one wording on the laptop and another on the phone learns that neither is
     * quite the rule — which is the disagreement the owner says they are most tired of. Read out of
     * the browser's source rather than restated here, because a copy in a test agrees with itself and
     * with nothing else.
     */
    @Test
    fun `the labels and the hint are the browser's own`() {
        val web = webFile("components/forms/WorkshopPicker.tsx")
        val android = withoutComments(PICKER) + withoutComments(MAIN)

        assertTrue("the browser stopped calling the first box \"Type of workshop\"", web.contains("<Field label=\"Type of workshop\">"))
        assertTrue("the handset stopped calling the first box \"Type of workshop\"", android.contains("label = \"Type of workshop\""))

        assertTrue("the browser stopped labelling both halves \"Workshop\"", web.contains("label=\"Workshop\""))
        assertTrue("the handset's design half stopped defaulting to \"Workshop\"", android.contains("label: String = \"Workshop\""))
        assertTrue("the handset's ordinary half stopped being labelled \"Workshop\"", android.contains("label = \"Workshop\","))

        for (hint in listOf(workshopTypeHint(served = true), workshopTypeHint(served = false))) {
            assertTrue(
                "the browser no longer prints this sentence under the type box:\n$hint",
                web.replace("\n", " ").replace(Regex(" +"), " ").contains(hint.replace(Regex(" +"), " ")),
            )
        }
    }

    /**
     * THE BROWSER STILL CLEARS THE OTHER COLUMN THE SAME WAY, so the two clients cannot produce
     * differently-shaped records from the same two taps.
     */
    @Test
    fun `the browser writes one column and empties the other, as this client does`() {
        val web = webFile("components/forms/WorkshopPicker.tsx")
        assertTrue(
            "the browser's WorkshopPicker no longer nulls the column the type does not name",
            web.contains("workshopId: routesToDesignWorkshop ? \"\" : field.workshopId") &&
                web.contains("designWorkshopId: routesToDesignWorkshop ? design.workshopId : \"\""),
        )
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 6. THE OFFLINE FLOOR — six labels that shipped with the APK, and the seed they came from
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * The floor is the BUNDLED REGISTRY, and it agrees with the rows the migration seeded.
     *
     * The six `WorkshopTypeOption` rows were seeded from `stage_schema.ENUMS["WORKSHOP_KIND"]`
     * precisely so every `workshopKind` already stored resolves to a label. That is what lets this
     * client use the registry it already ships as the offline floor instead of compiling a second
     * copy of the list. If the seed and the registry ever part company, an offline handset starts
     * offering labels the server does not have — so the two are held together here, against the
     * migration's own SQL.
     */
    @Test
    fun `the offline floor is the shipped registry and matches the seeded rows`() {
        assertEquals(6, FLOOR.size)
        assertEquals(
            listOf(
                "DESIGN_PROTOTYPE_DEVELOPMENT", "SKILL_UPGRADATION", "DESIGN_INTERVENTION",
                "CLUSTER_DEVELOPMENT", "EXPOSURE_EXHIBITION", "OTHER",
            ),
            FLOOR.map { it.key },
        )
        assertEquals(listOf(10, 20, 30, 40, 50, 60), FLOOR.map { it.sortOrder })
        assertTrue("every floor row must be offerable", FLOOR.all { it.isActive })

        val sql = repoFile(
            "../backend/prisma/migrations/20260916160000_workshop_type_options/migration.sql",
            "backend/prisma/migrations/20260916160000_workshop_type_options/migration.sql",
        ).readText(Charsets.UTF_8)
        for (row in FLOOR) {
            assertTrue(
                "the seeded label for ${row.key} is no longer the one this APK ships: ${row.label}",
                sql.contains("'${row.key}'") && sql.contains("'${row.label}'"),
            )
        }
    }

    /** Exactly one floor row routes at `DesignWorkshop`, and it is the one the seed marks. */
    @Test
    fun `exactly one floor row routes at the design workshop register`() {
        assertEquals(
            listOf("DESIGN_PROTOTYPE_DEVELOPMENT"),
            FLOOR.filter { it.routesToDesignWorkshop }.map { it.key },
        )
    }

    /**
     * AND THE FLOOR SAYS IT IS THE FLOOR.
     *
     * DROPDOWN_DESIGN R3: a silently short list reads as "there are only these", which this
     * repository names as its most repeated bug class. A handset that has never reached
     * `/workshop-types` is holding six labels that shipped with the APK, and an administrator may
     * have added a seventh programme that simply is not here.
     */
    @Test
    fun `the two hints are different and only one of them claims the administrator's list`() {
        val served = workshopTypeHint(served = true)
        val floor = workshopTypeHint(served = false)
        assertTrue(served.contains("It is not saved on this record"))
        assertTrue(floor.contains("built-in types of workshop"))
        assertTrue("both arms owe the reader the not-saved fact", floor.contains("not saved on this record"))
        assertFalse("the served arm must not apologise for a list that is right", served.contains("built-in"))
    }


    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 7. THE OFFLINE CACHE — the box a designer with no signal is actually looking at
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /*
     * THE DEFECT THIS SECTION EXISTS ABOUT, STATED PLAINLY.
     *
     * `data/DwLocalWorkshops.kt` was written and unit-tested and READ BY NOTHING. Both of these
     * pickers went straight to the network, so a designer in a courtyard opened a record form and
     * found an EMPTY workshop box — the exact thing the owner asked for and did not get: *"The info
     * for the designer workshop needs to be saved locally for the upcoming/ongoing workshops the
     * particular designer has been allotted to, so that they can attribute their work to the same."*
     *
     * Two halves are asserted here and they answer two different questions:
     *
     *   · THAT THE PICKERS READ IT — against the source, because "which composable calls what" is
     *     wiring, and this module has no Robolectric and no `ui-test-junit4` to compose a screen
     *     with (the same constraint this file opens with). Delete either call and the first test
     *     below fails.
     *   · WHAT THE READER HANDS BACK AND WHAT THE SCREEN THEN SAYS — as real behaviour, off a real
     *     temporary directory, driving `DesignWorkshopPickerState` through the same sequence
     *     `rememberDesignWorkshopPicker` drives it through with a network that throws. The
     *     mechanics of the cache itself (the window, the account key, the retirement of a revoked
     *     allotment) are `data/DwLocalWorkshopsTest`'s and are not repeated here.
     */

    /**
     * BOTH PICKERS ANSWER FROM THE DEVICE BEFORE THEY ASK THE SERVER.
     *
     * The two halves of the record form's control must not differ about this: a designer who sees
     * four workshops under "Design & prototype" and an empty box under "Skill Upgradation" on the
     * same phone in the same courtyard learns that the second list is empty, which is false.
     *
     * Each half reads its OWN table, off the request it was already issuing — so neither picker
     * calls `refreshAllottedWorkshops`, which would fetch the other table as well and walk the design
     * list on top, doubling the traffic of every record form open.
     */
    @Test
    fun `both record-form pickers read the offline cache before they ask the server`() {
        assertEquals(
            "the Workshop half no longer answers from the device's own copy",
            listOf("rememberWorkshopPicker"),
            enclosingFunctionsCalling("loadAllottedFieldWorkshops(").sorted(),
        )
        assertEquals(
            "the DesignWorkshop half no longer answers from the device's own copy",
            listOf("rememberDesignWorkshopPicker"),
            enclosingFunctionsCalling("loadAllottedDesignWorkshops(", PICKER).sorted(),
        )
        assertEquals(
            "a picker is refreshing BOTH tables on a form open — see refreshAllottedWorkshops' note",
            emptyList<String>(),
            enclosingFunctionsCalling("refreshAllottedWorkshops(") +
                enclosingFunctionsCalling("refreshAllottedWorkshops(", PICKER),
        )
    }

    /**
     * THE CACHED LIST IS DRAWN AND NEVER CHOSEN FROM — R6's surviving half, driven rather than read.
     *
     * A cached list may be OFFERED to a person who then picks from it; a stale answer may never be
     * WRITTEN onto a record nobody looked at. The difference is not academic: a prefill lands on
     * every record made that afternoon, silently, and the designer never saw the box it came from.
     *
     * So this drives the design picker's state through the same sequence the composable drives it
     * through — the device's copy, then a network that throws — and asserts both halves of the rule
     * at once: the rows are there to pick, and nothing has been picked.
     */
    @Test
    fun `a cached list fills the picker and selects nothing`() {
        withTempRoot { root ->
            val account = "picker-offline-design"
            storeDesign(
                root,
                account,
                listOf(
                    designWorkshop("dw-old", title = "Bagru block printing", startDate = "2026-08-02"),
                    designWorkshop("dw-new", title = "Chanderi weaving", startDate = "2026-09-12"),
                ),
            )
            val state = DesignWorkshopPickerState("")

            val half = runBlocking {
                dwLoadAllottedDesignWorkshops<DesignWorkshopPageDto>(
                    root = root,
                    userId = account,
                    fetch = { throw java.io.IOException("no route to host") },
                    rowsOf = { it.items },
                    isTransient = { true },
                    today = TODAY,
                    onCached = { rows, load -> state.markCached(rows, load) },
                )
            }
            if (half.page == null) state.markFailed(transient = !half.load.online)

            assertEquals(listOf("dw-old", "dw-new"), state.workshops.map { it.id })
            assertEquals(RegisterSource.CACHED, state.cached.source)
            assertEquals("a cached row was pre-selected onto a record nobody looked at", "", state.selectedId)
            assertFalse("the cache marked a pristine form as edited", state.isDirty())
            // R2: the control may be opened, because there is now something in it to answer with.
            assertTrue(listIsAnswerable(designWorkshopOptions(state.workshops)))
        }
    }

    /**
     * MOST-RECENT-FIRST SURVIVES THE ROUND TRIP THROUGH THE DISK, and the type still chooses the
     * TABLE rather than filtering one.
     *
     * The cached row has to carry enough for both of those. It does, and this is what says so: the
     * occurrence dates come back off the file, so `designWorkshopOptions` and `fieldWorkshopOptions`
     * put the newest workshop at the top exactly as they do for a live list — and the two tables come
     * back as two separate lists, so a record form showing one of them offline is showing the same
     * thing it would have shown online.
     *
     * A cache that dropped the dates would not fail anything visibly: the picker would simply come
     * back in an order nobody chose, which is the class of defect this repository keeps paying for.
     */
    @Test
    fun `the cached rows keep the order and the table the picker draws them in`() {
        withTempRoot { root ->
            val account = "picker-offline-order"
            storeDesign(
                root,
                account,
                listOf(
                    designWorkshop("dw-oldest", title = "Aaa oldest", startDate = "2026-07-01"),
                    designWorkshop("dw-newest", title = "Zzz newest", startDate = "2026-09-12"),
                ),
            )
            storeField(
                root,
                account,
                listOf(
                    fieldWorkshop("ws-oldest", title = "Aaa oldest", startDate = "2026-07-01", endDate = "2026-12-01"),
                    fieldWorkshop("ws-newest", title = "Zzz newest", startDate = "2026-09-12", endDate = "2026-12-01"),
                ),
            )

            val held = runBlocking { dwCachedAllottedWorkshops(root, account, TODAY) }

            // NEWEST FIRST, and by OCCURRENCE rather than by title — the alphabet is deliberately
            // fighting the dates here so that a sort that quietly fell back to the label is caught.
            assertEquals(
                listOf("dw-newest", "dw-oldest"),
                designWorkshopOptions(held.designWorkshops).map { it.value },
            )
            assertEquals(
                listOf("ws-newest", "ws-oldest"),
                fieldWorkshopOptions(held.fieldWorkshops, today = TODAY).map { it.value },
            )
            // TWO TABLES, TWO LISTS. The type box picks which half is drawn; neither half leaks into
            // the other, offline any more than online.
            assertTrue(held.designWorkshops.none { it.id.startsWith("ws-") })
            assertTrue(held.fieldWorkshops.none { it.id.startsWith("dw-") })
        }
    }

    /**
     * A LIST SERVED FROM THE DEVICE SAYS SO, AND SAYS WHEN.
     *
     * `cachedListLine` is the repository's existing sentence for exactly this and it is reused rather
     * than reworded: a second phrasing for one fact is two facts as far as a designer reading two
     * screens is concerned. The DATE is the whole sentence — a workshop missing from a list refreshed
     * an hour ago means something different from one missing from a nine-day-old file — which is why
     * the reader carries `fetchedAt` all the way to the screen instead of dropping it one function
     * short, the way `loadCachedRegister` used to.
     */
    @Test
    fun `a list drawn from the device prints the cached sentence with its date`() {
        val stamp = "2026-09-07T06:22:56.518000Z"
        val cached = RegisterLoad(source = RegisterSource.CACHED, fetchedAt = stamp)

        assertEquals(
            cachedListLine(2, "design workshops", readableStamp(stamp)),
            workshopListNotice(
                state = WorkshopListState.Failed,
                kind = WorkshopListKind.DESIGN,
                online = false,
                cached = cached,
                cachedRows = 2,
            ),
        )
        // And while the request is still in flight: the rows are already on screen, so "Looking for
        // your design workshops…" would be a sentence about a box that is answering.
        assertEquals(
            cachedListLine(2, "design workshops", readableStamp(stamp)),
            workshopListNotice(
                state = WorkshopListState.Loading,
                kind = WorkshopListKind.DESIGN,
                online = false,
                cached = cached,
                cachedRows = 2,
            ),
        )
    }

    /**
     * AN EMPTY CACHE, A FAILED FETCH AND "THIS ACCOUNT IS ON NO WORKSHOPS" ARE THREE FACTS AND THREE
     * SENTENCES.
     *
     * Collapsing them was a named defect in the field repository, and it is the failure this whole
     * vocabulary exists against: each one has a different next move — connect once, look at what
     * broke, ask an administrator — and an empty dropdown gives the reader none of them.
     *
     * The third is the one the cache adds, and it is only sayable because the store writes a file for
     * an answer of none: a file that exists and holds no current rows is the server having answered.
     */
    @Test
    fun `the three empty states keep their three different sentences`() {
        val neverFetched = RegisterLoad(source = RegisterSource.NONE)
        val answeredNone = RegisterLoad(source = RegisterSource.CACHED, fetchedAt = "2026-09-07T06:22:56Z")

        assertEquals(
            "a device that has never been given the list is being told to go and find a fault",
            offlineListLine("workshops"),
            workshopListNotice(
                state = WorkshopListState.Failed,
                kind = WorkshopListKind.FIELD,
                online = false,
                cached = neverFetched,
                cachedRows = 0,
            ),
        )
        assertEquals(
            "a read the server answered and refused is being blamed on the connection",
            couldNotListLine("workshops"),
            workshopListNotice(
                state = WorkshopListState.Failed,
                kind = WorkshopListKind.FIELD,
                online = true,
                cached = neverFetched,
                cachedRows = 0,
            ),
        )
        assertEquals(
            "an account that is on no current workshops is being told the device has not been given the list",
            scopedEmptyLine("workshops"),
            workshopListNotice(
                state = WorkshopListState.Failed,
                kind = WorkshopListKind.FIELD,
                online = false,
                cached = answeredNone,
                cachedRows = 0,
            ),
        )
        // All three are different strings. Stated as an assertion rather than left to the reader,
        // because the whole defect is three facts arriving as one sentence.
        assertEquals(
            3,
            setOf(offlineListLine("workshops"), couldNotListLine("workshops"), scopedEmptyLine("workshops")).size,
        )
    }

    /**
     * A READ THAT ANSWERED OUTRANKS ANYTHING THE DISK HAS TO SAY.
     *
     * The rows already work that way — the live list REPLACES the cached one and is never merged with
     * it — and the sentence has to agree, or a picker showing today's list would carry a line about a
     * file written nine days ago. The live arms are left exactly as they were, which is the other
     * half of the same rule: an answered read with no rows in it is still "no workshops are open to
     * this account", said about today.
     */
    @Test
    fun `a read that answered silences the cached sentence`() {
        val cached = RegisterLoad(source = RegisterSource.CACHED, fetchedAt = "2026-09-07T06:22:56Z")

        assertNull(
            "a live list with rows in it is apologising for a file it is not showing",
            workshopListNotice(
                state = WorkshopListState.Listed(count = 3, total = 3),
                kind = WorkshopListKind.FIELD,
                online = true,
                cached = cached,
                cachedRows = 3,
            ),
        )
        assertEquals(
            scopedEmptyLine("workshops"),
            workshopListNotice(
                state = WorkshopListState.Listed(count = 0, total = 0),
                kind = WorkshopListKind.FIELD,
                online = true,
                cached = cached,
                cachedRows = 0,
            ),
        )
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /** One decoder, hoisted: the compiler warns about building a `Json` per use, and is right. */
    private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The day §7's window assertions are made against. Fixed, so "ended" cannot move overnight. */
    private val TODAY: LocalDate = LocalDate.parse("2026-09-16")

    /**
     * A real directory, because the thing under test in §7 is what is on the disk.
     *
     * This module has no Robolectric, so a Context cannot be manufactured — which is why
     * `DwReferenceStore` grew the File-taking overloads and why the cache's reader has a File-taking
     * twin. See `DwLocalWorkshopsTest`'s header for the whole argument.
     */
    private fun withTempRoot(block: (File) -> Unit) {
        val root = java.nio.file.Files.createTempDirectory("workshop-picker-cache").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun fieldWorkshop(
        id: String,
        title: String = "Field workshop",
        startDate: String? = null,
        endDate: String? = null,
    ) = WorkshopDetailDto(id = id, title = title, place = "Chanderi", startDate = startDate, endDate = endDate)

    /**
     * [endDate] IS NOT OPTIONAL HERE AND THE REASON IS THE CACHE'S OWN WINDOW RULE.
     *
     * `dwLocalDesignWorkshopIsCurrent` reads `endDate ?: startDate`, so a workshop that STARTED in
     * the past and states no end is over as far as the cache is concerned — the fallback is what
     * makes a one-day workshop retire itself. These rows are meant to be running, so they say when
     * they finish; a row with a past start and no end would be filtered out on the read and the test
     * would be asserting the window rather than the ordering it means to assert.
     */
    private fun designWorkshop(
        id: String,
        title: String = "Design workshop",
        startDate: String? = null,
        endDate: String? = "2026-12-01",
    ) = DesignWorkshopDto(
        id = id,
        title = title,
        status = "IN_PROGRESS",
        startDate = startDate,
        endDate = endDate,
    )

    /*
     * WRITTEN THROUGH THE CACHE'S OWN ENCODER AND ITS OWN KEY, never through a hand-built file. A
     * test that composed the stored shape itself would be pinning its own copy of the two rules that
     * matter most here — which account a list is filed under, and what an answer of none writes — and
     * would go on passing after either of them changed.
     */
    private fun storeField(root: File, userId: String, rows: List<WorkshopDetailDto>) = runBlocking {
        DwReferenceStore.store(
            root,
            dwLocalWorkshopKey(DW_LOCAL_FIELD_WORKSHOPS, userId),
            dwLocalStored(DW_LOCAL_FIELD_WORKSHOPS, rows.map(::dwLocalFieldWorkshopToOption)),
        )
    }

    private fun storeDesign(root: File, userId: String, rows: List<DesignWorkshopDto>) = runBlocking {
        DwReferenceStore.store(
            root,
            dwLocalWorkshopKey(DW_LOCAL_DESIGN_WORKSHOPS, userId),
            dwLocalStored(DW_LOCAL_DESIGN_WORKSHOPS, rows.map(::dwLocalDesignWorkshopToOption)),
        )
    }

    private fun type(key: String, sortOrder: Int, routes: Boolean) = WorkshopTypeOptionDto(
        id = "id:$key",
        key = key,
        label = key.lowercase().replaceFirstChar { it.uppercase() },
        sortOrder = sortOrder,
        isActive = true,
        routesToDesignWorkshop = routes,
    )

    /**
     * The floor this APK genuinely produces, off the asset `StageSchemaStore.readAsset` opens.
     *
     * Parsed rather than restated, because a list copied into a test is a list that agrees with
     * itself and with nothing else. This is what a handset with no signal actually offers.
     */
    private val FLOOR: List<WorkshopTypeOptionDto> by lazy {
        val asset = repoFile(
            "app/src/main/assets/design-workshop-schema.json",
            "android/app/src/main/assets/design-workshop-schema.json",
        ).readText(Charsets.UTF_8)
        val schema = LENIENT_JSON.decodeFromString(SchemaResponse.serializer(), asset)
        workshopTypeFloor(schema)
    }

    /** The six rows the server actually serves today — the floor, which the seed agrees with. */
    private val SEEDED: List<WorkshopTypeOptionDto> get() = FLOOR

    /**
     * A file of this repository, found by walking up from wherever the test runner started.
     *
     * The working directory of a Gradle test worker is not something to depend on, and a test that
     * skipped when it could not find its subject would prove nothing on the day somebody moves it.
     * Missing is a failure, loudly. Same helper and same reasoning as `DashboardTileParityTest`, and
     * the `..`-prefixed candidates are what let it reach OUT of `android/` and into `frontend/`.
     */
    private fun repoFile(vararg relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = File(dir, path)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("none of ${relative.toList()} found from ${File(".").absolutePath}")
    }

    private fun androidFile(relative: String): File = repoFile(
        "app/src/main/java/com/designprototype/workshop/$relative",
        "android/app/src/main/java/com/designprototype/workshop/$relative",
    )

    private fun webFile(relative: String): String =
        repoFile("../frontend/$relative", "frontend/$relative").readText(Charsets.UTF_8)

    private val PICKER: String by lazy { androidFile("ui/DesignWorkshopPicker.kt").readText(Charsets.UTF_8).replace("\r\n", "\n") }
    private val MAIN: String by lazy { androidFile("MainActivity.kt").readText(Charsets.UTF_8).replace("\r\n", "\n") }

    /**
     * The TOP-LEVEL functions of `MainActivity.kt` that contain a call to [needle].
     *
     * Anchored at column zero on purpose. A naive "nearest preceding `fun`" answers with whatever
     * local helper — `submit`, `formSignature`, `refreshMedia` — happens to be declared last inside
     * the composable, which is a different question and a wrong answer that looks plausible enough to
     * be believed. Borrowed from `DesignWorkshopPickerTest`, which borrowed it from
     * `DashboardTileParityTest`.
     */
    private fun enclosingFunctionsCalling(needle: String, over: String = MAIN): List<String> {
        val source = withoutComments(over)
        val declarations = Regex("""(?m)^(?:private\s+|internal\s+)?fun\s+([A-Za-z0-9_]+)\s*\(""")
            .findAll(source)
            .map { it.range.first to it.groupValues[1] }
            .toList()
        val found = LinkedHashSet<String>()
        var from = 0
        while (true) {
            val at = source.indexOf(needle, from)
            if (at == -1) break
            from = at + needle.length
            // A DECLARATION IS NOT A CALL. Both halves of this control are declared in the same file
            // they are called from, so without this the answer always contains the control's own
            // name and the "exactly these five forms" assertion can never hold. The helper this was
            // borrowed from never needed it: the field it swept for is declared in another file.
            if (at >= 4 && source.regionMatches(at - 4, "fun ", 0, 4)) continue
            declarations.lastOrNull { it.first < at }?.let { found.add(it.second) }
        }
        return found.toList()
    }

    /**
     * Source with comments removed, so a sweep for a mount cannot be satisfied — or defeated — by a
     * sentence ABOUT that mount.
     *
     * Every file in this repository documents what it does at length, including the things these
     * tests are looking for, so a bare `contains` over raw source answers yes for a comment that says
     * "this used to call X". It steps over literals rather than into them: the triple-quoted raw form
     * first (a lone quote inside one is not a terminator), then the ordinary single- and
     * double-quoted forms, honouring the backslash escape. Their CONTENTS are copied through,
     * because the sweep for a literal control label wants to find it.
     */
    private fun withoutComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            when {
                source.startsWith("//", i) -> {
                    val end = source.indexOf('\n', i)
                    i = if (end == -1) source.length else end
                }
                source.startsWith("/*", i) -> {
                    var depth = 1
                    i += 2
                    while (i < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", i) -> { depth++; i += 2 }
                            source.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                }
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3)
                    val stop = if (end == -1) source.length else end + 3
                    out.append(source, i, stop)
                    i = stop
                }
                source[i] == '"' || source[i] == '\'' -> {
                    val quote = source[i]
                    var j = i + 1
                    while (j < source.length && source[j] != quote) {
                        // A backslash escapes the next character, INCLUDING a closing quote. Without
                        // this, `"\""` ends the literal one character early and everything after it
                        // is read as code that is really text.
                        j += if (source[j] == '\\') 2 else 1
                    }
                    val stop = minOf(j + 1, source.length)
                    out.append(source, i, stop)
                    i = stop
                }
                else -> {
                    out.append(source[i])
                    i++
                }
            }
        }
        return out.toString()
    }
}
