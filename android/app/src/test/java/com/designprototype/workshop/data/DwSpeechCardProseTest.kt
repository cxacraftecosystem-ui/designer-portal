package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.DW_DICTATION_LANGUAGES
import org.junit.Assert.assertTrue
import org.junit.Test

class DwSpeechCardProseTest {

    private val fleetHandset = DwDeviceMeasurement(
        totalRamBytes = 5_789_032L * 1024L,
        availableRamBytes = 1_285_164L * 1024L,
        lowRamDevice = false,
        freeStorageBytes = 39_034_012L * 1024L,
        abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
    )

    private fun words(text: String): Int =
        Regex("[0-9A-Za-zÀ-ɏ'’]+").findAll(text).count()

    private val cardWordBudget = 620
    private val longestNodeBudget = 130

    private fun everyRefusalSentence(): List<Pair<String, String>> =
        DwAiTier.entries.flatMap { tier ->
            DwTierRefusal.entries.map { refusal ->
                "dwTierRefusalSentence(${tier.name}, ${refusal.name})" to
                    dwTierRefusalSentence(tier, refusal)
            }
        }

    private fun composeCard(): List<Pair<String, String>> {
        val labels = DW_DICTATION_LANGUAGES.associate { it.tag to it.label }
        val connection = DwConnection.UNMETERED
        val recommendation = dwRecommendTiers(fleetHandset, connection)

        return buildList {
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
    }

    @Test
    fun `print the Speech and AI tier card exactly as the screen composes it`() {
        val card = composeCard()

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

        assertTrue("the card must compose at least the heading, class, readout and three tiers", card.size >= 6)
    }

    @Test
    fun `the card stays inside its word budget, in total and in any single string`() {
        val card = composeCard()
        val total = card.sumOf { words(it.second) }
        val longest = card.maxByOrNull { words(it.second) }!!

        assertTrue(
            "Settings → Speech & AI is $total words, over the $cardWordBudget-word budget. This " +
                "card has been rejected for length four times; the budget is the tripwire for the " +
                "fifth. If the growth is genuinely warranted, raise the number in this file and say " +
                "why — do not delete the assertion.\n\n" +
                card.joinToString("\n") { "  [${it.first}] ${words(it.second)}w" },
            total <= cardWordBudget
        )
        assertTrue(
            "the string at [${longest.first}] is ${words(longest.second)} words, over the " +
                "$longestNodeBudget-word ceiling for one string. A designer reads this as a wall " +
                "and skips it, which costs them the decision it was written to inform:\n\n" +
                "  ${longest.second}",
            words(longest.second) <= longestNodeBudget
        )
    }

    @Test
    fun `no sentence this app shows a designer names a file in this repository`() {
        val offenders = mutableListOf<String>()

        everyRefusalSentence().forEach { (where, sentence) ->
            if (Regex("""\bdocs/[A-Za-z0-9._-]+""").containsMatchIn(sentence)) {
                offenders += "$where\n    $sentence"
            }
        }

        val named = listOf(
            "dwTier3Sentence(NONE)" to dwTier3Sentence(DwConnection.NONE),
            "dwTier3Sentence(METERED)" to dwTier3Sentence(DwConnection.METERED),
            "dwTier3Sentence(UNMETERED)" to dwTier3Sentence(DwConnection.UNMETERED),
            "dwDeviceReadoutSentence" to dwDeviceReadoutSentence(fleetHandset),
            "DW_TIER_STALE_SENTENCE" to DW_TIER_STALE_SENTENCE,
        )

        (named + composeCard()).forEach { (where, sentence) ->
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
