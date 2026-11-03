package com.designprototype.workshop.data

import com.designprototype.workshop.ui.designworkshop.DW_DICTATION_LANGUAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwLanguagePackTest {

    private val tags = DW_DICTATION_LANGUAGES.map { it.tag }

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

    @Test
    fun `an underscored, upper-cased tag from an OEM recogniser still matches`() {
        val support = DwRecognitionSupport(installedOnDevice = listOf("HI_IN"))
        assertEquals(DwPackState.INSTALLED, dwPackState("hi-IN", support))
    }

    @Test
    fun `a bare language covers its regional tag`() {
        val support = DwRecognitionSupport(installedOnDevice = listOf("or"))
        assertEquals(DwPackState.INSTALLED, dwPackState("or-IN", support))
    }

    @Test
    fun `en-US installed does NOT mean en-IN installed`() {
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

    @Test
    fun `installed wins over pending, because dictation works right now`() {
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
        assertEquals(DwPackOffer.UNAVAILABLE, dwPackOffer(DwPackState.NETWORK_ONLY, DwConnection.UNMETERED))
    }

    @Test
    fun `a language in no list, from an engine that never answered about online, is not called unsupported`() {
        assertEquals(DwPackState.NO_OFFLINE_PACK, dwPackState("mni-IN", galaxyM32BeforeTheDownloads))
        assertEquals(DwPackOffer.UNAVAILABLE, dwPackOffer(DwPackState.NO_OFFLINE_PACK, DwConnection.UNMETERED))
        assertFalse(dwMayAsk(dwPackOffer(DwPackState.NO_OFFLINE_PACK, DwConnection.UNMETERED), requested = false, refused = false))
    }

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


    @Test
    fun `no connection never offers a download, however downloadable the pack is`() {
        assertEquals(DwPackOffer.NO_CONNECTION, dwPackOffer(DwPackState.DOWNLOADABLE, DwConnection.NONE))
    }

    @Test
    fun `a downloadable pack is offered on both metered and unmetered connections`() {
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

    @Test
    fun `a pack already asked for is not offered again, so one file is not fetched twice`() {
        assertFalse(dwMayAsk(DwPackOffer.DOWNLOAD, requested = true, refused = false))
    }

    @Test
    fun `a REFUSED request may be tried again, because it fetched nothing to pay twice for`() {
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
        assertFalse(dwMayAsk(dwPackOffer(DwPackState.DOWNLOADABLE, DwConnection.NONE), false, false))
    }

    @Test
    fun `the sentence for a phone that cannot be asked claims nothing about any pack`() {
        val unaskable = dwPackEmptyListSentence(canAsk = false)
        assertTrue(unaskable.contains("cannot be asked"))
        listOf("not installed", "is missing", "is installed", "not downloaded", "no pack")
            .forEach { claim ->
                assertFalse(
                    "the unaskable sentence must claim nothing either way, but says \"$claim\"",
                    unaskable.contains(claim, ignoreCase = true)
                )
            }
        assertEquals("Unknown", dwPackStateLabel(DwPackState.UNKNOWN))
    }

    @Test
    fun `on the real M32 exactly two of the nineteen can be fetched and none is called unsupported`() {
        val states = dwPackStates(tags, galaxyM32BeforeTheDownloads)
        assertEquals(
            listOf("hi-IN", "en-IN"),
            states.filterValues { it == DwPackState.DOWNLOADABLE }.keys.toList()
        )
        assertEquals(17, states.count { it.value == DwPackState.NO_OFFLINE_PACK })
        assertEquals(0, states.count { it.value == DwPackState.UNSUPPORTED })
        assertEquals(DwPackState.NO_OFFLINE_PACK, states["or-IN"])
    }

    @Test
    fun `every dictation language gets exactly one state and none is missed`() {
        val states = dwPackStates(tags, galaxyM32BeforeTheDownloads)
        assertEquals(tags, states.keys.toList())
        assertTrue(states.values.none { it == DwPackState.UNKNOWN })
    }

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
        DwConnection.entries.forEach { connection ->
            val sentence = dwDownloadCostSentence(connection)
            assertFalse("$connection names a size", Regex("""\d+\s*(MB|GB|KB)""").containsMatchIn(sentence))
        }
    }

    @Test
    fun `exactly three states are a row, and the other four are not`() {
        val rows = DwPackState.entries.filter { dwPackRowWorthShowing(it) }
        assertEquals(
            listOf(DwPackState.INSTALLED, DwPackState.DOWNLOADING, DwPackState.DOWNLOADABLE).sortedBy { it.name },
            rows.sortedBy { it.name }
        )
    }

    @Test
    fun `a state no button on the screen can change is never a row`() {
        assertFalse(dwPackRowWorthShowing(DwPackState.NO_OFFLINE_PACK))
        assertFalse(dwPackRowWorthShowing(DwPackState.NETWORK_ONLY))
        assertFalse(dwPackRowWorthShowing(DwPackState.UNSUPPORTED))
    }

    @Test
    fun `UNKNOWN is not a row, so an Android 12 handset gets one line and not nineteen`() {
        assertFalse(dwPackRowWorthShowing(DwPackState.UNKNOWN))
        val unasked = dwPackStates(tags, null)
        assertEquals(19, unasked.size)
        assertTrue("a phone that cannot be asked draws no rows at all", dwPackRows(unasked).isEmpty())
    }

    @Test
    fun `on the handset as it is today the list is two installed rows and no download control`() {
        val states = dwPackStates(tags, galaxyM32Today)
        assertEquals(listOf("hi-IN", "en-IN"), dwPackRows(states))
        assertEquals(
            "both rows read the same two words, and no third row is drawn",
            listOf(DwPackState.INSTALLED, DwPackState.INSTALLED),
            dwPackRows(states).map { states[it] }
        )
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
        val mixed = DwRecognitionSupport(
            installedOnDevice = listOf("en-IN"),
            supportedOnDevice = listOf("hi-IN"),
        )
        assertEquals(listOf("hi-IN", "en-IN"), dwPackRows(dwPackStates(tags, mixed)))
    }

    @Test
    fun `an empty list says so in one line, and never that dictation is unavailable`() {
        val canAsk = dwPackEmptyListSentence(canAsk = true)
        assertTrue("it must say dictation still works", canAsk.contains("Dictation works as normal"))
        val cannotAsk = dwPackEmptyListSentence(canAsk = false)
        assertTrue(cannotAsk.contains("Dictation still works"))
        assertTrue(cannotAsk.contains("phone's own speech or keyboard settings"))
        assertFalse(canAsk.contains("cannot be asked"))
        listOf(canAsk, cannotAsk).forEach {
            assertFalse("no empty list may read as broken dictation", it.contains("unavailable"))
            assertFalse(it.contains("cannot dictate"))
        }
    }

    @Test
    fun `no row and no empty-list line ever announces that a language uses the internet`() {
        val standing = DwPackState.entries.map { dwPackStateLabel(it) } +
            listOf(dwPackEmptyListSentence(canAsk = true), dwPackEmptyListSentence(canAsk = false))
        standing.forEach { line ->
            assertFalse("“$line” announces the network", line.contains("internet", ignoreCase = true))
            assertFalse("“$line” announces the network", line.contains("connection", ignoreCase = true))
            assertFalse("“$line” announces the network", line.contains("network", ignoreCase = true))
        }
    }
}
