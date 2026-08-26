package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Open button's two silent defects: a copy on the handset that was never offered, and a promise
 * of a download about a file with nothing to download.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THESE ARE TESTS AND NOT A CODE REVIEW
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Both shipped, and both are invisible at every point a person would look.
 *
 *  · `DwDocumentPreview` chose the file to hand over with `localFile ?: (state as? Page)?.let { null }`.
 *    `?.let { null }` evaluates to null for every input there has ever been, so the second half of
 *    that expression was dead: the branch READS as "and otherwise the rendered page's file", and it
 *    is `localFile` and nothing else. A remote PDF already fetched into `cacheDir` and rendered on
 *    screen was opened by https URL instead — which with no signal opens nothing, while the decoded
 *    bytes sit in the cache directory. Open did nothing on a document the app was visibly showing,
 *    and nothing threw, nothing logged, and the first page kept rendering underneath.
 *  · The non-PDF arm said *"Stored and downloadable … opens in whatever program handles it on your
 *    device"* without ever asking whether there was anything to hand over. On an account the encoder
 *    withheld `MediaFile.url` from, that is a promise beside no button — reachable today by an admin
 *    opening a designer's profile whose CV is a .docx.
 *
 * Reproducing either by hand costs an upload, an entitlement change and a flight-mode toggle per
 * attempt, on a screen whose failure mode is that it looks fine. Asserting the two rules costs a
 * millisecond, which is why they are pinned here rather than trusted to the next reader.
 *
 * These pin the PROPERTY and, where the wording is a product decision copied across clients, the
 * exact sentence — see `frontend/components/media/DocumentPreview.tsx`, which settles the withheld
 * case ABOVE its own PDF/non-PDF split so that one sentence covers both arms.
 */
class DwDocumentOpenTargetTest {

    private fun file(name: String): File = File("/does/not/need/to/exist/$name")

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // WHICH COPY Open OFFERS
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * THE REGRESSION ITSELF. A remote PDF that has been fetched and drawn must be opened FROM THE
     * FETCHED FILE. Handing back the URL here is the defect: it is the offline case, and the bytes
     * are already on the device.
     */
    @Test
    fun `a document already fetched to this device is opened from the file, not the url`() {
        val cached = file("survey.pdf")
        val target = dwOpenTarget(
            justPicked = null,
            onDevice = cached,
            remoteUrl = "https://example.test/signed/survey.pdf",
        )
        assertEquals(DwOpenTarget.OnDevice(cached), target)
    }

    /** And with no url at all — the same file, because a cached document does not need one. */
    @Test
    fun `a cached document is offered with no url in sight`() {
        val cached = file("cv.pdf")
        assertEquals(
            DwOpenTarget.OnDevice(cached),
            dwOpenTarget(justPicked = null, onDevice = cached, remoteUrl = null),
        )
    }

    /**
     * A DOCUMENT PICKED IN THIS SESSION WINS. The two are different files while an upload is in
     * flight — the cache is keyed on the media id, which the picked file does not have yet — and the
     * one the designer is looking at is theirs.
     */
    @Test
    fun `a file just picked wins over a cached one`() {
        val picked = file("corrected-cv.pdf")
        val cached = file("superseded-cv.pdf")
        assertEquals(
            DwOpenTarget.OnDevice(picked),
            dwOpenTarget(justPicked = picked, onDevice = cached, remoteUrl = "https://example.test/x"),
        )
    }

    /** With no copy here, the pre-signed link is all there is, and it is offered as itself. */
    @Test
    fun `with nothing on the device the url is what Open follows`() {
        val url = "https://example.test/signed/cv.pdf"
        assertEquals(
            DwOpenTarget.Remote(url),
            dwOpenTarget(justPicked = null, onDevice = null, remoteUrl = url),
        )
    }

    /**
     * NEITHER MEANS NO BUTTON. A control that opens nothing is worse than an absent one: the reader
     * presses it, nothing happens, and the app reads as broken rather than as withholding.
     */
    @Test
    fun `with neither a file nor a url there is nothing to offer`() {
        assertNull(dwOpenTarget(justPicked = null, onDevice = null, remoteUrl = null))
    }

