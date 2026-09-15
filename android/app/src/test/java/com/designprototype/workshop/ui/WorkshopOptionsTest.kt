package com.designprototype.workshop.ui

import com.designprototype.workshop.data.DesignWorkshopDto
import com.designprototype.workshop.data.WorkshopDetailDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * THE SENTENCES, THE ORDER AND THE ARITHMETIC OF EVERY WORKSHOP PICKER ON THIS HANDSET.
 *
 * ── WHY A JUNIT TEST AND NOT A SCREENSHOT ───────────────────────────────────────────────────────
 *
 * `app/build.gradle.kts` carries no `ui-test-junit4` and no Robolectric, so the JVM suite cannot
 * compose a picker and look at it, and an instrumented test needs a device CI has not got. Every
 * ruling worth pinning was therefore lifted out of the composables into the pure functions they
 * call — the same trade `SearchableSelectEmptyStateTest`, `dwSketchSourceFields` and the report
 * planner already made.
 *
 * ── AND WHY THESE PARTICULAR ASSERTIONS ─────────────────────────────────────────────────────────
 *
 * **Four of the six states below cannot be produced on a developer's desk.** A list that failed
 * while online, a list this device has never received, an account genuinely on no workshop and a
 * walk that stopped at its ceiling all render, on a laptop with a working connection and a seeded
 * database, as the one state that is fine. So the sentence chosen for each of them is only ever
 * exercised in a courtyard with no signal — which is where getting it wrong costs an interview and
 * where nobody is reading the source. That is the whole argument for testing strings.
 *
 * The strings themselves are DROPDOWN_DESIGN §3.5's, and they are a CONTRACT rather than copy:
 * `frontend/lib/workshopOptions.ts` prints the same words for the same state, so a designer doing
 * one job on the laptop and on the phone is told one thing about the same workshop. A test that
 * merely asserted "says something" would let the two drift apart a word at a time until neither
 * screen means much, which is requirement 20's whole subject.
 *
 * Anyone deleting a case below should read what it asserts first. Each one is a defect that has
 * either shipped on this client or was one call site away from shipping.
 */
class WorkshopOptionsTest {

    private fun dw(
        id: String,
        title: String = id,
        status: String = "DRAFT",
        craft: String? = null,
        cluster: String? = null,
        state: String? = null,
        startDate: String? = null,
        createdAt: String? = null,
    ) = DesignWorkshopDto(
        id = id,
        title = title,
        status = status,
        craftName = craft,
        clusterName = cluster,
        state = state,
        startDate = startDate,
        createdAt = createdAt,
    )

    private fun fw(
        id: String,
        title: String = id,
        place: String = "",
        startDate: String? = null,
        date: String? = null,
        endDate: String? = null,
        createdAt: String? = null,
    ) = WorkshopDetailDto(
        id = id,
        title = title,
        place = place,
        startDate = startDate,
        date = date,
        endDate = endDate,
        createdAt = createdAt,
    )

    // ── The six sentences ────────────────────────────────────────────────────────────────────────

    /**
     * The offline sentence keeps its middle clause, and the clause is the sentence.
     *
     * Everything else on the screen is already telling the reader the list is empty. *"That is not a
     * claim that there are none"* is the only part doing work, and a shortened version of this
     * string — which is what an editor tidying copy would produce — puts the app back to reporting
     * an absence as a fact about the repository.
     */
    @Test
    fun `the offline sentence refuses to claim the list is empty`() {
        assertEquals(
            "This device has not received the artisans list yet, so there is nothing to pick here. " +
                "That is not a claim that there are none. Connect once and the list is kept on the " +
                "device from then on.",
            offlineListLine("artisans")
        )
    }

