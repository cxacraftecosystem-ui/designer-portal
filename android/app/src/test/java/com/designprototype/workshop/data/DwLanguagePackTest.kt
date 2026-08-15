package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.DW_DICTATION_LANGUAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The language-pack capability layer, on the desktop JVM.
 *
 * WHAT THIS FILE IS FOR. Every claim this feature makes on screen is a claim about somebody else's
 * handset, and the only one that can be checked by looking at it is the one made on the handset in
 * the room. The failures that matter are the ones a Galaxy M32 would show and a Pixel would not: a
 * pack reported as `hi_IN` read as missing, `en-US` read as covering `en-IN`, and a recogniser that
 * answers nothing being rendered as nineteen rows of "not supported".
 *
 * The nineteen tags are taken from [DW_DICTATION_LANGUAGES] itself rather than restated, because a
 * copy would go on passing after somebody added a twentieth language to the dropdown.
 */
class DwLanguagePackTest {

    private val tags = DW_DICTATION_LANGUAGES.map { it.tag }

    /*
     * ---- THE FLEET'S GALAXY M32, TWICE. Two readings, four days apart, both taken off the handset.
     *
     * There are two fixtures rather than one because THE HANDSET CHANGED, and each reading pins a
     * different rule. Both are raw output from `androidTest/DwLanguagePackProbeTest`, which calls
     * `checkRecognitionSupport` and prints the four lists; both runs are written up in
     * docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md.
     *
     * THE VERSION THESE REPLACED WAS INVENTED, and that is why the defect they pin survived review. It
     * claimed this phone could fetch on-device packs for Odia, Bengali, Tamil, Telugu, Marathi and
     * Gujarati. The phone offers none of them. A fixture named after a specific handset, asserting
     * capabilities that handset does not have, made the suite agree with a device that does not exist
     * — so the tests passed while the settings screen told seventeen working languages they were
     * unsupported.
     *
     * `online` is empty in BOTH, because an on-device recogniser returns an empty online list BY
     * CONSTRUCTION. That emptiness is the absence of an answer, and treating it as a finding is the
     * bug these tests exist to pin.
     */

    /**
     * **2026-08-09 — BEFORE ANYBODY DOWNLOADED ANYTHING.** `en-GB` installed; `hi-IN` and `en-IN`
     * sitting in `supportedOnDevice`, one tap away.
     *
     * KEPT AS HISTORY RATHER THAN OVERWRITTEN, and it is not sentiment: this is the reading
     * [DwPackState.NO_OFFLINE_PACK] was derived from, and it is the only reading in which any of our
     * nineteen is DOWNLOADABLE at all. Delete it and there is no fixture left in which the download
     * control is drawable, so the tests that prove a designer can be offered a pack would have nothing
     * to run against.
     */
    private val galaxyM32BeforeTheDownloads = DwRecognitionSupport(
        installedOnDevice = listOf("en-GB"),
        supportedOnDevice = listOf(
            "en-US", "de-DE", "es-ES", "fr-FR", "it-IT", "en-AU", "en-IE", "en-SG", "ja-JP",
            "de-AT", "de-BE", "de-CH", "en-CA", "en-IN", "es-US", "fr-BE", "fr-CA", "fr-CH",
            "hi-IN", "id-ID", "it-CH", "ko-KR", "pt-BR", "th-TH", "cmn-Hans-CN", "cmn-Hant-TW",
            "pl-PL", "ru-RU", "tr-TR", "vi-VN",
        ),
        pendingOnDevice = emptyList(),
        online = emptyList(),
    )

