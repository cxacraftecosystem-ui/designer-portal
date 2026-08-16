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
 * ── THE WORD BUDGET, WHICH THIS FILE USED TO REFUSE TO MAKE ───────────────────────────────────
 *
 * It refused in these words: *"A word budget would be the obvious assertion and it is deliberately
 * NOT made here: the Tier 2 surface is under active development, and a red test over a wording change
 * somebody is mid-way through is a test that gets deleted rather than heeded."*
 *
 * **That reasoning was overtaken on 2026-08-16 by a FOURTH rejection of this surface**, and the
 * fourth is what settles it. Three times the prose was cut by hand and three times it grew back,
 * because nothing in the build could tell. The cost of the missing budget is now known and the cost
 * of a red test is still hypothetical — and a red test that says *"this card is 300 words"* is not an
 * obstruction, it is the measurement this file was written to take, finally load-bearing.
 *
 * The budget is deliberately SLACK — roughly double what the card measures today. It is not a style
 * guide and must never be tuned down to whatever the current wording happens to score; it is a
 * tripwire for the failure that has actually happened four times, which is a card doubling in size
 * one well-meant clarification at a time. A change that trips it is not necessarily wrong. It is a
 * change that has to be looked at by a person, which is exactly what did not happen three times.
 *
 * ── AND THE RULE NO REDESIGN COULD EVER LEGITIMATELY NEED ─────────────────────────────────────
 *
 * **No string this app shows a designer may name a file in this repository.**
 * `docs/DEVICE-TIER-MEASUREMENT.md` was live on the Speech & AI card on the fleet handset — read off
 * the view hierarchy at 02:57 on 2026-08-13, inside a 96-word paragraph — and a repository path is
 * the one class of text that is *provably* useless to the person holding the phone: they cannot open
 * it, cannot act on it, and it tells them the app is talking to its own authors. The refusals stay;
 * the citation goes.
 *
 * **That sweep had a blind spot and a citation survived in it for three days.** It walked the refusal
 * grid and five named sentences — not the MODEL ROWS, which are the longest text on the card, and
 * `DwFitNote.LOAD_FAILED_HERE_BEFORE` sat on one still reading *"it is exactly what
 * docs/DEVICE-TIER-MEASUREMENT.md exists to collect"*. The sweep now composes every row the card can
 * draw and checks those too; a partial sweep reports clean and is worse than none, because the next
 * reader trusts it.
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
     * The budget, as two numbers, both roughly double what the card measures today.
     *
     * TWO rather than one because they fail differently. A total catches the card growing a new
     * paragraph; a per-node ceiling catches ONE string becoming an essay, which is what actually
     * happened — the card was 591 words across 9 nodes with a single 176-word model row in it, so a
     * total alone would have been satisfied by moving words between nodes.
     */
    private val cardWordBudget = 620
    private val longestNodeBudget = 130

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

    /**
     * Exactly what `SpeechAndAiScreen`'s `DwDeviceTierBody` renders, in the same order, for the
     * fleet's own handset. Shared by every test in this file so that the card the budget is measured
     * against and the card the citation sweep walks are the same card — the sweep previously used a
     * hand-written subset and a citation lived for three days in the difference.
     */
    private fun composeCard(): List<Pair<String, String>> {
        val labels = DW_DICTATION_LANGUAGES.associate { it.tag to it.label }
        val connection = DwConnection.UNMETERED
        val recommendation = dwRecommendTiers(fleetHandset, connection)

        // The same order DwDeviceTierBody appends them in.
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

        // Proof the card was actually composed rather than skipped — a budget passes trivially
        // against an empty list, so this has to be asserted before the budget means anything.
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

        /*
         * ── THE MODEL ROWS, WHICH THIS SWEEP DID NOT WALK UNTIL 2026-08-16 ─────────────────────
         *
         * They are the longest strings on the card and they are assembled from clauses this file
         * never named — `dwFitNoteClause`, the accuracy clause, the speed clause. A citation lived in
         * `DwFitNote.LOAD_FAILED_HERE_BEFORE` for three days after this test reported the card clean,
         * because "clean" meant "clean in the half I looked at". Composing the card and walking ALL
         * of it is what makes the green meaningful; the named list above stays because two of its
         * entries (the stale sentence, the offline arm) are not reachable from the fleet handset's
         * own composition.
         */
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