    /**
     * The failed-while-online sentence promises the record is still savable, because it appears on a
     * FORM.
     *
     * A designer halfway through an interview who reads that something failed will reasonably
     * assume their typing is at risk. This is the `OFFLINE_STATES` incident's lesson stated in
     * words rather than in a validator: the save must reach the outbox, and the screen has to say
     * so, or the designer starts again somewhere they trust more.
     */
    @Test
    fun `the read-failed sentence says the record still saves`() {
        assertEquals(
            "The design workshops list could not be loaded, so this is not showing what exists. " +
                "Nothing you have entered is at risk — this record can be saved without it.",
            couldNotListLine("design workshops")
        )
    }

    /**
     * A scope with nothing in it and a repository with nothing in it are different sentences with
     * different next moves — an administrator, or a record.
     *
     * Collapsing them is what produced `"No crafts available."` and `"No workshops to request
     * yet."`: claims about the repository made from a read that may simply have timed out.
     */
    @Test
    fun `scoped emptiness names an administrator and unscoped emptiness does not`() {
        assertEquals(
            "No design workshops are open to this account. An administrator can give you access to one.",
            scopedEmptyLine("design workshops")
        )
        assertEquals("No crafts have been recorded yet.", unscopedEmptyLine("crafts"))
        assertNotEquals(scopedEmptyLine("crafts"), unscopedEmptyLine("crafts"))
    }

    /**
     * The cached sentence carries the count AND the date, and it is refused to any caller that
     * cannot produce a real one.
     *
     * `DwReferenceStore` states the reason it exists at all: *"A list last refreshed an hour ago
     * that does not contain Ram Kumar means Ram Kumar has no artisan record and one should be
     * created; the same list refreshed nine days ago means nothing of the kind."* A date is what
     * makes the sentence worth printing; without one it is just another way of saying the list is
     * short.
     */
    @Test
    fun `the cached sentence carries both the count and the date`() {
        val line = cachedListLine(count = 42, noun = "artisans", refreshedOn = "2026-08-14")
        assertTrue("the count is stated", line.contains("42 artisans"))
        assertTrue("the date is stated", line.contains("2026-08-14"))
        assertTrue(
            "and it says what to do before concluding a name is absent",
            line.contains("refresh with a connection before concluding it is not on record")
        )
    }

    /** A bundled vocabulary is always answerable, so it has no sentence — recorded, not forgotten. */
    @Test
    fun `a bundled list says nothing because there is nothing to report`() {
        assertNull(BUNDLED_LIST_HAS_NO_SENTENCE)
    }

    // ── Which sentence a workshop picker is entitled to ──────────────────────────────────────────

    /**
     * THE DEFECT THIS WHOLE FILE EXISTS FOR. Three facts used to be spelled `emptyList()`, and the
     * one the designer saw read as "there are none".
     */
    @Test
    fun `loading, failed and answered-empty are three different sentences`() {
        val loading = workshopListNotice(WorkshopListState.Loading, WorkshopListKind.DESIGN, online = true)
        val failed = workshopListNotice(WorkshopListState.Failed, WorkshopListKind.DESIGN, online = true)
        val none = workshopListNotice(
            WorkshopListState.Listed(count = 0, total = 0), WorkshopListKind.DESIGN, online = true
        )

        assertEquals("Looking for your design workshops…", loading)
        assertNotEquals("a failed read is not a list still arriving", loading, failed)
        assertNotEquals("a failed read is not an empty account", failed, none)
        assertNotEquals("an empty account is not a list still arriving", none, loading)
    }

    /**
     * Offline and refused are told apart by the OUTBOX's classification, not by a network probe.
     *
     * The two have different next moves — walk outside, versus this is not showing what exists — and
     * `WorkshopRepository.isTransient` is the one place in this app that decides which a throwable
     * is. A second implementation of that judgement would let a screen call a dead tunnel a server
     * fault while the queue behind it retries the same throwable for a fortnight.
     */
    @Test
    fun `a failed read words itself differently offline`() {
        val offline = workshopListNotice(WorkshopListState.Failed, WorkshopListKind.DESIGN, online = false)
        val refused = workshopListNotice(WorkshopListState.Failed, WorkshopListKind.DESIGN, online = true)

        assertEquals(offlineListLine("design workshops"), offline)
        assertEquals(couldNotListLine("design workshops"), refused)
    }

