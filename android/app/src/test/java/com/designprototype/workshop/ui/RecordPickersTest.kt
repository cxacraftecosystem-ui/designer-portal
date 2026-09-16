package com.designprototype.workshop.ui

import com.designprototype.workshop.data.ArtisanDto
import com.designprototype.workshop.data.CraftDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TOOL FORM'S FOUR LINK RULES, EACH OF WHICH DESTROYS SOMETHING WHEN IT IS WRONG.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THESE ARE PINNED AND THE FORM AROUND THEM IS NOT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * There is no `ui-test-junit4` and no Robolectric in `app/build.gradle.kts`, so nothing in this suite
 * can render `ToolForm` and look at it. What it CAN do is drive the decisions the form makes that a
 * reader cannot check by looking at the screen:
 *
 *  · WHETHER "English name" IS STILL MIRRORING. Get this wrong in one direction and opening a record
 *    to fix a spelling overwrites a translation somebody typed, under a 200. Get it wrong in the
 *    other and a form that should have filled the box silently does not, which is merely annoying —
 *    so the two failures are not symmetric and the test says which is which.
 *  · WHICH ARTISANS A CRAFT DESELECTION DROPS. Too many and the save deletes a link the record
 *    legitimately holds; too few and the picker offers an artisan of a craft nobody ticked. The
 *    pure-ADDITION case is here because it is the one the first plural draft of the rule got wrong:
 *    a reconciliation over the whole selection drops links on a change that removed nothing, and the
 *    five deselection cases below all passed while it did.
 *  · WHAT THE PICKERS OPEN WITH. `artisanIds[0]` is what the server writes into `tool.artisanId`,
 *    so a seed that loses the record's own scalar repoints the record on the next Save — silently,
 *    on an edit to something else entirely.
 *  · WHAT ORDER THE ARTISAN SHEET LISTS ROWS IN. The browser sorts the same artisans with the same
 *    five-part key. A divergence is invisible on either client alone and shows up as two colleagues
 *    describing the same list differently.
 *
 * `frontend/e2e` drives the web twin of the first two. The collation rules in the sort are the part
 * most likely to be "tidied" into `String.CASE_INSENSITIVE_ORDER` or `java.text.Collator`, and the
 * Turkish-I case below is what catches that.
 */
class RecordPickersTest {

    private fun craft(id: String, name: String) = CraftDto(id = id, name = name)

    private fun artisan(
        id: String,
        name: String,
        craftId: String? = null,
        place: String = "Bagru",
        craft: CraftDto? = null,
    ) = ArtisanDto(id = id, name = name, place = place, status = "APPROVED", craftId = craftId, craft = craft)

    /* ── (A) The sticky divorce ──────────────────────────────────────────────────────────────── */

    @Test
    fun `a create and an empty English name both open mirroring`() {
        assertTrue("a create has nothing to protect", englishNameMirrorArmed(null, null))
        assertTrue(englishNameMirrorArmed("Chhapa", ""))
        assertTrue("whitespace is an empty box", englishNameMirrorArmed("Chhapa", "   "))
    }

    @Test
    fun `an English name equal to the toolkit name keeps mirroring`() {
        // Nothing is at risk: the two columns already agree, so a later correction to the toolkit
        // spelling carrying into the English box changes nothing a designer chose.
        assertTrue(englishNameMirrorArmed("Chhapa", "Chhapa"))
    }

    @Test
    fun `an English name that differs opens DIVORCED and is never overwritten`() {
        // THE CASE THE WHOLE RULE EXISTS FOR. A tool with a real translation must not lose it the
        // first time somebody fixes a typo in the toolkit name.
        assertFalse(englishNameMirrorArmed("Chhapa", "Block-printing stamp"))
    }

    @Test
    fun `equality is RAW, because the casing belongs to the server`() {
        // `clean_data` title-cases both columns, so two values differing only in case are two values
        // this client was HANDED differently. Folding them together here would arm a form over a
        // stored value that is not the toolkit name, and the next keystroke would overwrite it.
        assertFalse(englishNameMirrorArmed("Chhapa", "chhapa"))
        assertFalse(englishNameMirrorArmed("Chhapa", " Chhapa "))
    }

    /* ── (C) What a craft deselection drops ─────────────────────────────────────────────────── */

