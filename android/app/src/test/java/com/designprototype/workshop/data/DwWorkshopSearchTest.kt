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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DwWorkshopSearchTest {

    private val registry = SchemaResponse(
        version = "spec",
        enums = mapOf(
            "fabricFamily" to listOf(
                EnumOption(value = "COTTON", label = "Cotton"),
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

    private fun hitsFor(query: String): List<DwWorkshopSearchHit> =
        DwWorkshopSearch.search(index, query).hits

    private fun only(query: String): DwWorkshopSearchHit {
        val hits = hitsFor(query)
        assertEquals("\"$query\" matched exactly one field", 1, hits.size)
        return hits[0]
    }

    private fun marked(hit: DwWorkshopSearchHit): String =
        hit.snippet.segments.joinToString("") { if (it.match) "[${it.text}]" else it.text }

    private fun onlyHitOver(value: String, query: String): DwWorkshopSearchHit {
        val one = draftWith(stage("SETUP", singleton = mapOf("craftName" to text(value))))
        val hits = DwWorkshopSearch.search(DwWorkshopSearch.buildIndex(registry, one), query).hits
        assertEquals("\"$query\" matched exactly one field of \"$value\"", 1, hits.size)
        return hits[0]
    }

    @Test
    fun `an Odia word is ONE token - the virama and the matra are not word boundaries`() {
        assertEquals(listOf("ଗାଡ଼", "ତନ୍ତ"), DwWorkshopSearch.tokenise("ଗାଡ଼ ତନ୍ତ"))
        assertEquals(listOf("ରଙ୍ଗ"), DwWorkshopSearch.tokenise("ରଙ୍ଗ"))
    }

    @Test
    fun `the JVM's own character classes are Unicode-aware only where they are written that way`() {
        val tanta = "ତନ୍ତ"
        assertEquals("[\\p{L}\\p{M}]+ reads the whole word", listOf(tanta), Regex("[\\p{L}\\p{M}]+").findAll(tanta).map { it.value }.toList())
        assertEquals(listOf("ତନ", "ତ"), Regex("[\\p{L}]+").findAll(tanta).map { it.value }.toList())
        assertEquals(emptyList<String>(), Regex("\\w+").findAll(tanta).map { it.value }.toList())
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
        assertTrue(marked(hit), marked(hit).contains("[ତନ୍ତ]"))
    }

    @Test
    fun `an Odia word that merely shares consonants is NOT a hit`() {
        val noise = draftWith(
            stage("PROCESS", rows = listOf(row("tool", "noise-1", mapOf("name" to text("ତନ ଓ ତାର")))))
        )
        val noiseIndex = DwWorkshopSearch.buildIndex(registry, noise)
        assertEquals(0, DwWorkshopSearch.search(noiseIndex, "ତନ୍ତ").hits.size)
    }

    @Test
    fun `folding never touches an Indic combining mark`() {
        assertEquals("ରଙ୍ଗ", DwWorkshopSearch.foldForSearch("ରଙ୍ଗ"))
        assertEquals("ଘନଶ୍ୟାମ ମେହେର", DwWorkshopSearch.foldForSearch("ଘନଶ୍ୟାମ ମେହେର"))
    }

    @Test
    fun `an Odia letter spelled the two ways a keyboard spells it is ONE word to this box`() {
        val precomposed = "\u0B17\u0B3E\u0B5C"
        val decomposed = "\u0B17\u0B3E\u0B21\u0B3C"
        assertFalse("this test means nothing if the two spellings are one string", precomposed == decomposed)
        assertEquals(
            DwWorkshopSearch.foldForSearch(decomposed),
            DwWorkshopSearch.foldForSearch(precomposed),
        )
        assertEquals(DwWorkshopSearch.tokenise(decomposed), DwWorkshopSearch.tokenise(precomposed))

        assertNotEquals(
            DwWorkshopSearch.foldForSearch("\u0B17\u0B3E\u0B21"),
            DwWorkshopSearch.foldForSearch(precomposed),
        )
    }

    @Test
    fun `a value typed with one spelling of a nukta letter is found by a query typed with the other`() {
        val hit = onlyHitOver("\u0B17\u0B3E\u0B5C \u0B24\u0B28\u0B4D\u0B24", "\u0B17\u0B3E\u0B21\u0B3C")
        assertEquals("[\u0B17\u0B3E\u0B5C] \u0B24\u0B28\u0B4D\u0B24", marked(hit))
    }

    @Test
    fun `a nukta letter earlier in the value does not slide the highlight off a later word`() {
        val hit = onlyHitOver("\u0B17\u0B3E\u0B5C \u0B24\u0B28\u0B4D\u0B24 loom", "loom")
        assertEquals("\u0B17\u0B3E\u0B5C \u0B24\u0B28\u0B4D\u0B24 [loom]", marked(hit))
    }

    @Test
    fun `a query that mixes a transliteration and the script itself ANDs across both`() {
        val hit = only("gada ତନ୍ତ")
        assertEquals("localName", hit.fieldKey)
        assertEquals(
            "[Gada] tanta (ଗାଡ଼ [ତନ୍ତ])",
            marked(hit),
        )
    }

    @Test
    fun `folding does tolerate the diacritics of transliterated Odia`() {
        assertEquals("sambalpuri", DwWorkshopSearch.foldForSearch("Sāmbalpurī"))
        val hits = hitsFor("sambalpuri")
        assertEquals(listOf("Craft", "Craft name"), hits.map { it.fieldLabel }.sorted())
        assertTrue(marked(hits[0]), marked(hits[0]).contains("[Sāmbalpurī]"))
    }

    @Test
    fun `the fold follows Locale ROOT and not the handset's locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("indigo", DwWorkshopSearch.foldForSearch("INDIGO"))
            assertEquals("Background", only("INDIGO").fieldLabel)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `a character that folds to two units still highlights the one character it came from`() {
        val hit = onlyHitOver("Sāmbalpurī ﬁlature reeling", "filature")
        assertEquals("Sāmbalpurī [ﬁlature] reeling", marked(hit))
    }

    @Test
    fun `an astral character does not slide the highlight off the word after it`() {
        val hit = onlyHitOver("Bandha 🧵 ତନ୍ତ loom", "loom")
        assertEquals("Bandha 🧵 ତନ୍ତ [loom]", marked(hit))
    }

    @Test
    fun `a snippet collapses the whitespace JavaScript calls whitespace, not the six Java does`() {
        val hit = onlyHitOver("Bandha\u00A0indigo\u2028dyed", "indigo")
        assertEquals("Bandha [indigo] dyed", marked(hit))
        assertFalse(hit.snippet.clippedStart)
        assertFalse(hit.snippet.clippedEnd)
    }

    @Test
    fun `a REF is searched by the label it resolves to, never by its cuid`() {
        val viaRef = hitsFor("phoda kumbha").firstOrNull { it.fieldKey == "prototypeRef" }
        assertNotNull("the reference field itself is a hit for the referenced row's name", viaRef)
        assertEquals("Stage log", viaRef!!.entityTitle)
        assertEquals("Warping and beam dressing", viaRef.recordTitle)
        assertEquals("log-1", viaRef.rowKey)
        assertTrue(marked(viaRef), marked(viaRef).contains("[Phoda]"))

        assertEquals(0, hitsFor("cmproto00000000000000001").size)
    }

    @Test
    fun `a REF to a record outside the workshop resolves through the name already on the row`() {
        assertEquals(listOf("craftName", "craftRef"), hitsFor("bandha").map { it.fieldKey }.sorted())
    }

    @Test
    fun `RICH_TEXT is indexed as prose, not as its stored JSON`() {
        val hit = only("indigo")
        assertEquals("Background", hit.fieldLabel)
        assertTrue(marked(hit), marked(hit).contains("[indigo]"))
        assertEquals(0, hitsFor("blocks").size)
        assertEquals(0, hitsFor("paragraph").size)
        assertEquals(1, hitsFor("dye house").size)
    }

    @Test
    fun `an ENUM is searched by the label a human sees, not by the stored token`() {
        assertEquals("Fabric family", only("wild silk").fieldLabel)
        assertEquals(0, hitsFor("MUGA_TUSSAR").size)
    }

    @Test
    fun `TAGS are searched`() {
        assertEquals(listOf("name", "photoCaption", "tags"), hitsFor("pit").map { it.fieldKey }.sorted())
        assertEquals("Tags", hitsFor("pit").first { it.fieldKey == "tags" }.fieldLabel)
    }

    @Test
    fun `a caption is searched, and navigates to the media field it is drawn under`() {
        val hit = only("weaving room")
        assertEquals("photoCaption", hit.fieldKey)
        assertEquals("Photograph caption", hit.fieldLabel)
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

    @Test
    fun `an empty query shows nothing, not everything`() {
        val result = DwWorkshopSearch.search(index, "")
        assertEquals(0, result.hits.size)
        assertEquals(0, result.total)
        assertEquals(DwSearchStatus.IDLE, result.status)
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

    @Test
    fun `a hit carries the stage, the field and the row it is in`() {
        val focus = DwWorkshopSearch.focusOf(only("ତନ୍ତ"))
        assertEquals(DwStageFocus("PROCESS", "tool", "localName", "tool-1"), focus)
    }

    @Test
    fun `a singleton hit carries no row, and a caption sends the form to the media field`() {
        assertEquals(
            DwStageFocus("SETUP", "workshopSetup", "background", null),
            DwWorkshopSearch.focusOf(only("indigo")),
        )
        assertEquals(
            DwStageFocus("PROCESS", "tool", "photo", "tool-1"),
            DwWorkshopSearch.focusOf(only("weaving room")),
        )
    }

    @Test
    fun `a product code tokenises to its letters and its digits, and a grouped price does not join`() {
        assertEquals(listOf("pt", "01"), DwWorkshopSearch.tokenise("PT-01"))
        assertEquals(listOf("pt", "01"), DwWorkshopSearch.tokenise("PT\u201101"))
        assertEquals(listOf("1", "650"), DwWorkshopSearch.tokenise("₹1,650"))
    }

    @Test
    fun `a no-break space is trimmed off a query the way the browser trims it`() {
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
