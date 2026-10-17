package com.designprototype.workshop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The session token is sealed before it is written (2026-10-17): see the note on [TokenStore.getToken].
 * The Keystore itself cannot run in a JVM unit test, so a reversible stand-in plays its part; what is
 * pinned here is everything around it — nothing in the file is the token, an older build's plain
 * token is sealed on first read and its plain copy deleted, a sealed value that no longer opens reads
 * as signed out, and a phone whose Keystore refuses keeps the token rather than losing it.
 */
class TokenStoreSealingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeSealer(var works: Boolean = true) : TokenSealer {
        override fun seal(plain: String): String? = if (works) "v1:sealed:" + plain.reversed() else null
        override fun open(sealed: String): String? =
            if (works && sealed.startsWith("v1:sealed:")) sealed.removePrefix("v1:sealed:").reversed() else null
    }

    private fun prefs(context: InMemoryContext) = context.getSharedPreferences("field_repository_auth", 0)

    @Test
    fun `the file never holds the token itself`() {
        val context = InMemoryContext(folder.root)
        val store = TokenStore(context, FakeSealer())
        store.setToken("eyJ.secret.token")
        assertEquals("eyJ.secret.token", store.getToken())
        val values = prefs(context).all.values.map { it.toString() }
        assertFalse("no plain copy", values.any { it == "eyJ.secret.token" })
        assertNull(prefs(context).getString("jwt", null))

        store.setToken(null)
        assertNull(store.getToken())
        assertTrue(prefs(context).all.isEmpty())
    }

    @Test
    fun `an older build's plain token is sealed on first read and nobody is signed out`() {
        val context = InMemoryContext(folder.root)
        prefs(context).edit().putString("jwt", "legacy-token").commit()
        val store = TokenStore(context, FakeSealer())
        assertEquals("legacy-token", store.getToken())
        assertNull("the plain copy is gone", prefs(context).getString("jwt", null))
        assertEquals("v1:sealed:" + "legacy-token".reversed(), prefs(context).getString("jwt_sealed", null))
        assertEquals("legacy-token", TokenStore(context, FakeSealer()).getToken())
    }

    @Test
    fun `a sealed token that no longer opens reads as signed out and is dropped`() {
        val context = InMemoryContext(folder.root)
        prefs(context).edit().putString("jwt_sealed", "v1:from-a-wiped-key").commit()
        assertNull(TokenStore(context, FakeSealer()).getToken())
        assertNull(prefs(context).getString("jwt_sealed", null))
    }

    @Test
    fun `a phone whose keystore refuses keeps the token as it always was`() {
        val context = InMemoryContext(folder.root)
        val store = TokenStore(context, FakeSealer(works = false))
        store.setToken("kept-token")
        assertEquals("kept-token", store.getToken())
        assertEquals("kept-token", prefs(context).getString("jwt", null))
    }

    @Test
    fun `the real sealer answers null rather than throwing where there is no keystore`() {
        // A JVM has no AndroidKeyStore provider, which is exactly the broken-phone case.
        val sealer = KeystoreTokenSealer("test-alias")
        assertNull(sealer.seal("x"))
        assertNull(sealer.open("v1:AAAA:BBBB"))
        assertNull(sealer.open("not-sealed"))
    }
}
