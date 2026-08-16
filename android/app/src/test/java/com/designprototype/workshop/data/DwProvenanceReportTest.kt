package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **THE ADMIN AUTHORSHIP & DIVERGENCE VIEW** — the arithmetic behind it, and the words it says.
 *
 * ── WHAT IT IS FOR ───────────────────────────────────────────────────────────────────────────────
 *
 * A workshop keeps what the designer saw ON THE DAY; the shared record goes on changing. Only the
 * `reference` stamp plus a live read of the record can say "this workshop says Barpali and the
 * artisan record now says Bargarh", because once hydrated the value is an ordinary string in `data` —
 * a hydrated village and a typed village are the same bytes, deliberately.
 *
 * ── DIVERGENCE IS NOT AN ERROR, AND THESE TESTS ARE WRITTEN THAT WAY ON PURPOSE ──────────────────
 *
 * Nothing here asserts that divergence is bad, counts "problems", or expects a warning. An artisan
 * who moved village after the workshop makes every row naming them diverge and every one of those
 * rows is CORRECT. The screen exists so an admin can EXPLAIN why a submitted report and the live
 * directory disagree, and these tests exist so the arithmetic behind that explanation is right.
 *
 * ── THE PAIR THIS FILE BELONGS TO ────────────────────────────────────────────────────────────────
 *
 * `frontend/e2e/workshop-provenance-unit.spec.ts` makes the same claims for the browser, and this
 * file mirrors it case for case wherever the two surfaces claim the same thing. There is ONE case
 * where the fixtures differ rather than the intent, and it is not a style choice: see
 * `a deleted canonical record counts and is never dropped`.
 *
 * The wire fixture below is the shape `workshop_provenance` actually emits — verbatim from that route
 * and from `canonical_divergence` (`backend/app/services/entry_provenance.py`), including the four
 * keys of a comparison and the tuple `backend/tests/test_entry_provenance.py` pins for a deleted
 * record. A DTO that agrees with the model we imagined the server has is worth nothing.
 */
class DwProvenanceReportTest {

    /** The same leniencies [ApiClient] decodes with, so this file cannot pass on a stricter parser. */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    /**
     * `GET /design-workshops/{id}/provenance` as the server sends it: two participant rows and the
     * stage's own singleton.
     *
     *  * `ent_p1` copied two fields from one artisan record. The name still matches; the village has
     *    been corrected on the record since the workshop. Its phone was TYPED by a designer, so it
     *    carries a stamp and NO comparison — there is no record behind it to compare against.
     *  * `ent_p2` copied a name from a record that has since been DELETED: `canonical: null`,
     *    `diverged: false`, `recordDeleted: true`. That is the exact tuple the backend test pins.
     *  * `ent_s1` is the singleton, which copied nothing: `canonical` is `{}` — the route builds it
     *    as `divergence.get(row.id, {})` — and `ordinal` IS PRESENT AND IS `0`.
     *
     * THE SINGLETON'S `ordinal: 0` IS THE POINT OF THIS FIXTURE AND IT USED TO BE MISSING. The key
     * was omitted here to mean "a singleton has no row number", which is a payload the server cannot
     * produce: `DwStageEntry.ordinal` is `Int @default(0)`, non-nullable, its schema comment reads
     * "A singleton's is always 0", and the route emits `"ordinal": row.ordinal` unconditionally. The
     * fiction made a green test out of a screen that headed every singleton "· row 1", which is the
     * exact sin this file's docstring indicts the browser's spec for. If a payload here is not one
     * the server sends, nothing built on it is worth anything.
     *
     * The stage/entity pairings are the registry's real ones for the same reason: `participant` is a
     * COLLECTION on `WORKSHOP_PLAN_PARTICIPANTS_OPENING`, and `workshopSetup` is the SINGLETON on
     * `WORKSHOP_SETUP`.
     */
    private val wire = """
        {
          "entries": [
            {
              "entryId": "ent_p1",
              "stageKey": "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
              "entityKey": "participant",
              "ordinal": 0,
              "createdById": "usr_asha",
              "fields": {
                "name": {"by": "usr_meena", "byName": "Meena Iyer",
                         "at": "2026-03-01T09:00:00+00:00", "source": "reference",
                         "refModel": "Artisan", "refId": "art_1", "refKey": "name"},
                "village": {"by": "usr_meena", "byName": "Meena Iyer",
                            "at": "2026-03-01T09:00:00+00:00", "source": "reference",
                            "refModel": "Artisan", "refId": "art_1", "refKey": "village"},
                "phone": {"by": "usr_asha", "byName": "Asha Patel",
                          "at": "2026-03-08T15:30:00+00:00", "source": "designer"}
              },
              "canonical": {
                "name": {"stored": "Latha Devi", "canonical": "Latha Devi",
                         "diverged": false, "recordDeleted": false},
                "village": {"stored": "Barpali", "canonical": "Bargarh Town",
                            "diverged": true, "recordDeleted": false}
              }
            },
            {
              "entryId": "ent_p2",
              "stageKey": "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
              "entityKey": "participant",
              "ordinal": 1,
              "createdById": "usr_asha",
              "fields": {
                "name": {"by": "usr_gone", "byName": null,
                         "at": "2026-03-01T09:00:00+00:00", "source": "reference",
                         "refModel": "Artisan", "refId": "art_2", "refKey": "name"}
              },
              "canonical": {
                "name": {"stored": "Kamla Bai", "canonical": null,
                         "diverged": false, "recordDeleted": true}
              }
            },
            {
              "entryId": "ent_s1",
              "stageKey": "WORKSHOP_SETUP",
              "entityKey": "workshopSetup",
              "ordinal": 0,
              "createdById": "usr_asha",
              "fields": {},
              "canonical": {}
            }
          ]
        }
    """.trimIndent()

