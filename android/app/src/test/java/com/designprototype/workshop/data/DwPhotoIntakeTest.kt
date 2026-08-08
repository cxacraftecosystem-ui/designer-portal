package com.designprototype.workshop.data

import com.designprototype.workshop.data.DwPhotoIntake.MAX_PROPOSALS
import com.designprototype.workshop.data.DwPhotoIntake.OUTSIDE_GRACE_DAYS
import com.designprototype.workshop.data.DwPhotoIntake.appendMediaRef
import com.designprototype.workshop.data.DwPhotoIntake.buildAnchors
import com.designprototype.workshop.data.DwPhotoIntake.intakePhotos
import com.designprototype.workshop.data.DwPhotoIntake.intakeSummary
import com.designprototype.workshop.data.DwPhotoIntake.parseExifStamp
import com.designprototype.workshop.data.DwPhotoIntake.photoDayIndex
import com.designprototype.workshop.data.DwPhotoIntake.photoTargets
import com.designprototype.workshop.data.DwPhotoIntake.rankProposals
import com.designprototype.workshop.data.DwPhotoIntake.resolveStamp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [DwPhotoIntake] — the bulk photo intake's proposal ranking.
 *
 * THE CASES ARE `frontend/e2e/photo-intake-ranking.spec.ts`, CASE FOR CASE AND STRING FOR STRING.
 * That file says so itself — "the Kotlin port, when it is written, has to reproduce these orders
 * exactly" — and the numbers and sentences below are the web's own expectations rather than whatever
 * this port printed the day it was written. A case that exists there and not here is a place the two
 * clients are free to disagree about which stage a photograph belongs to.
 *
 * WHY BY VALUE AND NOT BY PROPERTY, which is the web spec's own argument and holds identically here:
 * "a nearer anchor sorts first" passes for three mutually incompatible rankings, and the ranking IS
 * the product — the point of the feature is that a designer can trust the first row without reading
 * the other two. Every assertion below fixes an exact order, an exact day count or an exact sentence.
 *
 * SIX CASES ARE NOT IN THE WEB SPEC, because they pin seams that exist only in Kotlin, and each one
 * is a way the two clients would have drifted apart silently:
 *
 *  * `a no-break space in a stored date is stripped the way the browser strips it` — Kotlin's
 *    `trim()` is not JavaScript's, and a date pasted out of a spreadsheet carries U+00A0.
 *  * `a camera stuck years ago prints its gap in Indian digit grouping` — the browser's
 *    `toLocaleString("en-IN")` groups 1,00,000, and a handset whose locale data does not would print
 *    a different refusal for the same batch.
 *  * `a two-digit year lands where Date.UTC puts it` — the web's quirk, reproduced on purpose.
 *  * `an evidence sentence is lower-cased invariantly` — `lowercase(Locale.getDefault())` on a Turkish
 *    handset would print "ınspection date".
 *  * `appendMediaRef keeps a scalar the web would drop` — the one deliberate divergence, documented
 *    on the function.
 *  * `a draft row entered offline still has a key a confirmation can aim at` — this client keeps a
 *    row's client key in the draft row's ID rather than inside the object.
 */
class DwPhotoIntakeTest {

    // -----------------------------------------------------------------------
    // Fixtures — the web spec's workshop, which ran 12–26 Feb 2026
    // -----------------------------------------------------------------------

    private fun anchor(
        start: String,
        end: String = start,
        stageKey: String = "STAGE",
        stageNumber: Int = 1,
        stageTitle: String = "Stage",
        entityKey: String = "entity",
        entityTitle: String = "Entity",
        rowKey: String? = null,
        rowLabel: String? = null,
        fieldLabel: String = "Date",
        kind: DwAnchorKind = if (start == end) DwAnchorKind.DAY else DwAnchorKind.SPAN,
    ) = DwWorkshopAnchor(
        stageKey = stageKey,
        stageNumber = stageNumber,
        stageTitle = stageTitle,
        entityKey = entityKey,
        entityTitle = entityTitle,
        rowKey = rowKey,
        rowLabel = rowLabel,
        fieldLabel = fieldLabel,
        kind = kind,
        start = start,
        end = end,
    )

