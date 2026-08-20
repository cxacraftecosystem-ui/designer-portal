package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handset's reading of the five AI verb routes, asserted against bytes the SERVER produced.
 *
 * ── WHY THIS FILE EXISTS ──────────────────────────────────────────────────────────────────────────
 *
 * Encoding a DTO and decoding it again would pass on the day the DTO named every key wrong, because a
 * self-consistent DTO is self-consistent. The only assertion with any power is one that starts from
 * the server's own output, and this client has shipped the opposite mistake twice: `DwReferenceStore`
 * listened on a key the endpoint does not send and read a 50-record payload as an empty list, and the
 * identity-OCR DTO declared three keys that endpoint has never sent, so a perfect read of an identity
 * card was reported to the designer as unreadable. Both times `ignoreUnknownKeys` plus all-defaulted
 * properties turned the mistake into silence.
 *
 * **HERE THE SAME MISTAKE WOULD READ AS "THE MODEL PRODUCED NOTHING"** — an empty panel after a run
 * that spent real provider credit and moved the daily meter, which is the one failure a designer will
 * respond to by pressing the button again.
 *
 * ── AND THE OTHER DIRECTION, WHICH IS WORSE HERE THAN ANYWHERE ELSE IN THIS CLIENT ────────────────
 *
 * The request bodies are `APIModel`, which is `extra="forbid"`. A key this client invents is not
 * ignored up there — it is a 422 on every press, for every designer, until an app update ships. So the
 * encoded key SETS are pinned, not merely the values.
 *
 * ── WHAT [LIVE_VERB_201] AND [LIVE_CUE_PAYLOAD] ARE, EXACTLY ──────────────────────────────────────
 *
 * The verbatim stdout of `ai_layers.layer_payload(row, include_text=True)` composed with
 * `ai_verb_cap.allowance_payload(...)` in the shape `design_workshops._finish_verb` returns, and of
 * `subtitles.cues_payload(cues, language="Hindi")`, both EXECUTED on 2026-08-19 through the backend's
 * own venv:
 *
 *     backend/.venv/Scripts/python.exe -c "… print(json.dumps(…, indent=2))"
 *
 * So every key name, every nesting and every null below came out of the server's serialiser rather
 * than out of anybody's memory of it. **They are NOT captures of a live HTTP 201 over the wire** —
 * Docker is down in this working copy and there is no Postgres to run a route against — and that
 * difference is stated rather than glossed. `_finish_verb` composes exactly these three parts and adds
 * nothing else to them, which is checkable by reading it; the rows were hand-built rather than
 * selected, so the VALUES are invented and the SHAPE is the server's.
 */
class DwAiVerbWireTest {

