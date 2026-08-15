package com.designprototype.workshop.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **THE LAYERING LAW, ASSERTED ON THE DEVICE SIDE BEFORE ANY DEVICE CAN BREAK IT.**
 *
 * Five rules, five groups of assertions, and two of them read this repository's own source rather than
 * calling a function — because "no path names a stage entry as writable" and "neither money gate is
 * wired" are properties of the FILE, and a test that only exercised the happy path would go on passing
 * the day somebody imported a consent check into it.
 */
class DwTier2LayerTest {

    private val provenance = DwTier2Provenance(
        provider = "on-device (LiteRT-LM)",
        modelId = "gemma-4-E2B-it.litertlm",
        modelVersion = "litertlm-android 0.16.0",
        language = "hi-IN",
        producedAtIso = "2026-08-13T03:10:00+05:30",
    )

    private val draft = DwTier2Draft(
        verb = DwTier2Verb.PROOFREAD,
        source = DwTier2Source.Layer("layer_abc"),
        text = "The warp was set on a pit loom.",
        provenance = provenance,
    )

    /** This file's own source, for the two rules that are properties of the code. */
    private fun layerSource(): String =
        File("src/main/java/com/designprototype/workshop/data/DwTier2Layer.kt").readText()

    /**
     * The same source with its comment lines dropped. **THE ASSERTIONS BELOW READ THIS, NOT THE RAW
     * TEXT, AND THE FIRST RUN OF THIS FILE IS WHY.**
     *
     * The stage-entry rule asserted that the string `DwStageEntry` appears nowhere in
     * `DwTier2Layer.kt` — and that file's own header explains the rule by naming the type, in a KDoc
     * link. So the test failed on the paragraph that states the thing it is checking, which is the
     * crudest possible false positive: it would have been "fixed" either by weakening the rule or by
     * deleting the explanation, and the explanation is load-bearing here.
     *
     * Dropping comment lines keeps the rule exactly as strong where it matters — no declaration,
     * import, parameter or call may name it — and lets the prose say what the code may not do. It is
     * the same split `docs/DEVICE-TIER-MEASUREMENT.md` uses for its own greps
     * (`grep -v "^[^:]*:[0-9]*: *\*"`), for the same reason. **Imports survive the filter**, which
     * matters: an import is code, and the import assertions below depend on seeing it.
     */
    private fun layerCode(): String = layerSource()
        .lines()
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    // -----------------------------------------------------------------------------------------
    // Rule 1 and 5: a row, never an edit, and nothing addresses a designer's own words
    // -----------------------------------------------------------------------------------------

