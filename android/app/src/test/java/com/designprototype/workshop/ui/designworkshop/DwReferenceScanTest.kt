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

/**
 * READING A CARD INTO A REFERENCE PICKER — what each answer is, and what each one must not say.
 *
 * ── WHY THIS IS A UNIT TEST AND NOT AN INSTRUMENTED ONE ───────────────────────────────────────
 *
 * Every decision worth pinning here is made before a pixel is drawn: which cards a field will accept,
 * whether the device can answer alone, and which of five sentences a designer is shown. Those live in
 * [dwScannableRecordType], [dwScanLocalStep] and [dwScanServerAnswer], which take a code, a cached
 * list and a payload and return an answer — so they are checkable with no Compose runtime, no camera
 * and no server, which is what makes it worth checking the WORDING as well as the branch. The browser
 * lifted the same three functions out of its component for the same reason, and
 * `e2e/qr-surfaces-unit.spec.ts` calls them with real payloads.
 *
 * ── THE PROPERTY THAT MATTERS MOST IS A NEGATIVE ONE ──────────────────────────────────────────
 *
 * A refusal must never confirm that a record exists. `require_record` raises 404 and never 403 for a
 * record the caller may not have, and `reference_options` runs its by-id probe as a `find_many`
 * carrying the same read predicate, precisely so that "no such record" and "not yours" are one
 * answer. A client that told them apart would undo that from the outside, and a stack of printed
 * cards would become a way to enumerate the repository one photograph at a time. Several tests below
 * exist only to keep that collapse in place.
 */
class DwReferenceScanTest {

    // ── Fields, as the registry declares them ────────────────────────────────────────────────────

    private fun refField(key: String, label: String, model: String, filterBy: String = "") =
        FieldDto(key = key, label = label, type = "REF", refModel = model, refFilterBy = filterBy)

    private val artisanBox = refField("artisanRef", "Artisan", "Artisan")
    private val productBox = refField("productRef", "Documented product", "ProductDocumentation", "artisanRef")
    private val toolBox = refField("toolRef", "Documented tool", "ToolDocumentation")
    private val prototypeBox = refField("prototypeRef", "Prototype", "DwPrototype")
    private val sketchBox = refField("sketchRef", "From sketch", "DwSketch")

    // ── Codes, built with the app's own encoder so no check digit is invented here ───────────────

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

    /**
     * [filteredBy] IS THE HONEST HALF OF THE FIXTURE AND DEFAULTS TO BLANK, which is what the
     * repository stores for a list it merged out of the device's other cached files. A list that
     * really was fetched under one parent carries it; the merge fallback deliberately does not.
     */
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

    // ── Which pickers offer a card reader at all ─────────────────────────────────────────────────

    @Test
    fun `every reference model the server can serve has a card`() {
        // The five repository models are exactly `REFERENCE_MODELS` in `design_workshops.py`, and
        // every one of them has a `RecordCodeSection` drawn on its own screen in MainActivity.
        listOf("Artisan", "Craft", "ProductDocumentation", "ToolDocumentation", "Process").forEach {
            assertNotNull("no card reader offered on a picker for $it", dwScannableRecordType(it))
        }
        // A prototype tag is the other half of what `WorkshopCodesScreen` prints.
        assertEquals(DwWorkshopRecordType.PROTOTYPE, dwScannableRecordType("DwPrototype"))
    }

    @Test
    fun `a picker for a model nothing prints a card for offers no reader`() {
        // Not an omission to be filled in later: no letter in the grammar means no card exists, so a
        // reader on these could only ever refuse, which reads as a broken scanner rather than as a
        // box no card belongs in.
        listOf("DwSketch", "DwParticipant", "DwCostSheet", "DwFinalProduct").forEach {
            assertNull("a card reader was offered on a picker for $it", dwScannableRecordType(it))
        }
        // `W` exists in the grammar, but there is no `Workshop` reference model, so no REF field can
        // declare one and a branch for it would describe a picker that cannot exist.
        assertNull(dwScannableRecordType("Workshop"))
    }

