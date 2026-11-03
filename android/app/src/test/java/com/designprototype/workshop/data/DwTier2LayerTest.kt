package com.designprototype.workshop.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun layerSource(): String =
        File("src/main/java/com/designprototype/workshop/data/DwTier2Layer.kt").readText()

    private fun layerCode(): String = layerSource()
        .lines()
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    @Test
    fun `nothing in the device layer contract can address a stage entry`() {
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
        val body = dwTier2LayerBody(draft)
        listOf("fieldKey", "stageKey", "entryId", "value", "apply", "replace").forEach { forbidden ->
            assertFalse(
                "a layer body may not carry “$forbidden” — that is the vocabulary of an edit",
                body.keys.any { it.equals(forbidden, ignoreCase = true) }
            )
        }
    }

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
        assertEquals("terse note", supplied["sourceText"])
        assertFalse(
            "suppliedText is not a name anything in this system uses on the wire",
            supplied.keys.any { it.equals("suppliedText", ignoreCase = true) }
        )
        listOf(supplied, dwTier2LayerBody(draft)).forEach { body ->
            assertEquals(
                1,
                body.keys.count { it in setOf("sourceLayerId", "sourceMediaId", "sourceText") }
            )
        }
        assertEquals("the model's own words", "The warp was set on a pit loom.", supplied["text"])
        assertEquals("and the words it was given", "terse note", supplied["sourceText"])
    }

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

    @Test
    fun `no consent check and no daily cap stands in front of a model that runs on this phone`() {
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
        val GATE_SYMBOLS: List<String> = listOf(
            "DwTier3Consent",
            "dwTier3ConsentOf",
            "dwTier3ConsentToken",
            "dwConsentStateSentence",
            "dwResolveDictationConsent",
            "dwRecordDictationConsent",
            "DwDictationConsentRefused",
            "DwDictationConsentRow",
            "DwDictationAllowance",
            "dwDictationAllowanceOf",
            "DwDictationAllowanceStore",
            "dwDictationCapView",
            "DwDictationCapView",
            "DwDictationCapRefused",
            "dwDictationCapSpentRecord",
        )

        val STAGE_ENTRY_SYMBOLS: List<String> = listOf(
            "StageEntryBody",
            "DwSentEntry",
            "WorkshopDraftStore",
            "StageDto",
            "DwStageEntry",
        )

        val STAGE_ENTRY_SYMBOLS_THAT_ARE_KOTLIN: List<String> =
            STAGE_ENTRY_SYMBOLS - "DwStageEntry"
    }

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

    @Test
    fun `nothing blocks a device layer now, because the runtime is in the APK and the route exists`() {
        assertNull(dwTier2WriteBlocker())
        assertTrue(DW_TIER2_DEVICE_LAYER_ROUTE_EXISTS)
        assertEquals("on-device", DW_TIER2_LAYER_ROUTE_SEGMENT)
        assertEquals(DwTier2WriteBlocker.NO_RUNTIME_IN_THIS_BUILD, dwTier2WriteBlocker(runtimePresent = false))
        assertEquals(
            DwTier2WriteBlocker.NO_ROUTE_THAT_ACCEPTS_A_DEVICE_LAYER,
            dwTier2WriteBlocker(runtimePresent = true, routeExists = false)
        )
    }

    @Test
    fun `a supplied-text run sends exactly the keys AiOnDeviceLayerIn declares`() {
        val body = dwTier2LayerBody(
            draft.copy(
                source = DwTier2Source.SuppliedText("the dabu paist"),
                provenance = provenance.copy(provider = DW_TIER2_PROVIDER, modelVersion = DW_TIER2_RUNTIME_VERSION),
            )
        )
        assertEquals(
            setOf("kind", "text", "provider", "modelId", "modelVersion", "language", "producedAt", "sourceText"),
            body.keys
        )
        assertEquals("litert-lm", body["provider"])
    }

    @Test
    fun `the runtime version recorded on a layer is the AAR the build names`() {
        val gradle = java.io.File("build.gradle.kts").readText()
        val version = DW_TIER2_RUNTIME_VERSION.removePrefix("litertlm-android ")
        assertTrue(
            "app/build.gradle.kts must depend on litertlm-android:$version, the version every layer records",
            gradle.contains("com.google.ai.edge.litertlm:litertlm-android:$version")
        )
    }

    @Test
    fun `a layer with no words in it is not a layer`() {
        val thrown = runCatching { draft.copy(text = "   ") }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }
}
