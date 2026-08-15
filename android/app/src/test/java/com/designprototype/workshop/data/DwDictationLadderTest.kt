package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dictation ladder, on the desktop JVM.
 *
 * WHAT THIS FILE IS FOR. The defect it exists to prevent is not a crash and would never show up as
 * one: it is the phone quietly using a DIFFERENT transcription service from the web and producing
 * different words for the same audio. Nothing on screen looks wrong when that happens — a transcript
 * appears, in the right script, in the right box — and the only way to catch it is to assert the
 * ORDER the engines are asked in, which is what this file does.
 *
 * The order is a pure function of the facts in [DwDictationConditions] — five about the handset when
 * this file was written, and now also the workshop's recorded consent, this designer's allowance and
 * whether there is a workshop on the server to send a clip under — so it is checkable here, on a
 * machine with no microphone and no speech service, exactly as `DwLanguagePackTest` checks the pack
 * states that feed it. Counted rather than named, because the count went stale twice: a docstring that
 * says "five" beside a data class holding eleven is the first thing a reader stops trusting.
 */
class DwDictationLadderTest {

    /**
     * WHAT THE FLEET'S GALAXY M32 ACTUALLY ANSWERS, trimmed to the entries this decision turns on.
     *
     * Measured 2026-08-09 from the handset itself and written up in
     * docs/DICTATION-LANGUAGE-PACK-MEASUREMENT.md; the full thirty-entry list is in
     * `DwLanguagePackTest.galaxyM32`, which is where it belongs — repeating it here would give the
     * repository two copies of one measurement to keep in step. What matters below is the shape of
     * the answer: `hi-IN` and `en-IN` are downloadable, `or-IN` is in none of the lists, and `online`
     * is EMPTY because an on-device recogniser returns an empty online list by construction.
     *
     * The pack states are derived through [dwPackState] rather than written down as enum literals, so
     * this test starts from the device's own words and not from somebody's summary of them.
     */
    private val galaxyM32 = DwRecognitionSupport(
        installedOnDevice = listOf("en-GB"),
        supportedOnDevice = listOf("en-US", "en-IN", "hi-IN", "de-DE", "ja-JP"),
        pendingOnDevice = emptyList(),
        online = emptyList(),
    )

    /** Odia — the language of the state these workshops run in, and in none of the phone's lists. */
    private val odiaOnM32 = dwPackState("or-IN", galaxyM32)

    /**
     * THE DEFAULTS LIVE HERE AND NOT ON [DwDictationConditions], and that is the point of the helper.
     *
     * The data class deliberately requires [DwDictationConditions.tier3Consent],
     * [DwDictationConditions.dailyCapSpent], [DwDictationConditions.dailyCapLimit] and
     * [DwDictationConditions.workshopOnServer] with no defaults,
     * because there is exactly one production construction site and a permissive default there would
     * make a third-party send the thing that happens by omission. Defaulting them in THIS helper instead
     * is what lets the twenty-four tests written before plan §6 go on asserting what they were written to
     * assert: a consented workshop with an allowance left, which is the ordinary state of the fleet.
     *
     * GRANTED and not NOT_RECORDED, deliberately. The honest default would delete SERVER_DICTATE from
     * about ten existing expectations at once and hide the real diff of this change inside a rewrite of
     * every test in the file; the gate itself is asserted from the tests that name it.
     *
     * `workshopOnServer = true` FOR THE SAME REASON, and it is the ordinary state too: a workshop that has
     * been sent up, which is every workshop the moment it has seen signal once. The tests that name the
     * other case pass it explicitly, and the cross-product below sweeps both.
     */
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
        /*
         * THE DEFAULTS ARE TODAY'S WORLD, AND THAT IS WHY EVERY TEST ABOVE STILL MEANS WHAT IT MEANT.
         * No speech model can be installed on any handset (`DW_TIER1_CATALOGUE` is empty and
         * `DwModelChoiceTest` asserts it), so `appModelServesLanguage = false` is not a convenience
         * — it is the state of the fleet, and the tests that do not mention it are still describing
         * the phone they were written about.
         */
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

    // ---------------------------------------------------------------------------------------
    // The ordering change this whole lane exists for
    // ---------------------------------------------------------------------------------------

    /**
     * ODIA ON THE FLEET'S OWN HANDSET. The one case the plan names, asserted from the measurement.
     *
     * The phone has no offline pack for Odia, so rung 1 cannot serve it at all — and the ladder must
     * then reach the SERVER, with its craft keyterms, BEFORE Google's generic network engine. Before
     * this lane it went straight to the generic engine, which is why an artisan saying "dabu" was
     * written down as "double" on the phone and correctly on the web.
     */
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

    /**
     * AND THE INSTALLED CASE IS UNTOUCHED, which is the other half of the requirement.
     *
     * A pack on the phone is free, instant, streams as the designer speaks and works with no signal.
     * Spending provider credit per sentence where that is true would be a bill for nothing, so rung 1
     * stays first — and the test asserts the POSITION, not merely the presence, because a ladder that
     * merely contains the on-device rung somewhere would pass while charging for every sentence.
     */
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

    /**
     * Hindi on the M32 is DOWNLOADABLE — the platform knows the pack and it is not on the phone.
     *
     * The engine answers ERROR_LANGUAGE_UNAVAILABLE (13) for it, which is the observed failure that
     * started this whole feature, so rung 1 must be skipped rather than tried and failed. Same for a
     * pack whose download has been accepted but has not arrived: Android 13 gives no callback to say
     * when it does, so "asked for" is not "usable".
     */
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

    // ---------------------------------------------------------------------------------------
    // The measurement that outranks every claim
    // ---------------------------------------------------------------------------------------

