package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONE STEP IS SINGULAR — the motif carousel's accessible names, pinned as a rule about words.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE DEFECT THESE PIN
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `DwMediaCapture.kt` passes `noun = field.label.lowercase()`, and a carousel is mounted only on a
 * CAPPED gallery — which in the registry means `motifPhotos` and `contemporaryMotifPhotos` in
 * `stage_definitions.py`, labelled "Traditional motif photographs" and "Contemporary motif
 * photographs". So on both of the only two carousels that exist, every arrow announced *"Previous
 * traditional motif photographs"*: a PLURAL, on a control that moves by exactly one picture, on
 * every single step. [dwDescribeSubject] is the
 * repair — and for a while it was a repair nobody called. It was declared here, documented in the
 * PAST TENSE as though it had shipped, promised this test file by name, and referenced from no
 * executable line in the module. Its three strings now come out at `DwMediaCarousel.kt`'s frame
 * (the plural) and its two arrows (the singular).
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY A JVM TEST, AND WHAT IT CANNOT REACH
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Which of the two forms each `contentDescription` actually reads is a question about a composed
 * tree, and belongs to the instrumented suite. What a JVM test can pin — and what made the shipped
 * wording wrong rather than merely unpolished — is the PROPERTY underneath: that the two forms are
 * genuinely different, that the one an arrow is given can never be a plural whatever the registry
 * labels a gallery, and that the deliberate refusals in [dwDescribeSubject]'s docblock (no synonym
 * guessing, no re-casing, no cutting a real word in half) are refusals the code actually makes.
 *
 * Written the way `DwSketchChooserSentenceTest` is: the rule, and only where the exact string is
 * itself the product decision, the string.
 */
class DwMediaCarouselSubjectTest {

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // THE REGRESSION ITSELF
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /** The label the two shipped galleries actually carry, in the two forms this screen needs. */
    @Test
    fun `a plural label yields a singular for the arrows and a plural for the frame`() {
        val subject = dwDescribeSubject("traditional motif photographs")
        assertEquals("traditional motif photograph", subject.one)
        assertEquals("traditional motif photographs", subject.many)
    }

    /**
     * THE RULE THAT MATTERS, over every shape a caller can pass. An arrow moves by exactly one
     * picture, so the form it is handed must be singular no matter what the registry called the
     * field — including the shapes no label uses today but any label may use tomorrow.
     */
    @Test
    fun `no arrow can be handed a plural, whatever the label`() {
        listOf(
            "traditional motif photographs",
            "contemporary motif photographs",
            "Photographs",
            "photograph",
            "traditional motif",
            "360° capture",
            "",
        ).forEach { label ->
            val one = dwDescribeSubject(label).one
            assertTrue(
                "the singular form of '$label' does not end in a picture word at all: '$one'",
                one.endsWith("photograph"),
            )
            assertFalse(
                "an arrow on a gallery labelled '$label' would announce 'Previous $one' — a plural " +
                    "on a control that moves by one, which is the defect this file exists for",
                one.endsWith("photographs"),
            )
        }
    }

    /**
     * THE EXACT SENTENCE THAT SHIPPED, written out rather than derived, so that an edit which brings
     * it back has to delete a test that says in full what it is deleting.
     */
    @Test
    fun `the sentence the two shipped carousels announced is no longer reachable`() {
        assertEquals(
            "Previous traditional motif photograph",
            "Previous ${dwDescribeSubject("traditional motif photographs").one}",
        )
        assertEquals(
            "Next contemporary motif photograph",
            "Next ${dwDescribeSubject("contemporary motif photographs").one}",
        )
    }