    private fun decoded(): DwProvenanceReportDto =
        json.decodeFromString(DwProvenanceReportDto.serializer(), wire)

    /** A comparison, spelled out, so a case below reads as the claim it is making. */
    private fun comparison(
        stored: String? = null,
        canonical: String? = null,
        diverged: Boolean = false,
        recordDeleted: Boolean = false,
    ) = DwCanonicalComparisonDto(
        stored = stored?.let { JsonPrimitive(it) },
        canonical = canonical?.let { JsonPrimitive(it) },
        diverged = diverged,
        recordDeleted = recordDeleted,
    )

    private fun entry(
        entryId: String = "e1",
        canonical: Map<String, DwCanonicalComparisonDto>,
    ) = DwProvenanceEntryDto(
        entryId = entryId,
        stageKey = "S",
        entityKey = "participant",
        canonical = canonical,
    )

    // ── The wire ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the real response shape decodes, keys and all`() {
        // A wire test, and the failure it exists to catch is silent rather than loud: every field
        // below is defaulted, so a key spelled wrong here does not crash — it reads as "this field
        // was never copied from a record", which on screen is indistinguishable from a workshop where
        // nothing has moved. The whole screen would render its empty state over a real divergence.
        val report = decoded()
        assertEquals(3, report.entries.size)

        val p1 = report.entries[0]
        assertEquals("ent_p1", p1.entryId)
        assertEquals("WORKSHOP_PLAN_PARTICIPANTS_OPENING", p1.stageKey)
        assertEquals("participant", p1.entityKey)
        assertEquals(0, p1.ordinal)
        // Who created the ROW, carried BESIDE the per-field answers and never instead of them — the
        // row is Asha's while two of its three fields are attributed to Meena's artisan record, which
        // is the state this whole feature exists to make visible.
        assertEquals("usr_asha", p1.createdById)

        // The stamps are the SAME type the stage read carries, and the same `attribution()`. If this
        // ever diverges the handset has two sentences for one fact.
        assertEquals(
            "From the artisan record, by Meena Iyer",
            p1.fields["village"]?.attribution()
        )
        // A TYPED field carries a stamp and NO comparison: there is no record behind it. Its absence
        // from `canonical` is the honest answer, not a gap.
        assertTrue(p1.fields.containsKey("phone"))
        assertFalse(p1.canonical.containsKey("phone"))

