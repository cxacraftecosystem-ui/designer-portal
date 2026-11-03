package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwPhotoMeasure
import com.designprototype.workshop.data.DwRoundedValue
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.entity
import com.designprototype.workshop.data.liveFields
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DwPhotoMeasureFieldTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val schema: SchemaResponse by lazy {
        val asset = File("src/main/assets/design-workshop-schema.json")
        assertTrue(
            "the bundled registry is missing — it is what the handset renders from on first launch",
            asset.exists()
        )
        json.decodeFromString(SchemaResponse.serializer(), asset.readText(Charsets.UTF_8))
    }

    private fun entityOf(stageKey: String, entityKey: String): EntityDto =
        schema.stages.firstOrNull { it.key == stageKey }?.entity(entityKey)
            ?: throw AssertionError("the registry declares no `$stageKey`.`$entityKey`")

    private fun siblingsOf(stageKey: String, entityKey: String): Map<String, FieldDto> =
        entityOf(stageKey, entityKey).liveFields.associateBy { it.key }

    @Test
    fun `every entity that describes a photographed object offers the measurement`() {
        val expected = mapOf(
            ("EXISTING_PRODUCTS_BASELINE" to "existingProduct") to listOf("lengthCm", "widthCm", "heightCm"),
            ("SKETCH_DEVELOPMENT" to "sketch") to listOf("lengthCm", "widthCm", "heightCm"),
            ("PROTOTYPE_DEVELOPMENT" to "prototype") to
                listOf("lengthCm", "widthCm", "heightCm", "diameterCm"),
            ("FINAL_PROTOTYPE_DOCUMENTATION" to "finalProduct") to listOf("lengthCm", "widthCm", "heightCm"),
        )
        for ((location, keys) in expected) {
            val (stageKey, entityKey) = location
            val siblings = siblingsOf(stageKey, entityKey)
            assertEquals(
                "`$stageKey`.`$entityKey` no longer proposes into the dimensions the registry declares",
                keys,
                dwMeasurableLengthFields(siblings).map { it.field.key },
            )
        }
    }

    @Test
    fun `the offer lands on the image fields and nowhere else`() {
        val siblings = siblingsOf("PROTOTYPE_DEVELOPMENT", "prototype")
        val offered = siblings.values.filter { dwOffersPhotoMeasure(it, siblings) }.map { it.key }
        assertEquals(listOf("prototypePhotos", "turntablePhotos"), offered)

        assertFalse(dwOffersPhotoMeasure(siblings.getValue("lengthCm"), siblings))
    }

    @Test
    fun `photographs alone are not enough`() {
        val siblings = mapOf(
            "photos" to FieldDto(key = "photos", label = "Photographs", type = "IMAGE_LIST"),
            "note" to FieldDto(key = "note", label = "Note", type = "LONG_TEXT"),
        )
        assertTrue(dwMeasurableLengthFields(siblings).isEmpty())
        assertFalse(dwOffersPhotoMeasure(siblings.getValue("photos"), siblings))
    }

    @Test
    fun `weights prices counts and unknown units are never destinations`() {
        val siblings = listOf(
            FieldDto(key = "weightG", label = "Weight", type = "DECIMAL", unit = "g"),
            FieldDto(key = "durationDays", label = "Duration", type = "INT", unit = "days"),
            FieldDto(key = "price", label = "Price", type = "MONEY", unit = "cm"),
            FieldDto(key = "share", label = "Share", type = "PERCENT", unit = "cm"),
            FieldDto(key = "span", label = "Span", type = "DECIMAL", unit = "hands"),
            FieldDto(key = "retired", label = "Old length", type = "DECIMAL", unit = "cm", deprecated = true),
            FieldDto(key = "caption", label = "Caption", type = "TEXT", unit = "cm"),
        ).associateBy { it.key }
        assertEquals(emptyList<String>(), dwMeasurableLengthFields(siblings).map { it.field.key })
    }

    @Test
    fun `the declared unit is read case-folded and trimmed`() {
        val siblings = listOf(
            FieldDto(key = "a", label = "A", type = "DECIMAL", unit = " CM "),
            FieldDto(key = "b", label = "B", type = "INT", unit = "MM"),
        ).associateBy { it.key }
        assertEquals(listOf("cm", "mm"), dwMeasurableLengthFields(siblings).map { it.unit })
    }

    @Test
    fun `every offered destination is a unit the module can convert`() {
        val entities = listOf(
            "EXISTING_PRODUCTS_BASELINE" to "existingProduct",
            "SKETCH_DEVELOPMENT" to "sketch",
            "PROTOTYPE_DEVELOPMENT" to "prototype",
            "FINAL_PROTOTYPE_DOCUMENTATION" to "finalProduct",
        )
        for ((stageKey, entityKey) in entities) {
            for (target in dwMeasurableLengthFields(siblingsOf(stageKey, entityKey))) {
                assertTrue(
                    "`${target.field.key}` is offered in ${target.unit}, which the module cannot convert",
                    DwPhotoMeasure.LENGTH_UNITS.containsKey(target.unit)
                )
                assertEquals(
                    1.0,
                    DwPhotoMeasure.convertLength(1.0, target.unit, target.unit) ?: 0.0,
                    0.0,
                )
            }
        }
    }

    @Test
    fun `a proposal is written to the decimals its error bar supports`() {
        assertEquals("20.0", dwFormatRounded(DwRoundedValue(20.0, 1)))
        assertEquals("12", dwFormatRounded(DwRoundedValue(12.0, 0)))
        assertEquals("0.0335", dwFormatRounded(DwRoundedValue(0.0335, 4)))
        val rounded = DwPhotoMeasure.roundToUncertainty(19.98471, 0.3)
        assertEquals(1, rounded.decimals)
        assertEquals("20.0", dwFormatRounded(rounded))
    }

    @Test
    fun `the collapsed summary names the marks the reference and the method`() {
        assertEquals(
            "4 of 4 marks placed · 120 mm reference · same-plane method",
            dwMeasureSummary(
                marksPlaced = 4,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "120",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = null,
            )
        )
    }

    @Test
    fun `the four corner method is counted out of six and names its rectangle`() {
        assertEquals(
            "4 of 6 marks placed · 210 × 297 mm rectangle · four-corner method",
            dwMeasureSummary(
                marksPlaced = 4,
                marksNeeded = 6,
                fourCorner = true,
                referenceLength = "",
                referenceUnit = "mm",
                rectWidth = "210",
                rectHeight = "297",
                rectUnit = "mm",
                photographName = null,
            )
        )
    }

    @Test
    fun `an untouched card is not described as a configuration`() {
        assertNull(
            dwMeasureSummary(
                marksPlaced = 0,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = null,
            )
        )
        assertNull(
            dwMeasureSummary(
                marksPlaced = 0,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "   ",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = null,
            )
        )
    }

    @Test
    fun `a half configured card says which half is still missing`() {
        assertEquals(
            "4 of 4 marks placed · no reference length yet · same-plane method",
            dwMeasureSummary(
                marksPlaced = 4,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = null,
            )
        )
        assertEquals(
            "no marks placed yet · 300 mm reference · same-plane method",
            dwMeasureSummary(
                marksPlaced = 0,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "300",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = null,
            )
        )
        assertEquals(
            "2 of 6 marks placed · no rectangle size yet · four-corner method",
            dwMeasureSummary(
                marksPlaced = 2,
                marksNeeded = 6,
                fourCorner = true,
                referenceLength = "",
                referenceUnit = "mm",
                rectWidth = "210",
                rectHeight = "",
                rectUnit = "mm",
                photographName = null,
            )
        )
    }

    @Test
    fun `the chosen photograph is named only where there was a choice`() {
        assertEquals(
            "4 of 4 marks placed · 120 mm reference · same-plane method · photograph “IMG_0042.jpg”",
            dwMeasureSummary(
                marksPlaced = 4,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "120",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = "IMG_0042.jpg",
            )
        )
        assertEquals(
            "4 of 4 marks placed · 120 mm reference · same-plane method",
            dwMeasureSummary(
                marksPlaced = 4,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "120",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = null,
            )
        )
        assertEquals(
            "4 of 4 marks placed · 120 mm reference · same-plane method",
            dwMeasureSummary(
                marksPlaced = 4,
                marksNeeded = 4,
                fourCorner = false,
                referenceLength = "120",
                referenceUnit = "mm",
                rectWidth = "",
                rectHeight = "",
                rectUnit = "mm",
                photographName = "   ",
            )
        )
    }

    private fun panelSource(): String = sourceOf("DwPhotoMeasureField.kt")

    private fun sourceOf(name: String): String {
        val file = File("src/main/java/com/designprototype/workshop/ui/designworkshop/$name")
        assertTrue("$name is missing — a rename must move this guard", file.exists())
        return file.readText(Charsets.UTF_8)
    }

    private fun panelCode(): String = panelSource()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    @Test
    fun `collapsing and reopening cannot clear the marks`() {
        val code = panelCode()

        assertTrue(
            "the configuration must be held by DwPhotoMeasurePanel, which stays composed across a " +
                "collapse — a holder created inside the open half is destroyed by the collapse it " +
                "is supposed to survive",
            code.contains("val config = remember { DwMeasureConfig(")
        )
        assertTrue(
            "the open half must be handed the configuration rather than owning it",
            code.contains("config: DwMeasureConfig")
        )

        listOf(
            "val marks = remember",
            "var active by remember",
            "var mode by remember",
            "var photoId by remember",
            "var referenceLength by remember",
            "var referenceUnit by remember",
            "var rectWidth by remember",
            "var rectHeight by remember",
            "var rectUnit by remember",
        ).forEach { token ->
            assertFalse(
                "`$token` is back inside DwPhotoMeasureOpen. The open half is removed from the " +
                    "composition by a collapse, so anything remembered there is thrown away with it " +
                    "— and a collapse is only cheap if nothing is lost. See DwMeasureConfig.",
                code.contains(token)
            )
        }

        assertEquals(
            "there must be exactly one `marks.clear()` in this file, inside DwMeasureConfig, guarded " +
                "by a change of photograph. A second one — in particular back inside the decode " +
                "LaunchedEffect, which re-runs on every expansion — wipes the designer's work every " +
                "time they reopen the card.",
            1,
            code.split("marks.clear()").size - 1,
        )
        val guard = code.substringAfter("fun usePhotograph(").substringBefore("marks.clear()")
        assertTrue(
            "the surviving `marks.clear()` must sit behind the `marksPhotoId` comparison — that " +
                "comparison is the only thing distinguishing a change of photograph (which must " +
                "clear) from a re-entry into the composition (which must not)",
            guard.contains("if (id == marksPhotoId) return")
        )
        val decode = code.substringAfter("LaunchedEffect(photo.id) {").substringBefore("image = null")
        assertTrue(
            "the decode effect must route the photograph through DwMeasureConfig.usePhotograph, " +
                "which is where the guard lives",
            decode.contains("config.usePhotograph(photo.id)")
        )
    }

    @Test
    fun `the expanded card has a collapse control at the head and at the foot`() {
        val code = panelCode()

        assertTrue(
            "the header's collapse control is missing — the existing door must be kept, not replaced",
            code.contains("DwPanelCollapseButton(prominent = false")
        )
        assertTrue(
            "the foot's collapse control is missing — without it the only way out is back at the top",
            code.contains("DwPanelCollapseButton(prominent = true")
        )
        assertTrue(
            "every door must build its label from DW_PANEL_COLLAPSE_WORD, so none can be relabelled " +
                "alone — the difference between the two is whether the card is NAMED, never the verb",
            code.contains("if (prominent) \"\$DW_PANEL_COLLAPSE_WORD “\$title”\" else DW_PANEL_COLLAPSE_WORD")
        )
        assertEquals(
            "each foot door must pass the SAME title constant its own header passes; a literal here " +
                "is a second spelling of a card's name, which is the defect the titles were just " +
                "unified to remove",
            listOf(
                "DwPanelCollapseButton(prominent = true, title = DW_MEASURE_CARD_TITLE",
                "DwPanelCollapseButton(prominent = true, title = DW_TRACE_CARD_TITLE",
                "DwPanelCollapseButton(prominent = true, title = DW_RECTIFY_CARD_TITLE",
            ),
            listOf(
                "DwPhotoMeasureField.kt" to "DW_MEASURE_CARD_TITLE",
                "DwSketchTracePanel.kt" to "DW_TRACE_CARD_TITLE",
                "DwSketchRectifyField.kt" to "DW_RECTIFY_CARD_TITLE",
            ).map { (file, constant) ->
                val call = "DwPanelCollapseButton(prominent = true, title = $constant"
                if (sourceOf(file).contains(call)) call else "$file does not carry `$call`"
            },
        )
        assertEquals(
            "the collapse control must be ONE composable used by all three derivation cards; a " +
                "second definition is a second place for the wording, the icon size and the 48dp " +
                "floor to drift — which is exactly what the tracing and straightening panels' " +
                "hand-rolled copies had done (16dp against this one's 14dp)",
            1,
            code.split("internal fun DwPanelCollapseButton(").size - 1,
        )

        val readout = code.indexOf("DwMeasurementReadout(")
        val foot = code.indexOf("DwPanelCollapseButton(prominent = true")
        assertTrue("DwMeasurementReadout is no longer composed by the open card", readout > 0)
        assertTrue(
            "the foot's collapse control must come AFTER the readout and its propose buttons — that " +
                "is the point at which the designer is finished, and it is the point the report says " +
                "they were scrolling away from",
            foot > readout
        )
    }

    @Test
    fun `the header announces expanded and collapsed and the viewport reserves nothing`() {
        val code = panelCode()

        assertTrue(
            "the header row must be clickable with a label saying what the press does",
            code.contains("onClickLabel = if (expanded)")
        )
        assertTrue(
            "the header row must carry Role.Button — a tappable row is not announced as a control",
            code.contains("role = Role.Button")
        )
        assertTrue(
            "the header row must expose its own state, or a reader who cannot see the chevron has " +
                "no way to know whether the card is open",
            code.contains("stateDescription = if (expanded) \"Expanded\" else \"Collapsed\"")
        )
        assertTrue(
            "the chevron must still turn, and must still collapse to a snap under reducedMotion",
            code.contains(".rotate(turn)") && code.contains("if (reduceMotion) snap()")
        )

        assertFalse(
            "the photograph's viewport is a fixed 300dp again. That literal is the empty space in " +
                "the report, twice over: it reserved a third of the screen before the decode landed, " +
                "and it letterboxed every landscape photograph inside a shape that was not its own.",
            code.contains(".height(300.dp)")
        )
        assertTrue(
            "the viewport must take the working copy's own aspect ratio once there is one",
            code.contains("Modifier.aspectRatio(viewportRatio)")
        )
        assertTrue(
            "and must wrap its contents, over a floor, while there is nothing to show",
            code.contains("Modifier.heightIn(min = EMPTY_VIEWPORT_MIN_HEIGHT)")
        )
    }

    @Test
    fun `the shared choice lands in the half that is composed in both states`() {
        val code = panelCode()

        val panel = code.substringAfter("internal fun DwPhotoMeasurePanel(")
            .substringBefore("internal fun DwPanelDisclosureHeader(")
        assertTrue(
            "the shared choice must be read and applied in DwPhotoMeasurePanel, not in the open " +
                "half — a card that only noticed on re-expansion comes back holding marks placed " +
                "on a photograph that is no longer the subject",
            panel.contains("config.followShared(sharedId)")
        )
        assertTrue(
            "the supply must be the three-valued DwSketchPhotographSupply, defaulted to OwnChoice " +
                "so both of today's mounts are unchanged",
            panel.contains("supply: DwSketchPhotographSupply = DwSketchPhotographSupply.OwnChoice")
        )

        val follow = code.substringAfter("fun followShared(").substringBefore("fun measureInstead(")
        assertTrue(
            "followShared must decline while an override is in force, or choosing a different " +
                "photograph to measure would be undone by the next shared change",
            follow.contains("if (substituteId.isNotBlank()) return")
        )
    }

    @Test
    fun `the different-photograph control is named the web's name and shows itself when in force`() {
        val code = panelCode()

        assertTrue(
            "the escape hatch must be composed when a host owns the choice",
            code.contains("DwMeasureDifferentPhotograph(")
        )
        assertTrue(
            "the control must be named by the shared constant, which is the web's own label",
            code.contains("DW_MEASURE_DIFFERENT_PHOTOGRAPH")
        )
        assertTrue(
            "the way back must be named by the shared constant too",
            code.contains("DW_MEASURE_BACK_TO_SHARED")
        )

        val hatch = code.substringAfter("private fun DwMeasureDifferentPhotograph(")
        assertTrue(
            "an override in force must not be folded away — the other client's stated rule, and it " +
                "reaches further here because this card can be collapsed as well as folded",
            hatch.contains("val showing = open || inForce")
        )
        assertTrue(
            "the collapsed card must report an override in force; otherwise the one place a " +
                "designer can see that the two cards disagree is behind two presses",
            code.contains("DW_MEASURE_ELSEWHERE_TITLE")
        )
    }

    @Test
    fun `the measuring card opens no file dialog of its own`() {
        val code = panelCode()
        assertFalse(
            "a launcher has appeared in the measuring card. Photographs reach this panel by being " +
                "imported first; a picker here would be a second import route into a feature whose " +
                "premise is that there is exactly one.",
            code.contains("rememberLauncherForActivityResult") || code.contains("ActivityResultContracts")
        )
    }

    @Test
    fun `all three cards warn about replacing in the same words`() {
        assertNull(
            "nothing there is the absence of a warning, not a quieter one — the same contract the " +
                "two collapsed summaries hold, so no caller can render an empty warning box",
            dwPanelReplaceWarning("", DwPanelHolds.FILE)
        )
        assertNull(
            "a resolver can hand back whitespace; that is still nothing there",
            dwPanelReplaceWarning("   ", DwPanelHolds.VALUE)
        )
        assertNull("a missing value is nothing there", dwPanelReplaceWarning(null, DwPanelHolds.FILE))

        assertEquals(
            "“sheet-3.svg” is attached here now. This replaces it.",
            dwPanelReplaceWarning("sheet-3.svg", DwPanelHolds.FILE)
        )
        assertEquals(
            "a number is not attached to anything — the noun is the one thing that changes",
            "“24.0” is in this field now. This replaces it.",
            dwPanelReplaceWarning("24.0", DwPanelHolds.VALUE)
        )

        listOf(
            dwPanelReplaceWarning("sheet-3.svg", DwPanelHolds.FILE),
            dwPanelReplaceWarning("24.0", DwPanelHolds.VALUE),
        ).forEach { sentence ->
            assertTrue(
                "the thing about to be lost must open the sentence: $sentence",
                sentence!!.startsWith("“")
            )
            assertTrue(
                "and every card must end on the same three words: $sentence",
                sentence.endsWith("This replaces it.")
            )
        }
    }

    @Test
    fun `no derivation card writes its own replace warning`() {
        val cards = listOf(
            "DwPhotoMeasureField.kt",
            "DwSketchTracePanel.kt",
            "DwSketchRectifyField.kt",
        )

        var written = 0
        cards.forEach { name ->
            val code = sourceOf(name)
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
                .replace(Regex("""//[^\n]*"""), " ")
            assertTrue(
                "$name must reach the warning through dwPanelReplaceWarning rather than a literal",
                code.contains("dwPanelReplaceWarning(")
            )
            assertFalse(
                "$name still spells the measuring card's old reversed wording, which put the thing " +
                    "about to be lost at the END of the sentence",
                code.contains("Currently “")
            )
            written += Regex("""replaces it\.""").findAll(code).count()
        }

        assertEquals(
            "the replace warning must be written exactly once across the three cards — the " +
                "declaration in DwPhotoMeasureField.kt. A second occurrence is a card that has " +
                "grown its own copy, which is the drift dwPanelReplaceWarning exists to end.",
            1,
            written
        )
    }

    @Test
    fun `every chooser chip announces which one is in force`() {
        val code = panelCode()
        val chip = code.substringAfter("internal fun DwPanelChip(").substringBefore("\n}")

        assertTrue(
            "DwPanelChip must announce its state, not only fill it — the same grammar " +
                "DwPanelDisclosureHeader uses for Expanded/Collapsed",
            chip.contains("stateDescription")
        )
        assertTrue(
            "the announcement must read off the same parameter the fill does, or the two can " +
                "disagree about which chip is current",
            chip.contains("if (selected) \"Selected\" else \"Not selected\"")
        )
        assertTrue(
            "a chip that ACTS rather than chooses must be able to opt out of the announcement",
            chip.contains("isChoice")
        )

        assertEquals(
            "both preset rows must opt out of the selected-state announcement",
            2,
            Regex("""isChoice = false""").findAll(code).count()
        )
        assertEquals(
            "and nothing else may: every other chip in this file is one of a set",
            2,
            Regex("""selected = false""").findAll(code).count()
        )
    }

    @Test
    fun `the measuring card names the image fields it merged`() {
        val clause = dwMeasureSpansFieldsClause(listOf("Prototype photographs", "360° capture"))
        assertTrue(
            "both field names must appear, or the sentence describes a merge without naming it",
            clause.contains("“Prototype photographs”") && clause.contains("“360° capture”"),
        )
        assertTrue(
            "joined with AND: this sentence is about where the photographs on screen ALREADY are, " +
                "which is both fields at once. The other client's `fieldsPhrase` joins with OR " +
                "because it names one DESTINATION to attach to — same punctuation, opposite meaning",
            clause.contains("“Prototype photographs” and “360° capture”"),
        )
        assertTrue(
            "and it must say what the merge is FOR, in the designer's terms",
            clause.contains("wherever it was attached"),
        )
    }

    @Test
    fun `the merge sentence is silent where one field feeds the card`() {
        assertEquals("", dwMeasureSpansFieldsClause(emptyList()))
        assertEquals("", dwMeasureSpansFieldsClause(listOf("Sketch image")))
        assertEquals(
            "a blank label is not a field name and must not become one half of a pair",
            "",
            dwMeasureSpansFieldsClause(listOf("Sketch image", "   ")),
        )
    }
}
