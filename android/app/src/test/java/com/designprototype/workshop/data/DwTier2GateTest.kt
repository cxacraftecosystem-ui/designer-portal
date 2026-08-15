package com.designprototype.workshop.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **THE ONE THING THAT KEEPS A 2.6 GB DOWNLOAD OFF THE FLEET TODAY, PINNED — BECAUSE IT IS CURRENTLY
 * AN OMISSION AND NOT A GATE.**
 *
 * ── WHAT WAS FOUND, BY RUNNING THE REAL ARITHMETIC RATHER THAN READING IT ──────────────────────
 *
 * `dwRecommendTiers` was run over five handsets × three connections against the shipped
 * [DW_TIER2_CATALOGUE]. The tier-level gate holds everywhere — `tier2` is
 * `None(NO_RUNTIME_IN_THIS_BUILD)` on every device, so `dwTierDownloadMayBeOffered` is false. **But
 * the PER-CHOICE gate does not:**
 *
 * ```
 *   fleet SM-M325F  METERED    gemma-4-E2B-it.litertlm  TIGHT        dwModelDownloadMayBeOffered=TRUE
 *   roomy 12 GB     UNMETERED  gemma-4-E4B-it.litertlm  COMFORTABLE  dwModelDownloadMayBeOffered=TRUE
 *   Go edition 2 GB METERED    gemma-4-E2B-it.litertlm  TIGHT        dwModelDownloadMayBeOffered=TRUE
 * ```
 *
 * [dwModelDownloadMayBeOffered] is `choice.fit.mayInstall && connection != NONE` — it knows nothing
 * about a runtime, and it is the gate `DwModelChoiceList` uses to decide whether to draw "Install this
 * model". The Tier-2-aware gate that ANDs the runtime in, [dwTier2InstallMayBeOffered], **has no
 * production caller at all** — only tests. Verified by grep over `src/main`.
 *
 * So the only reason no handset is offered a multi-gigabyte fetch for a file nothing in this build can
 * open is that `DwTier2ModelList` **draws no install control and takes no `onInstall` parameter**.
 * That is a true and deliberate omission, argued in that file's header. It is also one keyword away
 * from being untrue, and nothing was asserting it. This file asserts it.
 *
 * ── WHY A SOURCE-READING TEST AND NOT A CALL ──────────────────────────────────────────────────
 *
 * The property is "no composable draws this control", and a composable cannot be invoked from a
 * desktop JVM test without Robolectric or an instrumented run. What CAN be checked without either is
 * the shape of the declaration and the shape of the call site, which is where the mistake would be
 * made. Same technique, and same reason, as `DwTier2LayerTest`'s source assertions.
 */