    /**
     * **2026-08-13 — THE SAME HANDSET, AFTER BOTH PACKS WERE FETCHED. This is the phone as it is.**
     *
     * `hi-IN` and `en-IN` have MOVED from `supportedOnDevice` to `installedOnDevice`, which is what a
     * completed download looks like through this API — the list shrinks from thirty to twenty-eight and
     * **contains no Indian language at all**. So on this reading there is nothing left of ours to
     * download, and every rule about offering a pack has to hold with an empty offer.
     *
     * THIS IS THE READING THE SHIPPING SCREEN IS JUDGED AGAINST. `dwPackRowWorthShowing` draws two rows
     * here, both INSTALLED, and therefore **zero download controls** — the state the settings card is in
     * on the attached phone right now.
     */
    private val galaxyM32Today = DwRecognitionSupport(
        installedOnDevice = listOf("hi-IN", "en-IN", "en-GB"),
        supportedOnDevice = listOf(
            "en-US", "de-DE", "es-ES", "fr-FR", "it-IT", "en-AU", "en-IE", "en-SG", "ja-JP",
            "de-AT", "de-BE", "de-CH", "en-CA", "es-US", "fr-BE", "fr-CA", "fr-CH",
            "id-ID", "it-CH", "ko-KR", "pt-BR", "th-TH", "cmn-Hans-CN", "cmn-Hant-TW",
            "pl-PL", "ru-RU", "tr-TR", "vi-VN",
        ),
        pendingOnDevice = emptyList(),
        online = emptyList(),
    )

    // ---------------------------------------------------------------------------------------
    // The honest unknown — the rule the whole file exists for
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a device that was never asked is unknown for every language, not unsupported`() {
        val states = dwPackStates(tags, support = null)
        assertEquals(19, states.size)
        assertTrue(
            "API < 33 has no way to ask, so no language may be claimed either way",
            states.values.all { it == DwPackState.UNKNOWN }
        )
    }

    @Test
    fun `four empty lists are unknown, never nineteen rows of not-supported`() {
        // A recogniser that answers with nothing has told us nothing. Reading that as "this phone
        // supports none of the nineteen" is the wrong claim that would paint over a handset which
        // may well have Hindi installed.
        val states = dwPackStates(tags, DwRecognitionSupport())
        assertTrue(states.values.all { it == DwPackState.UNKNOWN })
    }

    @Test
    fun `unknown never becomes a download offer, on any connection`() {
        DwConnection.entries.forEach { connection ->
            assertEquals(
                "an unknown state must never draw a button that spends data",
                DwPackOffer.UNKNOWN,
                dwPackOffer(DwPackState.UNKNOWN, connection)
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Tag matching
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an underscored, upper-cased tag from an OEM recogniser still matches`() {
        val support = DwRecognitionSupport(installedOnDevice = listOf("HI_IN"))
        assertEquals(DwPackState.INSTALLED, dwPackState("hi-IN", support))
    }

    @Test
    fun `a bare language covers its regional tag`() {
        // A device reporting "or" has an Odia pack, and it serves or-IN.
        val support = DwRecognitionSupport(installedOnDevice = listOf("or"))
        assertEquals(DwPackState.INSTALLED, dwPackState("or-IN", support))
    }

    @Test
    fun `en-US installed does NOT mean en-IN installed`() {
        // The asymmetry that keeps this from being a "close enough" matcher. They are different
        // packs; an Indian-English sentence transcribed by a US model is the silent wrong output
        // that EXTRA_LANGUAGE_PREFERENCE exists to avoid in DwDictation.
        val support = DwRecognitionSupport(
            installedOnDevice = listOf("en-US"),
            supportedOnDevice = listOf("en-IN"),
        )
        assertEquals(DwPackState.DOWNLOADABLE, dwPackState("en-IN", support))
    }

    @Test
    fun `normalisation leaves an already-normal tag alone`() {
        assertEquals("hi-in", dwNormalizeLanguageTag("hi-in"))
        assertEquals("hi-in", dwNormalizeLanguageTag(" hi_IN "))
    }

    // ---------------------------------------------------------------------------------------
    // Precedence between the four platform lists
    // ---------------------------------------------------------------------------------------

    @Test
    fun `installed wins over pending, because dictation works right now`() {
        // A pack that works AND has an update queued must read "Works offline", not "Downloading" —
        // the designer is deciding whether they can dictate in this village today.
        val support = DwRecognitionSupport(
            installedOnDevice = listOf("hi-IN"),
            pendingOnDevice = listOf("hi-IN"),
        )
        assertEquals(DwPackState.INSTALLED, dwPackState("hi-IN", support))
    }

    @Test
    fun `pending wins over supported, so one file is never paid for twice`() {
        val support = DwRecognitionSupport(
            pendingOnDevice = listOf("or-IN"),
            supportedOnDevice = listOf("or-IN"),
        )
        assertEquals(DwPackState.DOWNLOADING, dwPackState("or-IN", support))
        assertEquals(DwPackOffer.IN_PROGRESS, dwPackOffer(DwPackState.DOWNLOADING, DwConnection.UNMETERED))
    }

