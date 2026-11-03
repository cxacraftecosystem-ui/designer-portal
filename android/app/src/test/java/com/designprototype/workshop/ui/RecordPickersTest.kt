package com.designprototype.workshop.ui

import com.designprototype.workshop.data.ArtisanDto
import com.designprototype.workshop.data.CraftDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordPickersTest {

    private fun craft(id: String, name: String) = CraftDto(id = id, name = name)

    private fun artisan(
        id: String,
        name: String,
        craftId: String? = null,
        place: String = "Bagru",
        craft: CraftDto? = null,
    ) = ArtisanDto(id = id, name = name, place = place, status = "APPROVED", craftId = craftId, craft = craft)

    @Test
    fun `a create and an empty English name both open mirroring`() {
        assertTrue("a create has nothing to protect", englishNameMirrorArmed(null, null))
        assertTrue(englishNameMirrorArmed("Chhapa", ""))
        assertTrue("whitespace is an empty box", englishNameMirrorArmed("Chhapa", "   "))
    }

    @Test
    fun `an English name equal to the toolkit name keeps mirroring`() {
        assertTrue(englishNameMirrorArmed("Chhapa", "Chhapa"))
    }

    @Test
    fun `an English name that differs opens DIVORCED and is never overwritten`() {
        assertFalse(englishNameMirrorArmed("Chhapa", "Block-printing stamp"))
    }

    @Test
    fun `equality is RAW, because the casing belongs to the server`() {
        assertFalse(englishNameMirrorArmed("Chhapa", "chhapa"))
        assertFalse(englishNameMirrorArmed("Chhapa", " Chhapa "))
    }

    private val block = craft("c-block", "Block Printing")
    private val bandhani = craft("c-bandhani", "Bandhani")
    private val roster = listOf(
        artisan("a-1", "Ram Kumar", craftId = "c-block"),
        artisan("a-2", "Sita Devi", craftId = "c-bandhani"),
        artisan("a-3", "Mohan Lal", craftId = null),
    )

    @Test
    fun `deselecting a craft drops only the artisans of that craft`() {
        val dropped = craftsChangeClearsArtisans(
            nextCraftIds = listOf("c-bandhani"),
            removedCraftIds = listOf("c-block"),
            artisanIds = listOf("a-1", "a-2"),
            artisans = roster,
        )
        assertEquals(listOf("a-1"), dropped)
    }

    @Test
    fun `ticking an extra craft drops nothing, because nothing was removed`() {
        val dropped = craftsChangeClearsArtisans(
            nextCraftIds = listOf("c-block", "c-ajrakh"),
            removedCraftIds = emptyList(),
            artisanIds = listOf("a-1", "a-2"),
            artisans = roster,
        )
        assertEquals(emptyList<String>(), dropped)
    }

    @Test
    fun `deselecting one craft leaves the artisans of a craft nobody touched alone`() {
        val dropped = craftsChangeClearsArtisans(
            nextCraftIds = listOf("c-ajrakh"),
            removedCraftIds = listOf("c-block"),
            artisanIds = listOf("a-1", "a-2"),
            artisans = roster,
        )
        assertEquals(listOf("a-1"), dropped)
    }

    @Test
    fun `nothing is dropped while every ticked craft still covers its artisans`() {
        val dropped =
            craftsChangeClearsArtisans(listOf("c-block", "c-bandhani"), listOf("c-third"), listOf("a-1", "a-2"), roster)
        assertEquals(emptyList<String>(), dropped)
    }

    @Test
    fun `a craft removed and re-ticked in one answer orphans nobody`() {
        val dropped = craftsChangeClearsArtisans(
            nextCraftIds = listOf("c-block"),
            removedCraftIds = listOf("c-block", "c-bandhani"),
            artisanIds = listOf("a-1", "a-2"),
            artisans = roster,
        )
        assertEquals(listOf("a-2"), dropped)
    }

    @Test
    fun `an artisan this form cannot see is never dropped`() {
        val dropped =
            craftsChangeClearsArtisans(listOf("c-bandhani"), listOf("c-block"), listOf("a-off-page"), roster)
        assertEquals(emptyList<String>(), dropped)
    }

    @Test
    fun `an artisan whose record names no craft is never dropped`() {
        val dropped =
            craftsChangeClearsArtisans(emptyList(), listOf("c-block", "c-bandhani"), listOf("a-3"), roster)
        assertEquals(emptyList<String>(), dropped)
    }

    @Test
    fun `clearing every craft drops every artisan this form knows a craft for`() {
        val dropped = craftsChangeClearsArtisans(
            nextCraftIds = emptyList(),
            removedCraftIds = listOf("c-block", "c-bandhani"),
            artisanIds = listOf("a-1", "a-2", "a-3"),
            artisans = roster,
        )
        assertEquals("the craft-less artisan stays", listOf("a-1", "a-2"), dropped)
    }

    @Test
    fun `the record's own scalar leads and its link rows follow`() {
        assertEquals(listOf("a1", "a5", "a9"), initialLinkIds(listOf("a5", "a9"), "a1"))
    }

    @Test
    fun `a scalar that is already among the links keeps its place and is not repeated`() {
        assertEquals(listOf("c-1", "c-2"), initialLinkIds(listOf("c-1", "c-2"), "c-1"))
        assertEquals(listOf("c-2", "c-1"), initialLinkIds(listOf("c-1", "c-2"), "c-2"))
    }

    @Test
    fun `a record with no link rows opens on its scalar alone`() {
        assertEquals(listOf("c-1"), initialLinkIds(emptyList(), "c-1"))
        assertEquals(listOf("c-1"), initialLinkIds(null, "c-1"))
    }

    @Test
    fun `links with no scalar are kept in the order the server stated`() {
        assertEquals(listOf("a5", "a9"), initialLinkIds(listOf("a5", "a9"), null))
    }

    @Test
    fun `blanks are dropped and a create with neither opens empty`() {
        assertEquals(emptyList<String>(), initialLinkIds(null, ""))
        assertEquals(emptyList<String>(), initialLinkIds(emptyList(), "   "))
        assertEquals(listOf("c-1"), initialLinkIds(listOf("", "  "), " c-1 "))
        assertEquals(emptyList<String>(), initialLinkIds(null, null))
    }

    @Test
    fun `merging a sheet answer keeps tick order and appends newcomers in option order`() {
        val merged = mergeSelection(
            current = listOf("b", "a"),
            next = setOf("a", "b", "c"),
            optionIds = listOf("a", "b", "c", "d"),
        )
        assertEquals(listOf("b", "a", "c"), merged)
    }

    @Test
    fun `merging drops what the sheet unticked and keeps what it could not see`() {
        val merged = mergeSelection(
            current = listOf("a", "off-page", "b"),
            next = setOf("a"),
            optionIds = listOf("a", "b"),
        )
        assertEquals(listOf("a", "off-page"), merged)
    }

    @Test
    fun `select all keeps a deterministic order even though the answer arrives at once`() {
        val merged = mergeSelection(emptyList(), setOf("c", "a", "b"), listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), merged)
    }

    private val selectedNames = mapOf("c-block" to "Block Printing", "c-bandhani" to "Bandhani")

    @Test
    fun `artisans sort by craft name then by artisan name`() {
        val options = toolArtisanOptions(
            listOf(
                artisan("a-1", "Ram Kumar", craftId = "c-block"),
                artisan("a-2", "Sita Devi", craftId = "c-bandhani"),
                artisan("a-3", "Anil Verma", craftId = "c-block"),
                artisan("a-4", "Asha Bai", craftId = "c-bandhani"),
            ),
            selectedNames,
        )
        assertEquals(
            listOf("a-4", "a-2", "a-3", "a-1"),
            options.map { it.value },
        )
    }

    @Test
    fun `an artisan whose craft cannot be named sorts LAST`() {
        val options = toolArtisanOptions(
            listOf(
                artisan("a-unknown", "Aaaa Aaaa", craftId = "c-gone"),
                artisan("a-1", "Zzzz Zzzz", craftId = "c-block"),
            ),
            selectedNames,
        )
        assertEquals(listOf("a-1", "a-unknown"), options.map { it.value })
    }

    @Test
    fun `the artisan's own embedded craft wins over the selection lookup`() {
        val options = toolArtisanOptions(
            listOf(artisan("a-1", "Ram Kumar", craftId = "c-block", craft = craft("c-block", "Aari Work"))),
            selectedNames,
        )
        assertEquals("Aari Work · Bagru", options.single().hint)
    }

    @Test
    fun `the craft leads the hint, because the handset has no group headings`() {
        val option = toolArtisanOptions(listOf(artisan("a-1", "Ram Kumar", craftId = "c-block")), selectedNames).single()
        assertEquals("Ram Kumar", option.label)
        assertEquals("Block Printing · Bagru", option.hint)
    }

    @Test
    fun `an artisan with neither a craft nor a place gets no hint rather than an empty one`() {
        val option = toolArtisanOptions(listOf(artisan("a-1", "Ram Kumar", place = "")), emptyMap()).single()
        assertEquals(null, option.hint)
    }

    @Test
    fun `the order is TOTAL, so two identical names still list the same way twice`() {
        val twins = listOf(
            artisan("a-zz", "Ram Kumar", craftId = "c-block"),
            artisan("a-aa", "Ram Kumar", craftId = "c-block"),
        )
        assertEquals(listOf("a-aa", "a-zz"), toolArtisanOptions(twins, selectedNames).map { it.value })
        assertEquals(
            listOf("a-aa", "a-zz"),
            toolArtisanOptions(twins.reversed(), selectedNames).map { it.value },
        )
    }

    @Test
    fun `the fold is Locale ROOT and not the device's`() {
        val options = toolArtisanOptions(
            listOf(
                artisan("a-i", "Irfan", craftId = "c-block"),
                artisan("a-z", "Zara", craftId = "c-block"),
            ),
            selectedNames,
        )
        assertEquals(listOf("a-i", "a-z"), options.map { it.value })
    }

    @Test
    fun `an off-page link the record can name shows its name`() {
        val row = offPageLinkRow("c-old", "Kalamkari", "craft")
        assertEquals("Kalamkari", row.label)
        assertTrue(row.hint!!.startsWith("Linked earlier"))
    }

    @Test
    fun `an off-page link the record cannot name says what it is and never invents one`() {
        val row = offPageLinkRow("a-old", null, "artisan")
        assertEquals("The artisan already on this record", row.label)
        assertEquals("a-old", row.value)
        assertTrue(row.hint!!.contains("could not list it just now"))
        assertEquals("a blank name is the same as no name", row.label, offPageLinkRow("a-old", "  ", "artisan").label)
    }
}