    /**
     * NEITHER WORKSHOP PICKER MAY EVER SAY "have been recorded yet".
     *
     * Both lists are scoped by a grant — a `DesignWorkshopViewer` row on one, a `WorkshopAssignment`
     * on the other — so this account seeing none is not the platform holding none. A designer told
     * to go and create one, when the real remedy is an administrator, makes a duplicate workshop.
     */
    @Test
    fun `an empty workshop list is always the scoped sentence`() {
        for (kind in WorkshopListKind.entries) {
            val line = workshopListNotice(WorkshopListState.Listed(0, 0), kind, online = true)
            assertEquals(scopedEmptyLine(kind.noun), line)
            assertNotEquals(unscopedEmptyLine(kind.noun), line)
        }
    }

    /** A list that arrived with rows in it needs no explanation, and gets none. */
    @Test
    fun `a list with rows in it says nothing at all`() {
        assertNull(
            workshopListNotice(WorkshopListState.Listed(count = 4, total = 4), WorkshopListKind.DESIGN, true)
        )
    }

    /**
     * A READ THE DESIGNER NARROWED MAY NOT BE REPORTED AS A CLAIM ABOUT THEIR GRANTS.
     *
     * `DesignWorkshopSelect.tsx` writes the rule out for its SEARCH box: *"THE NOTICE IS ASKED OF THE
     * UNNARROWED LIST … printing 'No design workshops are open to this account. An administrator can
     * give you access to one.' underneath a search box somebody has just typed into is a claim about a
     * grant table produced by a filtered read."* The record-form picker on this handset has no search
     * box — `searchable = false` — so the TYPE is its only narrowing, and it is the same act.
     *
     * The cost of getting it wrong is not a wording: a designer on four Skill Upgradation workshops
     * who taps "Cluster Development" would be sent to an administrator to ask for access they already
     * have, by a sentence whose entire purpose is to send them there.
     */
    @Test
    fun `an empty answer to a narrowed read is not a claim about the account`() {
        val narrowed = workshopListNotice(
            WorkshopListState.Listed(count = 0, total = 0),
            WorkshopListKind.DESIGN,
            online = true,
            narrowed = true,
        )

        assertEquals(narrowedEmptyLine("design workshops"), narrowed)
        assertNotEquals(
            "a filtered read cannot support the scoped claim",
            scopedEmptyLine("design workshops"),
            narrowed,
        )
        // It has to name the way back, because R2 has just disabled the only other control on screen.
        assertTrue(narrowed!!.contains(ANY_WORKSHOP_KIND))
    }

    /**
     * AND IT CHANGES NOTHING ABOUT A READ THAT NEVER ANSWERED.
     *
     * A dropped connection with a type chosen is still a dropped connection. Wording it as "none of
     * this type" would hide a dead tunnel behind a control the designer would then go on fiddling
     * with, having been told the phone is fine.
     */
    @Test
    fun `narrowing does not re-word a read that failed`() {
        for (online in listOf(true, false)) {
            assertEquals(
                workshopListNotice(WorkshopListState.Failed, WorkshopListKind.DESIGN, online),
                workshopListNotice(WorkshopListState.Failed, WorkshopListKind.DESIGN, online, narrowed = true),
            )
        }
        assertEquals(
            workshopListNotice(WorkshopListState.Loading, WorkshopListKind.DESIGN, true),
            workshopListNotice(WorkshopListState.Loading, WorkshopListKind.DESIGN, true, narrowed = true),
        )
    }