    private val block = craft("c-block", "Block Printing")
    private val bandhani = craft("c-bandhani", "Bandhani")
    private val roster = listOf(
        artisan("a-1", "Ram Kumar", craftId = "c-block"),
        artisan("a-2", "Sita Devi", craftId = "c-bandhani"),
        artisan("a-3", "Mohan Lal", craftId = null),
    )

    @Test
    fun `deselecting a craft drops only the artisans of that craft`() {
        val dropped = craftsChangeClearsArtisans(
            nextCraftIds = listOf("c-bandhani"),
            removedCraftIds = listOf("c-block"),
            artisanIds = listOf("a-1", "a-2"),
            artisans = roster,
        )
        assertEquals(listOf("a-1"), dropped)
    }

    @Test
    fun `ticking an extra craft drops nothing, because nothing was removed`() {
        // THE CASE THE RULE GOT WRONG, and the only one here that is not a deselection at all.
        // Sita Devi is linked to this tool by the assignment screen — `POST
        // /tools/{id}/artisans` writes `ToolArtisan` rows without touching `ToolCraft`, and that
        // screen exists to link a tool to artisans across the same or DIFFERENT crafts — so her
        // craft is not among the tool's. Reconciled against the new selection she disappears; the
        // save then sends a list without her and `_write_links` deletes her row. The designer ticked
        // a craft and lost an assignment, with nothing on screen saying so.
        val dropped = craftsChangeClearsArtisans(
            nextCraftIds = listOf("c-block", "c-ajrakh"),
            removedCraftIds = emptyList(),
            artisanIds = listOf("a-1", "a-2"),
            artisans = roster,
        )
        assertEquals(emptyList<String>(), dropped)
    }

    @Test
    fun `deselecting one craft leaves the artisans of a craft nobody touched alone`() {
        // The same cross-craft link, on a change that DID remove something. "Drop exactly the
        // artisans belonging only to the craft that went" is the promise; Sita Devi belongs to
        // neither the craft that went nor the one that stayed, so this change did not orphan her.
        val dropped = craftsChangeClearsArtisans(
            nextCraftIds = listOf("c-ajrakh"),
            removedCraftIds = listOf("c-block"),
            artisanIds = listOf("a-1", "a-2"),
            artisans = roster,
        )
        assertEquals(listOf("a-1"), dropped)
    }

    @Test
    fun `nothing is dropped while every ticked craft still covers its artisans`() {
        val dropped =
            craftsChangeClearsArtisans(listOf("c-block", "c-bandhani"), listOf("c-third"), listOf("a-1", "a-2"), roster)
        assertEquals(emptyList<String>(), dropped)
    }

    @Test
    fun `a craft removed and re-ticked in one answer orphans nobody`() {
        // BOTH CLAUSES, ASSERTED TOGETHER. A sheet answers with a whole set, so "removed" is a diff
        // and can in principle name a craft the new selection still holds. Membership of the removed
        // set is not on its own a reason to drop anyone; being uncovered by what survives is the
        // other half.
        val dropped = craftsChangeClearsArtisans(
            nextCraftIds = listOf("c-block"),
            removedCraftIds = listOf("c-block", "c-bandhani"),
            artisanIds = listOf("a-1", "a-2"),
            artisans = roster,
        )
        assertEquals(listOf("a-2"), dropped)
    }

    @Test
    fun `an artisan this form cannot see is never dropped`() {
        // "NOT ON THE LIST" AND "NOT OF THAT CRAFT" ARE DIFFERENT OBSERVATIONS. The register is one
        // server page deep, so an artisan of a still-ticked craft is easily absent from it. Reading
        // that absence as disqualification would delete a link the record holds, silently, on a save
        // about something else entirely.
        val dropped =
            craftsChangeClearsArtisans(listOf("c-bandhani"), listOf("c-block"), listOf("a-off-page"), roster)
        assertEquals(emptyList<String>(), dropped)
    }

    @Test
    fun `an artisan whose record names no craft is never dropped`() {
        // Nothing says they belonged to the craft that was just removed.
        val dropped =
            craftsChangeClearsArtisans(emptyList(), listOf("c-block", "c-bandhani"), listOf("a-3"), roster)
        assertEquals(emptyList<String>(), dropped)
    }

    @Test
    fun `clearing every craft drops every artisan this form knows a craft for`() {
        val dropped = craftsChangeClearsArtisans(
            nextCraftIds = emptyList(),
            removedCraftIds = listOf("c-block", "c-bandhani"),
            artisanIds = listOf("a-1", "a-2", "a-3"),
            artisans = roster,
        )
        assertEquals("the craft-less artisan stays", listOf("a-1", "a-2"), dropped)
    }

