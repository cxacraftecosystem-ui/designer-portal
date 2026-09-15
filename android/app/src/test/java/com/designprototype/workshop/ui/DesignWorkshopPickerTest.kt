package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UNFILED_BY_CHOICE
import com.designprototype.workshop.data.UNFILED_NO_OPTIONS
import com.designprototype.workshop.data.unfiledLinkReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE TWO-BOX CASCADE ON THE HANDSET: type of workshop, then the workshop, on all six record forms.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS FILE EXISTS AT ALL, AND WHY IT IS NAMED WHAT IT IS NAMED
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `DesignWorkshopPicker.kt` has said *"Internal rather than private so `DesignWorkshopPickerTest` can
 * pin the pairing without a screen"* since `designWorkshopPrefillNote` was written, and no such file
 * existed. A comment naming a test that is not in the tree is worse than no comment: it is a claim
 * that something is covered, read by the next author as a reason not to look. The remedy is to write
 * the test the sentence promised, not to delete the sentence — so the prefill pairing is pinned
 * below, first, before anything the cascade added.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE ANDROID TWIN OF `frontend/e2e/design-workshop-cascade-unit.spec.ts`
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The web pins five rules there. They are reproduced here as the same five, in the same order, each
 * one asserted against THIS client's mechanism rather than translated word for word:
 *
 *  1. THE NARROWING GOES TO THE SERVER. One page is twenty rows out of a much larger table, so a
 *     filter applied to the rows in hand answers *"no workshops of this type"* about types that
 *     plainly have some — R5, and the same absence-read-as-non-existence the whole of
 *     `WorkshopOptions.kt` exists to prevent.
 *  2. THE TYPE IS IN THE EFFECT'S KEY. Without it the box changes, no request goes out, and the
 *     picker draws the previous type's workshops under the new type's label: confidently wrong
 *     rather than merely stale, because nothing on screen says the list did not move.
 *  3. THE DEFAULT IS A REAL TOKEN. `workshopKind` is validated server-side by `enum_filter_or_422`,
 *     so a token the registry does not carry is a 422 on a read nobody asked for.
 *  4. NOTHING HERE IS SAVED. The record stores `designWorkshopId`; the type is a lens over the list.
 *  5. EVERY MOUNT HAS IT. The field repository shipped its tracer form by form, missed four of nine
 *     mounts, and a researcher reported the feature as simply absent. A cascade on four of six forms
 *     is that bug again.
 *
 * ── AND ONE RULE THE BROWSER CANNOT HAVE AN OPINION ABOUT ───────────────────────────────────────
 *
 * Rule 6 below is this client's alone: a type filter must not change what the OFFLINE OUTBOX records
 * about why a record went up unfiled. A browser has no outbox and no clearance sentinel, so there is
 * nothing in the web suite to mirror and nothing there to warn about it. See
 * [DesignWorkshopPickerState.everListedRows].
 *
 * ── WHY SO MUCH OF IT READS SOURCE ──────────────────────────────────────────────────────────────
 *
 * `app/build.gradle.kts` carries no `ui-test-junit4` and no Robolectric, so the JVM suite cannot
 * compose a picker and look at it — the same constraint `WorkshopOptionsTest` and `RosterFilterWireTest`
 * open with, and the same answer: the rulings that can be pure functions are asserted as pure
 * functions, and the ones that are WIRING — which effect is keyed on what, which forms mount what —
 * are asserted against the source, which is where wiring lives. `DashboardTileParityTest` reads
 * `MainActivity.kt` for exactly this reason and this file borrows its shape.
 */
