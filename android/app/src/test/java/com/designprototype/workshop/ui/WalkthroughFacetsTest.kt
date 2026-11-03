package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkthroughFacetsTest {

    @Test
    fun `every numbered step yields a caution, whatever punctuation it raises its voice with`() {
        val silent = WALKTHROUGH_DECKS.flatMap { it.journey }
            .filter { walkthroughFacets(it).watch.isEmpty() }
        assertTrue(
            "these steps carry a caution in their body that the card will not draw, because the " +
                "seam was not recognised: ${silent.map { it.id }}. The two that shipped this way " +
                "wrote WATCH OUT with a comma after it rather than a colon; the marker is the WORDS " +
                "and not the punctuation, and if a step has found a third way to write them the fix " +
                "is in the marker, never in the copy.",
            silent.isEmpty(),
        )
    }

    @Test
    fun `the caution does not open on the words that introduced it`() {
        WALKTHROUGH_DECKS.flatMap { it.journey }.forEach { step ->
            step.watchNotes().forEach { note ->
                assertFalse(
                    "“${step.id}” has a caution still wearing its own introduction: “$note”",
                    note.startsWith("watch out", ignoreCase = true),
                )
                assertTrue(
                    "“${step.id}” has a caution opening on punctuation: “$note”",
                    note.isNotEmpty() && (note.first().isLetterOrDigit() || note.first() == '“'),
                )
            }
        }
    }

    @Test
    fun `the seam is removed from the prose rather than duplicated into both halves`() {
        WALKTHROUGH_DECKS.flatMap { it.journey }.forEach { step ->
            val facets = walkthroughFacets(step)
            val whole = facets.summary + " " + facets.detail
            assertFalse(
                "“${step.id}” still carries its caution's introduction inside the prose half, so " +
                    "the warning is drawn twice",
                whole.contains("watch out", ignoreCase = true),
            )
        }
    }

    @Test
    fun `every numbered step has both a summary line and a panel to open`() {
        WALKTHROUGH_DECKS.flatMap { it.journey }.forEach { step ->
            val facets = walkthroughFacets(step)
            assertTrue("“${step.id}” has no collapsed summary line", facets.summary.isNotBlank())
            assertTrue("“${step.id}” has nothing in its detail panel", facets.detail.isNotBlank())
            assertFalse(
                "“${step.id}”'s summary swallowed the whole body, so the panel repeats the card",
                facets.summary == step.body.trim(),
            )
        }
    }

    @Test
    fun `the summary is one readable line and not a paragraph`() {
        WALKTHROUGH_DECKS.flatMap { it.journey }.forEach { step ->
            val summary = walkthroughFacets(step).summary
            assertTrue(
                "“${step.id}” has a ${summary.length}-character collapsed summary: “$summary”",
                summary.length <= 320,
            )
        }
    }

    @Test
    fun `every step that opens a screen says what that screen asks for`() {
        val silent = WALKTHROUGH_DECKS.flatMap { it.journey }
            .filter { it.destination != null && walkthroughFacets(it).fields.isEmpty() }
        assertTrue(
            "these steps open a screen and no longer name a single thing it asks for: " +
                "${silent.map { it.id }}. Their cards draw the head, the why and the caution and " +
                "skip the middle block entirely, which is the exact shape the owner reported — " +
                "same number of cards, not the same content. The entries live in " +
                "WALKTHROUGH_FIELDS, keyed by step id, and they are the WEB'S: copy them from " +
                "frontend/components/guide/steps.ts, never from an Android form. " +
                "backend/tests/test_walkthrough_fields_parity.py is what holds the two together.",
            silent.isEmpty(),
        )
    }

    @Test
    fun `no card would draw an empty chip`() {
        WALKTHROUGH_DECKS.flatMap { it.steps }.forEach { step ->
            walkthroughFacets(step).fields.forEach { field ->
                assertTrue(
                    "“${step.id}” carries a blank entry in its field list: “$field”",
                    field.isNotBlank(),
                )
            }
        }
    }

    @Test
    fun `the steps that teach no screen list no fields at all`() {
        val screenless = WALKTHROUGH_DECKS.flatMap { deck ->
            listOf(deck.steps.first(), deck.steps.last()) +
                deck.journey.filter { it.destination == null }
        }
        screenless.forEach { step ->
            assertEquals(
                "“${step.id}” teaches no screen and must list no fields — an entry here is a " +
                    "register invented on this side, with nothing on the web holding it to " +
                    "anything, which is the failure WalkthroughJourney.kt's overruled paragraph " +
                    "spells out",
                emptyList<String>(),
                walkthroughFacets(step).fields,
            )
        }
    }

    @Test
    fun `the two ends of the deck come back with no caution at all`() {
        WALKTHROUGH_DECKS.flatMap { listOf(it.steps.first(), it.steps.last()) }.forEach { end ->
            val facets = walkthroughFacets(end)
            assertEquals(
                "“${end.id}” is one of the two ends and must produce no caution bullets",
                emptyList<String>(),
                facets.watch,
            )
            assertTrue("“${end.id}” lost its words in the cutter", facets.summary.isNotBlank())
        }
    }

    @Test
    fun `a step's own why and cautions win over the body's seams`() {
        val facets = walkthroughFacets(
            WalkStep(
                id = "probe",
                title = "Probe · Test",
                body = "Record the thing you came for, in the order it happens. Watch out: a body caution.",
                why = "The dataset needs it.",
                watch = listOf("First caution.", "  ", "Second caution."),
            )
        )
        assertEquals("Record the thing you came for, in the order it happens.", facets.summary)
        assertEquals("The dataset needs it.", facets.detail)
        assertEquals(listOf("First caution.", "Second caution."), facets.watch)
    }

    @Test
    fun `blank why and watch fields fall back to the body's seams`() {
        val facets = walkthroughFacets(
            WalkStep(
                id = "probe",
                title = "Probe · Test",
                body = "Record the thing you came for, in the order it happens. The dataset needs it. Watch out: a body caution.",
                why = "  ",
                watch = listOf(" "),
            )
        )
        assertEquals("The dataset needs it.", facets.detail)
        assertEquals(listOf("a body caution."), facets.watch)
    }
}

private fun WalkStep.watchNotes(): List<String> = walkthroughFacets(this).watch