    /** Stage 1's window: the whole workshop, and therefore the widest and weakest anchor there is. */
    private val workshopWindow = anchor(
        stageKey = "WORKSHOP_IDENTIFICATION",
        stageNumber = 1,
        stageTitle = "Workshop Identification",
        entityKey = "workshop",
        entityTitle = "Workshop",
        fieldLabel = "Start date – End date",
        kind = DwAnchorKind.SPAN,
        start = "2026-02-12",
        end = "2026-02-26",
    )

    /** Stage 13's per-day prototype logs — the narrow, specific anchors. */
    private val log14 = anchor(
        stageKey = "PROTOTYPE_DEVELOPMENT",
        stageNumber = 13,
        stageTitle = "Prototype Development",
        entityKey = "prototypeStageLog",
        entityTitle = "Stage logs",
        rowKey = "row-14",
        rowLabel = "Warping the loom",
        start = "2026-02-14",
    )

    private val log18 = anchor(
        stageKey = "PROTOTYPE_DEVELOPMENT",
        stageNumber = 13,
        stageTitle = "Prototype Development",
        entityKey = "prototypeStageLog",
        entityTitle = "Stage logs",
        rowKey = "row-18",
        rowLabel = "Dyeing the weft",
        start = "2026-02-18",
    )

    private val closing = anchor(
        stageKey = "INSPECTION_CLOSING",
        stageNumber = 19,
        stageTitle = "Inspection & Closing",
        entityKey = "closing",
        entityTitle = "Closing",
        fieldLabel = "Closing date",
        start = "2026-02-26",
    )

    private val anchors = listOf(workshopWindow, log14, log18, closing)

    private fun photo(fileName: String, takenAt: String?, takenAtOffset: String? = null) =
        DwIntakePhoto(fileName, takenAt, takenAtOffset)

    // -----------------------------------------------------------------------
    // 1. Inside a stage window
    // -----------------------------------------------------------------------

    @Test
    fun `a photograph on a logged day proposes that log above the workshop window`() {
        val row = intakePhotos(listOf(photo("DSC_0041.JPG", "2026:02:14 10:22:33")), anchors).single()

        assertNull(row.refusal)
        assertEquals("2026-02-14", row.stamp?.date)

        // Narrower evidence wins. Both anchors genuinely contain the 14th; only one is a reason.
        assertEquals(13, row.proposals[0].anchor.stageNumber)
        assertEquals("row-14", row.proposals[0].anchor.rowKey)
        assertEquals(DwProposalBasis.ON_DAY, row.proposals[0].basis)
        assertEquals(0, row.proposals[0].daysAway)

        assertEquals(1, row.proposals[1].anchor.stageNumber)
        assertEquals(DwProposalBasis.IN_SPAN, row.proposals[1].basis)
    }

    @Test
    fun `the evidence names the reading and the row it matched, so a wrong proposal is obvious`() {
        val row = intakePhotos(listOf(photo("DSC_0041.JPG", "2026:02:14 10:22:33")), anchors).single()

        assertEquals(
            "Taken 14 Feb 2026, 10:22 — stage 13's Stage logs row “Warping the loom”, date 14 Feb 2026.",
            row.proposals[0].evidence,
        )
        assertEquals(
            "Taken 14 Feb 2026, 10:22 — inside stage 1's Workshop, start date – end date " +
                "12 Feb 2026 – 26 Feb 2026.",
            row.proposals[1].evidence,
        )
    }

    @Test
    fun `a photograph inside the window but on no logged day still proposes the window`() {
        // The 21st: inside 12–26 Feb, but there is no log row for it.
        val row = intakePhotos(listOf(photo("DSC_0090.JPG", "2026:02:21 15:05:00")), anchors).single()

        assertNull(row.refusal)
        assertEquals(1, row.proposals[0].anchor.stageNumber)
        assertEquals(DwProposalBasis.IN_SPAN, row.proposals[0].basis)
    }

