package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwDictationLadderTest {

    private val galaxyM32 = DwRecognitionSupport(
        installedOnDevice = listOf("en-GB"),
        supportedOnDevice = listOf("en-US", "en-IN", "hi-IN", "de-DE", "ja-JP"),
        pendingOnDevice = emptyList(),
        online = emptyList(),
    )

    private val odiaOnM32 = dwPackState("or-IN", galaxyM32)

    private fun conditions(
        packState: DwPackState,
        onDeviceEngine: Boolean = true,
        networkRecogniser: Boolean = true,
        online: Boolean = true,
        serverRouteUnavailable: Boolean = false,
        deviceRefusedLanguage: Boolean = false,
        tier3Consent: DwTier3Consent = DwTier3Consent.GRANTED,
        dailyCapSpent: Boolean = false,
        dailyCapLimit: Int? = null,
        workshopOnServer: Boolean = true,
        appModelServesLanguage: Boolean = false,
        appModelRefusedLanguage: Boolean = false,
    ) = DwDictationConditions(
        languageLabel = "Odia",
        packState = packState,
        onDeviceEngine = onDeviceEngine,
        networkRecogniser = networkRecogniser,
        online = online,
        serverRouteUnavailable = serverRouteUnavailable,
        deviceRefusedLanguage = deviceRefusedLanguage,
        appModelServesLanguage = appModelServesLanguage,
        appModelRefusedLanguage = appModelRefusedLanguage,
        tier3Consent = tier3Consent,
        dailyCapSpent = dailyCapSpent,
        dailyCapLimit = dailyCapLimit,
        workshopOnServer = workshopOnServer,
    )

    @Test
    fun `the seventeen reach the server before Google's network engine`() {
        assertEquals(DwPackState.NO_OFFLINE_PACK, odiaOnM32)
        val plan = dwDictationLadder(conditions(odiaOnM32))
        assertEquals(
            listOf(DwDictationRung.SERVER_DICTATE, DwDictationRung.NETWORK_RECOGNISER),
            plan.rungs
        )
        assertNull("A ladder with rungs left must not also carry a giving-up sentence", plan.exhausted)
    }

    @Test
    fun `an installed pack is still asked first`() {
        val plan = dwDictationLadder(conditions(DwPackState.INSTALLED))
        assertEquals(DwDictationRung.ON_DEVICE_PACK, plan.first)
        assertEquals(
            listOf(
                DwDictationRung.ON_DEVICE_PACK,
                DwDictationRung.SERVER_DICTATE,
                DwDictationRung.NETWORK_RECOGNISER,
            ),
            plan.rungs
        )
    }

    @Test
    fun `a pack that is merely downloadable or on its way does not get a turn`() {
        assertEquals(DwPackState.DOWNLOADABLE, dwPackState("hi-IN", galaxyM32))
        listOf(DwPackState.DOWNLOADABLE, DwPackState.DOWNLOADING).forEach { state ->
            val plan = dwDictationLadder(conditions(state))
            assertFalse(
                "$state is not on the phone, so rung 1 would only produce a code 13",
                plan.rungs.contains(DwDictationRung.ON_DEVICE_PACK)
            )
            assertEquals(DwDictationRung.SERVER_DICTATE, plan.first)
        }
    }

    @Test
    fun `an engine that has refused the language loses its turn even when the list says installed`() {
        val plan = dwDictationLadder(
            conditions(DwPackState.INSTALLED, deviceRefusedLanguage = true)
        )
        assertFalse(plan.rungs.contains(DwDictationRung.ON_DEVICE_PACK))
        assertEquals(DwDictationRung.SERVER_DICTATE, plan.first)
    }

    @Test
    fun `a phone that cannot be asked tries its own engine before spending money`() {
        val plan = dwDictationLadder(
            conditions(DwPackState.UNKNOWN, onDeviceEngine = false)
        )
        assertEquals(
            listOf(DwDictationRung.NETWORK_RECOGNISER, DwDictationRung.SERVER_DICTATE),
            plan.rungs
        )
    }

    @Test
    fun `once the phone's engine has refused, the server outranks the generic one`() {
        val plan = dwDictationLadder(
            conditions(DwPackState.UNKNOWN, onDeviceEngine = false, deviceRefusedLanguage = true)
        )
        assertEquals(
            listOf(DwDictationRung.SERVER_DICTATE, DwDictationRung.NETWORK_RECOGNISER),
            plan.rungs
        )
    }

    @Test
    fun `an unasked API 33 phone tries its own pack, then the server, then the generic engine`() {
        val plan = dwDictationLadder(conditions(DwPackState.UNKNOWN, onDeviceEngine = true))
        assertEquals(
            listOf(
                DwDictationRung.ON_DEVICE_PACK,
                DwDictationRung.SERVER_DICTATE,
                DwDictationRung.NETWORK_RECOGNISER,
            ),
            plan.rungs
        )
    }

    @Test
    fun `no connection leaves nothing but the sentence`() {
        val plan = dwDictationLadder(conditions(odiaOnM32, online = false))
        assertTrue(plan.rungs.isEmpty())
        val sentence = assertSentence(plan.exhausted)
        assertTrue(
            "The sentence must name the missing connection: $sentence",
            sentence.contains("connection")
        )
        assertTrue("It must name a next move that works: $sentence", sentence.contains("Type the answer in"))
    }

    @Test
    fun `only a pack the phone would actually fetch is worth naming the settings list for`() {
        var namedSettings = 0
        DwPackState.values().forEach { packState ->
            listOf(false, true).forEach { refused ->
                val plan = dwDictationLadder(
                    conditions(packState, online = false, deviceRefusedLanguage = refused)
                )
                val sentence = plan.exhausted ?: return@forEach
                assertSentence(sentence)
                if (!sentence.contains("Settings")) return@forEach
                namedSettings++
                assertEquals(
                    "Only a pack this phone would fetch may be pointed at: $packState",
                    DwPackOffer.NO_CONNECTION,
                    dwPackOffer(packState, DwConnection.NONE)
                )
                assertTrue(
                    "The settings card is headed 'Offline dictation languages': $sentence",
                    sentence.contains("Settings › Offline dictation languages")
                )
            }
        }
        assertTrue("The fetchable case must actually have been exercised", namedSettings > 0)
    }

    @Test
    fun `odia offline is never told to download a pack that does not exist`() {
        val sentence = assertSentence(
            dwDictationLadder(conditions(odiaOnM32, online = false)).exhausted
        )
        assertEquals(DwPackOffer.UNAVAILABLE, dwPackOffer(odiaOnM32, DwConnection.NONE))
        assertFalse("There is no Odia pack to add: $sentence", sentence.contains("Settings"))
        assertTrue("It must still name the connection: $sentence", sentence.contains("connection"))
    }

    @Test
    fun `no connection still dictates in a language whose pack is on the phone`() {
        val plan = dwDictationLadder(conditions(DwPackState.INSTALLED, online = false))
        assertEquals(listOf(DwDictationRung.ON_DEVICE_PACK), plan.rungs)
        assertNull(plan.exhausted)
    }

    @Test
    fun `a server that is not configured falls to the generic engine and then says why`() {
        val withEngine = dwDictationLadder(conditions(odiaOnM32, serverRouteUnavailable = true))
        assertEquals(listOf(DwDictationRung.NETWORK_RECOGNISER), withEngine.rungs)

        val withNothing = dwDictationLadder(
            conditions(odiaOnM32, serverRouteUnavailable = true, networkRecogniser = false)
        )
        assertTrue(withNothing.rungs.isEmpty())
        val sentence = assertSentence(withNothing.exhausted)
        assertTrue(
            "A 503 must reach the designer as 'not set up': $sentence",
            sentence.contains("not set up")
        )
    }

    @Test
    fun `a language no recogniser offers can still go to the server`() {
        val plan = dwDictationLadder(conditions(DwPackState.UNSUPPORTED))
        assertEquals(listOf(DwDictationRung.SERVER_DICTATE), plan.rungs)

        val offline = dwDictationLadder(conditions(DwPackState.UNSUPPORTED, online = false))
        assertTrue(offline.rungs.isEmpty())
        assertSentence(offline.exhausted)
    }

    @Test
    fun `an unanswered consent keeps the craft-aware rung because consent is assumed by default`() {
        val unanswered = dwDictationLadder(conditions(odiaOnM32, tier3Consent = DwTier3Consent.NOT_RECORDED))
        val granted = dwDictationLadder(conditions(odiaOnM32, tier3Consent = DwTier3Consent.GRANTED))
        assertEquals(granted.rungs, unanswered.rungs)
        assertTrue(unanswered.rungs.contains(DwDictationRung.SERVER_DICTATE))
        assertNull(unanswered.suppressed)
    }

    @Test
    fun `a refusal on record withholds the craft-aware rung and says so`() {
        val refused = dwDictationLadder(
            conditions(odiaOnM32, tier3Consent = DwTier3Consent.REFUSED)
        )
        assertEquals(listOf(DwDictationRung.NETWORK_RECOGNISER), refused.rungs)
        assertNull("Something still answers, so nothing is exhausted", refused.exhausted)
        val b = assertSentence(refused.suppressed)
        assertTrue("The refused one says it is on record: $b", b.contains("answer on record"))
        assertFalse("A consent note must not suggest trying again: $b", b.contains("try again"))
    }

    @Test
    fun `an installed pack dictates offline whatever the consent says`() {
        DwTier3Consent.values().forEach { consent ->
            val offline = dwDictationLadder(
                conditions(DwPackState.INSTALLED, online = false, tier3Consent = consent)
            )
            assertEquals(
                "Rung 1 is free and local, so consent has nothing to say about it: $consent",
                listOf(DwDictationRung.ON_DEVICE_PACK),
                offline.rungs
            )
            assertNull("No connection means nothing was withheld by consent", offline.suppressed)

            val online = dwDictationLadder(conditions(DwPackState.INSTALLED, tier3Consent = consent))
            assertEquals(DwDictationRung.ON_DEVICE_PACK, online.first)
        }
    }

    @Test
    fun `a workshop that is not on the server yet cannot reach the craft-aware rung`() {
        val plan = dwDictationLadder(conditions(odiaOnM32, workshopOnServer = false))
        assertEquals(
            "Everything else stays exactly where it was; only rung 2 is excised",
            listOf(DwDictationRung.NETWORK_RECOGNISER),
            plan.rungs
        )
        assertNull("Google's engine still answers, so nothing is exhausted", plan.exhausted)
        val note = assertSentence(plan.suppressed)
        assertTrue("It says the workshop has not gone up: $note", note.contains("only on this phone"))
        assertTrue("And names the button that sends it: $note", note.contains("Upload the workshop from its card"))
        assertFalse("Nothing here is fixed by trying again: $note", note.contains("try again"))

        val alone = dwDictationLadder(
            conditions(odiaOnM32, networkRecogniser = false, workshopOnServer = false)
        )
        assertTrue("Nothing may be left to try: $alone", alone.rungs.isEmpty())
        val sentence = assertSentence(alone.exhausted)
        assertTrue("It names the send: $sentence", sentence.contains("upload the workshop from its card"))
        assertTrue("And the keyboard: $sentence", sentence.contains("Type the answer in"))
    }

    @Test
    fun `a refusal is named before a workshop that has not been sent up`() {
        val both = conditions(
            odiaOnM32,
            tier3Consent = DwTier3Consent.REFUSED,
            workshopOnServer = false,
        )
        val note = assertSentence(dwDictationLadder(both).suppressed)
        assertTrue("The artisan's answer comes first: $note", note.contains("answer on record"))
        assertFalse("One sentence, one next move: $note", note.lowercase().contains("upload the workshop"))

        val sentence = assertSentence(
            dwDictationLadder(both.copy(networkRecogniser = false)).exhausted
        )
        assertTrue("And the same order with nothing left: $sentence", sentence.contains("answer on record"))
        assertFalse("Still one next move: $sentence", sentence.lowercase().contains("upload the workshop"))

        val unanswered = assertSentence(
            dwDictationLadder(both.copy(tier3Consent = DwTier3Consent.NOT_RECORDED)).suppressed
        )
        assertTrue(unanswered.lowercase().contains("upload the workshop"))
    }

    @Test
    fun `a spent allowance names the number it was told and no other`() {
        val known = assertSentence(
            dwDictationLadder(
                conditions(
                    odiaOnM32,
                    networkRecogniser = false,
                    dailyCapSpent = true,
                    dailyCapLimit = 40,
                )
            ).exhausted
        )
        assertTrue("The refusal must name the ceiling: $known", known.contains("all 40 of today's"))

        val unknown = assertSentence(
            dwDictationLadder(
                conditions(
                    odiaOnM32,
                    networkRecogniser = false,
                    dailyCapSpent = true,
                    dailyCapLimit = null,
                )
            ).exhausted
        )
        assertFalse(
            "A phone that was never told the ceiling may not print one: $unknown",
            unknown.any { it.isDigit() }
        )
        listOf(known, unknown).forEach {
            assertTrue("The refusal must name when it lifts: $it", it.contains("midnight India time"))
            assertTrue("And that it starts again on its own: $it", it.contains("starts again after midnight"))
            assertTrue("And the move that works now: $it", it.contains("Type the answer in"))
        }

        val panel = assertSentence(
            dwDictationLadder(conditions(odiaOnM32, dailyCapSpent = true, dailyCapLimit = 40)).suppressed
        )
        assertTrue("The panel line must name the ceiling too: $panel", panel.contains("all 40 of today's"))
        assertTrue("And when it lifts: $panel", panel.contains("midnight India time"))
        val panelUnknown = assertSentence(
            dwDictationLadder(conditions(odiaOnM32, dailyCapSpent = true, dailyCapLimit = null)).suppressed
        )
        assertFalse(
            "A phone that was never told the ceiling may not print one on the panel either: $panelUnknown",
            panelUnknown.any { it.isDigit() },
        )
    }

    @Test
    fun `a ceiling of none says so instead of claiming a spent allowance`() {
        val plan = dwDictationLadder(
            conditions(odiaOnM32, networkRecogniser = false, dailyCapSpent = true, dailyCapLimit = 0)
        )
        val sentence = assertSentence(plan.exhausted)
        assertFalse("Never 'all 0 of today's': $sentence", sentence.contains("all 0"))
        assertFalse("Nobody used anything: $sentence", sentence.contains("You have used"))
        assertTrue("It names the setting: $sentence", sentence.contains("allowance is set to none"))

        val note = assertSentence(
            dwDictationLadder(
                conditions(odiaOnM32, dailyCapSpent = true, dailyCapLimit = 0)
            ).suppressed
        )
        assertFalse("The panel line must not claim a spent day either: $note", note.contains("all 0"))
    }

    @Test
    fun `a withheld rung never blames the server or an administrator`() {
        withheldStates().forEach { conditions ->
            val plan = dwDictationLadder(conditions.copy(networkRecogniser = false))
            assertTrue("Nothing may be left to try here: $conditions", plan.rungs.isEmpty())
            val sentence = assertSentence(plan.exhausted)
            if (conditions.dailyCapLimit == 0 && conditions.dailyCapSpent) return@forEach
            assertFalse(
                "Transcription is fine; do not say it is not set up: $sentence",
                sentence.contains("not set up")
            )
            assertFalse(
                "Nobody should be sent to an administrator over a consent or an allowance: $sentence",
                sentence.contains("administrator")
            )
            assertTrue("The keyboard is always the move that works: $sentence", sentence.contains("Type the answer in"))
        }
    }

    @Test
    fun `an unconfigured server outranks a missing consent, because consent would not fix it`() {
        withheldStates().forEach { conditions ->
            val sentence = assertSentence(
                dwDictationLadder(
                    conditions.copy(networkRecogniser = false, serverRouteUnavailable = true)
                ).exhausted
            )
            assertTrue(
                "A 503 must still reach the designer as 'not set up': $sentence",
                sentence.contains("not set up")
            )
        }
    }

    @Test
    fun `no connection never blames the consent or the allowance`() {
        withheldStates().forEach { conditions ->
            val plan = dwDictationLadder(conditions.copy(online = false))
            val sentence = assertSentence(plan.exhausted)
            assertTrue("The connection is the cause: $sentence", sentence.contains("connection"))
            listOf("allowance", "Nobody has recorded", "answer on record").forEach { claim ->
                assertFalse("Offline, this is a false cause: $claim in $sentence", sentence.contains(claim))
            }
            assertNull("Nothing was withheld — there was nothing to withhold", plan.suppressed)
        }
    }

    @Test
    fun `neither new fact points at the settings list`() {
        withheldStates().forEach { conditions ->
            val plan = dwDictationLadder(conditions.copy(networkRecogniser = false))
            assertFalse(
                "There is no pack to add for either fact: ${plan.exhausted}",
                plan.exhausted!!.contains("Settings")
            )
            val withARungLeft = dwDictationLadder(conditions)
            withARungLeft.suppressed?.let {
                assertFalse("Nor on the panel: $it", it.contains("Settings"))
            }
        }
    }

    private fun withheldStates(): List<DwDictationConditions> = listOf(
        conditions(odiaOnM32, tier3Consent = DwTier3Consent.REFUSED),
        conditions(odiaOnM32, dailyCapSpent = true, dailyCapLimit = 40),
        conditions(odiaOnM32, dailyCapSpent = true, dailyCapLimit = null),
        conditions(odiaOnM32, dailyCapSpent = true, dailyCapLimit = 0),
        conditions(odiaOnM32, workshopOnServer = false),
    )

    @Test
    fun `every combination obeys the rules each rung is allowed to exist under`() {
        var checked = 0
        DwPackState.values().forEach { packState ->
            listOf(false, true).forEach { onDeviceEngine ->
                listOf(false, true).forEach { networkRecogniser ->
                    listOf(false, true).forEach { online ->
                        listOf(false, true).forEach { serverUnavailable ->
                            listOf(false, true).forEach { refused ->
                                DwTier3Consent.values().forEach { consent ->
                                    listOf(false, true).forEach { capSpent ->
                                        listOf(null, 0, 40)
                                            .flatMap { limit -> listOf(false, true).map { limit to it } }
                                            .forEach { (capLimit, onServer) ->
                                            checked++
                                            val conditions = conditions(
                                                packState = packState,
                                                onDeviceEngine = onDeviceEngine,
                                                networkRecogniser = networkRecogniser,
                                                online = online,
                                                serverRouteUnavailable = serverUnavailable,
                                                deviceRefusedLanguage = refused,
                                                tier3Consent = consent,
                                                dailyCapSpent = capSpent,
                                                dailyCapLimit = capLimit,
                                                workshopOnServer = onServer,
                                            )
                                            val plan = dwDictationLadder(conditions)

                                            assertEquals(
                                                "A rung must never be offered twice — that is the bounce the " +
                                                    "one-shot `retried` flag used to prevent: $conditions",
                                                plan.rungs.distinct(),
                                                plan.rungs
                                            )
                                            assertEquals(
                                                "A ladder is exhausted exactly when it has no rungs: $conditions",
                                                plan.rungs.isEmpty(),
                                                plan.exhausted != null
                                            )
                                            if (plan.rungs.contains(DwDictationRung.ON_DEVICE_PACK)) {
                                                assertTrue("No engine, no rung 1: $conditions", onDeviceEngine)
                                                assertFalse("A refused engine gets no turn: $conditions", refused)
                                            }
                                            if (plan.rungs.contains(DwDictationRung.SERVER_DICTATE)) {
                                                assertTrue("An upload needs a connection: $conditions", online)
                                                assertFalse(
                                                    "A server that said it is not configured is not asked again: $conditions",
                                                    serverUnavailable
                                                )
                                                assertNotEquals(
                                                    "Nothing may be sent from a workshop whose artisan refused: $conditions",
                                                    DwTier3Consent.REFUSED,
                                                    consent
                                                )
                                                assertFalse(
                                                    "A spent allowance may not be spent again: $conditions",
                                                    capSpent
                                                )
                                                assertTrue(
                                                    "There is no gated route to post to without a workshop: $conditions",
                                                    onServer
                                                )
                                            }
                                            if (plan.rungs.contains(DwDictationRung.NETWORK_RECOGNISER)) {
                                                assertTrue("Google's network engine needs a connection: $conditions", online)
                                                assertTrue("No speech service, no rung 3: $conditions", networkRecogniser)
                                            }
                                            plan.exhausted?.let { assertSentence(it) }
                                            plan.suppressed?.let {
                                                assertSentence(it)
                                                assertTrue(
                                                    "A suppression note needs a rung left to be about: $conditions",
                                                    plan.rungs.isNotEmpty()
                                                )
                                                assertNull(
                                                    "One withheld rung, one sentence: $conditions",
                                                    plan.exhausted
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
        }
        assertEquals(
            "Every combination must actually have been visited",
            7 * 2 * 2 * 2 * 2 * 2 * 3 * 2 * 3 * 2,
            checked
        )
    }

    @Test
    fun `only an unasked phone puts the generic engine ahead of the craft-aware one`() {
        DwPackState.values().forEach { packState ->
            listOf(false, true).forEach { onDeviceEngine ->
                listOf(false, true).forEach { refused ->
                    val plan = dwDictationLadder(
                        conditions(
                            packState = packState,
                            onDeviceEngine = onDeviceEngine,
                            deviceRefusedLanguage = refused,
                        )
                    )
                    val network = plan.rungs.indexOf(DwDictationRung.NETWORK_RECOGNISER)
                    val server = plan.rungs.indexOf(DwDictationRung.SERVER_DICTATE)
                    if (network >= 0 && server >= 0 && network < server) {
                        assertEquals(
                            "Only a phone with no answer at all may try the generic engine first",
                            DwPackState.UNKNOWN,
                            packState
                        )
                        assertFalse(onDeviceEngine)
                        assertFalse(refused)
                    }
                }
            }
        }
    }

    @Test
    fun `after walks the plan once and then stops`() {
        val plan = dwDictationLadder(conditions(DwPackState.UNKNOWN, onDeviceEngine = true))
        assertEquals(DwDictationRung.SERVER_DICTATE, plan.after(DwDictationRung.ON_DEVICE_PACK))
        assertEquals(DwDictationRung.NETWORK_RECOGNISER, plan.after(DwDictationRung.SERVER_DICTATE))
        assertNull(plan.after(DwDictationRung.NETWORK_RECOGNISER))
    }

    @Test
    fun `a rung that is not in the plan has no next rung`() {
        val plan = dwDictationLadder(conditions(odiaOnM32))
        assertFalse(plan.rungs.contains(DwDictationRung.ON_DEVICE_PACK))
        assertNull(plan.after(DwDictationRung.ON_DEVICE_PACK))
    }

    @Test
    fun `the byte cap mirrors the server's`() {
        assertEquals(6L * 1024 * 1024, DW_DICTATION_MAX_BYTES)
        assertNull("A clip exactly at the cap is accepted, as the server accepts it", dwDictationOversize(DW_DICTATION_MAX_BYTES))
        assertNull(dwDictationOversize(0L))
        val refusal = assertSentence(dwDictationOversize(DW_DICTATION_MAX_BYTES + 1))
        assertTrue("The refusal must name the size limit: $refusal", refusal.contains("6 MB"))
    }

    @Test
    fun `four minutes at the requested bit rate is well under the byte cap`() {
        val bytesPerSecond = DW_DICTATION_BITS_PER_SECOND / 8
        val expected = bytesPerSecond.toLong() * (DW_DICTATION_MAX_MILLIS / 1000)
        assertEquals(960_000L, expected)
        assertTrue(
            "A full-length clip must leave room for container overhead and a wayward encoder",
            expected * 4 < DW_DICTATION_MAX_BYTES
        )
        assertEquals(16_000, DW_DICTATION_SAMPLE_RATE_HZ)
        assertEquals(1, DW_DICTATION_CHANNELS)
    }

    @Test
    fun `the standing sentences name a next move and no error code`() {
        listOf(
            DW_DICTATION_NOTHING_RECORDED,
            DW_DICTATION_NO_WORDS,
            DW_DICTATION_NOT_CONFIGURED,
            DW_DICTATION_UPLOAD_FAILED,
            DW_DICTATION_BUSY,
            DW_DICTATION_TRANSCRIPTION_FAILED,
            DW_DICTATION_SERVER_UNREACHABLE,
            DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN,
            DW_DICTATION_CAP_SPENT_SAY_AGAIN,
            DW_DICTATION_CONSENT_REFUSED,
        ).forEach { sentence ->
            assertSentence(sentence)
            assertFalse("No error codes in a sentence a designer reads: $sentence", sentence.contains("code "))
        }
        assertFalse(
            "A bodiless 409 does not say the artisan was never asked: $DW_DICTATION_CONSENT_REFUSED",
            DW_DICTATION_CONSENT_REFUSED.contains("Nobody has recorded")
        )
        assertFalse(
            "Nor that they refused: $DW_DICTATION_CONSENT_REFUSED",
            DW_DICTATION_CONSENT_REFUSED.contains("answer on record")
        )
        assertFalse(
            "No retry can change a consent: $DW_DICTATION_CONSENT_REFUSED",
            DW_DICTATION_CONSENT_REFUSED.contains("try again")
        )
        assertTrue(
            "It sends the designer to the one screen that reconciles the two answers",
            DW_DICTATION_CONSENT_REFUSED.contains("workshop's own screen")
        )
        assertTrue(
            "And says nothing was kept, because a silent loss is the failure these sentences prevent",
            DW_DICTATION_CONSENT_REFUSED.contains("Nothing was saved")
        )
        assertTrue(
            "The 503 must be shown as 'not set up'",
            DW_DICTATION_NOT_CONFIGURED.contains("not set up") ||
                DW_DICTATION_NOT_CONFIGURED.contains("Transcription is not set up")
        )
        assertTrue(
            "A throttled dictation must not promise the queue's automatic retry it will never get",
            DW_DICTATION_BUSY.contains("nothing is queued")
        )
    }

    @Test
    fun `both 503 sentences name the configuration, and the one with a rung left asks for a repeat`() {
        listOf(DW_DICTATION_NOT_CONFIGURED, DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN).forEach { sentence ->
            assertTrue(
                "A 503 must reach the designer as 'not set up': $sentence",
                sentence.contains("Transcription is not set up")
            )
        }
        assertTrue(
            "With a rung left, the designer has to be asked to speak again: $DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN",
            DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN.contains("say it again")
        )
        assertTrue(
            "With nothing left, the only move is the keyboard: $DW_DICTATION_NOT_CONFIGURED",
            DW_DICTATION_NOT_CONFIGURED.contains("Type the answer in")
        )
    }

    @Test
    fun `a spent allowance mid-dictation asks for the words again rather than opening another engine`() {
        assertTrue(
            "The designer has to be asked to speak again: $DW_DICTATION_CAP_SPENT_SAY_AGAIN",
            DW_DICTATION_CAP_SPENT_SAY_AGAIN.contains("say it again")
        )
        assertTrue(
            "And told nothing was kept, because a silent loss is the failure this prevents",
            DW_DICTATION_CAP_SPENT_SAY_AGAIN.contains("Nothing was saved")
        )
        assertTrue(
            "And which engine answers next, since it behaves differently",
            DW_DICTATION_CAP_SPENT_SAY_AGAIN.contains("phone's own recogniser")
        )
        assertFalse(
            "It may not promise an allowance it has no number for",
            DW_DICTATION_CAP_SPENT_SAY_AGAIN.any { it.isDigit() }
        )
    }

    @Test
    fun `an answer with no words in it never promises a retry that nothing will make`() {
        assertEquals(DW_DICTATION_BUSY, dwDictationServerAnswerSentence("RATE_LIMITED", null))

        val mixed = "Deepgram transcription rate-limited (HTTP 429); will retry automatically.; " +
            "Transcription failed (openai). The provider's reply is in the server log."
        assertEquals(DW_DICTATION_BUSY, dwDictationServerAnswerSentence("FAILED", mixed))

        assertEquals(
            DW_DICTATION_TRANSCRIPTION_FAILED,
            dwDictationServerAnswerSentence("FAILED", "All transcription providers failed.")
        )
        assertEquals(DW_DICTATION_NO_WORDS, dwDictationServerAnswerSentence("EMPTY", null))

        val standing = listOf(DW_DICTATION_BUSY, DW_DICTATION_TRANSCRIPTION_FAILED, DW_DICTATION_NO_WORDS)
        listOf("RATE_LIMITED", "FAILED", "EMPTY", "COMPLETED", "").forEach { status ->
            listOf(null, "", mixed, "Transcription unavailable: configure ELEVENLABS_API_KEY.").forEach { message ->
                val answer = assertSentence(dwDictationServerAnswerSentence(status, message))
                assertTrue("$status/$message produced text of its own: $answer", answer in standing)
                assertFalse("No key names on a phone screen: $answer", answer.contains("API_KEY"))
            }
        }
    }

    @Test
    fun `a pack that has arrived cancels the refusal the engine gave before it did`() {
        DwDictationRun.forgetRefusal("hi-IN")
        assertFalse(DwDictationRun.engineHasRefused("hi-IN"))

        DwDictationRun.engineRefused("hi_IN")
        assertTrue("A refusal must survive a difference of separator", DwDictationRun.engineHasRefused("hi-IN"))
        assertFalse(
            "While refused, the offline engine loses its turn",
            dwDictationLadder(conditions(DwPackState.INSTALLED, deviceRefusedLanguage = true))
                .rungs.contains(DwDictationRung.ON_DEVICE_PACK),
        )

        DwDictationRun.forgetRefusal("hi-in")
        assertFalse(
            "A pack that has landed must clear the refusal, whatever case it is asked in",
            DwDictationRun.engineHasRefused("hi-IN"),
        )
        assertTrue(
            "Once forgotten, the free engine is asked first again",
            dwDictationLadder(conditions(DwPackState.INSTALLED, deviceRefusedLanguage = false))
                .rungs.contains(DwDictationRung.ON_DEVICE_PACK),
        )
    }

    @Test
    fun `a blank server id is no server id, and rung 2 goes with it`() {
        DwDictationRun.publishWorkshopConsent(DwTier3Consent.GRANTED, serverWorkshopId = "   ")
        val blank = DwDictationRun.publishedWorkshop()
        assertNull(
            "A blank id must never be published as a workshop the server could load",
            blank.serverId,
        )
        assertFalse(
            "And the craft-aware rung closes with it rather than posting to an empty path segment",
            dwDictationLadder(conditions(odiaOnM32, workshopOnServer = blank.serverId != null))
                .rungs.contains(DwDictationRung.SERVER_DICTATE),
        )

        val real = DwDictationRun.publishWorkshopConsent(DwTier3Consent.GRANTED, serverWorkshopId = "wk-1")
        assertEquals("wk-1", DwDictationRun.publishedWorkshop().serverId)
        assertTrue(
            "With an id and a granted answer the rung is back",
            dwDictationLadder(conditions(odiaOnM32, workshopOnServer = true))
                .rungs.contains(DwDictationRung.SERVER_DICTATE),
        )

        DwDictationRun.forgetWorkshopConsent(real)
        assertNull(DwDictationRun.publishedWorkshop().serverId)
        assertEquals(DwTier3Consent.NOT_RECORDED, DwDictationRun.publishedWorkshop().consent)
    }

    @Test
    fun `with no model installed, which is every handset today, no plan anywhere changes`() {
        DwPackState.entries.forEach { pack ->
            listOf(true, false).forEach { onDevice ->
                listOf(true, false).forEach { online ->
                    listOf(true, false).forEach { network ->
                        val plan = dwDictationLadder(
                            conditions(
                                packState = pack,
                                onDeviceEngine = onDevice,
                                online = online,
                                networkRecogniser = network,
                            )
                        )
                        assertFalse(
                            "$pack / onDevice=$onDevice / online=$online / network=$network: a rung " +
                                "appeared for a model that cannot be installed on any handset",
                            plan.rungs.contains(DwDictationRung.APP_SPEECH_MODEL)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `an installed model that hears Odia gives the courtyard a rung it has never had`() {
        val today = dwDictationLadder(conditions(odiaOnM32, online = false))
        assertTrue("today: nothing offline can take Odia", today.rungs.isEmpty())

        val withModel = dwDictationLadder(
            conditions(odiaOnM32, online = false, appModelServesLanguage = true)
        )
        assertEquals(listOf(DwDictationRung.APP_SPEECH_MODEL), withModel.rungs)
        assertNull("nothing is exhausted when a rung answers", withModel.exhausted)
    }

    @Test
    fun `our model sits below Android's own pack and above everything that costs money`() {
        val plan = dwDictationLadder(
            conditions(DwPackState.INSTALLED, appModelServesLanguage = true)
        )
        assertEquals(
            listOf(
                DwDictationRung.ON_DEVICE_PACK,
                DwDictationRung.APP_SPEECH_MODEL,
                DwDictationRung.SERVER_DICTATE,
                DwDictationRung.NETWORK_RECOGNISER,
            ),
            plan.rungs
        )
    }

    @Test
    fun `on a handset that cannot be asked about packs, our model still outranks the free guess`() {
        val plan = dwDictationLadder(
            conditions(
                DwPackState.UNKNOWN,
                onDeviceEngine = false,
                appModelServesLanguage = true,
            )
        )
        assertEquals(DwDictationRung.APP_SPEECH_MODEL, plan.rungs.first())
        assertTrue(plan.rungs.contains(DwDictationRung.NETWORK_RECOGNISER))
        assertTrue(plan.rungs.contains(DwDictationRung.SERVER_DICTATE))
    }

    @Test
    fun `a language our model cannot hear keeps exactly the ladder it has today`() {
        val plan = dwDictationLadder(conditions(odiaOnM32, appModelServesLanguage = false))
        assertFalse(plan.rungs.contains(DwDictationRung.APP_SPEECH_MODEL))
        assertEquals(
            "the craft-aware rung first and Google's generic engine behind it — today's ladder",
            listOf(DwDictationRung.SERVER_DICTATE, DwDictationRung.NETWORK_RECOGNISER),
            plan.rungs
        )
    }

    @Test
    fun `our model only ever adds a rung, and never moves or removes one, on any handset`() {
        DwPackState.entries.forEach { pack ->
            listOf(true, false).forEach { onDevice ->
                listOf(true, false).forEach { online ->
                    listOf(true, false).forEach { network ->
                        listOf(true, false).forEach { workshopUp ->
                            val where = "$pack / onDevice=$onDevice / online=$online / " +
                                "network=$network / workshopOnServer=$workshopUp"
                            val without = dwDictationLadder(
                                conditions(
                                    packState = pack,
                                    onDeviceEngine = onDevice,
                                    online = online,
                                    networkRecogniser = network,
                                    workshopOnServer = workshopUp,
                                )
                            )
                            val with = dwDictationLadder(
                                conditions(
                                    packState = pack,
                                    onDeviceEngine = onDevice,
                                    online = online,
                                    networkRecogniser = network,
                                    workshopOnServer = workshopUp,
                                    appModelServesLanguage = true,
                                )
                            )
                            assertTrue(
                                "$where: the rung our model should have added is missing",
                                with.rungs.contains(DwDictationRung.APP_SPEECH_MODEL)
                            )
                            assertEquals(
                                "$where: our model changed a ladder it may only add to",
                                without.rungs,
                                with.rungs.filterNot { it == DwDictationRung.APP_SPEECH_MODEL }
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `our model never gates on consent, because nothing leaves the device`() {
        listOf(DwTier3Consent.NOT_RECORDED, DwTier3Consent.REFUSED, DwTier3Consent.GRANTED).forEach { consent ->
            val plan = dwDictationLadder(
                conditions(odiaOnM32, tier3Consent = consent, appModelServesLanguage = true)
            )
            assertTrue(
                "consent=$consent closed a rung that sends nothing anywhere",
                plan.rungs.contains(DwDictationRung.APP_SPEECH_MODEL)
            )
        }
        assertTrue(
            dwDictationLadder(
                conditions(odiaOnM32, dailyCapSpent = true, dailyCapLimit = 40, appModelServesLanguage = true)
            ).rungs.contains(DwDictationRung.APP_SPEECH_MODEL)
        )
    }

    @Test
    fun `a model that refuses the language on this handset loses the rung, like a pack that does`() {
        val plan = dwDictationLadder(
            conditions(odiaOnM32, appModelServesLanguage = true, appModelRefusedLanguage = true)
        )
        assertFalse(plan.rungs.contains(DwDictationRung.APP_SPEECH_MODEL))
        assertTrue("and the rest of the ladder is untouched", plan.rungs.contains(DwDictationRung.SERVER_DICTATE))
    }

    @Test
    fun `a model that refuses with no signal does not blame the courtyard for this app's fault`() {
        val sentence = assertSentence(
            dwDictationLadder(
                conditions(
                    odiaOnM32,
                    online = false,
                    appModelServesLanguage = true,
                    appModelRefusedLanguage = true,
                )
            ).exhausted
        )
        assertTrue(sentence.contains("The speech model on this phone"))
        assertTrue("it names the move that works, without narrating a fault",
            sentence.contains("Type the answer in"))
        assertFalse(
            "a model on this phone needs no connection, so the connection is not what stopped it",
            sentence.contains("Dictation in Odia on this phone needs a connection")
        )
    }

    @Test
    fun `a fixable cause is still named ahead of our model's unfixable refusal`() {
        val sentence = assertSentence(
            dwDictationLadder(
                conditions(
                    odiaOnM32,
                    networkRecogniser = false,
                    tier3Consent = DwTier3Consent.REFUSED,
                    appModelServesLanguage = true,
                    appModelRefusedLanguage = true,
                )
            ).exhausted
        )
        assertTrue(sentence.contains("answer on record"))
    }

    private fun assertSentence(text: String?): String {
        assertNotNull("A sentence was expected and there was none", text)
        val sentence = text!!
        assertTrue("Blank is not an answer", sentence.isNotBlank())
        assertTrue("An error must be a sentence, not a fragment: $sentence", sentence.length > 30)
        assertTrue("A sentence ends in a full stop: $sentence", sentence.trim().endsWith("."))
        return sentence
    }
}