    /**
     * The decoder these assertions run through, built to mirror `ApiClient.json` flag for flag —
     * `ignoreUnknownKeys`, `explicitNulls = false`, `isLenient`, `coerceInputValues`.
     *
     * A SECOND `Json` BESIDE THE PRODUCTION ONE IS A REAL HAZARD and is worth naming rather than
     * leaving as convenience: a flag added there and not here would make this file pass while the
     * handset failed. Two of the four are what the assertions below actually turn on — unknown keys
     * must not throw, so a server that gains a key does not blank the panel, and nulls must not be
     * emitted, so a body stays inside `extra="forbid"` — and both are asserted directly rather than
     * assumed from the flag being set.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    // ---------------------------------------------------------------------------------------
    // What the server sends back
    // ---------------------------------------------------------------------------------------

    private val LIVE_VERB_201 = """
        {
          "layer": {
            "id": "lyr_01",
            "designWorkshopId": "dw_77",
            "kind": "PROOFREAD",
            "tier": "TIER_3",
            "source": {
              "kind": "SUPPLIED_TEXT",
              "id": null,
              "text": "The dabu block is cut from teak."
            },
            "provider": "openai",
            "modelId": "gpt-4o-mini",
            "modelVersion": null,
            "language": "English",
            "sourceLanguage": null,
            "targetLanguage": null,
            "producedAt": "2026-08-19T10:30:00+00:00",
            "createdAt": "2026-08-19T10:30:05+00:00",
            "createdById": "usr_meera",
            "accepted": false,
            "acceptedAt": null,
            "acceptedById": null,
            "textChars": 32,
            "preview": "The dabu block is cut from teak.",
            "payload": null,
            "textWithheld": false,
            "deletedAt": null,
            "text": "The dabu block is cut from teak."
          },
          "accepted": false,
          "acceptanceRequired": true,
          "aiVerbsLimit": 25,
          "aiVerbsUsed": 22,
          "aiVerbsRemaining": 3,
          "aiVerbDay": "2026-08-19",
          "aiVerbsByVerb": {
            "PROOFREAD": 12,
            "CAPTION": 10
          }
        }
    """.trimIndent()

    @Test
    fun `the layer on a 201 decodes into every field a reviewer opens the screen for`() {
        val result = json.decodeFromString(DwAiVerbResultDto.serializer(), LIVE_VERB_201)
        val layer = result.layer

        assertEquals("lyr_01", layer.id)
        assertEquals("dw_77", layer.designWorkshopId)
        assertEquals("PROOFREAD", layer.kind)
        // The tier is the fact the plan says a reviewer opens this screen for — a cloud-diarized
        // interview and a device-guessed one must never look alike on a page.
        assertEquals("TIER_3", layer.tier)
        assertEquals("openai", layer.provider)
        assertEquals("gpt-4o-mini", layer.modelId)
        assertEquals("English", layer.language)
        assertEquals("usr_meera", layer.createdById)
        assertEquals("The dabu block is cut from teak.", layer.text)
        assertEquals(32, layer.textChars)
        assertEquals("The dabu block is cut from teak.", layer.preview)
    }

    /**
     * RULE 3 ON THE WIRE. A fresh layer is a suggestion and nothing else, and the two flags say so in
     * a field rather than in documentation — the client that just asked for this has words on screen
     * and is one tap from putting them in a report.
     */
    @Test
    fun `a fresh layer is never already accepted, and says acceptance is required`() {
        val result = json.decodeFromString(DwAiVerbResultDto.serializer(), LIVE_VERB_201)
        assertFalse(result.accepted)
        assertTrue(result.acceptanceRequired)
        assertFalse(result.layer.accepted)
        assertNull(result.layer.acceptedAt)
        assertNull(result.layer.acceptedById)
    }

    /**
     * THE EVIDENCE TRAVELS WITH THE LAYER for a supplied-text source, and the id is NULL there.
     *
     * `layer_payload` writes `stored.id or None` for exactly this case, with the note that an empty
     * string is "the shape a client renders as a link to nothing". This side had better not have typed
     * it non-nullable, because there is no row to open and the words exist only on this layer.
     */
    @Test
    fun `a supplied-text source carries the words and names no row`() {
        val source = json.decodeFromString(DwAiVerbResultDto.serializer(), LIVE_VERB_201).layer.source
        assertEquals("SUPPLIED_TEXT", source?.kind)
        assertNull(source?.id)
        assertEquals("The dabu block is cut from teak.", source?.text)
    }

    @Test
    fun `the allowance rides on the 201 under the server's own five key names`() {
        val result = json.decodeFromString(DwAiVerbResultDto.serializer(), LIVE_VERB_201)
        assertEquals(25, result.aiVerbsLimit)
        assertEquals(22, result.aiVerbsUsed)
        assertEquals(3, result.aiVerbsRemaining)
        // `aiVerbDay` and not `aiVerbsDay`. It is the key the whole freshness rule turns on, so a
        // mis-spelling here would silently mean the mirror is never written at all.
        assertEquals("2026-08-19", result.aiVerbDay)
        assertEquals(mapOf("PROOFREAD" to 12, "CAPTION" to 10), result.aiVerbsByVerb)
    }