    /**
     * THE ENGINE'S OWN REFUSAL BEATS THE PACK LIST, including a list that says INSTALLED.
     *
     * `checkRecognitionSupport` is a claim; ERROR_LANGUAGE_UNAVAILABLE from the engine that owns the
     * packs is a measurement of this handset. When they disagree the measurement wins — and it has to
     * be remembered, because the alternative is four hundred prose fields on one stage each paying
     * for the same doomed local attempt before reaching the rung that works.
     */
    @Test
    fun `an engine that has refused the language loses its turn even when the list says installed`() {
        val plan = dwDictationLadder(
            conditions(DwPackState.INSTALLED, deviceRefusedLanguage = true)
        )
        assertFalse(plan.rungs.contains(DwDictationRung.ON_DEVICE_PACK))
        assertEquals(DwDictationRung.SERVER_DICTATE, plan.first)
    }

    /**
     * A HANDSET THAT CANNOT BE ASKED ANYTHING KEEPS THE ORDER IT HAS TODAY.
     *
     * API < 33 has no `checkRecognitionSupport` and no on-device recogniser to create, so the pack
     * state is UNKNOWN for ever and the phone's own default recogniser is all there is to go on. It
     * has not failed yet, it streams partial results, and it costs nothing — and on those handsets it
     * may itself be answering from a pack its owner downloaded years ago. Putting the paid rung ahead
     * of it would spend credit on every sentence of every dictation on every Android 8 to 12 phone in
     * the fleet to fix a divergence nobody has measured there.
     *
     * This is a deliberate reading of plan §1, which reorders the ladder "where rung 1 cannot serve
     * the language at all". On these handsets we have not established that it cannot; we have been
     * unable to ask.
     */
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

    /**
     * The same phone once its own engine HAS refused: the order flips to the craft-aware one.
     *
     * This is the state the M32 is in the moment a code 13 comes back, and it is why the refusal is
     * remembered rather than merely reacted to.
     */
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

    /**
     * API 33+ before the pack check has landed: the free engine first, then the craft one.
     *
     * This is what a designer's very first tap on the M32 actually gets, because the pack check binds
     * a `SpeechRecognizer` and therefore only runs while the language list is open. The wasted local
     * attempt is bounded — one per language per app run — and it is the price of not spending money
     * on a phone that might have had the pack.
     */
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

    // ---------------------------------------------------------------------------------------
    // No signal — the state this whole application is designed around
    // ---------------------------------------------------------------------------------------

    /**
     * WITH NO CONNECTION BOTH ONLINE RUNGS GO, AND NOTHING IS QUEUED.
     *
     * The endpoint is synchronous and stores nothing, and the designer is standing in front of a
     * field waiting for words to appear in it. A dictation put into the offline outbox would arrive
     * as text after the next sync, into a field they finished with a fortnight ago. So rung 2 is
     * simply unavailable, and rung 4 — the sentence — is the honest answer.
     */
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

    /**
     * NO SENTENCE MAY SEND A DESIGNER TO A CONTROL THAT CANNOT HELP THEM, and this is the one that
     * did.
     *
     * The offline sentence used to end "add the pack from Settings › Dictation languages" for every
     * language on every handset. Two things were wrong with it and both are the failure this file
     * exists to catch. It named a menu entry that does not exist — the card is headed "Offline
     * dictation languages" (`ui/AppearanceScreen.kt`). And for the seventeen, ODIA AMONG THEM, there
     * is no pack on Google's catalogue to add at all: `dwPackOffer` answers UNAVAILABLE for
     * NO_OFFLINE_PACK, NETWORK_ONLY and UNSUPPORTED — "no offline pack is coming, and no button can
     * change that" — so a designer who walked to the guest house, found signal, opened Settings and
     * looked for Odia would find nothing there and conclude the app had lied to them.
     *
     * The predicate is asked of `dwPackOffer` here exactly as the sentence asks it, so the test is
     * checking that the two surfaces share one rule rather than restating the rule a second time.
     */
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

    /** The headline case, asserted on its own: Odia offline on the fleet's handset. */
    @Test
    fun `odia offline is never told to download a pack that does not exist`() {
        val sentence = assertSentence(
            dwDictationLadder(conditions(odiaOnM32, online = false)).exhausted
        )
        assertEquals(DwPackOffer.UNAVAILABLE, dwPackOffer(odiaOnM32, DwConnection.NONE))
        assertFalse("There is no Odia pack to add: $sentence", sentence.contains("Settings"))
        assertTrue("It must still name the connection: $sentence", sentence.contains("connection"))
    }

    /** An installed pack still works in a courtyard, which is the entire reason rung 1 exists. */
    @Test
    fun `no connection still dictates in a language whose pack is on the phone`() {
        val plan = dwDictationLadder(conditions(DwPackState.INSTALLED, online = false))
        assertEquals(listOf(DwDictationRung.ON_DEVICE_PACK), plan.rungs)
        assertNull(plan.exhausted)
    }

    // ---------------------------------------------------------------------------------------
    // The 503 — "not configured", never silence
    // ---------------------------------------------------------------------------------------

