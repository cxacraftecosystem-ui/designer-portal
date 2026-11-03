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

class RecordProseTest {

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

    @Test
    fun `every arm produces a real sentence`() {
        forEveryRecordCondition { conditions ->
            val sentence = recordDictationNothingLeftSentence(conditions)
            assertTrue("empty refusal for $conditions", sentence.length > 30)
            assertTrue("unterminated refusal for $conditions: $sentence", sentence.trim().endsWith("."))
        }
    }

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
        assertEquals(once, recordStoredFromDoc(reopened))
    }

    @Test
    fun `a stored JSON document is read as a document`() {
        val doc = RichDoc(listOf(RichBlock(spans = listOf(RichSpan("Dabu printing", setOf(Mark.BOLD))))))
        val asJson = toJson(doc).toString()
        val reopened = recordDocFromStored(asJson)
        assertEquals(1, reopened.blocks.size)
        assertEquals("Dabu printing", reopened.blocks.first().text)
        assertEquals(setOf(Mark.BOLD), reopened.blocks.first().spans.first().marks)
    }

    @Test
    fun `prose containing braces is still prose`() {
        val typed = "{the block printer's own note} — the resist is applied in two passes."
        assertEquals(typed, recordDocFromStored(typed).blocks.single().text)
    }

    @Test
    fun `an empty document stores nothing`() {
        assertNull(recordStoredFromDoc(RichDoc()))
        assertNull(recordStoredFromDoc(RichDoc(listOf(RichBlock()))))
        assertNull(recordStoredFromDoc(RichDoc(listOf(RichBlock(spans = listOf(RichSpan("   ")))))))
        assertTrue(recordDocFromStored(null).blocks.isEmpty())
        assertTrue(recordDocFromStored("   ").blocks.isEmpty())
    }

    @Test
    fun `spoken text is joined with one space and no more`() {
        assertEquals("the warp", appendSpokenToRecord("", "the warp"))
        assertEquals("the warp", appendSpokenToRecord("the warp", ""))
        assertEquals("the warp is sized", appendSpokenToRecord("the warp", "is sized"))
        assertEquals("the warp is sized", appendSpokenToRecord("the warp ", "is sized"))
        assertEquals("the warp\nis sized", appendSpokenToRecord("the warp\n", "is sized"))
    }

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
