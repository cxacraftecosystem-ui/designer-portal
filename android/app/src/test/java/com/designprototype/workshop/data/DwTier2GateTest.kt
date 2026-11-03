package com.designprototype.workshop.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwTier2GateTest {

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("$path is not where this test expects it", file.isFile)
        return file.readText()
    }

    private fun code(path: String): String = source(path)
        .lines()
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

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

    @Test
    fun `neither money gate appears anywhere in the Tier 2 surface, not only in the layer contract`() {
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