    /** And an unnarrowed empty read still says what it always said. */
    @Test
    fun `the default is the unnarrowed sentence`() {
        assertEquals(
            scopedEmptyLine("design workshops"),
            workshopListNotice(WorkshopListState.Listed(0, 0), WorkshopListKind.DESIGN, online = true),
        )
    }

    // ── R2: mandatory only where answerable ──────────────────────────────────────────────────────

    /**
     * The one-line rule the `OFFLINE_STATES` incident produced, in the form the web writes it
     * (`&& options.length > 0`).
     *
     * It is asserted even though every current workshop caller is optional, for the reason
     * `LocationFields.tsx` gives for keeping its own dead clause: *"the invariant is what matters —
     * this card never demands an answer it is not offering — and a later change that narrowed or
     * dropped the bundled list would otherwise reintroduce a lost interview in silence."*
     */
    @Test
    fun `a field is answerable only when it has something to answer with`() {
        assertTrue(listIsAnswerable(listOf(SelectOption("a", "A"))))
        assertTrue("an empty list is never answerable", !listIsAnswerable(emptyList()))
    }

    // ── R4: every cap says so, with the number ───────────────────────────────────────────────────

    /**
     * BOTH NUMBERS, ALWAYS, and a screen to reach the rest.
     *
     * "Showing the first 20" alone leaves the reader guessing whether that is most of their
     * workshops or a sixth of them, and the difference is whether they go looking elsewhere or
     * conclude the workshop was never created. The sentence also names a DESTINATION rather than a
     * box, because the box on this control is switched off (§3.6) — pointing at it would be the
     * same lie one layer down.
     */
    @Test
    fun `the cap sentence prints both numbers and names where the rest are`() {
        val line = workshopCapLine(shown = 20, total = 121, kind = WorkshopListKind.DESIGN)
        assertEquals(
            "Showing the 20 most recent of 121. Open Design workshops to search the whole list, " +
                "then come back.",
            line
        )
    }

    /**
     * And it is silent when nothing was cut, so an ordinary designer on four workshops never reads a
     * sentence about a ceiling they cannot reach.
     *
     * The old sentence fired on `size >= 20`, which said "showing your 20 most recent" to a designer
     * holding exactly twenty workshops and hiding none of them.
     */
    @Test
    fun `a list that was not cut says nothing about a cap`() {
        assertNull(workshopCapLine(shown = 20, total = 20, kind = WorkshopListKind.DESIGN))
        assertNull(workshopCapLine(shown = 4, total = 4, kind = WorkshopListKind.DESIGN))
        assertNull("nothing loaded is a different sentence's job", workshopCapLine(0, 121, WorkshopListKind.DESIGN))
    }

    // ── §2.3 the label, §2.5 the order, §2.6 the archived answer ─────────────────────────────────

    /**
     * The label is the TITLE ALONE and everything that tells two workshops apart is in the hint.
     *
     * Folding the date into the label gives every row the same suffix, demotes nothing in the
     * filter's ranking, makes the label the wrong length for a handset row and leaves nowhere for a
     * third fact. The hint is searched as well as shown, so nothing becomes unreachable.
     */
    @Test
    fun `the label is the title alone and the facts ride in the hint`() {
        val option = designWorkshopOptions(
            listOf(dw("w1", "Chanderi weaving", craft = "Weaving", cluster = "Bagru", startDate = "2026-07-12"))
        ).single()

        assertEquals("Chanderi weaving", option.label)
        assertEquals("Weaving · Bagru · 2026-07-12", option.hint)
    }

    /** A workshop whose stage 1 is unfinished is still pickable, and does not render a blank row. */
    @Test
    fun `an untitled workshop is named rather than left blank`() {
        assertEquals("Untitled workshop", designWorkshopOptions(listOf(dw("w1", title = "   "))).single().label)
    }

