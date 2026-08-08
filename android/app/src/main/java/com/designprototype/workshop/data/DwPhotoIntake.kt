package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Bulk photo intake on the handset — which stage does each photograph belong to, judged from its
 * EXIF clock.
 *
 * THE PROBLEM THIS EXISTS FOR. A designer shoots two hundred photographs over a thirty-day workshop
 * and then attaches each one by hand, stage by stage. It is the single most tedious hour of the job
 * and it is entirely mechanical: the workshop's dates are already recorded — stage 1's window, the
 * schedule days, stage 13's per-prototype logs, the closing — and the camera already wrote when each
 * frame was taken. So the answer is computable from data the DEVICE ALREADY HOLDS, which is what
 * makes it computable in the village where the fieldwork happened and where there is no laptop and
 * no signal. The web has had this since `frontend/lib/photoIntake.ts`; the photographs are on the
 * phone.
 *
 * IT IS A PORT, NOT A SECOND OPINION. `frontend/lib/photoIntake.ts` is the specification and
 * `frontend/e2e/photo-intake-ranking.spec.ts` is its test; that spec says in so many words that "the
 * Kotlin port, when it is written, has to reproduce these orders exactly", and [DwPhotoIntakeTest]
 * is those cases, case for case and string for string. Where the two could disagree the WEB wins,
 * and every place the two languages would have diverged on their own is named below rather than
 * left to be discovered from two clients proposing different stages for one photograph.
 *
 * IT PROPOSES. IT NEVER COMMITS. Nothing here attaches a photograph to anything and nothing here
 * copies a byte. It returns a ranking and the sentence that justifies it, and a human presses the
 * button — the same rule, for the same reason, as the identity-card reader: the one person who can
 * tell whether this is really the loom shot from the fourteenth is standing there holding the
 * camera, and this module's whole job is to save them the sorting, not the deciding. Hence
 * [DwStageProposal.evidence]: a proposal a designer cannot check is a proposal they will
 * rubber-stamp, and a wrong one has to be OBVIOUS rather than merely plausible.
 *
 * ── THE TIMEZONE RULE, which is the whole correctness argument ────────────────────────────────
 *
 * EXIF `DateTimeOriginal` is a NAIVE WALL CLOCK: "2026:02:14 19:40:12", with no zone attached. It is
 * what the camera's clock read. The workshop's dates are CALENDAR DATES — "2026-02-14" — with no
 * time and no zone either. Both sides are zoneless, so the correct comparison is calendar date
 * against calendar date, and this module NEVER BUILDS AN INSTANT FROM THE EXIF STRING to make it.
 *
 * That restraint is the entire defence against the off-by-5.5-hours bug. Parsing the wall clock in
 * the device's zone and rendering it in Asia/Kolkata — or parsing as UTC and rendering local — moves
 * every evening photograph a day. There is no round trip here to get wrong: [parseExifStamp] pulls
 * six integers out of the string and [photoDayIndex] does arithmetic on the calendar triple.
 *
 * WHAT IS THEREFORE ASSUMED, and it is stated on screen rather than buried here: **the camera's
 * clock was set to the workshop's local time** ([DEFAULT_TIMEZONE], which is this repository's
 * default everywhere).
 *
 * WHEN THE CAMERA KNOWS BETTER, WE STOP ASSUMING. EXIF 2.31 added `OffsetTimeOriginal` ("+05:30"),
 * and a camera that writes one has told us its zone outright. Then there is a real instant, and
 * [resolveStamp] shifts it into the workshop's zone — so a camera left on UTC no longer files every
 * photograph taken after 18:30 on the following day. The shift is reported in the evidence, because
 * a designer who sees a date they did not expect deserves to know the clock was moved.
 *
 * WHAT IS NOT DONE, deliberately: a camera whose clock is simply WRONG — never set, or still on last
 * year — cannot be detected from one photograph, and this module does not try. It refuses instead
 * (see [OUTSIDE_GRACE_DAYS]), which puts the distance on screen where the pattern is obvious across
 * a batch.
 *
 * ── WHY THIS FILE HAS NO ANDROID IN IT ────────────────────────────────────────────────────────
 *
 * Same split, and for the same reason, as [DwImageQuality] / [DwImageDecode]: there is no
 * Robolectric in this module (app/build.gradle.kts declares JUnit 4 and nothing else), so anything
 * that touches a `Uri`, an `ExifInterface` or a `Bitmap` is by construction code no unit test can
 * reach. Reading the clock off a picked file is the platform's job and lives beside the screen
 * (`dwReadCaptureStamp`, ui/designworkshop/PhotoIntakeScreen.kt), exactly as `readCaptureStamp` in
 * `lib/media.ts` is the browser's half; everything below takes the string that reader produced.
 */
object DwPhotoIntake {

    // ── Thresholds ───────────────────────────────────────────────────────────────────────────────

    /**
     * How far outside every recorded date a photograph may fall and still be proposed at all.
     *
     * A photograph a day or two outside the window is routine and real: the designer travelled in
     * the evening before, the last prototype was finished on an afternoon nobody logged, the closing
     * ran over. Proposing the nearest stage there is useful and the evidence says plainly that no
     * recorded date covers it.
     *
     * Beyond that the far likelier explanation is not "an undated workshop day" but "a camera clock
     * that was never set" — and a confident stage proposal derived from a clock that is wrong by
     * months is exactly the plausible-but-wrong answer this module must not produce. So past this
     * distance it REFUSES: the photograph is still listed, still assignable by hand, and the refusal
     * names the gap in days, which is how a batch shot on a camera stuck in 2019 announces itself at
     * a glance.
     *
     * The same discipline as `MIN_SAMPLE_FOR_QUANTILES` in [DwMarketAnalysis]: withholding a figure
     * the data cannot support is not a weaker answer than producing one, it is a better outcome.
     */
    const val OUTSIDE_GRACE_DAYS: Int = 2