    /**
     * A SERVER THAT GAINS A KEY MUST NOT BLANK THE PANEL. `ignoreUnknownKeys` is what makes that true,
     * and it is asserted rather than assumed because it is the flag that turned this client's two
     * previous wire defects into silence — it is doing real work here, in the direction it helps.
     */
    @Test
    fun `a key this build has never heard of does not fail the decode`() {
        val withNewKey = LIVE_VERB_201.replaceFirst(
            "\"acceptanceRequired\": true,",
            "\"acceptanceRequired\": true,\n  \"aiVerbsCostPaise\": 4100,"
        )
        val result = json.decodeFromString(DwAiVerbResultDto.serializer(), withNewKey)
        assertEquals("lyr_01", result.layer.id)
        assertEquals(3, result.aiVerbsRemaining)
    }

    /**
     * THE WITHHELD SHAPE, WHICH IS FOUR NULLS AND NOT ONE.
     *
     * A layer standing on a recording this account may not read comes back with no text, no preview,
     * no character count and no payload — and with its provenance intact, because none of that is the
     * recording's content. `textChars` is NULL rather than 0, deliberately: 0 would say "there is
     * nothing to read", which is a different fact from "you may not read it" and the only one of the
     * two that is false. A client that rendered `textChars ?: 0` would print "0 characters" over a
     * forty-minute interview.
     */
    @Test
    fun `a withheld layer keeps its provenance and loses its content`() {
        val withheld = """
            {
              "id": "lyr_09", "designWorkshopId": "dw_77", "kind": "RAW_TRANSCRIPT",
              "tier": "TIER_3", "source": {"kind": "MEDIA", "id": "med_4", "text": null},
              "provider": "elevenlabs", "modelId": "scribe_v2", "modelVersion": null,
              "language": "multi", "sourceLanguage": null, "targetLanguage": null,
              "producedAt": null, "createdAt": "2026-08-19T10:30:05+00:00",
              "createdById": "usr_ravi", "accepted": false, "acceptedAt": null,
              "acceptedById": null, "textChars": null, "preview": null, "payload": null,
              "textWithheld": true, "deletedAt": null
            }
        """.trimIndent()
        val layer = json.decodeFromString(DwAiLayerDto.serializer(), withheld)

        assertTrue(layer.textWithheld)
        assertNull(layer.textChars)
        assertNull(layer.preview)
        assertNull(layer.payload)
        assertNull(layer.text)
        // The provenance is the point of withholding rather than omitting the row.
        assertEquals("elevenlabs", layer.provider)
        assertEquals("scribe_v2", layer.modelId)
        assertEquals("TIER_3", layer.tier)
        // `multi` is a real answer and not a placeholder: these interviews code-switch mid-sentence.
        assertEquals("multi", layer.language)
    }

    /**
     * A KIND OR A TIER THIS BUILD HAS NEVER HEARD OF STILL DECODES, which is why both are `String` and
     * not enums.
     *
     * A handset updates when it next sees wifi and the API updates when somebody deploys it, so a
     * server running ahead of a client is an ordinary state on this fleet rather than a mistake. An
     * enum-typed `kind` would throw on the whole list, taking out the twenty-four transcripts a
     * designer came to look at because a twenty-fifth row was written by a newer build.
     */
    @Test
    fun `a layer kind from a newer server decodes instead of failing the list`() {
        val future = """{"id":"lyr_x","kind":"SUMMARY_OF_SUMMARIES","tier":"TIER_4"}"""
        val layer = json.decodeFromString(DwAiLayerDto.serializer(), future)
        assertEquals("SUMMARY_OF_SUMMARIES", layer.kind)
        assertEquals("TIER_4", layer.tier)
    }

    // ---------------------------------------------------------------------------------------
    // What this client sends
    // ---------------------------------------------------------------------------------------

    private fun keysOf(encoded: String): Set<String> =
        (json.parseToJsonElement(encoded) as JsonObject).keys

    /**
     * **EXACTLY ONE SOURCE, AND THE OTHER KEY IS ABSENT RATHER THAN NULL.**
     *
     * `_require_exactly_one_source` reads `(body.text or "").strip()`, so an explicit `"text": null`
     * beside a `sourceLayerId` would in fact pass — but it would also be a key this client sends for
     * no reason into a body whose model forbids extras the moment anything else is added beside it.
     * `explicitNulls = false` is what keeps it off the wire, and this is the assertion that would
     * notice the flag being dropped.
     */
    @Test
    fun `a proofread over a passage sends text and never a layer id`() {
        val body = dwProofreadBody(DwVerbSource.Passage("teh block"), language = "English")
        val keys = keysOf(json.encodeToString(DwProofreadBody.serializer(), body))
        assertEquals(setOf("text", "language"), keys)
    }