    /**
     * A SUBMITTED or ARCHIVED workshop is OFFERED, and marked — never dropped and never disabled.
     *
     * A designer legitimately corrects a record already filed under a submitted workshop and the
     * server does not refuse it. Withholding the row would convert a read-only fact into a wrong
     * write: the record gets re-filed somewhere else, or is not saved at all.
     */
    @Test
    fun `archived and submitted workshops are still offered, with the word on them`() {
        val options = designWorkshopOptions(
            listOf(dw("w1", "Old round", status = "ARCHIVED"), dw("w2", "Filed round", status = "SUBMITTED"))
        )

        assertEquals(2, options.size)
        assertTrue(options.single { it.value == "w1" }.hint!!.startsWith("Archived"))
        assertTrue(options.single { it.value == "w2" }.hint!!.startsWith("Submitted"))
    }

    /** An unrecognised status from a newer server reads as open rather than being dressed as one of the five. */
    @Test
    fun `an unknown status is not printed as a known one`() {
        assertNull(designWorkshopStatusWord("SOME_FUTURE_STATE"))
        assertEquals(0, designWorkshopStanding(dw("w1", status = "SOME_FUTURE_STATE")))
    }

    /**
     * EVERY MEMBER OF `DesignWorkshopStatus` IS ANSWERED, and the three the review loop added on
     * 2026-09-13 are the reason this test exists.
     *
     * They fell to the `else` arm for a day: a report on an inspecting officer's desk, and a report
     * an officer had sent back with corrections, were drawn in the picker exactly like a draft
     * nobody had opened. Listing the statuses HERE rather than deriving them is deliberate — this
     * client has no enum to derive from, `status` is a plain `String` off the wire, so a hand-written
     * list is the only thing that can notice a member going unanswered. Add a status to the server's
     * enum, add a row here.
     */
    @Test
    fun `every design workshop status gets the word it should`() {
        assertNull(designWorkshopStatusWord("DRAFT"))
        assertNull(designWorkshopStatusWord("IN_PROGRESS"))
        assertNull(designWorkshopStatusWord("COMPLETE"))
        assertEquals("In pre-submission", designWorkshopStatusWord("PRE_SUBMISSION"))
        assertEquals("Needs revision", designWorkshopStatusWord("NEEDS_REVISION"))
        assertEquals("Approved", designWorkshopStatusWord("APPROVED"))
        assertEquals("Submitted", designWorkshopStatusWord("SUBMITTED"))
        assertEquals("Archived", designWorkshopStatusWord("ARCHIVED"))
    }

    /**
     * A REPORT SENT BACK FOR CORRECTIONS SORTS WITH THE OPEN ONES, not with the finished ones.
     *
     * `designWorkshopStanding` used to be `if (statusWord == null) 0 else 1`, which tied "carries a
     * word in the hint" to "is finished with". Those are one fact only while the words are Submitted
     * and Archived. NEEDS_REVISION is the counter-example that breaks the tie: it is the most open
     * state a report can be in — somebody is waiting on the designer holding this phone — and under
     * the derived rule it would have sorted to the bottom of the picker beside the archived rounds
     * the moment it was given the word it needs. PRE_SUBMISSION is the same argument: it is
     * withdrawable, so it is not over.
     */
    @Test
    fun `a report in the review loop sorts with the open workshops and not with the closed ones`() {
        assertEquals(0, designWorkshopStanding(dw("w", status = "NEEDS_REVISION")))
        assertEquals(0, designWorkshopStanding(dw("w", status = "PRE_SUBMISSION")))
        // APPROVED is terminal for the person holding this handset: both of its remaining moves are
        // the sanctioning authority's, so no new fieldwork is filed under it here.
        assertEquals(1, designWorkshopStanding(dw("w", status = "APPROVED")))
        assertEquals(1, designWorkshopStanding(dw("w", status = "SUBMITTED")))
        assertEquals(1, designWorkshopStanding(dw("w", status = "ARCHIVED")))

        val options = designWorkshopOptions(
            listOf(
                dw("approved", "Approved round", status = "APPROVED", startDate = "2026-08-01"),
                dw("sent-back", "Sent back", status = "NEEDS_REVISION", startDate = "2026-01-01"),
            )
        )
        assertEquals(listOf("sent-back", "approved"), options.map { it.value })
        assertTrue(options.single { it.value == "sent-back" }.hint!!.startsWith("Needs revision"))
    }

