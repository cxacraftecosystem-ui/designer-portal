package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE THREE RULINGS A PICKER MAKES BEFORE IT HAS DRAWN ANYTHING, AND WHY THEY ARE PINNED HERE
 * RATHER THAN LOOKED AT.
 *
 * ── WHAT IS BEING PINNED ────────────────────────────────────────────────────────────────────────
 *
 * Three decisions, all taken in `SearchableSelect.kt` before a single row is composed, and every
 * one of them about what a control says when it has nothing to offer.
 *
 * 1. [resolveSearchable] — whether a single-select opens the searchable bottom sheet or the
 *    anchored menu. It used to be `options.size >= SEARCH_THRESHOLD` with no way to overrule it, and
 *    that produced two defects at once. A list that is ONE SERVER-TRUNCATED PAGE got a filter box
 *    over a page: the design-workshop picker asks for twenty rows, so typing the title of a workshop
 *    sitting on page four answered "Nothing matches" about a workshop that exists. And a list whose
 *    LENGTH DEPENDS ON THE ANSWER ABOVE IT changed shape as the reader worked: the district field is
 *    two rows in Goa, six in Sikkim and seventy-five in Uttar Pradesh, so one field was a menu on one
 *    screen and a sheet on the next.
 * 2. [pickerEmptyLine] — which of two sentences an empty picker prints. "Nothing matches" is
 *    about the SEARCH and the next move is to retype. The caller's `emptyMessage` is about the LIST,
 *    and the next move depends on why it is empty — a list this device has not received, a read
 *    that failed while online, a scope with nothing in it — which only the caller can know. When
 *    there is no list at all the second fact outranks the first, because a term cannot fail to
 *    match a list with no members in it.
 * 3. [countLine] — the same ruling in the sheet's polite live region, which is the copy of it
 *    that a screen-reader user hears on every keystroke without having gone looking for it.
 *
 * ── WHY A JUNIT TEST AND NOT A SCREENSHOT ───────────────────────────────────────────────────────
 *
 * `app/build.gradle.kts` carries no `ui-test-junit4` and no Robolectric, so the JVM suite cannot
 * compose a picker and look at it, and an instrumented test needs a device that CI has not got. Both
 * rulings were therefore lifted out of the composables into pure functions the composables call —
 * which is the same trade `dwSketchSourceFields` and the report planner already made in this app,
 * and it is why the extraction is not merely tidiness.
 *
 * ── THE REGRESSION THIS FILE EXISTS TO CATCH ────────────────────────────────────────────────────
 *
 * A single-select whose list crossed BELOW eight lost, in one step, the filter box, the "N options"
 * live region, the "This list is empty." sentence, the Select-all row and the IME commit path,
 * because every one of those lives in the sheet and below eight there is no sheet. With
 * `options.isEmpty()`, `includeNone = false` and no `createAction` — the configuration of eight
 * shipping fields — tapping the trigger opened a popup containing no words at all, and a picker
 * that opens on nothing reads as "there are none". On a handset in a workshop with no signal the
 * truthful reading is nearly always "this device has not been given the list yet". Those are
 * opposite facts with opposite next moves, and they looked identical.
 *
 * Anyone deleting a case below should read what it asserts first. Each one is a defect that has
 * either shipped or was one call site away from shipping.
 */
class SearchableSelectEmptyStateTest {

    private fun options(n: Int): List<SelectOption> =
        (1..n).map { SelectOption(value = "id-$it", label = "Option $it") }

    /**
     * The number is measured, it is the web's number too, and nothing in this design moves it. A
     * change here re-shapes every closed vocabulary in the app — the status ladders, the sharing
     * tiers, "Draft / Pending / Approved" — on both clients at once, so it may not happen by
     * accident while somebody is serving a record-backed list that should have passed the flag.
     */
    @Test
    fun `the threshold is eight and it does not move`() {
        assertEquals(8, SEARCH_THRESHOLD)
    }