    /** The default this repository uses everywhere a timezone is unstated (`app_settings.DEFAULT_TIMEZONE`). */
    const val DEFAULT_TIMEZONE: String = "Asia/Kolkata"

    /**
     * How many proposals one photograph offers.
     *
     * Three, not one: the interesting case is a photograph that falls between two logged days, where
     * the honest answer is "it is nearer this one, but here is the other". A single proposal would
     * hide the ambiguity that is the reason to look. More than three is a menu nobody reads for two
     * hundred rows.
     */
    const val MAX_PROPOSALS: Int = 3

    /**
     * Date-field pairs that describe a RANGE rather than two separate days.
     *
     * This is the one fact about the dated fields that the registry does not itself declare. Every
     * other property of every anchor below — which stage, which entity, which row, the field's label
     * — is read from `stage_definitions.py` through the served registry, so a twenty-third stage with
     * a dated collection produces photo proposals with no change here. But the registry says only
     * that `startDate` and `endDate` are two DATE fields on one entity; that they bound a window is
     * knowledge that has to live somewhere, and a short explicit list is honest about being knowledge
     * rather than pretending to derive it from a naming convention that would also pair
     * `sanctionOrderDate` with something one day.
     *
     * A DATE field not named here becomes a single-day anchor, which is the safe default: a lone day
     * proposes only its own date, where a wrongly-inferred range would silently swallow a fortnight.
     *
     * KEPT IN THE SAME ORDER as `DATE_RANGES` in `lib/photoIntake.ts`. The order decides which pair
     * claims a shared field first — `startDate` belongs to two of them — so a reordering here would
     * change which window a stage produces on this client only.
     */
    private val DATE_RANGES: List<Pair<String, String>> = listOf(
        "startDate" to "endDate",
        "startDate" to "completedDate",
        "surveyStartDate" to "surveyEndDate",
    )

    private val MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    // ── Reading the clock ────────────────────────────────────────────────────────────────────────

    /**
     * EXIF's own date format, and the ISO spelling as a courtesy.
     *
     * EXIF writes colons in the date part ("2026:02:14 10:22:33") — that is the specified form, not a
     * typo to be normalised away. Some libraries and some phones hand back "2026-02-14T10:22:33"
     * instead, so both separators are accepted. Seconds are optional; a fractional part and a
     * trailing zone are tolerated and ignored, because the zone travels separately in
     * `OffsetTimeOriginal` and a value glued onto the end of this field is not one this module is
     * willing to trust.
     *
     * `\d` is ASCII-only in both Java and JavaScript unless asked otherwise, and neither is asked —
     * so a Devanagari digit is refused identically on both clients.
     */
    private val EXIF_STAMP = Regex("^(\\d{4})[:-](\\d{2})[:-](\\d{2})[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?")

    /** `OffsetTimeOriginal`: "+05:30", "-08:00", "Z". */
    private val EXIF_OFFSET = Regex("^(?:(Z)|([+-])(\\d{2}):?(\\d{2}))$")

