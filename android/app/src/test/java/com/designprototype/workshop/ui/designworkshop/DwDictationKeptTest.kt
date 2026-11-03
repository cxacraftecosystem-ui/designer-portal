package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DwDictateDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DwDictationKeptTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun decode(body: String) = json.decodeFromString(DwDictateDto.serializer(), body)

    @Test fun `a kept clip's media id is read off the answer`() {
        val dto = decode("""{"status":"COMPLETED","text":"dabu resist printing","provider":"elevenlabs","mediaId":"med_1"}""")
        assertEquals("med_1", dto.mediaId)
        assertEquals("med_1", dwDictationKeptMediaId(dto))
    }

    @Test fun `an answer without one shows no transcript`() {
        val dto = decode("""{"status":"COMPLETED","text":"dabu resist printing","mediaId":null}""")
        assertNull(dto.mediaId)
        assertNull(dwDictationKeptMediaId(dto))
        assertNull(dwDictationKeptMediaId(decode("""{"status":"COMPLETED","text":"dabu"}""")))
    }

    @Test fun `no words means no transcript row even with an id`() {
        assertNull(dwDictationKeptMediaId(decode("""{"status":"EMPTY","text":"","mediaId":"med_1"}""")))
        assertNull(dwDictationKeptMediaId(decode("""{"status":"COMPLETED","text":"dabu","mediaId":"  "}""")))
    }
}
