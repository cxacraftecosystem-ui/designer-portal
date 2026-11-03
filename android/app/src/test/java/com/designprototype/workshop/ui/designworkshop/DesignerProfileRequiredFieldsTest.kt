package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesignerProfileRequiredFieldsTest {

    private val filled = ProfileForm(
        displayName = "A. Sharma",
        qualification = "M.Des, NID",
        phone = "+91 9876543210",
        email = "a.sharma@nid.ac.in",
    )

    @Test
    fun `the four mandatory columns are the server's four, read from its own source`() {
        val schema = repoFile(
            "backend/app/schemas/designers.py",
            "../backend/app/schemas/designers.py",
        ).readText(Charsets.UTF_8)

        val block = schema
            .substringAfter("REQUIRED_PROFILE_COLUMNS: dict[str, str] = {")
            .substringBefore("}")
        assertTrue(
            "REQUIRED_PROFILE_COLUMNS was not found in backend/app/schemas/designers.py — if the " +
                "server's declaration moved or was renamed, this test has to follow it rather than " +
                "be deleted",
            block.isNotBlank() && block.length < 800,
        )
        val serverColumns = Regex("\"([A-Za-z]+)\"\\s*:")
            .findAll(block)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(
            "the handset marks a different set of boxes than the API refuses — one of the two was " +
                "edited alone, and whichever it was, a designer either meets a 422 naming a box " +
                "that carries no asterisk or is stopped by an asterisk the API does not enforce",
            serverColumns,
            DESIGNER_PROFILE_REQUIRED_LABELS.keys.toList(),
        )
    }

    @Test
    fun `every mandatory column is one this form can actually read`() {
        assertEquals(emptyList<String>(), designerProfileMissingRequired(filled))
    }

    @Test
    fun `an untouched profile is missing all four, in screen order`() {
        assertEquals(
            listOf("displayName", "qualification", "phone", "email"),
            designerProfileMissingRequired(ProfileForm()),
        )
    }

    @Test
    fun `a box holding only whitespace is empty, because that is what the column stores`() {
        assertEquals(
            listOf("displayName"),
            designerProfileMissingRequired(filled.copy(displayName = "   ")),
        )
    }

    @Test
    fun `the other seventeen columns are not mandatory`() {
        val onlyTheFour = ProfileForm(
            displayName = filled.displayName,
            qualification = filled.qualification,
            phone = filled.phone,
            email = filled.email,
        )
        assertEquals(emptyList<String>(), designerProfileMissingRequired(onlyTheFour))
        assertEquals(4, DESIGNER_PROFILE_REQUIRED_LABELS.size)
    }

    @Test
    fun `one missing box is refused in the server's own words`() {
        assertEquals(
            "Name as printed is required on a designer profile — it is printed on every report " +
                "generated under this name, so it cannot be left blank.",
            designerProfileRequiredRefusal(listOf("displayName")),
        )
    }

    @Test
    fun `several missing boxes are named together, so Save is pressed once and not four times`() {
        assertEquals(
            "Name as printed, Phone and Email are required on a designer profile — they are " +
                "printed on every report generated under this name, so they cannot be left blank.",
            designerProfileRequiredRefusal(listOf("displayName", "phone", "email")),
        )
    }

    @Test
    fun `nothing missing says nothing at all`() {
        assertEquals("", designerProfileRequiredRefusal(emptyList()))
    }

    @Test
    fun `an ordinary address is accepted`() {
        listOf(
            "a.sharma@nid.ac.in",
            "meera+reports@example.org",
            "d_1@sub.domain.co.uk",
        ).forEach { assertNull(it, designerEmailRefusal(it)) }
    }

    @Test
    fun `an address with no at sign is refused before the round trip`() {
        assertNotNull(designerEmailRefusal("meera.nid.ac.in"))
        assertNotNull(designerEmailRefusal("@nid.ac.in"))
        assertNotNull(designerEmailRefusal("meera@"))
        assertNotNull(designerEmailRefusal("meera@@nid.ac.in"))
        assertNotNull(designerEmailRefusal("meera@nid"))
        assertNotNull(designerEmailRefusal("meera nair@nid.ac.in"))
        assertNotNull(designerEmailRefusal("meera@.nid.ac.in"))
        assertNotNull(designerEmailRefusal("meera@nid..in"))
    }

    @Test
    fun `an empty box is not this rule's business`() {
        assertNull(designerEmailRefusal(""))
        assertNull(designerEmailRefusal("   "))
    }

    @Test
    fun `the refusal names the at sign, which is what the owner asked to be checked`() {
        val message = designerEmailRefusal("meera.nid.ac.in")
        assertTrue("the refusal must say what is missing: $message", message?.contains("@") == true)
    }

    @Test
    fun `the CV keeps its capitals in the middle of a sentence`() {
        assertEquals("CV", ProfileMediaSlot.CV.caption())
        assertEquals("CV", ProfileMediaSlot.CV.midSentence())
        assertEquals("Photograph", ProfileMediaSlot.PHOTOGRAPH.caption())
        assertEquals("photograph", ProfileMediaSlot.PHOTOGRAPH.midSentence())
        assertEquals("Signature", ProfileMediaSlot.SIGNATURE.caption())
        assertEquals("signature", ProfileMediaSlot.SIGNATURE.midSentence())

        ProfileMediaSlot.entries.forEach { slot ->
            val sentence = "Could not upload the ${slot.midSentence()}."
            assertTrue(
                "\"$sentence\" lower-cases an acronym",
                !sentence.contains(" cv"),
            )
        }
    }
}

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