    @Test
    fun `nothing in the device layer contract can address a stage entry`() {
        /*
         * `DwStageEntry` IS THE POSTGRES TABLE, NOT A KOTLIN TYPE, and the first version of this test
         * forbade only that name. Checked: the string occurs in the whole of `android/app/src` five
         * times, every one of them a comment or this test's own message, because **there is no Kotlin
         * declaration called `DwStageEntry`** — the server's Prisma model is what carries that name
         * (`dictation_consent.STAGE_TABLE = "DwStageEntry"`). So the rule was spelled in a vocabulary
         * no Kotlin code could ever use, and `dwTier2Apply(entry: StageEntryBody)` — the actual shape
         * of the defect — would have passed it.
         *
         * The names below are what a device-side path to a designer's own words is really spelled
         * with, each verified to exist by `every symbol the gate rule forbids…`.
         */
        STAGE_ENTRY_SYMBOLS.forEach { symbol ->
            assertFalse(
                "“$symbol” is how a designer's own writing is addressed on this device. A Tier 2 " +
                    "output is a row BESIDE it, so no declaration, parameter or call in this file " +
                    "may name it as a target — the moment one does, somebody wires an “apply the " +
                    "proofread” button and model prose replaces an artisan's words. (Comments may " +
                    "name it; see layerCode.)",
                layerCode().contains(symbol)
            )
        }
        // AND THE BODY HAS NO SHAPE THAT COULD ADDRESS ONE. Not the same assertion: a body carrying a
        // field key would let a server apply the text even with no mention of the Kotlin type here.
        val body = dwTier2LayerBody(draft)
        listOf("fieldKey", "stageKey", "entryId", "value", "apply", "replace").forEach { forbidden ->
            assertFalse(
                "a layer body may not carry “$forbidden” — that is the vocabulary of an edit",
                body.keys.any { it.equals(forbidden, ignoreCase = true) }
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Rule 2: provenance is mandatory, and the tier is not a field
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a run that cannot say what produced it cannot be constructed`() {
        listOf(
            { provenance.copy(provider = "") },
            { provenance.copy(modelId = " ") },
            { provenance.copy(modelVersion = "") },
            { provenance.copy(language = "") },
            { provenance.copy(producedAtIso = "") },
        ).forEach { build ->
            val thrown = runCatching { build() }.exceptionOrNull()
            assertTrue(
                "every provenance field is required, with no default: got $thrown",
                thrown is IllegalArgumentException
            )
        }
        // The one legitimate way to say nothing is to say it in a word that shows up in a diff.
        assertEquals("UNRECORDED", DW_TIER2_UNRECORDED)
        DwTier2Provenance(
            provider = DW_TIER2_UNRECORDED,
            modelId = "gemma-4-E2B-it.litertlm",
            modelVersion = DW_TIER2_UNRECORDED,
            language = "multi",
            producedAtIso = "2026-08-13T03:10:00+05:30",
        )
    }

    @Test
    fun `the body carries the provenance and lets the route fix the tier`() {
        val body = dwTier2LayerBody(draft)
        assertEquals("PROOFREAD", body["kind"])
        assertEquals("The warp was set on a pit loom.", body["text"])
        assertEquals("on-device (LiteRT-LM)", body["provider"])
        assertEquals("gemma-4-E2B-it.litertlm", body["modelId"])
        assertEquals("litertlm-android 0.16.0", body["modelVersion"])
        assertEquals("hi-IN", body["language"])
        assertEquals("2026-08-13T03:10:00+05:30", body["producedAt"])
        assertEquals("layer_abc", body["sourceLayerId"])

        /*
         * NO `tier` IN THE BODY, AND THIS IS THE ASSERTION THAT KEEPS THE PROVENANCE COLUMN WORTH
         * READING. `AiLayerRegisterIn`'s own docstring: a body that could claim TIER_1 for cloud
         * output "would make the tier column — the one thing that lets a reviewer tell a
         * phone-produced transcript from a cloud-produced one — worth nothing". The five existing
         * verb routes fix TIER_3 from a module constant; a device route fixes TIER_2 the same way.
         */
        assertFalse(body.keys.any { it.equals("tier", ignoreCase = true) })
        assertEquals("but the tier of an on-device run is not in doubt", "TIER_2", DW_TIER2_TIER)
    }

    @Test
    fun `one source, and the shape of it decides which key travels`() {
        assertEquals(
            "media_1",
            dwTier2LayerBody(
                draft.copy(verb = DwTier2Verb.CAPTION, source = DwTier2Source.Media("media_1"))
            )["sourceMediaId"]
        )
        val supplied = dwTier2LayerBody(
            draft.copy(verb = DwTier2Verb.EXPANDED, source = DwTier2Source.SuppliedText("terse note"))
        )
        /*
         * `sourceText`, NOT `suppliedText`. The three source keys are named after the three Postgres
         * columns the `DwAiLayer_source_is_exactly_one` CHECK counts — `sourceMediaId`,
         * `sourceLayerId`, `sourceText` — so the migration and this file use one vocabulary. The
         * string `suppliedText` appears nowhere in `backend/`; what exists there is the internal
         * factory `ai_layers.LayerSource.supplied_text()`, which is a Python method and not a wire key.
         */
        assertEquals("terse note", supplied["sourceText"])
        assertFalse(
            "suppliedText is not a name anything in this system uses on the wire",
            supplied.keys.any { it.equals("suppliedText", ignoreCase = true) }
        )
        // Exactly one source key, ever. Two would let a reviewer read the wrong evidence rung.
        listOf(supplied, dwTier2LayerBody(draft)).forEach { body ->
            assertEquals(
                1,
                body.keys.count { it in setOf("sourceLayerId", "sourceMediaId", "sourceText") }
            )
        }
        /*
         * THE TWO PROSE KEYS, PINNED TOGETHER, BECAUSE THEY ARE THE COLLISION. `text` is what the
         * model WROTE; on the five verb routes that exist today `text` is the passage a verb is run
         * OVER. A route that confused them would run the server's own Tier 3 chain over Gemma's output
         * and charge `ai_verb_cap` for a feature that is supposed to spend nothing. See
         * `dwTier2LayerBody`'s KDoc. If either name changes, that argument needs re-reading.
         */
        assertEquals("the model's own words", "The warp was set on a pit loom.", supplied["text"])
        assertEquals("and the words it was given", "terse note", supplied["sourceText"])
    }

    // -----------------------------------------------------------------------------------------
    // Rule 3: inert until a person accepts it
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a device cannot post an already-accepted row`() {
        val body = dwTier2LayerBody(draft)
        listOf("accepted", "acceptedAt", "acceptedById", "approved").forEach { forbidden ->
            assertFalse(
                "acceptance is a separate act by a person. A device that could post an accepted row " +
                    "could put model prose into a report with nobody reading it.",
                body.keys.any { it.equals(forbidden, ignoreCase = true) }
            )
        }
        assertFalse(
            "and nothing in the draft type can express it either",
            layerCode().contains("acceptedAt")
        )
    }

    // -----------------------------------------------------------------------------------------
    // Rule 4: neither money gate is wired, and the file may not so much as mention them
    // -----------------------------------------------------------------------------------------

    @Test
    fun `no consent check and no daily cap stands in front of a model that runs on this phone`() {
        // CODE, NOT PROSE — the file's own argument for NOT wiring these has to be able to name them,
        // and DW_TIER2_NO_GATES_NOTE is that argument. What may not appear is a call or a reference,
        // and GATE_SYMBOLS is the list of things such a reference would be spelled with.
        val source = layerCode()
        GATE_SYMBOLS.forEach { symbol ->
            assertFalse(
                "an on-device run sends nothing off the phone and spends nothing at a provider, so " +
                    "“$symbol” has no business in this path — the cap is scoped by explicit " +
                    "instruction to the paid providers, and the consent gate exists because a " +
                    "recording LEAVES the handset",
                source.contains(symbol)
            )
        }
        assertTrue(
            "the reason is stated once, where a surface can print it",
            DW_TIER2_NO_GATES_NOTE.contains("sends nothing off it")
        )
    }

    /**
     * **THE TEST OF THE TEST ABOVE, AND IT EXISTS BECAUSE THE FIRST VERSION OF THAT TEST WAS
     * VACUOUS.**
     *
     * It forbade five spellings — `dwDictationConsent(`, `DwDictationConsentState`,
     * `dwDictationAllowance(`, `DwDictationAllowance(`, `dwCapRemaining` — and **four of the five
     * exist nowhere in this repository.** The consent enum is [DwTier3Consent], the reader is
     * [dwTier3ConsentOf], the allowance reader is [dwDictationAllowanceOf] and the cap reader is
     * [dwDictationCapView]; not one of those was on the list. The fifth, `DwDictationAllowance(`,
     * only ever matches a CONSTRUCTOR call, so naming the type in a parameter — which is how a gate
     * is actually plumbed — slipped past it too.
     *
     * It also asserted that neither module was IMPORTED. `DwDictationConsent.kt`,
     * `DwDictationAllowance.kt` and `DwTier2Layer.kt` are all in
     * `com.designprototype.workshop.data`, so **a same-package reference needs no import and that
     * assertion could never fire.** Verified by wiring a working gate into `DwTier2Layer.kt` —
     * `dwTier3ConsentOf(token) != GRANTED` and `dwDictationCapView(...).spent`, exactly the
     * regression the rule is for — and watching all nine tests in this class pass.
     *
     * So this test pins the mechanism rather than the outcome: every name in [GATE_SYMBOLS] must
     * still be a real symbol somewhere in the app's own sources. Rename one and this goes red,
     * pointing at the list, instead of the rule quietly ceasing to check anything.
     */
    @Test
    fun `every symbol the gate rule forbids is a symbol that really exists`() {
        val mainSources = File("src/main/java/com/designprototype/workshop")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "DwTier2Layer.kt" }
            .map { it.readText() }
            .toList()
        assertTrue("no sources were read at all", mainSources.size > 50)
        (GATE_SYMBOLS + STAGE_ENTRY_SYMBOLS_THAT_ARE_KOTLIN).forEach { symbol ->
            assertTrue(
                "“$symbol” is forbidden in DwTier2Layer.kt but no longer exists anywhere in the " +
                    "app, so forbidding it protects nothing. Something was renamed: update the " +
                    "list to the new name rather than deleting the entry.",
                mainSources.any { it.contains(symbol) }
            )
        }
    }

    private companion object {
        /**
         * **THE REAL NAMES OF THE TWO MONEY GATES.** Every one verified by
         * `every symbol the gate rule forbids is a symbol that really exists` to still exist.
         *
         * Deliberately NOT `dwModelNeedsConsent`, which is a different thing wearing the same word:
         * that is the one confirmation a designer taps before installing a model this handset calls
         * TIGHT, it spends nothing and discloses nothing, and forbidding it here would forbid the
         * install flow Tier 2 is supposed to have.
         */
        val GATE_SYMBOLS: List<String> = listOf(
            // The Tier 3 send-consent gate: an artisan's recording leaving the handset.
            "DwTier3Consent",
            "dwTier3ConsentOf",
            "dwTier3ConsentToken",
            "dwConsentStateSentence",
            "dwResolveDictationConsent",
            "dwRecordDictationConsent",
            "DwDictationConsentRefused",
            "DwDictationConsentRow",
            // The daily ceiling on provider spend.
            "DwDictationAllowance",
            "dwDictationAllowanceOf",
            "DwDictationAllowanceStore",
            "dwDictationCapView",
            "DwDictationCapView",
            "DwDictationCapRefused",
            "dwDictationCapSpentRecord",
        )

        /**
         * **HOW A DESIGNER'S OWN WRITING IS ADDRESSED ON THIS DEVICE.** None of these may appear in
         * `DwTier2Layer.kt`'s code.
         *
         * `DwStageEntry` is kept at the end and is the server's Prisma model name rather than a
         * Kotlin declaration — it cannot be constructed here, which is why it needed the four above
         * it. It stays because it is what the row is called everywhere else in this system, and a
         * developer reaching for it by that name should still trip.
         */
        val STAGE_ENTRY_SYMBOLS: List<String> = listOf(
            // The wire body one stage entry is PUT as — the shape an "apply this" call would take.
            "StageEntryBody",
            // One entry as it was last sent, used by the refusal decoder.
            "DwSentEntry",
            // Where the designer's typed words live on the handset before they are sent.
            "WorkshopDraftStore",
            // One stage as the server describes it, entries and all.
            "StageDto",
            // The Postgres table. Not a Kotlin type; see the KDoc above.
            "DwStageEntry",
        )

        /** The subset of [STAGE_ENTRY_SYMBOLS] that is real Kotlin, for the existence guard. */
        val STAGE_ENTRY_SYMBOLS_THAT_ARE_KOTLIN: List<String> =
            STAGE_ENTRY_SYMBOLS - "DwStageEntry"
    }

    // -----------------------------------------------------------------------------------------
    // The verbs that are real, and the one that is not
    // -----------------------------------------------------------------------------------------

    @Test
    fun `subtitles is not a device verb, because nothing on the phone returns a timing`() {
        assertEquals(
            setOf("PROOFREAD", "EXPANDED", "TRANSLATION", "CAPTION"),
            DwTier2Verb.entries.map { it.name }.toSet()
        )
        assertFalse(
            "ai_verbs.subtitle needs timed fragments and fit_cues needs them to place a cue; the " +
                "LiteRT-LM API returns a timing for nothing. Inventing one is the fabrication this " +
                "repository has a document about.",
            DwTier2Verb.entries.any { it.name == "SUBTITLES" }
        )
    }

    // -----------------------------------------------------------------------------------------
    // What actually stops this working today
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the blocker today is the runtime, and behind it a route nobody has written`() {
        assertEquals(
            DwTier2WriteBlocker.NO_RUNTIME_IN_THIS_BUILD,
            dwTier2WriteBlocker()
        )
        assertEquals(
            "with a runtime there is still nowhere to send it — every verb route on the server fixes " +
                "TIER_3 and the registration body refuses a text field",
            DwTier2WriteBlocker.NO_ROUTE_THAT_ACCEPTS_A_DEVICE_LAYER,
            dwTier2WriteBlocker(runtimePresent = true)
        )
        assertNull(dwTier2WriteBlocker(runtimePresent = true, routeExists = true))
        assertFalse(DW_TIER2_DEVICE_LAYER_ROUTE_EXISTS)
        assertTrue(
            "and the sentence says whose problem it is, so nobody reads it as the phone's fault",
            DW_TIER2_NO_WRITE_PATH_SENTENCE.contains("not a limitation of the phone")
        )
    }

    @Test
    fun `a layer with no words in it is not a layer`() {
        val thrown = runCatching { draft.copy(text = "   ") }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }
}