    /* ── What the two pickers OPEN with ──────────────────────────────────────────────────────── */

    @Test
    fun `the record's own scalar leads and its link rows follow`() {
        // `artisanIds[0]` is what the server writes into `tool.artisanId`. Leading with the scalar
        // is what makes the server's derivation a no-op on a record nobody changed.
        assertEquals(listOf("a1", "a5", "a9"), initialLinkIds(listOf("a5", "a9"), "a1"))
    }

    @Test
    fun `a scalar that is already among the links keeps its place and is not repeated`() {
        // The ordinary case for a record saved through this form, where element 0 IS the scalar.
        // Dedupe keeps the FIRST occurrence, which is `dict.fromkeys` — the same rule the route
        // applies on the way in, so what the form holds is what the server will store.
        assertEquals(listOf("c-1", "c-2"), initialLinkIds(listOf("c-1", "c-2"), "c-1"))
        assertEquals(listOf("c-2", "c-1"), initialLinkIds(listOf("c-1", "c-2"), "c-2"))
    }

    @Test
    fun `a record with no link rows opens on its scalar alone`() {
        // Saved before the join table existed, or replayed from an outbox entry queued by a build
        // that had never heard of these keys. An empty list is an honest answer, not "use nothing".
        assertEquals(listOf("c-1"), initialLinkIds(emptyList(), "c-1"))
        assertEquals(listOf("c-1"), initialLinkIds(null, "c-1"))
    }

    @Test
    fun `links with no scalar are kept in the order the server stated`() {
        // NOT re-sorted. `craftLinks` arrive in `craftName` order and `artisanLinks` oldest first,
        // and both are part of what a save writes back — so reordering here would rewrite the record
        // by merely opening it.
        assertEquals(listOf("a5", "a9"), initialLinkIds(listOf("a5", "a9"), null))
    }

    @Test
    fun `blanks are dropped and a create with neither opens empty`() {
        assertEquals(emptyList<String>(), initialLinkIds(null, ""))
        assertEquals(emptyList<String>(), initialLinkIds(emptyList(), "   "))
        assertEquals(listOf("c-1"), initialLinkIds(listOf("", "  "), " c-1 "))
        assertEquals(emptyList<String>(), initialLinkIds(null, null))
    }

    /* ── Order, which is the wire contract ───────────────────────────────────────────────────── */

    @Test
    fun `merging a sheet answer keeps tick order and appends newcomers in option order`() {
        // `craftIds` is ORDERED on the wire: the server writes `craftId` from element 0 and joins
        // `craftName` in this sequence. The sheet answers with a Set, which has no order at all.
        val merged = mergeSelection(
            current = listOf("b", "a"),
            next = setOf("a", "b", "c"),
            optionIds = listOf("a", "b", "c", "d"),
        )
        assertEquals(listOf("b", "a", "c"), merged)
    }

    @Test
    fun `merging drops what the sheet unticked and keeps what it could not see`() {
        val merged = mergeSelection(
            current = listOf("a", "off-page", "b"),
            next = setOf("a"),
            optionIds = listOf("a", "b"),
        )
        // "b" was on the sheet and is gone from the answer: unticked. "off-page" was never drawn, so
        // the answer says NOTHING about it, and dropping it would be deleting a link on the strength
        // of a question nobody was asked.
        assertEquals(listOf("a", "off-page"), merged)
    }

