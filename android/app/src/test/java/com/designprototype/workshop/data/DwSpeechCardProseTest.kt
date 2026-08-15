package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.DW_DICTATION_LANGUAGES
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **HOW MANY WORDS SETTINGS → SPEECH & AI ACTUALLY PUTS ON A DESIGNER'S SCREEN, AND ONE THING IT MUST
 * NEVER PUT THERE.**
 *
 * ── WHY THIS FILE EXISTS ──────────────────────────────────────────────────────────────────────
 *
 * The repository owner has rejected this surface three times for being over-explained, in these words:
 * *"I do not need to know it about each and every language in three paragraphs"*, *"stop crying over
 * odia or any other particular language, or the crunch of space or compute on any particular device"*.
 * Every previous lane answered that with a prose argument in a report. Nothing in the build measured
 * it, so the surface could — and did — grow back between reviews.
 *
 * This test composes **exactly what `SpeechAndAiScreen`'s `DwDeviceTierBody` renders**, in the same
 * order, for the fleet's own handset, and prints the word count of each string. It is a MEASUREMENT
 * first: `./gradlew testDebugUnitTest --tests '*DwSpeechCardProseTest*' -i` prints the card. Read the
 * number rather than an adjective.
 *
 * ── THE ONE HARD ASSERTION, AND WHY IT IS ONLY THIS ONE ───────────────────────────────────────
 *
 * A word budget would be the obvious assertion and it is deliberately NOT made here: the Tier 2
 * surface is under active development, and a red test over a wording change somebody is mid-way
 * through is a test that gets deleted rather than heeded.
 *
 * What IS asserted is the thing no redesign could ever legitimately need: **no string this app shows a
 * designer may name a file in this repository.** `docs/DEVICE-TIER-MEASUREMENT.md` was live on the
 * Speech & AI card on the fleet handset — read off the view hierarchy at 02:57 on 2026-08-13, inside a
 * 96-word paragraph — and a repository path is the one class of text that is *provably* useless to the
 * person holding the phone: they cannot open it, cannot act on it, and it tells them the app is
 * talking to its own authors. The refusals stay; the citation goes.
 */
class DwSpeechCardProseTest {

    /**
     * The fleet's SM-M325F as it actually read at 02:57 on 2026-08-13 — the same reading the live
     * screenshot in this lane's report was taken against, so the printed card is the printed screen.
     */
    private val fleetHandset = DwDeviceMeasurement(
        totalRamBytes = 5_789_032L * 1024L,
        availableRamBytes = 1_285_164L * 1024L,
        lowRamDevice = false,
        freeStorageBytes = 39_034_012L * 1024L,
        abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
    )

    private fun words(text: String): Int =
        Regex("[0-9A-Za-zÀ-ɏ'’]+").findAll(text).count()

    /**
     * Every user-facing string the tier card can reach, whatever this handset happens to be offered
     * today. Reachability moves as catalogues fill — `dwTier2Offer` stopped returning
     * `NO_MEASURED_MODEL` the moment `DW_TIER2_CATALOGUE` got its first row — so the doc-path rule is
     * checked across the whole enum rather than only the arm that fires on one phone.
     */
    private fun everyRefusalSentence(): List<Pair<String, String>> =
        DwAiTier.entries.flatMap { tier ->
            DwTierRefusal.entries.map { refusal ->
                "dwTierRefusalSentence(${tier.name}, ${refusal.name})" to
                    dwTierRefusalSentence(tier, refusal)
            }
        }

    @Test
    fun `print the Speech and AI tier card exactly as the screen composes it`() {
        val labels = DW_DICTATION_LANGUAGES.associate { it.tag to it.label }
        val connection = DwConnection.UNMETERED
        val recommendation = dwRecommendTiers(fleetHandset, connection)

        // The same order DwDeviceTierBody appends them in.
        val card = buildList {
            add("heading" to "AI on this phone")
            add("class" to "This phone: ${dwDeviceClassLabel(recommendation.deviceClass)}")
            add("readout" to dwDeviceReadoutSentence(fleetHandset))
            add("tier2" to dwTierOfferSentence(DwAiTier.TIER_2, recommendation.tier2))
            add("tier1" to dwTierOfferSentence(DwAiTier.TIER_1, recommendation.tier1))
            add("tier3" to dwTier3Sentence(connection))
            recommendation.tier1Choices.forEachIndexed { i, choice ->
                add("tier1 model row $i" to dwModelChoiceSentence(choice, fleetHandset, labels))
            }
            recommendation.tier2Choices.forEachIndexed { i, choice ->
                add("tier2 model row $i" to dwModelChoiceSentence(choice, fleetHandset, labels))
            }
        }

        println("=".repeat(100))
        println("SETTINGS -> SPEECH & AI, CARD 2 \"AI on this phone\", on the fleet SM-M325F")
        println("=".repeat(100))
        var total = 0
        card.forEach { (slot, text) ->
            val w = words(text)
            total += w
            println("[%-18s] %4d words | %s".format(slot, w, text))
            println()
        }
        println("-".repeat(100))
        println("CARD 2 TOTAL: $total words across ${card.size} text nodes")
        println("Longest single node: ${card.maxOf { words(it.second) }} words")
        println("Nodes of 25+ words: ${card.count { words(it.second) >= 25 }}")
        println("-".repeat(100))

        // Not a budget — just proof the card was actually composed rather than skipped.
        assertTrue("the card must compose at least the heading, class, readout and three tiers", card.size >= 6)
    }

    @Test
    fun `no sentence this app shows a designer names a file in this repository`() {
        val offenders = mutableListOf<String>()

        everyRefusalSentence().forEach { (where, sentence) ->
            if (Regex("""\bdocs/[A-Za-z0-9._-]+""").containsMatchIn(sentence)) {
                offenders += "$where\n    $sentence"
            }
        }

        listOf(
            "dwTier3Sentence(NONE)" to dwTier3Sentence(DwConnection.NONE),
            "dwTier3Sentence(METERED)" to dwTier3Sentence(DwConnection.METERED),
            "dwTier3Sentence(UNMETERED)" to dwTier3Sentence(DwConnection.UNMETERED),
            "dwDeviceReadoutSentence" to dwDeviceReadoutSentence(fleetHandset),
            "DW_TIER_STALE_SENTENCE" to DW_TIER_STALE_SENTENCE,
        ).forEach { (where, sentence) ->
            if (Regex("""\bdocs/[A-Za-z0-9._-]+""").containsMatchIn(sentence)) {
                offenders += "$where\n    $sentence"
            }
        }

        assertTrue(
            "A designer holding the phone cannot open a file in this repository. " +
                "These strings print one on screen:\n\n" + offenders.joinToString("\n\n"),
            offenders.isEmpty()
        )
    }
}
