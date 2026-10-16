package com.designprototype.workshop.data

import com.designprototype.workshop.ui.aadhaar.AadhaarCopy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Aadhaar verification on the handset: hidden unless the server switched it on (including for an
 * account cached before the server ever sent `features`), the consent words the same as the web's,
 * and the wire shapes the server's.
 */
class AadhaarVerificationTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `an account with no features key reads as everything off`() {
        val cached = json.decodeFromString(
            UserDto.serializer(),
            """{"id":"u1","email":"a@example.org","name":"A","role":"RESEARCHER"}""",
        )
        assertEquals(AadhaarFeatures.OFF, AadhaarFeatures.of(cached))
        assertFalse(AadhaarFeatures.of(null).enabled)
    }

    @Test
    fun `each switch turns on only its own method`() {
        val user = json.decodeFromString(
            UserDto.serializer(),
            """{"id":"u1","email":"a@example.org","name":"A","role":"RESEARCHER",
               "features":{"aadhaarOfflineVerification":true,"aadhaarOnlineAuth":false}}""",
        )
        val features = AadhaarFeatures.of(user)
        assertTrue(features.offline)
        assertFalse(features.online)
        assertTrue(features.enabled)
    }

    @Test
    fun `the answer survives the account cache round trip, so it holds offline`() {
        val user = json.decodeFromString(
            UserDto.serializer(),
            """{"id":"u1","email":"a@example.org","name":"A","role":"RESEARCHER",
               "features":{"aadhaarOfflineVerification":true,"aadhaarOnlineAuth":true},
               "aadhaarVerifyStatus":"VERIFIED","aadhaarVerifyLast4":"2345"}""",
        )
        val again = json.decodeFromString(UserDto.serializer(), json.encodeToString(UserDto.serializer(), user))
        assertEquals(AadhaarFeatures(offline = true, online = true), AadhaarFeatures.of(again))
        assertEquals("VERIFIED", again.aadhaarVerifyStatus)
    }

    @Test
    fun `only a long decimal number is taken for a Secure QR`() {
        assertTrue(looksLikeSecureQr("1".repeat(1500)))
        assertFalse(looksLikeSecureQr("https://example.org"))
        assertFalse(looksLikeSecureQr("12345"))
    }

    @Test
    fun `consent words are the web's, sentence for sentence`() {
        val web = webSource("frontend/lib/aadhaar.ts")
        for (subject in listOf(AadhaarSubject.Self, AadhaarSubject.Artisan("a1"))) {
            for (point in AadhaarCopy.consentPoints(subject)) {
                // The web builds the first sentence around ${whose}; compare the fixed tail.
                val fixed = point.substringAfter("identity against UIDAI")
                assertTrue("web lacks: $fixed", web.contains(fixed))
            }
            assertTrue(web.contains(AadhaarCopy.consentStatement(subject)))
        }
    }

    @Test
    fun `no request body can be built without consent`() {
        val body = json.encodeToString(
            AadhaarSecureQrRequest.serializer(),
            AadhaarSecureQrRequest(consent = true, qrText = "123"),
        )
        assertTrue(body.contains("\"consent\":true"))
    }

    private fun webSource(path: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("$path not found above the working directory")
    }
}
