package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwMediaCarouselSubjectTest {

    @Test
    fun `a plural label yields a singular for the arrows and a plural for the frame`() {
        val subject = dwDescribeSubject("traditional motif photographs")
        assertEquals("traditional motif photograph", subject.one)
        assertEquals("traditional motif photographs", subject.many)
    }

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

    @Test
    fun `the frame name and an arrow name differ by exactly the plural s`() {
        listOf("traditional motif photographs", "Photographs", "360° capture", "").forEach { label ->
            val subject = dwDescribeSubject(label)
            assertEquals(subject.one + "s", subject.many)
        }
    }

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

    @Test
    fun `a gallery labelled only Photographs still has a subject`() {
        val bare = DwCarouselSubject(one = "photograph", many = "photographs")
        listOf("Photographs", "photographs", "photograph", "  photographs  ", "", "   ").forEach {
            assertEquals("'$it' left the subject empty", bare, dwDescribeSubject(it))
        }
    }

    @Test
    fun `runs of whitespace are collapsed, not merely trimmed`() {
        assertEquals("Traditional motif photographs", dwDescribeSubject(" Traditional  motif ").many)
    }

    @Test
    fun `a real word that merely ends in the picture word is doubled, never cut in half`() {
        val subject = dwDescribeSubject("microphotographs")
        assertEquals("microphotographs photograph", subject.one)
        assertEquals("microphotographs photographs", subject.many)
    }

    @Test
    fun `a picture word that is not at the end is left where it is`() {
        assertEquals(
            "photographs of butidar plates photograph",
            dwDescribeSubject("photographs of butidar plates").one,
        )
    }

    @Test
    fun `words the rule does not know are kept whole`() {
        assertEquals("360° capture photograph", dwDescribeSubject("360° capture").one)
        assertEquals("motif photos photograph", dwDescribeSubject("motif photos").one)
        assertEquals("reference images photographs", dwDescribeSubject("reference images").many)
    }

    @Test
    fun `the stem keeps its own casing and only the added picture word is lower case`() {
        assertEquals("Traditional Motif photograph", dwDescribeSubject("Traditional Motif Photographs").one)
        assertEquals("Traditional motif photographs", dwDescribeSubject("Traditional motif PHOTOGRAPHS").many)
    }
}
