package com.designprototype.workshop.data

import com.designprototype.workshop.ui.RegisterLoad
import com.designprototype.workshop.ui.RegisterSource
import com.designprototype.workshop.ui.designWorkshopStanding
import com.designprototype.workshop.ui.fieldWorkshopStatusWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * THE ALLOTTED-WORKSHOP CACHE, AND THE FOUR WAYS AN OFFLINE ACCESS LIST GOES WRONG.
 *
 * Every rule under test here fails SILENTLY and only on a phone with no signal, which is the one
 * place nobody is reading the source. A workshop that ended a week ago still offered; one designer's
 * allotment offered to the colleague who shares the handset; a revoked allotment that cannot be
 * retired because the store refuses to write an empty list over a populated one; "this device has
 * never been given the list" and "you are on nothing right now" collapsed into one empty dropdown.
 * All four look identical on a desk with a working connection: the network answers, the list is
 * right, and nothing is wrong.
 *
 * ── WHY THE DISK IS REAL HERE ─────────────────────────────────────────────────────────────────
 *
 * This module has no Robolectric (see [DwReferenceFallbackOwnerTest]'s note, and the File-taking
 * overloads [DwReferenceStore] grew because of it), so a Context cannot be manufactured. The rules
 * worth pinning are about FILES — which account a list is filed under, whether a retirement can be
 * written at all, whether an absent file and an empty one read differently — so these tests write
 * through the same [DwReferenceStore] the app writes through, into a temporary directory.
 *
 * EVERY TEST USES ITS OWN ACCOUNT ID. [DwReferenceStore] keeps a process-lifetime memory mirror keyed
 * by cache key, and the key composed by [dwLocalWorkshopKey] carries the account — so distinct ids are
 * what keeps one test's rows out of the next test's temporary directory. A shared id here would make
 * these tests pass in an order that has nothing to do with the code.
 */
class DwLocalWorkshopsTest {

    private lateinit var root: File