    /**
     * `null` is the default and it must keep meaning exactly what the count meant before the
     * parameter existed, or every caller that says nothing — which is most of them — changes
     * shape on the day this lands. Both sides of the boundary are asserted because an off-by-one
     * here silently swaps the surface of every seven- and eight-row vocabulary in the app.
     */
    @Test
    fun `with no ruling from the caller the count still decides, on the same boundary as before`() {
        assertFalse("an empty list takes the menu", resolveSearchable(null, options(0)))
        assertFalse("one row takes the menu", resolveSearchable(null, options(1)))
        assertFalse("seven rows still take the menu", resolveSearchable(null, options(7)))
        assertTrue("eight rows take the sheet", resolveSearchable(null, options(8)))
        assertTrue("nine rows take the sheet", resolveSearchable(null, options(9)))
        assertTrue("a long list takes the sheet", resolveSearchable(null, options(200)))
    }

    /**
     * THE TRUNCATED-PAGE CASE. `DesignWorkshopField` asks the server for twenty rows, which is over
     * the threshold, so before the override the handset drew a filter box over a single page and
     * answered "Nothing matches" about workshops that exist further down the list. The web has always
     * refused to draw that box. `false` is how this client refuses too, and it has to beat a count
     * that says otherwise — which is the whole content of this assertion.
     */
    @Test
    fun `false overrules a long list whose options are one truncated page`() {
        assertFalse(resolveSearchable(false, options(20)))
        assertFalse(resolveSearchable(false, options(200)))
    }

    /**
     * THE CHANGING-SHAPE CASE. A record-backed list that is the WHOLE answer keeps its box however
     * short today's answer happens to be, because a reader cannot learn a control that is a menu in
     * Goa and a sheet in Uttar Pradesh. The empty case matters as much as the short one: a list that
     * shrank to zero offline must not also lose the surface that carries the count line and the
     * sentence saying why it is empty.
     */
    @Test
    fun `true overrules a short or empty list that would otherwise change shape`() {
        assertTrue(resolveSearchable(true, options(2)))
        assertTrue(resolveSearchable(true, options(7)))
        assertTrue("a list that shrank to nothing keeps its surface", resolveSearchable(true, options(0)))
    }

    /**
     * THE REGRESSION THIS PARCEL EXISTS TO PREVENT, stated as an assertion: with nothing typed, an
     * empty picker prints the CALLER'S sentence, verbatim, and invents nothing of its own.
     *
     * The five strings below are the five states an empty list can be in, and they are here in full
     * rather than as a token because the failure is not that the wrong string is chosen — it is
     * that a primitive substitutes a claim about the repository ("No crafts available.") for a
     * caller's report about a device that has not been given the list yet. Round-tripping each one
     * unchanged is what says the substitution cannot happen.
     */
    @Test
    fun `an empty list prints the caller's sentence and nothing else`() {
        val sentences = listOf(
            "This list is empty.",
            "4 design workshops on this device, last refreshed 12 March. If the one you want is " +
                "missing, refresh with a connection before concluding it is not on record.",
            "This device has not received the artisans list yet, so there is nothing to pick here. " +
                "That is not a claim that there are none. Connect once and the list is kept on the " +
                "device from then on.",
            "The districts list could not be loaded, so this is not showing what exists. Nothing " +
                "you have entered is at risk — this record can be saved without it.",
            "No design workshops are open to this account. An administrator can give you access to one."
        )
        sentences.forEach { sentence ->
            assertEquals(
                sentence,
                pickerEmptyLine(searching = false, query = "", emptyMessage = sentence, listIsEmpty = true)
            )
        }
    }

