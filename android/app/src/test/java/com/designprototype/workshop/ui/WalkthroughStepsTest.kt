package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WalkthroughStepsTest {

    private val WEB_GUIDE_STEPS: List<String> by lazy {
        val source = webGuideSource()
        val start = source.indexOf("export const GUIDE_STEPS")
        check(start >= 0) { "GUIDE_STEPS is no longer declared in $WEB_GUIDE_PATH" }
        Regex("""^ {4}id: "([^"]+)"""", RegexOption.MULTILINE)
            .findAll(source.substring(start))
            .map { it.groupValues[1] }
            .toList()
    }

    @Test
    fun `the parser found the web's step list`() {
        assertTrue(
            "$WEB_GUIDE_PATH parsed to ${WEB_GUIDE_STEPS.size} step ids — the anchor this file " +
                "scans for has stopped matching, so every parity assertion below is now checking " +
                "nothing at all",
            WEB_GUIDE_STEPS.size >= 10
        )
        assertEquals(
            "the web's guide has two steps with the same id, so it cannot be the join",
            WEB_GUIDE_STEPS.size,
            WEB_GUIDE_STEPS.toSet().size
        )
    }

    @Test
    fun `every subject the web walkthrough teaches has a step here`() {
        val here = walkthroughJourney.map { it.id }.toSet()
        val missing = WEB_GUIDE_STEPS.filterNot { it in here }
        assertTrue(
            "the web walkthrough teaches $missing and this handset does not. A designer who read " +
                "the guide on a laptop and then opened it in a courtyard would find the subject " +
                "gone — which is how the whole design-workshop arc went untaught here for months. " +
                "Add the step to WalkthroughSteps.kt. This list is READ FROM the web's own file, " +
                "so there is nothing here to edit and no way to make this pass by agreeing with " +
                "the handset — which is the entire point of it no longer being a hand-copy. If the " +
                "id merely CHANGED on one side, this reports it as missing rather than as renamed: " +
                "check the web's spelling before adding anything.",
            missing.isEmpty()
        )
    }

    @Test
    fun `the web's steps appear here in the web's own order`() {
        val shared = walkthroughJourney.map { it.id }.filter { it in WEB_GUIDE_STEPS }
        assertEquals(
            "the two clients teach the same subjects in different orders, which is worse than a " +
                "missing step: both look complete and only one of them is the order the work " +
                "happens in",
            WEB_GUIDE_STEPS,
            shared
        )
    }

    @Test
    fun `no two steps share an id`() {
        val ids = WALKTHROUGH_DECKS.flatMap { deck -> deck.steps.map { it.id } }
        assertEquals(
            "a duplicated id makes every id-based check answer for the wrong step, silently",
            ids.size,
            ids.toSet().size
        )
        assertTrue("an id is the join key and cannot be blank", ids.none { it.isBlank() })
    }

    @Test
    fun `no step title carries its own number`() {
        val numbered = Regex("""^\s*\d+\s*[.)]""")
        WALKTHROUGH_DECKS.flatMap { it.steps }.forEach { step ->
            assertFalse(
                "“${step.title}” has a step number typed into it. Inserting a step ahead of it " +
                    "would make that number wrong with nothing to catch it; the dialog derives " +
                    "the position from the list instead.",
                numbered.containsMatchIn(step.title)
            )
        }
    }

    @Test
    fun `the opening card counts the journey rather than claiming a number`() {
        WALKTHROUGH_DECKS.forEach { deck ->
            val intro = deck.steps.first()
            assertNull(
                "“${deck.id}”'s opening card is not one of its numbered steps",
                walkthroughStepNumber(deck, intro)
            )
            assertTrue(
                "“${deck.id}”'s opening card must state how many steps its journey has, and state " +
                    "the true number: the shipped one read “Ten steps, in this order” over a list " +
                    "of twelve while the web taught nineteen",
                intro.body.contains("${deck.journey.size} steps")
            )
            assertFalse(
                "“${deck.id}”'s opening card must count the JOURNEY and not the deck — a reader " +
                    "on a card whose first sentence says there are ${deck.journey.size} steps must " +
                    "not be told there are ${deck.steps.size}",
                intro.body.contains("${deck.steps.size} steps")
            )
        }
    }

    @Test
    fun `every deck is a journey with one card at each end`() {
        WALKTHROUGH_DECKS.forEach { deck ->
            assertEquals(
                "“${deck.id}” is the opening card, the journey, and the closing checklist",
                deck.journey.size + 2,
                deck.steps.size
            )
            assertEquals(
                "“${deck.id}”'s journey must reach the screen in its own order, unedited",
                deck.journey,
                deck.steps.subList(1, deck.steps.size - 1)
            )
            assertTrue(
                "“${deck.id}” has ${deck.steps.size} cards; the journey draws nothing under three",
                deck.steps.size >= 3
            )
        }
    }

    @Test
    fun `only the journey is numbered, and it is numbered from one`() {
        WALKTHROUGH_DECKS.forEach { deck ->
            assertNull(walkthroughStepNumber(deck, deck.steps.first()))
            assertNull(walkthroughStepNumber(deck, deck.steps.last()))
            deck.journey.forEachIndexed { index, step ->
                assertEquals(
                    "“${step.title}” must report itself as step ${index + 1} of “${deck.id}”",
                    index + 1,
                    walkthroughStepNumber(deck, step)
                )
            }
            WALKTHROUGH_DECKS.filter { it.id != deck.id }.forEach { other ->
                other.journey.forEach { step ->
                    assertNull(
                        "“${step.id}” belongs to “${other.id}” and “${deck.id}” gave it a number",
                        walkthroughStepNumber(deck, step)
                    )
                }
            }
        }
    }

    @Test
    fun `every step that offers a door names a menu row that exists`() {
        WALKTHROUGH_DECKS.flatMap { it.steps }.forEach { step ->
            val destination = step.destination ?: return@forEach
            assertNotNull(
                "“${step.title}” opens $destination, which is no longer a row in FIELD_NAV_ITEMS. " +
                    "Its “Open …” button would render without the screen's name on it.",
                FIELD_NAV_ITEMS.firstOrNull { it.destination == destination }
            )
        }
    }

    @Test
    fun `the two ends open nothing`() {
        WALKTHROUGH_DECKS.forEach { deck ->
            assertNull(
                "“${deck.id}”'s opening card is not a feature",
                deck.steps.first().destination
            )
            assertNull(
                "“${deck.id}”'s closing checklist is not a feature",
                deck.steps.last().destination
            )
        }
        assertTrue(
            "no step may open the walkthrough from inside the walkthrough",
            WALKTHROUGH_DECKS.flatMap { it.steps }
                .none { it.destination == NavDestination.WALKTHROUGH }
        )
    }

    @Test
    fun `every step of the journey ends on the thing that goes wrong`() {
        WALKTHROUGH_DECKS.flatMap { it.journey }.forEach { step ->
            assertTrue(
                "“${step.title}” has no “Watch out” — the one part of a step that cannot be " +
                    "reconstructed by reading the screen it describes",
                step.body.contains("watch out", ignoreCase = true)
            )
        }
    }

    @Test
    fun `every step wears a glyph and says what it is`() {
        WALKTHROUGH_DECKS.flatMap { it.steps }.forEach { step ->
            assertNotNull("“${step.title}” has no icon to find its row by", step.icon)
            assertTrue("a step with no words is not a step", step.title.isNotBlank())
            assertTrue("“${step.title}” has no body", step.body.isNotBlank())
        }
    }
}

private const val WEB_GUIDE_PATH = "frontend/components/guide/steps.ts"

private fun webGuideSource(): String =
    repoFile(
        "../$WEB_GUIDE_PATH",
        WEB_GUIDE_PATH,
    ).readText(Charsets.UTF_8).replace("\r\n", "\n")

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