    // -----------------------------------------------------------------------
    // 2. Between two anchors
    // -----------------------------------------------------------------------

    @Test
    fun `a photograph between two logged days offers the nearer one first, and says it covers nothing`() {
        // The 15th: one day after log14, three days before log18. The window contains it.
        val row = intakePhotos(listOf(photo("DSC_0055.JPG", "2026:02:15 09:00:00")), anchors).single()

        assertNull(row.refusal)
        // The containing window outranks both non-containing logs — containment is the first sort
        // key, and a claim that is actually true beats a near miss.
        assertEquals(1, row.proposals[0].anchor.stageNumber)
        assertEquals(DwProposalBasis.IN_SPAN, row.proposals[0].basis)

        // Then the nearer log, then the farther one — the ambiguity stays visible rather than being
        // hidden behind a single confident answer.
        assertEquals("row-14", row.proposals[1].anchor.rowKey)
        assertEquals(1, row.proposals[1].daysAway)
        assertEquals(DwProposalBasis.NEAREST, row.proposals[1].basis)
        assertTrue(row.proposals[1].evidence.contains("nothing recorded covers that date"))
        assertTrue(row.proposals[1].evidence.contains("1 day after"))

        assertEquals("row-18", row.proposals[2].anchor.rowKey)
        assertEquals(3, row.proposals[2].daysAway)
    }

    @Test
    fun `between two logged days with no covering window, the nearer log leads`() {
        val row = intakePhotos(listOf(photo("DSC_0055.JPG", "2026:02:15 09:00:00")), listOf(log14, log18)).single()

        assertEquals("row-14", row.proposals[0].anchor.rowKey)
        assertEquals(1, row.proposals[0].daysAway)
        assertEquals("row-18", row.proposals[1].anchor.rowKey)
        assertEquals(3, row.proposals[1].daysAway)
    }

    // -----------------------------------------------------------------------
    // 3. Before every anchor
    // -----------------------------------------------------------------------

    @Test
    fun `a photograph just before the workshop is proposed, and the evidence says nothing covers it`() {
        // 11 Feb — one day before the window opens, inside the grace. Travelling in the night before
        // is a real workshop photograph and must not be refused.
        val row = intakePhotos(listOf(photo("DSC_0001.JPG", "2026:02:11 18:30:00")), anchors).single()

        assertNull(row.refusal)
        assertEquals(1, row.proposals[0].anchor.stageNumber)
        assertEquals(DwProposalBasis.NEAREST, row.proposals[0].basis)
        assertEquals(1, row.proposals[0].daysAway)
        assertTrue(row.proposals[0].evidence.contains("1 day before"))
    }

    @Test
    fun `a photograph far before every anchor is refused rather than guessed`() {
        // 1 Jan — 42 days before the window. Far likelier a camera clock that was never set than an
        // undated workshop day, and a confident stage from a wrong clock is the failure to avoid.
        val row = intakePhotos(listOf(photo("IMG_0002.JPG", "2026:01:01 12:00:00")), anchors).single()

        assertEquals(emptyList<DwStageProposal>(), row.proposals)
        assertTrue(row.refusal!!.contains("42 days before the first date recorded"))
        assertTrue(row.refusal!!.contains("12 Feb 2026"))
        assertTrue(row.refusal!!.contains("choose the stage yourself"))
        // The reading is still on screen — that is what diagnoses the camera.
        assertEquals("2026-01-01", row.stamp?.date)
    }

    @Test
    fun `the grace boundary is exact - OUTSIDE_GRACE_DAYS proposes, one more refuses`() {
        val inside = intakePhotos(listOf(photo("a.jpg", "2026:02:10 12:00:00")), anchors).single()
        val outside = intakePhotos(listOf(photo("b.jpg", "2026:02:09 12:00:00")), anchors).single()

        assertEquals(2, OUTSIDE_GRACE_DAYS)
        assertEquals(2, inside.proposals[0].daysAway)
        assertEquals(emptyList<DwStageProposal>(), outside.proposals)
        assertTrue(outside.refusal!!.contains("3 days before"))
    }

