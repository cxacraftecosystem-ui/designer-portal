package com.designprototype.workshop.ui

import com.designprototype.workshop.UnsavedGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Discard" in the unsaved-changes prompt brought the draft back (0.0.20 emulator smoke).
 *
 * The prompt only cleared the guard and left. The form's draft was never told, so its flush on the
 * way out wrote the typing back to disk and the next visit restored it. Discard now drops the draft
 * first, and the flush that follows writes nothing.
 */
class DiscardDropsDraftTest {

    // ── The guard: Discard reaches the form's draft before the screen goes ────────────────

    @Test
    fun `discard drops the registered draft and then forgets the form`() {
        val guard = UnsavedGuard()
        var dropped = 0
        guard.dirty = true
        guard.onSave = {}
        guard.onDiscard = { dropped++ }

        guard.discard()

        assertEquals(1, dropped)
        assertFalse(guard.dirty)
        assertNull(guard.onSave)
        assertNull(guard.onDiscard)
    }

    @Test
    fun `a draft that fails to drop still lets the person leave`() {
        val guard = UnsavedGuard()
        guard.dirty = true
        guard.onDiscard = { error("disk full") }

        guard.discard()

        assertFalse(guard.dirty)
        assertNull(guard.onDiscard)
    }

    @Test
    fun `leaving without a prompt does not drop the draft`() {
        val guard = UnsavedGuard()
        var dropped = 0
        guard.onDiscard = { dropped++ }

        guard.clear()

        assertEquals(0, dropped)
    }

    // ── The gate: the flush on the way out does not write the form back ─────────────────────

    private val typed = mapOf("name" to "Ramesh", "place" to "Kutch")

    @Test
    fun `an open gate lets every write through`() {
        val gate = DraftDiscardGate()
        assertTrue(gate.admits(typed))
        assertTrue(gate.admits(typed))
    }

    @Test
    fun `after discard the flush with the same typing writes nothing`() {
        val gate = DraftDiscardGate()
        gate.close()

        // The form's own flush as it leaves composition, carrying what was on screen.
        assertFalse(gate.admits(typed))
        // The debounce catching up with the same values.
        assertFalse(gate.admits(typed))
        assertTrue(gate.isClosed)
    }

    @Test
    fun `typing something new after a save is protected again`() {
        val gate = DraftDiscardGate()
        gate.close()
        assertFalse(gate.admits(emptyMap()))

        assertTrue(gate.admits(mapOf("name" to "Next artisan")))
        assertFalse(gate.isClosed)
    }

    // ── The wiring: every guarded form hands over its draft, and Discard uses it ────────────

    private val main = listOf("src/main/java/com/designprototype/workshop", "app/src/main/java/com/designprototype/workshop")
        .map(::File).first { it.isDirectory }

    @Test
    fun `every form on the guard hands over its draft`() {
        val sources = main.walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.joinToString("\n")
        val calls = sources.lines()
            .filter { it.contains("RegisterUnsavedGuard(dirty") && !it.contains("dirty: Boolean") }
        assertEquals(8, calls.size)
        calls.forEach { args ->
            assertTrue("no draft in RegisterUnsavedGuard($args)", Regex("""draft = \w+""").containsMatchIn(args))
            assertFalse("null draft in RegisterUnsavedGuard($args)", args.contains("draft = null"))
        }
    }

    @Test
    fun `the prompt's Discard drops the draft before it leaves`() {
        val shell = File(main, "MainActivity.kt").readText().replace("\r\n", "\n")
        val discard = shell.indexOf("unsavedGuard.discard()\n")
        assertTrue("Discard must call unsavedGuard.discard()", discard > 0)
        val exit = shell.indexOf("exit()", discard)
        val button = shell.indexOf("Text(\"Discard\"", discard)
        assertTrue(exit in (discard + 1) until button)
        assertFalse(shell.contains("unsavedGuard.clear()\n                                    exit()"))
    }
}