    /**
     * THE DEAD EXPRESSION MAY NOT COME BACK, and it is pinned over the SOURCE because that is where
     * it was invisible: `?.let { null }` type-checks, reads as a fallback, and evaluates to null for
     * every input. Nothing in this app can legitimately want a `let` that discards its receiver and
     * returns null — it is a fallback that is not one, whatever it is written about.
     *
     * WHITESPACE IS STRIPPED, so a reformat or a line wrap cannot evade the guard — the expression
     * would be caught spread over two lines as readily as over one.
     *
     * COMMENT LINES ARE DROPPED FIRST, and that is not a convenience: this file's own KDoc quotes the
     * dead idiom twice, on purpose, because a comment that names the defect it prevents is the whole
     * house style. A guard that could not tell prose from code would fire on the documentation of its
     * own subject and would be deleted by the next person to see it go red — which is how a guard
     * stops guarding. Same construction as `DwAiVerbSurfaceGuardTest`, which reads its three files the
     * way `frontend/e2e/ai-verbs-unit.spec.ts` reads the browser's.
     */
    @Test
    fun `no expression in the preview pretends to be a fallback and returns null`() {
        val source = File("src/main/java/com/designprototype/workshop/ui/designworkshop/DwDocumentPreview.kt")
        assertTrue("the preview source moved: ${source.absolutePath}", source.isFile)
        val code = source.readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
            .joinToString("") { line -> line.filterNot { it.isWhitespace() } }
        assertFalse(
            "`?.let { null }` is back in DwDocumentPreview: it is null for every input, so whatever " +
                "it reads as, it is dead code — see this test's header.",
            code.contains("?.let{null}"),
        )
        // The guard can read the file at all, and is reading the code and not only the prose: if the
        // comment filter ever ate everything, every assertion above would pass vacuously for ever.
        assertTrue("the comment filter left no code to guard", code.contains("fundwOpenTarget("))
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // WHAT THE CARD PROMISES ABOUT A FILE IT WILL NOT DRAW
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * NO DOWNLOAD IS PROMISED WHEN THERE IS NOTHING TO HAND OVER. This is the .docx-with-a-withheld-url
     * case, and the sentence it must fall back to is the one the PDF arm has always used for the same
     * fact — not a second wording for it.
     */
    @Test
    fun `a withheld file is never described as downloadable`() {
        val note = dwUndrawnDocumentNote(noun = "CV", openableHere = false)
        assertEquals(dwWithheldFileNote("CV"), note)
        assertFalse("a file with nothing to download was called downloadable", note.contains("download"))
    }

    /** And when there IS something to open, the card says so — the ordinary .docx on file. */
    @Test
    fun `a file this app cannot draw but can hand over is still called downloadable`() {
        val note = dwUndrawnDocumentNote(noun = "CV", openableHere = true)
        assertTrue(note.startsWith("Stored and downloadable."))
        assertNotEquals(dwWithheldFileNote("CV"), note)
    }

    /**
     * THE TWO SENTENCES, VERBATIM, because both are copied from the web and a paraphrase on one
     * client is a product that says two things about one fact. `DocumentPreview.tsx` names the
     * FILENAME where this names the noun — a commented platform difference, because this card prints
     * the filename on its own row underneath and would otherwise say it twice on a phone-width line.
     */
    @Test
    fun `the wording is the web's, to the character`() {
        assertEquals(
            "CV is stored, but this account may not open the file itself.",
            dwWithheldFileNote("CV"),
        )
        assertEquals(
            "Stored and downloadable. Only a PDF can be shown inside the app, so this one opens in " +
                "whatever program handles it on your device.",
            dwUndrawnDocumentNote(noun = "market survey", openableHere = true),
        )
    }

    /** The noun is the caller's, and both of this product's two documents read correctly with it. */
    @Test
    fun `the withheld sentence takes the reader's noun`() {
        assertEquals(
            "market survey is stored, but this account may not open the file itself.",
            dwWithheldFileNote("market survey"),
        )
    }
}
