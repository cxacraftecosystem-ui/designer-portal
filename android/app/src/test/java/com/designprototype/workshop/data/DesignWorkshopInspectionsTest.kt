package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APPOINTING AN INSPECTOR, AND READING A WORKSHOP THAT MAY NOT BE WRITTEN BACK.
 *
 * ── WHY EVERY ONE OF THESE IS A TEST AND NOT A CODE REVIEW ───────────────────────────────────────
 *
 * `PUT /design-workshop-inspections/{id}/inspectors` REPLACES the whole set, exactly as the viewers
 * PUT does, so every id absent from the payload is a row the server DELETES — silently, because a
 * deletion is the intended meaning of the call. That turns two ordinary-looking client bugs into
 * revocations nobody sees: building the picker from the eligible list alone drops an inspector the
 * platform access list barred since their assignment, and adopting the payload as the new baseline
 * instead of the ANSWER shows a panel nobody has the moment two admins edit one workshop. Neither
 * shows on screen — the list redraws, it looks right, and an examiner finds out a week later when a
 * workshop stops opening.
 *
 * ── AND THE READ, WHERE THE FAILURE IS THE OTHER DIRECTION ───────────────────────────────────────
 *
 * The read half cannot revoke anything; what it can do is tell an inspector something untrue. Three
 * of the assertions below are there because the honest answer and the convenient one differ:
 *
 *  * a MEDIA field with files in it is not "empty" and is not a photograph — it is "N files this
 *    read does not carry", and collapsing the three would either accuse a designer of a gap that is
 *    not there or promise an image that will never load;
 *  * an ENUM token the registry no longer offers is a real answer somebody gave, and dropping it
 *    from an INSPECTION is the least forgivable place to drop anything;
 *  * a REF that cannot be named prints a sentence and never the cuid, because on this surface there
 *    is no picker to open and check it against.
 *
 * The pure half of all three screens is separated from Compose precisely so this file can assert
 * them in milliseconds with no network, no dispatcher and no Retrofit.
 */
class DesignWorkshopInspectionsTest {

    private fun eligible(id: String, name: String = id, role: String = "INSPECTOR") =
        DwEligibleInspectorDto(id = id, name = name, email = "$id@example.org", role = role)

    private fun assigned(id: String, name: String = id, role: String = "INSPECTOR") =
        DwInspectorDto(userId = id, name = name, email = "$id@example.org", role = role)

    /** Configured exactly as `ApiClient` configures the converter Retrofit actually uses. */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    // ── The picker's options ─────────────────────────────────────────────────────────────────────

    @Test
    fun `an inspector the server no longer offers is still offered here, ticked and marked`() {
        // THE LOAD-BEARING ONE. `eligible_inspectors` excludes an account the PLATFORM allow-list has
        // rejected or suspended — deliberately, so an admin cannot assign an inspection the next
        // sign-in refuses — but their EXISTING row stands. Leaving them out of the options would mean
        // the next Save sends a set without them and ends an examination as a side effect of adding
        // somebody unrelated.
        val choices = dwInspectorChoices(
            eligible = listOf(eligible("u-active")),
            inspectors = listOf(assigned("u-barred")),
            eligibleListComplete = true,
        )

        assertEquals(listOf("u-active", "u-barred"), choices.map { it.userId })
        assertFalse(choices.first { it.userId == "u-active" }.assignedButIneligible)
        assertTrue(
            "an assigned account missing from a COMPLETE eligible list must be marked, or an admin " +
                "cannot tell it from an ordinary option",
            choices.first { it.userId == "u-barred" }.assignedButIneligible
        )
    }

    @Test
    fun `over a searched list nobody is marked ineligible, because the absence proves nothing`() {
        // Over a search result the mark is nonsense: every colleague who did not match the typed term
        // would be labelled as barred, on the screen where an admin decides whether to end somebody's
        // examination.
        val choices = dwInspectorChoices(
            eligible = listOf(eligible("u-match")),
            inspectors = listOf(assigned("u-elsewhere")),
            eligibleListComplete = false,
        )
        assertTrue(choices.none { it.assignedButIneligible })
    }

