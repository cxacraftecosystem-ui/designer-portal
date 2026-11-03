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

class DwLocalWorkshopsTest {

    private lateinit var root: File

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

    @Test
    fun `a field workshop with no stated type round-trips to a blank type, not to OTHER`() {
        val restored = dwLocalOptionToFieldWorkshop(dwLocalFieldWorkshopToOption(fieldWorkshop("ws-2", type = "")))
        assertEquals("", restored?.workshopType)
    }

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

    @Test
    fun `an option with no id decodes to nothing on both sides`() {
        assertNull(dwLocalOptionToFieldWorkshop(DwReferenceOption(id = "", label = "Nameless")))
        assertNull(dwLocalOptionToDesignWorkshop(DwReferenceOption(id = "", label = "Nameless")))
    }

    @Test
    fun `a workshop that ends today has not ended, and one that ended yesterday has`() {
        assertFalse(dwLocalWorkshopEnded("2026-09-16", today))
        assertTrue(dwLocalWorkshopEnded("2026-09-15", today))
        assertFalse(dwLocalWorkshopEnded("2026-09-17", today))
    }

    @Test
    fun `a workshop with no stated end has not ended`() {
        assertFalse(dwLocalWorkshopEnded(null, today))
        assertFalse(dwLocalWorkshopEnded("", today))
        assertFalse(dwLocalWorkshopEnded("2026-09", today))
    }

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

    @Test
    fun `an open design workshop that is over by the calendar is not current`() {
        assertFalse(dwLocalDesignWorkshopIsCurrent(designWorkshop("dw-old", endDate = "2026-09-01"), today))
        assertTrue(dwLocalDesignWorkshopIsCurrent(designWorkshop("dw-now", endDate = "2026-09-16"), today))
        assertTrue(dwLocalDesignWorkshopIsCurrent(designWorkshop("dw-undated"), today))
    }

    @Test
    fun `a cached workshop drops off the list the morning after it ends`() {
        val account = "user-ends-while-cached"
        storeField(account, listOf(fieldWorkshop("ws-ending", endDate = "2026-09-16")))

        assertEquals(listOf("ws-ending"), read(account, on = LocalDate.parse("2026-09-16")).fieldWorkshops.map { it.id })
        assertEquals(emptyList<String>(), read(account, on = LocalDate.parse("2026-09-17")).fieldWorkshops.map { it.id })
    }

    @Test
    fun `a list whose every row has ended still reads as cached, not as never fetched`() {
        val account = "user-all-ended"
        storeField(account, listOf(fieldWorkshop("ws-gone", endDate = "2026-09-01")))

        val answer = read(account)
        assertEquals(emptyList<String>(), answer.fieldWorkshops.map { it.id })
        assertEquals(RegisterSource.CACHED, answer.fieldLoad.source)
        assertTrue("the date that makes the cached sentence sayable is missing", !answer.fieldLoad.fetchedAt.isNullOrBlank())
    }

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

    @Test
    fun `a blank account reads nothing and claims nothing`() {
        val answer = read("")
        assertEquals(emptyList<String>(), answer.fieldWorkshops.map { it.id })
        assertEquals(RegisterSource.PENDING, answer.fieldLoad.source)
        assertEquals(RegisterSource.PENDING, answer.designLoad.source)
    }

    @Test
    fun `a device that has never fetched says so`() {
        val answer = read("user-never-asked")
        assertEquals(RegisterSource.NONE, answer.fieldLoad.source)
        assertEquals(RegisterSource.NONE, answer.designLoad.source)
        assertNull(answer.fieldLoad.fetchedAt)
    }

    @Test
    fun `an answer of none retires the rows the designer no longer holds`() {
        val account = "user-revoked"
        storeField(account, listOf(fieldWorkshop("ws-was-mine", endDate = "2026-12-01")))
        val key = dwLocalWorkshopKey(DW_LOCAL_FIELD_WORKSHOPS, account)

        val refused = runBlocking {
            DwReferenceStore.store(root, key, DwReferenceList(model = DW_LOCAL_FIELD_WORKSHOPS, items = emptyList()))
        }
        assertEquals("the store no longer refuses an empty write; this test is measuring nothing", 1, refused.items.size)
        assertEquals(listOf("ws-was-mine"), read(account).fieldWorkshops.map { it.id })

        storeField(account, emptyList())

        val answer = read(account)
        assertEquals(emptyList<String>(), answer.fieldWorkshops.map { it.id })
        assertEquals(RegisterSource.CACHED, answer.fieldLoad.source)
    }

    @Test
    fun `an answer of none retires the design workshops too`() {
        val account = "user-revoked-design"
        storeDesign(account, listOf(designWorkshop("dw-was-mine")))
        assertEquals(listOf("dw-was-mine"), read(account).designWorkshops.map { it.id })

        storeDesign(account, emptyList())
        assertEquals(emptyList<String>(), read(account).designWorkshops.map { it.id })
        assertEquals(RegisterSource.CACHED, read(account).designLoad.source)
    }

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
        assertEquals(
            listOf("OTHER", "DESIGN_PROTOTYPE", ""),
            answer.fieldWorkshops.map { it.workshopType },
        )
    }

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
        assertNull(half.page)
        assertEquals(listOf("ws-bagru", "ws-chanderi"), half.rows.map { it.id })
        assertEquals(RegisterSource.CACHED, half.load.source)
    }

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
        assertEquals(listOf("ws-kept"), read(account).fieldWorkshops.map { it.id })
    }

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
                today = LocalDate.parse("2026-09-17"),
                onCached = offered::take,
            )
        }

        assertEquals(listOf("ws-still-running"), offered.rows.map { it.id })
    }

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
        assertEquals(emptyList<String>(), read("").fieldWorkshops.map { it.id })
    }
}