    @Test
    fun `a language only the network can serve is network-only, not unsupported`() {
        val support = DwRecognitionSupport(
            installedOnDevice = listOf("en-IN"),
            online = listOf("ks-IN"),
        )
        assertEquals(DwPackState.NETWORK_ONLY, dwPackState("ks-IN", support))
        // Still nothing to download — but the row must not tell them the language is unavailable.
        assertEquals(DwPackOffer.UNAVAILABLE, dwPackOffer(DwPackState.NETWORK_ONLY, DwConnection.UNMETERED))
    }

    /**
     * The seventeen. This is the state most of the list is really in, and it used to be UNSUPPORTED.
     *
     * `galaxyM32BeforeTheDownloads` has an empty `online` list because that is what an on-device recogniser returns —
     * so "not in any list" is the absence of an answer about online support, not a finding. Calling
     * it UNSUPPORTED printed "does not offer Manipuri (Meitei) ... pick another language" over a
     * language the network recogniser dictates perfectly well.
     */
    @Test
    fun `a language in no list, from an engine that never answered about online, is not called unsupported`() {
        assertEquals(DwPackState.NO_OFFLINE_PACK, dwPackState("mni-IN", galaxyM32BeforeTheDownloads))
        // Still nothing to download — the correction is to the sentence, not to the button.
        assertEquals(DwPackOffer.UNAVAILABLE, dwPackOffer(DwPackState.NO_OFFLINE_PACK, DwConnection.UNMETERED))
        assertFalse(dwMayAsk(dwPackOffer(DwPackState.NO_OFFLINE_PACK, DwConnection.UNMETERED), requested = false, refused = false))
    }

    /**
     * UNSUPPORTED survives, but only where it has been earned: an engine that DID answer about
     * online support, with this language absent from that answer.
     */
    @Test
    fun `a language absent from an engine that did report online languages is unsupported`() {
        val answered = DwRecognitionSupport(
            installedOnDevice = listOf("en-IN"),
            supportedOnDevice = listOf("hi-IN"),
            online = listOf("hi-IN", "en-IN", "bn-IN"),
        )
        assertEquals(DwPackState.UNSUPPORTED, dwPackState("mni-IN", answered))
        assertEquals(DwPackState.NETWORK_ONLY, dwPackState("bn-IN", answered))
    }


    // ---------------------------------------------------------------------------------------
    // The offer decision — the one that spends somebody's prepaid data
    // ---------------------------------------------------------------------------------------

    @Test
    fun `no connection never offers a download, however downloadable the pack is`() {
        assertEquals(DwPackOffer.NO_CONNECTION, dwPackOffer(DwPackState.DOWNLOADABLE, DwConnection.NONE))
    }

    @Test
    fun `a downloadable pack is offered on both metered and unmetered connections`() {
        // Metered is NOT a refusal — it is a warning attached to a deliberate tap. Refusing outright
        // would strand a designer in a district town with no Wi-Fi for a fortnight.
        assertEquals(DwPackOffer.DOWNLOAD, dwPackOffer(DwPackState.DOWNLOADABLE, DwConnection.METERED))
        assertEquals(DwPackOffer.DOWNLOAD, dwPackOffer(DwPackState.DOWNLOADABLE, DwConnection.UNMETERED))
    }

    @Test
    fun `an installed pack is never offered for download`() {
        DwConnection.entries.forEach { connection ->
            assertEquals(DwPackOffer.INSTALLED, dwPackOffer(DwPackState.INSTALLED, connection))
        }
    }