    @Test
    fun `a tick narrowed out by a search is handed back, so the next save cannot end it`() {
        // The anti-revocation store. An admin who found one examiner under one surname, ticked them,
        // then typed a second would otherwise save the second and silently end the first.
        val choices = dwInspectorChoices(
            eligible = listOf(eligible("u-second")),
            inspectors = emptyList(),
            eligibleListComplete = false,
            retained = listOf(eligible("u-first")),
        )
        assertEquals(listOf("u-second", "u-first"), choices.map { it.userId })
        assertTrue("a retained account says nothing about eligibility", choices.none { it.assignedButIneligible })
    }

    @Test
    fun `the workshop's creator is NOT filtered out, unlike the viewers picker`() {
        // THE DELIBERATE DIVERGENCE FROM `dwViewerChoices`, and it is the point of the tier rather
        // than an oversight. There the creator is dropped because `_deduplicate` drops them anyway
        // and a control that cannot do what it appears to reads as broken. Here they are refused BY
        // NAME with a 422 — "an independent review by somebody who worked on it is not a review" —
        // so hiding them would bury a MISTAKE an admin needs to be told about behind a silent no-op.
        //
        // The signature is the evidence: there is no `creatorId` parameter to pass.
        val choices = dwInspectorChoices(
            eligible = listOf(eligible("u-creator", role = "DESIGNER"), eligible("u-b")),
            inspectors = emptyList(),
            eligibleListComplete = true,
        )
        assertEquals(listOf("u-creator", "u-b"), choices.map { it.userId })
    }

    @Test
    fun `a blank id is never offered and a duplicate is never offered twice`() {
        val choices = dwInspectorChoices(
            eligible = listOf(eligible(""), eligible("u-a")),
            inspectors = listOf(assigned("u-a"), assigned("")),
            eligibleListComplete = true,
        )
        assertEquals(listOf("u-a"), choices.map { it.userId })
        assertFalse(
            "an account in BOTH lists is an ordinary eligible option, not a marked one",
            choices.single().assignedButIneligible
        )
    }