        val village = p1.canonical["village"]!!
        assertEquals(JsonPrimitive("Barpali"), village.stored)
        assertEquals(JsonPrimitive("Bargarh Town"), village.canonical)
        assertTrue(village.diverged)
        assertFalse(village.recordDeleted)
    }

    @Test
    fun `an entry that copied nothing has an empty map and not a null`() {
        // The route builds this as `divergence.get(row.id, {})`, so "this row copied nothing from a
        // shared record" is `{}`. Reading it as a missing/absent map would be the same answer here.
        val singleton = decoded().entries[2]
        assertEquals(emptyMap<String, DwCanonicalComparisonDto>(), singleton.canonical)
        // AND ITS ORDINAL IS 0, NOT ABSENT. The column is `Int @default(0)` and the route emits it
        // unconditionally, so "is there an ordinal?" can never mean "is this one of a list?" — which
        // is what both surfaces asked, and why both headed every singleton "· row 1". Whether a row
        // has a position is the REGISTRY's answer; see `dwProvenanceEntryTitle`.
        assertEquals(0, singleton.ordinal)
    }

    // ── The arithmetic. Mirrors frontend/e2e/workshop-provenance-unit.spec.ts ────────────────────

    @Test
    fun `a workshop whose copied values still match reports nothing to explain`() {
        // The COMMON CASE, and the one the screen must answer in a line rather than with an empty
        // table that reads like a failed load.
        val tally = dwDivergenceTally(
            DwProvenanceReportDto(
                entries = listOf(
                    entry(canonical = mapOf("name" to comparison("Latha", "Latha", diverged = false)))
                )
            )
        )
        assertEquals(DwDivergenceTally(records = 0, fields = 0), tally)
        assertTrue(tally.nothingHasMoved)
        assertEquals("Nothing has moved since this workshop was recorded", dwDivergenceHeadline(tally))
    }

    @Test
    fun `fields and records are counted separately, because they answer different questions`() {
        // "Three fields across one participant" and "three fields across three participants" are very
        // different findings for an admin — one artisan's record was corrected, or a whole cluster's
        // directory has moved on — and a single total cannot tell them apart.
        val tally = dwDivergenceTally(
            DwProvenanceReportDto(
                entries = listOf(
                    entry(
                        entryId = "e1",
                        canonical = mapOf(
                            "village" to comparison("Barpali", "Bargarh", diverged = true),
                            "phone" to comparison("98765", "99999", diverged = true),
                            "name" to comparison("Latha", "Latha", diverged = false),
                        )
                    ),
                    entry(
                        entryId = "e2",
                        canonical = mapOf("name" to comparison("Bhikari", "Bhikari", diverged = false))
                    ),
                )
            )
        )
        assertEquals(DwDivergenceTally(records = 1, fields = 2), tally)
        assertEquals("2 fields across 1 record now differ", dwDivergenceHeadline(tally))
    }

    @Test
    fun `only the diverged fields are listed, never the whole comparison`() {
        val fields = dwDivergedFields(
            entry(
                canonical = mapOf(
                    "village" to comparison("Barpali", "Bargarh", diverged = true),
                    "name" to comparison("Latha", "Latha", diverged = false),
                )
            )
        )
        assertEquals(listOf("village"), fields.map { it.first })
    }

    @Test
    fun `a deleted canonical record counts and is never dropped`() {
        /*
         * THE CASE REFERENCE HYDRATION EXISTS FOR. The record is gone and this workshop now holds the
         * ONLY copy of what the designer saw, which is the most interesting row on the screen —
         * omitting it would make "the record is gone" look identical to "the field was never copied",
         * the one distinction an auditor opened the screen to make.
         *
         * ── WHY THIS FIXTURE IS NOT THE WEB'S, THOUGH THE CLAIM IS THE SAME ─────────────────────
         *
         * The browser's spec writes this row as `{stored: "Latha", canonical: null, diverged: true}`,
         * and the server never emits that. `canonical_divergence` computes
         * `diverged = source is not None and str(stored) != str(canonical)`, so with the record gone
         * `source` is None, `diverged` is FALSE and `recordDeleted` is TRUE —
         * `backend/tests/test_entry_provenance.py::test_a_deleted_record_reads_as_deleted_rather_
         * than_disappearing` pins exactly `{"stored": "Latha Devi", "canonical": None,
         * "diverged": False, "recordDeleted": True}`.
         *
         * So the browser's `divergedFields`, which filters on `comparison?.diverged` alone, DROPS this
         * row on real data — and the branch on its page that renders "The record this came from no
         * longer exists" is unreachable in production. Its spec passes only because the fixture
         * hand-writes a shape the server does not produce. This test uses the server's shape, which is
         * why the handset ORs `recordDeleted` into the filter. Rewriting this fixture to say
         * `diverged: true` would make the test pass against a payload that does not exist and would
         * take the deleted row off the screen.
         */
        val report = DwProvenanceReportDto(
            entries = listOf(
                entry(
                    canonical = mapOf(
                        "name" to comparison("Latha Devi", canonical = null, diverged = false, recordDeleted = true)
                    )
                )
            )
        )
        assertEquals(DwDivergenceTally(records = 1, fields = 1), dwDivergenceTally(report))
        assertEquals(listOf("name"), dwDivergedFields(report.entries[0]).map { it.first })
        assertEquals("1 field across 1 record now differ", dwDivergenceHeadline(dwDivergenceTally(report)))
    }

    @Test
    fun `the deleted row survives decoding of the real payload, not only a hand-built one`() {
        // The pair to the case above: the same claim, made against the wire fixture, so that a DTO
        // that silently loses `recordDeleted` cannot pass by having the hand-built case set it.
        val report = decoded()
        val deleted = report.entries[1].canonical["name"]!!
        assertTrue(deleted.recordDeleted)
        assertFalse("the server reports a deleted record as NOT diverged", deleted.diverged)
        assertTrue(deleted.hasMoved)
        // ent_p1's village (a real correction) and ent_p2's name (a deleted record) — two records,
        // two fields. The singleton contributes to neither.
        assertEquals(DwDivergenceTally(records = 2, fields = 2), dwDivergenceTally(report))
    }

    @Test
    fun `a cleared column is not reported as a deleted record`() {
        /*
         * THE OTHER HALF OF THE SAME DISTINCTION, and the reason the deleted sentence is keyed on
         * `recordDeleted` rather than on a null `canonical`.
         *
         * A record that is perfectly present and has simply had a column blanked answers
         * `canonical: null` with `recordDeleted: false`. That IS a divergence and must be listed —
         * a workshop still holding a phone number the directory has since cleared is one of the more
         * useful things on this screen — but it is emphatically not a deleted record, and telling an
         * admin the artisan's whole record is gone would send them looking for a record that is
         * sitting there. The browser does exactly that, because its type never modelled the flag.
         */
        val cleared = comparison("98765", canonical = null, diverged = true, recordDeleted = false)
        assertTrue(cleared.hasMoved)
        assertFalse(cleared.recordDeleted)
        // AND THE CELL ITSELF SAYS THE EM DASH. `dwCanonicalText` and not `dwComparisonText`: the
        // choice between the deleted sentence and the value is the whole subject of this test, and
        // while it lived inside the composable this case asserted three things that were all true
        // with the branch inverted. It is one line in the pure layer now, so it is pinnable here.
        assertEquals("—", dwCanonicalText(cleared))
        // The other side of the same branch, so neither can be satisfied alone.
        assertEquals(
            DW_PROVENANCE_RECORD_DELETED,
            dwCanonicalText(comparison("Kamla Bai", canonical = null, recordDeleted = true))
        )
    }

    @Test
    fun `a whole number is spelled the way the browser spells it`() {
        /*
         * THE ONE PLACE THE TWO LANGUAGES DISAGREE ABOUT WHAT A NUMBER LOOKS LIKE, on the one screen
         * whose entire job is showing whether two values are the same.
         *
         * Kotlin hands back the raw literal off the wire; the browser has already been through
         * `JSON.parse`, and `String(127.0)` in JavaScript is "127" — a trailing `.0` cannot survive
         * becoming a JS number. And the server really does send one: `coerce_value` stores a DECIMAL
         * as a raw Python float (MONEY stringifies, DECIMAL does not) and `_inches_to_cm` returns
         * `round(float(v) * 2.54, 2)`, so `lengthCm` is `127.0` on both sides of the comparison.
         *
         * The sharpest form is a single row whose two columns spell one number differently — stored
         * `0`, canonical `0.0`, side by side. The submitted .docx agrees with the browser too
         * (`report_builder` prints DECIMAL with `%g`), so the handset was the odd one out of three.
         */
        assertEquals("127", dwComparisonText(JsonPrimitive(127.0)))
        assertEquals("0", dwComparisonText(JsonPrimitive(0.0)))
        assertEquals("-4", dwComparisonText(JsonPrimitive(-4.0)))
        // A REAL FRACTION KEEPS ITS DECIMAL. Rounding here would not be a formatting choice, it would
        // be a wrong measurement in the column an admin is auditing.
        assertEquals("127.5", dwComparisonText(JsonPrimitive(127.5)))
        assertEquals("2.54", dwComparisonText(JsonPrimitive(2.54)))
        // And the neighbours this must not disturb: `0` is still an answer and not an empty cell,
        // `false` is still a word, and a string that merely looks numeric is passed through as typed.
        assertEquals("0", dwComparisonText(JsonPrimitive(0)))
        assertEquals("false", dwComparisonText(JsonPrimitive(false)))
        assertEquals("127.0", dwComparisonText(JsonPrimitive("127.0")))
    }

    // ── Values ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an empty value renders as a dash rather than as the word null`() {
        // A blank cell means "this side says nothing here", which is a real answer. The em dash is
        // the browser's character, U+2014, and the two surfaces must print the same one.
        assertEquals("—", dwComparisonText(null))
        assertEquals("—", dwComparisonText(JsonNull))
        assertEquals("—", dwComparisonText(JsonPrimitive("")))
        assertEquals("—", DW_COMPARISON_EMPTY)
    }

    @Test
    fun `zero and false are answers and must not render as the dash`() {
        // The falsy trap, which JavaScript and Kotlin fail in different ways and which both surfaces
        // therefore assert rather than trust the language for. A cost of zero and a "no" are things
        // the record SAYS; rendering either as an empty cell tells an admin it says nothing.
        assertEquals("0", dwComparisonText(JsonPrimitive(0)))
        assertEquals("false", dwComparisonText(JsonPrimitive(false)))
        assertEquals("Barpali", dwComparisonText(JsonPrimitive("Barpali")))
    }

    @Test
    fun `an object value is printed as JSON rather than summarised`() {
        // `spec.data(...)` yields GEO points and media references for some columns and this view does
        // not model them. Braces are ugly and honest; a prose summary invented for a shape the screen
        // cannot interpret would be a worse lie, and `JSON.stringify` is what the browser prints.
        val geo = buildJsonObject { put("lat", 21.34); put("lng", 83.61) }
        assertEquals("""{"lat":21.34,"lng":83.61}""", dwComparisonText(geo))
    }

    // ── Headings ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an entry is titled in the registry's words and falls back to its keys`() {
        val registry = SchemaResponse(
            stages = listOf(
                StageDto(
                    key = "WORKSHOP_PLAN_PARTICIPANTS_OPENING",
                    title = "Participants",
                    entities = listOf(
                        EntityDto(key = "participant", title = "Participants", cardinality = "COLLECTION")
                    )
                ),
                StageDto(
                    key = "WORKSHOP_SETUP",
                    title = "Workshop setup",
                    entities = listOf(
                        EntityDto(key = "workshopSetup", title = "Workshop details", cardinality = "SINGLETON")
                    )
                )
            )
        )
        val report = decoded()
        // Zero-based on the wire, ONE-BASED on screen: `_ordinal` is 0 for the first participant, and
        // "row 0" is a sentence no admin counting down a table will match against what is in front of
        // them. The web adds the same 1.
        assertEquals(
            "Participants · Participants · row 1",
            dwProvenanceEntryTitle(report.entries[0], registry)
        )
        // A SINGLETON GETS NO ROW CLAUSE, and its ordinal is 0 rather than absent — so this can only
        // come out right by asking the registry what kind of row it is. Keyed on the ordinal instead,
        // this read "Workshop setup · Workshop details · row 1": a position in a list of one, for a
        // stage's own answers, on both surfaces.
        assertEquals(
            "Workshop setup · Workshop details",
            dwProvenanceEntryTitle(report.entries[2], registry)
        )
        // NO REGISTRY AT ALL is an ordinary state, not a failure: it is fetched separately, it may be
        // a release behind the server that produced this report, and a key is readable whereas a
        // blank heading over a table of differences leaves an admin unable to say which record they
        // are looking at. The ROW CLAUSE goes with it, because the registry was the only thing that
        // knew whether this row has a position — an ambiguous heading is worse than a wrong one only
        // until the wrong one is a confident "row 1" on a record that has no rows.
        assertEquals(
            "WORKSHOP_PLAN_PARTICIPANTS_OPENING · participant",
            dwProvenanceEntryTitle(report.entries[0], null)
        )
    }

    @Test
    fun `both halves of the headline are pluralised on their own number`() {
        // "1 fields across 2 records" is the kind of sentence that makes a reader distrust the number
        // beside it, and the browser pluralises each independently for that reason.
        assertEquals(
            "1 field across 1 record now differ",
            dwDivergenceHeadline(DwDivergenceTally(records = 1, fields = 1))
        )
        assertEquals(
            "5 fields across 3 records now differ",
            dwDivergenceHeadline(DwDivergenceTally(records = 3, fields = 5))
        )
    }

    // ── The words ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every sentence is the browser's, character for character`() {
        /*
         * A TRIPWIRE, NOT A PROOF — and worth having as exactly that. It cannot know what the web
         * says today; what it does is make a reword on THIS surface impossible to land quietly, by
         * failing with the name of the file the other half lives in. The literals below were copied
         * from `frontend/app/(protected)/design-workshops/[id]/provenance/page.tsx` and
         * `frontend/lib/designWorkshopProvenance.ts`.
         *
         * Why it matters more here than on most screens: the sentence being pinned is "divergence is
         * NOT an error". A designer or admin who reads a firm version of that on a laptop and a
         * hedged one on the handset has been told two things by one product, on the screen whose
         * entire purpose is to stop a correct record from being mistaken for a wrong one.
         */
        assertEquals("Authorship & divergence", DW_PROVENANCE_TITLE)
        assertEquals("Comparing this workshop against the shared records…", DW_PROVENANCE_LOADING)
        assertEquals("Could not load the provenance view.", DW_PROVENANCE_LOAD_FAILED)
        assertEquals("Nothing has moved since this workshop was recorded", DW_PROVENANCE_NOTHING_MOVED)
        assertEquals(
            "Every value this workshop copied from a shared record still matches that record.",
            DW_PROVENANCE_ALL_MATCH
        )
        assertEquals("The record this came from no longer exists", DW_PROVENANCE_RECORD_DELETED)
        assertEquals("Field", DW_PROVENANCE_COLUMN_FIELD)
        assertEquals("This workshop", DW_PROVENANCE_COLUMN_STORED)
        assertEquals("The record today", DW_PROVENANCE_COLUMN_CANONICAL)
        // The paragraph is stored in three pieces only so the middle one can be bolded, exactly as
        // the browser's `<strong>` does. Joined, it is one sentence and must read as one.
        assertEquals(
            "A workshop keeps what the designer saw on the day, so a value that differs from the " +
                "shared record today is not an error — an artisan who moved village after the " +
                "workshop makes every row that names them differ, and every one of those rows is " +
                "right. This page exists so the difference can be seen and explained, not corrected.",
            DW_PROVENANCE_NOT_AN_ERROR_PREFIX +
                DW_PROVENANCE_NOT_AN_ERROR_EMPHASIS +
                DW_PROVENANCE_NOT_AN_ERROR_SUFFIX
        )
    }

    @Test
    fun `nothing on this screen calls a divergence a fault`() {
        // THE RULE THE WHOLE FEATURE TURNS ON, asserted rather than merely written down. A workshop
        // is a dated observation and is SUPPOSED to keep what the designer saw; an artisan who moved
        // village makes every row naming them differ and every one of those rows is right. The day
        // somebody "improves" this copy with the word "error" or "problem", or adds a "Fix" control,
        // this test is what says no — and the sentence above is why.
        val everySentence = listOf(
            DW_PROVENANCE_TITLE,
            DW_PROVENANCE_NOTHING_MOVED,
            DW_PROVENANCE_ALL_MATCH,
            DW_PROVENANCE_RECORD_DELETED,
            DW_PROVENANCE_COLUMN_FIELD,
            DW_PROVENANCE_COLUMN_STORED,
            DW_PROVENANCE_COLUMN_CANONICAL,
            dwDivergenceHeadline(DwDivergenceTally(records = 3, fields = 5)),
        ).joinToString(" ").lowercase()
        // "error" IS ON THIS LIST. It was left off — the file says "not an error" twice, so it reads
        // like a word that cannot be banned — but the paragraph constants are deliberately NOT among
        // the eight scanned above, precisely so the one sanctioned use does not buy an exemption for
        // the other seven strings. Without it, the very word the docstring promises to catch was the
        // one word that could be added freely.
        val accusations =
            listOf("error", "problem", "wrong", "invalid", "stale", "fix", "conflict", "outdated", "!")
        for (accusation in accusations) {
            assertFalse(
                "the divergence view must never call a correct, dated observation a $accusation",
                everySentence.contains(accusation)
            )
        }
        // AND EVERY WORD THE SCREEN SAYS THAT IS NOT A CONSTANT, WHICH IS WHERE THE CONTROLS LIVE.
        // The eight strings above are the copy the two surfaces share; the screen also has button
        // labels and refusal sentences of its own, written inline. So a "Fix this record" control —
        // the exact thing this test's docstring promises to prevent — could be added without
        // touching any constant, and this case stayed green. Reading the source is blunt, and it is
        // the only reader that can see a literal nobody has written yet.
        //
        // Comment lines are skipped deliberately: this file's comments discuss the browser's defect
        // and use several of these words to do it, and a guard that cannot be explained in a comment
        // beside itself is a guard people delete.
        val screen = repoFile(
            "src/main/java/com/designprototype/workshop/ui/designworkshop/DwProvenanceScreen.kt",
            "app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwProvenanceScreen.kt",
            "android/app/src/main/java/com/designprototype/workshop/ui/designworkshop/DwProvenanceScreen.kt",
        ).readText()
        val spoken = screen.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .flatMap { Regex("\"([^\"]*)\"").findAll(it).map { m -> m.groupValues[1] } }
            .joinToString(" ")
            .lowercase()
        // The guard is worthless if the scan found nothing, so prove it reached the one control the
        // screen does have — which is about the failed REQUEST and not about any divergence.
        assertTrue("the scan must actually be reading the screen's copy", spoken.contains("try again"))
        for (accusation in accusations) {
            assertFalse(
                "nothing the divergence view says may call a correct, dated observation a " +
                    "$accusation, and that includes its controls",
                spoken.contains(accusation)
            )
        }
        // "not an error" is the ONE place the word may appear, and it appears there denied. Asserted
        // so the guard above cannot be satisfied by deleting the paragraph that says it.
        assertTrue(DW_PROVENANCE_NOT_AN_ERROR_PREFIX.endsWith("today is "))
        assertEquals("not an error", DW_PROVENANCE_NOT_AN_ERROR_EMPHASIS)
    }

    /** Walks up from the test's working directory, as the other source-reading tests here do. */
    private fun repoFile(vararg relative: String): java.io.File {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        while (dir != null) {
            for (path in relative) {
                val candidate = java.io.File(dir, path)
                if (candidate.isFile) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("none of ${relative.toList()} found from ${java.io.File(".").absolutePath}")
    }
}
