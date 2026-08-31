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
 * A step card draws four blocks: a collapsed summary line, a "Why this step exists" panel, a "What
 * the screen asks for" list and a "Watch out for" list. [WalkStep] carries three of them as ONE
 * string, and [walkthroughFacets] is what separates them — it cuts the body at the words "Watch out"
 * and again at the end of the first sentence. The card then draws a heading only where the block
 * behind it has something in it, which is right and is also what made the failure silent.
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
 *
 * ── AND THE FOURTH BLOCK, WHICH IS NOT CUT OUT OF ANYTHING ──────────────────────────────────────
 *
 * `WalkthroughFacets.fields` arrived on 2026-08-31 and it is the one facet that is LOOKED UP rather
 * than cut: it is the web's `GuideStep.fields`, carried in `WALKTHROUGH_FIELDS`. Whether those
 * strings are the WEB'S strings is not a question this suite can answer — it cannot see `steps.ts`
 * character by character and should not learn to — and it is answered instead by
 * `backend/tests/test_walkthrough_fields_parity.py`, which reads both source files and fails naming
 * the step. What is answered HERE is the same thing this file answers about the caution: that the
 * card ends up with something to draw, and never with a heading over nothing.
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
    fun `every step that opens a screen says what that screen asks for`() {
        // THE REGRESSION GUARD FOR THE REPORT OF 2026-08-31 — "the name of the fields in bubble are
        // missing from there". The rule is stated against the DOOR rather than against a list of
        // ids, so it survives the web adding a step: a step that teaches a screen must say what
        // that screen will ask for, and `WalkStep.destination` is exactly "there is a screen".
        //
        // The one journey step with no door is `offline`, which teaches a behaviour rather than a
        // form and correctly has nothing to list. It is not named here — the null destination is
        // what excuses it, and naming it would be a second register of exceptions.
        val silent = walkthroughJourney
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
        // The card lays these out as a FlowRow of bordered chips, one per entry, and it decides
        // whether to draw the heading by asking whether the list is EMPTY — not whether it has
        // anything readable in it. A blank string therefore survives every other assertion in this
        // file and renders as an empty bordered box, which is the same family of defect as a
        // heading over nothing and is harder to spot because the heading above it looks right.
        walkthroughSteps.forEach { step ->
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
        // The two ends are not features, and `offline` is the one subject with no counterpart on the
        // web and no screen of its own. What matters is the SHAPE handed back: an empty list, not a
        // list holding one blank string, because the card asks `isNotEmpty()` and would otherwise
        // draw "What the screen asks for" over a single empty chip.
        val screenless = listOf(walkthroughSteps.first(), walkthroughSteps.last()) +
            walkthroughJourney.filter { it.destination == null }
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
