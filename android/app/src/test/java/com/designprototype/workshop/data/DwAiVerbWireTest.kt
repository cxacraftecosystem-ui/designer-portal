package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwAiVerbWireTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

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
            "modelId": "gpt-6-luna",
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
        assertEquals("TIER_3", layer.tier)
        assertEquals("openai", layer.provider)
        assertEquals("gpt-6-luna", layer.modelId)
        assertEquals("English", layer.language)
        assertEquals("usr_meera", layer.createdById)
        assertEquals("The dabu block is cut from teak.", layer.text)
        assertEquals(32, layer.textChars)
        assertEquals("The dabu block is cut from teak.", layer.preview)
    }

    @Test
    fun `a fresh layer is never already accepted, and says acceptance is required`() {
        val result = json.decodeFromString(DwAiVerbResultDto.serializer(), LIVE_VERB_201)
        assertFalse(result.accepted)
        assertTrue(result.acceptanceRequired)
        assertFalse(result.layer.accepted)
        assertNull(result.layer.acceptedAt)
        assertNull(result.layer.acceptedById)
    }

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
        assertEquals("2026-08-19", result.aiVerbDay)
        assertEquals(mapOf("PROOFREAD" to 12, "CAPTION" to 10), result.aiVerbsByVerb)
    }

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
        assertEquals("elevenlabs", layer.provider)
        assertEquals("scribe_v2", layer.modelId)
        assertEquals("TIER_3", layer.tier)
        assertEquals("multi", layer.language)
    }

    @Test
    fun `a layer kind from a newer server decodes instead of failing the list`() {
        val future = """{"id":"lyr_x","kind":"SUMMARY_OF_SUMMARIES","tier":"TIER_4"}"""
        val layer = json.decodeFromString(DwAiLayerDto.serializer(), future)
        assertEquals("SUMMARY_OF_SUMMARIES", layer.kind)
        assertEquals("TIER_4", layer.tier)
    }

    private fun keysOf(encoded: String): Set<String> =
        (json.parseToJsonElement(encoded) as JsonObject).keys

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
        val subtitles = DwMediaVerbBody(sourceMediaId = "med_4")
        assertEquals(
            setOf("sourceMediaId"),
            keysOf(json.encodeToString(DwMediaVerbBody.serializer(), subtitles)),
        )
    }

    @Test
    fun `each verb posts to the segment its route declares`() {
        assertEquals("proofread", DwAiVerb.PROOFREAD.path)
        assertEquals("expand", DwAiVerb.EXPAND.path)
        assertEquals("translate", DwAiVerb.TRANSLATE.path)
        assertEquals("caption", DwAiVerb.CAPTION.path)
        assertEquals("subtitles", DwAiVerb.SUBTITLES.path)
        assertEquals("expanding a note", DwAiVerb.EXPAND.human)
        assertEquals("describing a photograph", DwAiVerb.CAPTION.human)
    }

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

    @Test
    fun `a payload that is not a cue list says so`() {
        val tags = """{"schema":"dw.tags/1","tags":["indigo","block"]}"""
        val summary = dwSubtitleCueSummary(json.parseToJsonElement(tags))
        assertFalse(summary.readable)
        assertEquals(0, summary.count)
        assertTrue(summary.cues.isEmpty())

        assertFalse(dwSubtitleCueSummary(null).readable)
    }

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
        assertEquals("0:00", dwSubtitleTimecode(-3.0))
    }

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

    @Test
    fun `an unrecorded provider is recognised whatever its case`() {
        assertTrue(dwAiIsUnrecorded("UNRECORDED"))
        assertTrue(dwAiIsUnrecorded(" unrecorded "))
        assertFalse(dwAiIsUnrecorded("openai"))
        assertFalse(dwAiIsUnrecorded(null))
    }

    @Test
    fun `the passage bound is the server's MAX_SOURCE_TEXT_CHARS`() {
        assertEquals(20_000, DW_VERB_MAX_TEXT_CHARS)
        assertEquals(40, DW_VERB_MAX_LANGUAGE_CHARS)
    }

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