    /**
     * THE OTHER HALF OF THE SAME RULE. A term was typed and matched nothing, which is a statement
     * about the term and not about the list, and it must never be answered with the caller's
     * empty-list sentence: telling a researcher who mistyped a name that this device has not
     * received the list yet sends them to the settings screen over a typo. The reverse — printing
     * “Nothing matches “”.” at somebody whose box is empty — is what the case above forbids.
     */
    @Test
    fun `a search that matched nothing is a different sentence from a list with nothing in it`() {
        val empty = "This device has not received the artisans list yet."
        val matched = pickerEmptyLine(
            searching = true,
            query = "bagru",
            emptyMessage = empty,
            // A LIST WITH MEMBERS IN IT. That is the whole precondition of this rule, and the case
            // below is what happens without it.
            listIsEmpty = false
        )

        assertEquals("Nothing matches “bagru”.", matched)
        assertNotEquals(empty, matched)
    }

    /**
     * THE CASE THAT WAS REACHABLE IN SHIPPING CODE, and the reason [pickerEmptyLine] takes a fourth
     * argument at all.
     *
     * The reference roster keeps its trigger when the list is empty and there is a `createAction`,
     * because an empty roster is exactly when a designer needs to add the artisan standing in front
     * of them. A multi-select is searchable at every length, so the sheet opens with its box drawn
     * over nothing. The designer then types the name they came to look for, and every keystroke
     * replaced `DwReferenceField.kt:996`'s sentence — "No records for this on the device yet.
     * Connect once and reopen this stage." — with "Nothing matches “Ram Kumar”.".
     *
     * That is the product's oldest bug wearing a new coat: absence read as non-existence. A term
     * cannot fail to match a list that has no members, so the sentence about the LIST outranks the
     * sentence about the TERM whenever there is no list — and it must go on outranking it however
     * much has been typed, which is what the loop below is for.
     */
    @Test
    fun `a list with no members keeps its own sentence however much is typed over it`() {
        val empty = "No records for this on the device yet. Connect once and reopen this stage."
        listOf("R", "Ram", "Ram Kumar", "  ram bagru  ", "zzzz").forEach { typed ->
            assertEquals(
                "typed: $typed",
                empty,
                pickerEmptyLine(searching = true, query = typed, emptyMessage = empty, listIsEmpty = true)
            )
        }
    }

    /**
     * THE SAME RULE IN THE ONE STRING THAT SPEAKS WITHOUT BEING ASKED. [countLine] is the sheet's
     * polite live region: TalkBack re-reads it on every keystroke. Fixing the sentence in the body
     * of the sheet and leaving this one alone would have produced a picker that printed "this device
     * has not received the list" while announcing "No matches" over the top of it — the two
     * opposite facts again, this time inside one control, with the wrong one going to the reader who
     * cannot see the other. A list with no members has no term to fail, so it reports its size.
     */
    @Test
    fun `the live region reports the size of an empty list rather than a failed search`() {
        assertEquals("0 options", countLine(shown = 0, total = 0, searching = false))
        assertEquals("0 options", countLine(shown = 0, total = 0, searching = true))
    }

    /**
     * And the counts that DO describe a list, unchanged, because the arm added above is first in a
     * `when` and a mis-ordered guard would swallow every one of them.
     */
    @Test
    fun `a list with members counts exactly as it did`() {
        assertEquals("1 option", countLine(shown = 1, total = 1, searching = false))
        assertEquals("74 options", countLine(shown = 74, total = 74, searching = false))
        assertEquals("12 of 74 match", countLine(shown = 12, total = 74, searching = true))
        assertEquals("No matches", countLine(shown = 0, total = 74, searching = true))
    }

    /**
     * The term is echoed trimmed, so a trailing space picked up from a predictive keyboard does not
     * turn the quoted word into “bagru ” and make the reader doubt what they typed.
     */
    @Test
    fun `the echoed term is trimmed`() {
        assertEquals(
            "Nothing matches “bagru”.",
            pickerEmptyLine(
                searching = true,
                query = "  bagru  ",
                emptyMessage = "This list is empty.",
                listIsEmpty = false
            )
        )
    }
}
