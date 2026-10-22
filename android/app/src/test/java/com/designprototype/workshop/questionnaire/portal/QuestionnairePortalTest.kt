package com.designprototype.workshop.questionnaire.portal

import com.designprototype.workshop.data.ArtisanCreateRequest
import com.designprototype.workshop.questionnaire.ui.NewArtisan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The designer portal's half of the shared questionnaire trees (not shared): what an artisan typed on
 * the phone is created as, and which old outbox entries hold the v2 queue.
 */
class QuestionnairePortalTest {
    @Test fun `an artisan registered on the phone needs what the server needs, and travels as the create body`() {
        assertFalse(NewArtisan(name = "Meena", place = "Bankura", dos = "Ask first", donts = "No flash").valid)
        val shared = NewArtisan(name = "Meena", place = "Bankura", aadhaarNotShared = true, dos = "Ask first", donts = "No flash")
        assertTrue(shared.valid)
        val withNumber = NewArtisan(name = "Meena", place = "Bankura", aadhaar = "1234 5678 9012", dos = "a", donts = "b")
        assertTrue(withNumber.valid)
        // The portal's own create body: the designer portal files the artisan under the design workshop.
        val body = QuestionnairePortal.json.decodeFromString(ArtisanCreateRequest.serializer(), QuestionnairePortal.artisanCreateJson(withNumber, "w1"))
        assertEquals("123456789012", body.aadhaarNumber)
        assertEquals("w1", body.designWorkshopId)
        assertNull(QuestionnairePortal.json.decodeFromString(ArtisanCreateRequest.serializer(), QuestionnairePortal.artisanCreateJson(shared, null)).aadhaarNumber)
    }


    @Test fun `sessions here file under a design workshop`() {
        assertTrue(QuestionnairePortal.FILES_UNDER_DESIGN_WORKSHOP)
        assertEquals("designer-portal", QuestionnairePortal.NAME)
    }

    @Test fun `the answer box gives plain words always, and its document only when formatted`() {
        assertEquals(QuestionnairePortal.AnswerParts("Teak and sal", null), QuestionnairePortal.answerParts("Teak and sal"))
        val doc = com.designprototype.workshop.ui.recordStoredKeepingFormat(
            com.designprototype.workshop.ui.recordDocFromStored("Teak").let { d ->
                d.copy(blocks = d.blocks.map { b -> b.copy(spans = b.spans.map { it.copy(marks = setOf(com.designprototype.workshop.report.Mark.BOLD)) }) })
            }
        )!!
        val parts = QuestionnairePortal.answerParts(doc)
        assertEquals(doc, parts.richText)
        assertFalse(parts.text.contains("{"))
        assertTrue(parts.text.contains("Teak"))
    }
}
