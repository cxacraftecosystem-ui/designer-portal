package com.designprototype.workshop.ui

import com.designprototype.workshop.data.DwDictationPlan
import com.designprototype.workshop.data.DwDictationRung
import com.designprototype.workshop.data.DwPackState
import com.designprototype.workshop.data.dwDictationLadder
import com.designprototype.workshop.report.BlockKind
import com.designprototype.workshop.report.Mark
import com.designprototype.workshop.report.RichBlock
import com.designprototype.workshop.report.RichDoc
import com.designprototype.workshop.report.RichSpan
import com.designprototype.workshop.report.toJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record forms' dictation and rich text, on the desktop JVM.
 *
 * ── WHY THESE THREE THINGS AND NOT THE CONTROLS THEMSELVES ────────────────────────────────────
 *
 * Nothing here binds a `SpeechRecognizer`, opens a microphone or composes a text field, and none of
 * that is what would go wrong. The three defects this lane can actually ship are all silent, and all
 * three are decidable by a pure function:
 *
 *  1. **A CLIP LEAVING THE DEVICE FROM A SCREEN WHERE NOBODY WAS ASKED.** The only route that
 *     accepts an artisan's recording checks a design workshop's recorded consent; a record form has
 *     no workshop; the id-less route was retired to 410 GONE so that nothing could dictate without
 *     one. Nothing on screen would look wrong if that broke — a transcript would appear, in the
 *     right box, in the right script — so the guard is asserted rather than observed.
 *  2. **A REFUSAL SENTENCE THAT NAMES SOMEBODY ELSE'S SCREEN.** The stage ladder's exhausted
 *     sentence has five arms about workshops, consent and a server administrator. Printed on an
 *     artisan form they send a researcher to a screen that has nothing to do with their record, and
 *     the researcher concludes the app is broken and stops tapping the microphone — a permanent loss
 *     of a feature to a paragraph. Copy is exactly the thing that rots quietly, so it is pinned.
 *  3. **A DOCUMENT THAT DOES NOT SURVIVE A SAVE.** These columns are `String?` and stay `String?`.
 *     A bullet that reopens as a paragraph beginning with a bullet glyph doubles on the next save
 *     and the list becomes a row of glyphs, and a JSON document read as prose puts braces in a
 *     ministry report. Both are invisible until somebody reads the export.
 */
class RecordProseTest {

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  1. NOTHING UPLOADS
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * The filter drops the server rung even when it is handed one.
     *
     * Asserted against a HAND-BUILT plan rather than against a plan the conditions produced, and the
     * difference is the whole value of the test: the conditions cannot produce one today, so a test
     * that went through them would pass for the wrong reason and would go on passing after somebody
     * weakened the filter. This asks the filter the question directly.
     */
    @Test
    fun `the server rung is removed even when the plan contains it`() {
        val plan = DwDictationPlan(
            rungs = listOf(
                DwDictationRung.ON_DEVICE_PACK,
                DwDictationRung.APP_SPEECH_MODEL,
                DwDictationRung.SERVER_DICTATE,
                DwDictationRung.NETWORK_RECOGNISER,
            ),
            exhausted = null,
        )
        assertEquals(
            listOf(
                DwDictationRung.ON_DEVICE_PACK,
                DwDictationRung.APP_SPEECH_MODEL,
                DwDictationRung.NETWORK_RECOGNISER,
            ),
            recordDictationRungs(plan),
        )
    }

    /**
     * **Over every combination of handset facts a record form can be in, no plan reaches the server.**
     *
     * 2^6 × 6 pack states = 384 shapes, which is small enough to enumerate and large enough that
     * nobody would find the hole by hand. The point is not the count; it is that the guarantee is
     * about the CONDITIONS as well as the filter — `recordDictationConditions` pins three separate
     * facts (no workshop on the server, consent NOT_RECORDED, route unavailable) and any one of them
     * being enough is what makes this hard to break by accident.
     */
    @Test
    fun `no record-form plan ever contains the server rung`() {
        forEveryRecordCondition { conditions ->
            val plan = dwDictationLadder(conditions)
            assertFalse(
                "the shared ladder offered SERVER_DICTATE for $conditions",
                plan.rungs.contains(DwDictationRung.SERVER_DICTATE),
            )
            assertFalse(
                "the record filter let SERVER_DICTATE through for $conditions",
                recordDictationRungs(plan).contains(DwDictationRung.SERVER_DICTATE),
            )
        }
    }