class DesignWorkshopPickerTest {

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 0. THE PAIRING THE PICKER'S OWN COMMENT PROMISED THIS FILE WOULD PIN
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * THE TWO DOORS NEED DIFFERENT SENTENCES, which is the whole reason `reason` crosses the wire.
     *
     * *"the workshop you were most recently added to"* sends a designer looking for an allocation
     * that really happened; *"the one you opened most recently"* does not. A picker that fills itself
     * in and explains itself with the wrong door is worse than one that says nothing, because the
     * designer acts on it.
     */
    @Test
    fun `the prefill note names which door the default came through`() {
        val granted = designWorkshopPrefillNote("GRANTED", "2026-08-30T09:15:00Z")
        val created = designWorkshopPrefillNote("CREATED", "2026-08-30T09:15:00Z")

        assertTrue("GRANTED must name the allocation", granted!!.contains("most recently added to"))
        assertTrue("CREATED must name the opening", created!!.contains("most recently opened"))
        assertNotEquals("two doors, two sentences", granted, created)
        // Both must offer the way out, or a prefill reads as a decision already taken.
        assertTrue(granted.contains("Change it if this record belongs somewhere else."))
        assertTrue(created.contains("Change it if this record belongs somewhere else."))
    }

    /**
     * AN UNKNOWN `reason` PRINTS NOTHING RATHER THAN ONE OF THE TWO KNOWN WORDS.
     *
     * A third door added server-side must not be dressed as one of the two that exist. The sentence
     * is about WHY, and a wrong why is the one kind of explanation that costs more than silence.
     */
    @Test
    fun `an unrecognised reason is not dressed as one of the two known ones`() {
        assertNull(designWorkshopPrefillNote("INHERITED", "2026-08-30T09:15:00Z"))
        assertNull(designWorkshopPrefillNote(null, "2026-08-30T09:15:00Z"))
        assertNull(designWorkshopPrefillNote("", null))
    }