    /** And the frame keeps the plural: the two forms differ, and differ by exactly the one letter. */
    @Test
    fun `the frame name and an arrow name differ by exactly the plural s`() {
        listOf("traditional motif photographs", "Photographs", "360° capture", "").forEach { label ->
            val subject = dwDescribeSubject(label)
            assertEquals(subject.one + "s", subject.many)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // ONE PROP, THREE SHAPES OF ANSWER — the reason the rule lives in the component
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * The `noun` doc asks for the field's whole label and `DwMediaCapture.kt` passes one; the web
     * twin's doc asked for a bare noun. Both shapes have to arrive at the same two words, or the
     * rule would be one more contract a call site has to remember — which is exactly how two
     * independent call sites made the identical mistake in the same words.
     */
    @Test
    fun `a whole label and a bare noun arrive at the same two words`() {
        val expected = DwCarouselSubject(
            one = "traditional motif photograph",
            many = "traditional motif photographs",
        )
        assertEquals(expected, dwDescribeSubject("traditional motif photographs"))
        assertEquals(expected, dwDescribeSubject("traditional motif photograph"))
        assertEquals(expected, dwDescribeSubject("traditional motif"))
    }

    /**
     * THE EMPTY STEM IS A REAL CASE, not defensiveness: three IMAGE_LIST fields in the registry are
     * labelled exactly "Photographs" (`productPhotos`, `responsePhotos`, `logPhotos`), and any of
     * them reaches this component the day it declares a cap. Stripping the picture word off one of
     * those leaves nothing, and "Previous " with nothing after it is worse than the plural was.
     */
    @Test
    fun `a gallery labelled only Photographs still has a subject`() {
        val bare = DwCarouselSubject(one = "photograph", many = "photographs")
        listOf("Photographs", "photographs", "photograph", "  photographs  ", "", "   ").forEach {
            assertEquals("'$it' left the subject empty", bare, dwDescribeSubject(it))
        }
    }

    /**
     * Collapsed and not merely trimmed. The reason on the web was an empty stem printing a doubled
     * space inside its empty-gallery sentence; here it is that an inner double space would be
     * carried into the frame's name and into both arrows, on every step.
     */
    @Test
    fun `runs of whitespace are collapsed, not merely trimmed`() {
        assertEquals("Traditional motif photographs", dwDescribeSubject(" Traditional  motif ").many)
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // WHAT THE RULE DELIBERATELY REFUSES TO DO
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * THE STATED COST OF THE `(?:^|\s)` ANCHOR, pinned so nobody "fixes" it into a bare suffix test.
     * A bare suffix test would cut "microphotographs" down to "micro" — a word taken out of
     * somebody's label to fix a stutter that was never there. Doubling is the chosen failure, and a
     * chosen failure has to be asserted or it looks like an accident to the next reader.
     */
    @Test
    fun `a real word that merely ends in the picture word is doubled, never cut in half`() {
        val subject = dwDescribeSubject("microphotographs")
        assertEquals("microphotographs photograph", subject.one)
        assertEquals("microphotographs photographs", subject.many)
    }

    /** Anchored at the end, so a picture word anywhere else is part of the label and stays put. */
    @Test
    fun `a picture word that is not at the end is left where it is`() {
        assertEquals(
            "photographs of butidar plates photograph",
            dwDescribeSubject("photographs of butidar plates").one,
        )
    }

    /**
     * NO SYNONYM GUESSING. "Photos", "images" and "pictures" are left alone because no registry
     * label uses them and a stripper guessing at synonyms eventually eats a real word — so a label
     * ending in one of those keeps all of it and gains a picture word, which is what such a gallery
     * holds.
     */
    @Test
    fun `words the rule does not know are kept whole`() {
        assertEquals("360° capture photograph", dwDescribeSubject("360° capture").one)
        assertEquals("motif photos photograph", dwDescribeSubject("motif photos").one)
        assertEquals("reference images photographs", dwDescribeSubject("reference images").many)
    }

    /**
     * NO RE-CASING OF THE LABEL. The caller decides its own case — `DwMediaCapture.kt` lowercases so
     * the sentence around it reads as a sentence — and a component that title-cased for the frame
     * would flatten a proper noun in a label the registry has not written yet. Only the picture word
     * this file adds is this file's to case.
     */
    @Test
    fun `the stem keeps its own casing and only the added picture word is lower case`() {
        assertEquals("Traditional Motif photograph", dwDescribeSubject("Traditional Motif Photographs").one)
        assertEquals("Traditional motif photographs", dwDescribeSubject("Traditional motif PHOTOGRAPHS").many)
    }
}
