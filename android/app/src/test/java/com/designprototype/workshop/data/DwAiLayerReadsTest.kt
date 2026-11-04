package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwAiLayerReadsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun theAllowancePreflightIsReadIntoTheStoredAllowance() {
        val dto = json.decodeFromString(
            DwAiVerbAllowanceDto.serializer(),
            """{"aiVerbsLimit":40,"aiVerbsUsed":3,"aiVerbsRemaining":37,"aiVerbDay":"2026-11-04","aiVerbsByVerb":{"PROOFREAD":3}}""",
        )
        val stored = dwAiVerbAllowanceOf(dto, "usr_1")!!
        assertEquals(DwAiVerbAllowance("usr_1", "2026-11-04", 40, 37, mapOf("PROOFREAD" to 3)), stored)
        val view = dwAiVerbCapView(stored, "usr_1", "2026-11-04")
        assertTrue(view.told)
        assertFalse(view.spent)
        assertEquals(37, view.remaining)
    }

    @Test
    fun anUncappedOrDaylessAllowanceIsHandledHonestly() {
        val uncapped = dwAiVerbAllowanceOf(DwAiVerbAllowanceDto(aiVerbDay = "2026-11-04"), "usr_1")!!
        val view = dwAiVerbCapView(uncapped, "usr_1", "2026-11-04")
        assertTrue(view.told)
        assertNull(view.remaining)
        assertNull(dwAiVerbAllowanceOf(DwAiVerbAllowanceDto(aiVerbDay = null), "usr_1"))
        assertNull(dwAiVerbAllowanceOf(DwAiVerbAllowanceDto(aiVerbDay = "2026-11-04"), null))
    }

    @Test
    fun thePreflightIsAskedOncePerAccountPerDay() {
        DwAiVerbAllowancePreflight.reset()
        assertTrue(DwAiVerbAllowancePreflight.shouldAsk("usr_1", "2026-11-04"))
        assertFalse(DwAiVerbAllowancePreflight.shouldAsk("usr_1", "2026-11-04"))
        assertTrue(DwAiVerbAllowancePreflight.shouldAsk("usr_2", "2026-11-04"))
        assertTrue(DwAiVerbAllowancePreflight.shouldAsk("usr_1", "2026-11-05"))
        assertFalse(DwAiVerbAllowancePreflight.shouldAsk(null, "2026-11-05"))
        DwAiVerbAllowancePreflight.reset()
    }

    @Test
    fun aDecisionHistoryWithADeletedActorStillReads() {
        val history = json.decodeFromString(
            DwAiDecisionHistoryDto.serializer(),
            """{"items":[{"id":"d1","layerId":"l1","decision":"ACCEPTED","note":null,"actorId":"u1","actorName":"R. Menon","layerKind":"CLEANED_TRANSCRIPT","createdAt":"2026-11-04T10:00:00Z"},{"id":"d2","layerId":"l2","decision":"DECLINED","actorId":null,"createdAt":null}],"total":2}""",
        )
        assertEquals(2, history.total)
        assertEquals("Accepted by R. Menon · Cleaned transcript", dwDecisionLine(history.items[0]))
        assertEquals("Declined by A former account", dwDecisionLine(history.items[1]))
        assertEquals("Acceptance withdrawn", dwDecisionLabel("WITHDRAWN"))
    }

    @Test
    fun aLayerReadCarriesItsTextAndItsDecisions() {
        val read = json.decodeFromString(
            DwAiLayerReadDto.serializer(),
            """{"layer":{"id":"l1","kind":"RAW_TRANSCRIPT","tier":"TIER_3","provider":"UNRECORDED","modelId":"UNRECORDED","text":"Hello"},"decisions":[]}""",
        )
        assertEquals("Hello", read.layer.text)
        assertTrue(read.decisions.isEmpty())
    }

    @Test
    fun onlyTranscriptsWithNoLiveLayerAreOfferedForRegistering() {
        val layers = listOf(
            DwAiLayerDto(id = "a", source = DwAiLayerSourceDto(kind = "MEDIA", id = "m1")),
            DwAiLayerDto(id = "b", source = DwAiLayerSourceDto(kind = "MEDIA", id = "m2"), deletedAt = "2026-11-01T00:00:00Z"),
            DwAiLayerDto(id = "c", source = DwAiLayerSourceDto(kind = "LAYER", id = "m3")),
        )
        val transcripts = listOf("m1", "m2", "m3", "").map { DwTranscriptItemDto(mediaId = it) }
        assertEquals(listOf("m2", "m3"), dwRegisterableTranscripts(layers, transcripts).map { it.mediaId })
        assertEquals("Registered. Accept it to print.", dwRegisteredNotice(1))
        assertEquals("Registered as 2 layers. Accept them to print.", dwRegisteredNotice(2))
    }

    @Test
    fun theRegisterBodyCarriesTheRecordingAndNothingElse() {
        assertEquals("""{"sourceMediaId":"m1"}""", json.encodeToString(DwAiLayerRegisterBody.serializer(), DwAiLayerRegisterBody("m1")))
    }

    @Test
    fun theServersMarketAnalysisReadsBackIntoTheSameFindings() {
        val stream = javaClass.getResourceAsStream("/dw-analysis-expected.json") ?: error("golden missing")
        val golden = Json.parseToJsonElement(stream.reader(Charsets.UTF_8).use { it.readText() }).jsonObject
        val market = golden.filterKeys { it.startsWith("market/") }
        assertTrue(market.isNotEmpty())
        for ((key, value) in market) {
            val served = value.jsonObject
            val findings = DwMarketAnalysis.fromPayload(served)
            assertEquals(key, served["bands"]?.jsonArray?.size ?: 0, findings.bands.size)
            assertEquals(key, served["swot"]?.jsonArray?.size ?: 0, findings.swot.size)
            assertEquals(key, served["cautions"]?.jsonArray?.size ?: 0, findings.cautions.size)
            assertEquals(key, findings, DwMarketAnalysis.fromPayload(DwMarketAnalysis.payload(findings)))
        }
    }

    @Test
    fun aMalformedMarketAnalysisIsEmptyRatherThanACrash() {
        val findings = DwMarketAnalysis.fromPayload(JsonObject(emptyMap()))
        assertTrue(findings.isEmpty)
    }
}
