package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DwWorkshopSearch] on its own — no Compose, no server, no filesDir.
 *
 * Case for case the same spec as `frontend/e2e/design-workshop-search-unit.spec.ts`, which is the
 * specification both clients answer to: that file's header says the module was written "the same
 * shape as lib/marketAnalysis.ts, so that it can be reasoned about here and ported to Kotlin later",
 * and this is that port's half of the bargain. A case that file covers and this one does not is a
 * place the two search boxes are free to return different answers for the same workshop.
 *
 * THE ODIA CASE IS THE POINT OF THE FILE. `an Odia query finds an Odia value` fails outright — zero
 * hits — if the tokeniser's character class is written `[\p{L}]+` instead of `[\p{L}\p{M}]+`, because
 * the virama in ତନ୍ତ is category M and shatters the word into two tokens no prefix probe can reach.
 * That is the bug `backend/app/services/market_analysis.py` was found to have.
 *
 * AND ON THIS PLATFORM THERE IS A SECOND HALF TO IT, which the web spec cannot have and which
 * `the JVM's own character classes` pins: Java's `\w`, `\d` and `\b` are ASCII-ONLY unless
 * [java.util.regex.Pattern.UNICODE_CHARACTER_CLASS] is set, so the wrong spelling here does not merely
 * split an Odia word the way JavaScript's would — it matches nothing at all, and every Odia answer in
 * the workshop becomes unsearchable while the English tests stay green.
 *
 * The fixture is hand-built rather than read from the bundled registry asset, for the reason
 * [DwSubmissionReadinessTest] gives: a fixture that changed when somebody edited stage 5 would make
 * every failure here ambiguous.
 */
class DwWorkshopSearchTest {

    /* ── A miniature registry and draft, mirroring the web spec's ─────────────────────────────── */