    @Test
    fun `a proofread over a stored layer sends the layer id and never text`() {
        val body = dwProofreadBody(DwVerbSource.StoredLayer("lyr_09"))
        val keys = keysOf(json.encodeToString(DwProofreadBody.serializer(), body))
        assertEquals(setOf("sourceLayerId"), keys)
    }

    /**
     * **THE EXPAND BODY HAS NO WAY TO NAME A LAYER, AND THAT IS THE POINT.**
     *
     * The key set is pinned rather than the absence being read off the class, because a pinned set
     * fails on the day somebody ADDS one — which is the change this assertion exists to make visible.
     * An expansion invents sentences; run over an artisan's transcript it would put invented words in
     * a named person's mouth in a document a ministry officer reads, and no acceptance screen makes
     * that safe because the person accepting is not the person being quoted. `AiExpandIn` has no such
     * field "so a client cannot even ask", and this is the handset's copy of that guarantee.
     */
    @Test
    fun `an expansion can be asked for over a note and over nothing else`() {
        val body = dwExpandBody("dabu, teak block, 3 dips", language = "English")
        val keys = keysOf(json.encodeToString(DwExpandBody.serializer(), body))
        assertEquals(setOf("text", "language"), keys)
        assertFalse("expand must never gain a layer parameter", keys.contains("sourceLayerId"))
        assertFalse(keys.contains("sourceMediaId"))
    }

    @Test
    fun `a translation names its target and may leave its source unstated`() {
        val stated = dwTranslateBody(DwVerbSource.Passage("गोंद और मिट्टी"), "English", "Hindi")
        assertEquals(
            setOf("text", "targetLanguage", "sourceLanguage"),
            keysOf(json.encodeToString(DwTranslateBody.serializer(), stated)),
        )

        // Left out, the server records what the run detected — or UNRECORDED in that word. What it
        // will not do is default it to English, and neither may this client by sending a blank.
        val unstated = dwTranslateBody(DwVerbSource.StoredLayer("lyr_09"), "English", "   ")
        assertEquals(
            setOf("sourceLayerId", "targetLanguage"),
            keysOf(json.encodeToString(DwTranslateBody.serializer(), unstated)),
        )
    }

    @Test
    fun `a media verb sends the server's media id, and subtitles send no language`() {
        val caption = DwMediaVerbBody(sourceMediaId = "med_4", language = "Odia")
        assertEquals(
            setOf("sourceMediaId", "language"),
            keysOf(json.encodeToString(DwMediaVerbBody.serializer(), caption)),
        )
        // A cue list is in whatever language was spoken, and `AiMediaVerbIn` documents that subtitles
        // ignore the field entirely — so it is not sent rather than sent and ignored.
        val subtitles = DwMediaVerbBody(sourceMediaId = "med_4")
        assertEquals(
            setOf("sourceMediaId"),
            keysOf(json.encodeToString(DwMediaVerbBody.serializer(), subtitles)),
        )
    }

    /**
     * The path segment each verb posts to, so a caller cannot lower-case a token by hand and miss.
     *
     * Read off the five `@router.post` decorators. The pairing that is easy to get wrong is the last
     * one: the VERB is `EXPAND` and the KIND it produces is `EXPANDED`, and the route is `/expand`.
     */
    @Test
    fun `each verb posts to the segment its route declares`() {
        assertEquals("proofread", DwAiVerb.PROOFREAD.path)
        assertEquals("expand", DwAiVerb.EXPAND.path)
        assertEquals("translate", DwAiVerb.TRANSLATE.path)
        assertEquals("caption", DwAiVerb.CAPTION.path)
        assertEquals("subtitles", DwAiVerb.SUBTITLES.path)
        // `Verb.human`, so the sentence before the press and the server's after it use one name.
        assertEquals("expanding a note", DwAiVerb.EXPAND.human)
        assertEquals("describing a photograph", DwAiVerb.CAPTION.human)
    }

