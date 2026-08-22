package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * A WORKSHOP CREATE THAT COULD BE FILED TWICE, AND A COMMENT THAT SAID IT COULD NOT.
 *
 * `WorkshopSync`'s create arm passed `alreadyOnServer = false` as a literal, and the paragraph above
 * it justified the literal like this: *"Android has no counterpart: this pass is the only thing that
 * creates, and it learns the remote id from its own response, so a draft reaching this line has never
 * been created and `false` is a fact rather than a default."*
 *
 * **EVERY CLAUSE OF THAT IS FALSE EXCEPT THE LAST TWO WORDS OF THE FIRST.** `CreateWorkshopDialog`
 * (`ui/designworkshop/WorkshopListScreen.kt`) POSTs `design-workshops` as well. `classifyCreate` maps
 * a READ TIMEOUT — the server committed the row and the reply was lost — to `CreateOutcome.Local`,
 * whose whole purpose is to mint a `dwlocal-` draft with `remoteId = null`. The next sync pass reads
 * that null, and `POST /design-workshops` de-duplicates nothing: no `_clientKey`, no match on title,
 * nothing. One tap, two `DesignWorkshop` rows under one title in a government index, one of them
 * empty for ever. No process death is required — which is what made the comment so expensive, because
 * it read as an audited invariant and nobody looked again.
 *
 * ── WHAT IS PINNED HERE ──────────────────────────────────────────────────────────────────────────
 *
 * [dwResumedCreateFrom], which is the whole decision. The request that feeds it is not pinned and
 * deliberately is not: it is one `designWorkshops(page = 1, pageSize = 100, search = title)` call and
 * a `catch` that answers [DwResumedCreate.None], and both of those are argued at the call site.
 *
 * THE AMBIGUOUS ARM IS THE ONE TO READ FIRST. Two workshops one admin titled the same way is a thing
 * a person can do — twice from the same default title, most obviously — and a resolver that picked
 * the newer one would move a fortnight of stages into the wrong ministry record under a 200. Refusing
 * is the only honest answer available to a device that cannot ask the server "is my create in there",
 * and this file exists to stop a later lane "improving" it into a guess.
 *
 * AND TWO THINGS THE FIRST VERSION OF THIS FILE DID NOT PIN, both added after review found them:
 *
 *  * THE CREATED-SINCE FLOOR. Refusing the date filter outright left one candidate — last month's
 *    identically titled workshop — indistinguishable from this week's lost create, and the Ambiguous
 *    arm cannot catch it because one is not two. `DW_RESUMED_CREATE_LOOKBACK` is a week, which no
 *    clock skew crosses and no previous campaign clears, and every unknown fails OPEN.
 *  * WHICH IDS COUNT AS ALREADY SPOKEN FOR, [dwClaimedRemoteIds]. The web's `titlesAwaitingACreate`
 *    was not ported with the resolver, and without it the ordinary user move — open the workshop
 *    that DID land and start stage 1 — made the row look claimed and filed the duplicate anyway.
 *
 * Kept in step with `frontend/lib/designWorkshopStore.ts::resolveInterruptedCreate` and its
 * companion `titlesAwaitingACreate`. The filters are the web's, plus the floor, which the web does
 * not have: its comment rejecting the date is the one this repository has now walked back.
 */
class DwInterruptedCreateTest {

    private fun row(
        id: String,
        title: String,
        createdById: String?,
        createdAt: String? = null,
    ) = DesignWorkshopDto(id = id, title = title, createdById = createdById, createdAt = createdAt)