    /** A real directory: the thing under test is what is on the disk and which file it is in. */
    @Before
    fun setUp() {
        root = java.nio.file.Files.createTempDirectory("dw-local-workshops-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private val today: LocalDate = LocalDate.parse("2026-09-16")

    private fun fieldWorkshop(
        id: String,
        title: String = "Chanderi weaving visit",
        type: String = "OTHER",
        place: String = "Chanderi",
        date: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        createdAt: String? = null,
    ) = WorkshopDetailDto(
        id = id,
        title = title,
        workshopType = type,
        place = place,
        date = date,
        startDate = startDate,
        endDate = endDate,
        createdAt = createdAt,
    )

    private fun designWorkshop(
        id: String,
        title: String = "Bidriware design & prototype",
        status: String = "IN_PROGRESS",
        startDate: String? = null,
        endDate: String? = null,
        craftName: String? = null,
        clusterName: String? = null,
        state: String? = null,
        createdAt: String? = null,
    ) = DesignWorkshopDto(
        id = id,
        title = title,
        status = status,
        craftName = craftName,
        clusterName = clusterName,
        state = state,
        startDate = startDate,
        endDate = endDate,
        createdAt = createdAt,
    )

    private fun storeField(userId: String, rows: List<WorkshopDetailDto>) = runBlocking {
        DwReferenceStore.store(
            root,
            dwLocalWorkshopKey(DW_LOCAL_FIELD_WORKSHOPS, userId),
            dwLocalStored(DW_LOCAL_FIELD_WORKSHOPS, rows.map(::dwLocalFieldWorkshopToOption)),
        )
    }

    private fun storeDesign(userId: String, rows: List<DesignWorkshopDto>) = runBlocking {
        DwReferenceStore.store(
            root,
            dwLocalWorkshopKey(DW_LOCAL_DESIGN_WORKSHOPS, userId),
            dwLocalStored(DW_LOCAL_DESIGN_WORKSHOPS, rows.map(::dwLocalDesignWorkshopToOption)),
        )
    }

    private fun read(userId: String, on: LocalDate = today) =
        runBlocking { dwCachedAllottedWorkshops(root, userId, on) }

    // ── 1. What the picker draws has to survive the round trip ───────────────────────────────────

    /**
     * The fields `fieldWorkshopOptions` reads are the fields this cache promises. A value dropped on
     * write is a null a picker quietly treats as "not carried": the hint loses its place, the sort
     * loses its day, and the row moves to the wrong end of the list with nothing having errored.
     */
    @Test
    fun `a field workshop's label, hint, type and three dates survive the round trip`() {
        val workshop = fieldWorkshop(
            id = "ws-1",
            title = "Chanderi weaving visit",
            type = "OTHER",
            place = "Chanderi",
            date = "2026-09-10",
            startDate = "2026-09-09",
            endDate = "2026-09-18",
            createdAt = "2026-08-30T09:00:00Z",
        )
        val restored = dwLocalOptionToFieldWorkshop(dwLocalFieldWorkshopToOption(workshop))
        assertEquals(workshop.id, restored?.id)
        assertEquals(workshop.title, restored?.title)
        assertEquals(workshop.workshopType, restored?.workshopType)
        assertEquals(workshop.place, restored?.place)
        assertEquals(workshop.date, restored?.date)
        assertEquals(workshop.startDate, restored?.startDate)
        assertEquals(workshop.endDate, restored?.endDate)
        assertEquals(workshop.createdAt, restored?.createdAt)
    }

    /**
     * A ROW WHOSE TYPE THE SERVER NEVER STATED MUST NOT COME BACK CLAIMING ONE. `WorkshopDetailDto`
     * defaults `workshopType` to "OTHER", so a decode that let the default apply would file every
     * un-typed workshop under a type nobody chose — and then hide it from every other type's list,
     * which is the cascade showing a confident wrong answer.
     */
    @Test
    fun `a field workshop with no stated type round-trips to a blank type, not to OTHER`() {
        val restored = dwLocalOptionToFieldWorkshop(dwLocalFieldWorkshopToOption(fieldWorkshop("ws-2", type = "")))
        assertEquals("", restored?.workshopType)
    }

    /** The design side's own fields: the hint's three facts, the sort's status, and the window's dates. */
    @Test
    fun `a design workshop's status, craft, cluster, state and dates survive the round trip`() {
        val workshop = designWorkshop(
            id = "dw-1",
            status = "NEEDS_REVISION",
            startDate = "2026-09-01",
            endDate = "2026-09-20",
            craftName = "Bidriware",
            clusterName = "Bidar",
            state = "Karnataka",
            createdAt = "2026-08-01T05:00:00Z",
        )
        val restored = dwLocalOptionToDesignWorkshop(dwLocalDesignWorkshopToOption(workshop))
        assertEquals(workshop.id, restored?.id)
        assertEquals(workshop.title, restored?.title)
        assertEquals(workshop.status, restored?.status)
        assertEquals(workshop.craftName, restored?.craftName)
        assertEquals(workshop.clusterName, restored?.clusterName)
        assertEquals(workshop.state, restored?.state)
        assertEquals(workshop.startDate, restored?.startDate)
        assertEquals(workshop.endDate, restored?.endDate)
        assertEquals(workshop.createdAt, restored?.createdAt)
    }

    /** A row with no id cannot be filed against and is not a workshop. */
    @Test
    fun `an option with no id decodes to nothing on both sides`() {
        assertNull(dwLocalOptionToFieldWorkshop(DwReferenceOption(id = "", label = "Nameless")))
        assertNull(dwLocalOptionToDesignWorkshop(DwReferenceOption(id = "", label = "Nameless")))
    }

    // ── 2. The window: what "upcoming or ongoing" means, to the day ──────────────────────────────

    /**
     * THE WHOLE OF THE END DAY IS STILL IN WINDOW. One day either way marks a workshop the designer is
     * standing in as over, or keeps offering one that finished yesterday.
     */
    @Test
    fun `a workshop that ends today has not ended, and one that ended yesterday has`() {
        assertFalse(dwLocalWorkshopEnded("2026-09-16", today))
        assertTrue(dwLocalWorkshopEnded("2026-09-15", today))
        assertFalse(dwLocalWorkshopEnded("2026-09-17", today))
    }

    /**
     * A ROW WITH NO USABLE DATE HAS NOT ENDED. A design workshop whose stage 1 is unfilled carries no
     * dates at all and is the most ongoing thing in the list; the same goes for a timestamp too short
     * to be a day.
     */
    @Test
    fun `a workshop with no stated end has not ended`() {
        assertFalse(dwLocalWorkshopEnded(null, today))
        assertFalse(dwLocalWorkshopEnded("", today))
        assertFalse(dwLocalWorkshopEnded("2026-09", today))
    }

    /**
     * THE TWO IMPLEMENTATIONS OF ONE RULE, PINNED AGAINST EACH OTHER.
     *
     * The cache asks `fieldWorkshopStatusWord` for the field table and [dwLocalWorkshopEnded] for the
     * design table, because `ui` has no "has this design workshop ended" to ask. Two functions for one
     * rule is exactly how a picker comes to call a workshop Ended while the cache still offers it, so
     * this walks the boundary and asserts they agree on every day around it.
     */
    @Test
    fun `the cache's window agrees with the picker's Ended word on the boundary`() {
        listOf("2026-09-14", "2026-09-15", "2026-09-16", "2026-09-17").forEach { day ->
            val workshop = fieldWorkshop("ws-boundary", endDate = day)
            assertEquals(
                "the two readings of ended disagree on $day",
                fieldWorkshopStatusWord(workshop, today) != null,
                dwLocalWorkshopEnded(day, today),
            )
        }
    }

    /**
     * A HANDED-IN REPORT IS NOT SOMEWHERE NEW FIELDWORK IS FILED, and a report sent back for
     * corrections is the most open thing in the list. The partition is `designWorkshopStanding`'s and
     * this asserts the cache uses it rather than a second reading of the same statuses.
     */
    @Test
    fun `only the finished-with design workshop statuses are kept out`() {
        listOf("DRAFT", "IN_PROGRESS", "COMPLETE", "PRE_SUBMISSION", "NEEDS_REVISION").forEach { status ->
            val workshop = designWorkshop("dw-$status", status = status)
            assertTrue("$status is open and must be cached", dwLocalDesignWorkshopIsCurrent(workshop, today))
            assertEquals(0, designWorkshopStanding(workshop))
        }
        listOf("SUBMITTED", "ARCHIVED", "APPROVED").forEach { status ->
            val workshop = designWorkshop("dw-$status", status = status)
            assertFalse("$status is finished with and must not be cached", dwLocalDesignWorkshopIsCurrent(workshop, today))
        }
    }

    /** An open status does not save a workshop whose last day has gone past. */
    @Test
    fun `an open design workshop that is over by the calendar is not current`() {
        assertFalse(dwLocalDesignWorkshopIsCurrent(designWorkshop("dw-old", endDate = "2026-09-01"), today))
        assertTrue(dwLocalDesignWorkshopIsCurrent(designWorkshop("dw-now", endDate = "2026-09-16"), today))
        assertTrue(dwLocalDesignWorkshopIsCurrent(designWorkshop("dw-undated"), today))
    }

    // ── 3. The disk: whose list it is, and what happens when a workshop ends while it sits there ──

    /**
     * A WORKSHOP THAT ENDS WHILE THE PHONE IS AWAY STOPS BEING OFFERED, WITH NO REFRESH.
     *
     * The window is re-tested on the READ, against the device's own idea of today. Without that, a
     * handset in a village keeps offering a workshop that finished ten days ago for as long as it has
     * no signal — which is precisely the stretch of time the cache exists to cover.
     */
    @Test
    fun `a cached workshop drops off the list the morning after it ends`() {
        val account = "user-ends-while-cached"
        storeField(account, listOf(fieldWorkshop("ws-ending", endDate = "2026-09-16")))

        assertEquals(listOf("ws-ending"), read(account, on = LocalDate.parse("2026-09-16")).fieldWorkshops.map { it.id })
        assertEquals(emptyList<String>(), read(account, on = LocalDate.parse("2026-09-17")).fieldWorkshops.map { it.id })
    }

    /**
     * AND THE FILE IS STILL A FILE. Dropping the last row must not read as "this device has never been
     * given the list": one of those sentences sends the designer to find a connection and the other
     * tells them, correctly, that nothing they are on is running. See the header's question 4.
     */
    @Test
    fun `a list whose every row has ended still reads as cached, not as never fetched`() {
        val account = "user-all-ended"
        storeField(account, listOf(fieldWorkshop("ws-gone", endDate = "2026-09-01")))

        val answer = read(account)
        assertEquals(emptyList<String>(), answer.fieldWorkshops.map { it.id })
        assertEquals(RegisterSource.CACHED, answer.fieldLoad.source)
        assertTrue("the date that makes the cached sentence sayable is missing", !answer.fieldLoad.fetchedAt.isNullOrBlank())
    }

    /**
     * ONE HANDSET, TWO DESIGNERS. The key carries the account, so B is never offered A's allotment —
     * the permissive failure the server cannot catch, because B picking a workshop B was never on is
     * refused only after the record has been typed.
     */
    @Test
    fun `one designer's allotment is never offered to the colleague sharing the handset`() {
        storeField("user-alice", listOf(fieldWorkshop("ws-alice", endDate = "2026-12-01")))
        storeDesign("user-alice", listOf(designWorkshop("dw-alice")))

        val bob = read("user-bob")
        assertEquals(emptyList<String>(), bob.fieldWorkshops.map { it.id })
        assertEquals(emptyList<String>(), bob.designWorkshops.map { it.id })
        assertEquals(RegisterSource.NONE, bob.fieldLoad.source)
        assertEquals(RegisterSource.NONE, bob.designLoad.source)

        assertEquals(listOf("ws-alice"), read("user-alice").fieldWorkshops.map { it.id })
    }

    /** No signed-in account is not an account with no workshops. Nothing is read and nothing claimed. */
    @Test
    fun `a blank account reads nothing and claims nothing`() {
        val answer = read("")
        assertEquals(emptyList<String>(), answer.fieldWorkshops.map { it.id })
        assertEquals(RegisterSource.PENDING, answer.fieldLoad.source)
        assertEquals(RegisterSource.PENDING, answer.designLoad.source)
    }

    /**
     * NEVER FETCHED IS NOT THE SAME ANSWER AS FETCHED AND EMPTY, and the distinction is the file's
     * existence. This is the null half; the test below is the empty half.
     */
    @Test
    fun `a device that has never fetched says so`() {
        val answer = read("user-never-asked")
        assertEquals(RegisterSource.NONE, answer.fieldLoad.source)
        assertEquals(RegisterSource.NONE, answer.designLoad.source)
        assertNull(answer.fieldLoad.fetchedAt)
    }

    // ── 4. Retiring an allotment the designer no longer holds ────────────────────────────────────

    /**
     * A REVOKED ALLOTMENT CAN ACTUALLY BE RETIRED, WHICH THE STORE'S OWN SAFETY RULE WOULD OTHERWISE
     * FORBID.
     *
     * [DwReferenceStore.store] refuses to let an EMPTY list overwrite a populated one — right for the
     * artisan register, wrong in the permissive direction for an access list, where the shorter answer
     * is the true one. Without the retirement row, a designer taken off every workshop would go on
     * being offered yesterday's rows for ever.
     *
     * The first assertion is what makes the second one mean something: it shows the store really does
     * refuse a bare empty write, so the empty read that follows is a statement about behaviour that
     * changed rather than about a rule nobody was near.
     */
    @Test
    fun `an answer of none retires the rows the designer no longer holds`() {
        val account = "user-revoked"
        storeField(account, listOf(fieldWorkshop("ws-was-mine", endDate = "2026-12-01")))
        val key = dwLocalWorkshopKey(DW_LOCAL_FIELD_WORKSHOPS, account)

        // PROOF THIS TEST BITES. A plain empty list is refused by the store, which is the rule the
        // retirement row exists to get past.
        val refused = runBlocking {
            DwReferenceStore.store(root, key, DwReferenceList(model = DW_LOCAL_FIELD_WORKSHOPS, items = emptyList()))
        }
        assertEquals("the store no longer refuses an empty write; this test is measuring nothing", 1, refused.items.size)
        assertEquals(listOf("ws-was-mine"), read(account).fieldWorkshops.map { it.id })

        // What a refresh that answered "you are on none" actually writes.
        storeField(account, emptyList())

        val answer = read(account)
        assertEquals(emptyList<String>(), answer.fieldWorkshops.map { it.id })
        assertEquals(RegisterSource.CACHED, answer.fieldLoad.source)
    }

    /** The same retirement on the design half, which is a separate file and a separate refresh. */
    @Test
    fun `an answer of none retires the design workshops too`() {
        val account = "user-revoked-design"
        storeDesign(account, listOf(designWorkshop("dw-was-mine")))
        assertEquals(listOf("dw-was-mine"), read(account).designWorkshops.map { it.id })

        storeDesign(account, emptyList())
        assertEquals(emptyList<String>(), read(account).designWorkshops.map { it.id })
        assertEquals(RegisterSource.CACHED, read(account).designLoad.source)
    }

    // ── 5. The type box, offline ─────────────────────────────────────────────────────────────────

    /**
     * THE TYPE CHOOSES THE TABLE, SO THE CACHE MUST NOT FILTER EITHER LIST BY IT.
     *
     * `WorkshopTypeOption.routesToDesignWorkshop` decides whether the "Workshop" dropdown is filled
     * from `DesignWorkshop` or from `Workshop`, and `GET /workshops` takes no type parameter at all —
     * the ONLINE list for every non-design type is the whole scoped list. `Workshop.workshopType` is
     * the legacy two-member enum (`DESIGN_PROTOTYPE | OTHER`) and matches none of the six
     * `WorkshopTypeOption.key` tokens, so a cache that narrowed by the chosen key would answer "no
     * workshops of this type" about a table full of them — on a phone with no signal, in the one
     * control least allowed to say it, and nowhere else.
     *
     * This test is what keeps that narrowing from being re-added: both halves come back whole, and
     * both legacy type values survive as facts on the rows rather than as a filter over them.
     */
    @Test
    fun `the cache holds both tables whole and filters neither by the type box`() {
        val account = "user-two-tables"
        storeField(
            account,
            listOf(
                fieldWorkshop("ws-other", type = "OTHER", endDate = "2026-12-01"),
                fieldWorkshop("ws-legacy-dp", type = "DESIGN_PROTOTYPE", endDate = "2026-12-01"),
                fieldWorkshop("ws-untyped", type = "", endDate = "2026-12-01"),
            ),
        )
        storeDesign(account, listOf(designWorkshop("dw-any")))

        val answer = read(account)
        assertEquals(
            listOf("ws-other", "ws-legacy-dp", "ws-untyped"),
            answer.fieldWorkshops.map { it.id },
        )
        assertEquals(listOf("dw-any"), answer.designWorkshops.map { it.id })
        // The row's own token is carried as a FACT — readable, never used to narrow.
        assertEquals(
            listOf("OTHER", "DESIGN_PROTOTYPE", ""),
            answer.fieldWorkshops.map { it.workshopType },
        )
    }

    // ── 6. THE READER: what a picker is handed when the network does not answer ──────────────────

    /*
     * WHY THESE TESTS EXIST AT ALL, WHICH IS THE WHOLE POINT OF THE SLICE THEY LANDED IN.
     *
     * Everything above this line pins the cache's CONTENT: what round-trips, what the window means,
     * whose file it is, how a revoked allotment is retired. All of it passed on the day a designer
     * with no signal still opened a record form and found an EMPTY workshop box — because nothing
     * read the file. A cache with no reader is a cache that cannot fail a test about its contents
     * and cannot help anybody either.
     *
     * So this section drives the reader the two record-form pickers call
     * ([dwLoadAllottedFieldWorkshops] and [dwLoadAllottedDesignWorkshops], the File-taking twins of
     * the two the composables call) with a network that throws, and asserts that what comes back is
     * the device's own list. Delete the disk read out of `dwLoadAllotted` and every test below fails.
     * That the PICKERS call it is asserted in `ui/WorkshopPickerTest`, against the source, because
     * wiring is where a JVM suite with no Robolectric can only read.
     */

    /** What `onCached` was handed, and how many times. A picker's `markCached`, with no screen. */
    private class Offered<T> {
        var rows: List<T> = emptyList()
        var load: RegisterLoad = RegisterLoad()
        var calls: Int = 0

        fun take(offered: List<T>, provenance: RegisterLoad) {
            rows = offered
            load = provenance
            calls++
        }
    }

    private fun deadNetwork(): Nothing = throw java.io.IOException("no route to host")

    /**
     * THE TEST THAT WOULD HAVE FAILED BEFORE THE READER EXISTED, on the field table.
     *
     * A phone holding this account's allotted workshops, a read that cannot reach the server, and a
     * picker that must still offer something. Before this slice the same situation produced an empty
     * list and the sentence *"This device has not received the workshops list yet"* — which was false
     * on a device that had received it, and which sent a designer looking for a connection instead of
     * at the four workshops sitting on their own disk.
     *
     * The provenance matters as much as the rows: `CACHED` with a real `fetchedAt` is what lets
     * `cachedListLine` say how old the list is, and a list offered without that date is a list nobody
     * can judge.
     */
    @Test
    fun `a failed read is answered from the device's own copy, with the date that came with it`() {
        val account = "user-reader-no-signal"
        storeField(
            account,
            listOf(
                fieldWorkshop("ws-bagru", title = "Bagru block printing", startDate = "2026-09-01", endDate = "2026-12-01"),
                fieldWorkshop("ws-chanderi", title = "Chanderi weaving", startDate = "2026-09-14", endDate = "2026-12-01"),
            ),
        )
        val offered = Offered<WorkshopDetailDto>()

        val half = runBlocking {
            dwLoadAllottedFieldWorkshops<PageResponse<WorkshopDetailDto>>(
                root = root,
                userId = account,
                fetch = { deadNetwork() },
                rowsOf = { it.items },
                isTransient = { true },
                today = today,
                onCached = offered::take,
            )
        }

        assertEquals("the cached rows were never offered", 1, offered.calls)
        assertEquals(listOf("ws-bagru", "ws-chanderi"), offered.rows.map { it.id })
        assertEquals(RegisterSource.CACHED, offered.load.source)
        assertTrue(
            "the date that makes the cached sentence sayable is missing",
            !offered.load.fetchedAt.isNullOrBlank(),
        )
        // The fetch did not answer, so there is no page for the picker to draw and what the device
        // holds is what stands.
        assertNull(half.page)
        assertEquals(listOf("ws-bagru", "ws-chanderi"), half.rows.map { it.id })
        assertEquals(RegisterSource.CACHED, half.load.source)
    }

    /** The same, on the design table, which is a separate file and a separate read. */
    @Test
    fun `a failed design read is answered from the device's own copy`() {
        val account = "user-reader-design"
        storeDesign(account, listOf(designWorkshop("dw-bidri", title = "Bidriware")))
        val offered = Offered<DesignWorkshopDto>()

        val half = runBlocking {
            dwLoadAllottedDesignWorkshops<DesignWorkshopPageDto>(
                root = root,
                userId = account,
                fetch = { deadNetwork() },
                rowsOf = { it.items },
                isTransient = { true },
                today = today,
                onCached = offered::take,
            )
        }

        assertEquals(listOf("dw-bidri"), offered.rows.map { it.id })
        assertEquals(RegisterSource.CACHED, offered.load.source)
        assertNull(half.page)
        assertEquals(listOf("dw-bidri"), half.rows.map { it.id })
    }

    /**
     * A WORKSHOP THE DESIGNER IS NO LONGER ON DISAPPEARS THE MOMENT ANY READ ANSWERS, AND THE FILE
     * GOES WITH IT.
     *
     * This is the permissive failure R6 was written against, closed at the only place it can be
     * closed: the live answer REPLACES the cached one — it is never merged with it — and a shorter
     * answer is the true one. Three things are asserted because all three have to hold for a revoked
     * allotment to actually go away: the picker is handed the server's page; what the device now
     * holds is the server's list; and a later read with NO network sees the shorter list rather than
     * yesterday's.
     */
    @Test
    fun `an answered read replaces the cached rows and the file, even when it is shorter`() {
        val account = "user-reader-revoked"
        storeField(
            account,
            listOf(
                fieldWorkshop("ws-kept", endDate = "2026-12-01"),
                fieldWorkshop("ws-revoked", endDate = "2026-12-01"),
            ),
        )
        val page = PageResponse(
            items = listOf(fieldWorkshop("ws-kept", endDate = "2026-12-01")),
            total = 1,
            page = 1,
            pageSize = 100,
            pages = 1,
        )

        val half = runBlocking {
            dwLoadAllottedFieldWorkshops(
                root = root,
                userId = account,
                fetch = { page },
                rowsOf = { it.items },
                isTransient = { true },
                today = today,
            )
        }

        assertEquals(listOf("ws-kept"), half.page?.items?.map { it.id })
        assertEquals(listOf("ws-kept"), half.rows.map { it.id })
        assertEquals(RegisterSource.LIVE, half.load.source)
        // And the disk agrees, which is what the NEXT form open with no signal will read.
        assertEquals(listOf("ws-kept"), read(account).fieldWorkshops.map { it.id })
    }

    /**
     * A WORKSHOP THAT ENDED WHILE THE PHONE WAS AWAY IS NOT OFFERED, with no request and no refresh.
     *
     * The window is re-tested on the READ against the device's own today, so the answer changes
     * overnight on a handset that has not seen a connection for a fortnight. `a cached workshop drops
     * off the list the morning after it ends` pins the same rule on the two-table read; this pins it
     * on the path a picker actually takes, which is the one a designer meets.
     */
    @Test
    fun `a workshop that ended since it was cached is not offered when the read fails`() {
        val account = "user-reader-ended"
        storeField(
            account,
            listOf(
                fieldWorkshop("ws-still-running", endDate = "2026-12-01"),
                fieldWorkshop("ws-finished", endDate = "2026-09-16"),
            ),
        )
        val offered = Offered<WorkshopDetailDto>()

        runBlocking {
            dwLoadAllottedFieldWorkshops<PageResponse<WorkshopDetailDto>>(
                root = root,
                userId = account,
                fetch = { deadNetwork() },
                rowsOf = { it.items },
                isTransient = { true },
                // The morning after the 16th.
                today = LocalDate.parse("2026-09-17"),
                onCached = offered::take,
            )
        }

        assertEquals(listOf("ws-still-running"), offered.rows.map { it.id })
    }

    /**
     * THE PICKER IS HANDED THE SERVER'S OWN PAGE AND NOT THE NARROWER SET THE CACHE KEEPS.
     *
     * A record opened for a correction is filed under a workshop that ended last month, and the live
     * picker draws that row with the word *"Ended"* beside it and sorts it to the bottom. Narrowing
     * the LIVE list to what may be CACHED would take that row off a phone with a perfect connection —
     * the picker would then say nothing about a workshop the record names, which is the one thing
     * `offPageWorkshopRow` exists to stop happening by accident.
     *
     * So: the page keeps the ended workshop, what the device holds does not, and the file does not.
     */
    @Test
    fun `an answered read shows the whole page and caches only the upcoming and ongoing`() {
        val account = "user-reader-whole-page"
        val page = PageResponse(
            items = listOf(
                fieldWorkshop("ws-open", endDate = "2026-12-01"),
                fieldWorkshop("ws-over", endDate = "2026-08-01"),
            ),
            total = 2,
            page = 1,
            pageSize = 100,
            pages = 1,
        )

        val half = runBlocking {
            dwLoadAllottedFieldWorkshops(
                root = root,
                userId = account,
                fetch = { page },
                rowsOf = { it.items },
                isTransient = { true },
                today = today,
            )
        }

        assertEquals(listOf("ws-open", "ws-over"), half.page?.items?.map { it.id })
        assertEquals(listOf("ws-open"), half.rows.map { it.id })
        assertEquals(listOf("ws-open"), read(account).fieldWorkshops.map { it.id })
    }

    /**
     * THE THREE SILENCES STAY THREE, THROUGH THE READER.
     *
     * A device that has never been given the list, a device whose file says the account is on nothing
     * current, and a read that the server ANSWERED and refused are three different facts with three
     * different next moves, and `workshopListNotice` turns them into three different sentences. They
     * can only stay apart if the reader keeps them apart, which is [RegisterLoad.source] and
     * [RegisterLoad.online] between them.
     */
    @Test
    fun `never fetched, fetched and none, and a refusal are three different answers`() {
        val never = Offered<WorkshopDetailDto>()
        runBlocking {
            dwLoadAllottedFieldWorkshops<PageResponse<WorkshopDetailDto>>(
                root = root,
                userId = "user-reader-never",
                fetch = { deadNetwork() },
                rowsOf = { it.items },
                isTransient = { true },
                today = today,
                onCached = never::take,
            )
        }
        assertEquals(RegisterSource.NONE, never.load.source)
        assertEquals(emptyList<String>(), never.rows.map { it.id })

        val none = Offered<WorkshopDetailDto>()
        val emptied = "user-reader-emptied"
        storeField(emptied, emptyList())
        runBlocking {
            dwLoadAllottedFieldWorkshops<PageResponse<WorkshopDetailDto>>(
                root = root,
                userId = emptied,
                fetch = { deadNetwork() },
                rowsOf = { it.items },
                isTransient = { true },
                today = today,
                onCached = none::take,
            )
        }
        assertEquals(RegisterSource.CACHED, none.load.source)
        assertEquals(emptyList<String>(), none.rows.map { it.id })

        // ANSWERED AND REFUSED versus COULD NOT BE REACHED — `isTransient`'s verdict, carried on the
        // half so the screen picks "could not be listed" over "this device has not received it yet".
        val refused = runBlocking {
            dwLoadAllottedFieldWorkshops<PageResponse<WorkshopDetailDto>>(
                root = root,
                userId = "user-reader-refused",
                fetch = { throw IllegalStateException("422") },
                rowsOf = { it.items },
                isTransient = { false },
                today = today,
            )
        }
        assertTrue("an answered refusal was recorded as an unreachable server", refused.load.online)

        val unreachable = runBlocking {
            dwLoadAllottedFieldWorkshops<PageResponse<WorkshopDetailDto>>(
                root = root,
                userId = "user-reader-unreachable",
                fetch = { deadNetwork() },
                rowsOf = { it.items },
                isTransient = { true },
                today = today,
            )
        }
        assertFalse("a dead connection was recorded as a server refusal", unreachable.load.online)
    }

    /**
     * A FORM THE DESIGNER NAVIGATED AWAY FROM IS NOT A FAILED READ.
     *
     * `CancellationException` is rethrown rather than classified, so a cancelled load never writes
     * the sentence *"the workshops list could not be loaded"* about a request that was never really
     * one — and, just as important, never replaces the file with a half-read answer. Both composables
     * did this for themselves before the reader existed; it is asserted here because doing it in one
     * place is what keeps them both doing it.
     */
    @Test
    fun `a cancelled load is rethrown and leaves the cached file exactly as it was`() {
        val account = "user-reader-cancelled"
        storeField(account, listOf(fieldWorkshop("ws-kept", endDate = "2026-12-01")))

        var thrown: Throwable? = null
        try {
            runBlocking {
                dwLoadAllottedFieldWorkshops<PageResponse<WorkshopDetailDto>>(
                    root = root,
                    userId = account,
                    fetch = { throw CancellationException("left the screen") },
                    rowsOf = { it.items },
                    isTransient = { true },
                    today = today,
                )
            }
        } catch (error: CancellationException) {
            thrown = error
        }

        assertTrue("a cancelled load was swallowed and reported as a failure", thrown is CancellationException)
        assertEquals(listOf("ws-kept"), read(account).fieldWorkshops.map { it.id })
    }

    /**
     * NO ACCOUNT, NO FILE — read or written. The fetch still happens, because that is what the picker
     * did before any of this and a signed-out record form is not the cache's to invent behaviour for.
     */
    @Test
    fun `a blank account reads nothing, writes nothing, and still asks`() {
        val offered = Offered<WorkshopDetailDto>()
        var asked = 0
        val page = PageResponse(
            items = listOf(fieldWorkshop("ws-somebody-else", endDate = "2026-12-01")),
            total = 1,
            page = 1,
            pageSize = 100,
            pages = 1,
        )

        val half = runBlocking {
            dwLoadAllottedFieldWorkshops(
                root = root,
                userId = "",
                fetch = { asked++; page },
                rowsOf = { it.items },
                isTransient = { true },
                today = today,
                onCached = offered::take,
            )
        }

        assertEquals("a signed-out load was answered off somebody's disk", 0, offered.calls)
        assertEquals(1, asked)
        assertEquals(listOf("ws-somebody-else"), half.page?.items?.map { it.id })
        // Nothing was filed, so nothing can be offered to whoever signs in next.
        assertEquals(emptyList<String>(), read("").fieldWorkshops.map { it.id })
    }
}