    @Test
    fun `select all keeps a deterministic order even though the answer arrives at once`() {
        val merged = mergeSelection(emptyList(), setOf("c", "a", "b"), listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), merged)
    }

    /* ── (C) The canonical artisan ordering ─────────────────────────────────────────────────── */

    private val selectedNames = mapOf("c-block" to "Block Printing", "c-bandhani" to "Bandhani")

    @Test
    fun `artisans sort by craft name then by artisan name`() {
        val options = toolArtisanOptions(
            listOf(
                artisan("a-1", "Ram Kumar", craftId = "c-block"),
                artisan("a-2", "Sita Devi", craftId = "c-bandhani"),
                artisan("a-3", "Anil Verma", craftId = "c-block"),
                artisan("a-4", "Asha Bai", craftId = "c-bandhani"),
            ),
            selectedNames,
        )
        assertEquals(
            listOf("a-4", "a-2", "a-3", "a-1"),   // Bandhani: Asha, Sita. Block Printing: Anil, Ram.
            options.map { it.value },
        )
    }

    @Test
    fun `an artisan whose craft cannot be named sorts LAST`() {
        // A row with no heading at the top of a list reads as the most important one, which is the
        // opposite of what it is.
        val options = toolArtisanOptions(
            listOf(
                artisan("a-unknown", "Aaaa Aaaa", craftId = "c-gone"),
                artisan("a-1", "Zzzz Zzzz", craftId = "c-block"),
            ),
            selectedNames,
        )
        assertEquals(listOf("a-1", "a-unknown"), options.map { it.value })
    }

    @Test
    fun `the artisan's own embedded craft wins over the selection lookup`() {
        // `GET /artisans` carries the craft with the row. When it does, that is the fresher fact;
        // the selection map is the fallback for a list that did not embed one.
        val options = toolArtisanOptions(
            listOf(artisan("a-1", "Ram Kumar", craftId = "c-block", craft = craft("c-block", "Aari Work"))),
            selectedNames,
        )
        assertEquals("Aari Work · Bagru", options.single().hint)
    }

    @Test
    fun `the craft leads the hint, because the handset has no group headings`() {
        // `SelectOption` carries value, label and hint and no group, on BOTH handsets. So the craft
        // goes into the hint, first, and `SelectOption.matches` searches it — which is what makes
        // typing a craft name filter the sheet to that craft.
        val option = toolArtisanOptions(listOf(artisan("a-1", "Ram Kumar", craftId = "c-block")), selectedNames).single()
        assertEquals("Ram Kumar", option.label)
        assertEquals("Block Printing · Bagru", option.hint)
    }

    @Test
    fun `an artisan with neither a craft nor a place gets no hint rather than an empty one`() {
        val option = toolArtisanOptions(listOf(artisan("a-1", "Ram Kumar", place = "")), emptyMap()).single()
        assertEquals(null, option.hint)
    }

    @Test
    fun `the order is TOTAL, so two identical names still list the same way twice`() {
        val twins = listOf(
            artisan("a-zz", "Ram Kumar", craftId = "c-block"),
            artisan("a-aa", "Ram Kumar", craftId = "c-block"),
        )
        // The id is the final tiebreak, so this does not depend on `sortedWith` being stable — which
        // matters because the browser's `Array.prototype.sort` need not be.
        assertEquals(listOf("a-aa", "a-zz"), toolArtisanOptions(twins, selectedNames).map { it.value })
        assertEquals(
            listOf("a-aa", "a-zz"),
            toolArtisanOptions(twins.reversed(), selectedNames).map { it.value },
        )
    }

    @Test
    fun `the fold is Locale ROOT and not the device's`() {
        // THE TURKISH DOTTED I. `"I".lowercase(Locale("tr"))` is "ı" (U+0131), which sorts AFTER "z"
        // in UTF-16 code-unit order; `"I".lowercase()` is "i". A handset set to Turkish would order
        // this list differently from every browser and every other handset, and nothing on either
        // screen would say so. This asserts the no-argument overload's behaviour survives.
        val options = toolArtisanOptions(
            listOf(
                artisan("a-i", "Irfan", craftId = "c-block"),
                artisan("a-z", "Zara", craftId = "c-block"),
            ),
            selectedNames,
        )
        assertEquals(listOf("a-i", "a-z"), options.map { it.value })
    }

    /* ── The off-page row ───────────────────────────────────────────────────────────────────── */

    @Test
    fun `an off-page link the record can name shows its name`() {
        val row = offPageLinkRow("c-old", "Kalamkari", "craft")
        assertEquals("Kalamkari", row.label)
        assertTrue(row.hint!!.startsWith("Linked earlier"))
    }

    @Test
    fun `an off-page link the record cannot name says what it is and never invents one`() {
        // `offPageWorkshopRow`'s rule, one control along: the row never claims to be the record's
        // name, so it cannot be mistaken for one. It exists so the designer can SEE and untick a
        // link the register could not list, instead of a count they cannot account for.
        val row = offPageLinkRow("a-old", null, "artisan")
        assertEquals("The artisan already on this record", row.label)
        assertEquals("a-old", row.value)
        assertTrue(row.hint!!.contains("could not list it just now"))
        assertEquals("a blank name is the same as no name", row.label, offPageLinkRow("a-old", "  ", "artisan").label)
    }
}
