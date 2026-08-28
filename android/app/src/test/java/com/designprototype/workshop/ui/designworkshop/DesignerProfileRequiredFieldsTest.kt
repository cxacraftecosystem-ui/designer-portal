package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE FOUR MANDATORY BOXES ON A DESIGNER PROFILE, AND THE E-MAIL RULE BESIDE THEM.
 *
 * ── WHAT THIS DEFENDS ──────────────────────────────────────────────────────────────────────────
 *
 * The owner's instruction of 2026-08-27: "Name, qualification, email, and phone number should be
 * mandatory fields as well. Email addresses should be validated for @ too." Three surfaces now
 * answer that — `DesignerProfileUpdate` on the server, `DesignerProfileForm.tsx` on the web, and
 * this screen — and the failure mode of a rule written down three times is that two of them agree
 * and the third quietly does not. The columns are pinned against the SERVER's own list below, read
 * out of its source, because the server is what both clients talk to and it is the only one of the
 * three that cannot be worked around.
 *
 * ── WHY THE COLUMNS ARE COMPARED AND THE LABELS ARE NOT ────────────────────────────────────────
 *
 * `REQUIRED_PROFILE_COLUMNS` calls the first one "Name"; this handset's box says "Name as printed",
 * and has since long before this rule existed, because on your own profile "Name" is ambiguous
 * between the account and the cover page. A refusal has to name the box the reader is looking at, so
 * the two clients are allowed to differ in what they CALL a column and are not allowed to differ in
 * WHICH columns they are. That is the line these assertions draw.
 */
class DesignerProfileRequiredFieldsTest {

    private val filled = ProfileForm(
        displayName = "A. Sharma",
        qualification = "M.Des, NID",
        phone = "+91 9876543210",
        email = "a.sharma@nid.ac.in",
    )

    // ── The register itself ──────────────────────────────────────────────────────────────────────

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
        // A key with no arm in `ProfileForm.requiredValue` would read as permanently blank, so the
        // screen would refuse every save with a message naming a box that is filled in. The full
        // form below has nothing missing; a typo in a key would make it claim otherwise.
        assertEquals(emptyList<String>(), designerProfileMissingRequired(filled))
    }

    // ── What is missing, and in what order ───────────────────────────────────────────────────────

    @Test
    fun `an untouched profile is missing all four, in screen order`() {
        assertEquals(
            listOf("displayName", "qualification", "phone", "email"),
            designerProfileMissingRequired(ProfileForm()),
        )
    }

    @Test
    fun `a box holding only whitespace is empty, because that is what the column stores`() {
        // `designerProfileUpdateJson` folds a blank box to an explicit JSON null and the server
        // stores `value.strip() or None`, so a rule that accepted a single space would be a rule a
        // space defeats — and the save would be refused by the API instead, after the round trip.
        assertEquals(
            listOf("displayName"),
            designerProfileMissingRequired(filled.copy(displayName = "   ")),
        )
    }

    @Test
    fun `the other seventeen columns are not mandatory`() {
        // The owner named four. An empanelment number a designer has not been ISSUED must not stop
        // them saving a biography, so emptying everything else must still leave nothing missing.
        val onlyTheFour = ProfileForm(
            displayName = filled.displayName,
            qualification = filled.qualification,
            phone = filled.phone,
            email = filled.email,
        )
        assertEquals(emptyList<String>(), designerProfileMissingRequired(onlyTheFour))
        assertEquals(4, DESIGNER_PROFILE_REQUIRED_LABELS.size)
    }

    // ── The sentence ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `one missing box is refused in the server's own words`() {
        // Word for word `_mandatory_columns_may_not_be_cleared`, with only the label swapped for the
        // one this handset draws above the box. A designer who meets this on the phone and again on
        // a laptop must not have to work out whether two differently-phrased lines mean one thing.
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
        // The screen joins this with the other faults, so a stray sentence here would attach an
        // empty clause to an unrelated refusal.
        assertEquals("", designerProfileRequiredRefusal(emptyList()))
    }

    // ── The e-mail rule ──────────────────────────────────────────────────────────────────────────

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
        // Two sentences under one box is one sentence too many: `designerProfileMissingRequired`
        // owns the empty case, and it says something a designer can act on ("Email is required").
        assertNull(designerEmailRefusal(""))
        assertNull(designerEmailRefusal("   "))
    }

    @Test
    fun `the refusal names the at sign, which is what the owner asked to be checked`() {
        val message = designerEmailRefusal("meera.nid.ac.in")
        assertTrue("the refusal must say what is missing: $message", message?.contains("@") == true)
    }

    // ── The acronym ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the CV keeps its capitals in the middle of a sentence`() {
        /*
          THE DEFECT THE OWNER REPORTED, PINNED ON THIS CLIENT.

          On the web it was `Attach ${label.toLowerCase()}` drawing "Attach cv" as a heading, plus a
          second instance nobody had reported in the upload-failure sentence ("the designer cv did
          not upload"). This screen's HEADING was already right and its failure sentence was not:
          `caption().lowercase()` printed "Could not upload the cv."

          The rule being defended is not one string's spelling. It is that a mid-sentence noun cannot
          be DERIVED from a heading by lower-casing it, because only a table knows which of these
          three strings is an acronym — so the two forms are two tables, and the day somebody folds
          them back into one to remove the "duplication", this fails naming the CV.
        */
        assertEquals("CV", ProfileMediaSlot.CV.caption())
        assertEquals("CV", ProfileMediaSlot.CV.midSentence())
        assertEquals("Photograph", ProfileMediaSlot.PHOTOGRAPH.caption())
        assertEquals("photograph", ProfileMediaSlot.PHOTOGRAPH.midSentence())
        assertEquals("Signature", ProfileMediaSlot.SIGNATURE.caption())
        assertEquals("signature", ProfileMediaSlot.SIGNATURE.midSentence())

        // And the sentence itself, since that is what the designer actually reads.
        ProfileMediaSlot.entries.forEach { slot ->
            val sentence = "Could not upload the ${slot.midSentence()}."
            assertTrue(
                "\"$sentence\" lower-cases an acronym",
                !sentence.contains(" cv"),
            )
        }
    }
}

/**
 * A file of this repository, found by walking up from wherever the test runner started.
 *
 * The working directory of a Gradle test worker is not something to depend on, and a test that
 * skipped when it could not find its subject would prove nothing on the day somebody moves it.
 * Missing is a failure, loudly. Same helper and same reasoning as `DashboardTileParityTest`'s — and
 * the `..`-prefixed candidates are what let it reach OUT of `android/` and into `backend/`, which is
 * how the server's list is compared at all.
 */
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