    // ---------------------------------------------------------------------------------------
    // The cue list
    // ---------------------------------------------------------------------------------------

    private val LIVE_CUE_PAYLOAD = """
        {
          "schema": "dw.subtitles/1",
          "language": "Hindi",
          "count": 2,
          "estimatedCues": 1,
          "durationSeconds": 5.12,
          "cues": [
            {
              "start": 0.0,
              "end": 2.4,
              "text": "With gum and clay",
              "speaker": "Speaker 1"
            },
            {
              "start": 2.4,
              "end": 5.12,
              "text": "the block is pressed",
              "speaker": "Speaker 2",
              "estimated": true
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a stored cue list is read for its cues, its count and its approximations`() {
        val summary = dwSubtitleCueSummary(json.parseToJsonElement(LIVE_CUE_PAYLOAD))
        assertTrue(summary.readable)
        assertEquals(2, summary.count)
        assertEquals(1, summary.estimatedCues)
        assertEquals(5.12, summary.durationSeconds!!, 0.0001)
        assertEquals("Hindi", summary.language)
        assertTrue(summary.hasSpeakers)
        assertEquals("Speaker 1", summary.cues[0].speaker)
        assertFalse(summary.cues[0].estimated)
        assertTrue(summary.cues[1].estimated)
    }

    /**
     * TOLERATES THE BARE LIST AS WELL AS THE WRAPPED OBJECT, exactly as `cues_of_payload` does: a
     * payload written by an on-device runner may not have gone through `cues_payload`, and the tiers
     * are allowed to differ in how they produce a thing and not in what it means. With no wrapper
     * there is no stored count, so the count is the number of cues actually present.
     */
    @Test
    fun `a bare cue array is still a cue list`() {
        val bare = """[{"start":0,"end":1.5,"text":"With gum and clay"}]"""
        val summary = dwSubtitleCueSummary(json.parseToJsonElement(bare))
        assertTrue(summary.readable)
        assertEquals(1, summary.count)
        assertEquals(0, summary.estimatedCues)
        assertNull(summary.durationSeconds)
        assertFalse(summary.hasSpeakers)
    }

    /**
     * A CUE WITH NO USABLE TIME IS DROPPED AND NOT DEFAULTED TO ZERO.
     *
     * A cue at 00:00:00 that belongs at 41 minutes lands over the opening frame of a video somebody is
     * showing to a ministry officer. The stored `count` still says what the server counted, which is
     * the honest pair: two cues were written, one of them cannot be placed.
     */
    @Test
    fun `a cue whose times will not parse is left out rather than placed at zero`() {
        val broken = """
            {"schema":"dw.subtitles/1","count":2,"cues":[
              {"start":"--","end":2.0,"text":"unplaceable"},
              {"start":2.0,"end":4.0,"text":"the block is pressed"}]}
        """.trimIndent()
        val summary = dwSubtitleCueSummary(json.parseToJsonElement(broken))
        assertEquals(1, summary.cues.size)
        assertEquals("the block is pressed", summary.cues[0].text)
        assertEquals(2, summary.count)
    }

    /**
     * A payload that is not a cue list at all is UNREADABLE rather than empty, so a screen can say
     * "there is nothing to show here" instead of drawing a player with no track and no explanation.
     */
    @Test
    fun `a payload that is not a cue list says so`() {
        val tags = """{"schema":"dw.tags/1","tags":["indigo","block"]}"""
        val summary = dwSubtitleCueSummary(json.parseToJsonElement(tags))
        assertFalse(summary.readable)
        assertEquals(0, summary.count)
        assertTrue(summary.cues.isEmpty())

        assertFalse(dwSubtitleCueSummary(null).readable)
    }

    /** A wrapper naming a schema this build does not know is still read for the cues it holds. */
    @Test
    fun `a newer cue schema is still a cue list`() {
        val future = """{"schema":"dw.subtitles/2","count":1,"cues":[{"start":0,"end":1,"text":"x"}]}"""
        assertTrue(dwSubtitleCueSummary(json.parseToJsonElement(future)).readable)
    }

    @Test
    fun `a timecode reads as a caption reader expects, and truncates rather than rounds`() {
        assertEquals("0:00", dwSubtitleTimecode(0.0))
        assertEquals("4:09", dwSubtitleTimecode(249.9))
        assertEquals("1:04:09", dwSubtitleTimecode(3849.48))
        // A negative start cannot arise from `cue_of`, but a clock that reads before zero must not
        // print a minus in front of a caption time.
        assertEquals("0:00", dwSubtitleTimecode(-3.0))
    }

    // ---------------------------------------------------------------------------------------
    // The decision routes
    // ---------------------------------------------------------------------------------------

    /**
     * ACCEPT AND UNACCEPT ANSWER WITH THE LAYER AND THE WHOLE LOG, and both are read, because the audit
     * being visible in the response is what stops a screen rendering acceptance as a checkbox.
     */
    @Test
    fun `an acceptance comes back with the layer's new state and its history`() {
        val body = """
            {"layer":{"id":"lyr_01","accepted":true,"acceptedAt":"2026-08-19T11:02:00+00:00",
             "acceptedById":"usr_meera"},
             "decisions":[{"id":"dec_1","layerId":"lyr_01","decision":"ACCEPTED","note":null,
             "actorId":"usr_meera","createdAt":"2026-08-19T11:02:00+00:00"}]}
        """.trimIndent()
        val result = json.decodeFromString(DwAiLayerDecisionResultDto.serializer(), body)
        assertTrue(result.layer.accepted)
        assertEquals("usr_meera", result.layer.acceptedById)
        assertEquals(1, result.decisions.size)
        assertEquals("ACCEPTED", result.decisions[0].decision)
        assertEquals("usr_meera", result.decisions[0].actorId)
    }

    /**
     * The list's `accepted` count is what the report would print, which is not the same as counting
     * the accepted rows on screen: a layer accepted and afterwards declined keeps its `acceptedAt` —
     * deletion clears no acceptance — and is in `items` and out of this number.
     */
    @Test
    fun `the layer list carries a count of what a report would actually print`() {
        val body = """
            {"items":[{"id":"a","accepted":true},{"id":"b","accepted":true,"deletedAt":"2026-08-19T09:00:00+00:00"}],
             "total":2,"accepted":1}
        """.trimIndent()
        val list = json.decodeFromString(DwAiLayerListDto.serializer(), body)
        assertEquals(2, list.total)
        assertEquals(2, list.items.size)
        assertEquals(1, list.accepted)
    }

    /** `UNRECORDED` is a word the server writes and never a word a screen prints raw. */
    @Test
    fun `an unrecorded provider is recognised whatever its case`() {
        assertTrue(dwAiIsUnrecorded("UNRECORDED"))
        assertTrue(dwAiIsUnrecorded(" unrecorded "))
        assertFalse(dwAiIsUnrecorded("openai"))
        assertFalse(dwAiIsUnrecorded(null))
    }

    /** Kept honest about the one thing this file cannot import: the server's own character bound. */
    @Test
    fun `the passage bound is the server's MAX_SOURCE_TEXT_CHARS`() {
        assertEquals(20_000, DW_VERB_MAX_TEXT_CHARS)
        assertEquals(40, DW_VERB_MAX_LANGUAGE_CHARS)
    }

    /** Guards against a refactor that quietly stops the wrapper being consulted at all. */
    @Test
    fun `a wrapped payload's stored numbers win over anything recomputed here`() {
        val truncated = """{"schema":"dw.subtitles/1","count":142,"estimatedCues":11,
            "cues":[{"start":0,"end":1,"text":"only the first"}]}""".trimIndent()
        val summary = dwSubtitleCueSummary(json.parseToJsonElement(truncated).jsonObject)
        assertEquals(142, summary.count)
        assertEquals(11, summary.estimatedCues)
        assertEquals(1, summary.cues.size)
    }
}
