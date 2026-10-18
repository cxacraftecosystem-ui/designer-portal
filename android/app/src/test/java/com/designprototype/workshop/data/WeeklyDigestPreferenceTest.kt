package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Monday summary's switch: the wire shape both portals send, and when the row is drawn. */
class WeeklyDigestPreferenceTest {
    private val json = ApiClient.json

    @Test
    fun parsesTheEligibleAnswer() {
        val dto = json.decodeFromString<WeeklyDigestPreferenceDto>(
            """
            {"eligible": true, "enabled": false, "emailAvailable": true, "schedule": "Mondays, 08:00 IST",
             "latest": {"id": "c1", "weekStart": "2026-10-19", "waiting": 4, "overdue": 2, "newThisWeek": 11,
                        "coverage": {"label": "Reports in", "value": 41, "of": 58, "percent": 71}}}
            """.trimIndent()
        )
        assertTrue(dto.eligible)
        assertFalse(dto.enabled)
        assertEquals("c1", dto.latest?.id)
        assertEquals(71, dto.latest?.coverage?.percent)
        assertTrue(WeeklyDigestSwitch.visible(dto))
        assertTrue(WeeklyDigestSwitch.description(dto).endsWith("By e-mail and here."))
    }

    @Test
    fun theDocumentationPortalsCoverageHasNoDenominator() {
        val dto = json.decodeFromString<WeeklyDigestPreferenceDto>(
            """{"eligible": true, "enabled": true, "emailAvailable": false, "schedule": "Mondays, 08:00 IST",
               "latest": {"id": "c2", "weekStart": "2026-10-19", "waiting": 1, "overdue": 0, "newThisWeek": 3,
                          "coverage": {"label": "Districts documented", "value": 12, "of": null, "percent": null}}}"""
        )
        assertNull(dto.latest?.coverage?.of)
        assertTrue(WeeklyDigestSwitch.description(dto).endsWith("Here, in your notifications."))
    }

    @Test
    fun theSwitchIsHiddenWhenNotEligibleOrUnread() {
        val dto = json.decodeFromString<WeeklyDigestPreferenceDto>(
            """{"eligible": false, "enabled": false, "emailAvailable": true, "schedule": "Mondays, 08:00 IST", "latest": null}"""
        )
        assertFalse(WeeklyDigestSwitch.visible(dto))
        assertFalse(WeeklyDigestSwitch.visible(null))
        assertFalse(WeeklyDigestSwitch.visible(WeeklyDigestPreferenceDto()))
    }

    @Test
    fun theDigestLinkIsRecognisedAndLeftAlone() {
        assertTrue(WeeklyDigestSwitch.isDigestLink("/digest/c1"))
        assertFalse(WeeklyDigestSwitch.isDigestLink("/design-workshops/w1"))
        assertFalse(WeeklyDigestSwitch.isDigestLink(null))
    }

    @Test
    fun theBodyIsOneField() {
        assertEquals("""{"enabled":false}""", json.encodeToString(WeeklyDigestPreferenceBody.serializer(), WeeklyDigestPreferenceBody(false)))
    }
}
