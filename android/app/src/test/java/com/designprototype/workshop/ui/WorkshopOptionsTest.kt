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
        handedOnAt: String? = null,
    ) = DesignWorkshopDto(
        id = id,
        title = title,
        status = status,
        craftName = craft,
        clusterName = cluster,
        state = state,
        startDate = startDate,
        createdAt = createdAt,
        handedOnAt = handedOnAt,
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

    @Test
    fun `the offline sentence refuses to claim the list is empty`() {
        assertEquals(
            "No artisans list yet. Connect once to download it.",
            offlineListLine("artisans")
        )
    }

    @Test
    fun `the read-failed sentence says the record still saves`() {
        assertEquals(
            "Couldn't load design workshops. You can still save without it.",
            couldNotListLine("design workshops")
        )
    }

    @Test
    fun `scoped emptiness names an administrator and unscoped emptiness does not`() {
        assertEquals(
            "No design workshops available. An admin can give you access.",
            scopedEmptyLine("design workshops")
        )
        assertEquals("No crafts have been recorded yet.", unscopedEmptyLine("crafts"))
        assertNotEquals(scopedEmptyLine("crafts"), unscopedEmptyLine("crafts"))
    }

    @Test
    fun `a workshop with no roster is not a repository with no artisans`() {
        val line = atWorkshopEmptyLine("artisans")

        assertTrue("it says WHICH workshop it is about", line.contains("at this workshop"))
        assertTrue(
            "and it refuses to be read as a claim about the rest",
            line.contains("not a claim about your other artisans")
        )

        assertNotEquals(
            "an empty roster is not an empty repository — that is the duplicate-artisan report",
            unscopedEmptyLine("artisans"),
            line
        )
        assertNotEquals(
            "an empty roster is not a missing grant — nobody needs an administrator for this",
            scopedEmptyLine("artisans"),
            line
        )
        assertNotEquals(
            "and the way out is not to widen the workshop box, which would misfile the interview",
            narrowedEmptyLine("artisans"),
            line
        )
        assertNotEquals("nor a read that has not answered", loadingListLine("artisans"), line)
        assertNotEquals("nor a read that was refused", couldNotListLine("artisans"), line)
        assertNotEquals("nor a list this device has never received", offlineListLine("artisans"), line)
    }

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

    @Test
    fun `a bundled list says nothing because there is nothing to report`() {
        assertNull(BUNDLED_LIST_HAS_NO_SENTENCE)
    }

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

    @Test
    fun `a failed read words itself differently offline`() {
        val offline = workshopListNotice(WorkshopListState.Failed, WorkshopListKind.DESIGN, online = false)
        val refused = workshopListNotice(WorkshopListState.Failed, WorkshopListKind.DESIGN, online = true)

        assertEquals(offlineListLine("design workshops"), offline)
        assertEquals(couldNotListLine("design workshops"), refused)
    }

    @Test
    fun `an empty workshop list is always the scoped sentence`() {
        for (kind in WorkshopListKind.entries) {
            val line = workshopListNotice(WorkshopListState.Listed(0, 0), kind, online = true)
            assertEquals(scopedEmptyLine(kind.noun), line)
            assertNotEquals(unscopedEmptyLine(kind.noun), line)
        }
    }

    @Test
    fun `a list with rows in it says nothing at all`() {
        assertNull(
            workshopListNotice(WorkshopListState.Listed(count = 4, total = 4), WorkshopListKind.DESIGN, true)
        )
    }

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
        assertTrue(narrowed!!.contains(ANY_WORKSHOP_KIND))
    }

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

    @Test
    fun `the default is the unnarrowed sentence`() {
        assertEquals(
            scopedEmptyLine("design workshops"),
            workshopListNotice(WorkshopListState.Listed(0, 0), WorkshopListKind.DESIGN, online = true),
        )
    }

    @Test
    fun `a field is answerable only when it has something to answer with`() {
        assertTrue(listIsAnswerable(listOf(SelectOption("a", "A"))))
        assertTrue("an empty list is never answerable", !listIsAnswerable(emptyList()))
    }

    @Test
    fun `the cap sentence prints both numbers and names where the rest are`() {
        val line = workshopCapLine(shown = 20, total = 121, kind = WorkshopListKind.DESIGN)
        assertEquals(
            "Latest 20 of 121. Search in Design workshops for others.",
            line
        )
    }

    @Test
    fun `a list that was not cut says nothing about a cap`() {
        assertNull(workshopCapLine(shown = 20, total = 20, kind = WorkshopListKind.DESIGN))
        assertNull(workshopCapLine(shown = 4, total = 4, kind = WorkshopListKind.DESIGN))
        assertNull("nothing loaded is a different sentence's job", workshopCapLine(0, 121, WorkshopListKind.DESIGN))
    }

    @Test
    fun `the label is the title alone and the facts ride in the hint`() {
        val option = designWorkshopOptions(
            listOf(dw("w1", "Chanderi weaving", craft = "Weaving", cluster = "Bagru", startDate = "2026-07-12"))
        ).single()

        assertEquals("Chanderi weaving", option.label)
        assertEquals("Weaving · Bagru · 2026-07-12", option.hint)
    }

    @Test
    fun `an untitled workshop is named rather than left blank`() {
        assertEquals("Workshop", designWorkshopOptions(listOf(dw("w1", title = "   "))).single().label)
    }

    @Test
    fun `archived and submitted workshops are still offered, with the word on them`() {
        val options = designWorkshopOptions(
            listOf(dw("w1", "Old round", status = "ARCHIVED"), dw("w2", "Filed round", status = "SUBMITTED"))
        )

        assertEquals(2, options.size)
        assertTrue(options.single { it.value == "w1" }.hint!!.startsWith("Archived"))
        assertTrue(options.single { it.value == "w2" }.hint!!.startsWith("Submitted"))
    }

    @Test
    fun `an unknown status is not printed as a known one`() {
        assertNull(designWorkshopStatusWord("SOME_FUTURE_STATE"))
        assertEquals(0, designWorkshopStanding(dw("w1", status = "SOME_FUTURE_STATE")))
    }

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

    @Test
    fun `a report in the review loop sorts with the open workshops and not with the closed ones`() {
        assertEquals(0, designWorkshopStanding(dw("w", status = "NEEDS_REVISION")))
        assertEquals(0, designWorkshopStanding(dw("w", status = "PRE_SUBMISSION")))
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

    @Test
    fun `a stored workshop the list does not hold keeps a row of its own`() {
        val options = designWorkshopOptions(rows = emptyList(), offPageId = "gone-from-this-page")

        assertEquals(1, options.size)
        assertEquals("gone-from-this-page", options.single().value)
        assertEquals("The design workshop already on this record", options.single().label)
    }

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

    @Test
    fun `the off-page row appears only when the workshop is genuinely absent`() {
        assertEquals(1, designWorkshopOptions(listOf(dw("w1")), offPageId = "w1").size)
        assertEquals(1, designWorkshopOptions(listOf(dw("w1")), offPageId = "").size)
        assertEquals(1, designWorkshopOptions(listOf(dw("w1")), offPageId = "   ").size)
    }

    @Test
    fun `a workshop ending today has not ended`() {
        val today = LocalDate.parse("2026-08-30")

        assertNull(fieldWorkshopStatusWord(fw("w", endDate = "2026-08-30"), today))
        assertEquals("Ended", fieldWorkshopStatusWord(fw("w", endDate = "2026-08-29"), today))
        assertNull(fieldWorkshopStatusWord(fw("w", endDate = "2026-09-01"), today))
        assertNull("a workshop with no dates at all is not declared over", fieldWorkshopStatusWord(fw("w"), today))
    }

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

    @Test
    fun `the four none rows say four different things`() {
        val all = listOf(NO_DESIGN_WORKSHOP, NO_FIELD_WORKSHOP, ATTACH_LATER, TYPE_DETAILS_INSTEAD)
        assertEquals("nine strings collapse to four, not to three", 4, all.toSet().size)
        assertEquals("Not filed under a design workshop", NO_DESIGN_WORKSHOP)
        assertEquals("Not linked to a workshop", NO_FIELD_WORKSHOP)
    }

    @Test
    fun `every design workshop status has a plain word on the lists`() {
        assertEquals("Draft", designWorkshopStatusLabel("DRAFT"))
        assertEquals("In progress", designWorkshopStatusLabel("IN_PROGRESS"))
        assertEquals("Complete", designWorkshopStatusLabel("COMPLETE"))
        assertEquals("In pre-submission", designWorkshopStatusLabel("PRE_SUBMISSION"))
        assertEquals("Needs revision", designWorkshopStatusLabel("NEEDS_REVISION"))
        assertEquals("Approved", designWorkshopStatusLabel("APPROVED"))
        assertEquals("Submitted", designWorkshopStatusLabel("SUBMITTED"))
        assertEquals("Archived", designWorkshopStatusLabel("ARCHIVED"))
        for (token in listOf("PRE_SUBMISSION", "NEEDS_REVISION", "APPROVED", "SUBMITTED", "ARCHIVED")) {
            assertEquals(token, designWorkshopStatusLabel(token), designWorkshopStatusWord(token))
        }
    }

    @Test
    fun `a handed-on report says so, and a legacy submitted one does not`() {
        val handedOn = "2026-10-10T08:00:00+00:00"
        assertEquals("Handed on", designWorkshopStatusLabel("SUBMITTED", handedOn))
        assertEquals("Handed on", designWorkshopStatusWord("SUBMITTED", handedOn))
        assertEquals("Submitted", designWorkshopStatusLabel("SUBMITTED", null))
        assertEquals("Approved", designWorkshopStatusLabel("APPROVED", handedOn))

        val options = designWorkshopOptions(
            listOf(
                dw("gone", "Handed-on round", status = "SUBMITTED", handedOnAt = handedOn),
                dw("legacy", "Old round", status = "SUBMITTED"),
            )
        )
        assertTrue(options.single { it.value == "gone" }.hint!!.startsWith("Handed on"))
        assertTrue(options.single { it.value == "legacy" }.hint!!.startsWith("Submitted"))
        assertEquals(1, designWorkshopStanding(dw("gone", status = "SUBMITTED", handedOnAt = handedOn)))
    }

    @Test
    fun `an unknown status is printed as its own words on the lists`() {
        assertEquals("some future state", designWorkshopStatusLabel("SOME_FUTURE_STATE"))
        assertNull(designWorkshopStatusWord("SOME_FUTURE_STATE", "2026-10-10T08:00:00+00:00"))
    }
}