    /** The shape the API actually sends: Python's `datetime.isoformat()` on an aware datetime. */
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
        // THE ASSERTION THE WHOLE FILE IS FOR. `createdAt` would order these; ordering them is how a
        // fortnight of stages lands in the wrong record under a 200.
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
        // The title is a weak claim on a government record and the owner is what makes it a claim at
        // all. An admin who happens to have titled a workshop the same way is not this device's.
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
        // Two drafts adopting one workshop is the failure this excludes: both would then push their
        // own twenty-two stages into it, and each save would sweep the other's rows.
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
        // Not a corner case: it is the ordinary shape of "I already moved one of these by hand".
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
        // The server's `search` filter is a CONTAINS over the promoted columns, so the response is a
        // superset. The equality here is what narrows it, and dropping it would adopt a neighbouring
        // cluster's record.
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
        // Stated rather than asserted: a sync pass is the wrong place to throw, and without an owner
        // to compare against, the title alone is too weak a claim on a government record.
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
        // `DesignWorkshopDto.id` is defaulted to the empty string, so a payload from a captive portal
        // or a future API decodes with a blank id rather than failing. Adopting one would write ""
        // into `remoteId` and point the whole fortnight at nothing.
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
        // The ordinary answer, and the one that must stay cheap: it is what a stamp written for a
        // request that never left the handset resolves to.
        assertEquals(
            DwResumedCreate.None,
            dwResumedCreateFrom("Anything", "admin-1", emptyList(), emptySet())
        )
    }

    @Test
    fun `the refusal names what was kept before it names what to do`() {
        // The sentence a person reads while holding a fortnight of fieldwork with a red mark on it.
        // `DW_WORKSHOP_CREATE_DECLINED_BY_APP` obeys the same rule and for the same reason.
        assertTrue(
            "an ambiguous create must say nothing has been deleted",
            DW_WORKSHOP_CREATE_AMBIGUOUS.contains("Nothing on this phone has been deleted")
        )
        assertTrue(
            "it must name the control that finishes the job",
            DW_WORKSHOP_CREATE_AMBIGUOUS.contains("Try again")
        )
    }

    // ── The created-since floor ──────────────────────────────────────────────────────────────────

    @Test
    fun `last month's workshop of the same name is not this week's interrupted create`() {
        // THE ADOPTION THE THREE ORIGINAL FILTERS COULD NOT REFUSE. One candidate is not two, so the
        // Ambiguous arm never sees this: an admin who ran the same cluster in July and typed the same
        // title in August would have had August's fortnight pushed into July's ministry record under
        // a 200.
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
        // The reason the floor is a week and not an hour. The handset's stamp and the server's
        // `createdAt` are written by two clocks that have not spoken for a fortnight; a tight window
        // would reject the very record it exists to find and file the duplicate anyway.
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
        // FAILS OPEN, BOTH WAYS ROUND. Dropping a candidate costs the duplicate this whole function
        // exists to prevent, so an unparseable stamp on either side is judged on the other filters.
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
        // `datetime.isoformat()` carries no offset for a naive datetime. Read as UTC: the floor is a
        // week wide, so hours cannot change the answer, and dropping the value would widen the hole.
        val answer = dwResumedCreateFrom(
            title = "Sambalpuri ikat, Bargarh",
            sessionUserId = "admin-1",
            rows = listOf(row("dw-july", "Sambalpuri ikat, Bargarh", "admin-1", "2026-07-18T09:30:00.123456")),
            claimedRemoteIds = emptySet(),
            createSentAt = stamp.toString(),
        )
        assertEquals(DwResumedCreate.None, answer)
    }

    // ── Which ids count as already spoken for ────────────────────────────────────────────────────

    @Test
    fun `the row the stage screen minted for this very create does not claim it`() {
        // THE PORT'S MISSING HALF (`titlesAwaitingACreate` on the web). The create landed, the answer
        // was lost, and the designer — seeing the workshop in the online list, because it really is
        // up there — opened it and saved stage 1. `persistLocally` then filed a second draft under
        // the SERVER's id, and counting that as a claim sent the answer from "found it" to "create
        // another one".
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
        // THE NARROWING, AND WHY IT IS NOT A TITLE RULE. An admin who made this workshop this morning
        // and started a second one of the same name this afternoon must not have the afternoon's
        // fortnight adopted into the morning's record: that draft carries the id in its own field,
        // which is the one thing the stage screen's never does.
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
        // The trap `createMustBeDeclined` takes two arguments for, now reachable on Android: a demoted
        // admin holding a draft whose create LANDED needs no create, so refusing it would strand a
        // workshop that exists and send them to ask for one they already have.
        assertTrue(createMustBeDeclined(alreadyOnServer = false, sessionMayCreate = false))
        assertTrue(!createMustBeDeclined(alreadyOnServer = true, sessionMayCreate = false))
    }
}
