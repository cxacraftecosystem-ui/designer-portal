package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwEncodeResult
import com.designprototype.workshop.data.DwReferenceList
import com.designprototype.workshop.data.DwReferenceOption
import com.designprototype.workshop.data.DwReferenceResponseDto
import com.designprototype.workshop.data.DwWorkshopCodeRef
import com.designprototype.workshop.data.DwWorkshopRecordType
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.encodeWorkshopCode
import com.designprototype.workshop.data.unresolvedWorkshopCodeMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwReferenceScanTest {

    private fun refField(key: String, label: String, model: String, filterBy: String = "") =
        FieldDto(key = key, label = label, type = "REF", refModel = model, refFilterBy = filterBy)

    private val artisanBox = refField("artisanRef", "Artisan", "Artisan")
    private val productBox = refField("productRef", "Documented product", "ProductDocumentation", "artisanRef")
    private val toolBox = refField("toolRef", "Documented tool", "ToolDocumentation")
    private val prototypeBox = refField("prototypeRef", "Prototype", "DwPrototype")
    private val sketchBox = refField("sketchRef", "From sketch", "DwSketch")

    private fun codeFor(type: DwWorkshopRecordType, id: String): String =
        when (val encoded = encodeWorkshopCode(type, id)) {
            is DwEncodeResult.Ok -> encoded.code
            is DwEncodeResult.Refused ->
                throw AssertionError("the test's own fixture is not encodable: ${encoded.message}")
        }

    private fun option(id: String, label: String = "Latha Devi", filterValue: String = "") =
        DwReferenceOption(
            id = id,
            label = label,
            hint = "Barpali",
            filterValue = filterValue,
            data = buildJsonObject { put("name", JsonPrimitive(label)) },
        )

    private fun cachedList(vararg options: DwReferenceOption, filteredBy: String = "") =
        DwReferenceList(
            model = "Artisan",
            filteredBy = filteredBy,
            items = options.toList(),
            fetchedAt = "",
        )

    private fun refusalOf(step: DwScanStep): String {
        val settled = step as? DwScanStep.Settled
            ?: throw AssertionError("expected a settled answer, got $step")
        val refused = settled.answer as? DwScanAnswer.Refused
            ?: throw AssertionError("expected a refusal, got ${settled.answer}")
        return refused.message
    }

    private fun pickOf(step: DwScanStep): DwReferenceOption {
        val settled = step as? DwScanStep.Settled
            ?: throw AssertionError("expected a settled answer, got $step")
        val pick = settled.answer as? DwScanAnswer.Pick
            ?: throw AssertionError("expected a pick, got ${settled.answer}")
        return pick.option
    }

    private fun messageOf(answer: DwScanAnswer): String =
        (answer as? DwScanAnswer.Refused)?.message
            ?: throw AssertionError("expected a refusal, got $answer")

    @Test
    fun `every reference model the server can serve has a card`() {
        listOf("Artisan", "Craft", "ProductDocumentation", "ToolDocumentation", "Process").forEach {
            assertNotNull("no card reader offered on a picker for $it", dwScannableRecordType(it))
        }
        assertEquals(DwWorkshopRecordType.PROTOTYPE, dwScannableRecordType("DwPrototype"))
    }

    @Test
    fun `a picker for a model nothing prints a card for offers no reader`() {
        listOf("DwSketch", "DwParticipant", "DwCostSheet", "DwFinalProduct").forEach {
            assertNull("a card reader was offered on a picker for $it", dwScannableRecordType(it))
        }
        assertNull(dwScannableRecordType("Workshop"))
    }

    @Test
    fun `a tool tag read into an artisan picker is refused, and the sentence names both and the box`() {
        val message = refusalOf(
            dwScanLocalStep(
                scanned = codeFor(DwWorkshopRecordType.TOOL, "ctool0000000000000000001"),
                field = artisanBox,
                list = cachedList(option("cart0000000000000000001")),
                parentId = "",
            )
        )
        assertTrue("the sentence does not say what was read: $message", message.contains("a tool"))
        assertTrue("the sentence does not say what is wanted: $message", message.contains("an artisan"))
        assertTrue("the sentence does not name the box: $message", message.contains("“Artisan”"))
    }

    @Test
    fun `the wrong-kind refusal needs no list, no parent and no server`() {
        val message = refusalOf(
            dwScanLocalStep(
                scanned = codeFor(DwWorkshopRecordType.ARTISAN, "cart0000000000000000001"),
                field = productBox,
                list = null,
                parentId = "",
            )
        )
        assertTrue("the box's own kind is not named: $message", message.contains("a product"))
    }

    @Test
    fun `a box holding a row of this workshop says no card is printed for it, rather than which card`() {
        val message = dwWrongRecordTypeMessage(
            sketchBox,
            DwWorkshopCodeRef(DwWorkshopRecordType.ARTISAN, "cart0000000000000000001"),
        )
        assertNotNull(message)
        assertTrue("$message", message!!.contains("carry no printed code"))
        assertFalse("a designer was sent to find a tag that is never printed: $message",
            message.contains("own card or tag"))
    }

    @Test
    fun `a payment code or a shop barcode is refused by the grammar and not by this file`() {
        val message = refusalOf(
            dwScanLocalStep("upi://pay?pa=someone@bank", artisanBox, null, "")
        )
        assertTrue("$message", message.contains("DPW"))
    }

    @Test
    fun `a code one character out is refused rather than linked to a different record`() {
        val good = codeFor(DwWorkshopRecordType.ARTISAN, "cart0000000000000000001")
        val broken = good.dropLast(1) + if (good.last() == 'Z') '2' else 'Z'
        val message = refusalOf(dwScanLocalStep(broken, artisanBox, null, ""))
        assertTrue("a mistyped code was not refused: $message", message.contains("does not check out"))
    }

    @Test
    fun `a card for a record this device already holds is picked with no server at all`() {
        val cached = option("cart0000000000000000001")
        val step = dwScanLocalStep(
            scanned = codeFor(DwWorkshopRecordType.ARTISAN, "cart0000000000000000001"),
            field = artisanBox,
            list = cachedList(cached, option("cart0000000000000000002", "Ram Kumar")),
            parentId = "",
        )
        assertEquals(cached, pickOf(step))
    }

    @Test
    fun `a card for a record this device does not hold is sent to the server, not refused`() {
        val step = dwScanLocalStep(
            scanned = codeFor(DwWorkshopRecordType.ARTISAN, "cart0000000000000000009"),
            field = artisanBox,
            list = cachedList(option("cart0000000000000000001")),
            parentId = "",
        )
        val ask = step as? DwScanStep.AskServer
            ?: throw AssertionError("an unknown id was settled on the device: $step")
        assertEquals("cart0000000000000000009", ask.ref.id)
        assertEquals(DwWorkshopRecordType.ARTISAN, ask.ref.recordType)
    }

    @Test
    fun `a cached record on an unnarrowed cascaded list is asked about, not picked`() {
        val step = dwScanLocalStep(
            scanned = codeFor(DwWorkshopRecordType.PRODUCT, "cprod000000000000000001"),
            field = productBox,
            list = cachedList(option("cprod000000000000000001", "Sambalpuri saree")),
            parentId = "cart0000000000000000001",
        )
        assertTrue("a product from an unnarrowed list was picked: $step", step is DwScanStep.AskServer)
    }

    @Test
    fun `a cached record on a list the server narrowed to this parent is picked`() {
        val cached = option("cprod000000000000000001", "Sambalpuri saree")
        val step = dwScanLocalStep(
            scanned = codeFor(DwWorkshopRecordType.PRODUCT, "cprod000000000000000001"),
            field = productBox,
            list = cachedList(cached, filteredBy = "cart0000000000000000001"),
            parentId = "cart0000000000000000001",
        )
        assertEquals(cached, pickOf(step))
    }

    @Test
    fun `an option that names this parent itself is picked whatever the list says`() {
        val cached = option("cprod000000000000000001", "Sambalpuri saree",
            filterValue = "cart0000000000000000001")
        val step = dwScanLocalStep(
            scanned = codeFor(DwWorkshopRecordType.PRODUCT, "cprod000000000000000001"),
            field = productBox,
            list = cachedList(cached),
            parentId = "cart0000000000000000001",
        )
        assertEquals(cached, pickOf(step))
    }

    @Test
    fun `an uncascaded picker still answers from the cache with no proof to give`() {
        val cached = option("cart0000000000000000001")
        val step = dwScanLocalStep(
            scanned = codeFor(DwWorkshopRecordType.ARTISAN, "cart0000000000000000001"),
            field = artisanBox,
            list = cachedList(cached),
            parentId = "",
        )
        assertEquals(cached, pickOf(step))
    }

    @Test
    fun `the out-of-scope answer names the record and carries the remedy`() {
        val message = messageOf(
            dwScanServerAnswer(
                field = productBox,
                ref = DwWorkshopCodeRef(DwWorkshopRecordType.PRODUCT, "cprod000000000000000001"),
                payload = DwReferenceResponseDto(
                    model = "ProductDocumentation",
                    scope = "WORKSHOP",
                    options = emptyList(),
                    outOfScope = true,
                    outOfScopeOption = option("cprod000000000000000001", "Sambalpuri saree"),
                ),
                cascadeLabel = "Artisan",
            )
        )
        assertTrue("the excluded record is not named: $message", message.contains("Sambalpuri saree"))
        assertTrue(
            "the sentence does not say what to do about it: $message",
            message.contains("Link that record to this workshop"),
        )
        assertTrue(
            "the sentence does not say the row was left alone: $message",
            message.contains("Nothing on this row has been changed"),
        )
        assertFalse(
            "an out-of-scope record was reported as one that may not exist: $message",
            message == dwUnresolvedScanMessage(productBox, "Artisan"),
        )
    }

    @Test
    fun `an out-of-scope row is never offered as a choice`() {
        val answer = dwScanServerAnswer(
            field = productBox,
            ref = DwWorkshopCodeRef(DwWorkshopRecordType.PRODUCT, "cprod000000000000000001"),
            payload = DwReferenceResponseDto(
                options = emptyList(),
                outOfScope = true,
                outOfScopeOption = option("cprod000000000000000001", "Sambalpuri saree"),
            ),
            cascadeLabel = "",
        )
        assertTrue("an excluded record was linked to the row: $answer", answer is DwScanAnswer.Refused)
    }

    @Test
    fun `the flag without the row falls through to the unresolved sentence`() {
        val answer = dwScanServerAnswer(
            field = toolBox,
            ref = DwWorkshopCodeRef(DwWorkshopRecordType.TOOL, "ctool0000000000000000001"),
            payload = DwReferenceResponseDto(options = emptyList(), outOfScope = true),
            cascadeLabel = "",
        )
        assertEquals(dwUnresolvedScanMessage(toolBox, ""), messageOf(answer))
    }

    @Test
    fun `the unresolved sentence is the picker's own and not the one the record scanners use`() {
        val message = dwUnresolvedScanMessage(toolBox, "")
        assertFalse(
            "the picker borrowed a sentence written for a scanner that opens records: $message",
            message == unresolvedWorkshopCodeMessage(DwWorkshopRecordType.TOOL),
        )
        assertTrue("$message", message.contains("No tool this box can offer"))
        assertTrue("$message", message.contains("Nothing on this row has been changed"))
    }

    @Test
    fun `a record that does not exist and one this designer may not see read identically`() {
        val ref = DwWorkshopCodeRef(DwWorkshopRecordType.ARTISAN, "cart0000000000000000009")
        val absent = dwScanServerAnswer(artisanBox, ref, DwReferenceResponseDto(options = emptyList()), "")
        val forbidden = dwScanServerAnswer(
            artisanBox,
            ref,
            DwReferenceResponseDto(options = emptyList(), outOfScope = false),
            "",
        )
        assertEquals(messageOf(absent), messageOf(forbidden))
    }

    @Test
    fun `a cascaded box names the cascade as a possible reason, and an uncascaded one does not`() {
        val cascaded = dwUnresolvedScanMessage(productBox, "Artisan")
        assertTrue("the cascade is not named: $cascaded", cascaded.contains("chosen on this row"))
        assertTrue("the cascade's own field is not named: $cascaded", cascaded.contains("the artisan"))

        val plain = dwUnresolvedScanMessage(toolBox, "")
        assertFalse(
            "a box with no cascade blamed one anyway: $plain",
            plain.contains("chosen on this row"),
        )
    }

    @Test
    fun `a record the server resolves by id is picked, with the label and data it sent`() {
        val served = option("cart0000000000000000009", "Ram Kumar")
        val answer = dwScanServerAnswer(
            field = artisanBox,
            ref = DwWorkshopCodeRef(DwWorkshopRecordType.ARTISAN, "cart0000000000000000009"),
            payload = DwReferenceResponseDto(model = "Artisan", options = listOf(served)),
            cascadeLabel = "",
        )
        assertEquals(served, (answer as DwScanAnswer.Pick).option)
    }

    @Test
    fun `a prototype tag printed from a client key resolves to the row's real id`() {
        val row = DwReferenceOption(id = "centry00000000000000001", label = "Bag, second attempt")
        val answer = dwScanServerAnswer(
            field = prototypeBox,
            ref = DwWorkshopCodeRef(DwWorkshopRecordType.PROTOTYPE, "k-9f2c1a4e-0000-4000-8000-000000000001"),
            payload = DwReferenceResponseDto(model = "DwPrototype", options = listOf(row)),
            cascadeLabel = "",
        )
        assertEquals("centry00000000000000001", (answer as DwScanAnswer.Pick).option.id)
    }

    @Test
    fun `a truncated answer is never read for a row, because it is a server that ignored the id`() {
        val answer = dwScanServerAnswer(
            field = prototypeBox,
            ref = DwWorkshopCodeRef(DwWorkshopRecordType.PROTOTYPE, "k-9f2c1a4e-0000-4000-8000-000000000001"),
            payload = DwReferenceResponseDto(
                model = "DwPrototype",
                options = listOf(DwReferenceOption(id = "centry00000000000000001", label = "Whatever sorts first")),
                truncated = true,
            ),
            cascadeLabel = "",
        )
        assertTrue("a row was read out of an unnarrowed list: $answer", answer is DwScanAnswer.Refused)
    }

    @Test
    fun `the client-key fallback is for prototype tags only`() {
        val answer = dwScanServerAnswer(
            field = artisanBox,
            ref = DwWorkshopCodeRef(DwWorkshopRecordType.ARTISAN, "cart0000000000000000009"),
            payload = DwReferenceResponseDto(
                model = "Artisan",
                options = listOf(option("cart0000000000000000001", "Somebody else")),
            ),
            cascadeLabel = "",
        )
        assertTrue("an unrelated single option was linked to the row: $answer", answer is DwScanAnswer.Refused)
    }

    @Test
    fun `every no-answer sentence says the row was left alone`() {
        listOf(
            DW_SCAN_OFFLINE_MESSAGE,
            DW_SCAN_LOOKUP_FAILED_MESSAGE,
            DW_SCAN_UNSENT_WORKSHOP_MESSAGE,
        ).forEach { message ->
            assertTrue(
                "does not say the row was left alone: $message",
                message.contains("nothing on this row has been changed", ignoreCase = true),
            )
        }
    }

    @Test
    fun `no no-answer sentence claims the record does not exist`() {
        listOf(
            DW_SCAN_OFFLINE_MESSAGE,
            DW_SCAN_LOOKUP_FAILED_MESSAGE,
            DW_SCAN_UNSENT_WORKSHOP_MESSAGE,
        ).forEach { message ->
            val lowered = message.lowercase()
            assertFalse(
                "an unanswered lookup was written as a claim about the repository: $message",
                lowered.contains("may not have been recorded") || lowered.contains("cannot open"),
            )
        }
    }

    @Test
    fun `a cascade that moved under the request is said, and names the field that moved`() {
        val message = dwScanCascadeMovedMessage("Artisan")
        assertTrue("the field that moved is not named: $message", message.contains("The artisan on this row"))
        assertTrue(
            "the sentence does not say the row was left alone: $message",
            message.contains("Nothing on this row has been changed"),
        )
        assertTrue("the sentence does not say what to do: $message", message.contains("read the code again"))
    }

    @Test
    fun `a box with no cascade to name still gets a sentence that reads`() {
        val message = dwScanCascadeMovedMessage("")
        assertTrue("$message", message.startsWith("The record this list narrows to changed"))
        assertFalse("an empty label leaked into the sentence: $message", message.contains("The  on"))
    }

    @Test
    fun `the moved-cascade sentence is not one of the three refusals`() {
        val moved = dwScanCascadeMovedMessage("Artisan")
        assertFalse(moved == dwUnresolvedScanMessage(productBox, "Artisan"))
        assertFalse(moved == DW_SCAN_OFFLINE_MESSAGE)
        assertFalse(moved == DW_SCAN_LOOKUP_FAILED_MESSAGE)
        val lowered = moved.lowercase()
        assertFalse(
            "a moved row was written as a claim about the repository: $moved",
            lowered.contains("may not have been recorded") || lowered.contains("cannot open"),
        )
    }

    @Test
    fun `no signal and a server that answered badly are different sentences`() {
        assertFalse(DW_SCAN_OFFLINE_MESSAGE == DW_SCAN_LOOKUP_FAILED_MESSAGE)
        assertTrue(DW_SCAN_OFFLINE_MESSAGE.contains("no connection"))
        assertFalse(
            "a server that answered was blamed on the signal: $DW_SCAN_LOOKUP_FAILED_MESSAGE",
            DW_SCAN_LOOKUP_FAILED_MESSAGE.contains("connection"),
        )
    }
}