    /** A stored `yyyy-mm-dd`, matched as a PREFIX exactly as the web's `photoDayIndex` matches it. */
    private val ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})")

    /** The six integers of an EXIF timestamp. */
    data class DwExifParts(
        val y: Int,
        val mo: Int,
        val d: Int,
        val hh: Int,
        val mm: Int,
        val ss: Int,
    )

    /**
     * Pull the six integers out of an EXIF timestamp, or return null.
     *
     * Returns null — not a guess and not today — for every unusable reading, and the unusable
     * readings are not exotic: a camera with no clock battery writes "0000:00:00 00:00:00", and a
     * stripped tag comes back as an empty string. Both must land in [DwPhotoIntakeRow.refusal]
     * beside the genuinely date-less files, because "the camera said midnight on the year zero" is
     * not a date and anything that treats it as one files a photograph two thousand years before the
     * workshop.
     */
    fun parseExifStamp(raw: String?): DwExifParts? {
        if (raw.isNullOrEmpty()) return null
        val match = EXIF_STAMP.find(jsTrim(raw)) ?: return null
        val groups = match.groupValues
        val parts = DwExifParts(
            y = groups[1].toInt(),
            mo = groups[2].toInt(),
            d = groups[3].toInt(),
            hh = groups[4].toInt(),
            mm = groups[5].toInt(),
            ss = groups[6].takeIf { it.isNotEmpty() }?.toInt() ?: 0,
        )
        // The zero sentinel and every out-of-range component. `mo`/`d` are checked against the real
        // month length rather than a flat 31 so that "2026:02:30" — which a corrupt tag can
        // absolutely produce — is refused rather than silently rolling forward into March.
        if (parts.y < 1900 || parts.mo < 1 || parts.mo > 12 || parts.d < 1) return null
        if (parts.d > daysInMonth(parts.y, parts.mo)) return null
        if (parts.hh > 23 || parts.mm > 59 || parts.ss > 59) return null
        return parts
    }

    /** Days in a Gregorian month. Spelled out because the leap rule decides whether 29 Feb is refused. */
    private fun daysInMonth(year: Int, month: Int): Int {
        if (month == 2) return if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        return if (month == 4 || month == 6 || month == 9 || month == 11) 30 else 31
    }

    /** Minutes east of UTC declared by an `OffsetTimeOriginal`, or null when there isn't one. */
    private fun parseExifOffset(raw: String?): Int? {
        if (raw.isNullOrEmpty()) return null
        val match = EXIF_OFFSET.find(jsTrim(raw)) ?: return null
        val groups = match.groupValues
        if (groups[1] == "Z") return 0
        val sign = if (groups[2] == "-") -1 else 1
        return sign * (groups[3].toInt() * 60 + groups[4].toInt())
    }

    /**
     * What a named zone's UTC offset was at a given instant, in minutes east.
     *
     * Asked about an INSTANT, never about a wall clock, so there is no ambiguity to resolve: this is
     * only ever reached once `OffsetTimeOriginal` has given us a real instant to convert.
     *
     * `java.time`'s own rules rather than the web's `Intl` round trip, because they answer the same
     * question directly and to the second; every modern zone's offset is a whole number of minutes,
     * so the division below loses nothing that a photograph taken this decade can carry. Null when
     * the platform does not know the zone — the caller then declines to shift and says so, which is
     * right: silently falling back to the device's own zone is how a designer in Delhi and a
     * reviewer in London get different stage assignments from one file.
     */
    private fun zoneOffsetMinutesAt(timeZone: String, utcMs: Long): Int? = runCatching {
        ZoneId.of(timeZone).rules.getOffset(Instant.ofEpochMilli(utcMs)).totalSeconds / 60
    }.getOrNull()

    /**
     * Turn one photograph's EXIF reading into a workshop-local calendar date and time.
     *
     * The ordinary path does no arithmetic at all: the camera had no zone to declare, the assumption
     * stated in the file header applies, and the wall clock's date is used as written. The shift
     * below happens only when the camera DID declare a zone and that zone differs from the
     * workshop's — which is the case that would otherwise put every evening photograph on the wrong
     * day.
     */
    fun resolveStamp(photo: DwIntakePhoto, timeZone: String): DwPhotoStamp? {
        val parts = parseExifStamp(photo.takenAt) ?: return null

        val raw = "${pad4(parts.y)}-${pad2(parts.mo)}-${pad2(parts.d)} ${pad2(parts.hh)}:${pad2(parts.mm)}"
        val plain = DwPhotoStamp(
            date = "${pad4(parts.y)}-${pad2(parts.mo)}-${pad2(parts.d)}",
            minutes = parts.hh * 60 + parts.mm,
            raw = raw,
            shiftedFrom = null,
        )

        val declared = parseExifOffset(photo.takenAtOffset) ?: return plain

        // The camera told us its zone, so there is a genuine instant here rather than a wall clock.
        // [LocalDateTime] carries no zone at all and is pinned to UTC explicitly, which is what makes
        // this safe in the way the browser's `Date.UTC` is and `new Date(string)` is not: the
        // device's own zone never enters the calculation.
        val utcMs = LocalDateTime.of(parts.y, parts.mo, parts.d, parts.hh, parts.mm, parts.ss)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli() - declared * 60_000L
        val local = zoneOffsetMinutesAt(timeZone, utcMs)
        if (local == null || local == declared) return plain

        val shifted = Instant.ofEpochMilli(utcMs + local * 60_000L).atOffset(ZoneOffset.UTC)
        return DwPhotoStamp(
            date = "${pad4(shifted.year)}-${pad2(shifted.monthValue)}-${pad2(shifted.dayOfMonth)}",
            minutes = shifted.hour * 60 + shifted.minute,
            raw = raw,
            shiftedFrom = photo.takenAtOffset,
        )
    }

    // ── Calendar arithmetic ──────────────────────────────────────────────────────────────────────

    /**
     * `yyyy-mm-dd` as a day number, for subtraction. Null for anything that is not one.
     *
     * A count of calendar days carrying no time and no zone — which is the only thing that may be
     * compared against a `DATE` field.
     *
     * ── THE TWO-DIGIT YEAR, WHICH IS THE WEB'S QUIRK AND IS REPRODUCED ON PURPOSE ─────────────
     *
     * The browser reaches this number through `Date.UTC(year, month - 1, day)`, and `Date.UTC` maps
     * a year of 0-99 onto 1900+year — so "0099-12-31" is the last day of 1999 there. It is absurd,
     * and it is what the web does; a DATE box will take a year typed with leading zeros, so the input
     * is reachable. Diverging here would mean one client anchoring a workshop in 1999 and the other
     * in the year 99, for the same stored string, which is a worse outcome than an absurd date both
     * clients agree on. The leap-year check above deliberately runs on the RAW year, exactly as the
     * web's does, so the mapping happens after the validation and not before.
     *
     * `plusDays` rather than `LocalDate.of(y, m, d)` for the same fidelity: the one input that
     * survives the raw-year check and is then invalid in the mapped year is 0000-02-29 (year 0 is a
     * leap year, 1900 is not), and `Date.UTC` rolls that forward to 1 March 1900 rather than
     * rejecting it.
     */
    fun photoDayIndex(date: String?): Int? {
        if (date.isNullOrEmpty()) return null
        val match = ISO_DATE.find(jsTrim(date)) ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        if (month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month)) return null
        val effectiveYear = if (year in 0..99) year + 1900 else year
        return LocalDate.of(effectiveYear, month, 1).plusDays((day - 1).toLong()).toEpochDay().toInt()
    }

    /** Day number back to `yyyy-mm-dd`, for the refusal sentence. */
    private fun indexToDate(index: Int): String {
        val at = LocalDate.ofEpochDay(index.toLong())
        return "${pad4(at.year)}-${pad2(at.monthValue)}-${pad2(at.dayOfMonth)}"
    }

    /**
     * Print a `yyyy-mm-dd` as "14 Feb 2026", from its integer parts.
     *
     * Deliberately NOT a `DateTimeFormatter`, and deliberately not locale-aware. The month
     * abbreviations are the web's own list, in English, because this string is compared against the
     * web's character for character in the evidence sentence — a handset set to Hindi must not
     * propose the same photograph with a different sentence from the laptop beside it.
     */
    fun formatAnchorDate(date: String): String {
        val match = ISO_DATE.find(date) ?: return date
        val month = match.groupValues[2]
        return "${match.groupValues[3].toInt()} ${MONTHS.getOrNull(month.toInt() - 1) ?: month} ${match.groupValues[1]}"
    }

    /** "14 Feb 2026, 10:22" — the photograph's own reading, as the evidence opens with it. */
    fun formatStamp(stamp: DwPhotoStamp): String =
        "${formatAnchorDate(stamp.date)}, ${pad2(stamp.minutes / 60)}:${pad2(stamp.minutes % 60)}"

    /** "12 Feb 2026 – 26 Feb 2026" for a window, "14 Feb 2026" for a day. */
    private fun formatRange(anchor: DwWorkshopAnchor): String =
        if (anchor.start == anchor.end) {
            formatAnchorDate(anchor.start)
        } else {
            "${formatAnchorDate(anchor.start)} – ${formatAnchorDate(anchor.end)}"
        }

    private fun pad2(value: Int): String = value.toString().padStart(2, '0')

    private fun pad4(value: Int): String = value.toString().padStart(4, '0')

    /**
     * JavaScript's `String.prototype.trim`, which is NOT Kotlin's and NOT [DwPy.strip].
     *
     * Kotlin's `trim()` is `Char.isWhitespace`, which is deliberately FALSE for the no-break space
     * U+00A0 and the narrow no-break space U+202F — both of which JS strips, because its WhiteSpace
     * production is "any Unicode space separator" plus tab/VT/FF/space/U+FEFF. A stored date pasted
     * out of a spreadsheet carries U+00A0 often enough that this is not theoretical: the browser
     * reads the date and the phone does not, and one client silently drops an anchor the other has.
     *
     * NOT [DwPy.strip], which is Python's set: that one takes U+001C-U+001F and U+0085 (which JS does
     * not) and leaves U+FEFF (which JS does). Reaching for it here — the reflex everywhere else in
     * this package — would be porting the wrong language's edge, and this module is a port of the
     * WEB.
     *
     * Spelled as code points rather than character literals because two of them (VT and FF) are
     * invisible in a source file, and an invisible character in a whitespace test is one nobody can
     * review.
     */
    private fun jsTrim(text: String): String = text.trim { ch ->
        ch.code == 0x09 || ch.code == 0x0A || ch.code == 0x0B || ch.code == 0x0C || ch.code == 0x0D ||
            ch.code == 0xFEFF || Character.isSpaceChar(ch)
    }

    /**
     * An integer the way the browser's `toLocaleString("en-IN")` writes it: 1,847 and 1,00,000.
     *
     * Written out rather than taken from `NumberFormat.getIntegerInstance(Locale("en","IN"))`,
     * because that grouping is locale DATA — it depends on the ICU the handset happens to ship, and
     * a device whose data falls back to the plain three-digit pattern would print a refusal sentence
     * that differs from the web's for a batch off a camera whose clock was never set. Which is
     * precisely the sentence a designer compares between the two clients when they are trying to work
     * out whether the camera or the app is wrong.
     */
    private fun indianGrouped(value: Int): String {
        val negative = value < 0
        val digits = value.toLong().let { if (negative) -it else it }.toString()
        if (digits.length <= 3) return if (negative) "-$digits" else digits
        val tail = digits.takeLast(3)
        var head = digits.dropLast(3)
        val groups = ArrayList<String>()
        while (head.length > 2) {
            groups.add(0, head.takeLast(2))
            head = head.dropLast(2)
        }
        if (head.isNotEmpty()) groups.add(0, head)
        val grouped = (groups + tail).joinToString(",")
        return if (negative) "-$grouped" else grouped
    }

    // ── Building the anchors from the registry ───────────────────────────────────────────────────

    /** A DATE value as stored: `yyyy-mm-dd`, or something that is not one. */
    private fun dateValue(row: DwDataRow, key: String): String? {
        val raw = row[key] as? JsonPrimitive ?: return null
        if (!raw.isString) return null
        val trimmed = jsTrim(raw.content).take(10)
        return if (photoDayIndex(trimmed) == null) null else trimmed
    }

    /**
     * The row's own name, when the entity declares a label field and the row filled it in.
     *
     * `internal` rather than private because the SURFACE names the same rows — the "attach to" picker
     * lists every collection row a photograph can go into — and `page.tsx` spells that label with the
     * identical `typeof labelValue === "string" && labelValue.trim()`. A second copy written beside
     * the picker was a second answer: it reached for Kotlin's `trim()`, which leaves the no-break
     * space [jsTrim] removes, so a label pasted out of a spreadsheet that the browser reads as blank
     * — and names "row 3" — named the option with an invisible character here and gave the designer
     * an unlabelled row to choose between.
     */
    internal fun rowLabelOf(labelField: String, row: DwDataRow): String? {
        if (labelField.isEmpty()) return null
        val raw = row[labelField] as? JsonPrimitive ?: return null
        if (!raw.isString) return null
        return jsTrim(raw.content).takeIf { it.isNotEmpty() }
    }

    /**
     * Which row of a collection this is — `_clientKey` or `_entryId`, whichever the row has.
     *
     * `_clientKey` FIRST: it is the idempotency key that survives a row being re-created by a
     * replayed save, where `_entryId` is only present once the server has seen the row. A local draft
     * row keeps its client key in the row id rather than in the object, so a caller reading rows out
     * of a [WorkshopDraft] hands them through [codeRow] first — without that, every row entered in a
     * courtyard this morning would have no key at all and could not be filed into.
     */
    fun rowKeyOf(row: DwDataRow): String? {
        val client = (row["_clientKey"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        if (client != null) return client
        return (row["_entryId"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }

    /**
     * Every dated thing in this workshop, as anchors a photograph can be matched against.
     *
     * DRIVEN BY THE REGISTRY, not by a list of stages. It walks [SchemaResponse.stages] for fields of
     * type DATE and reads their values out of the saved stage data, so stage 1's window, the schedule
     * days, stage 13's prototypes and their per-day logs and the closing all become anchors without
     * being named here — and a twenty-third stage with a dated collection becomes one the day it is
     * declared, with no change to this file and none to the web. That is the same property the stage
     * forms have and it is the reason the registry is served rather than duplicated.
     *
     * A DEPRECATED DATE FIELD STILL CONTRIBUTES, matching the web, which filters `entity.fields`
     * rather than the live ones. It is the right answer for a date: the value is still stored, the
     * report still reads it, and a photograph taken on that day still belongs to that stage — the
     * deprecation withholds an INPUT, not a fact.
     */
    fun buildAnchors(schema: SchemaResponse, stages: Map<String, DwStageData>): List<DwWorkshopAnchor> {
        val anchors = ArrayList<DwWorkshopAnchor>()

        for (stage in schema.stages) {
            val data = stages[stage.key] ?: continue

            for (entity in stage.entities) {
                val dateKeys = entity.fields.filter { it.type == "DATE" }.map { it.key }
                if (dateKeys.isEmpty()) continue
                val labelOf = { key: String -> entity.fields.firstOrNull { it.key == key }?.label ?: key }

                val rows: List<Triple<DwDataRow, String?, String?>> =
                    if (entity.cardinality == "SINGLETON") {
                        listOf(Triple(data.singleton, null, null))
                    } else {
                        data.collections[entity.key].orEmpty().map { row ->
                            Triple(row, rowKeyOf(row), rowLabelOf(entity.labelField, row))
                        }
                    }

                for ((row, key, label) in rows) {
                    val consumed = HashSet<String>()

                    for ((startKey, endKey) in DATE_RANGES) {
                        if (startKey !in dateKeys || endKey !in dateKeys) continue
                        val start = dateValue(row, startKey) ?: continue
                        val end = dateValue(row, endKey) ?: continue
                        consumed.add(startKey)
                        consumed.add(endKey)
                        // A window typed backwards is still a window. Ordering the pair rather than
                        // discarding it matters because the fix — a designer noticing the dates are
                        // the wrong way round — is one they can only make if the photographs did not
                        // silently vanish from the proposal in the meantime. Nothing about fieldwork
                        // may be blocked by a typo in a date box.
                        val ordered = if (photoDayIndex(start)!! <= photoDayIndex(end)!!) start to end else end to start
                        anchors.add(
                            DwWorkshopAnchor(
                                stageKey = stage.key,
                                stageNumber = stage.number,
                                stageTitle = stage.title,
                                entityKey = entity.key,
                                entityTitle = entity.title,
                                rowKey = key,
                                rowLabel = label,
                                fieldLabel = "${labelOf(startKey)} – ${labelOf(endKey)}",
                                kind = DwAnchorKind.SPAN,
                                start = ordered.first,
                                end = ordered.second,
                            )
                        )
                    }

                    for (dateKey in dateKeys) {
                        if (dateKey in consumed) continue
                        val day = dateValue(row, dateKey) ?: continue
                        anchors.add(
                            DwWorkshopAnchor(
                                stageKey = stage.key,
                                stageNumber = stage.number,
                                stageTitle = stage.title,
                                entityKey = entity.key,
                                entityTitle = entity.title,
                                rowKey = key,
                                rowLabel = label,
                                fieldLabel = labelOf(dateKey),
                                kind = DwAnchorKind.DAY,
                                start = day,
                                end = day,
                            )
                        )
                    }
                }
            }
        }

        return anchors
    }

    /**
     * The IMAGE / IMAGE_LIST fields a confirmed photograph could actually be filed into, per entity.
     *
     * Registry-read like everything else, and returned rather than chosen: a stage can declare both
     * "Opening photographs" and "Event photographs", and which of the two a given photograph belongs
     * in is not knowable from a timestamp. The surface offers the entity's own list and defaults to
     * its first, which is the BASIC-tier one by declaration order.
     */
    fun photoTargets(schema: SchemaResponse, stageKey: String, entityKey: String): List<DwPhotoTarget> {
        val stage = schema.stages.firstOrNull { it.key == stageKey }
        val entity = stage?.entities?.firstOrNull { it.key == entityKey }
        return entity?.fields.orEmpty()
            .filter { it.type == "IMAGE" || it.type == "IMAGE_LIST" }
            .map { DwPhotoTarget(fieldKey = it.key, fieldLabel = it.label, multiple = it.type == "IMAGE_LIST") }
    }

    // ── Ranking ──────────────────────────────────────────────────────────────────────────────────

    /**
     * The distance a photograph is from an anchor whose dates cannot be read at all.
     *
     * `Int.MAX_VALUE` standing in for the web's `Number.MAX_SAFE_INTEGER`, and it is a SENTINEL that
     * [rankProposals] filters out rather than a large distance — an anchor with an unreadable date
     * must contribute nothing, not sort last.
     */
    private const val UNDATED = Int.MAX_VALUE

    /** Whole days from [day] to the nearest edge of the anchor; 0 when it falls inside. */
    private fun distanceTo(anchor: DwWorkshopAnchor, day: Int): Int {
        val from = photoDayIndex(anchor.start) ?: return UNDATED
        val to = photoDayIndex(anchor.end) ?: return UNDATED
        if (day < from) return from - day
        if (day > to) return day - to
        return 0
    }

    /** How many days an anchor covers. 1 for a single day. */
    private fun widthOf(anchor: DwWorkshopAnchor): Int {
        val from = photoDayIndex(anchor.start) ?: return UNDATED
        val to = photoDayIndex(anchor.end) ?: return UNDATED
        return to - from + 1
    }

    /**
     * Order two candidate anchors for one photograph. Lower sorts first.
     *
     * THE RULE IS: NARROWER EVIDENCE WINS. Containment first, then distance, then width. A photograph
     * taken on the fourteenth is inside stage 1's thirty-day window AND on stage 13's log row for the
     * fourteenth, and both are true — but "the log entry for that exact day" is a reason and
     * "somewhere in the month the workshop ran" is barely one. Ranking by width is what puts the
     * specific claim above the vacuous one, and it is why stage 1's whole-workshop span is a useful
     * last resort rather than a permanent winner that would swallow every photograph in the batch.
     *
     * Stage number is the final tie-break so the order is total and stable: two anchors that match a
     * photograph equally well must not swap places between renders, or a designer scanning two
     * hundred rows sees the list reshuffle under them.
     *
     * `compareTo` on the strings rather than a locale collator, because the web's `<` compares UTF-16
     * code units and so does Kotlin's — the one place this package normally has to reach for
     * [DwPy.compareStrings] is a port of PYTHON, whose order is by code point. Two clients that
     * disagreed here would order two equally-good anchors differently and put a different proposal
     * on top.
     */
    private fun compareCandidates(a: DwWorkshopAnchor, b: DwWorkshopAnchor, day: Int): Int {
        val distanceA = distanceTo(a, day)
        val distanceB = distanceTo(b, day)
        if (distanceA != distanceB) return distanceA.compareTo(distanceB)
        val widthA = widthOf(a)
        val widthB = widthOf(b)
        if (widthA != widthB) return widthA.compareTo(widthB)
        if (a.stageNumber != b.stageNumber) return a.stageNumber.compareTo(b.stageNumber)
        if (a.entityKey != b.entityKey) return if (a.entityKey < b.entityKey) -1 else 1
        return a.rowKey.orEmpty().compareTo(b.rowKey.orEmpty())
    }

    /** The sentence under a proposal. See [DwStageProposal.evidence] for why it lives here. */
    private fun evidenceFor(
        stamp: DwPhotoStamp,
        anchor: DwWorkshopAnchor,
        basis: DwProposalBasis,
        daysAway: Int,
    ): String {
        val where = if (anchor.rowLabel != null) {
            "stage ${anchor.stageNumber}'s ${anchor.entityTitle} row “${anchor.rowLabel}”"
        } else {
            "stage ${anchor.stageNumber}'s ${anchor.entityTitle}"
        }
        val shift = if (stamp.shiftedFrom != null) {
            " The camera recorded ${stamp.raw} at ${stamp.shiftedFrom}; that is the workshop-local time above."
        } else {
            ""
        }
        // `lowercase()` with no locale is the invariant one, which is what the browser's
        // `toLowerCase()` also is. `lowercase(Locale.getDefault())` would turn "Inspection date" into
        // "ınspection date" on a handset set to Turkish, in a sentence pinned against the web's.
        val field = anchor.fieldLabel.lowercase()

        if (basis == DwProposalBasis.ON_DAY) {
            return "Taken ${formatStamp(stamp)} — $where, $field ${formatAnchorDate(anchor.start)}.$shift"
        }
        if (basis == DwProposalBasis.IN_SPAN) {
            return "Taken ${formatStamp(stamp)} — inside $where, $field ${formatRange(anchor)}.$shift"
        }
        val side = if (photoDayIndex(stamp.date)!! < photoDayIndex(anchor.start)!!) "before" else "after"
        val days = "$daysAway day${if (daysAway == 1) "" else "s"}"
        return "Taken ${formatStamp(stamp)} — nothing recorded covers that date. " +
            "It is $days $side $where, $field ${formatRange(anchor)}.$shift"
    }

    /**
     * Rank the anchors for one photograph whose clock has already been read.
     *
     * Exported on its own so the ranking can be tested by value — one photograph, a handful of
     * anchors, an expected order — without constructing a registry.
     */
    fun rankProposals(stamp: DwPhotoStamp, anchors: List<DwWorkshopAnchor>): List<DwStageProposal> {
        val day = photoDayIndex(stamp.date) ?: return emptyList()
        val usable = anchors.filter { distanceTo(it, day) != UNDATED }
        if (usable.isEmpty()) return emptyList()

        // `sortedWith` is a stable sort, as `Array.prototype.sort` has been since ES2019 — which is
        // what makes [compareCandidates]'s final tie-break enough for a total order.
        val ordered = usable.sortedWith { a, b -> compareCandidates(a, b, day) }
        val nearest = distanceTo(ordered[0], day)
        // Refusal, not a long-range guess. See [OUTSIDE_GRACE_DAYS].
        if (nearest > OUTSIDE_GRACE_DAYS) return emptyList()

        return ordered.take(MAX_PROPOSALS).map { anchor ->
            val daysAway = distanceTo(anchor, day)
            val basis = when {
                daysAway > 0 -> DwProposalBasis.NEAREST
                anchor.kind == DwAnchorKind.DAY -> DwProposalBasis.ON_DAY
                else -> DwProposalBasis.IN_SPAN
            }
            DwStageProposal(
                anchor = anchor,
                basis = basis,
                daysAway = daysAway,
                evidence = evidenceFor(stamp, anchor, basis, daysAway),
            )
        }
    }

    /**
     * The whole intake: every photograph, what was read off it, and what is proposed for it.
     *
     * ORDER IS PRESERVED. The rows come back in the order the designer picked the files, which on a
     * camera dump is filename order and therefore usually shooting order. Sorting by timestamp here
     * would scatter the date-less files — the screenshots and the scans, which are exactly the ones
     * needing a human — to one end of a two-hundred-row list.
     *
     * NOTHING IS EVER DROPPED. A file with no EXIF date, a file with a broken one, and a file the
     * workshop's dates come nowhere near all come back as rows with a `refusal` and no proposal,
     * ready for manual assignment. A bulk importer that silently discarded the awkward third of a
     * camera dump would be worse than no bulk importer, because the designer would never learn which
     * third.
     */
    fun intakePhotos(
        photos: List<DwIntakePhoto>,
        anchors: List<DwWorkshopAnchor>,
        timeZone: String = DEFAULT_TIMEZONE,
    ): List<DwPhotoIntakeRow> {
        val zone = jsTrim(timeZone).ifEmpty { DEFAULT_TIMEZONE }
        val dated = anchors.filter { photoDayIndex(it.start) != null && photoDayIndex(it.end) != null }
        val earliest = dated.mapNotNull { photoDayIndex(it.start) }.minOrNull()
        val latest = dated.mapNotNull { photoDayIndex(it.end) }.maxOrNull()

        return photos.map { photo ->
            val stamp = resolveStamp(photo, zone)
                ?: return@map DwPhotoIntakeRow(
                    fileName = photo.fileName,
                    stamp = null,
                    refusal = "No capture date in this file. Screenshots, scanned pages and anything " +
                        "sent through a messaging app arrive with it stripped — choose the stage yourself.",
                    proposals = emptyList(),
                )

            val proposals = rankProposals(stamp, dated)
            if (proposals.isNotEmpty()) {
                return@map DwPhotoIntakeRow(photo.fileName, stamp, null, proposals)
            }

            if (earliest == null || latest == null) {
                return@map DwPhotoIntakeRow(
                    fileName = photo.fileName,
                    stamp = stamp,
                    refusal = "Taken ${formatStamp(stamp)}, but this workshop has no dates recorded yet " +
                        "— fill in the start and end dates on stage 1 and the daily logs, and these " +
                        "photographs will place themselves.",
                    proposals = emptyList(),
                )
            }

            // Name the distance rather than saying "no match". A batch off a camera whose clock was
            // never set reads "1,847 days before" on every row, and that sentence diagnoses the
            // camera in one glance where a bare refusal would have the designer re-checking the
            // workshop's dates.
            //
            // THE GAP IS MEASURED TO THE OUTER ENVELOPE, exactly as `intakePhotos` measures it in
            // `lib/photoIntake.ts`, and that is a defect both clients carry rather than one this port
            // introduced: a photograph BETWEEN two anchors more than the grace apart is inside
            // [earliest, latest], so the "after the last" arm subtracts a larger number and prints a
            // negative day count. It is reproduced rather than quietly corrected because a refusal
            // that reads differently on the phone and the laptop is a worse failure than one that
            // reads oddly on both — the photograph is still listed and still assignable by hand
            // either way. The fix belongs in `lib/photoIntake.ts` and here, in one commit.
            val day = photoDayIndex(stamp.date)!!
            val gap = if (day < earliest) earliest - day else day - latest
            val side = if (day < earliest) "before the first" else "after the last"
            val edge = if (day < earliest) earliest else latest
            DwPhotoIntakeRow(
                fileName = photo.fileName,
                stamp = stamp,
                refusal = "Taken ${formatStamp(stamp)} — ${indianGrouped(gap)} day${if (gap == 1) "" else "s"} " +
                    "$side date recorded for this workshop (${formatAnchorDate(indexToDate(edge))}). " +
                    "No stage is proposed; if the camera's clock was wrong, choose the stage yourself.",
                proposals = emptyList(),
            )
        }
    }

    /**
     * A one-line summary of a finished intake, for the surface's header.
     *
     * Counted rather than described: "180 of 200 placed" is the number a designer needs to decide
     * whether to press Confirm, and the twenty are the ones they then go and look at. Truncation and
     * omission must always be visible in this repository; this is that rule applied to an import.
     */
    fun intakeSummary(rows: List<DwPhotoIntakeRow>): DwIntakeSummary {
        val proposed = rows.count { it.proposals.isNotEmpty() }
        return DwIntakeSummary(total = rows.size, proposed = proposed, manual = rows.size - proposed)
    }

    /**
     * Add one media reference to whatever a field already holds.
     *
     * MORE CONSERVATIVE THAN `appendMediaRef` IN `lib/photoIntake.ts`, in one case, on purpose. The
     * web reads the existing list only when the stored value is an ARRAY and starts from empty
     * otherwise — so an IMAGE_LIST that somehow holds a bare string (a value written before the field
     * became a list) loses that photograph the moment a second one is added. [DwValues.list] keeps
     * it, which is also what [MediaField] already shows on the capture card for the same value, so
     * this client is at least self-consistent. Silently dropping a reference to a photograph is not a
     * parity worth having.
     */
    fun appendMediaRef(value: JsonElement?, ref: String, multiple: Boolean): JsonElement {
        if (!multiple) return JsonPrimitive(ref)
        val existing = DwValues.list(value)
        if (ref in existing) return JsonArray(existing.map { JsonPrimitive(it) })
        return JsonArray((existing + ref).map { JsonPrimitive(it) })
    }
}

// --------------------------------------------------------------------------------------
// The values the intake speaks in
// --------------------------------------------------------------------------------------

/** One photograph as the intake sees it: a name, and whatever clock the file carried. */
data class DwIntakePhoto(
    val fileName: String,
    /**
     * EXIF `DateTimeOriginal`, VERBATIM ("2026:02:14 10:22:33"), or null when the file carries none.
     *
     * Null is normal and must stay expressible. Screenshots, flatbed scans of a sanction order, and
     * anything that has been through WhatsApp all arrive with the EXIF stripped, and those are real
     * workshop photographs. The one thing that may never happen to them is being silently dropped or
     * silently guessed — see [DwPhotoIntakeRow.refusal].
     */
    val takenAt: String?,
    /** EXIF `OffsetTimeOriginal` ("+05:30"), on the minority of cameras that write one. */
    val takenAtOffset: String? = null,
)

/** Whether an anchor is one recorded day or a recorded window. */
enum class DwAnchorKind { DAY, SPAN }

/**
 * A dated thing in the workshop that a photograph could belong to.
 *
 * Built by [DwPhotoIntake.buildAnchors] from the registry plus the workshop's saved stage data.
 * [start] and [end] are inclusive `yyyy-mm-dd` calendar dates and are equal for a single day.
 */
data class DwWorkshopAnchor(
    val stageKey: String,
    val stageNumber: Int,
    val stageTitle: String,
    val entityKey: String,
    /** The entity's title as the registry words it ("Stage logs"), for the evidence sentence. */
    val entityTitle: String,
    /**
     * Which row of a collection this came from — `_clientKey` or `_entryId`, whichever the row has.
     * Null for a singleton. A confirmation carries it so a photograph can join the right row.
     */
    val rowKey: String?,
    /** The row's own label ("Warping the loom"), when the entity declares a label field. */
    val rowLabel: String?,
    /** The date field(s) this was read from, as the registry labels them ("Start date – End date"). */
    val fieldLabel: String,
    val kind: DwAnchorKind,
    /** Inclusive, `yyyy-mm-dd`. Equal to [end] when [kind] is DAY. */
    val start: String,
    val end: String,
)

/** Why a proposal was made — the shape of the match, not its strength. */
enum class DwProposalBasis {
    /** The photograph's calendar date IS this anchor's single recorded day. */
    ON_DAY,

    /** The photograph's calendar date falls inside this anchor's recorded window. */
    IN_SPAN,

    /** Nothing recorded covers that date; this is the nearest dated thing, and it is close. */
    NEAREST,
}

data class DwStageProposal(
    val anchor: DwWorkshopAnchor,
    val basis: DwProposalBasis,
    /** Whole days from the photograph to the nearest edge of the anchor. 0 when it falls inside. */
    val daysAway: Int,
    /**
     * The sentence shown beside the proposal, e.g. *"Taken 14 Feb 2026, 10:22 — stage 13's Stage logs
     * row “Warping the loom”, date 14 Feb 2026."*
     *
     * Written HERE, not in the screen, for the reason every cross-client string in this repository
     * is: the web, the handset and any later report of what was imported have to justify one decision
     * in one wording, or two clients describe the same proposal differently and a designer comparing
     * them has to work out whether they disagree.
     */
    val evidence: String,
)

/** The parsed wall clock, reduced to the two things the ranking needs plus what the evidence prints. */
data class DwPhotoStamp(
    /** Calendar date in the workshop's zone, `yyyy-mm-dd`. */
    val date: String,
    /** Minutes since midnight, for display and for ordering photographs within a day. */
    val minutes: Int,
    /** The clock EXIF actually wrote, kept verbatim so the evidence can show the unshifted reading. */
    val raw: String,
    /**
     * Set when the camera declared its own zone and the clock was moved into the workshop's.
     * Null on the ordinary path, where the wall clock is taken at face value.
     */
    val shiftedFrom: String?,
)

/** One row of the intake list: a photograph, what was read off it, and what is proposed. */
data class DwPhotoIntakeRow(
    val fileName: String,
    /** Null when the file carried no usable EXIF date. */
    val stamp: DwPhotoStamp?,
    /**
     * Why there is no proposal, in a sentence, when there is none — and null when there are
     * proposals.
     *
     * Never empty and never a shrug: either the file carried no date, or it carried one that nothing
     * recorded comes near. Both are ordinary, both leave the photograph on screen for manual
     * assignment, and neither is allowed to look like a bug.
     */
    val refusal: String?,
    /** Best first, capped at [DwPhotoIntake.MAX_PROPOSALS]. Empty exactly when [refusal] is set. */
    val proposals: List<DwStageProposal>,
)

/** One image field a confirmed photograph could be written into. */
data class DwPhotoTarget(
    val fieldKey: String,
    val fieldLabel: String,
    /** True for IMAGE_LIST, which holds many; false for IMAGE, which holds exactly one. */
    val multiple: Boolean,
)

data class DwIntakeSummary(val total: Int, val proposed: Int, val manual: Int)

/**
 * One stage's saved data in the shape [DwPhotoIntake.buildAnchors] reads, mirroring the web's
 * `DwStageData`.
 *
 * Kept as a plain value rather than taking a [WorkshopDraft] directly so the anchor walk is testable
 * on the desktop JVM against the web spec's own fixtures. [dwStageDataFrom] is the adapter from what
 * this device actually stores.
 */
data class DwStageData(
    val singleton: DwDataRow = emptyMap(),
    val collections: Map<String, List<DwDataRow>> = emptyMap(),
)

/**
 * The local draft as stage data the anchor walk can read.
 *
 * LOCAL DRAFT ONLY, exactly as the web's import page reads only `loadDraft(id)`. A workshop this
 * handset has never opened has no dates on it to match against, and the surface says so in words
 * rather than proposing nothing and looking broken — falling back to the server's copy would let a
 * photograph be filed into a collection row that does not exist in this draft, which the stage
 * payload builder has no way to address.
 *
 * Rows go through [codeRow] so a row entered in a courtyard this morning — whose client key lives in
 * the draft row's id rather than inside the object — still has a key a confirmation can aim at.
 */
fun dwStageDataFrom(schema: SchemaResponse, draft: WorkshopDraft?): Map<String, DwStageData> {
    if (draft == null) return emptyMap()
    val out = LinkedHashMap<String, DwStageData>()
    for (stage in schema.stages) {
        val stored = draft.stages[stage.key] ?: continue
        out[stage.key] = DwStageData(
            singleton = stored.values,
            collections = stage.collections.associate { entity ->
                entity.key to stored.rowsFor(entity.key).map { it.codeRow() }
            },
        )
    }
    return out
}
