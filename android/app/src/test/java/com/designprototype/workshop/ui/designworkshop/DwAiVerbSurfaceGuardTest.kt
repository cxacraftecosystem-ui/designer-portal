package com.designprototype.workshop.ui.designworkshop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **NO SURFACE OF THIS FEATURE MAY PUT A MODEL'S WORDS BACK INTO A STAGE FIELD, AND THIS IS THE TEST
 * THAT MAKES ADDING ONE A FAILING COMMIT RATHER THAN A HELPFUL ONE.**
 *
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY A SOURCE-READING TEST, WHICH IS AN UNUSUAL SHAPE AND IS THE RIGHT ONE HERE
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The rule is rule 1 of this whole feature: an AI output is a LAYER that is accepted or rejected,
 * never applied in place. On the SERVER it is true by construction — a `LayerWritePlan` may only name
 * a table in `WRITABLE_TABLES`, `DwStageEntry` is deliberately absent, and `_writable_model` has no
 * entry for it either, so a plan carrying its name would still have nowhere to be applied. **On a
 * client it is true only by there being nothing to press.** There is no type, no signature and no
 * absent dependency that stops somebody adding a "Use this text" button to
 * [DwAiVerbReviewSheet] — `insertText` is in the same package, `onChange` is a parameter of the
 * editor two files away, and Android's clipboard is one `LocalClipboardManager` away.
 *
 * So the guard is over the SOURCE, exactly as `frontend/e2e/ai-verbs-unit.spec.ts` guards the
 * browser's three files. It is the same rule, enforced the same way, on both clients.
 *
 * ── WHY EACH TOKEN IS ON THE LIST ───────────────────────────────────────────────────────────────
 *
 *  · `onChange(` — the editor's own write. A verb surface that called it would put model prose into a
 *    RICH_TEXT stage field, which is a field compared across surfaces, which plan §3 forbids: the same
 *    note through a phone and through the cloud differs legitimately and for ever, so the first
 *    cross-surface divergence test to fail would be blamed on a bug that is actually the design.
 *  · `insertText(` — how dictation puts spoken words at the caret. It is the single most natural thing
 *    to reach for here and the review sheet is sitting directly on top of the paragraph it corrected.
 *  · `ClipboardManager` / `LocalClipboardManager` / `setPrimaryClip` — **a clipboard button is a paste
 *    button with one extra keystroke**, and the cross-surface argument does not count keystrokes.
 *  · `SelectionContainer` — the platform's own Copy action, raised on a long press. It is no less a
 *    paste affordance for having been supplied by Android rather than written here.
 *  · `Intent.ACTION_SEND` / `createChooser` — an Android share sheet over model prose is a clipboard
 *    button with an icon on it. The subtitle FILE is deliberately not shared this way either: the
 *    repository writes it to Downloads and answers with where it landed, which is a document a player
 *    opens rather than text bound for a field.
 *
 * ── AND WHAT THE ALTERNATIVE IS, BECAUSE THE FEATURE WILL FEEL BROKEN WITHOUT ONE ───────────────
 *
 * `ai_verbs.expand`'s own words: *"A designer who wants those words in the field types them, at which
 * point they are that designer's sentences under that designer's name — which is a true statement,
 * unlike anything a paste button could produce."*
 *
 * ── WHAT THIS TEST CANNOT DO ────────────────────────────────────────────────────────────────────
 *
 * It reads text. Somebody determined to add the affordance can name a helper something else and this
 * will not notice. That is accepted: the point is not to make the change impossible but to make it
 * DELIBERATE — nobody adds a paste button by accident past a test that names the rule and the reason.
 *
 * NOT EXECUTED IN THIS ENVIRONMENT (no Gradle here), so it was written to be obviously correct by
 * reading: it fails if a file is missing, and fails if any listed token appears in one.
 */
class DwAiVerbSurfaceGuardTest {

    /** The unit tests run with `app/` as the working directory — see `DwSketchRectifyFieldTest`. */
    private val surfaces = listOf(
        "DwAiVerbReviewSheet.kt",
        "DwAiVerbsPanel.kt",
        "DwMediaAiVerbs.kt",
        "DwAiVerbWording.kt",
        "DwAiVerbSurface.kt",
    )

    private fun sourceOf(name: String): String {
        val file = File("src/main/java/com/designprototype/workshop/ui/designworkshop/$name")
        assertTrue(
            "$name is missing — this guard is only meaningful while the file it guards exists, so a " +
                "rename must move this list rather than silently disarming it",
            file.exists()
        )
        return file.readText(Charsets.UTF_8)
    }

    /**
     * The tokens no verb surface may contain. Each one's argument is in this class's header.
     *
     * `onChange(` and `insertText(` carry their opening bracket so that a KDoc sentence ABOUT them —
     * and this feature's comments discuss both at length, deliberately, because a reader has to know
     * why they are absent — is not itself a failure. A call has a bracket; a mention does not.
     */
    private val forbidden = listOf(
        "onChange(",
        "insertText(",
        "ClipboardManager",
        "LocalClipboardManager",
        "setPrimaryClip",
        "SelectionContainer",
        "ACTION_SEND",
        "createChooser",
    )

    @Test
    fun `no verb surface can put the model words into a field or onto the clipboard`() {
        surfaces.forEach { name ->
            val source = sourceOf(name)
            forbidden.forEach { token ->
                assertFalse(
                    "$name contains `$token`. An AI output is a LAYER that a named person accepts or " +
                        "declines, never text applied in place — see this test's header for why, and " +
                        "`ai_verbs.expand` for the alternative this repository prefers. If the words " +
                        "belong in the field, the designer types them, and they are then that " +
                        "designer's sentences under that designer's name.",
                    source.contains(token)
                )
            }
        }
    }

    /**
     * The review sheet must go on SAYING that nothing has been applied.
     *
     * The absence of a button is invisible: a designer looking at model prose with no paste control
     * cannot tell "this is a suggestion" from "this app is half-finished", and the second reading is
     * the one that gets a paste button added. `_finish_verb` puts `acceptanceRequired: true` on the
     * wire for exactly this reason — *"the client that just asked for this has words on screen and is
     * one tap from putting them in a report"* — and the sheet has to spend that fact on a sentence.
     */
    @Test
    fun `the review sheet says nothing has been applied and asks for a named acceptance`() {
        val sheet = sourceOf("DwAiVerbReviewSheet.kt")
        assertTrue(
            "the sheet must state that nothing has reached a document yet",
            sheet.contains("Nothing has been put in any document yet")
        )
        assertTrue(
            "accepting must be worded as a person putting their name to it, not as a form control",
            sheet.contains("accept it in my name")
        )
        assertTrue(
            "leaving a layer alone is a real third answer and must be on a labelled control",
            sheet.contains("Leave it for now")
        )
    }

    /**
     * The expansion's caution must reach the designer BEFORE the acceptance, not only after it.
     *
     * `report_ai_layers.EXPANDED_NOTE` prints above this kind in the annexure, so a ministry officer
     * reading the .docx a year later is warned. If the sheet does not carry the same substance, the
     * person who SIGNED for the passage read a weaker warning than the person who merely reads it —
     * which is the wrong way round, because only one of them was in a position to decline.
     */
    @Test
    fun `the expansion carries its invention caution where the decision is made`() {
        val sheet = sourceOf("DwAiVerbReviewSheet.kt")
        assertTrue(sheet.contains("EXPANDED"))
        assertTrue(
            "the caution must say the model supplied what is not in the note",
            sheet.contains("was supplied by")
        )
    }
}
