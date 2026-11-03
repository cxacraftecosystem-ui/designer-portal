package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessRosterWireTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `an emptied box sends an explicit null so the column is actually cleared`() {
        val body = accessRosterUpdateJson(fullName = "", role = null, notes = "   ")
        assertEquals(JsonNull, body["fullName"])
        assertEquals(JsonNull, body["role"])
        assertEquals(JsonNull, body["notes"])
    }

    @Test
    fun `a filled box is trimmed and sent`() {
        val body = accessRosterUpdateJson(fullName = "  Asha Sharma ", role = "RESEARCHER", notes = " let in for the cluster ")
        assertEquals(JsonPrimitive("Asha Sharma"), body["fullName"])
        assertEquals(JsonPrimitive("RESEARCHER"), body["role"])
        assertEquals(JsonPrimitive("let in for the cluster"), body["notes"])
    }

    @Test
    fun `the correction body can never move the gate`() {
        val body = accessRosterUpdateJson(fullName = "Asha", role = "DESIGNER", notes = "note")
        assertEquals(
            "the PATCH may write these three admin-typed columns and nothing else",
            setOf("fullName", "role", "notes"),
            body.keys
        )
        assertFalse("an email key here would let a name edit re-point somebody's access", body.containsKey("email"))
        assertFalse("status moves through the decision endpoint, which stamps who decided", body.containsKey("status"))
    }

    @Test
    fun `a row from an older or newer server still decodes`() {
        val row = json.decodeFromString<AccessRosterDto>(
            """{"id":"a1","email":"someone@example.org","somethingNew":{"nested":true}}"""
        )
        assertEquals("someone@example.org", row.email)
        assertEquals(
            "an unreadable row must not be drawn as somebody who may sign in",
            AccessStatus.PENDING,
            row.status
        )
        assertEquals(0, row.attemptCount)
    }

    @Test
    fun `the four states are the server's own spellings`() {
        assertEquals("ACTIVE", AccessStatus.ACTIVE)
        assertEquals("PENDING", AccessStatus.PENDING)
        assertEquals("REJECTED", AccessStatus.REJECTED)
        assertEquals("SUSPENDED", AccessStatus.SUSPENDED)
        assertEquals("APPROVE", AccessDecision.APPROVE)
        assertEquals("REJECT", AccessDecision.REJECT)
    }

    @Test
    fun `the decision body carries the decision, and the role only when one was chosen`() {
        val plain = json.encodeToString(AccessDecisionBody.serializer(), AccessDecisionBody(decision = AccessDecision.APPROVE))
        assertTrue(plain.contains("\"decision\":\"APPROVE\""))
        assertFalse(plain.contains("role"))

        val tiered = json.encodeToString(
            AccessDecisionBody.serializer(),
            AccessDecisionBody(decision = AccessDecision.APPROVE, role = "RESEARCHER")
        )
        assertTrue(tiered.contains("\"role\":\"RESEARCHER\""))
    }
}
