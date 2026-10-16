package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The pure half of two-step verification on the handset: which box a code belongs in, which URIs may
 * be handed to an authenticator, and reading the server's QR square.
 *
 * The shape rule is the server's `two_factor.code_kind` and the web's `codeKind`, and the three must
 * agree character for character — a recovery code typed by somebody who has lost their phone is the
 * worst moment for one client to disagree. The challenge sentence is read off the backend source, so
 * a wording change there fails here instead of silently leaving the form waiting on a code it can no
 * longer use.
 */
class TwoStepVerificationTest {

    @Test
    fun sixDigitsIsAnAuthenticatorCode() {
        assertEquals(TwoFactorCode.Kind.AUTHENTICATOR, TwoFactorCode.kindOf("123456"))
        assertEquals(TwoFactorCode.Kind.AUTHENTICATOR, TwoFactorCode.kindOf(" 123 456 "))
    }

    @Test
    fun eightLettersAndDigitsIsARecoveryCodeHoweverTyped() {
        assertEquals(TwoFactorCode.Kind.RECOVERY, TwoFactorCode.kindOf("ab2c-def3"))
        assertEquals(TwoFactorCode.Kind.RECOVERY, TwoFactorCode.kindOf("AB2CDEF3"))
        assertEquals(TwoFactorCode.Kind.RECOVERY, TwoFactorCode.kindOf("ab2c def3"))
    }

    @Test
    fun anythingElseIsNeither() {
        listOf("", "12345", "1234567", "ab2c-def", "ab2c-def3x", "12 34 5", "ab2c_def3").forEach {
            assertNull(it, TwoFactorCode.kindOf(it))
        }
    }

    @Test
    fun onlyATotpOtpauthUriIsHandedToAnAuthenticator() {
        val uri = "otpauth://totp/Design%20Prototype%20Workshop%3Ameera%40example.org?secret=JBSWY3DPEHPK3PXP&issuer=x"
        assertEquals(uri, TwoFactorCode.authenticatorUri(uri))
        assertEquals(uri, TwoFactorCode.authenticatorUri("  $uri\n"))
        assertNull(TwoFactorCode.authenticatorUri("otpauth://hotp/x?secret=A"))
        assertNull(TwoFactorCode.authenticatorUri("https://example.org/?secret=A"))
        assertNull(TwoFactorCode.authenticatorUri("intent://totp/x?secret=A"))
        assertNull(TwoFactorCode.authenticatorUri("otpauth://totp/no-secret"))
    }

    @Test
    fun theKeyIsGroupedInFours() {
        assertEquals("JBSW Y3DP EHPK 3PXP", TwoFactorCode.grouped("JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun theServersSquareIsReadAsOneViewBoxAndOnePath() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 29 29\" shape-rendering=\"crispEdges\">" +
            "<rect width=\"29\" height=\"29\" fill=\"#ffffff\"/>" +
            "<path fill=\"#000000\" d=\"M4 4h7v1h-7zM12 4h1v1h-1z\"/></svg>"
        val drawing = QrSvg.parse(svg)
        assertNotNull(drawing)
        assertEquals(29, drawing!!.size)
        assertEquals("M4 4h7v1h-7zM12 4h1v1h-1z", drawing.pathData)
    }

    @Test
    fun aSquareOfAnyOtherShapeIsRefusedRatherThanGuessedAt() {
        assertNull(QrSvg.parse("<svg viewBox=\"0 0 29 30\"><path d=\"M0 0h1v1h-1z\"/></svg>"))
        assertNull(QrSvg.parse("<svg viewBox=\"0 0 29 29\"></svg>"))
        assertNull(QrSvg.parse("<svg viewBox=\"0 0 29 29\"><path d=\"M0 0 C1 1 2 2 3 3\"/></svg>"))
        assertNull(QrSvg.parse("not svg at all"))
    }

    @Test
    fun theFinishedChallengeSentenceIsTheServers() {
        val service = locate("backend/app/services/two_factor.py").readText()
        assert(service.contains("CHALLENGE_DEAD_DETAIL = \"$CHALLENGE_ENDED_DETAIL\"")) {
            "two_factor.CHALLENGE_DEAD_DETAIL no longer reads \"$CHALLENGE_ENDED_DETAIL\""
        }
    }

    private fun locate(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("could not find $relative above ${File("").absolutePath}")
    }
}
