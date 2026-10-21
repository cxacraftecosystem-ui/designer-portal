package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** M2 and M3 (audit 21 Oct 2026): what a re-upload kept and did not import is said on the handset. */
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