    @Test
    fun `only DOWNLOADABLE can ever produce a DOWNLOAD offer`() {
        // The invariant behind "nothing may auto-download and nothing may offer what it cannot do":
        // exactly one state may draw the button, and only when there is a connection.
        DwPackState.entries.forEach { state ->
            DwConnection.entries.forEach { connection ->
                val offer = dwPackOffer(state, connection)
                if (offer == DwPackOffer.DOWNLOAD) {
                    assertEquals(DwPackState.DOWNLOADABLE, state)
                    assertFalse(connection == DwConnection.NONE)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Asking twice — the rule that stands between a tap and somebody's prepaid bundle
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a pack already asked for is not offered again, so one file is not fetched twice`() {
        // Android 13 gives no download callback at all, so the row goes on reading DOWNLOADABLE for
        // ever. Without this rule a second tick sends a second triggerModelDownload for one model.
        assertFalse(dwMayAsk(DwPackOffer.DOWNLOAD, requested = true, refused = false))
    }

    @Test
    fun `a REFUSED request may be tried again, because it fetched nothing to pay twice for`() {
        // The refusal leaves a note saying "check the connection and try again". A row that says
        // that with no control left to do it with is how a designer concludes the feature is broken.
        assertTrue(dwMayAsk(DwPackOffer.DOWNLOAD, requested = true, refused = true))
    }

    @Test
    fun `nothing but a DOWNLOAD offer may ever draw the control, asked for or not`() {
        DwPackOffer.entries.forEach { offer ->
            listOf(false, true).forEach { requested ->
                listOf(false, true).forEach { refused ->
                    if (dwMayAsk(offer, requested, refused)) {
                        assertEquals(
                            "only a DOWNLOAD offer may draw a control that spends data",
                            DwPackOffer.DOWNLOAD,
                            offer
                        )
                    }
                }
            }
        }
        // And an offline handset never reaches DOWNLOAD in the first place, so the two rules
        // compose: no connection, no control, however many times it has been asked for.
        assertFalse(dwMayAsk(dwPackOffer(DwPackState.DOWNLOADABLE, DwConnection.NONE), false, false))
    }

    // ---------------------------------------------------------------------------------------
    // API < 33 — the words said where nothing can be found out
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the sentence for a phone that cannot be asked claims nothing about any pack`() {
        // The wrong claim this whole feature exists to avoid. On Android 8 and 9 — a large share of
        // the field fleet — there is no way to find out, so the copy must say it cannot be asked and
        // must not slip into telling a designer a pack is present or missing.
        //
        // RE-POINTED 2026-08-13 from the deleted `DW_PACK_CANNOT_ASK_SENTENCE`, whose 58 words said
        // this in three clauses and one of them narrated the ladder. The state is unchanged and is
        // still on screen — `DwLanguagePackController.refresh` assigns this exact string for API < 33
        // and the pack list draws it — so this test still guards the sentence a designer reads.
        val unaskable = dwPackEmptyListSentence(canAsk = false)
        assertTrue(unaskable.contains("cannot be asked"))
        listOf("not installed", "is missing", "is installed", "not downloaded", "no pack")
            .forEach { claim ->
                assertFalse(
                    "the unaskable sentence must claim nothing either way, but says \"$claim\"",
                    unaskable.contains(claim, ignoreCase = true)
                )
            }
        // And the state it pairs with is rendered as the word itself, not as an absence.
        assertEquals("Unknown", dwPackStateLabel(DwPackState.UNKNOWN))
    }

    // ---------------------------------------------------------------------------------------
    // The M32, end to end
    // ---------------------------------------------------------------------------------------

    /**
     * The whole list as the real handset produces it — the test the fabricated fixture was hiding.
     *
     * Two downloadable, seventeen with no offline pack, and NOT ONE called unsupported. English
     * (India) is downloadable rather than installed: what is installed is `en-GB`, and this file's
     * `dwTagCovers` deliberately refuses to let one region's pack stand in for another's.
     */
    @Test
    fun `on the real M32 exactly two of the nineteen can be fetched and none is called unsupported`() {
        val states = dwPackStates(tags, galaxyM32BeforeTheDownloads)
        assertEquals(
            listOf("hi-IN", "en-IN"),
            states.filterValues { it == DwPackState.DOWNLOADABLE }.keys.toList()
        )
        assertEquals(17, states.count { it.value == DwPackState.NO_OFFLINE_PACK })
        assertEquals(0, states.count { it.value == DwPackState.UNSUPPORTED })
        // Odia — the language of the state these workshops are run in — is one of the seventeen.
        // The fixture this replaces claimed it was downloadable, which is why nobody looked.
        assertEquals(DwPackState.NO_OFFLINE_PACK, states["or-IN"])
    }

    @Test
    fun `every dictation language gets exactly one state and none is missed`() {
        val states = dwPackStates(tags, galaxyM32BeforeTheDownloads)
        assertEquals(tags, states.keys.toList())
        assertTrue(states.values.none { it == DwPackState.UNKNOWN })
    }

    // ---------------------------------------------------------------------------------------
    // The words
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every state has its own short label and its own sentence`() {
        val labels = DwPackState.entries.map { dwPackStateLabel(it) }
        assertEquals("two states sharing a label would read as one state", labels.size, labels.toSet().size)
        val sentences = DwPackState.entries.map { dwPackStateSentence("Odia", it) }
        assertEquals(sentences.size, sentences.toSet().size)
        assertTrue("every sentence names the language it is about", sentences.all { "Odia" in it })
    }

    @Test
    fun `no cost sentence invents a download size the platform never reports`() {
        // triggerModelDownload reports no size anywhere in the API. A figure here would be fiction
        // printed next to somebody's prepaid data bundle.
        DwConnection.entries.forEach { connection ->
            val sentence = dwDownloadCostSentence(connection)
            assertFalse("$connection names a size", Regex("""\d+\s*(MB|GB|KB)""").containsMatchIn(sentence))
        }
    }

    // ---------------------------------------------------------------------------------------
    // WHICH LANGUAGES ARE A ROW AT ALL — the owner's central instruction, which had NO TESTS
    //
    // `dwPackRowWorthShowing`, `dwPackRows` and `dwPackEmptyListSentence` are the whole of
    // *"For the language that have no download option at all, why even show them in the very first
    // place?"*, and until 2026-08-13 not one line of them was covered anywhere in the test tree —
    // while `DwLanguagePackList`'s own docstring called the row decision "pure and tested". It was
    // pure and untested, which is the more dangerous of the two halves to be missing: the rule
    // decides what a designer is shown, so a regression in it is invisible from the code and visible
    // on every handset.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `exactly three states are a row, and the other four are not`() {
        // Written as a partition over the WHOLE enum rather than as three positive cases, so a state
        // added later fails here instead of quietly becoming a row (or quietly not becoming one).
        val rows = DwPackState.entries.filter { dwPackRowWorthShowing(it) }
        assertEquals(
            listOf(DwPackState.INSTALLED, DwPackState.DOWNLOADING, DwPackState.DOWNLOADABLE).sortedBy { it.name },
            rows.sortedBy { it.name }
        )
    }

    @Test
    fun `a state no button on the screen can change is never a row`() {
        // The three that no control can move, and the reason the seventeen paragraphs went. Each of
        // these languages still dictates through the server; that is said in the dictation flow.
        assertFalse(dwPackRowWorthShowing(DwPackState.NO_OFFLINE_PACK))
        assertFalse(dwPackRowWorthShowing(DwPackState.NETWORK_ONLY))
        assertFalse(dwPackRowWorthShowing(DwPackState.UNSUPPORTED))
    }

    @Test
    fun `UNKNOWN is not a row, so an Android 12 handset gets one line and not nineteen`() {
        /*
         * THE CALL WORTH DEFENDING, AND THE ONE MOST LIKELY TO BE "FIXED" BACK. On API < 32 every one
         * of the nineteen is UNKNOWN. Admitting UNKNOWN as a row would put nineteen rows of "Unknown"
         * on those handsets — nineteen rows of nothing-to-do, which is the exact failure this rule
         * exists to end, spelled with a different word.
         */
        assertFalse(dwPackRowWorthShowing(DwPackState.UNKNOWN))
        val unasked = dwPackStates(tags, null)
        assertEquals(19, unasked.size)
        assertTrue("a phone that cannot be asked draws no rows at all", dwPackRows(unasked).isEmpty())
    }

    @Test
    fun `on the handset as it is today the list is two installed rows and no download control`() {
        /*
         * THE MEASUREMENT THE WHOLE INSTRUCTION TURNS ON, on the phone attached right now. Nineteen
         * rows and a paragraph each became two rows and no button.
         */
        val states = dwPackStates(tags, galaxyM32Today)
        assertEquals(listOf("hi-IN", "en-IN"), dwPackRows(states))
        assertEquals(
            "both rows read the same two words, and no third row is drawn",
            listOf(DwPackState.INSTALLED, DwPackState.INSTALLED),
            dwPackRows(states).map { states[it] }
        )
        // AND NOT ONE DOWNLOAD CONTROL, which is the half a reader would assume rather than check:
        // both rows are INSTALLED, so `dwPackOffer` answers INSTALLED and `dwMayAsk` is false for
        // every one of the nineteen. The only control left on the card is "Check again".
        assertTrue(
            tags.none {
                dwMayAsk(
                    offer = dwPackOffer(states[it]!!, DwConnection.UNMETERED),
                    requested = false,
                    refused = false,
                )
            }
        )
    }

    @Test
    fun `the same rule on the earlier reading draws the two rows a designer could act on`() {
        // THE OTHER DIRECTION, so the filter cannot be passing by accident on a phone where nothing is
        // downloadable. On 2026-08-09 the same two languages were one tap away, and the rule shows
        // exactly them — with a live download offer, which is what makes the row worth drawing.
        val states = dwPackStates(tags, galaxyM32BeforeTheDownloads)
        assertEquals(listOf("hi-IN", "en-IN"), dwPackRows(states))
        assertEquals(
            listOf(DwPackState.DOWNLOADABLE, DwPackState.DOWNLOADABLE),
            dwPackRows(states).map { states[it] }
        )
        assertTrue(
            dwPackRows(states).all {
                dwMayAsk(
                    offer = dwPackOffer(states[it]!!, DwConnection.UNMETERED),
                    requested = false,
                    refused = false,
                )
            }
        )
    }

    @Test
    fun `the row order is the dropdown's order and is never re-sorted by state`() {
        // A settings list that reordered itself by state would move the row a designer is reaching for
        // as they reach for it. Hindi is first in DW_DICTATION_LANGUAGES and must be first here.
        val mixed = DwRecognitionSupport(
            installedOnDevice = listOf("en-IN"),
            supportedOnDevice = listOf("hi-IN"),
        )
        assertEquals(listOf("hi-IN", "en-IN"), dwPackRows(dwPackStates(tags, mixed)))
    }

    @Test
    fun `an empty list says so in one line, and never that dictation is unavailable`() {
        /*
         * THE SENTENCE MOST AT RISK OF OVERSTATING. Zero installable packs is NOT zero dictation:
         * every one of the nineteen still works through the server and seventeen only ever did. A line
         * a designer reads as "dictation is unavailable" makes them stop using a control that works.
         */
        val canAsk = dwPackEmptyListSentence(canAsk = true)
        assertTrue("it must say dictation still works", canAsk.contains("Dictation works as normal"))
        val cannotAsk = dwPackEmptyListSentence(canAsk = false)
        assertTrue(cannotAsk.contains("Dictation still works"))
        // The two cases differ because the NEXT MOVE differs: one is "your phone's catalogue has none
        // of these", the other is "this Android version cannot be asked — use the phone's settings".
        assertTrue(cannotAsk.contains("phone's own speech or keyboard settings"))
        assertFalse(canAsk.contains("cannot be asked"))
        listOf(canAsk, cannotAsk).forEach {
            assertFalse("no empty list may read as broken dictation", it.contains("unavailable"))
            assertFalse(it.contains("cannot dictate"))
        }
    }

    @Test
    fun `no row and no empty-list line ever announces that a language uses the internet`() {
        /*
         * PRINCIPLE 2, AS AN ASSERTION. *"What is the point of giving user a disclaimer that the
         * language would utilise internet?"* Two constants carrying exactly that disclaimer were
         * deleted on 2026-08-13; this is what stops a third being written. It is asked of the strings
         * a designer meets WITHOUT having asked for anything — the row labels and the empty line.
         *
         * It deliberately does NOT police `dwPackStateSentence`, which is drawn only inside the offer
         * dialog a designer opened themselves, nor `dwDownloadCostSentence`, which is money.
         */
        val standing = DwPackState.entries.map { dwPackStateLabel(it) } +
            listOf(dwPackEmptyListSentence(canAsk = true), dwPackEmptyListSentence(canAsk = false))
        standing.forEach { line ->
            assertFalse("“$line” announces the network", line.contains("internet", ignoreCase = true))
            assertFalse("“$line” announces the network", line.contains("connection", ignoreCase = true))
            assertFalse("“$line” announces the network", line.contains("network", ignoreCase = true))
        }
    }
}