    /**
     * A deployment with no transcription provider drops rung 2 and says so when nothing is left.
     *
     * The route answers 503 rather than a 200 with an empty string precisely so this is
     * distinguishable from "you said nothing"; the ladder has to keep that distinction rather than
     * quietly leaving the designer with a control that produces nothing.
     */
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
            "A 503 must reach the designer as 'not configured': $sentence",
            sentence.contains("not configured")
        )
    }

    /**
     * UNSUPPORTED is the one state that was actually measured as "this recogniser cannot do this
     * language, offline or on", so no rung of the platform's is offered — but the server still can,
     * and offering it is the difference between a language this app supports and one it does not.
     */
    @Test
    fun `a language no recogniser offers can still go to the server`() {
        val plan = dwDictationLadder(conditions(DwPackState.UNSUPPORTED))
        assertEquals(listOf(DwDictationRung.SERVER_DICTATE), plan.rungs)

        val offline = dwDictationLadder(conditions(DwPackState.UNSUPPORTED, online = false))
        assertTrue(offline.rungs.isEmpty())
        assertSentence(offline.exhausted)
    }

    // ---------------------------------------------------------------------------------------
    // Consent, per workshop — plan §6 answer 3
    // ---------------------------------------------------------------------------------------

    /**
     * AN UNANSWERED CONSENT QUESTION TAKES RUNG 2 AND LEAVES EVERY OTHER RUNG WHERE IT WAS.
     *
     * The headline case of the gate, on the fleet's own handset. Odia has no offline pack there, so the
     * plan was [SERVER_DICTATE, NETWORK_RECOGNISER] and becomes Google's generic engine alone — which
     * means the phone goes back to writing "double" where the artisan said "dabu". That cost is the
     * whole reason the suppression has to be SAID: the dictation still succeeds, and nothing else on
     * screen would differ.
     */
    @Test
    fun `an unanswered consent question withholds the craft-aware rung and says so`() {
        val plan = dwDictationLadder(
            conditions(odiaOnM32, tier3Consent = DwTier3Consent.NOT_RECORDED)
        )
        assertEquals(listOf(DwDictationRung.NETWORK_RECOGNISER), plan.rungs)
        assertNull("Something still answers, so nothing is exhausted", plan.exhausted)
        val note = assertSentence(plan.suppressed)
        assertTrue(
            "The one next move that can work is a person answering the question: $note",
            note.contains("workshop's own screen")
        )
    }

    /**
     * "NOBODY HAS ASKED" AND "THE ANSWER IS NO" GATE IDENTICALLY AND READ DIFFERENTLY.
     *
     * The entire reason the input is three states rather than a boolean. Both stop the send; one is
     * answered by asking the artisan and the other only by the artisan changing their mind, so a
     * designer told the wrong one either goes to ask a question that is already on record or never
     * learns there is a question at all.
     */
    @Test
    fun `a refusal on record does not read as a question nobody has asked`() {
        val unasked = dwDictationLadder(
            conditions(odiaOnM32, tier3Consent = DwTier3Consent.NOT_RECORDED)
        )
        val refused = dwDictationLadder(
            conditions(odiaOnM32, tier3Consent = DwTier3Consent.REFUSED)
        )
        assertEquals(unasked.rungs, refused.rungs)
        val a = assertSentence(unasked.suppressed)
        val b = assertSentence(refused.suppressed)
        assertNotEquals("Two different facts must not share one sentence", a, b)
        assertTrue("The unasked one asks for the answer: $a", a.contains("Nobody has recorded"))
        assertTrue("The refused one says it is on record: $b", b.contains("answer on record"))
        // Neither may suggest a retry: no amount of tapping a microphone changes either fact.
        listOf(a, b).forEach {
            assertFalse("A consent note must not suggest trying again: $it", it.contains("try again"))
        }
    }

    /**
     * RUNG 1 IS NEVER GATED BY CONSENT, WHICH IS THE OTHER HALF OF THE DECISION.
     *
     * A pack on the phone dictates in a courtyard, for free, with no consent question asked of anybody —
     * BECAUSE NOTHING LEAVES THE DEVICE. A gate that took rung 1 as well would withdraw offline
     * dictation from every workshop nobody has been asked about, which is every workshop that predates
     * the question.
     */
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

    // ---------------------------------------------------------------------------------------
    // The workshop the clip is sent under — the route that carries the gate
    // ---------------------------------------------------------------------------------------

    /**
     * A WORKSHOP THE SERVER HAS NEVER HEARD OF HAS NO RUNG 2, WHATEVER THE ARTISAN ANSWERED.
     *
     * THE DEFECT THIS PINS IS THE ONE THIS LANE CLOSED. Rung 2 used to post to
     * `POST /design-workshops/dictate`, which takes no workshop id, consults no `dictationConsent`
     * column and hands the clip straight to the provider chain — so the gate this file spends four tests
     * on gated nothing at the only place it mattered. It now posts to
     * `POST /design-workshops/{id}/dictate`, and an id the server can load is therefore part of the
     * rung's existence rather than a detail of the upload.
     *
     * CONSENT IS GRANTED HERE ON PURPOSE. This is the state a client that trusted consent alone would
     * have posted through the ungated door: the artisan has agreed, the answer is on this phone, and
     * there is still nowhere to send it that could check it.
     *
     * AND THE SENTENCE NAMES THE SEND, NOT AN ADMINISTRATOR AND NOT A RETRY. Sending the workshop up is
     * a move the designer can make and it is the only one that opens this rung.
     */
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
        assertTrue("It says the workshop has not gone up: $note", note.contains("not been sent to the server"))
        assertTrue("And names the button that sends it: $note", note.contains("Send to server"))
        assertFalse("Nothing here is fixed by trying again: $note", note.contains("try again"))

        // With nothing left to hand over to, the same fact gets the exhausted sentence — and the
        // keyboard, which is the move that works while the workshop is still only on this phone.
        val alone = dwDictationLadder(
            conditions(odiaOnM32, networkRecogniser = false, workshopOnServer = false)
        )
        assertTrue("Nothing may be left to try: $alone", alone.rungs.isEmpty())
        val sentence = assertSentence(alone.exhausted)
        assertTrue("It names the send: $sentence", sentence.contains("Send to server"))
        assertTrue("And the keyboard: $sentence", sentence.contains("Type the answer in"))
    }

    /**
     * WITH BOTH MISSING, THE CONSENT IS NAMED FIRST — BECAUSE OF WHERE THE ARTISAN IS STANDING.
     *
     * A courtyard workshop nobody has been asked about is missing two things, and only one of them can
     * be done while the artisan is there: recording their answer needs no signal at all, and sending the
     * workshop up needs signal and no artisan. Naming the send first would have a designer walk out for
     * a bar of signal, come back, and find the artisan gone and the question still unasked.
     *
     * The same ordering argument the file already makes for consent-before-the-cap, applied to the third
     * suppressor, and asserted because it is one `when` arm away from being lost in a tidy-up.
     */
    @Test
    fun `an unasked artisan is named before a workshop that has not been sent up`() {
        val both = conditions(
            odiaOnM32,
            tier3Consent = DwTier3Consent.NOT_RECORDED,
            workshopOnServer = false,
        )
        val note = assertSentence(dwDictationLadder(both).suppressed)
        assertTrue("The artisan's answer comes first: $note", note.contains("Nobody has recorded"))
        assertFalse("One sentence, one next move: $note", note.contains("Send to server"))

        val sentence = assertSentence(
            dwDictationLadder(both.copy(networkRecogniser = false)).exhausted
        )
        assertTrue("And the same order with nothing left: $sentence", sentence.contains("nobody has recorded"))
        assertFalse("Still one next move: $sentence", sentence.contains("Send to server"))
    }

    // ---------------------------------------------------------------------------------------
    // The daily cap — plan §6 answer 1
    // ---------------------------------------------------------------------------------------

    /**
     * THE CAP IS NAMED IN WORDS WHEN IT IS HIT — AND NEVER INVENTED WHEN IT IS NOT KNOWN.
     *
     * Plan §6.1 asks for the number, and `dwDownloadCostSentence` forbids printing a figure this phone
     * was never told: "any figure printed here would be invented … a made-up '≈40 MB' beside a prepaid
     * data bundle would be worse than silence." Both halves are asserted here, because the second one is
     * the one a later "just show the limit" edit would break.
     */
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
        // Both name the boundary, because a designer refused at 21:00 otherwise cannot tell whether
        // that means three hours or eleven — and it is the SERVER's day, not this phone's.
        listOf(known, unknown).forEach {
            assertTrue("The refusal must name when it lifts: $it", it.contains("midnight India time"))
            assertTrue("And whose day that is: $it", it.contains("server's day"))
            assertTrue("And the move that works now: $it", it.contains("Type the answer in"))
        }

        /*
          AND THE PANEL LINE NAMES IT TOO, which nothing asserted until this was added.

          The gap was silent in the way this file exists to catch: with a rung still left, the panel takes
          `suppressed`, and deleting its limit-known arm would fall through to the limit-unknown one — a
          refusal that stops saying how many, on the one surface a designer opens deliberately, with every
          other assertion in this file still passing. `plan.suppressed` is non-null in both shapes, so no
          null check catches it either.
        */
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

    /**
     * AN ALLOWANCE OF NONE IS A SETTING, AND "ALL 0 OF TODAY'S DICTATIONS" IS PROSE THAT READS AS A BUG.
     *
     * The server's own refusal makes this distinction first and for the same reason: a designer who has
     * recorded nothing all morning, told they have used up their allowance, reasonably concludes the app
     * is broken and stops trusting the next message. A ceiling of zero means somebody switched server
     * dictation off, so this is the one cap sentence whose next move is an administrator.
     */
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

    // ---------------------------------------------------------------------------------------
    // The two sentences these arms replaced, and the one they must not replace
    // ---------------------------------------------------------------------------------------

    /**
     * NEITHER NEW FACT MAY BLAME A SERVER THAT IS PERFECTLY CONFIGURED.
     *
     * THE SENTENCES THIS REPLACES were the two online arms, and both of them were reachable only when
     * the server had answered 503. On a correctly configured deployment held up by an unanswered consent
     * question or a spent allowance they asserted "the server has no transcription service configured"
     * and sent the designer to "tell whoever runs the server" — dispatching somebody to bother an
     * administrator when the fix was one tap on their own workshop screen, or tomorrow.
     */
    @Test
    fun `a withheld rung never blames the server or an administrator`() {
        withheldStates().forEach { conditions ->
            val plan = dwDictationLadder(conditions.copy(networkRecogniser = false))
            assertTrue("Nothing may be left to try here: $conditions", plan.rungs.isEmpty())
            val sentence = assertSentence(plan.exhausted)
            // The one exception is a ceiling of zero, which IS a server setting and IS an
            // administrator's to change — see the test above.
            if (conditions.dailyCapLimit == 0 && conditions.dailyCapSpent) return@forEach
            assertFalse(
                "The server is fine; do not say it is not configured: $sentence",
                sentence.contains("not configured")
            )
            assertFalse(
                "Nobody should be sent to an administrator over a consent or an allowance: $sentence",
                sentence.contains("whoever runs the server")
            )
            assertTrue("The keyboard is always the move that works: $sentence", sentence.contains("Type the answer in"))
        }
    }

    /**
     * AND A SERVER THAT REALLY IS UNCONFIGURED STILL SAYS SO, EVEN WHEN A CONSENT IS ALSO MISSING.
     *
     * Both facts are true at once here and only one of them names a next move capable of a different
     * outcome: recording the consent would not produce a transcript from a deployment with no provider.
     * This is what the `!serverRouteUnavailable` guard on each new arm is for, and it is easy to lose in
     * a later tidy-up.
     */
    @Test
    fun `an unconfigured server outranks a missing consent, because consent would not fix it`() {
        withheldStates().forEach { conditions ->
            val sentence = assertSentence(
                dwDictationLadder(
                    conditions.copy(networkRecogniser = false, serverRouteUnavailable = true)
                ).exhausted
            )
            assertTrue(
                "A 503 must still reach the designer as 'not configured': $sentence",
                sentence.contains("not configured")
            )
        }
    }

    /**
     * WITH NO CONNECTION, NEITHER NEW FACT IS MENTIONED AT ALL.
     *
     * Rung 2 is already gone on the connection, and so is rung 3, so the connection is the true blocker
     * and naming consent there would be a false cause: a designer told to "record the artisan's answer"
     * in a courtyard would walk to the workshop screen, record it, come back, and still have no
     * dictation — because what they needed was signal. The `!online` trio is therefore untouched, and
     * this test is what keeps the new arms below it.
     */
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

    /**
     * NO NEW SENTENCE SENDS A DESIGNER TO THE PACK LIST, and a test already pins that for the old ones.
     *
     * Neither a consent nor an allowance has anything for that list to offer, and the existing test
     * `only a pack the phone would actually fetch is worth naming the settings list for` cannot catch it:
     * it loops with `online = false`, where these arms are deliberately unreachable.
     */
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

    /**
     * The states in which rung 2 is withheld while the server itself is perfectly well.
     *
     * ONE LIST, USED BY EVERY TEST ABOVE, so a further suppressor added later either appears in all of
     * them or is visibly absent from one place rather than silently missing from six.
     *
     * THE LAST ENTRY IS THE ONE THIS LIST'S OWN COMMENT PREDICTED — the suppressor added when rung 2
     * moved onto `POST /design-workshops/{id}/dictate`. A workshop with nothing on the server has no id
     * for that route to be asked about, so the rung is withheld with the artisan's answer GRANTED and
     * the allowance untouched; every rule the four above obey it must obey too, which is the whole point
     * of adding it here rather than writing a sixth set of assertions.
     */
    private fun withheldStates(): List<DwDictationConditions> = listOf(
        conditions(odiaOnM32, tier3Consent = DwTier3Consent.NOT_RECORDED),
        conditions(odiaOnM32, tier3Consent = DwTier3Consent.REFUSED),
        conditions(odiaOnM32, dailyCapSpent = true, dailyCapLimit = 40),
        conditions(odiaOnM32, dailyCapSpent = true, dailyCapLimit = null),
        conditions(odiaOnM32, dailyCapSpent = true, dailyCapLimit = 0),
        conditions(odiaOnM32, workshopOnServer = false),
    )

    // ---------------------------------------------------------------------------------------
    // Properties that must hold for every handset, not just the ones written down above
    // ---------------------------------------------------------------------------------------

    /**
     * The whole cross-product, asserting the invariants each rung's own existence depends on.
     *
     * Enumerated rather than sampled because the interesting combinations are the ones nobody thought to
     * write a named test for — which is exactly where a two-line reordering of this function would do
     * its damage.
     *
     * WIDENED BY PLAN §6 from 224 combinations to 4,032: the consent's three states, the allowance spent
     * or not, and the three shapes of ceiling a phone can hold (none it was told, zero, and a real
     * number). The three new dimensions multiply rather than add because the interactions are the whole
     * risk — a spent allowance on an unconsented workshop with a 503-ed server is a state no named test
     * would ever have been written for, and it is the one where a sentence blames the wrong party.
     *
     * WIDENED AGAIN TO 8,064 when rung 2 moved onto the workshop-scoped route: whether there is a
     * workshop on the server for a clip to be sent under. It multiplies for that same reason and for one
     * of its own — this is the dimension a later reader is most likely to think is redundant with the
     * consent, and the invariant below is what says it is not. A GRANTED workshop that has never been
     * sent up is exactly the state where a client that trusted consent alone would post to the door with
     * no gate on it.
     */
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
                                        // The ceiling's three shapes crossed with "is this workshop on the
                                        // server", folded into ONE loop rather than a tenth level of
                                        // nesting: another `forEach` would have re-indented every
                                        // assertion below it and hidden the one line this dimension adds
                                        // inside sixty lines of whitespace diff.
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
                                                // Consent gates rung 2 and NEVER rung 1: nothing leaves the
                                                // device, so there is nothing to consent to.
                                            }
                                            if (plan.rungs.contains(DwDictationRung.SERVER_DICTATE)) {
                                                assertTrue("An upload needs a connection: $conditions", online)
                                                assertFalse(
                                                    "A server that said it is not configured is not asked again: $conditions",
                                                    serverUnavailable
                                                )
                                                assertEquals(
                                                    "Nothing may be sent from a workshop that has not cleared it: $conditions",
                                                    DwTier3Consent.GRANTED,
                                                    consent
                                                )
                                                assertFalse(
                                                    "A spent allowance may not be spent again: $conditions",
                                                    capSpent
                                                )
                                                // THE ONE THAT CLOSED THE UNCONSENTED DOOR. Rung 2 is an
                                                // upload to `POST /design-workshops/{id}/dictate`, so a
                                                // plan that offers it without a workshop the server can
                                                // load is a plan whose only reachable URL is the one that
                                                // consults no consent at all.
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
                                            // A withheld-rung note is NON-FATAL by definition: it may only
                                            // appear while something else can still answer, and never
                                            // beside a sentence that has already said the same thing.
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

    /**
     * THE GENERIC ENGINE MAY ONLY OUTRANK THE CRAFT-AWARE ONE ON A PHONE WE COULD NOT ASK.
     *
     * The single property this lane turns on, asserted over every combination rather than over the
     * cases someone remembered. Once anything is known — a pack state from the platform, or an engine
     * that has refused the language — the server comes first, because it is the path that carries the
     * craft vocabulary and therefore the path that agrees with the web.
     */
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

    // ---------------------------------------------------------------------------------------
    // Walking the plan
    // ---------------------------------------------------------------------------------------

    @Test
    fun `after walks the plan once and then stops`() {
        val plan = dwDictationLadder(conditions(DwPackState.UNKNOWN, onDeviceEngine = true))
        assertEquals(DwDictationRung.SERVER_DICTATE, plan.after(DwDictationRung.ON_DEVICE_PACK))
        assertEquals(DwDictationRung.NETWORK_RECOGNISER, plan.after(DwDictationRung.SERVER_DICTATE))
        assertNull(plan.after(DwDictationRung.NETWORK_RECOGNISER))
    }

    /**
     * A rung that is not on the plan has no successor — it must not silently hand back the first one.
     *
     * `indexOf` answers -1 for an absent element, and `getOrNull(-1 + 1)` is the FIRST rung. Without
     * the guard in [DwDictationPlan.after], a failure reported by an engine the plan never contained
     * would restart the ladder from the top, which for a two-rung plan is an infinite loop between
     * two engines — the exact failure the old one-shot `retried` flag existed to prevent.
     */
    @Test
    fun `a rung that is not in the plan has no next rung`() {
        val plan = dwDictationLadder(conditions(odiaOnM32))
        assertFalse(plan.rungs.contains(DwDictationRung.ON_DEVICE_PACK))
        assertNull(plan.after(DwDictationRung.ON_DEVICE_PACK))
    }

    // ---------------------------------------------------------------------------------------
    // The clip: the mirrored cap, and the arithmetic behind the duration
    // ---------------------------------------------------------------------------------------

    /**
     * THE CAP IS THE SERVER'S, MIRRORED. `DICTATION_MAX_BYTES` at
     * backend/app/api/routes/design_workshops.py is `6 * 1024 * 1024`.
     *
     * Pinned as arithmetic rather than as `6291456` so a reader can see it is the same expression the
     * server writes, and so a change on either side is a visible edit here rather than a magic number
     * that quietly stops matching.
     */
    @Test
    fun `the byte cap mirrors the server's`() {
        assertEquals(6L * 1024 * 1024, DW_DICTATION_MAX_BYTES)
        assertNull("A clip exactly at the cap is accepted, as the server accepts it", dwDictationOversize(DW_DICTATION_MAX_BYTES))
        assertNull(dwDictationOversize(0L))
        val refusal = assertSentence(dwDictationOversize(DW_DICTATION_MAX_BYTES + 1))
        assertTrue("The refusal must name the size limit: $refusal", refusal.contains("6 MB"))
    }

    /**
     * The duration cap has to keep an ordinary clip well under the byte cap, or it is not a cap.
     *
     * This is the arithmetic the constants' own comment states, asserted so the two cannot drift: a
     * later "let it run for twenty minutes" would leave a designer's longest passage refused after
     * they had already spoken it, which is the failure the duration cap exists to prevent.
     *
     * IT IS ARITHMETIC AND NOT A MEASUREMENT. What an arbitrary handset's AAC encoder actually
     * produces is unmeasured; that is why the recorder also asks the platform for a file-size limit
     * and why the finished file is checked before it is sent.
     */
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

    // ---------------------------------------------------------------------------------------
    // The words
    // ---------------------------------------------------------------------------------------

    /**
     * The failure sentences are SENTENCES that name the next move — never codes.
     *
     * The one this repository keeps finding is the opposite: "Dictation stopped unexpectedly (code
     * 13). Type the answer in, or try again", printed on every tap on a handset where no number of
     * further taps could ever have downloaded a language pack.
     */
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
        /*
          THE 409 FALLBACK, AND THE TWO THINGS IT MAY NOT DO.

          It is only ever reached for a consent refusal whose body carried no sentence of its own — the
          server writes one, and the control prints that verbatim. So this one must not GUESS which of the
          two states the server found (telling a designer to go and ask a question that is already on
          record is how they learn to stop reading these messages), and it must not offer a retry: what
          changes a consent is a person deciding, which is the rule the whole feature is written under.
        */
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
            "The 503 must be shown as 'not configured'",
            DW_DICTATION_NOT_CONFIGURED.contains("not configured") ||
                DW_DICTATION_NOT_CONFIGURED.contains("no transcription service configured")
        )
        assertTrue(
            "A throttled dictation must not promise the queue's automatic retry it will never get",
            DW_DICTATION_BUSY.contains("nothing is queued")
        )
    }

    /**
     * BOTH 503 SENTENCES SAY THE SAME THING ABOUT THE SERVER, AND ONE OF THEM ASKS FOR THE WORDS
     * AGAIN.
     *
     * Which is shown depends on whether a rung remains behind rung 2, and the difference is the
     * designer's next move, not the server's state. Neither may leave them waiting: rung 2 fails
     * after the utterance is finished, so where there IS another rung the sentence has to ask for it
     * to be spoken again rather than let the control silently start listening at somebody who has
     * stopped talking.
     */
    @Test
    fun `both 503 sentences name the configuration, and the one with a rung left asks for a repeat`() {
        listOf(DW_DICTATION_NOT_CONFIGURED, DW_DICTATION_NOT_CONFIGURED_SAY_AGAIN).forEach { sentence ->
            assertTrue(
                "A 503 must reach the designer as 'not configured': $sentence",
                sentence.contains("no transcription service configured")
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

    /**
     * A CAP REFUSAL THAT ARRIVES AFTER SOMEBODY HAS SPOKEN ASKS FOR THE WORDS AGAIN.
     *
     * The 503's argument, applied to the other mid-walk refusal, because it is the same act: rung 2 fails
     * at the END of an utterance — forty words said, Stop pressed, the clip uploaded and thrown away — so
     * silently starting Google's engine there re-opens the microphone at somebody who has finished
     * speaking, and hands them "No speech was heard" a few seconds later with no account of where their
     * passage went.
     *
     * It names no number, and that is deliberate rather than an omission: a 429 arrives as a sentence
     * rather than as an allowance, so there is no figure here to name and this file may not invent one.
     * The server's own refusal names it, and is shown verbatim where nothing remains to hand over to.
     */
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

    /**
     * A 200 THAT CARRIED NO TEXT MUST NEVER PROMISE A RETRY, WHATEVER STATUS IT CAME UNDER.
     *
     * Reading the status alone is not enough, and that is the whole point of this test. The endpoint
     * is synchronous and stores nothing, so nothing retries a dictation — but the provider chain's
     * throttle message ends "will retry automatically", which is true of the transcription QUEUE, and
     * `_transcribe_sync` folds that message into the FAILED one whenever a throttled provider is
     * mixed with a hard failure (`ai.py`: `errors.append(str(rate_limited.get("message")))`). A
     * client that switched on RATE_LIMITED and passed `message` through for everything else therefore
     * printed the promise anyway, on a FAILED status, under a dictation nobody was ever going to
     * produce. A designer who reads it waits, and the words never come.
     */
    @Test
    fun `an answer with no words in it never promises a retry that nothing will make`() {
        assertEquals(DW_DICTATION_BUSY, dwDictationServerAnswerSentence("RATE_LIMITED", null))

        // Verbatim in shape from `ai.py`: one provider throttled, another hard-failed, so the chain
        // resolves the whole call to FAILED and joins both messages with "; ".
        val mixed = "Deepgram transcription rate-limited (HTTP 429); will retry automatically.; " +
            "Transcription failed (openai). The provider's reply is in the server log."
        assertEquals(DW_DICTATION_BUSY, dwDictationServerAnswerSentence("FAILED", mixed))

        assertEquals(
            DW_DICTATION_TRANSCRIPTION_FAILED,
            dwDictationServerAnswerSentence("FAILED", "All transcription providers failed.")
        )
        // EMPTY carries no message at all (`_transcription_result` sets none), and it is the one
        // status whose next move really is the microphone and the room.
        assertEquals(DW_DICTATION_NO_WORDS, dwDictationServerAnswerSentence("EMPTY", null))

        // And nothing the server wrote for its own operator reaches the phone verbatim: every answer
        // is one of this file's own sentences, whatever came back beside the status.
        val standing = listOf(DW_DICTATION_BUSY, DW_DICTATION_TRANSCRIPTION_FAILED, DW_DICTATION_NO_WORDS)
        listOf("RATE_LIMITED", "FAILED", "EMPTY", "COMPLETED", "").forEach { status ->
            listOf(null, "", mixed, "Transcription unavailable: configure ELEVENLABS_API_KEY.").forEach { message ->
                val answer = assertSentence(dwDictationServerAnswerSentence(status, message))
                assertTrue("$status/$message produced text of its own: $answer", answer in standing)
                assertFalse("No key names on a phone screen: $answer", answer.contains("API_KEY"))
            }
        }
    }

    /**
     * A REFUSAL THAT CAN BE FORGOTTEN, because the pack it was about has arrived.
     *
     * THE DEFECT THIS PINS. `DwDictationRun` had an `engineRefused` and an `engineHasRefused` and no
     * way out, so a code 13 outlived the download that answered it: the settings row read "Hindi is
     * on this phone now, dictation in Hindi works with no signal" while the microphone went on
     * dropping rung 1, and offline the designer was told this phone had no offline Hindi to work
     * from. Two accounts of one pack, one tap apart, and only a force-stop reconciled them — after
     * the designer had already paid for the download on a prepaid bundle.
     *
     * WHAT IS ASSERTED HERE, and it is the whole of what is testable on a desktop JVM: the memory
     * takes a refusal, answers yes, gives it up when told the pack landed, and answers no again — and
     * the ladder's verdict follows it in both directions. The CALLER is
     * `DwLanguagePackUi.refreshNow`, which forgets a refusal only for a language a fresh
     * `checkRecognitionSupport` answer lists as INSTALLED; that half needs `SpeechRecognizer` and is
     * unmeasured here, which is why this test drives the memory directly rather than pretending to
     * exercise the screen.
     *
     * The tag is spelled differently at every call on purpose. Both halves normalise through
     * `dwNormalizeLanguageTag`, and a set that remembered `hi-IN` but was asked about `hi_IN` would
     * hold a refusal nothing could ever clear — which is the same defect again, wearing a separator.
     */
    @Test
    fun `a pack that has arrived cancels the refusal the engine gave before it did`() {
        DwDictationRun.forgetRefusal("hi-IN")   // leave no state behind from another test
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

    /**
     * A BLANK SERVER ID PUBLISHES AS NO SERVER ID, AND RUNG 2 CLOSES WITH IT.
     *
     * ── THE SEND THIS PREVENTS, WHICH IS THE ONE THIS WHOLE LANE IS ABOUT ───────────────────────
     *
     * `""` IS NOT NULL. Published as it arrived it would say this workshop IS on the server —
     * `conditionsNow()` reads `workshopOnServer = serverId != null` — so the ladder would offer rung 2,
     * the recorder would open, and `designWorkshopDictate("")` would put an EMPTY PATH SEGMENT into
     * `design-workshops/{id}/dictate`. Retrofit substitutes the empty string without complaint and
     * okhttp preserves the empty segment rather than collapsing it, so six megabytes of a named
     * artisan's voice would be posted to `design-workshops//dictate` — which in front of any proxy that
     * merges duplicate slashes IS `POST /design-workshops/dictate`, the id-less door that consults no
     * `dictationConsent` column at all. Where the slashes are not merged it is a 404 instead, bought
     * with the upload the mirror and the ladder exist to avoid spending.
     *
     * IT IS NOT A HYPOTHETICAL VALUE, which is why it is pinned rather than merely documented.
     * `DesignWorkshopDto.id` is DEFAULTED to `""` and decoded with `ignoreUnknownKeys` and
     * `coerceInputValues`, so a 2xx create answered by a captive portal instead of by this API decodes
     * to the empty string rather than throwing, and `WorkshopListScreen`'s create writes it into the
     * draft's `remoteId`. `WorkshopSync`'s own create already refuses that value in as many words ("the
     * server accepted this workshop but did not say what it saved").
     *
     * ASSERTED AT THE AMBIENT AND NOT AT THE SCREEN, because [DwDictationRun.publishWorkshopConsent] is
     * the only writer every reader inherits from — the ladder's fact and the id the recorder captures at
     * Start — and because the screen needs Compose while this needs a desktop JVM.
     */
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
            // Exactly what `conditionsNow()` asks of the published pair, so this asserts the join
            // between the ambient and the ladder and not two facts that merely agree.
            dwDictationLadder(conditions(odiaOnM32, workshopOnServer = blank.serverId != null))
                .rungs.contains(DwDictationRung.SERVER_DICTATE),
        )

        // A REAL ID STILL PUBLISHES. Asserted because the assertion above passes just as well for a
        // guard that withheld rung 2 from every workshop in the fleet, which would retire the feature
        // rather than gate it.
        val real = DwDictationRun.publishWorkshopConsent(DwTier3Consent.GRANTED, serverWorkshopId = "wk-1")
        assertEquals("wk-1", DwDictationRun.publishedWorkshop().serverId)
        assertTrue(
            "With an id and a granted answer the rung is back",
            dwDictationLadder(conditions(odiaOnM32, workshopOnServer = true))
                .rungs.contains(DwDictationRung.SERVER_DICTATE),
        )

        // The ambient is process-scoped and this suite shares one process, so it is left CLOSED — the
        // state every other test in this file reads the ladder under, and the fail-closed one.
        DwDictationRun.forgetWorkshopConsent(real)
        assertNull(DwDictationRun.publishedWorkshop().serverId)
        assertEquals(DwTier3Consent.NOT_RECORDED, DwDictationRun.publishedWorkshop().consent)
    }

    // ---------------------------------------------------------------------------------------
    // RUNG 1b — this app's own model, which serves languages Android's packs do not
    // ---------------------------------------------------------------------------------------

    @Test
    fun `with no model installed, which is every handset today, no plan anywhere changes`() {
        /*
         * THE GUARD THAT MAKES EVERY OTHER TEST IN THIS FILE STILL TRUE. A new rung is a new way for
         * a ladder to be wrong on a phone nobody was thinking about, so this walks the whole cross
         * product of the states that decide a plan and asserts the new rung is absent from all of
         * them while no model is installed — which is the fleet, today, on every handset.
         */
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
        // THE POINT OF THE WHOLE LANE, in one assertion. On the fleet's own handset asking for Odia,
        // today's plan needs signal for every rung on it; with a model that hears Odia installed, the
        // first rung works with none.
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
        /*
         * BOTH HALVES OF THE PLACEMENT, IN ONE PLAN. Android's pack keeps the job where it has the
         * language — it streams and it has been measured to exist, while our model's accuracy in a
         * courtyard is unmeasured — and our model goes ahead of both online rungs, because it is
         * free, needs no signal, and somebody paid for it precisely so that it would be used.
         */
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
        /*
         * THE ONE ORDERING THIS RUNG OVERTURNS. `phoneNotYetAsked` puts Google's network recogniser
         * ahead of the server on a phone that cannot be asked what packs it has, because that rung's
         * availability is a GUESS worth trying before spending money. Ours is not a guess — it is a
         * model verified on this phone and measured against this language — so it goes in front of
         * both. Otherwise the download this app just asked a designer to pay for would sit unused
         * behind an engine that answers over the network.
         */
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
        /*
         * A model installed for Odia says nothing about Tamil, and Tamil's plan must be the one it
         * had before any model existed. `appModelServesLanguage` IS the per-language answer, so
         * "our model cannot hear this one" is that flag reading false, and what has to be asserted
         * is the SHAPE of the plan rather than its equality with itself.
         *
         * AN EARLIER VERSION OF THIS TEST COMPARED `conditions(odiaOnM32)` WITH
         * `conditions(odiaOnM32, appModelServesLanguage = false)`. Those two calls construct the
         * same conditions — false is the default — so it asserted `x == x` and would have gone on
         * passing against a ladder that had deleted SERVER_DICTATE outright, which is the one
         * outcome that would undo the first lane of this project. The real invariant is in the test
         * below this one; this test now pins the concrete plan on the fleet's own handset.
         */
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
        /*
         * THE INVARIANT THE BRIEF NAMES, ASSERTED OVER THE CROSS PRODUCT RATHER THAN ON ONE PHONE.
         * A LANGUAGE WITH NO OFFLINE COVERAGE MUST STILL REACH THE SERVER — and the way to pin that
         * against a new rung is not to check the server rung is present in one case, but to check
         * that removing the new rung from the new plan gives back the OLD PLAN EXACTLY: same rungs,
         * same order, nothing dropped.
         *
         * `workshopOnServer` is swept as well as the four states that decide a plan, because it is
         * one of the two gates that can empty rung 2 on a perfectly online handset — and an empty
         * rung 2 is precisely the state in which a bug that ate the server rung would hide.
         */
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
        /*
         * THE MIRROR OF THE RULE RUNG 1 ALREADY KEEPS. The artisan's answer is about recordings
         * LEAVING THE DEVICE for a third-party provider; a model in this app's own storage sends
         * nothing anywhere. Gating it on consent would withdraw an offline capability over a
         * question that does not apply to it — and would do it in the exact place the capability
         * matters most, a courtyard with no signal.
         */
        listOf(DwTier3Consent.NOT_RECORDED, DwTier3Consent.REFUSED, DwTier3Consent.GRANTED).forEach { consent ->
            val plan = dwDictationLadder(
                conditions(odiaOnM32, tier3Consent = consent, appModelServesLanguage = true)
            )
            assertTrue(
                "consent=$consent closed a rung that sends nothing anywhere",
                plan.rungs.contains(DwDictationRung.APP_SPEECH_MODEL)
            )
        }
        // And the same for a spent allowance, which is about provider credit our model does not spend.
        assertTrue(
            dwDictationLadder(
                conditions(odiaOnM32, dailyCapSpent = true, dailyCapLimit = 40, appModelServesLanguage = true)
            ).rungs.contains(DwDictationRung.APP_SPEECH_MODEL)
        )
    }

    @Test
    fun `a model that refuses the language on this handset loses the rung, like a pack that does`() {
        // The exact counterpart of `deviceRefusedLanguage`: the engine that owns the model has said
        // no, and a catalogue row does not outrank the engine that read it.
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
        assertTrue(sentence.contains("This app's own speech model"))
        assertTrue("it is worth reporting, because the catalogue and the handset disagree",
            sentence.contains("worth reporting"))
        assertFalse(
            "a model on this phone needs no connection, so the connection is not what stopped it",
            sentence.contains("Dictation in Odia on this phone needs a connection")
        )
    }

    @Test
    fun `a fixable cause is still named ahead of our model's unfixable refusal`() {
        /*
         * ARM ORDER, AND IT IS THE ONE THAT COSTS A DESIGNER A WALK. An unanswered consent question
         * re-opens a rung with one tap on the workshop screen; our model refusing re-opens nothing at
         * all. Naming ours first would send somebody to report a bug when the fix was in their hands.
         */
        val sentence = assertSentence(
            dwDictationLadder(
                conditions(
                    odiaOnM32,
                    networkRecogniser = false,
                    tier3Consent = DwTier3Consent.NOT_RECORDED,
                    appModelServesLanguage = true,
                    appModelRefusedLanguage = true,
                )
            ).exhausted
        )
        assertTrue(sentence.contains("nobody has recorded yet"))
    }

    /** Non-blank, a real sentence, and ending in a full stop. Returns it so callers can go on. */
    private fun assertSentence(text: String?): String {
        assertNotNull("A sentence was expected and there was none", text)
        val sentence = text!!
        assertTrue("Blank is not an answer", sentence.isNotBlank())
        assertTrue("An error must be a sentence, not a fragment: $sentence", sentence.length > 30)
        assertTrue("A sentence ends in a full stop: $sentence", sentence.trim().endsWith("."))
        return sentence
    }
}
