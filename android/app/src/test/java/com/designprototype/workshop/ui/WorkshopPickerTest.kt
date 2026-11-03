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

class WorkshopPickerTest {

    @Test
    fun `the pair is on exactly the five forms whose records carry both columns`() {
        val expected = listOf(
            "ArtisanForm", "ProcessForm", "ProductForm", "ToolForm",
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

    @Test
    fun `no record form grows a type box of its own`() {
        assertFalse(
            "MainActivity.kt has grown its own \"Type of workshop\" control",
            withoutComments(MAIN).contains("\"Type of workshop\""),
        )
    }

    @Test
    fun `the record form picker sends no workshopKind to the server`() {
        val code = withoutComments(PICKER)
        assertFalse(
            "the record-form picker is narrowing the design workshop list by workshopKind again. " +
                "The type chooses the TABLE; DesignWorkshop.workshopKind is NULL on 13,847 of " +
                "13,871 rows, so that filter answers \"none of this type\" about a full register.",
            code.contains("workshopKind ="),
        )
        assertEquals(
            "workshopKind is being read somewhere other than the offline floor",
            1,
            Regex("workshopKind").findAll(code).count(),
        )
        assertTrue(code.contains("workshopKindOptions(schema)"))
    }

    @Test
    fun `a second design-workshop-backed type routes correctly without any client change`() {
        val types = listOf(
            type("DESIGN_PROTOTYPE_DEVELOPMENT", 10, routes = true),
            type("SKILL_UPGRADATION", 20, routes = false),
            type("PROTOTYPE_RESIDENCY", 25, routes = true),
        )
        assertTrue(routesToDesignWorkshopFor(types, "PROTOTYPE_RESIDENCY", storedRouting = null))
        assertFalse(routesToDesignWorkshopFor(types, "SKILL_UPGRADATION", storedRouting = null))
    }

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

    @Test
    fun `no record body carries the chosen type`() {
        val code = withoutComments(MAIN)
        for (leak in listOf("link.type.typeKey", "workshopType = link", "workshopKind = link")) {
            assertFalse("a record form is sending the type: $leak", code.contains(leak))
        }
    }

    @Test
    fun `the displaced column is cleared only when it held a workshop`() {
        val code = withoutComments(MAIN)
        assertTrue(
            "RecordWorkshopLink.displaced no longer guards on the baseline, so a form is claiming a " +
                "clearance for a column that was already empty",
            code.contains("if (baselineId.isNotBlank()) UNFILED_BY_CHOICE else null"),
        )
        assertEquals(UNFILED_BY_CHOICE, unfiledLinkReason(selectedId = "", baselineId = "w1", hadOptions = false))
        assertEquals(UNFILED_NO_OPTIONS, unfiledLinkReason(selectedId = "", baselineId = "", hadOptions = false))
        assertNull(unfiledLinkReason(selectedId = "w1", baselineId = "", hadOptions = true))
    }

    @Test
    fun `a designer opens a new record on the design and prototype type`() {
        assertEquals(
            "DESIGN_PROTOTYPE_DEVELOPMENT",
            openingWorkshopTypeKey(SEEDED, storedRouting = null, preferDesignWorkshops = true),
        )
    }

    @Test
    fun `everybody else opens on the first type in the administrator's order`() {
        assertEquals(
            "DESIGN_PROTOTYPE_DEVELOPMENT",
            openingWorkshopTypeKey(SEEDED, storedRouting = null, preferDesignWorkshops = false),
        )
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

    @Test
    fun `a deactivated type is not offered`() {
        val retired = SEEDED.map { if (it.key == "CLUSTER_DEVELOPMENT") it.copy(isActive = false) else it }
        assertFalse(workshopTypeOptions(retired, "SKILL_UPGRADATION", FLOOR).any { it.value == "CLUSTER_DEVELOPMENT" })
        assertNull(defaultWorkshopType(emptyList(), preferDesignWorkshops = true))
    }

    @Test
    fun `two types sharing a position are broken apart by their key`() {
        val tied = listOf(type("ZEBRA", 10, routes = false), type("ALPHA", 10, routes = false))
        assertEquals(listOf("ALPHA", "ZEBRA"), sortWorkshopTypes(tied).map { it.key })
    }

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

    @Test
    fun `the type box offers no empty row`() {
        assertTrue(withoutComments(PICKER).contains("label = \"Type of workshop\",\n            options = options,\n            selectedValue = state.typeKey,\n            includeNone = false,"))
    }

    @Test
    fun `both clients name the design programme by the same key`() {
        val web = Regex("""DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY = "([A-Z_]+)"""")
            .find(webFile("lib/workshopTypes.ts"))?.groupValues?.get(1)
        assertEquals(web, DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY)
    }

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

    @Test
    fun `the browser writes one column and empties the other, as this client does`() {
        val web = webFile("components/forms/WorkshopPicker.tsx")
        assertTrue(
            "the browser's WorkshopPicker no longer nulls the column the type does not name",
            web.contains("workshopId: routesToDesignWorkshop ? \"\" : field.workshopId") &&
                web.contains("designWorkshopId: routesToDesignWorkshop ? design.workshopId : \"\""),
        )
    }

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

    @Test
    fun `exactly one floor row routes at the design workshop register`() {
        assertEquals(
            listOf("DESIGN_PROTOTYPE_DEVELOPMENT"),
            FLOOR.filter { it.routesToDesignWorkshop }.map { it.key },
        )
    }

    @Test
    fun `the two hints are different and only one of them claims the administrator's list`() {
        val served = workshopTypeHint(served = true)
        val floor = workshopTypeHint(served = false)
        assertTrue(served.contains("It is not saved on this record"))
        assertTrue(floor.contains("standard types of workshop"))
        assertTrue("both arms owe the reader the not-saved fact", floor.contains("not saved on this record"))
        assertFalse("the served arm must not apologise for a list that is right", served.contains("built-in"))
    }


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
            assertTrue(listIsAnswerable(designWorkshopOptions(state.workshops)))
        }
    }

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

            assertEquals(
                listOf("dw-newest", "dw-oldest"),
                designWorkshopOptions(held.designWorkshops).map { it.value },
            )
            assertEquals(
                listOf("ws-newest", "ws-oldest"),
                fieldWorkshopOptions(held.fieldWorkshops, today = TODAY).map { it.value },
            )
            assertTrue(held.designWorkshops.none { it.id.startsWith("ws-") })
            assertTrue(held.fieldWorkshops.none { it.id.startsWith("dw-") })
        }
    }

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
        assertEquals(
            3,
            setOf(offlineListLine("workshops"), couldNotListLine("workshops"), scopedEmptyLine("workshops")).size,
        )
    }

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

    private val LENIENT_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    private val TODAY: LocalDate = LocalDate.parse("2026-09-16")

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

    private val FLOOR: List<WorkshopTypeOptionDto> by lazy {
        val asset = repoFile(
            "app/src/main/assets/design-workshop-schema.json",
            "android/app/src/main/assets/design-workshop-schema.json",
        ).readText(Charsets.UTF_8)
        val schema = LENIENT_JSON.decodeFromString(SchemaResponse.serializer(), asset)
        workshopTypeFloor(schema)
    }

    private val SEEDED: List<WorkshopTypeOptionDto> get() = FLOOR

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
            if (at >= 4 && source.regionMatches(at - 4, "fun ", 0, 4)) continue
            declarations.lastOrNull { it.first < at }?.let { found.add(it.second) }
        }
        return found.toList()
    }

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
