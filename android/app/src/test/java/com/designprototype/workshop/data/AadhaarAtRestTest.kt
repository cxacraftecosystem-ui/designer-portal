package com.designprototype.workshop.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A typed Aadhaar number never reaches this phone's storage in the clear (2026-10-17).
 *
 * The outbox and the artisan form's draft hold it sealed (AES-GCM under a Keystore key on a device;
 * a software AES key stands in for the Keystore here), the plaintext comes back only in memory for the
 * send, a sent entry leaves nothing behind, and when sealing is impossible the number is not written
 * at all.
 */
class AadhaarAtRestTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val number = "234567890124"

    private class SoftwareSealer : AadhaarAtRest.Sealer {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun seal(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plain)
        }
        override fun open(sealed: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
            return cipher.doFinal(sealed, 12, sealed.size - 12)
        }
    }

    private object BrokenKeystore : AadhaarAtRest.Sealer {
        override fun seal(plain: ByteArray): ByteArray = throw IllegalStateException("no keystore")
        override fun open(sealed: ByteArray): ByteArray = throw IllegalStateException("no keystore")
    }

    @Before
    fun useSoftwareKey() {
        AadhaarAtRest.sealerForTests = SoftwareSealer()
    }

    @After
    fun restore() {
        AadhaarAtRest.sealerForTests = null
    }

    private fun body(aadhaar: String?) =
        """{"name":"Kalu Ram","place":"Bagru",""" +
            (if (aadhaar != null) """"aadhaarNumber":"$aadhaar",""" else "") +
            """"formContract":2}"""

    @Test
    fun a_stored_outbox_body_holds_no_number_and_opens_to_the_same_bytes() {
        val original = body(number)
        val stored = AadhaarAtRest.sealPayload(original)
        assertFalse(stored.contains(number))
        assertFalse(stored.contains("\"aadhaarNumber\""))
        assertTrue(stored.contains(AadhaarAtRest.SEALED_FIELD))
        // Byte for byte, so a replay under the same Idempotency-Key is the same request.
        assertEquals(original, AadhaarAtRest.openPayload(stored))
    }

    @Test
    fun a_body_without_a_typed_number_is_left_exactly_as_it_was() {
        for (unchanged in listOf(body(null), body(""), body("XXXX XXXX 0124"), "not json", "[1,2]")) {
            assertEquals(unchanged, AadhaarAtRest.sealPayload(unchanged))
            assertEquals(unchanged, AadhaarAtRest.openPayload(unchanged))
        }
    }

    @Test
    fun without_a_keystore_the_number_is_withheld_and_never_written() {
        AadhaarAtRest.sealerForTests = BrokenKeystore
        val stored = AadhaarAtRest.sealPayload(body(number))
        assertFalse(stored.contains(number))
        assertTrue(AadhaarAtRest.numberWithheld(stored))
        assertTrue(stored.contains("\"${AadhaarAtRest.WITHHELD_FIELD}\":\"0124\""))
        // Read back still MARKED, so the sync pass holds it for the number to be typed again rather
        // than sending the rest as though nothing had been typed.
        val opened = AadhaarAtRest.openPayload(stored)
        assertFalse(opened.contains("\"aadhaarNumber\""))
        assertTrue(AadhaarAtRest.numberWithheld(opened))
        assertEquals("0124", AadhaarAtRest.withheldLast4(opened))
    }

    @Test
    fun a_sealed_number_whose_key_is_gone_is_marked_withheld_rather_than_dropped() {
        val stored = AadhaarAtRest.sealPayload(body(number))
        AadhaarAtRest.sealerForTests = SoftwareSealer() // a different key: the old one is gone
        val opened = AadhaarAtRest.openPayload(stored)
        assertFalse(opened.contains("\"aadhaarNumber\""))
        assertTrue("an unopenable number must hold the entry, not vanish", AadhaarAtRest.numberWithheld(opened))
        assertEquals(null, AadhaarAtRest.withheldLast4(opened))
    }

    @Test
    fun a_held_entry_is_listed_as_needing_the_number_and_retyping_it_seals_it_and_unholds_it() = runBlocking {
        val context = InMemoryContext(folder.root)
        AadhaarAtRest.sealerForTests = BrokenKeystore
        OfflineOutbox.enqueue(
            context,
            PendingEntry(
                id = "e1",
                type = "artisan",
                payloadJson = body(number),
                label = "Kalu Ram",
                createdAt = "2026-10-18T00:00:00Z",
                targetId = "artisan-1",
            ),
        )
        OfflineOutbox.markFailure(context, "e1", AadhaarAtRest.RETYPE_REASON)
        val row = outboxFailureRows(OfflineOutbox.all(context)).single()
        assertTrue(row.needsAadhaar)
        assertEquals("0124", row.aadhaarLast4)
        assertEquals(AadhaarAtRest.RETYPE_REASON, row.reason)

        AadhaarAtRest.sealerForTests = SoftwareSealer() // the Keystore answers again
        assertTrue(AadhaarAtRest.canSeal())
        assertTrue(OfflineOutbox.retypeAadhaar(context, "e1", number))
        val onDisk = File(folder.root, "outbox").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse("the retyped number is written sealed", onDisk.contains(number))
        val entry = OfflineOutbox.all(context).single()
        assertEquals(body(number), entry.payloadJson)
        assertEquals(null, entry.failure)
        assertFalse(AadhaarAtRest.numberWithheld(entry.payloadJson))
        assertTrue(outboxFailureRows(listOf(entry)).isEmpty())
    }

    @Test
    fun an_entry_whose_record_is_already_saved_is_never_held_for_the_number() {
        AadhaarAtRest.sealerForTests = BrokenKeystore
        val held = AadhaarAtRest.openPayload(AadhaarAtRest.sealPayload(body(number)))
        val saved = PendingEntry(
            id = "e2",
            type = "artisan",
            payloadJson = held,
            label = "Kalu Ram",
            createdAt = "2026-10-18T00:00:00Z",
            createdId = "artisan-2",
            failure = "Saved, but 1 file was refused.",
        )
        assertFalse(outboxFailureRows(listOf(saved)).single().needsAadhaar)
    }

    @Test
    fun the_outbox_file_never_holds_the_number_and_a_sent_entry_leaves_nothing() = runBlocking {
        val context = InMemoryContext(folder.root)
        val entry = PendingEntry(
            id = "e1",
            type = "artisan",
            payloadJson = body(number),
            label = "Kalu Ram",
            createdAt = "2026-10-17T00:00:00Z",
        )
        OfflineOutbox.enqueue(context, entry)
        val onDisk = File(folder.root, "outbox").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse("the queue file holds the typed number", onDisk.contains(number))
        // In memory, for the send, it is the number that was typed.
        assertEquals(body(number), OfflineOutbox.all(context).single().payloadJson)

        OfflineOutbox.remove(context, OfflineOutbox.all(context).single())
        val after = File(folder.root, "outbox").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(after.contains(number))
        assertFalse(after.contains(AadhaarAtRest.SEALED_FIELD))
    }

    @Test
    fun the_artisan_form_draft_keeps_the_number_sealed_and_gives_it_back() {
        val root = folder.newFolder("form-drafts")
        FormDraftStore.save(root, "user-1", "artisan:new", mapOf("name" to "Kalu Ram", "aadhaar" to number))
        val onDisk = root.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse("the draft file holds the typed number", onDisk.contains(number))
        assertEquals(number, FormDraftStore.load(root, "user-1", "artisan:new")?.fields?.get("aadhaar"))
    }

    @Test
    fun without_a_keystore_the_draft_drops_the_number() {
        AadhaarAtRest.sealerForTests = BrokenKeystore
        val root = folder.newFolder("form-drafts")
        FormDraftStore.save(root, "user-1", "artisan:new", mapOf("name" to "Kalu Ram", "aadhaar" to number))
        val onDisk = root.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(onDisk.contains(number))
        assertEquals(null, FormDraftStore.load(root, "user-1", "artisan:new")?.fields?.get("aadhaar"))
    }
}
