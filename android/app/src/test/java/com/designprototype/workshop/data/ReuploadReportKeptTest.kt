package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ReuploadReportKeptTest {

    @Test
    fun `kept questions and answers not imported are counted in the tally`() {
        val report = Json { ignoreUnknownKeys = true }.decodeFromString(
            QFormChangeReportDto.serializer(),
            """{"updated":1,"kept":2,"answersNotImported":3,"problems":[]}""",
        )
        assertEquals(
            "1 edited, 2 left out of the file and kept, 3 answers in the file not imported",
            qFormUploadSummary(report),
        )
    }
}