    // ── The pending set ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the payload is exactly what is ticked - there is no creator row to re-attach`() {
        val selection = DwInspectorSelection.adopt(listOf(assigned("u-a"), assigned("u-b")))
            .withSelection(setOf("u-b", "u-c"))
        assertEquals(setOf("u-c"), selection.added)
        assertEquals(setOf("u-a"), selection.removed)
        assertTrue(selection.dirty)
        assertEquals(setOf("u-b", "u-c"), selection.payload().toSet())
    }

    @Test
    fun `the ANSWER becomes the baseline, never the payload that was sent`() {
        // Another admin may have assigned somebody between this screen loading and Save being
        // pressed. A client that trusted its own request would show a panel nobody has.
        val sent = DwInspectorSelection.adopt(listOf(assigned("u-a"))).withSelection(setOf("u-a", "u-b"))
        assertTrue(sent.dirty)
        val served = DwInspectorSelection.adopt(listOf(assigned("u-a"), assigned("u-b"), assigned("u-c")))
        assertFalse("adopting the answer leaves nothing unsaved", served.dirty)
        assertEquals(setOf("u-a", "u-b", "u-c"), served.baseline)
    }

    @Test
    fun `discard returns to what the repository holds and nothing else`() {
        val selection = DwInspectorSelection.adopt(listOf(assigned("u-a"))).withSelection(emptySet())
        assertEquals(setOf("u-a"), selection.removed)
        assertFalse(selection.discard().dirty)
    }

    @Test
    fun `the cap is the server's twenty-five, and it is reached before the save rather than at it`() {
        // Mirrored so the refusal is a sentence about accounts rather than Pydantic's "List should
        // have at most 25 items", which names a shape and not a workshop.
        assertEquals(25, DW_INSPECTOR_LIMIT)
        val under = DwInspectorSelection().withSelection((1..DW_INSPECTOR_LIMIT).map { "u-$it" }.toSet())
        assertFalse(under.overLimit)
        val over = DwInspectorSelection().withSelection((1..DW_INSPECTOR_LIMIT + 1).map { "u-$it" }.toSet())
        assertTrue(over.overLimit)
        // LOWER THAN THE VIEWERS' CEILING, and deliberately: that list holds a field team, this one
        // holds examiners. A test that only asserted "some cap exists" would go green if somebody
        // copied the viewers' hundred across.
        assertTrue(DW_INSPECTOR_LIMIT < DW_VIEWER_LIMIT)
    }

    // ── The offer notice ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the notice has three states and says nothing at all in the ordinary one`() {
        // Silence is a real answer and the common one: a complete list has nothing to explain.
        assertNull(dwInspectorOfferNotice(DwEligibleInspectors(users = listOf(eligible("u-a")))))
        assertEquals(
            "Too many accounts to show them all — search a name or email to reach the rest.",
            dwInspectorOfferNotice(DwEligibleInspectors(truncated = true))
        )
        assertEquals(
            "Too many matches to show them all — narrow the search.",
            dwInspectorOfferNotice(DwEligibleInspectors(truncated = true, search = "sharma"))
        )
        assertEquals(
            "No Inspector / Reviewer account matches that search.",
            dwInspectorOfferNotice(DwEligibleInspectors(search = "sharma"))
        )
    }

    @Test
    fun `the viewers' FOURTH state is deliberately absent, and this is what pins that`() {
        // `dwViewerOfferNotice` opens with `truncated && users.isEmpty()` — the ACTIVE-ROSTER read
        // was cut, so eligible designers are absent from every possible search and narrowing cannot
        // help. `eligible_inspectors` reads no roster at all: it is one role plus the platform
        // allow-list, so a cut here can only be the account list hitting its ceiling, and a ceiling
        // is always reachable by typing. Copying the fourth sentence across would print advice about
        // a cut that cannot happen on this endpoint.
        val cutAndEmpty = DwEligibleInspectors(truncated = true)
        assertEquals(
            "an unsearched cut must advise searching, not report an unreachable cut",
            "Too many accounts to show them all — search a name or email to reach the rest.",
            dwInspectorOfferNotice(cutAndEmpty)
        )
        assertTrue(
            "the viewers' first sentence must not appear here at all",
            dwInspectorOfferNotice(cutAndEmpty)?.contains("no search can reach them") != true
        )
    }

    @Test
    fun `complete means the WHOLE eligible set and nothing narrower`() {
        assertTrue(DwEligibleInspectors().complete)
        assertFalse(DwEligibleInspectors(truncated = true).complete)
        assertFalse(DwEligibleInspectors(search = "a").complete)
    }

    // ── The search term ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the search term is stripped Python's way, so both sides agree about emptiness`() {
        // Python calls U+00A0 whitespace and `Char.isWhitespace` does not. A lone no-break space —
        // what a name pasted out of a PDF leaves behind — would be a request the server treats as
        // "no search", leaving this client holding a search box with something in it beside a list of
        // everybody.
        assertNull(dwInspectorSearchTerm(null))
        assertNull(dwInspectorSearchTerm("   "))
        assertNull(dwInspectorSearchTerm(" "))
        assertEquals("sharma", dwInspectorSearchTerm("  sharma  "))
    }

    @Test
    fun `the term is clamped to the server's max_length and never splits a surrogate pair`() {
        assertEquals(120, DW_INSPECTOR_SEARCH_MAX)
        val long = "a".repeat(DW_INSPECTOR_SEARCH_MAX + 40)
        assertEquals(DW_INSPECTOR_SEARCH_MAX, dwInspectorSearchTerm(long)?.length)
        // Half an emoji is not a character the server can be asked about. 119 a's then a surrogate
        // pair puts the boundary between its halves.
        val split = "a".repeat(DW_INSPECTOR_SEARCH_MAX - 1) + "🧵"
        assertEquals(DW_INSPECTOR_SEARCH_MAX - 1, dwInspectorSearchTerm(split)?.length)
    }

    // ── Reading a failure honestly ───────────────────────────────────────────────────────────────

    @Test
    fun `a save whose answer was lost never claims nothing was changed`() {
        // THE ONE CLAIM THAT MUST NEVER BE PRINTED WHEN IT CANNOT BE KNOWN. `replace_inspectors`
        // issues its `delete_many` and its `create_many` as two statements, so a fault between them
        // leaves the removals applied and the additions not.
        val lost = dwInspectionFailureMessage(null, null, DwInspectionAttempt.SAVE)
        assertFalse(lost.contains("Nothing has been changed"))
        assertTrue(lost.contains("may still have landed"))

        val read = dwInspectionFailureMessage(null, null, DwInspectionAttempt.READ)
        assertTrue("a read that failed changed nothing by construction", read.contains("Nothing has been changed."))
    }

    @Test
    fun `a 5xx is never reported as being offline`() {
        // The repository ANSWERED; something inside it failed. Calling that a connection problem
        // sends somebody to look at their bars and leaves a real fault wearing a message that will
        // never be investigated.
        val said = dwInspectionFailureMessage(500, null, DwInspectionAttempt.READ)
        assertTrue(said.contains("This is not a connection problem."))
        assertFalse(said.contains("could not reach the repository"))
    }

    @Test
    fun `the 403 names both doors, because either one of them may be the refusal`() {
        // A 403 here can mean two opposite things — an admin refused the inspector's READ surface, or
        // a non-admin refused the appointment routes — which is why the server's own
        // NOT_AN_INSPECTOR_DETAIL is written to name the other door and is passed through FIRST.
        val said = dwInspectionFailureMessage(
            403,
            "The inspection surface belongs to the Inspector / Reviewer tier.",
            DwInspectionAttempt.READ
        )
        assertTrue(said.startsWith("The inspection surface belongs to the Inspector / Reviewer tier. "))
        assertTrue(said.contains("two different"))
        assertTrue("a rule is not a fault, and the sentence has to say so", said.contains("Neither is a fault."))
    }

    @Test
    fun `the server's own 422 is passed through rather than talked over`() {
        // `_assert_every_id_may_inspect` already names the offending account and already ends with
        // "Nothing was changed." — and its refusals STACK, so it may name several people. Repeating
        // either half would be this client talking over the one message written for this moment.
        val detail = "Meena Iyer (meena@example.org) is already on this workshop as its creator or " +
            "a co-designer, so they cannot be its inspector. Nothing was changed."
        val said = dwInspectionFailureMessage(422, detail, DwInspectionAttempt.SAVE)
        assertTrue(said.contains(detail))
        assertEquals(
            "the client must add exactly one lead-in and no second verdict",
            1,
            Regex("Nothing was changed\\.").findAll(said).count()
        )
    }

    @Test
    fun `a punctuation-less server sentence does not run into the clause after it`() {
        // `require_admin` says "Admin access required" with no full stop. Concatenated directly this
        // produced "Admin access required The inspection surface …", which reads as a truncated
        // string and makes a reader distrust the whole message.
        val said = dwInspectionFailureMessage(403, "Admin access required", DwInspectionAttempt.SAVE)
        assertTrue(said.startsWith("Admin access required. The inspection surface"))
    }

    @Test
    fun `the deployment-predates-the-feature probe is a 404 and nothing else`() {
        assertTrue(dwInspectionAdministrationMissing(404))
        assertFalse(dwInspectionAdministrationMissing(403))
        assertFalse("nothing answered is not a missing route", dwInspectionAdministrationMissing(null))
    }

    // ── The wire ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a server that has never heard of truncated decodes as saying nothing about it`() {
        // This app ships separately from the API, so a handset updated ahead of the server is a real
        // state. `false` makes that phone say nothing about truncation, which is what it did
        // yesterday and is the only honest answer when the server has not been asked to have an
        // opinion.
        val decoded = json.decodeFromString(
            DwEligibleInspectorListDto.serializer(),
            """{"users":[{"id":"u-a","name":"A","email":"a@example.org","role":"INSPECTOR"}]}"""
        )
        assertFalse(decoded.truncated)
        assertEquals(1, decoded.users.size)
    }

    @Test
    fun `an inspection payload from a server predating readOnly still decodes, and reads as read-only`() {
        // THE PAIR THAT MATTERS: it must not throw, and the absent flag must not be read as "writable".
        val decoded = json.decodeFromString(
            DwInspectionDetailDto.serializer(),
            """{"id":"w-1","title":"Sambalpuri Ikat","stages":{},"completeness":{}}"""
        )
        assertNull(decoded.readOnly)
        assertTrue(dwInspectionIsReadOnly(decoded.readOnly))
    }

    @Test
    fun `an inspection payload decodes readOnly and the keys it deliberately does not carry`() {
        val decoded = json.decodeFromString(
            DwInspectionDetailDto.serializer(),
            """{"id":"w-1","title":"T","readOnly":true,"schemaVersion":"v9","stages":{},"completeness":{}}"""
        )
        assertEquals(true, decoded.readOnly)
        assertEquals("v9", decoded.schemaVersion)
        // A payload carrying an unknown key must not blank the screen: the shared decoder sets
        // `ignoreUnknownKeys`, and this is the assertion that says so rather than assuming it.
        val ahead = json.decodeFromString(
            DwInspectionDetailDto.serializer(),
            """{"id":"w-1","title":"T","readOnly":true,"somethingNew":42,"stages":{},"completeness":{}}"""
        )
        assertEquals("w-1", ahead.id)
    }

    @Test
    fun `an assignment row decodes with a null assignedAt rather than throwing`() {
        val decoded = json.decodeFromString(
            DwInspectorListDto.serializer(),
            """{"inspectors":[{"userId":"u-a","name":"A","email":"a@example.org","role":"INSPECTOR","assignedAt":null}]}"""
        )
        assertNull(decoded.inspectors.single().assignedAt)
    }

    // ── Reading one stored answer ────────────────────────────────────────────────────────────────

    private fun field(
        key: String,
        type: String,
        label: String = key,
        unit: String = "",
        options: List<EnumOption> = emptyList(),
        refModel: String = "",
        refFilterBy: String = "",
    ) = FieldDto(
        key = key, label = label, type = type, unit = unit,
        options = options, refModel = refModel, refFilterBy = refFilterBy,
    )

    private fun entity(vararg fields: FieldDto) =
        EntityDto(key = "e", name = "E", title = "E", fields = fields.toList())

    private val schema = SchemaResponse(version = "v1")

    private fun row(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> = mapOf(*pairs)

    @Test
    fun `a media field with files is neither empty nor a photograph - it is a count`() {
        // THE THREE-WAY SPLIT THIS WHOLE TYPE EXISTS FOR. `GET /media/{id}` is entitled per file and
        // an inspector holds no upload, no grant and no viewer row, so drawing a tile would render
        // its "could not be read" state — indistinguishable from a photograph that failed to load,
        // and not what happened.
        val e = entity(field("motifs", "IMAGE_LIST"), field("cover", "IMAGE"))
        val many = dwInspectionFieldReading(
            schema, e, e.fields[0],
            row("motifs" to buildJsonArray { add(JsonPrimitive("m-1")); add(JsonPrimitive("m-2")) })
        )
        assertEquals(DwInspectionReading.Media(2), many)
        val one = dwInspectionFieldReading(
            schema, e, e.fields[1],
            row("cover" to JsonPrimitive("m-9"))
        )
        assertEquals(DwInspectionReading.Media(1), one)
    }

    @Test
    fun `a media field with nothing in it is EMPTY, so a gap and a withheld file never collapse`() {
        // "No photograph" and "a photograph this read does not carry" are two different facts about
        // the workshop, and only one of them is a finding against the designer.
        val e = entity(field("cover", "IMAGE"))
        assertEquals(DwInspectionReading.Empty, dwInspectionFieldReading(schema, e, e.fields[0], row()))
        assertEquals(
            DwInspectionReading.Empty,
            dwInspectionFieldReading(schema, e, e.fields[0], row("cover" to JsonPrimitive("")))
        )
    }

    @Test
    fun `an enum token the registry no longer offers is printed, never dropped`() {
        // It is a real answer a designer gave against a list that has since changed, and hiding it
        // from an INSPECTION is the least forgivable place to hide anything — an inspector reads this
        // precisely to check what was recorded.
        val e = entity(field("loom", "ENUM", options = listOf(EnumOption(value = "PIT", label = "Pit loom"))))
        assertEquals(
            DwInspectionReading.Text("Pit loom"),
            dwInspectionFieldReading(schema, e, e.fields[0], row("loom" to JsonPrimitive("PIT")))
        )
        assertEquals(
            DwInspectionReading.Text("FRAME"),
            dwInspectionFieldReading(schema, e, e.fields[0], row("loom" to JsonPrimitive("FRAME")))
        )
    }

    @Test
    fun `a shared enum is resolved through the registry table when the field inlines nothing`() {
        val withEnums = SchemaResponse(
            version = "v1",
            enums = mapOf("LOOM" to listOf(EnumOption(value = "PIT", label = "Pit loom")))
        )
        val e = entity(FieldDto(key = "loom", label = "Loom", type = "ENUM", enumName = "LOOM"))
        assertEquals(
            DwInspectionReading.Text("Pit loom"),
            dwInspectionFieldReading(withEnums, e, e.fields[0], row("loom" to JsonPrimitive("PIT")))
        )
    }

    @Test
    fun `a reference is named from the sibling key hydration wrote, never from the cuid`() {
        // The name the picker copied onto this row at the moment the designer chose it is a fact
        // stored beside the id, and it is the only way to name a linked record without a lookup this
        // surface cannot make.
        val e = entity(
            field("artisanRef", "REF", label = "Artisan", refModel = "Artisan"),
            field("artisanName", "TEXT", label = "Artisan name"),
        )
        assertEquals(
            DwInspectionReading.Text("Sita Devi"),
            dwInspectionFieldReading(
                schema, e, e.fields[0],
                row(
                    "artisanRef" to JsonPrimitive("clx123"),
                    "artisanName" to JsonPrimitive("Sita Devi"),
                )
            )
        )
    }

    @Test
    fun `an unnameable reference prints a sentence and never the raw id`() {
        // A cuid asks an inspector to recognise a record they cannot possibly recognise, and on this
        // surface there is no picker to open and check it against.
        val e = entity(field("artisanRef", "REF", refModel = "Artisan"))
        val reading = dwInspectionFieldReading(
            schema, e, e.fields[0],
            row("artisanRef" to JsonPrimitive("clx123"))
        )
        assertEquals(DwInspectionReading.Text("A linked record this read cannot name"), reading)
        assertFalse((reading as DwInspectionReading.Text).text.contains("clx123"))
    }

    @Test
    fun `documentedFor names a cascade parent and never an ordinary reference`() {
        // `processStep` declares BOTH `documentedFor` and its own `name`, and reading `documentedFor`
        // unconditionally would label every step's process with its PRODUCT — a missing hint turned
        // into a wrong one, which is the worse outcome.
        val parentOnly = entity(
            field("productRef", "REF", refModel = "ProductDocumentation"),
            field("documentedFor", "TEXT"),
            // Something else CASCADES off productRef, which is what makes it a parent.
            field("processRef", "REF", refModel = "Process", refFilterBy = "productRef"),
        )
        assertEquals(
            DwInspectionReading.Text("Ikat saree"),
            dwInspectionFieldReading(
                schema, parentOnly, parentOnly.fields[0],
                row(
                    "productRef" to JsonPrimitive("p-1"),
                    "documentedFor" to JsonPrimitive("Ikat saree"),
                )
            )
        )
        // The SAME row read through the non-parent reference must NOT borrow `documentedFor`.
        assertEquals(
            DwInspectionReading.Text("A linked record this read cannot name"),
            dwInspectionFieldReading(
                schema, parentOnly, parentOnly.fields[2],
                row(
                    "processRef" to JsonPrimitive("pr-1"),
                    "documentedFor" to JsonPrimitive("Ikat saree"),
                )
            )
        )
    }

    @Test
    fun `a boolean false is an answer and not a blank`() {
        // "This cluster has no power supply" is a finding, not a gap, and an inspection that dropped
        // it would count a recorded negative as an unanswered field.
        val e = entity(field("hasPower", "BOOL"))
        assertEquals(
            DwInspectionReading.Text("No"),
            dwInspectionFieldReading(schema, e, e.fields[0], row("hasPower" to JsonPrimitive(false)))
        )
        assertEquals(
            DwInspectionReading.Text("Yes"),
            dwInspectionFieldReading(schema, e, e.fields[0], row("hasPower" to JsonPrimitive("yes")))
        )
    }

    @Test
    fun `a unit is appended to the value it belongs to`() {
        val e = entity(field("width", "DECIMAL", unit = "cm"))
        assertEquals(
            DwInspectionReading.Text("44.5 cm"),
            dwInspectionFieldReading(schema, e, e.fields[0], row("width" to JsonPrimitive("44.5")))
        )
    }

    @Test
    fun `a coordinate prints six decimals, and its accuracy only when the device reported one`() {
        // A fix with a 2 km radius and one with a 5 m radius are different evidence about the same
        // claim, and printing an accuracy nobody measured would invent one.
        val e = entity(field("where", "GEO"))
        val withAccuracy = buildJsonObject {
            put("lat", 20.2961); put("lon", 85.8245); put("accuracy", 12.4)
        }
        assertEquals(
            DwInspectionReading.Text("20.296100, 85.824500 (±12 m)"),
            dwInspectionFieldReading(schema, e, e.fields[0], row("where" to withAccuracy))
        )
        val without = buildJsonObject { put("lat", 20.2961); put("lon", 85.8245) }
        assertEquals(
            DwInspectionReading.Text("20.296100, 85.824500"),
            dwInspectionFieldReading(schema, e, e.fields[0], row("where" to without))
        )
    }

    @Test
    fun `a narrative that was opened and left alone is empty, matching the server and the browser`() {
        // A rich-text document that has been opened and left is a non-empty JsonObject holding not
        // one word. Counting it as answered is how a stage reports itself complete with nothing in
        // it — and the phone being the OPTIMISTIC one is the wrong direction, because it is the
        // surface an inspector uses to decide whether the fieldwork was done.
        val e = entity(field("intro", "RICH_TEXT"))
        val emptyDoc: JsonObject = buildJsonObject {
            put("blocks", buildJsonArray { add(buildJsonObject { put("kind", "PARAGRAPH"); put("spans", buildJsonArray { }) }) })
        }
        assertEquals(DwInspectionReading.Empty, dwInspectionFieldReading(schema, e, e.fields[0], row("intro" to emptyDoc)))
    }

    @Test
    fun `unanswered fields are counted rather than drawn, and media with files counts as answered`() {
        // Forty empty rows would bury the answers that exist; omitting them silently would tell an
        // inspector nothing about the gaps, which is most of what an inspection is looking for.
        //
        // MEDIA COUNTS AS ANSWERED: the files exist and this read simply does not carry them.
        // Counting them as unanswered would accuse a designer of a gap that is a limit of the payload.
        val e = entity(
            field("name", "TEXT"),
            field("notes", "LONG_TEXT"),
            field("cover", "IMAGE"),
        )
        val answered = row(
            "name" to JsonPrimitive("Sambalpuri"),
            "cover" to JsonPrimitive("m-1"),
        )
        assertEquals(1, dwInspectionUnansweredCount(schema, e, e.fields, answered))
        assertEquals(3, dwInspectionUnansweredCount(schema, e, e.fields, row()))
    }
}