    // ── Refusal (a): the wrong kind of card ──────────────────────────────────────────────────────

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
        // THE ONE REFUSAL A PHONE IN A COURTYARD CAN ALWAYS GIVE. It is decided from the letter in
        // the code, so a device that has never downloaded a list still answers it correctly.
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
        // A `DwSketch` is another ROW of this workshop. There is no tag anywhere to go and find, so
        // sending the designer to look for one would be the worst of the answers available.
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
        // The decode refusal is passed through as the parser wrote it. A second opinion here is how a
        // read code and a typed one come to be judged differently about the same card.
        val message = refusalOf(
            dwScanLocalStep("upi://pay?pa=someone@bank", artisanBox, null, "")
        )
        assertTrue("$message", message.contains("DPW"))
    }

    @Test
    fun `a code one character out is refused rather than linked to a different record`() {
        val good = codeFor(DwWorkshopRecordType.ARTISAN, "cart0000000000000000001")
        // Break the check block, which is the last four characters of the code.
        val broken = good.dropLast(1) + if (good.last() == 'Z') '2' else 'Z'
        val message = refusalOf(dwScanLocalStep(broken, artisanBox, null, ""))
        assertTrue("a mistyped code was not refused: $message", message.contains("does not check out"))
    }

    // ── The offline pick: the device answers alone whenever it can ───────────────────────────────

    @Test
    fun `a card for a record this device already holds is picked with no server at all`() {
        // THE COURTYARD CASE, and the one place this client does something the browser does not. The
        // cached option carries the record's own `data`, so hydration runs immediately and
        // identically to a pick made from the dropdown.
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
        // The whole point of the feature is a record SOMEBODY ELSE made. Refusing here because this
        // phone's list is short would answer "no such artisan" about an artisan that exists.
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
        /*
         * THE SHAPE THE SERVER ACTUALLY SENDS, which is the whole point of this fixture.
         * `_reference_option` emits id, label, sublabel and data and nothing else, so `filterValue`
         * is BLANK on every option that ever came off the wire — and `DwReferenceList.narrowedTo`
         * keeps a blank one rather than dropping it. Meanwhile the repository's offline fallback
         * merges the model's cached files ACROSS filters, so this list legitimately holds another
         * artisan's product while the row names this one.
         *
         * An earlier version of this test set `filterValue = "someone-else"` and passed against a
         * branch no payload can reach. The list below is the real one: nothing about it contradicts
         * the parent, and nothing about it establishes the parent either, which is exactly why the
         * code has to go to the server.
         */
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
        // The other half, and the reason the rule is a proof and not a ban: a designer who chose the
        // artisan while online has this artisan's products cached under this artisan's key, and a
        // card read in the courtyard an hour later must still link with no request.
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
        // Nothing on the wire populates `filterValue` today. It is honoured so that the day a server
        // does, the device stops having to ask — and so that this rule is about the evidence rather
        // than about which of two fields happens to carry it.
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
        // The courtyard case must not be collateral damage of the rule above. A box with no
        // `refFilterBy` has no cascade to belong to, and the cache is fenced to one model and one
        // owner before it ever reaches here.
        val cached = option("cart0000000000000000001")
        val step = dwScanLocalStep(
            scanned = codeFor(DwWorkshopRecordType.ARTISAN, "cart0000000000000000001"),
            field = artisanBox,
            list = cachedList(cached),
            parentId = "",
        )
        assertEquals(cached, pickOf(step))
    }

    // ── Refusal (b): real, readable, and out of this field's scope ───────────────────────────────

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
        // AND IT IS NOT THE UNRESOLVED SENTENCE. The two demand opposite next actions — link the
        // record to this cluster, versus go and search by name — which is the whole reason the server
        // carries the flag at all.
        assertFalse(
            "an out-of-scope record was reported as one that may not exist: $message",
            message == dwUnresolvedScanMessage(productBox, "Artisan"),
        )
    }

    @Test
    fun `an out-of-scope row is never offered as a choice`() {
        // The server keeps it out of `options` so a client that renders `options` cannot show it as
        // an ordinary row. This client must not undo that by reading it back into a pick.
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
        // The server derives the flag FROM the row, so the two cannot disagree; a flag with no row is
        // a payload this build does not know how to render, and inventing a name for it would be
        // worse than the honest fallback.
        val answer = dwScanServerAnswer(
            field = toolBox,
            ref = DwWorkshopCodeRef(DwWorkshopRecordType.TOOL, "ctool0000000000000000001"),
            payload = DwReferenceResponseDto(options = emptyList(), outOfScope = true),
            cascadeLabel = "",
        )
        assertEquals(dwUnresolvedScanMessage(toolBox, ""), messageOf(answer))
    }

    // ── Refusal (c): nothing this box can offer ──────────────────────────────────────────────────

    @Test
    fun `the unresolved sentence is the picker's own and not the one the record scanners use`() {
        /*
         * The RULE is carried over and the WORDS are not. `unresolvedWorkshopCodeMessage` sends the
         * reader to a screen — "search for the tool by name instead" — which is right for a scanner
         * that opens records and wrong for a box that fills a row in.
         */
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
        // THE PROPERTY THIS WHOLE FEATURE RESTS ON. Both arrive as an empty `options` with
        // `outOfScope` false — the server composes the same read predicate the record list routes
        // compose, so a row the caller may not read simply produces no row — and nothing here may
        // introduce a difference the wire does not carry.
        val ref = DwWorkshopCodeRef(DwWorkshopRecordType.ARTISAN, "cart0000000000000000009")
        val absent = dwScanServerAnswer(artisanBox, ref, DwReferenceResponseDto(options = emptyList()), "")
        val forbidden = dwScanServerAnswer(
            artisanBox,
            ref,
            // A forbidden row is excluded by the predicate, so the probe finds nothing and the flag
            // stays false — byte for byte the payload above.
            DwReferenceResponseDto(options = emptyList(), outOfScope = false),
            "",
        )
        assertEquals(messageOf(absent), messageOf(forbidden))
    }

    @Test
    fun `a cascaded box names the cascade as a possible reason, and an uncascaded one does not`() {
        // The server's out-of-scope probe KEEPS the artisan clause, so a product belonging to
        // somebody else's artisan lands in this refusal and not in the out-of-scope one. Saying only
        // "it may not be in the repository" would be a lie about a record the designer can see two
        // rows up.
        val cascaded = dwUnresolvedScanMessage(productBox, "Artisan")
        assertTrue("the cascade is not named: $cascaded", cascaded.contains("chosen on this row"))
        assertTrue("the cascade's own field is not named: $cascaded", cascaded.contains("the artisan"))

        val plain = dwUnresolvedScanMessage(toolBox, "")
        assertFalse(
            "a box with no cascade blamed one anyway: $plain",
            plain.contains("chosen on this row"),
        )
    }

    // ── The successful by-id resolve ─────────────────────────────────────────────────────────────

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
        /*
         * HALF THE TAGS EVER PRINTED NAME A CLIENT KEY. A prototype tag has to be printable the
         * afternoon the prototype is made, and a workshop can go a fortnight without signal, so
         * `_in_record_options` matches `id` OR `clientKey` and always answers with the row's real id.
         * An id-only comparison here would refuse a record the server had just found.
         */
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
        /*
         * THE GUARD THAT MAKES THE CLIENT-KEY FALLBACK SAFE. A deployment older than the by-id half
         * does not refuse an unknown query parameter, it IGNORES it and returns the ordinary list —
         * and the request asks for a page of ONE, so that arrives as `truncated = true`. Reading a
         * row out of it would tag the stage with whatever sorts first: a wrong record chosen
         * confidently, which is the failure a printed identifier exists to end.
         */
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
        // A repository record is looked up on its `id` alone, so a single option that does not carry
        // the scanned id is not proof of anything and must not be taken as the answer.
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

    // ── Offline, which is not one of the three ───────────────────────────────────────────────────

    @Test
    fun `every no-answer sentence says the row was left alone`() {
        // A designer who has just read a card and been refused must not be left wondering whether
        // something was linked anyway.
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
        // "There is no signal" and "there is no such record" send a designer to opposite places, and
        // this app's normal state is the first one.
        listOf(
            DW_SCAN_OFFLINE_MESSAGE,
            DW_SCAN_LOOKUP_FAILED_MESSAGE,
            DW_SCAN_UNSENT_WORKSHOP_MESSAGE,
        ).forEach { message ->
            val lowered = message.lowercase()
            assertFalse(
                "an unanswered lookup was written as a claim about the repository: $message",
                lowered.contains("not in the repository") || lowered.contains("cannot open"),
            )
        }
    }

    // ── The row that moved while the lookup was in the air ─────────────────────────────

    @Test
    fun `a cascade that moved under the request is said, and names the field that moved`() {
        // The failure this guards is a product read under artisan A landing on a row that now names
        // artisan B — linked AND hydrated, so B's row would carry A's product's measurements.
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
        // Unreachable from the panel, whose guard can only fire where there is a parent to change.
        // A blank label must still produce prose rather than a sentence with a hole in it.
        val message = dwScanCascadeMovedMessage("")
        assertTrue("$message", message.startsWith("The record this list narrows to changed"))
        assertFalse("an empty label leaked into the sentence: $message", message.contains("The  on"))
    }

    @Test
    fun `the moved-cascade sentence is not one of the three refusals`() {
        // It is a fourth answer of the offline kind: the lookup happened, and its answer is about a
        // row that no longer exists in that shape. Saying "no product matches that code" instead
        // would be a claim about the repository that nothing was measured.
        val moved = dwScanCascadeMovedMessage("Artisan")
        assertFalse(moved == dwUnresolvedScanMessage(productBox, "Artisan"))
        assertFalse(moved == DW_SCAN_OFFLINE_MESSAGE)
        assertFalse(moved == DW_SCAN_LOOKUP_FAILED_MESSAGE)
        val lowered = moved.lowercase()
        assertFalse(
            "a moved row was written as a claim about the repository: $moved",
            lowered.contains("not in the repository") || lowered.contains("cannot open"),
        )
    }

    @Test
    fun `no signal and a server that answered badly are different sentences`() {
        // A 500 means the server was reached and then failed. Telling a designer their signal is at
        // fault sends them out of the building while the real bug wears an offline message.
        assertFalse(DW_SCAN_OFFLINE_MESSAGE == DW_SCAN_LOOKUP_FAILED_MESSAGE)
        assertTrue(DW_SCAN_OFFLINE_MESSAGE.contains("no connection"))
        assertFalse(
            "a server that answered was blamed on the signal: $DW_SCAN_LOOKUP_FAILED_MESSAGE",
            DW_SCAN_LOOKUP_FAILED_MESSAGE.contains("connection"),
        )
    }
}