    /**
     * A DATE THAT WILL NOT PARSE IS DROPPED, NOT ECHOED — the opposite of what this app does with a
     * date a designer TYPED.
     *
     * This one is the server's, so an unparseable value is a defect rather than something to put in
     * front of a reader, and the sentence reads perfectly well without it.
     */
    @Test
    fun `a malformed timestamp costs the day and not the sentence`() {
        val good = designWorkshopPrefillNote("GRANTED", "2026-08-30T09:15:00Z")!!
        assertTrue(good.contains("on 2026-08-30"))

        for (bad in listOf(null, "", "yesterday", "30-08-2026T00:00:00Z", "2026")) {
            val note = designWorkshopPrefillNote("GRANTED", bad)
            assertTrue("a bad timestamp must not lose the sentence: $bad", note != null)
            assertFalse("and must not be echoed: $bad", note!!.contains(" on "))
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 1. THE NARROWING IS A QUERY PARAMETER, NEVER A FILTER OVER THE PAGE IN HAND
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `the chosen type is sent to the server`() {
        // Bounded at the call's OWN closing paren, which is the only one at this indentation. A
        // plain `substringBefore(")")` stops inside `isNotBlank()` and quietly asserts half a line.
        val call = PICKER
            .substringAfter("repository.designWorkshops(")
            .substringBefore("\n            )")
        assertTrue(
            "rememberDesignWorkshopPicker no longer passes workshopKind to designWorkshops. One page " +
                "is DESIGN_WORKSHOP_PAGE rows, so narrowing on the device answers 'none of this type' " +
                "about types that have some.",
            call.contains("workshopKind")
        )
        assertTrue(
            "and it must reach the wire as an ABSENT parameter when nothing is chosen (R1), never as " +
                "a blank token, which is a filter matching nothing over a full corpus.",
            call.contains("takeIf { it.isNotBlank() }")
        )
    }

    @Test
    fun `no part of the picker narrows rows it already holds`() {
        assertFalse(
            "DesignWorkshopPicker filters rows by workshopKind on the device. That is DROPDOWN_DESIGN " +
                "R5: the corpus is on the server and the box must ask it.",
            Regex("""\.filter\s*[({][^\n]*workshopKind""").containsMatchIn(PICKER)
        )
        assertFalse(
            "and neither may the option builder.",
            Regex("""\.filter\s*[({][^\n]*workshopKind""").containsMatchIn(OPTIONS)
        )
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 2. A TYPE CHANGE RE-READS THE LIST — AND RE-READS NOTHING ELSE
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    fun `the listing effect is keyed on the chosen type`() {
        assertTrue(
            "the list effect is no longer keyed on the type, so tapping the type box sends no request " +
                "and the picker keeps drawing the previous type's workshops under the new type's label.",
            PICKER.contains("LaunchedEffect(resetKey, state.kind)")
        )
    }

    /**
     * AND THE DEFAULT-FOR-ME READ IS NOT — which is the one non-obvious piece of this change.
     *
     * The two reads used to share one effect. Re-keying that effect on the type would have re-issued
     * `GET /design-workshops/default-for-me` on every tap of the type box, to be told the same thing
     * each time, on a connection where — as the note in that effect put it — *"every avoidable
     * request is one the designer waits through"*. The split is the whole reason there are two
     * effects instead of one, and a later tidy-up that merges them back would undo it in silence.
     */
    @Test
    fun `changing the type does not re-ask which workshop this account was last given`() {
        val defaultEffect = PICKER
            .substringBefore("repository.designWorkshopDefaultForMe()")
            .substringAfterLast("LaunchedEffect(")
        assertFalse(
            "the default-for-me read has been keyed on the type. It answers 'which workshop were you " +
                "most recently given', which the type box has no bearing on; per-tap it is a round " +
                "trip spent to be told the same thing.",
            defaultEffect.contains("kind")
        )
    }

    /**
     * A TYPE IS A TAP AND NOT TYPING, so the re-read must not be made to wait.
     *
     * `DwWorkshopNameField` debounces its NAME box and deliberately does not debounce its type, and
     * `DesignWorkshopSelect.tsx` keys its own debounce off the search term alone for the same reason:
     * there is no run of intermediate values to absorb. This picker has no search box at all
     * (`searchable = false`), so a `delay` anywhere in it could only ever be delaying a tap.
     */
    @Test
    fun `the re-read is not debounced`() {
        assertFalse(
            "a delay has appeared in the picker. A type change has no burst to absorb; the only thing " +
                "a debounce here can do is make a tap feel broken.",
            withoutComments(PICKER).contains("delay(")
        )
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 3. THE DEFAULT IS A TOKEN THE VOCABULARY ACTUALLY CARRIES
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * CHECKED AGAINST THE REGISTRY THIS APK SHIPS, not against a copy of the list in a test.
     *
     * The bundled asset is what `StageSchemaStore.readAsset` falls through to and therefore what a
     * handset with no signal actually offers. A default that is not in it is a token the box would
     * send to a server that answers 422 — `enum_filter_or_422` refuses an unknown kind rather than
     * returning an empty list, which is the right server behaviour and exactly why the client may
     * not send one.
     */
    @Test
    fun `the default type is one the shipped vocabulary carries`() {
        assertTrue(
            "DEFAULT_WORKSHOP_KIND is $DEFAULT_WORKSHOP_KIND, which the bundled registry does not " +
                "carry. workshopKind is validated server-side, so an unknown token is a 422 on a read " +
                "nobody asked for.",
            SHIPPED_KINDS.contains(DEFAULT_WORKSHOP_KIND)
        )
    }

    /** And it is the ruling the owner gave, stated rather than restated. */
    @Test
    fun `the default is the design and prototype workshop`() {
        assertEquals("DESIGN_PROTOTYPE_DEVELOPMENT", DEFAULT_WORKSHOP_KIND)
    }

    /**
     * THE SAME TOKEN AS THE BROWSER'S, READ OFF THE BROWSER'S OWN FILE.
     *
     * A designer whose laptop opens on one type and whose phone opens on another is reading two
     * different lists under one label, on the same record, in the same afternoon — and the first
     * thing they conclude is that one of the two is missing workshops.
     */
    @Test
    fun `both clients open on the same type`() {
        val web = Regex("DEFAULT_WORKSHOP_KIND\\s*=\\s*\"([A-Z_]+)\"")
            .find(repoFile("../frontend/components/forms/DesignWorkshopCascade.tsx", "frontend/components/forms/DesignWorkshopCascade.tsx").readText(Charsets.UTF_8))
            ?.groupValues?.get(1)
        assertEquals("the two clients no longer open on the same type of workshop", web, DEFAULT_WORKSHOP_KIND)
    }

    /**
     * A RETIRED TOKEN FALLS BACK TO EVERY TYPE, AND NOT TO WHATEVER NOW SITS FIRST.
     *
     * "Every type" is the only answer certainly correct without knowing what replaced the retired
     * one, and it hides nothing. A fallback to the first offered row would narrow a designer's list
     * to a type somebody chose for them in a migration, silently.
     */
    @Test
    fun `a type the registry has retired is not sent back to the server`() {
        val offered = listOf(SelectOption("SKILL_UPGRADATION", "Skill Upgradation"))
        assertEquals("", retainedWorkshopKind(DEFAULT_WORKSHOP_KIND, offered))
        assertEquals("SKILL_UPGRADATION", retainedWorkshopKind("SKILL_UPGRADATION", offered))
    }

    /**
     * AND AN EMPTY VOCABULARY RETIRES NOTHING.
     *
     * Empty means the whole enum is gone from the registry, the type box is not drawn at all, and
     * blanking the held token there would be a second list read issued for a control nobody can see.
     * It is also the state the loader's synchronous seed is in on a cold process, one frame before
     * the registry answers off disk — clearing the default there would make every cold open of a
     * record form fetch the unnarrowed list first and the narrowed one a moment later.
     */
    @Test
    fun `an empty vocabulary leaves the held type alone`() {
        assertEquals(DEFAULT_WORKSHOP_KIND, retainedWorkshopKind(DEFAULT_WORKSHOP_KIND, emptyList()))
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 4. THE TYPE IS A LENS, NEVER A STORED COLUMN — AND IT NEVER DISCARDS A CHOSEN WORKSHOP
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * NO FORM CAN READ THE TYPE, SO NO FORM CAN SEND IT.
     *
     * A record's type is a fact about the workshop it is filed under — `DesignWorkshop.workshopKind`,
     * answered in stage 1 — so a second copy beside the record disagrees with its own source the
     * first time somebody corrects that stage. The six save handlers reach the picker through
     * `designWorkshop.value()` and `designWorkshop.unfiledReason()`; the day one of them reaches for
     * `designWorkshop.kind` is the day a lens became a column.
     */
    @Test
    fun `no record form reads the chosen type`() {
        assertFalse(
            "a record form has learned to read designWorkshop.kind. The type is a lens over the list: " +
                "the record stores designWorkshopId and nothing else.",
            withoutComments(MAIN).contains("designWorkshop.kind")
        )
    }

    /**
     * CHANGING THE TYPE CANNOT CLEAR A CHOSEN WORKSHOP, and here it is structural rather than
     * promised.
     *
     * The web's own test settles for grepping its cascade for `setWorkshopId(`, because in a
     * component that holds both pieces of state nothing stronger was available. On this client the
     * setter is a method that physically does not touch [DesignWorkshopPickerState.selectedId] — so
     * this test reads it and says so, which is the only way a later edit that adds the line gets
     * caught.
     *
     * The case is the edit one: a product filed last season under a Skill Upgradation workshop opens
     * with that workshop chosen and the type box on its default. If the type box could blank it, the
     * designer's next Save would quietly move a month of fieldwork.
     */
    @Test
    fun `the type setter cannot touch the chosen workshop`() {
        val setter = withoutComments(PICKER)
            .substringAfter("internal fun chooseKind(next: String) {")
            .substringBefore("}")
        for (forbidden in listOf("selectedId", "baselineId", "workshops")) {
            assertFalse("chooseKind assigns $forbidden; narrowing a list is not discarding an answer", setter.contains(forbidden))
        }
        assertEquals("kind = next", setter.trim())
    }

    /**
     * AND THE NARROWED READ CANNOT EITHER — the other half of the same guarantee.
     *
     * `markReading` clears the rows on a type change, deliberately, so the previous type's workshops
     * are not drawn under the new type's label. What it must never clear is the record's own link.
     */
    @Test
    fun `the narrowed read clears the rows and never the link`() {
        val marker = withoutComments(PICKER)
            .substringAfter("internal fun markReading() {")
            .substringBefore("}")
        assertTrue("markReading must clear the previous type's rows", marker.contains("workshops = emptyList()"))
        assertFalse("and must never touch the record's link", marker.contains("selectedId"))
        assertFalse("nor the memory the outbox reads", marker.contains("everListedRows"))
    }

    /**
     * A WORKSHOP THE NARROWED PAGE DOES NOT HOLD STAYS ON SCREEN AND STAYS SELECTED.
     *
     * This is the behaviour the two tests above protect, asserted from the other end: whatever the
     * rows are, the stored id keeps a row of its own at the head of the list. Without it the trigger
     * falls back to its placeholder and a filed record reads as *"Not filed under a design
     * workshop"* — the screen stating the opposite of the stored value, with the designer's obvious
     * next move being to re-file a month of fieldwork.
     */
    @Test
    fun `a chosen workshop of another type survives the narrowing`() {
        val narrowedPage = designWorkshopOptions(rows = emptyList(), offPageId = "filed-last-season", narrowed = true)

        assertEquals("filed-last-season", narrowedPage.first().value)
        assertEquals("The design workshop already on this record", narrowedPage.first().label)
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 5. EVERY MOUNT HAS IT, AND THE SIX ARE THE WEB'S SIX
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Every form that mounts the picker, spelled out rather than derived, for the reason the web's
     * own list gives: what this is protecting against is a SEVENTH form arriving with half the
     * control, and anything derived from "forms that mention the cascade" would grow to include it
     * only after somebody had already got it right there.
     *
     * The pairing is to the web file that mounts `<DesignWorkshopCascade>` for the same record type.
     */
    private val mounts = mapOf(
        "ArtisanForm" to "components/forms/ArtisanForm.tsx",
        "ProductForm" to "components/forms/ProductForm.tsx",
        "ToolForm" to "components/forms/ToolForm.tsx",
        "ProcessForm" to "components/forms/ProcessForm.tsx",
        "AndroidMediaForm" to "app/(protected)/media/page.tsx",
        "QuestionnaireForm" to "app/(protected)/questionnaire/page.tsx",
    )

    @Test
    fun `the cascade is on exactly the six forms the browser puts it on`() {
        assertEquals(
            "the forms mounting DesignWorkshopField are no longer the six that pair with the web's " +
                "cascade mounts. A seventh form with the picker, or a sixth without it, is the " +
                "tracer bug again — the odd one out reads as the feature being absent rather than " +
                "as one screen differing.",
            mounts.keys.toList().sorted(),
            enclosingFunctionsCalling("DesignWorkshopField(").sorted()
        )
        // And the state each of them holds comes from the same loader, on the same six.
        assertEquals(
            mounts.keys.toList().sorted(),
            enclosingFunctionsCalling("rememberDesignWorkshopPicker(").sorted()
        )
    }

    @Test
    fun `each handset mount has a browser mount that still carries the cascade`() {
        for ((form, web) in mounts) {
            val source = repoFile("../frontend/$web", "frontend/$web").readText(Charsets.UTF_8)
            assertTrue(
                "$web is $form's browser twin and no longer mounts <DesignWorkshopCascade>",
                Regex("""<DesignWorkshopCascade[\s/>]""").containsMatchIn(source)
            )
            assertFalse(
                "$web still mounts the bare <DesignWorkshopSelect>, so $form's twin has lost its type box",
                Regex("""<DesignWorkshopSelect[\s/>]""").containsMatchIn(source)
            )
        }
    }

    /**
     * NO FORM BUILDS A TYPE BOX OF ITS OWN.
     *
     * The whole argument for putting the type inside [DesignWorkshopPickerState] rather than beside
     * the field at each call site is that a decision with one right answer and five wrong ones should
     * be made once. A form that grew its own "Type of workshop" control would be making it a seventh
     * time, with its own default, its own idea of what a type change does to a chosen workshop, and
     * nothing at all forcing the two to agree.
     */
    @Test
    fun `no record form grows a type box of its own`() {
        assertFalse(
            "MainActivity.kt has grown its own \"Type of workshop\" control. The cascade is one " +
                "control drawn by DesignWorkshopField; a second copy is six copies waiting to happen.",
            withoutComments(MAIN).contains("\"Type of workshop\"")
        )
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // 6. A LENS MUST NOT CHANGE WHAT THE OUTBOX RECORDS — this client's rule, with no web twin
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * THE TWO ANSWERS ARE GENUINELY DIFFERENT, so what feeds them matters.
     *
     * `unfiledLinkReason` decides which sentence the outbox drain prints about a record that went up
     * filed under nothing, and the whole point of that sentence is to let a record MISSING from a
     * workshop's lists be told apart from one deliberately filed under none. This test states the
     * difference so the one below has something to protect.
     */
    @Test
    fun `the outbox tells a deliberate clearance from an empty picker`() {
        assertEquals(UNFILED_BY_CHOICE, unfiledLinkReason(selectedId = "", baselineId = "", hadOptions = true))
        assertEquals(UNFILED_NO_OPTIONS, unfiledLinkReason(selectedId = "", baselineId = "", hadOptions = false))
    }

    /**
     * AND `hadOptions` IS ASKED OF THE UNNARROWED HISTORY, NOT OF THIS MOMENT'S ROWS.
     *
     * `workshops.isNotEmpty()` was exact while the rows could only grow — the list was read once and
     * `markFailed` keeps what it has. A type filter breaks that: a designer on nine design workshops
     * who taps a type that happens to have none leaves the state with an empty `workshops`, and a
     * record they simply never filed would then be queued as UNFILED_NO_OPTIONS — *"there was nothing
     * to pick"* — when there were nine and they did not pick one. That is a lens changing what the
     * outbox RECORDS about why a link is absent, which is the class of silent damage `Offline.kt` and
     * `OutboxUnfiledSentinelTest` were written to close.
     */
    @Test
    fun `the unfiled reason is read off the sticky memory and not off the narrowed rows`() {
        val body = withoutComments(PICKER)
            .substringAfter("fun unfiledReason(): String? = unfiledLinkReason(")
            .substringBefore(")")
        assertTrue(
            "unfiledReason no longer reads everListedRows. Under a type filter, `workshops` is this " +
                "type's rows and not 'was anything ever on offer' — the question being asked.",
            body.contains("hadOptions = everListedRows")
        )
        assertFalse(
            "and it must not go back to the live rows",
            body.contains("workshops.isNotEmpty()")
        )
        // The flag has to actually be sticky, or reading it changes nothing.
        val listed = withoutComments(PICKER)
            .substringAfter("internal fun markListed(page: DesignWorkshopPageDto) {")
            .substringBefore("\n    }")
        assertTrue(
            "markListed no longer records that rows were once offered",
            listed.contains("if (page.items.isNotEmpty()) everListedRows = true")
        )
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * A file of this repository, found by walking up from wherever the test runner started.
     *
     * The working directory of a Gradle test worker is not something to depend on, and a test that
     * skipped when it could not find its subject would prove nothing on the day somebody moves it.
     * Missing is a failure, loudly. Same helper and same reasoning as `DashboardTileParityTest` and
     * `RosterFilterWireTest` — and the `..`-prefixed candidates are what let it reach OUT of
     * `android/` and into `frontend/`, which is what every parity assertion here depends on.
     */
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

    private val PICKER: String by lazy { androidFile("ui/DesignWorkshopPicker.kt").readText(Charsets.UTF_8).replace("\r\n", "\n") }
    private val OPTIONS: String by lazy { androidFile("ui/WorkshopOptions.kt").readText(Charsets.UTF_8).replace("\r\n", "\n") }
    private val MAIN: String by lazy { androidFile("MainActivity.kt").readText(Charsets.UTF_8).replace("\r\n", "\n") }

    /**
     * The six `WORKSHOP_KIND` tokens the APK actually ships, off the bundled asset.
     *
     * Parsed rather than restated, because a list copied into a test is a list that agrees with
     * itself and with nothing else. This is the file `StageSchemaStore.readAsset` opens, so it is
     * what a handset with no signal genuinely offers.
     */
    private val SHIPPED_KINDS: List<String> by lazy {
        val asset = repoFile(
            "app/src/main/assets/design-workshop-schema.json",
            "android/app/src/main/assets/design-workshop-schema.json",
        ).readText(Charsets.UTF_8)
        Json { ignoreUnknownKeys = true; isLenient = true }
            .parseToJsonElement(asset)
            .jsonObject["enums"]!!
            .jsonObject["WORKSHOP_KIND"]!!
            .jsonArray
            .map { it.jsonObject["value"]!!.jsonPrimitive.content }
    }

    /**
     * The TOP-LEVEL functions of `MainActivity.kt` that contain a call to [needle].
     *
     * Anchored at column zero on purpose. A naive "nearest preceding `fun`" answers with whatever
     * local helper — `submit`, `formSignature`, `refreshMedia` — happens to be declared last inside
     * the composable, which is a different question and a wrong answer that looks plausible enough
     * to be believed.
     */
    private fun enclosingFunctionsCalling(needle: String): List<String> {
        val source = withoutComments(MAIN)
        val declarations = Regex("""(?m)^(?:private\s+|internal\s+)?fun\s+([A-Za-z0-9_]+)\s*\(""")
            .findAll(source)
            .map { it.range.first to it.groupValues[1] }
            .toList()
        val found = mutableListOf<String>()
        var from = source.indexOf(needle)
        while (from >= 0) {
            val owner = declarations.lastOrNull { it.first < from }
                ?: throw AssertionError("a call to $needle sits above every top-level fun")
            if (owner.second !in found) found += owner.second
            from = source.indexOf(needle, from + needle.length)
        }
        assertTrue("no call to $needle found in MainActivity.kt at all", found.isNotEmpty())
        return found
    }

    /**
     * Drop line and block comments so a source sweep reads CODE — keeping every string literal.
     *
     * Every rule swept for here is also EXPLAINED by a comment that names the thing it forbids —
     * "designWorkshop.kind", "Type of workshop", "workshops.isNotEmpty()" — so a sweep over the raw
     * text would fail on its own documentation. Kotlin block comments NEST, unlike Java's, and
     * getting that backwards ends a comment early and drags real code into the swept text.
     *
     * ── WHY IT UNDERSTANDS STRING LITERALS, WHICH THE OTHER TWO READERS IN THIS SUITE DO NOT ───
     *
     * `RosterFilterWireTest` and `DashboardTileParityTest` both carry the cheap version, which walks
     * the text looking for a comment opener and nothing else. On `MainActivity.kt` that reader is not
     * merely imprecise, it is WRONG BY A THIRD OF THE FILE: the media pickers hold the WILDCARD MIME
     * LITERALS — the image one, the audio one, and the bare any-type one, which is star slash star —
     * and the slash-then-star sitting in the middle of that last one reads to a naive scanner as a
     * block-comment OPENER, from which it runs forward hunting a close that belongs to some other
     * comment hundreds of lines away.
     *
     * Measured on this tree the cheap reader took 1,116,328 characters down to 349,796 and left FIVE
     * of the six `DesignWorkshopField(` mounts inside what it believed were comments — so the mount
     * sweep found one form, and would have reported the cascade as missing from five screens it is
     * on. A test wrong in that direction is worse than no test: it fails loudly for a reason that is
     * not true, and the fix somebody reaches for is to delete the assertion.
     *
     * (This very comment is the same trap one layer up, which is why it spells that MIME type out in
     * words: a KDoc containing those two characters ENDS THERE, and the compiler's report is an
     * unclosed comment two hundred lines further down.)
     *
     * So this reader steps over literals rather than into them: the triple-quoted raw form first — a
     * lone quote inside one is not a terminator — then the ordinary single- and double-quoted forms,
     * honouring the backslash escape. Their CONTENTS are copied through, because the sweep for a
     * literal control label wants to find it.
     *
     * The other two files are left alone deliberately: they read a different span of source and
     * neither is failing today, and a drive-by fix to a helper in a test this change did not touch is
     * how one change becomes three.
     */
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
                        // A backslash escapes the next character, INCLUDING a closing quote. Without
                        // this, `"\""` ends the literal one character early and everything after it
                        // is read as code that is really text.
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