    private val registry = SchemaResponse(
        version = "spec",
        enums = mapOf(
            "fabricFamily" to listOf(
                EnumOption(value = "COTTON", label = "Cotton"),
                // The token and the label share no word ON PURPOSE — see the ENUM test.
                EnumOption(value = "MUGA_TUSSAR", label = "Wild silk"),
            )
        ),
        stages = listOf(
            StageDto(
                number = 1, key = "SETUP", title = "Workshop setup",
                entities = listOf(
                    EntityDto(
                        key = "workshopSetup", name = "DwWorkshopSetup", cardinality = "SINGLETON",
                        title = "Workshop setup",
                        fields = listOf(
                            FieldDto(key = "craftName", label = "Craft name", type = "TEXT", tier = "BASIC"),
                            FieldDto(key = "craftRef", label = "Craft", type = "REF", tier = "BASIC", refModel = "Craft"),
                            FieldDto(
                                key = "fabricFamily", label = "Fabric family", type = "ENUM",
                                tier = "STANDARD", enumName = "fabricFamily",
                            ),
                            FieldDto(key = "background", label = "Background", type = "RICH_TEXT", tier = "BASIC"),
                            FieldDto(
                                key = "retiredNote", label = "Retired note", type = "TEXT",
                                tier = "BASIC", deprecated = true,
                            ),
                        ),
                    )
                ),
            ),
            StageDto(
                number = 5, key = "PROCESS", title = "Traditional process",
                entities = listOf(
                    EntityDto(
                        key = "tool", name = "DwTool", cardinality = "COLLECTION",
                        title = "Tools", labelField = "name",
                        fields = listOf(
                            FieldDto(key = "name", label = "Tool name", type = "TEXT", tier = "BASIC"),
                            FieldDto(key = "localName", label = "Local name", type = "TEXT", tier = "BASIC"),
                            FieldDto(key = "tags", label = "Tags", type = "TAGS", tier = "STANDARD"),
                            FieldDto(key = "photo", label = "Photograph", type = "IMAGE", tier = "STANDARD"),
                            FieldDto(
                                key = "photoCaption", label = "Photograph caption", type = "TEXT",
                                tier = "STANDARD", captionFor = "photo",
                            ),
                        ),
                    )
                ),
            ),
            StageDto(
                number = 13, key = "PROTOTYPE", title = "Prototype development",
                entities = listOf(
                    EntityDto(
                        key = "prototype", name = "DwPrototype", cardinality = "COLLECTION",
                        title = "Prototypes", labelField = "name",
                        fields = listOf(
                            FieldDto(key = "name", label = "Prototype name", type = "TEXT", tier = "BASIC")
                        ),
                    ),
                    EntityDto(
                        key = "prototypeStageLog", name = "DwPrototypeStageLog", cardinality = "COLLECTION",
                        title = "Stage log", parent = "prototype", labelField = "stageName",
                        fields = listOf(
                            FieldDto(key = "stageName", label = "Stage", type = "TEXT", tier = "BASIC"),
                            FieldDto(
                                key = "prototypeRef", label = "Prototype", type = "REF",
                                tier = "BASIC", refModel = "DwPrototype",
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun text(value: String): JsonElement = JsonPrimitive(value)

    private fun stage(stageKey: String, singleton: Map<String, JsonElement> = emptyMap(), rows: List<DraftRow> = emptyList()) =
        StageDraft(stageId = stageKey, values = singleton, rows = rows)

    private fun row(entityKey: String, rowKey: String, values: Map<String, JsonElement>) =
        DraftRow(id = dwRowId(entityKey, rowKey), values = values)

    private fun draftWith(vararg stages: StageDraft) =
        WorkshopDraft(workshopId = "dwlocal-spec", stages = stages.associateBy { it.stageId })

    /** The stored form of the two-block narrative the web spec uses. */
    private val background = buildJsonObject {
        putJsonArray("blocks") {
            addJsonObject {
                put("kind", "PARAGRAPH")
                putJsonArray("spans") {
                    addJsonObject { put("text", "The ground was left undyed rather than the usual indigo.") }
                }
            }
            addJsonObject {
                put("kind", "BULLET_ITEM")
                putJsonArray("spans") {
                    addJsonObject { put("text", "Vat dyeing is done in the shared dye house.") }
                }
            }
        }
    }

    private val draft = draftWith(
        stage(
            "SETUP",
            singleton = mapOf(
                // Diacritics a designer will not type: the fold is what makes "sambalpuri" reach it.
                "craftName" to text("Sāmbalpurī bandha"),
                "craftRef" to text("cmcraft000000000000000001"),
                "fabricFamily" to text("MUGA_TUSSAR"),
                "background" to background,
                "retiredNote" to text("This field is deprecated and no form draws it."),
            ),
        ),
        stage(
            "PROCESS",
            rows = listOf(
                row(
                    "tool", "tool-1",
                    mapOf(
                        "name" to text("Four-treadle pit loom"),
                        // "gada tanta" — the virama in ତନ୍ତ is the character this feature turns on.
                        "localName" to text("Gada tanta (ଗାଡ଼ ତନ୍ତ)"),
                        "tags" to buildJsonArray { add("Loom"); add("Pit") },
                        "photo" to text("cmmedia00000000000000001"),
                        "photoCaption" to text("Pit loom in the weaving room at Barpali."),
                    ),
                )
            ),
        ),
        stage(
            "PROTOTYPE",
            rows = listOf(
                row(
                    "prototype", "proto-1",
                    mapOf(
                        "_entryId" to text("cmproto00000000000000001"),
                        "name" to text("Phoda kumbha table runner"),
                    ),
                ),
                row(
                    "prototypeStageLog", "log-1",
                    mapOf(
                        "stageName" to text("Warping and beam dressing"),
                        "prototypeRef" to text("cmproto00000000000000001"),
                    ),
                ),
            ),
        ),
    )

    private val index = DwWorkshopSearch.buildIndex(registry, draft)

    /** Every hit for [query], so an assertion can name the one it means rather than a position. */
    private fun hitsFor(query: String): List<DwWorkshopSearchHit> =
        DwWorkshopSearch.search(index, query).hits

    private fun only(query: String): DwWorkshopSearchHit {
        val hits = hitsFor(query)
        assertEquals("\"$query\" matched exactly one field", 1, hits.size)
        return hits[0]
    }

    /** The snippet as one string, with the highlighted runs wrapped, so an assertion can read it. */
    private fun marked(hit: DwWorkshopSearchHit): String =
        hit.snippet.segments.joinToString("") { if (it.match) "[${it.text}]" else it.text }

    /* ── Unicode ──────────────────────────────────────────────────────────────────────────────── */

    @Test
    fun `an Odia word is ONE token - the virama and the matra are not word boundaries`() {
        // ଗାଡ଼ ତନ୍ତ = "gada tanta". Two words. With `[\p{L}]+` this is four fragments and the
        // assertion below is what fails first.
        assertEquals(listOf("ଗାଡ଼", "ତନ୍ତ"), DwWorkshopSearch.tokenise("ଗାଡ଼ ତନ୍ତ"))
        // ରଙ୍ଗ = "ranga", colour: one token, four code points, one of them a virama.
        assertEquals(listOf("ରଙ୍ଗ"), DwWorkshopSearch.tokenise("ରଙ୍ଗ"))
    }

    /**
     * The JVM's own character classes, pinned — the half of decision 1 the web spec cannot have.
     *
     * `\p{L}`, `\p{M}` and `\p{N}` are Unicode general categories in `java.util.regex` with no flag
     * set; `\w` and `\d` are ASCII-only without [java.util.regex.Pattern.UNICODE_CHARACTER_CLASS].
     * That asymmetry is the whole reason the tokeniser is spelled the way it is, and it is asserted
     * rather than believed: the day a platform changes it, this fails here rather than as "search
     * finds nothing in Odia" on a handset in a village.
     */
    @Test
    fun `the JVM's own character classes are Unicode-aware only where they are written that way`() {
        val tanta = "ତନ୍ତ"
        assertEquals("[\\p{L}\\p{M}]+ reads the whole word", listOf(tanta), Regex("[\\p{L}\\p{M}]+").findAll(tanta).map { it.value }.toList())
        // The defect, stated: dropping \p{M} splits on the virama, which is what shatters the word.
        assertEquals(listOf("ତନ", "ତ"), Regex("[\\p{L}]+").findAll(tanta).map { it.value }.toList())
        // And the JVM-specific one: `\w` does not see Odia at all without a flag nobody sets.
        assertEquals(emptyList<String>(), Regex("\\w+").findAll(tanta).map { it.value }.toList())
        // `\p{N}` is Unicode-aware, so an Odia numeral is a digit run like any other. ୧୬୫୦ = 1650.
        assertEquals(listOf("୧୬୫୦"), DwWorkshopSearch.tokenise("୧୬୫୦"))
    }

    @Test
    fun `an Odia query finds an Odia value, and says which field it is in`() {
        val hit = only("ତନ୍ତ")
        assertEquals(5, hit.stageNumber)
        assertEquals("Traditional process", hit.stageTitle)
        assertEquals("Tools", hit.entityTitle)
        assertEquals("Four-treadle pit loom", hit.recordTitle)
        assertEquals("Local name", hit.fieldLabel)
        // The highlight covers the WHOLE Odia word, not a fragment of it.
        assertTrue(marked(hit), marked(hit).contains("[ତନ୍ତ]"))
    }

    @Test
    fun `an Odia word that merely shares consonants is NOT a hit`() {
        // "ତନ ଓ ତାର" — "tana o tara", warp and wire. It shares the consonants ତ and ନ with ତନ୍ତ and
        // is a different phrase. With `[\p{L}]+` both sides shatter into the same fragments and this
        // returns a hit a designer cannot tell from a real one, which is the corrosive half of that
        // bug.
        val noise = draftWith(
            stage("PROCESS", rows = listOf(row("tool", "noise-1", mapOf("name" to text("ତନ ଓ ତାର")))))
        )
        val noiseIndex = DwWorkshopSearch.buildIndex(registry, noise)
        assertEquals(0, DwWorkshopSearch.search(noiseIndex, "ତନ୍ତ").hits.size)
    }

    @Test
    fun `folding never touches an Indic combining mark`() {
        // If the fold stripped category M rather than U+0300–U+036F, ରଙ୍ଗ would become ରଗ — a word
        // that exists in no language, and one no query could ever reach.
        assertEquals("ରଙ୍ଗ", DwWorkshopSearch.foldForSearch("ରଙ୍ଗ"))
        assertEquals("ଘନଶ୍ୟାମ ମେହେର", DwWorkshopSearch.foldForSearch("ଘନଶ୍ୟାମ ମେହେର"))
    }

    @Test
    fun `folding does tolerate the diacritics of transliterated Odia`() {
        assertEquals("sambalpuri", DwWorkshopSearch.foldForSearch("Sāmbalpurī"))
        // Two hits, not one: the craft NAME holds the word, and the craft REF resolves to the same
        // name through the row the picker hydrated. Both are honest answers to "where did I write
        // this".
        val hits = hitsFor("sambalpuri")
        assertEquals(listOf("Craft", "Craft name"), hits.map { it.fieldLabel }.sorted())
        assertTrue(marked(hits[0]), marked(hits[0]).contains("[Sāmbalpurī]"))
    }

    /* ── What is indexed, and how ─────────────────────────────────────────────────────────────── */

    @Test
    fun `a REF is searched by the label it resolves to, never by its cuid`() {
        // The stage log names a prototype by id. The word a designer knows is the prototype's NAME,
        // which lives on a row in a different collection of the same stage — and on this device.
        val viaRef = hitsFor("phoda kumbha").firstOrNull { it.fieldKey == "prototypeRef" }
        assertNotNull("the reference field itself is a hit for the referenced row's name", viaRef)
        assertEquals("Stage log", viaRef!!.entityTitle)
        assertEquals("Warping and beam dressing", viaRef.recordTitle)
        assertEquals("log-1", viaRef.rowKey)
        assertTrue(marked(viaRef), marked(viaRef).contains("[Phoda]"))

        // And the cuid is not searchable, because it is not text anybody wrote.
        assertEquals(0, hitsFor("cmproto00000000000000001").size)
    }

    @Test
    fun `a REF to a record outside the workshop resolves through the name already on the row`() {
        // `Craft` is not an entity of this registry — there is nothing offline to look it up in — so
        // the label comes from the hydrated display name the picker wrote onto the row.
        assertEquals(listOf("craftName", "craftRef"), hitsFor("bandha").map { it.fieldKey }.sorted())
    }

    @Test
    fun `RICH_TEXT is indexed as prose, not as its stored JSON`() {
        val hit = only("indigo")
        assertEquals("Background", hit.fieldLabel)
        assertTrue(marked(hit), marked(hit).contains("[indigo]"))
        // The document's own vocabulary must not be searchable, or every narrative in the workshop
        // matches "blocks", "spans" and "PARAGRAPH".
        assertEquals(0, hitsFor("blocks").size)
        assertEquals(0, hitsFor("paragraph").size)
        // …and a bullet is still reachable, which is what proves the second block was flattened.
        assertEquals(1, hitsFor("dye house").size)
    }

    @Test
    fun `an ENUM is searched by the label a human sees, not by the stored token`() {
        assertEquals("Fabric family", only("wild silk").fieldLabel)
        assertEquals(0, hitsFor("MUGA_TUSSAR").size)
    }

    @Test
    fun `TAGS are searched`() {
        // "Pit" is one of the tool's two tags, and it is also a word in its name and in its caption.
        // All three are hits; the tag is the one this test is about.
        assertEquals(listOf("name", "photoCaption", "tags"), hitsFor("pit").map { it.fieldKey }.sorted())
        assertEquals("Tags", hitsFor("pit").first { it.fieldKey == "tags" }.fieldLabel)
    }

    @Test
    fun `a caption is searched, and navigates to the media field it is drawn under`() {
        val hit = only("weaving room")
        assertEquals("photoCaption", hit.fieldKey)
        assertEquals("Photograph caption", hit.fieldLabel)
        // The caption input has no wrapper of its own — the stage screen hands it to the media field.
        assertEquals("photo", hit.anchorFieldKey)
    }

    @Test
    fun `a deprecated field is not indexed - there is no control to send a designer to`() {
        assertEquals(0, hitsFor("deprecated").size)
    }

    @Test
    fun `media ids and numbers are not indexed`() {
        assertEquals(0, hitsFor("cmmedia00000000000000001").size)
    }

    /* ── Querying ─────────────────────────────────────────────────────────────────────────────── */

    @Test
    fun `an empty query shows nothing, not everything`() {
        val result = DwWorkshopSearch.search(index, "")
        assertEquals(0, result.hits.size)
        assertEquals(0, result.total)
        assertEquals(DwSearchStatus.IDLE, result.status)
        // …and the index it was asked against is emphatically not empty.
        assertTrue("fieldCount ${index.fieldCount}", index.fieldCount > 5)
        assertEquals(3, index.stageCount)
    }

    @Test
    fun `a one-character query is refused rather than answered with the whole workshop`() {
        assertEquals(DwSearchStatus.TOO_SHORT, DwWorkshopSearch.search(index, "ତ").status)
    }

    @Test
    fun `a query with no letters or digits in it says so`() {
        val result = DwWorkshopSearch.search(index, "₹₹₹")
        assertEquals(DwSearchStatus.NO_TERMS, result.status)
        assertEquals(0, result.hits.size)
    }

    @Test
    fun `terms are ANDed, so typing more narrows`() {
        assertTrue(hitsFor("loom").size > 1)
        assertTrue(hitsFor("phoda").isNotEmpty())
        assertEquals(0, hitsFor("loom phoda").size)
    }

    @Test
    fun `a prefix matches, an infix does not`() {
        assertEquals(1, hitsFor("indi").size)
        // "digo" is inside "indigo". A substring search would return it and then highlight a
        // fragment of a word the designer did not type, which is not what a token index promises.
        assertEquals(0, hitsFor("digo").size)
    }

    @Test
    fun `an exact token outranks a merely-prefixed one`() {
        val hits = hitsFor("loom")
        assertTrue(hits[0].score >= hits[hits.size - 1].score)
    }

    @Test
    fun `a snippet quotes the surrounding sentence and marks only what matched`() {
        val hit = only("indigo")
        assertEquals(1, hit.snippet.segments.count { it.match })
        // The bullet that follows is carried too, and its "•" marker with it: `toPlain` keeps list
        // markers because a bulleted list flattened to bare lines reads as one run-on sentence. The
        // newline between the two blocks is collapsed to a space so a result row stays a row.
        assertEquals(
            "The ground was left undyed rather than the usual [indigo]. " +
                "• Vat dyeing is done in the shared dye house.",
            marked(hit),
        )
        assertFalse(hit.snippet.clippedStart)
        assertFalse(hit.snippet.clippedEnd)
    }

    @Test
    fun `a long value is windowed around the match and says it was clipped`() {
        val filler = "the loom was dressed and the warp was sized ".repeat(20)
        val long = draftWith(stage("SETUP", singleton = mapOf("craftName" to text("$filler indigo $filler"))))
        val hit = DwWorkshopSearch.search(DwWorkshopSearch.buildIndex(registry, long), "indigo").hits[0]
        assertTrue(hit.snippet.clippedStart)
        assertTrue(hit.snippet.clippedEnd)
        assertTrue(marked(hit), marked(hit).contains("[indigo]"))
        // Windowed, not the whole field: a result row must stay one or two lines.
        assertTrue(marked(hit).length.toString(), marked(hit).length < 300)
    }

    @Test
    fun `the cap is applied AND announced`() {
        val many = draftWith(
            stage(
                "PROCESS",
                rows = (0 until DW_MAX_HITS + 25).map { i ->
                    row("tool", "tool-$i", mapOf("name" to text("Pit loom number $i")))
                },
            )
        )
        val result = DwWorkshopSearch.search(DwWorkshopSearch.buildIndex(registry, many), "loom")
        assertEquals(DW_MAX_HITS, result.hits.size)
        assertTrue(result.truncated)
        assertEquals(DW_MAX_HITS + 25, result.total)
    }

    /* ── Getting there ────────────────────────────────────────────────────────────────────────── */

    @Test
    fun `a hit carries the stage, the field and the row it is in`() {
        // The web's half of this asserts a URL that round-trips through `readStageFocus`. There is no
        // address bar here — see [DwWorkshopSearch.focusOf] — so the assertion is on the address
        // itself, which is the thing `StageScreen` is actually handed.
        val focus = DwWorkshopSearch.focusOf(only("ତନ୍ତ"))
        assertEquals(DwStageFocus("PROCESS", "tool", "localName", "tool-1"), focus)
    }

    @Test
    fun `a singleton hit carries no row, and a caption sends the form to the media field`() {
        assertEquals(
            DwStageFocus("SETUP", "workshopSetup", "background", null),
            DwWorkshopSearch.focusOf(only("indigo")),
        )
        // The caption's own key would scroll to nothing: the box is drawn inside the media field.
        assertEquals(
            DwStageFocus("PROCESS", "tool", "photo", "tool-1"),
            DwWorkshopSearch.focusOf(only("weaving room")),
        )
    }

    /* ── The rules the web spec states in prose ───────────────────────────────────────────────── */

    @Test
    fun `a product code tokenises to its letters and its digits, and a grouped price does not join`() {
        // A designer looks for "PT-01" as often as for a word, and a letters-only tokeniser cannot
        // see it at all. The separate digit run is what makes the hyphen, the non-breaking hyphen
        // and a plain space all the same query.
        assertEquals(listOf("pt", "01"), DwWorkshopSearch.tokenise("PT-01"))
        assertEquals(listOf("pt", "01"), DwWorkshopSearch.tokenise("PT\u201101"))
        // …and the cost of that rule, stated rather than discovered: a grouping separator is a
        // boundary, so "₹1,650" is two tokens and "1650" does not find it.
        assertEquals(listOf("1", "650"), DwWorkshopSearch.tokenise("₹1,650"))
    }

    @Test
    fun `a no-break space is trimmed off a query the way the browser trims it`() {
        // `String.trim()` is `Character.isWhitespace`, which is FALSE for U+00A0 — the character a
        // value pasted out of a spreadsheet carries. Left attached, this query is three characters of
        // which one is punctuation, and the box would answer a search nobody typed.
        assertEquals(DwSearchStatus.IDLE, DwWorkshopSearch.search(index, "\u00A0\u00A0").status)
        assertEquals(DwSearchStatus.SEARCHED, DwWorkshopSearch.search(index, "\u00A0indigo\u00A0").status)
        assertEquals(1, DwWorkshopSearch.search(index, "\u00A0indigo\u00A0").hits.size)
    }

    @Test
    fun `an empty draft indexes to nothing rather than failing`() {
        val empty = DwWorkshopSearch.buildIndex(registry, null)
        assertEquals(0, empty.fieldCount)
        assertEquals(0, empty.stageCount)
        assertEquals(DwSearchStatus.SEARCHED, DwWorkshopSearch.search(empty, "indigo").status)
        assertEquals(0, DwWorkshopSearch.search(empty, "indigo").total)
    }
}
