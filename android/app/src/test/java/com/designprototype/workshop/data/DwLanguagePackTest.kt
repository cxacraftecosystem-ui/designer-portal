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

    /** The M32 as verified in docs/DICTATION-DEVICE-VERIFICATION.md: English there, Hindi not. */
    private val galaxyM32 = DwRecognitionSupport(
        installedOnDevice = listOf("en-IN", "en-US"),
        supportedOnDevice = listOf("hi-IN", "or-IN", "bn-IN", "ta-IN", "te-IN", "mr-IN", "gu-IN"),
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

    @Test
    fun `a language in no list at all is unsupported`() {
        assertEquals(DwPackState.UNSUPPORTED, dwPackState("mni-IN", galaxyM32))
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
    // The M32, end to end
    // ---------------------------------------------------------------------------------------

    @Test
    fun `on the verified M32 Hindi and Odia are downloadable and English already works`() {
        val states = dwPackStates(tags, galaxyM32)
        assertEquals(DwPackState.DOWNLOADABLE, states["hi-IN"])
        assertEquals(DwPackState.DOWNLOADABLE, states["or-IN"])
        assertEquals(DwPackState.INSTALLED, states["en-IN"])
        // Sanskrit, Konkani, Manipuri, Kashmiri, Sindhi, Nepali and the rest are in none of the
        // lists this handset returned, and the honest answer is that it cannot do them.
        assertEquals(DwPackState.UNSUPPORTED, states["sa-IN"])
    }

    @Test
    fun `every dictation language gets exactly one state and none is missed`() {
        val states = dwPackStates(tags, galaxyM32)
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

    @Test
    fun `the offline sentence promises no download`() {
        assertFalse(DW_PACK_NO_CONNECTION_SENTENCE.contains("Download", ignoreCase = true))
        assertTrue(DW_PACK_NO_CONNECTION_SENTENCE.contains("no connection"))
    }
}