    /**
     * OPEN WORKSHOPS FIRST — the sort key that carries the web's group headings on a client whose
     * [SelectOption] has no group slot.
     *
     * New fieldwork does not belong in a submitted workshop, so the picker opens on the ones that
     * are still running however recently the closed ones ran.
     */
    @Test
    fun `open workshops sort above submitted and archived ones`() {
        val options = designWorkshopOptions(
            listOf(
                dw("closed", "Closed", status = "SUBMITTED", startDate = "2026-08-01"),
                dw("open", "Open", startDate = "2026-01-01"),
            )
        )

        assertEquals(listOf("open", "closed"), options.map { it.value })
    }

    /**
     * BY OCCURRENCE, NEWEST FIRST — never by creation.
     *
     * *"A workshop entered into the system last is not the workshop that ran last."* Every design
     * workshop picker inherits `createdAt desc` from the server and, until this file, none of them
     * re-sorted; `createdAt` is the last resort here and not the answer.
     */
    @Test
    fun `workshops are ordered by when they ran and not by when they were typed in`() {
        val options = designWorkshopOptions(
            listOf(
                dw("typed-last", startDate = "2025-02-01", createdAt = "2026-08-29"),
                dw("ran-last", startDate = "2026-08-01", createdAt = "2024-01-01"),
                dw("no-start", createdAt = "2026-06-01"),
            )
        )

        assertEquals(listOf("ran-last", "no-start", "typed-last"), options.map { it.value })
    }

    /**
     * The tiebreaks are not decoration. A page of workshops sharing a start date would otherwise come
     * out in whatever order the server's non-total sort produced, and a picker whose rows move
     * between two openings is one a designer stops trusting. `id` is last because it is the only key
     * guaranteed unique — the same reason `with_id_tiebreak` exists on the server.
     */
    @Test
    fun `a shared date is broken by title and then by id`() {
        val options = designWorkshopOptions(
            listOf(
                dw("z", "Same name", startDate = "2026-05-05"),
                dw("a", "Same name", startDate = "2026-05-05"),
                dw("m", "Another name", startDate = "2026-05-05"),
            )
        )

        assertEquals(listOf("m", "a", "z"), options.map { it.value })
    }

    // ── The off-page row ─────────────────────────────────────────────────────────────────────────

    /**
     * A RECORD ALREADY FILED UNDER A WORKSHOP THIS DEVICE COULD NOT LIST MUST NOT READ AS UNFILED.
     *
     * The trigger draws its label by looking the selected value up in the options and falls back to
     * the placeholder, so without this row an edit opened offline printed "Not filed under a design
     * workshop" over a record that IS filed — the screen stating the opposite of the stored value,
     * and the designer's obvious next move quietly re-files a month of fieldwork.
     *
     * It became reachable the moment the field started standing down on an empty list, which is why
     * it lands in the same change.
     */
    @Test
    fun `a stored workshop the list does not hold keeps a row of its own`() {
        val options = designWorkshopOptions(rows = emptyList(), offPageId = "gone-from-this-page")

        assertEquals(1, options.size)
        assertEquals("gone-from-this-page", options.single().value)
        assertEquals("The design workshop already on this record", options.single().label)
    }

