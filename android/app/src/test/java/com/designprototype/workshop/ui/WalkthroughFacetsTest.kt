package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **THE SEAM THE WALKTHROUGH CARD CUTS A STEP AT** — the one piece of real logic in
 * `WalkthroughJourney.kt`, and until this file the only piece of it nothing could see.
 *
 * ── THE DEFECT THAT MADE THIS FILE NECESSARY, WHICH HAD SHIPPED ─────────────────────────────────
 *
 * A step card draws three blocks: a collapsed summary line, a "Why this step exists" panel and a
 * "Watch out for" list. [WalkStep] carries all three as ONE string, and [walkthroughFacets] is what
 * separates them — it cuts the body at the words "Watch out" and again at the end of the first
 * sentence. The card then draws a heading only where the block behind it has something in it, which
 * is right and is also what made the failure silent.
 *
 * The marker was the literal `"Watch out:"`. Twenty-one of the twenty-three journey steps write
 * exactly that. TWO RAISE THEIR VOICE INSTEAD — `design-workshop-stages` opens its caution
 * "WATCH OUT, AND THIS IS THE ONE TO REMEMBER:" and `offline` opens its own "WATCH OUT, BECAUSE THIS
 * ONE COSTS SOMEBODY ELSE'S WORK:" — and a comma is not a colon. `indexOf` answered -1 for both, the
 * `watch` list came back empty, the heading was correctly not drawn over an empty block, and the two
 * cautions stayed buried in the last third of a prose paragraph with no heading, no bullet and no
 * warning glyph over them. On the web both are `watch[]` entries under a triangle.
 *
 * They are also the two that cost somebody ELSE's fortnight: a queued correction overwriting a
 * colleague's edits with nobody told, and a reference pick copying values that a later edit does not
 * rewrite. And `WalkthroughStepsTest` was green throughout, because it asks whether the body
 * `contains("watch out", ignoreCase = true)` — which is true of both. A test standing over the
 * CONTENT cannot see a parser that fails to find what the content says.
 *
 * ── WHY IT IS A SEPARATE FILE FROM `WalkthroughStepsTest` ───────────────────────────────────────
 *
 * That suite holds the step LIST — the subjects, the order, the derived numbering, the doors. This
 * one holds the FUNCTION that reads a step, and the two fail for different reasons: a step added
 * without a caution fails there, a parser that stops recognising one fails here. Keeping them apart
 * is what makes the failure message tell you which of the two happened.
 *
 * ── WHAT IS ASSERTED, AND WHAT IS DELIBERATELY NOT ──────────────────────────────────────────────
 *
 * Structure, never sentences. Nothing below pins a word of copy: the prose on both clients is edited
 * constantly and a test that fails when somebody improves a paragraph is a test everybody learns to
 * delete. What is pinned is that every numbered step comes out of the cutter with all three blocks
 * populated, that the seam words are not left sitting at the top of the bullet, and that the two ends
 * of the deck — which have no caution and are drawn by different composables entirely — come back
 * with an empty list rather than one blank string, because an empty string in that list is a heading
 * drawn over nothing.
 */
class WalkthroughFacetsTest {

    @Test
    fun `every numbered step yields a caution, whatever punctuation it raises its voice with`() {
        // THE REGRESSION GUARD FOR THE SHIPPED DEFECT. Not "the body mentions a caution" — that is
        // `WalkthroughStepsTest`'s assertion and it was true of both broken steps — but "the cutter
        // found one and the card therefore has something to draw a heading over".
        val silent = walkthroughJourney.filter { walkthroughFacets(it).watch.isEmpty() }
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
        // The card prints these under a heading that already says "Watch out for". A bullet reading
        // "Watch out: create it before you leave" says it twice, and one that opens on a stray comma
        // or colon reads as a typo on the one screen whose job is to look authoritative to somebody
        // who has never used the app.
        walkthroughJourney.forEach { step ->
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
        // The panel's "Why this step exists" block is everything BEFORE the seam. If the words
        // survived there too, a reader would meet the caution twice — once with no heading over it
        // and once with — which is worse than either alone, because the second reading looks like a
        // different caution until you have read both.
        walkthroughJourney.forEach { step ->
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
        // A card whose summary is blank is a card with a title and nothing under it; a card whose
        // detail is blank opens on a chevron and shows a heading-less gap. Both are shapes the cutter
        // can produce from a body that is one sentence long, which is why the floor exists in
        // `walkthroughFirstSentenceEnd` and why this asserts the result rather than the rule.
        walkthroughJourney.forEach { step ->
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
        // Not a style rule — a layout one. The summary sits under the title in the COLLAPSED card, so
        // it is what a reader scrolling past twenty-three of these actually reads. There is no
        // maxLines on it, so a long one simply pushes the next card down the spine and makes the
        // journey feel longer than it is. The ceiling is generous on purpose: the cutter deliberately
        // runs past a short opening sentence rather than printing a fragment, so the honest limit is
        // "no step has quietly become three sentences", not "one sentence".
        walkthroughJourney.forEach { step ->
            val summary = walkthroughFacets(step).summary
            assertTrue(
                "“${step.id}” has a ${summary.length}-character collapsed summary: “$summary”",
                summary.length <= 320,
            )
        }
    }

    @Test
    fun `the two ends of the deck come back with no caution at all`() {
        // The opening card and the closing checklist are not features, have no caution and are drawn
        // by their own composables. What matters here is the SHAPE the cutter hands back for a body
        // with no seam in it: an empty list and not a list holding one empty string, because the card
        // decides whether to draw the "Watch out for" heading by asking whether the list is empty.
        listOf(walkthroughSteps.first(), walkthroughSteps.last()).forEach { end ->
            val facets = walkthroughFacets(end)
            assertEquals(
                "“${end.id}” is one of the two ends and must produce no caution bullets",
                emptyList<String>(),
                facets.watch,
            )
            assertTrue("“${end.id}” lost its words in the cutter", facets.summary.isNotBlank())
        }
    }
}

/** The caution bullets a step's card would draw. Named so the assertions above read as English. */
private fun WalkStep.watchNotes(): List<String> = walkthroughFacets(this).watch