    @Test
    fun `a photograph after the last recorded date refuses the same way`() {
        val row = intakePhotos(listOf(photo("DSC_0300.JPG", "2026:03:20 09:00:00")), anchors).single()

        assertEquals(emptyList<DwStageProposal>(), row.proposals)
        assertTrue(row.refusal!!.contains("after the last date recorded"))
        assertTrue(row.refusal!!.contains("26 Feb 2026"))
    }

    // -----------------------------------------------------------------------
    // 4. No date at all — never dropped, never guessed
    // -----------------------------------------------------------------------

    @Test
    fun `a photograph with no EXIF date is offered for manual assignment, not dropped or guessed`() {
        val rows = intakePhotos(listOf(photo("Screenshot 2026-02-14.png", null)), anchors)

        assertEquals(1, rows.size)
        assertEquals("Screenshot 2026-02-14.png", rows[0].fileName)
        assertNull(rows[0].stamp)
        assertEquals(emptyList<DwStageProposal>(), rows[0].proposals)
        assertTrue(rows[0].refusal!!.contains("No capture date in this file"))
        // The filename says "2026-02-14" and the module must NOT read it. A name is not evidence.
        assertTrue(rows[0].refusal!!.contains("choose the stage yourself"))
    }

    @Test
    fun `the unset-clock sentinel and a corrupt date are refusals, not dates`() {
        // A camera with a flat clock battery writes exactly this, and it is not a date.
        assertNull(parseExifStamp("0000:00:00 00:00:00"))
        // 30 February cannot be rolled forward into March — that would file the photograph on a real
        // day the camera never saw.
        assertNull(parseExifStamp("2026:02:30 10:00:00"))
        assertNull(parseExifStamp(""))
        assertNull(parseExifStamp(null))
        assertEquals(
            DwPhotoIntake.DwExifParts(y = 2026, mo = 2, d = 14, hh = 10, mm = 22, ss = 33),
            parseExifStamp("2026:02:14 10:22:33"),
        )
        // 29 Feb 2024 is real; 29 Feb 2026 is not.
        assertNotNull(parseExifStamp("2024:02:29 10:00:00"))
        assertNull(parseExifStamp("2026:02:29 10:00:00"))

        val row = intakePhotos(listOf(photo("IMG_9999.JPG", "0000:00:00 00:00:00")), anchors).single()
        assertNull(row.stamp)
        assertEquals(emptyList<DwStageProposal>(), row.proposals)
        assertTrue(row.refusal!!.contains("No capture date"))
    }

    @Test
    fun `nothing is dropped - every file picked comes back as a row, in the order picked`() {
        val rows = intakePhotos(
            listOf(
                photo("c.jpg", "2026:02:18 08:00:00"),
                photo("a.png", null),
                photo("b.jpg", "2026:01:01 12:00:00"),
                photo("d.jpg", "2026:02:14 10:00:00"),
            ),
            anchors,
        )

        assertEquals(listOf("c.jpg", "a.png", "b.jpg", "d.jpg"), rows.map { it.fileName })
        assertEquals(DwIntakeSummary(total = 4, proposed = 2, manual = 2), intakeSummary(rows))
    }

    // -----------------------------------------------------------------------
    // 5. Day boundaries — the case the whole timezone argument is about
    // -----------------------------------------------------------------------

    @Test
    fun `2359 and 0001 land on their own calendar days, whatever the device's zone`() {
        val late = intakePhotos(listOf(photo("late.jpg", "2026:02:14 23:59:00")), anchors).single()
        val early = intakePhotos(listOf(photo("early.jpg", "2026:02:15 00:01:00")), anchors).single()

        // The late one is ON the 14th's log; the early one is not, and falls back to the window.
        assertEquals("2026-02-14", late.stamp?.date)
        assertEquals("row-14", late.proposals[0].anchor.rowKey)
        assertEquals(DwProposalBasis.ON_DAY, late.proposals[0].basis)

        assertEquals("2026-02-15", early.stamp?.date)
        assertEquals(1, early.proposals[0].anchor.stageNumber)
        assertEquals(DwProposalBasis.IN_SPAN, early.proposals[0].basis)
    }