    /**
     * AND THE HINT TELLS THE TWO ABSENCES APART, because one of them stopped being true.
     *
     * *"this device could not list it just now"* was accurate for every way this row could be reached
     * before the type box existed: a failed read, an offline phone, a workshop past the end of one
     * truncated page. A narrowed read is none of those — the device is online, the read succeeded,
     * and the workshop is sitting in the register perfectly listable; it simply was not asked for.
     * Left unchanged, the row would be a comment contradicting the code, printed on screen, in front
     * of the one reader who cannot check it — and its obvious reading, *something is wrong with this
     * phone*, sends a designer looking for a fault instead of at the box two lines above.
     */
    @Test
    fun `the off-page row says which absence this is`() {
        val unlisted = designWorkshopOptions(rows = emptyList(), offPageId = "w9").single()
        val filteredOut = designWorkshopOptions(rows = emptyList(), offPageId = "w9", narrowed = true).single()

        assertEquals("the label is the same row either way", unlisted.label, filteredOut.label)
        assertNotEquals("but the reason for the absence is not", unlisted.hint, filteredOut.hint)
        assertTrue(unlisted.hint!!.contains("could not list it"))
        assertFalse(
            "a narrowed read must not blame the device for a list it was never asked for",
            filteredOut.hint!!.contains("could not list it"),
        )
        assertTrue("and must point at the control that did it", filteredOut.hint!!.contains("type chosen above"))
    }

    /** And it never duplicates a row the list already holds, nor grows one for a blank selection. */
    @Test
    fun `the off-page row appears only when the workshop is genuinely absent`() {
        assertEquals(1, designWorkshopOptions(listOf(dw("w1")), offPageId = "w1").size)
        assertEquals(1, designWorkshopOptions(listOf(dw("w1")), offPageId = "").size)
        assertEquals(1, designWorkshopOptions(listOf(dw("w1")), offPageId = "   ").size)
    }

    // ── Field workshops: the window ──────────────────────────────────────────────────────────────

    /**
     * THE WHOLE OF THE END DAY IS STILL IN WINDOW, mirroring the backend rule and the web's
     * `endedLocally`.
     *
     * A workshop that ends today has not ended. One day out marks a workshop the researcher is
     * standing in as over, and the late-submission dialog then asks them to confirm a late
     * submission that is not late.
     */
    @Test
    fun `a workshop ending today has not ended`() {
        val today = LocalDate.parse("2026-08-30")

        assertNull(fieldWorkshopStatusWord(fw("w", endDate = "2026-08-30"), today))
        assertEquals("Ended", fieldWorkshopStatusWord(fw("w", endDate = "2026-08-29"), today))
        assertNull(fieldWorkshopStatusWord(fw("w", endDate = "2026-09-01"), today))
        assertNull("a workshop with no dates at all is not declared over", fieldWorkshopStatusWord(fw("w"), today))
    }

    /** Ended workshops stay offered — the pre-flight and its dialog are what govern saving into one. */
    @Test
    fun `ended field workshops are offered, marked, and sorted below the running ones`() {
        val today = LocalDate.parse("2026-08-30")
        val options = fieldWorkshopOptions(
            rows = listOf(
                fw("over", "Last month", place = "Bagru", endDate = "2026-07-30", startDate = "2026-07-01"),
                fw("running", "This week", place = "Nuapatna", startDate = "2026-08-28"),
            ),
            today = today,
        )

        assertEquals(listOf("running", "over"), options.map { it.value })
        assertEquals("Bagru · 2026-07-01", options.single { it.value == "over" }.hint?.removePrefix("Ended · "))
        assertTrue(options.single { it.value == "over" }.hint!!.startsWith("Ended"))
    }

    /** The none-row constants are four different meanings and may not be collapsed to one string. */
    @Test
    fun `the four none rows say four different things`() {
        val all = listOf(NO_DESIGN_WORKSHOP, NO_FIELD_WORKSHOP, ATTACH_LATER, TYPE_DETAILS_INSTEAD)
        assertEquals("nine strings collapse to four, not to three", 4, all.toSet().size)
        assertEquals("Not filed under a design workshop", NO_DESIGN_WORKSHOP)
        assertEquals("Not linked to a workshop", NO_FIELD_WORKSHOP)
    }
}
