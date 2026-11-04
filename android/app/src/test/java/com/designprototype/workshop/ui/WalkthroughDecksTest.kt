package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WalkthroughDecksTest {

    private val everyRole = listOf(
        "CROWDSOURCE_VOLUNTEER",
        "FIELD_CONTRIBUTOR",
        "RESEARCHER",
        "DESIGNER",
        "INSPECTOR",
        "PROFESSOR",
        "ASSISTANT_DIRECTOR",
        "REGIONAL_DIRECTOR",
        "MINISTRY_ADMIN",
        "ADMIN",
        "MASTER_ADMIN",
    )

    private fun account(role: String) =
        UserDto(id = "u-$role", email = "$role@example.org", name = role, role = role)

    private val WEB_INSPECTOR_STEPS: List<String> by lazy {
        val source = repoFile(
            "../$WEB_INSPECTOR_PATH",
            WEB_INSPECTOR_PATH,
        ).readText(Charsets.UTF_8).replace("\r\n", "\n")
        val start = source.indexOf("export const INSPECTOR_STEPS")
        check(start >= 0) { "INSPECTOR_STEPS is no longer declared in $WEB_INSPECTOR_PATH" }
        Regex("""^ {4}id: "([^"]+)"""", RegexOption.MULTILINE)
            .findAll(source.substring(start))
            .map { it.groupValues[1] }
            .toList()
    }

    @Test
    fun `the parser found the web's inspector deck`() {
        assertTrue(
            "$WEB_INSPECTOR_PATH parsed to ${WEB_INSPECTOR_STEPS.size} step ids — the anchor this " +
                "file scans for has stopped matching, so every assertion below is now checking " +
                "nothing at all",
            WEB_INSPECTOR_STEPS.size >= 3
        )
        assertEquals(
            "the web's inspector deck has two steps with the same id, so it cannot be the join",
            WEB_INSPECTOR_STEPS.size,
            WEB_INSPECTOR_STEPS.toSet().size
        )
    }

    @Test
    fun `the handset teaches the web's inspector subjects in the web's own order`() {
        val here = walkthroughInspectorJourney.map { it.id }
        assertEquals(
            "the handset's inspector deck teaches the web's subjects in a different order",
            WEB_INSPECTOR_STEPS.filter { it in here },
            here
        )
    }

    @Test
    fun `the cards this handset leaves out are exactly the ones it says it leaves out`() {
        val here = walkthroughInspectorJourney.map { it.id }.toSet()
        assertEquals(
            "the web's inspector deck teaches ${WEB_INSPECTOR_STEPS.filterNot { it in here }} and " +
                "this handset does not. `walkthroughInspectorOmissions` is the one register of " +
                "that decision and it says ${walkthroughInspectorOmissions.sorted()}. If a card " +
                "was dropped, argue it where the deck is declared and add it there; if a " +
                "capability has landed on this handset, write the step and take the id OUT of the " +
                "register. Never edit one side alone to make this green — that is the register " +
                "agreeing with itself about a product neither half is describing.",
            walkthroughInspectorOmissions.sorted(),
            WEB_INSPECTOR_STEPS.filterNot { it in here }.sorted()
        )
    }

    @Test
    fun `nothing is in the omission register that the web does not teach`() {
        assertEquals(
            "these ids are registered as omissions and the web's inspector deck does not teach " +
                "them at all: ${walkthroughInspectorOmissions - WEB_INSPECTOR_STEPS.toSet()}",
            emptySet<String>(),
            walkthroughInspectorOmissions - WEB_INSPECTOR_STEPS.toSet()
        )
    }

    @Test
    fun `every deck this build carries is registered, and each is the object it claims to be`() {
        assertEquals(
            "a deck is declared and not registered, so nothing can switch to it",
            listOf("designer", "inspector"),
            WALKTHROUGH_DECKS.map { it.id }
        )
        assertEquals(
            "two decks share an id, so the switcher's tick would land on whichever comes first",
            WALKTHROUGH_DECKS.size,
            WALKTHROUGH_DECKS.map { it.id }.toSet().size
        )
        assertSame(
            "the designer's deck must be the journey those suites read, not a copy of it",
            walkthroughJourney,
            WALKTHROUGH_DESIGNER_DECK.journey
        )
        assertSame(
            "the designer's deck must be the deck the screen has always rendered",
            walkthroughSteps,
            WALKTHROUGH_DESIGNER_DECK.steps
        )
        assertSame(
            "the inspector's deck must be the journey this file's parity assertions read",
            walkthroughInspectorJourney,
            WALKTHROUGH_INSPECTOR_DECK.journey
        )
    }

    @Test
    fun `no deck is empty, because the journey draws nothing under three cards`() {
        WALKTHROUGH_DECKS.forEach { deck ->
            assertTrue("“${deck.id}” has no numbered steps", deck.journey.isNotEmpty())
            assertTrue(
                "“${deck.id}” has ${deck.steps.size} cards and the journey draws nothing under three",
                deck.steps.size >= 3
            )
            assertTrue("“${deck.id}” has no name for the switcher", deck.name.isNotBlank())
            assertTrue("“${deck.id}” does not say who it is for", deck.audience.isNotBlank())
        }
    }

    @Test
    fun `the role picks the deck that opens, tier by tier`() {
        everyRole.forEach { role ->
            val expected = if (role == "INSPECTOR") "inspector" else "designer"
            assertEquals(
                "a $role opens on the wrong walkthrough. The handset's inspection door is set " +
                    "membership on {INSPECTOR} — it refuses a professor, and leaves an admin appointed " +
                    "to inspect to the web — so this default is a set and never a rank floor.",
                expected,
                walkthroughDeckFor(account(role)).id
            )
        }
    }

    @Test
    fun `a ministry post opens on the designer's deck, and that is the ruling rather than an oversight`() {
        listOf("ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN").forEach { role ->
            assertEquals(role, "designer", walkthroughDeckFor(account(role)).id)
        }
    }

    @Test
    fun `a null account gets the designer's deck rather than nothing`() {
        assertEquals("designer", walkthroughDeckFor(null).id)
    }

    @Test
    fun `choosing a deck by role takes nothing away from anybody`() {
        val menu = FIELD_NAV_ITEMS.first { it.destination == NavDestination.WALKTHROUGH }
        everyRole.forEach { role ->
            val user = account(role)
            assertTrue("the walkthrough is hidden from $role", menu.can(user))
            assertTrue(
                "a $role opens on “${walkthroughDeckFor(user).id}”, which is not a registered deck",
                WALKTHROUGH_DECKS.any { it.id == walkthroughDeckFor(user).id }
            )
            assertEquals(
                "the deck register is being narrowed for $role. It is a default and not a gate: " +
                    "the role picks which deck OPENS and every deck stays reachable for everybody.",
                listOf("designer", "inspector"),
                WALKTHROUGH_DECKS.map { it.id }
            )
        }
    }

    @Test
    fun `every inspector step opens a screen this handset actually has`() {
        walkthroughInspectorJourney.forEach { step ->
            assertTrue(
                "“${step.id}” has no destination. A card on this deck either opens a real screen " +
                    "or is not a card: the capability that has none is recorded in " +
                    "`walkthroughInspectorOmissions` and taught on the closing card instead.",
                step.destination != null
            )
        }
        assertEquals(
            "the inspection read must point at the list it is reached from",
            NavDestination.DESIGN_WORKSHOP_INSPECTIONS,
            walkthroughInspectorJourney.first { it.id == "inspection-read" }.destination
        )
        assertEquals(
            "the review card must name the row this handset really has",
            NavDestination.REVIEW,
            walkthroughInspectorJourney.first { it.id == "inspection-review-queue" }.destination
        )
    }
}

private const val WEB_INSPECTOR_PATH = "frontend/components/guide/inspectorSteps.ts"

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