    @Test
    fun `a naive evening clock is not shifted - the wall clock is taken as workshop-local`() {
        // The off-by-5.5-hours bug in its most common form: 19:40 read as UTC and rendered in
        // Asia/Kolkata would become 01:10 on the 15th and file this photograph on the wrong day.
        val stamp = resolveStamp(photo("evening.jpg", "2026:02:14 19:40:12"), DwPhotoIntake.DEFAULT_TIMEZONE)

        assertEquals("2026-02-14", stamp?.date)
        assertEquals(19 * 60 + 40, stamp?.minutes)
        assertNull(stamp?.shiftedFrom)
    }

    @Test
    fun `a camera left on UTC that declares its offset is shifted into workshop-local time`() {
        // Same wall clock, but the camera says it was +00:00. The real instant is 2026-02-15 01:10
        // IST, so this belongs on the 15th — and the evidence has to say the clock was moved.
        val stamp = resolveStamp(
            photo("utc.jpg", "2026:02:14 19:40:12", "+00:00"),
            DwPhotoIntake.DEFAULT_TIMEZONE,
        )

        assertEquals("2026-02-15", stamp?.date)
        assertEquals(60 + 10, stamp?.minutes)
        assertEquals("+00:00", stamp?.shiftedFrom)

        val row = intakePhotos(listOf(photo("utc.jpg", "2026:02:14 19:40:12", "+00:00")), anchors).single()
        assertTrue(row.proposals[0].evidence.contains("The camera recorded 2026-02-14 19:40 at +00:00"))
    }

    @Test
    fun `a camera already on the workshop's offset is left alone`() {
        val stamp = resolveStamp(
            photo("ist.jpg", "2026:02:14 19:40:12", "+05:30"),
            DwPhotoIntake.DEFAULT_TIMEZONE,
        )

        assertEquals("2026-02-14", stamp?.date)
        assertNull(stamp?.shiftedFrom)
    }

    @Test
    fun `an explicit timezone other than the default is honoured`() {
        // Same file, a workshop recorded in London: 19:40+00:00 is 19:40 there, so it stays on the
        // 14th.
        val stamp = resolveStamp(photo("utc.jpg", "2026:02:14 19:40:12", "+00:00"), "Europe/London")

        assertEquals("2026-02-14", stamp?.date)
        assertNull(stamp?.shiftedFrom)
    }

    // -----------------------------------------------------------------------
    // Anchors are read from the registry, not from a list of stage numbers
    // -----------------------------------------------------------------------

