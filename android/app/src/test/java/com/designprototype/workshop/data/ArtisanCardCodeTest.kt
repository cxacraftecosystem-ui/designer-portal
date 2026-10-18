package com.designprototype.workshop.data

import com.designprototype.workshop.artisanToOption
import com.designprototype.workshop.optionToArtisan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The artisan card's parse rules on the handset, held to `shared/artisan-card-vectors.json` — the
 * same file the backend and the web read — and the offline register match a scan relies on.
 */
class ArtisanCardCodeTest {

    /** The shared table, found by walking up from the module (as `DwTextFormatParityTest` does). */
    private fun vectors(): JsonObject {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "shared/artisan-card-vectors.json")
            if (candidate.isFile) return Json.parseToJsonElement(candidate.readText()).jsonObject
            dir = dir.parentFile
        }
        error("shared/artisan-card-vectors.json was not found above ${java.io.File("").absolutePath}")
    }

    @Test
    fun `the check and the short code match the shared vectors`() {
        val rows = vectors()["shortCodes"]!!.jsonArray
        assertTrue(rows.size >= 5)
        for (row in rows) {
            val o = row.jsonObject
            val issuer = o["issuer"]!!.jsonPrimitive.content
            val code = o["code"]!!.jsonPrimitive.content
            assertEquals(o["check"]!!.jsonPrimitive.content, ArtisanCardCode.check(issuer, code))
            assertEquals(o["shortCode"]!!.jsonPrimitive.content, ArtisanCardCode.shortCode(issuer, code))
        }
    }

    @Test
    fun `every parse vector parses, or fails for the stated reason`() {
        val disagreements = mutableListOf<String>()
        for (row in vectors()["parse"]!!.jsonArray) {
            val o = row.jsonObject
            val input = o["input"]!!.jsonPrimitive.content
            val result = ArtisanCardCode.parse(input)
            val error = o["error"]?.jsonPrimitive?.contentOrNull
            if (error != null) {
                val ok = result is ArtisanCardCode.Result.Refused && result.reason.name == error
                if (!ok) disagreements += "$input: expected $error, got $result"
            } else {
                val e = o["expect"]!!.jsonObject
                val expected = ArtisanCardCode.Parsed(
                    issuer = e["issuer"]!!.jsonPrimitive.content,
                    code = e["code"]!!.jsonPrimitive.content,
                    keyVersion = e["keyVersion"]!!.jsonPrimitive.intOrNull,
                    tag = e["tag"]!!.jsonPrimitive.contentOrNull,
                    typed = e["typed"]!!.jsonPrimitive.content.toBoolean(),
                )
                if (result != ArtisanCardCode.Result.Ok(expected)) disagreements += "$input: expected $expected, got $result"
            }
        }
        assertEquals("", disagreements.joinToString("\n"))
    }

    @Test
    fun `an old DPW1 code is not a card, so it goes on to the DPW1 decoder`() {
        val old = (encodeWorkshopCode(DwWorkshopRecordType.ARTISAN, "cmsvfnb4y0001qq1bzd2g48lq") as DwEncodeResult.Ok).code
        assertFalse(ArtisanCardCode.looksLikeCard(old))
        assertTrue(decodeWorkshopCode(old) is DwDecodeResult.Ok)
        // A mistyped card IS meant as a card, and is refused with the server's sentence.
        assertTrue(ArtisanCardCode.looksLikeCard("D-7K3QM-9XA2F-H0"))
    }

    private val own = ArtisanDto(
        id = "a-own", name = "Sita Devi", place = "Bagru", status = "",
        card = ArtisanCardWireDto("7K3QM9XA2F", "D-7K3QM-9XA2F-HQ", "CXA1:D1:7K3QM9XA2F:QQPZR8FHGZPJXHBM"),
        aadhaarLast4 = "9012",
    )
    private val linked = ArtisanDto(
        id = "a-linked", name = "Meena Devi", place = "Bhuj", status = "",
        linkedCardCodes = listOf("0123456789"),
    )

    private fun parsed(input: String) = (ArtisanCardCode.parse(input) as ArtisanCardCode.Result.Ok).card

    @Test
    fun `offline, a card matches the cached register by its own code or by the linked Field Repository code`() {
        val register = listOf(own, linked)
        assertEquals("a-own", matchCardInRegister(parsed("D-7K3QM-9XA2F-HQ"), register)?.id)
        assertEquals("a-own", matchCardInRegister(parsed("CXA1:D1:7K3QM9XA2F:QQPZR8FHGZPJXHBM"), register)?.id)
        assertEquals("a-linked", matchCardInRegister(parsed("F-01234-56789-S5"), register)?.id)
        // An F card never matches a D code that happens to be equal, nor the other way round.
        assertNull(matchCardInRegister(parsed("F-ABCDE-FGHJK-KH"), register))
        assertNull(matchCardInRegister(parsed("D-ZZZZZ-ZZZZZ-2W"), register))
    }

    @Test
    fun `the register cache keeps the card, so the match works after a restart with no signal`() {
        val back = optionToArtisan(artisanToOption(own))!!
        assertEquals(own.card, back.card)
        assertEquals("9012", back.aadhaarLast4)
        val linkedBack = optionToArtisan(artisanToOption(linked))!!
        assertEquals(listOf("0123456789"), linkedBack.linkedCardCodes)
        assertNull(linkedBack.card)
        // The card rides under underscore keys that no stage field is named, so nothing hydrates from it.
        assertTrue(artisanToOption(own).data.keys.all { it.startsWith("_") })
    }

    @Test
    fun `an unknown card offline is pending and keeps its short code`() {
        val card = parsed("CXA1:D1:7K3QM9XA2F:QQPZR8FHGZPJXHBM")
        assertNull(matchCardInRegister(card, emptyList()))
        val value = ArtisanCardCode.pendingValue(card)
        assertEquals("card:D-7K3QM-9XA2F-HQ", value)
        assertTrue(ArtisanCardCode.isPending(value))
        assertEquals("Card D-7K3QM… links when you're online.", ArtisanCardCode.pendingLabel(value))
    }
}