class DwTier2GateTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("$path is not where this test expects it", file.isFile)
        return file.readText()
    }

    /** Comment lines dropped, so prose may explain a rule the code may not break. */
    private fun code(path: String): String = source(path)
        .lines()
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    // -----------------------------------------------------------------------------------------
    // The arithmetic, stated as it really is rather than as it is hoped to be
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the general download gate says yes to a Tier 2 row, and only the runtime gate says no`() {
        /*
         * THIS TEST DOCUMENTS A HAZARD RATHER THAN A GUARANTEE, and it is written the honest way
         * round: it asserts that `dwModelDownloadMayBeOffered` DOES answer true, so that the day
         * somebody makes it runtime-aware this test fails and points at the two gates that then agree.
         * Asserting "it says no" would have been the comfortable version and would have been false.
         */
        val roomy = DwDeviceMeasurement(
            totalRamBytes = 12L * 1024 * 1024 * 1024,
            availableRamBytes = 8L * 1024 * 1024 * 1024,
            lowRamDevice = false,
            freeStorageBytes = 90L * 1024 * 1024 * 1024,
            abis = listOf("arm64-v8a"),
        )
        val choices = dwModelChoices(DW_TIER2_CATALOGUE, roomy, tier = DwAiTier.TIER_2)
        assertTrue("the shipped catalogue has rows to judge", choices.isNotEmpty())
        choices.forEach { choice ->
            assertEquals(
                "${choice.plan.modelId}: a 12 GB phone is comfortable with every Tier 2 row",
                DwModelFit.COMFORTABLE,
                choice.fit
            )
            assertTrue(
                "${choice.plan.modelId}: the general gate is fit-and-connection only — it does not " +
                    "know this build has no runtime, which is exactly why the Tier 2 surface must " +
                    "not use it",
                dwModelDownloadMayBeOffered(choice, DwConnection.UNMETERED)
            )
            assertFalse(
                "${choice.plan.modelId}: and the Tier 2 gate is the one that refuses",
                dwTier2InstallMayBeOffered(choice, DwConnection.UNMETERED)
            )
        }
        assertFalse("if this ever becomes true, revisit every assertion here", DW_TIER2_RUNTIME_PRESENT)
    }

    // -----------------------------------------------------------------------------------------
    // The omission that is doing the actual work
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the Tier 2 list cannot install anything, because it has no way to be told how`() {
        val ui = code("src/main/java/com/designprototype/workshop/ui/designworkshop/DwTier2ModelUi.kt")
        listOf("onInstall", "onDownload", "onFetch", "Button", "clickable").forEach { control ->
            assertFalse(
                "DwTier2ModelUi.kt gained “$control”. That file is the only reason no handset is " +
                    "offered a 2.6 GB fetch for a file this build cannot open: it draws rows and no " +
                    "action. If an install control is genuinely wanted, route it through " +
                    "dwTier2InstallMayBeOffered — which ANDs DW_TIER2_RUNTIME_PRESENT — and never " +
                    "through dwModelDownloadMayBeOffered, which does not.",
                ui.contains(control)
            )
        }
    }

    @Test
    fun `no screen hands the Tier 2 rows to the list that can install them`() {
        val screen = code("src/main/java/com/designprototype/workshop/ui/SpeechAndAiScreen.kt")
        /*
         * `DwModelChoiceList` draws "Install this model" whenever `onInstall != null &&
         * dwModelDownloadMayBeOffered(choice, connection)`. Both halves are satisfiable for a Tier 2
         * row on a real phone — see the first test — so the whole of the protection is that
         * `tier2Choices` is never the list it is handed.
         */
        val choiceListCalls = Regex("""DwModelChoiceList\((?:[^()]|\([^()]*\))*\)""")
            .findAll(screen)
            .map { it.value }
            .toList()
        assertTrue("SpeechAndAiScreen no longer calls DwModelChoiceList", choiceListCalls.isNotEmpty())
        choiceListCalls.forEach { call ->
            assertFalse(
                "the Tier 2 rows were handed to DwModelChoiceList, which can draw an install " +
                    "control for them: $call",
                call.contains("tier2Choices")
            )
        }
        // And the Tier 2 rows do reach a list — this must not pass by the rows being dropped.
        assertTrue(
            "the Tier 2 rows are no longer drawn at all, which is a different bug",
            screen.contains("DwTier2ModelList")
        )
    }

    @Test
    fun `the runtime-aware gate is the one a future install control must use`() {
        /*
         * Today `dwTier2InstallMayBeOffered` has NO caller in src/main — it is a gate nobody stands
         * behind, and `DwDeviceTier.kt`'s own comment nonetheless calls it "the gate that says so".
         * This test does not demand a caller: adding one for its own sake would mean drawing a
         * control. It pins the property that makes the function worth keeping — that it refuses on
         * the runtime alone, whatever the fit and whatever the connection.
         */
        val roomy = DwDeviceMeasurement(
            totalRamBytes = 16L * 1024 * 1024 * 1024,
            availableRamBytes = 12L * 1024 * 1024 * 1024,
            lowRamDevice = false,
            freeStorageBytes = 200L * 1024 * 1024 * 1024,
            abis = listOf("arm64-v8a"),
        )
        dwModelChoices(DW_TIER2_CATALOGUE, roomy, tier = DwAiTier.TIER_2).forEach { choice ->
            DwConnection.entries.forEach { connection ->
                assertFalse(
                    "${choice.plan.modelId} on $connection",
                    dwTier2InstallMayBeOffered(choice, connection)
                )
            }
            // …and the refusal is the runtime, not the phone: pass a runtime and the answer flips.
            assertTrue(
                "${choice.plan.modelId}: with a runtime this must become an ordinary fit question",
                dwTier2InstallMayBeOffered(choice, DwConnection.UNMETERED, runtimePresent = true)
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Nothing in the Tier 2 surface reaches a money gate
    // -----------------------------------------------------------------------------------------

    @Test
    fun `neither money gate appears anywhere in the Tier 2 surface, not only in the layer contract`() {
        /*
         * `DwTier2LayerTest` pins this for `DwTier2Layer.kt`. The catalogue and the list are the other
         * two files a designer's Tier 2 interaction passes through, and a consent check added to
         * EITHER of them would refuse an on-device model for a disclosure that does not happen.
         */
        val files = listOf(
            "src/main/java/com/designprototype/workshop/data/DwTier2Models.kt",
            "src/main/java/com/designprototype/workshop/ui/designworkshop/DwTier2ModelUi.kt",
        )
        val gates = listOf(
            "DwTier3Consent",
            "dwTier3ConsentOf",
            "dwResolveDictationConsent",
            "DwDictationAllowance",
            "dwDictationAllowanceOf",
            "dwDictationCapView",
            "DwDictationCapRefused",
        )
        files.forEach { path ->
            val body = code(path)
            gates.forEach { gate ->
                assertFalse(
                    "$path names “$gate”. A model that runs on this phone sends nothing off it and " +
                        "spends nothing at a provider; the cap is scoped by explicit instruction to " +
                        "the paid providers.",
                    body.contains(gate)
                )
            }
        }
    }
}