    /**
     * And the rungs that DO survive are the on-device ones, in the shared ladder's own order.
     *
     * Pinned so that "we removed rung 2" cannot quietly become "we removed dictation": a filter that
     * returned an empty list would satisfy the test above and withhold the feature from every record
     * form in the app.
     */
    @Test
    fun `a phone with a pack and a model keeps both rungs, in the ladder's order`() {
        val conditions = recordDictationConditions(
            languageLabel = "Hindi",
            packState = DwPackState.INSTALLED,
            onDeviceEngine = true,
            networkRecogniser = true,
            online = true,
            deviceRefusedLanguage = false,
            appModelServesLanguage = true,
            appModelRefusedLanguage = false,
        )
        assertEquals(
            listOf(
                DwDictationRung.ON_DEVICE_PACK,
                DwDictationRung.APP_SPEECH_MODEL,
                DwDictationRung.NETWORK_RECOGNISER,
            ),
            recordDictationRungs(dwDictationLadder(conditions)),
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  2. THE COPY NAMES NOTHING A RECORD FORM DOES NOT HAVE
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Words that describe a design workshop's screen and never a record form's.
     *
     * "consent" and "allowance" are here because a record form has neither concept; "workshop" and
     * "administrator" because both send the reader to a place that cannot help them. "server" is the
     * subtle one and it is the reason this test exists at all: with the route marked unavailable the
     * stage sentence falls through to two arms that assert the server has no transcription service
     * configured — a false accusation about a server that is fine, on a screen that was never going
     * to talk to it.
     */
    private val forbidden = listOf(
        "workshop", "consent", "allowance", "administrator", "server", "artisan's recording",
    )

    @Test
    fun `the exhausted sentence never names a workshop, a consent, an allowance or a server`() {
        forEveryRecordCondition { conditions ->
            val sentence = recordDictationNothingLeftSentence(conditions).lowercase()
            forbidden.forEach { word ->
                assertFalse(
                    "\"$word\" appeared in the record-form refusal for $conditions:\n  $sentence",
                    sentence.contains(word),
                )
            }
        }
    }

    /**
     * Every arm says something, names the language where it has one, and ends in a full stop.
     *
     * A blank arm is the failure mode this guards: a `when` that grows a case and forgets a string
     * produces an empty error strip under the box, which reads exactly like the control having done
     * nothing at all — the one impression the whole ladder exists to prevent.
     */
    @Test
    fun `every arm produces a real sentence`() {
        forEveryRecordCondition { conditions ->
            val sentence = recordDictationNothingLeftSentence(conditions)
            assertTrue("empty refusal for $conditions", sentence.length > 30)
            assertTrue("unterminated refusal for $conditions: $sentence", sentence.trim().endsWith("."))
        }
    }

    /**
     * The one arm whose wording is load-bearing: a phone with no speech service at all.
     *
     * It must not suggest a retry, because no number of taps installs a recogniser, and it must not
     * suggest a setting, because this app has none that would. "Type the answer in" is the only
     * honest next move and it is the one thing the sentence has to carry.
     */
    @Test
    fun `a phone with no recogniser is told to type, not to retry`() {
        val sentence = recordDictationNothingLeftSentence(
            recordDictationConditions(
                languageLabel = "Odia",
                packState = DwPackState.UNKNOWN,
                onDeviceEngine = false,
                networkRecogniser = false,
                online = true,
                deviceRefusedLanguage = false,
                appModelServesLanguage = false,
                appModelRefusedLanguage = false,
            )
        )
        assertTrue(sentence, sentence.contains("no speech recogniser"))
        assertTrue(sentence, sentence.contains("Type the answer in"))
        assertFalse(sentence, sentence.contains("try again"))
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  3. THE COLUMN STILL HOLDS SOMETHING A CSV CAN PRINT
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * **The property the whole storage decision rests on: what lands in the column is readable prose.**
     *
     * Not JSON, not a brace, not a mark name. Every downstream reader of these columns — eleven raw
     * `contains` clauses for free-text search, `record_fields.cell()` behind four export surfaces, the
     * review diff on both platforms — reads the string as prose and none of them attempts a decode.
     * If this assertion ever fails, those surfaces are printing braces and nobody will notice until
     * somebody opens the workbook.
     */
    @Test
    fun `what lands in the column is prose and never JSON`() {
        val doc = RichDoc(
            listOf(
                RichBlock(spans = listOf(RichSpan("The warp is sized with rice paste.", setOf(Mark.BOLD)))),
                RichBlock(kind = BlockKind.BULLET_ITEM, spans = listOf(RichSpan("cotton"))),
                RichBlock(kind = BlockKind.BULLET_ITEM, level = 1, spans = listOf(RichSpan("unbleached"))),
                RichBlock(kind = BlockKind.ORDERED_ITEM, spans = listOf(RichSpan("size the warp"))),
                RichBlock(kind = BlockKind.ORDERED_ITEM, spans = listOf(RichSpan("dry in shade"))),
            )
        )
        val stored = recordStoredFromDoc(doc)!!
        assertFalse(stored, stored.contains("{"))
        assertFalse(stored, stored.contains("blocks"))
        assertFalse(stored, stored.contains("BOLD"))
        assertEquals(
            "The warp is sized with rice paste.\n" +
                "• cotton\n" +
                "  • unbleached\n" +
                "1. size the warp\n" +
                "2. dry in shade",
            stored,
        )
    }

    /**
     * **A list written on the phone reopens as a list, and does not grow a glyph on every save.**
     *
     * The defect: `fromPlain` makes every line a PARAGRAPH, so a bullet stored as "• cotton" would
     * reopen as prose whose text begins with a bullet character. Tap the bullet button once and the
     * next save writes "• • cotton"; two more edits and the field is a row of glyphs. Saving twice is
     * what makes this test able to see it — a single round trip looks fine.
     */
    @Test
    fun `structure survives a save and does not double on the second one`() {
        val first = RichDoc(
            listOf(
                RichBlock(kind = BlockKind.BULLET_ITEM, spans = listOf(RichSpan("cotton"))),
                RichBlock(kind = BlockKind.BULLET_ITEM, level = 2, spans = listOf(RichSpan("unbleached"))),
                RichBlock(kind = BlockKind.ORDERED_ITEM, spans = listOf(RichSpan("size the warp"))),
                RichBlock(spans = listOf(RichSpan("A closing paragraph."))),
            )
        )
        val once = recordStoredFromDoc(first)
        val reopened = recordDocFromStored(once)
        assertEquals(
            listOf(BlockKind.BULLET_ITEM, BlockKind.BULLET_ITEM, BlockKind.ORDERED_ITEM, BlockKind.PARAGRAPH),
            reopened.blocks.map { it.kind },
        )
        assertEquals(listOf(0, 2, 0, 0), reopened.blocks.map { it.level })
        assertEquals(listOf("cotton", "unbleached", "size the warp", "A closing paragraph."), reopened.blocks.map { it.text })
        // The fixed point: a second save changes nothing at all.
        assertEquals(once, recordStoredFromDoc(reopened))
    }

    /**
     * A document written into the column by the OTHER platform is read as a document, not as prose.
     *
     * The web's lane may yet choose to store `{"blocks": …}` in these columns. This build must render
     * that as text rather than as braces on the day it does — which is what lets the two lanes land in
     * either order without a release that shows JSON to somebody. It is also the read half of the
     * switch described in `RecordProseText.kt`: flip `recordStoredFromDoc` to emit JSON and this
     * already reads it back.
     */
    @Test
    fun `a stored JSON document is read as a document`() {
        val doc = RichDoc(listOf(RichBlock(spans = listOf(RichSpan("Dabu printing", setOf(Mark.BOLD))))))
        val asJson = toJson(doc).toString()
        val reopened = recordDocFromStored(asJson)
        assertEquals(1, reopened.blocks.size)
        assertEquals("Dabu printing", reopened.blocks.first().text)
        // The marks survive THIS direction, which is the point of accepting the shape at all: a
        // document written by the other platform must render as text here, not as braces.
        assertEquals(setOf(Mark.BOLD), reopened.blocks.first().spans.first().marks)
    }

    /**
     * And prose that merely happens to contain a brace is NOT mistaken for a document.
     *
     * The shape test is deliberately conservative — `{` at the start AND a `"blocks"` key — because
     * `Json.parseToJsonElement` accepts `123`, `true` and a bare quoted string as valid JSON, so
     * parsing first and asking afterwards would reinterpret a remark that reads "true" as a boolean.
     */
    @Test
    fun `prose containing braces is still prose`() {
        val typed = "{the block printer's own note} — the resist is applied in two passes."
        assertEquals(typed, recordDocFromStored(typed).blocks.single().text)
    }

    /**
     * An empty document clears the column rather than writing "".
     *
     * These columns are nullable, and a blank box means "not filled in". An empty string would make a
     * never-answered field indistinguishable from one somebody cleared, and would put a no-op line in
     * the record's edit history — which is read by a person deciding whether to accept the work.
     */
    @Test
    fun `an empty document stores nothing`() {
        assertNull(recordStoredFromDoc(RichDoc()))
        assertNull(recordStoredFromDoc(RichDoc(listOf(RichBlock()))))
        assertNull(recordStoredFromDoc(RichDoc(listOf(RichBlock(spans = listOf(RichSpan("   ")))))))
        assertTrue(recordDocFromStored(null).blocks.isEmpty())
        assertTrue(recordDocFromStored("   ").blocks.isEmpty())
    }

    /**
     * Spoken text joins what is already in the box with exactly one space.
     *
     * "…in Bagru.The second" and "…in Bagru.  The second" are both trivial and both end up in a
     * ministry report verbatim, because nobody proof-reads four hundred narrative fields. This is the
     * twin of `FieldRenderer.appendSpoken`, which is private to the stage renderer; the rule is stated
     * identically in both places and pinned here so the two cannot drift.
     */
    @Test
    fun `spoken text is joined with one space and no more`() {
        assertEquals("the warp", appendSpokenToRecord("", "the warp"))
        assertEquals("the warp", appendSpokenToRecord("the warp", ""))
        assertEquals("the warp is sized", appendSpokenToRecord("the warp", "is sized"))
        assertEquals("the warp is sized", appendSpokenToRecord("the warp ", "is sized"))
        assertEquals("the warp\nis sized", appendSpokenToRecord("the warp\n", "is sized"))
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Every handset shape a record form can be in. Six booleans and six pack states, enumerated.
     *
     * The workshop-shaped facts are NOT enumerated, deliberately: they are pinned by
     * `recordDictationConditions` and pinning them is the guarantee under test. Varying them here
     * would test the shared ladder, which `DwDictationLadderTest` already owns.
     */
    private fun forEveryRecordCondition(check: (com.designprototype.workshop.data.DwDictationConditions) -> Unit) {
        val flags = listOf(false, true)
        for (packState in DwPackState.entries) {
            for (onDeviceEngine in flags) {
                for (networkRecogniser in flags) {
                    for (online in flags) {
                        for (deviceRefused in flags) {
                            for (modelServes in flags) {
                                for (modelRefused in flags) {
                                    check(
                                        recordDictationConditions(
                                            languageLabel = "Odia",
                                            packState = packState,
                                            onDeviceEngine = onDeviceEngine,
                                            networkRecogniser = networkRecogniser,
                                            online = online,
                                            deviceRefusedLanguage = deviceRefused,
                                            appModelServesLanguage = modelServes,
                                            appModelRefusedLanguage = modelRefused,
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
