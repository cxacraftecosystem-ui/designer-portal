package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class DwInterruptedCreateTest {

    private fun row(
        id: String,
        title: String,
        createdById: String?,
        createdAt: String? = null,
    ) = DesignWorkshopDto(id = id, title = title, createdById = createdById, createdAt = createdAt)

    private fun serverStamp(instant: Instant): String =
        OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).toString()

    private val stamp: Instant = Instant.parse("2026-08-22T06:00:00Z")

    @Test
    fun `exactly one workshop this account made under this title is the interrupted create`() {
        val answer = dwResumedCreateFrom(
            title = "Sambalpuri ikat, Bargarh",
            sessionUserId = "admin-1",
            rows = listOf(row("dw-9", "Sambalpuri ikat, Bargarh", "admin-1")),
            claimedRemoteIds = emptySet(),
        )
        assertEquals(DwResumedCreate.Found("dw-9"), answer)
    }

    @Test
    fun `two candidates are refused rather than guessed between`() {
        val answer = dwResumedCreateFrom(
            title = "Untitled design workshop",
            sessionUserId = "admin-1",
            rows = listOf(
                row("dw-1", "Untitled design workshop", "admin-1"),
                row("dw-2", "Untitled design workshop", "admin-1"),
            ),
            claimedRemoteIds = emptySet(),
        )
        assertTrue(answer is DwResumedCreate.Ambiguous)
    }

    @Test
    fun `a workshop another account created is never adopted`() {
        val answer = dwResumedCreateFrom(
            title = "Sambalpuri ikat, Bargarh",
            sessionUserId = "admin-1",
            rows = listOf(row("dw-9", "Sambalpuri ikat, Bargarh", "admin-2")),
            claimedRemoteIds = emptySet(),
        )
        assertEquals(DwResumedCreate.None, answer)
    }

    @Test
    fun `a workshop another draft on this phone already points at is not a candidate`() {
        val answer = dwResumedCreateFrom(
            title = "Sambalpuri ikat, Bargarh",
            sessionUserId = "admin-1",
            rows = listOf(row("dw-9", "Sambalpuri ikat, Bargarh", "admin-1")),
            claimedRemoteIds = setOf("dw-9"),
        )
        assertEquals(DwResumedCreate.None, answer)
    }

    @Test
    fun `excluding a claimed row can turn ambiguity back into an answer`() {
        val answer = dwResumedCreateFrom(
            title = "Untitled design workshop",
            sessionUserId = "admin-1",
            rows = listOf(
                row("dw-1", "Untitled design workshop", "admin-1"),
                row("dw-2", "Untitled design workshop", "admin-1"),
            ),
            claimedRemoteIds = setOf("dw-1"),
        )
        assertEquals(DwResumedCreate.Found("dw-2"), answer)
    }

    @Test
    fun `a title that merely contains the search term is not this draft's workshop`() {
        val answer = dwResumedCreateFrom(
            title = "Ikat",
            sessionUserId = "admin-1",
            rows = listOf(row("dw-9", "Ikat, Bargarh — phase two", "admin-1")),
            claimedRemoteIds = emptySet(),
        )
        assertEquals(DwResumedCreate.None, answer)
    }

    @Test
    fun `with no signed-in account nothing is adopted`() {
        assertEquals(
            DwResumedCreate.None,
            dwResumedCreateFrom(
                title = "Sambalpuri ikat, Bargarh",
                sessionUserId = null,
                rows = listOf(row("dw-9", "Sambalpuri ikat, Bargarh", null)),
                claimedRemoteIds = emptySet(),
            )
        )
        assertEquals(
            DwResumedCreate.None,
            dwResumedCreateFrom(
                title = "Sambalpuri ikat, Bargarh",
                sessionUserId = "",
                rows = listOf(row("dw-9", "Sambalpuri ikat, Bargarh", "")),
                claimedRemoteIds = emptySet(),
            )
        )
    }

    @Test
    fun `a row the server sent with no id is not a candidate`() {
        val answer = dwResumedCreateFrom(
            title = "Sambalpuri ikat, Bargarh",
            sessionUserId = "admin-1",
            rows = listOf(row("", "Sambalpuri ikat, Bargarh", "admin-1")),
            claimedRemoteIds = emptySet(),
        )
        assertEquals(DwResumedCreate.None, answer)
    }

    @Test
    fun `an empty server list means the create never landed`() {
        assertEquals(
            DwResumedCreate.None,
            dwResumedCreateFrom("Anything", "admin-1", emptyList(), emptySet())
        )
    }

    @Test
    fun `the refusal names what was kept before it names what to do`() {
        assertTrue(
            "an ambiguous create must say nothing has been deleted",
            DW_WORKSHOP_CREATE_AMBIGUOUS.contains("Nothing on this phone has been deleted")
        )
        assertTrue(
            "it must name the control that finishes the job",
            DW_WORKSHOP_CREATE_AMBIGUOUS.contains("Try again")
        )
    }

    @Test
    fun `last month's workshop of the same name is not this week's interrupted create`() {
        val answer = dwResumedCreateFrom(
            title = "Sambalpuri ikat, Bargarh",
            sessionUserId = "admin-1",
            rows = listOf(
                row("dw-july", "Sambalpuri ikat, Bargarh", "admin-1", serverStamp(stamp.minus(Duration.ofDays(35))))
            ),
            claimedRemoteIds = emptySet(),
            createSentAt = stamp.toString(),
        )
        assertEquals(DwResumedCreate.None, answer)
    }

    @Test
    fun `a clock days out of step still finds the create`() {
        val answer = dwResumedCreateFrom(
            title = "Sambalpuri ikat, Bargarh",
            sessionUserId = "admin-1",
            rows = listOf(
                row("dw-9", "Sambalpuri ikat, Bargarh", "admin-1", serverStamp(stamp.minus(Duration.ofDays(6))))
            ),
            claimedRemoteIds = emptySet(),
            createSentAt = stamp.toString(),
        )
        assertEquals(DwResumedCreate.Found("dw-9"), answer)
    }

    @Test
    fun `an unreadable or absent timestamp keeps the candidate rather than dropping it`() {
        val rows = listOf(row("dw-9", "Sambalpuri ikat, Bargarh", "admin-1", createdAt = null))
        assertEquals(
            DwResumedCreate.Found("dw-9"),
            dwResumedCreateFrom("Sambalpuri ikat, Bargarh", "admin-1", rows, emptySet(), stamp.toString())
        )
        assertEquals(
            DwResumedCreate.Found("dw-9"),
            dwResumedCreateFrom(
                title = "Sambalpuri ikat, Bargarh",
                sessionUserId = "admin-1",
                rows = listOf(row("dw-9", "Sambalpuri ikat, Bargarh", "admin-1", "not a date")),
                claimedRemoteIds = emptySet(),
                createSentAt = stamp.toString(),
            )
        )
        assertEquals(
            DwResumedCreate.Found("dw-9"),
            dwResumedCreateFrom(
                title = "Sambalpuri ikat, Bargarh",
                sessionUserId = "admin-1",
                rows = listOf(
                    row("dw-9", "Sambalpuri ikat, Bargarh", "admin-1", serverStamp(stamp.minus(Duration.ofDays(90))))
                ),
                claimedRemoteIds = emptySet(),
                createSentAt = null,
            )
        )
    }

    @Test
    fun `a naive server timestamp is read rather than dropped`() {
        val answer = dwResumedCreateFrom(
            title = "Sambalpuri ikat, Bargarh",
            sessionUserId = "admin-1",
            rows = listOf(row("dw-july", "Sambalpuri ikat, Bargarh", "admin-1", "2026-07-18T09:30:00.123456")),
            claimedRemoteIds = emptySet(),
            createSentAt = stamp.toString(),
        )
        assertEquals(DwResumedCreate.None, answer)
    }

    @Test
    fun `the row the stage screen minted for this very create does not claim it`() {
        val claims = listOf(
            DwDraftClaim(
                remoteId = null,
                title = "Sambalpuri ikat, Bargarh",
                idComesFromTheKeyAlone = false,
                awaitingCreate = true,
            ),
            DwDraftClaim(
                remoteId = "dw-9",
                title = "Sambalpuri ikat, Bargarh",
                idComesFromTheKeyAlone = true,
                awaitingCreate = false,
            ),
        )
        assertEquals(emptySet<String>(), dwClaimedRemoteIds(claims))
    }

    @Test
    fun `a draft whose own create landed keeps its claim on the same title`() {
        val claims = listOf(
            DwDraftClaim(
                remoteId = null,
                title = "Sambalpuri ikat, Bargarh",
                idComesFromTheKeyAlone = false,
                awaitingCreate = true,
            ),
            DwDraftClaim(
                remoteId = "dw-morning",
                title = "Sambalpuri ikat, Bargarh",
                idComesFromTheKeyAlone = false,
                awaitingCreate = false,
            ),
        )
        assertEquals(setOf("dw-morning"), dwClaimedRemoteIds(claims))
    }

    @Test
    fun `an unrelated title is claimed however the draft was filed`() {
        val claims = listOf(
            DwDraftClaim(
                remoteId = null,
                title = "Sambalpuri ikat, Bargarh",
                idComesFromTheKeyAlone = false,
                awaitingCreate = true,
            ),
            DwDraftClaim(
                remoteId = "dw-other",
                title = "Pattachitra, Raghurajpur",
                idComesFromTheKeyAlone = true,
                awaitingCreate = false,
            ),
        )
        assertEquals(setOf("dw-other"), dwClaimedRemoteIds(claims))
    }

    @Test
    fun `a create that is already on the server is not declined, whoever is signed in`() {
        assertTrue(createMustBeDeclined(alreadyOnServer = false, sessionMayCreate = false))
        assertTrue(!createMustBeDeclined(alreadyOnServer = true, sessionMayCreate = false))
    }
}