    private val registry = SchemaResponse(
        version = "test",
        stages = listOf(
            StageDto(
                number = 1,
                key = "WORKSHOP_IDENTIFICATION",
                title = "Workshop Identification",
                entities = listOf(
                    EntityDto(
                        key = "workshop",
                        name = "DwWorkshop",
                        cardinality = "SINGLETON",
                        title = "Workshop",
                        fields = listOf(
                            FieldDto(key = "startDate", label = "Start date", type = "DATE", tier = "BASIC", required = true),
                            FieldDto(key = "endDate", label = "End date", type = "DATE", tier = "BASIC", required = true),
                            FieldDto(key = "coverPhoto", label = "Cover photograph", type = "IMAGE", tier = "STANDARD"),
                        ),
                    ),
                ),
            ),
            StageDto(
                number = 13,
                key = "PROTOTYPE_DEVELOPMENT",
                title = "Prototype Development",
                entities = listOf(
                    EntityDto(
                        key = "prototypeStageLog",
                        name = "DwPrototypeStageLog",
                        cardinality = "COLLECTION",
                        title = "Stage logs",
                        labelField = "activity",
                        fields = listOf(
                            FieldDto(key = "logDate", label = "Date", type = "DATE", tier = "BASIC", required = true),
                            FieldDto(key = "activity", label = "Activity", type = "TEXT", tier = "BASIC", required = true),
                            FieldDto(key = "logPhotos", label = "Photographs", type = "IMAGE_LIST", tier = "BASIC"),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun text(value: String): JsonElement = JsonPrimitive(value)

    private val stageData = mapOf(
        "WORKSHOP_IDENTIFICATION" to DwStageData(
            singleton = mapOf("startDate" to text("2026-02-12"), "endDate" to text("2026-02-26")),
        ),
        "PROTOTYPE_DEVELOPMENT" to DwStageData(
            collections = mapOf(
                "prototypeStageLog" to listOf(
                    // Carries BOTH keys, so the preference between them is actually exercised: a row
                    // round-tripped through the server has an `_entryId` too, and picking the wrong
                    // one is invisible until a replayed save re-creates the row under a fresh id.
                    mapOf(
                        "_clientKey" to text("row-a"),
                        "_entryId" to text("server-a"),
                        "logDate" to text("2026-02-14"),
                        "activity" to text("Warping the loom"),
                    ),
                    mapOf(
                        "_entryId" to text("row-b"),
                        "logDate" to text("2026-02-18"),
                        "activity" to text("Dyeing the weft"),
                    ),
                    mapOf("_clientKey" to text("row-c"), "logDate" to text(""), "activity" to text("Undated note")),
                ),
            ),
        ),
    )

    @Test
    fun `buildAnchors reads the registry - a window from the paired dates, a day from each dated row`() {
        val built = buildAnchors(registry, stageData)

        // startDate + endDate collapse into ONE span, not two loose days.
        val spans = built.filter { it.kind == DwAnchorKind.SPAN }
        assertEquals(1, spans.size)
        assertEquals(1, spans[0].stageNumber)
        assertEquals("workshop", spans[0].entityKey)
        assertEquals("2026-02-12", spans[0].start)
        assertEquals("2026-02-26", spans[0].end)
        assertEquals("Start date – End date", spans[0].fieldLabel)
        assertNull(spans[0].rowKey)

        val days = built.filter { it.kind == DwAnchorKind.DAY }
        assertEquals(listOf("2026-02-14", "2026-02-18"), days.map { it.start })
        // `_clientKey` is preferred over `_entryId` — it is the key that survives a replayed save.
        assertEquals("row-a", days[0].rowKey)
        assertEquals("Warping the loom", days[0].rowLabel)
        assertEquals("row-b", days[1].rowKey)
        // The undated row contributes nothing rather than an anchor on some default date.
        assertEquals(3, built.size)
    }

    @Test
    fun `a stage the workshop has no data for contributes no anchors`() {
        assertEquals(emptyList<DwWorkshopAnchor>(), buildAnchors(registry, emptyMap()))
        val row = intakePhotos(listOf(photo("x.jpg", "2026:02:14 10:00:00")), emptyList()).single()
        assertTrue(row.refusal!!.contains("no dates recorded yet"))
    }

    @Test
    fun `the end-to-end path from registry to proposal picks the log row over the window`() {
        val built = buildAnchors(registry, stageData)
        val row = intakePhotos(listOf(photo("DSC_0041.JPG", "2026:02:14 10:22:33")), built).single()

        assertEquals("PROTOTYPE_DEVELOPMENT", row.proposals[0].anchor.stageKey)
        assertEquals("prototypeStageLog", row.proposals[0].anchor.entityKey)
        assertEquals("row-a", row.proposals[0].anchor.rowKey)
    }

    @Test
    fun `photoTargets lists only the image fields of the entity a proposal points at`() {
        assertEquals(
            listOf(DwPhotoTarget(fieldKey = "logPhotos", fieldLabel = "Photographs", multiple = true)),
            photoTargets(registry, "PROTOTYPE_DEVELOPMENT", "prototypeStageLog"),
        )
        assertEquals(
            listOf(DwPhotoTarget(fieldKey = "coverPhoto", fieldLabel = "Cover photograph", multiple = false)),
            photoTargets(registry, "WORKSHOP_IDENTIFICATION", "workshop"),
        )
        assertEquals(emptyList<DwPhotoTarget>(), photoTargets(registry, "NOPE", "nope"))
    }

    // -----------------------------------------------------------------------
    // Ordering is total and stable
    // -----------------------------------------------------------------------

    @Test
    fun `two anchors that match equally well keep a fixed order however the input is shuffled`() {
        val stamp = resolveStamp(photo("x.jpg", "2026:02:14 10:00:00"), DwPhotoIntake.DEFAULT_TIMEZONE)!!
        val twin = log14.copy(rowKey = "row-14b", rowLabel = "Second entry")

        val forwards = rankProposals(stamp, listOf(log14, twin, workshopWindow)).map { it.anchor.rowKey }
        val backwards = rankProposals(stamp, listOf(workshopWindow, twin, log14)).map { it.anchor.rowKey }

        assertEquals(forwards, backwards)
        assertEquals(listOf("row-14", "row-14b"), forwards.take(2))
    }

    @Test
    fun `no more than MAX_PROPOSALS come back for one photograph`() {
        val many = (0 until 9).map { log14.copy(rowKey = "r$it") }
        val stamp = resolveStamp(photo("x.jpg", "2026:02:14 10:00:00"), DwPhotoIntake.DEFAULT_TIMEZONE)!!

        assertEquals(3, MAX_PROPOSALS)
        assertEquals(MAX_PROPOSALS, rankProposals(stamp, many).size)
    }

    @Test
    fun `a window typed backwards is still read as a window, not thrown away`() {
        // Nothing about fieldwork may be blocked by a typo in a date box.
        val reversed = stageData + (
            "WORKSHOP_IDENTIFICATION" to DwStageData(
                singleton = mapOf("startDate" to text("2026-02-26"), "endDate" to text("2026-02-12")),
            )
            )
        val span = buildAnchors(registry, reversed).first { it.kind == DwAnchorKind.SPAN }

        assertEquals("2026-02-12", span.start)
        assertEquals("2026-02-26", span.end)
    }

    // -----------------------------------------------------------------------
    // Seams that exist only in Kotlin
    // -----------------------------------------------------------------------

    @Test
    fun `a no-break space in a stored date is stripped the way the browser strips it`() {
        // U+00A0 is what a date pasted out of a spreadsheet carries, and `Char.isWhitespace` is
        // deliberately false for it - so Kotlin's own `trim()` here would leave the phone with no
        // anchor for a date the browser reads perfectly well. Written as an escape because a
        // no-break space and a space are indistinguishable in a source file.
        val nbsp = "\u00A0"
        val padded = mapOf(
            "WORKSHOP_IDENTIFICATION" to DwStageData(
                singleton = mapOf(
                    "startDate" to text("${nbsp}2026-02-12$nbsp"),
                    "endDate" to text("2026-02-26"),
                ),
            ),
        )
        val span = buildAnchors(registry, padded).single()

        assertEquals("2026-02-12", span.start)
        assertEquals("2026-02-26", span.end)
        assertEquals(photoDayIndex("2026-02-12"), photoDayIndex("$nbsp 2026-02-12"))
    }

    @Test
    fun `a camera stuck years ago prints its gap in Indian digit grouping`() {
        // 1 Jan 1970 against a workshop in 2026 — the shape of a camera whose clock was never set,
        // and the number the browser prints for it is grouped 20,496 rather than 20496. A lakh-sized
        // gap groups 1,00,000 the same way, which is what an en-IN reader expects and what a handset
        // with plain three-digit locale data would get wrong.
        val row = intakePhotos(listOf(photo("IMG_0001.JPG", "1970:01:01 09:00:00")), anchors).single()

        assertTrue(row.refusal!!.contains("20,496 days before the first date recorded"))
    }

    @Test
    fun `a two-digit year lands where Date UTC puts it`() {
        // The web reaches this number through `Date.UTC`, which maps 0-99 onto 1900+year. Absurd,
        // reachable from a DATE box, and reproduced so the two clients cannot anchor one stored
        // string in two different centuries.
        assertEquals(photoDayIndex("1999-12-31"), photoDayIndex("0099-12-31"))
        assertEquals(photoDayIndex("1904-02-29"), photoDayIndex("0004-02-29"))
        // A year of 100 or more is itself, with no mapping at all — the boundary `Date.UTC` draws.
        assertEquals(LocalDate.of(100, 1, 1).toEpochDay().toInt(), photoDayIndex("0100-01-01"))
        assertTrue(photoDayIndex("0100-01-01")!! < photoDayIndex("1900-01-01")!!)
    }

    @Test
    fun `an evidence sentence is lower-cased invariantly`() {
        // `lowercase(Locale.getDefault())` on a handset set to Turkish turns "Inspection date" into
        // "ınspection date" — a sentence pinned against the web's, silently different on one device.
        val turkish = anchor(
            stageKey = "INSPECTION_CLOSING",
            stageNumber = 19,
            entityTitle = "Closing",
            fieldLabel = "Inspection date",
            start = "2026-02-26",
        )
        val stamp = resolveStamp(photo("x.jpg", "2026:02:26 10:00:00"), DwPhotoIntake.DEFAULT_TIMEZONE)!!

        assertEquals(
            "Taken 26 Feb 2026, 10:00 — stage 19's Closing, inspection date 26 Feb 2026.",
            rankProposals(stamp, listOf(turkish)).single().evidence,
        )
    }

    @Test
    fun `appendMediaRef appends once, and keeps a scalar the web would drop`() {
        assertEquals(JsonPrimitive("m2"), appendMediaRef(JsonPrimitive("m1"), "m2", multiple = false))

        val appended = appendMediaRef(JsonArray(listOf(JsonPrimitive("m1"))), "m2", multiple = true)
        assertEquals(JsonArray(listOf(JsonPrimitive("m1"), JsonPrimitive("m2"))), appended)

        // Idempotent: confirming the same photograph twice must not put it in the list twice.
        assertEquals(appended, appendMediaRef(appended, "m2", multiple = true))

        // The deliberate divergence, documented on the function: a legacy scalar in a list-valued
        // field is KEPT, where `appendMediaRef` in lib/photoIntake.ts starts from empty and loses it.
        assertEquals(
            JsonArray(listOf(JsonPrimitive("m1"), JsonPrimitive("m2"))),
            appendMediaRef(JsonPrimitive("m1"), "m2", multiple = true),
        )
    }

    @Test
    fun `a draft row entered offline still has a key a confirmation can aim at`() {
        // This client keeps a row's client key in the DRAFT ROW'S ID rather than inside the object,
        // so a row entered in a courtyard this morning has no `_clientKey` of its own. Without
        // [codeRow] it would produce an anchor with a null rowKey, and a photograph proposed for it
        // would have nowhere to be written.
        val draft = WorkshopDraft(
            workshopId = "local-1",
            stages = mapOf(
                "PROTOTYPE_DEVELOPMENT" to StageDraft(
                    stageId = "PROTOTYPE_DEVELOPMENT",
                    order = 13,
                    rows = listOf(
                        DraftRow(
                            id = dwRowId("prototypeStageLog", "offline-row"),
                            values = mapOf("logDate" to text("2026-02-14"), "activity" to text("Warping the loom")),
                        ),
                    ),
                ),
            ),
        )

        val built = buildAnchors(registry, dwStageDataFrom(registry, draft))

        assertEquals(1, built.size)
        assertEquals("offline-row", built[0].rowKey)
        assertEquals("Warping the loom", built[0].rowLabel)
        // And a workshop this device has never opened contributes nothing at all, which is what the
        // surface reports in words rather than by proposing nothing.
        assertEquals(emptyMap<String, DwStageData>(), dwStageDataFrom(registry, null))
    }
}
