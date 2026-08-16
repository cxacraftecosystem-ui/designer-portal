package com.designprototype.workshop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE THREE THINGS THE ALLOW-LIST'S WIRE MUST GET RIGHT ON A HANDSET.
 *
 * 1. **A cleared box actually clears the column.** This client is configured `explicitNulls =
 *    false`, so a typed body carrying `fullName = null` encodes to `{}` and the server's
 *    `exclude_unset` leaves the old value in place — a silent no-op an admin only discovers on the
 *    next load, far enough from the action that nobody connects the two. That is why the PATCH body
 *    is built key by key, and this is what stops somebody "simplifying" it into a data class.
 *
 * 2. **The PATCH cannot move the gate.** No `email` key and no `status` key, ever. The address IS
 *    the gate, so an edit that could change it would hand one person's admission to a different
 *    mailbox — and the person who lost it would simply stop being able to sign in, with the entry on
 *    screen still saying they may. Status transitions go through the decision endpoint so the stamps
 *    that accompany them are written by one piece of code that cannot forget them.
 *
 * 3. **A row the server sends in a shape this build does not know is still a row.** Every field of
 *    [AccessRosterDto] is defaulted, so a handset older or newer than the server it is talking to
 *    renders the queue rather than throwing `MissingFieldException` and taking the screen down over
 *    a column nobody was reading. The status defaults to PENDING and not ACTIVE, which is the
 *    direction that fails safe: an unreadable row must not be drawn as somebody who may sign in.
 */
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
        // The minimum the server could send, and a key this build has never heard of. Neither may
        // throw: a queue that fails to decode is a person nobody ever decides about.
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
        // Compared against `AccessRosterDto.status`, which is a plain string off the wire. A typo in
        // any of these is a screen whose Approve button never appears, with nothing to say why.
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
        // `explicitNulls = false` drops the absent role, which is what the server wants: an absent
        // `role` means "the tier already on the row, or the platform default", while an explicit
        // null would ask it to clear a tier an admin may have set deliberately.
        assertFalse(plain.contains("role"))

        val tiered = json.encodeToString(
            AccessDecisionBody.serializer(),
            AccessDecisionBody(decision = AccessDecision.APPROVE, role = "RESEARCHER")
        )
        assertTrue(tiered.contains("\"role\":\"RESEARCHER\""))
    }
}
