package com.designprototype.workshop.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **THE GATE IN FRONT OF A 2.6 GB DOWNLOAD, PINNED NOW THAT THE DOWNLOAD EXISTS.**
 *
 * Until 2026-10-10 the only thing keeping a Tier 2 fetch off the fleet was that `DwTier2ModelList`
 * drew no control at all, and this file pinned that omission. LiteRT-LM is now in the APK and the list
 * draws Download, Pause, Cancel and Remove — so what is pinned is the rule that omission stood in for:
 * **the control is decided by `dwTier2InstallOffer`, which asks `dwTier2InstallMayBeOffered` (fit AND
 * connection AND runtime), and never by `dwModelDownloadMayBeOffered` alone.** Source-reading tests,
 * because a composable cannot be invoked from a desktop JVM test.
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
    fun `the Tier 2 gate is the general fit gate AND the runtime`() {
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
            assertEquals(DwModelFit.COMFORTABLE, choice.fit)
            DwConnection.entries.forEach { connection ->
                assertEquals(
                    dwModelDownloadMayBeOffered(choice, connection),
                    dwTier2InstallMayBeOffered(choice, connection)
                )
                assertFalse(
                    "${choice.plan.modelId}: a build without the runtime offers nothing",
                    dwTier2InstallMayBeOffered(choice, connection, runtimePresent = false)
                )
            }
        }
    }

    @Test
    fun `the Tier 2 list decides its control through dwTier2InstallOffer and never the general gate`() {
        val ui = code("src/main/java/com/designprototype/workshop/ui/designworkshop/DwTier2ModelUi.kt")
        assertTrue(ui.contains("dwTier2InstallOffer("))
        assertFalse(
            "dwModelDownloadMayBeOffered knows nothing about the runtime; the Tier 2 list must not use it",
            ui.contains("dwModelDownloadMayBeOffered")
        )
        val install = code("src/main/java/com/designprototype/workshop/data/DwTier2Install.kt")
        assertTrue(
            "the offer must ask the runtime-aware gate before it draws Download",
            install.contains("dwTier2InstallMayBeOffered(")
        )
    }

    @Test
    fun `no screen hands the Tier 2 rows to the speech model list`() {
        val screen = code("src/main/java/com/designprototype/workshop/ui/SpeechAndAiScreen.kt")
        val choiceListCalls = Regex("""DwModelChoiceList\((?:[^()]|\([^()]*\))*\)""")
            .findAll(screen)
            .map { it.value }
            .toList()
        assertTrue("SpeechAndAiScreen no longer calls DwModelChoiceList", choiceListCalls.isNotEmpty())
        choiceListCalls.forEach { call ->
            assertFalse(call.contains("tier2Choices"))
        }
        assertTrue(screen.contains("DwTier2ModelList"))
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
            "src/main/java/com/designprototype/workshop/data/DwTier2Install.kt",
            "src/main/java/com/designprototype/workshop/data/DwTier2Engine.kt",
            "src/main/java/com/designprototype/workshop/ui/designworkshop/DwTier2ModelInstallUi.kt",
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
