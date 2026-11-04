package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DwWorkshopCodesTest {

    @Test
    fun `a code round-trips to the same record, and the id comes back in the case the repository stores`() {
        val encoded = encodeWorkshopCode("artisan", ARTISAN_ID)

        assertEquals("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD", codeOf(encoded))
        assertEquals(DwWorkshopCodeRef(DwWorkshopRecordType.ARTISAN, ARTISAN_ID), refOf(encoded))

        val decoded = decodeWorkshopCode(codeOf(encoded))
        assertEquals(DwWorkshopCodeRef(DwWorkshopRecordType.ARTISAN, ARTISAN_ID), refOf(decoded))
    }

    @Test
    fun `a prototype's client key round-trips, so a tag can be printed before the row has ever synced`() {
        val encoded = encodeWorkshopCode(DwWorkshopRecordType.PROTOTYPE, CLIENT_KEY)
        assertEquals("DPW1:P:3F7A91C2-0B4D-4E19-9C2A-1D5E6F708A9B:K5T5", codeOf(encoded))

        val decoded = decodeWorkshopCode(codeOf(encoded))
        assertEquals(DwWorkshopCodeRef(DwWorkshopRecordType.PROTOTYPE, CLIENT_KEY), refOf(decoded))
    }

    @Test
    fun `the same id under two record types is two different codes`() {
        assertEquals("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD", codeOf(encodeWorkshopCode("artisan", ARTISAN_ID)))
        assertEquals("DPW1:P:CMSIK2JG8000EH8XC1LCY661A:63DP", codeOf(encodeWorkshopCode("prototype", ARTISAN_ID)))
    }

    @Test
    fun `what a human does to a printed code is tolerated, what it means is not`() {
        val code = "DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD"

        assertEquals(EXPECTED_REF, refOf(decodeWorkshopCode(code.lowercase())))
        assertEquals(EXPECTED_REF, refOf(decodeWorkshopCode(formatWorkshopCodeForPrint(code))))
        assertEquals(EXPECTED_REF, refOf(decodeWorkshopCode("  $code\n")))

        assertEquals("5QVC", workshopCodeCheck("DPW1:P:CMT0PROTOTYPE0001ABC"))
        assertTrue(decodeWorkshopCode("DPW1:P:CMT0PROTOTYPE0001ABC:5QVC") is DwDecodeResult.Ok)
        assertEquals("1HDH", workshopCodeCheck("DPW1:P:CM0000001MPROTOTYPE"))
        for (typed in listOf("1HDH", "IHDH", "LHDH")) {
            val decoded = decodeWorkshopCode("DPW1:P:CM0000001MPROTOTYPE:$typed")
            assertEquals("cm0000001mprototype", refOf(decoded)?.id)
            assertEquals("DPW1:P:CM0000001MPROTOTYPE:1HDH", (decoded as DwDecodeResult.Ok).code)
        }
        assertEquals("0N94", workshopCodeCheck("DPW1:P:CM00000010PROTOTYPE"))
        assertTrue(decodeWorkshopCode("DPW1:P:CM00000010PROTOTYPE:ON94") is DwDecodeResult.Ok)
        assertEquals(DwDecodeRefusal.CHECK_FAILED, refusalOf(decodeWorkshopCode("DPW1:P:CM00000010PROTOTYPE:UN94")))
    }

    @Test
    fun `a no-break space in a typed code is a refusal about nothing, so it is not one`() {
        val nbsp = "\u00A0"
        val zwnbsp = "\uFEFF"
        val figureSpace = "\u2007"
        assertEquals(EXPECTED_REF, refOf(decodeWorkshopCode("DPW1$nbsp:A:C${nbsp}MSIK2JG8000EH8XC1LCY661A:NEWD")))
        assertEquals(EXPECTED_REF, refOf(decodeWorkshopCode("${zwnbsp}DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")))
        assertEquals(EXPECTED_REF, refOf(decodeWorkshopCode("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD$figureSpace")))

        assertEquals(
            "DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD",
            codeOf(encodeWorkshopCode("artisan", "$zwnbsp$figureSpace$ARTISAN_ID$nbsp"))
        )
        assertEquals(DwEncodeRefusal.NO_ID, refusalOf(encodeWorkshopCode("artisan", nbsp)))
    }

    @Test
    fun `a corrupted code is refused, and refused for the right reason`() {
        val good = "DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD"

        val oneOff = decodeWorkshopCode(good.replace("CMSIK2JG8", "CMSIK2JG9"))
        assertEquals(DwDecodeRefusal.CHECK_FAILED, refusalOf(oneOff))

        assertEquals(
            DwDecodeRefusal.CHECK_FAILED,
            refusalOf(decodeWorkshopCode("DPW1:A:CMSIK2JG8000EH8XC1LCY661:NEWD"))
        )
        assertEquals(DwDecodeRefusal.MALFORMED, refusalOf(decodeWorkshopCode("DPW1:A:CM:NEWD")))
        assertEquals(
            DwDecodeRefusal.MALFORMED,
            refusalOf(decodeWorkshopCode("DPW1:A:CMSIK2JG8000EH8XC1LCY6_1A:NEWD"))
        )
        assertEquals(DwDecodeRefusal.CHECK_FAILED, refusalOf(decodeWorkshopCode("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEW")))
        assertEquals(DwDecodeRefusal.CHECK_FAILED, refusalOf(decodeWorkshopCode("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWDD")))

        for (foreign in listOf("https://example.org/artisans/abc", "4006381333931", "", "   ")) {
            assertNotEquals(
                "'$foreign' must not be reported as a damaged workshop code",
                DwDecodeRefusal.CHECK_FAILED,
                refusalOf(decodeWorkshopCode(foreign))
            )
        }
        assertEquals(
            DwDecodeRefusal.NOT_A_WORKSHOP_CODE,
            refusalOf(decodeWorkshopCode("https://example.org/x"))
        )
        assertEquals(DwDecodeRefusal.EMPTY, refusalOf(decodeWorkshopCode(null)))
        assertEquals(DwDecodeRefusal.EMPTY, refusalOf(decodeWorkshopCode("   ")))
        assertEquals(DwDecodeRefusal.NOT_A_WORKSHOP_CODE, refusalOf(decodeWorkshopCode("$good:EXTRA")))
        assertEquals(
            DwDecodeRefusal.NOT_A_WORKSHOP_CODE,
            refusalOf(decodeWorkshopCode("DPW1:A:CMSIK2JG8000EH8XC1LCY661A"))
        )
    }

    @Test
    fun `the sentences a designer reads are the web's, word for word`() {
        assertEquals(
            "This code does not check out, so one of its characters is wrong. Read it off the card " +
                "again, character by character — a single wrong character points at a different record, " +
                "and nothing later would notice.",
            messageOf(decodeWorkshopCode("DPW1:A:CMSIK2JG9000EH8XC1LCY661A:NEWD"))
        )
        assertEquals(
            "That is not a workshop card or tag. Workshop codes begin “DPW”; a shop barcode, a " +
                "payment code or a web address will not open a record here.",
            messageOf(decodeWorkshopCode("4006381333931"))
        )
        assertEquals(
            "That is not a workshop card or tag. Workshop codes begin “DPW” followed by a version number.",
            messageOf(decodeWorkshopCode("DPWX:A:CMSIK2JG8000EH8XC1LCY661A:NEWD"))
        )
        assertEquals("Nothing was scanned or typed.", messageOf(decodeWorkshopCode(null)))
        assertEquals(
            "This is a workshop code for a kind of record that needs a newer version of the app. " +
                "Update the app, then scan it again.",
            messageOf(decodeWorkshopCode("DPW1:Z:CMSIK2JG8000EH8XC1LCY661A:VZCR"))
        )
        assertEquals(
            "This code is damaged or was typed incompletely — the identifier in it is not a whole one. " +
                "Check it against the card.",
            messageOf(decodeWorkshopCode("DPW1:A:CM:NEWD"))
        )
        assertEquals(
            "No code can be printed for this kind of record — codes exist for artisans, crafts, workshops, " +
                "products, processes, tools, interviews, media files, design workshops and prototypes.",
            messageOf(encodeWorkshopCode("designer", ARTISAN_ID))
        )
        assertEquals(
            "This record has no identifier yet, so there is nothing to print on a code. Save it first.",
            messageOf(encodeWorkshopCode("artisan", null))
        )
        assertEquals(
            "That is not a record identifier, so no code can be printed for it. Save the record " +
                "first, then print its code.",
            messageOf(encodeWorkshopCode("artisan", "Ram Kumar"))
        )
    }

    @Test
    fun `versions - this build writes 1, reads every version it declares, and says so about a newer one`() {
        assertEquals(1, WORKSHOP_CODE_VERSION)
        assertTrue(1 in SUPPORTED_VERSIONS)
        assertTrue(codeOf(encodeWorkshopCode("artisan", ARTISAN_ID))!!.startsWith("DPW1:"))

        val future = decodeWorkshopCode("DPW9:A:CMSIK2JG8000EH8XC1LCY661A:3ESN")
        assertEquals(DwDecodeRefusal.NEWER_VERSION, refusalOf(future))
        assertTrue(messageOf(future)!!.contains("newer version"))
        assertTrue(messageOf(future)!!.contains("code format 9"))

        assertEquals(
            DwDecodeRefusal.UNKNOWN_RECORD_TYPE,
            refusalOf(decodeWorkshopCode("DPW1:Z:CMSIK2JG8000EH8XC1LCY661A:VZCR"))
        )
    }

    @Test
    fun `a version is a number and not the digits printed, so a leading zero is the same card`() {
        for (padded in listOf("DPW01", "DPW001", "DPW0000000001")) {
            val decoded = decodeWorkshopCode("$padded:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")
            assertEquals(EXPECTED_REF, refOf(decoded))
            assertEquals("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD", (decoded as DwDecodeResult.Ok).code)
        }
    }

    @Test
    fun `a version too large for an Int is still named in the sentence, not turned into a damaged card`() {
        val padded = decodeWorkshopCode("DPW00000000000000000009:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")
        assertEquals(DwDecodeRefusal.NEWER_VERSION, refusalOf(padded))
        assertTrue(messageOf(padded)!!.contains("code format 9"))

        val overflowed = decodeWorkshopCode("DPW${"9".repeat(400)}:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")
        assertEquals(DwDecodeRefusal.NEWER_VERSION, refusalOf(overflowed))
        assertTrue(messageOf(overflowed)!!.contains("code format Infinity"))

        val absurd = decodeWorkshopCode("DPW${"9".repeat(23)}:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")
        assertEquals(DwDecodeRefusal.NEWER_VERSION, refusalOf(absurd))
        val sentence = messageOf(absurd)!!
        assertTrue(
            "the refusal must name the version it read, and it said: $sentence",
            Regex("""code format [0-9]""").containsMatchIn(sentence),
        )
    }

    @Test
    fun `no sensitive field can be encoded, whatever a caller hands over`() {
        assertEquals(DwEncodeRefusal.ID_LOOKS_SENSITIVE, refusalOf(encodeWorkshopCode("artisan", "234567890123")))
        assertEquals(DwEncodeRefusal.ID_LOOKS_SENSITIVE, refusalOf(encodeWorkshopCode("artisan", "2345 6789 0123")))
        assertEquals(DwEncodeRefusal.ID_LOOKS_SENSITIVE, refusalOf(encodeWorkshopCode("artisan", "2345-6789-0123")))
        assertEquals(DwEncodeRefusal.ID_LOOKS_SENSITIVE, refusalOf(encodeWorkshopCode("artisan", "XXXX XXXX 9012")))
        assertEquals(DwEncodeRefusal.ID_LOOKS_SENSITIVE, refusalOf(encodeWorkshopCode("artisan", "VW1234567890")))

        for (value in listOf("Ram Kumar", "ram@example.org", "+91 98765 43210", "Bagru, Rajasthan", "ram", "", null)) {
            assertTrue(
                "encoding '$value' must be refused",
                encodeWorkshopCode("artisan", value) is DwEncodeResult.Refused
            )
        }

        assertEquals(
            DwEncodeRefusal.ID_NOT_AN_IDENTIFIER,
            refusalOf(encodeWorkshopCode("artisan", "CmSik2jg8000eh8xc1lcy661a"))
        )
        for (notARecord in listOf("designer", "location", "user", "review", "Artisan", "")) {
            assertEquals(
                "encoding a “$notARecord” must be refused as an unknown record type",
                DwEncodeRefusal.UNKNOWN_RECORD_TYPE,
                refusalOf(encodeWorkshopCode(notARecord, ARTISAN_ID))
            )
        }
    }

    @Test
    fun `an Aadhaar number spaced the way it is printed is refused as sensitive, not as malformed`() {
        for (spacing in listOf("\u00A0", "\u2007", "\u202F", " ")) {
            assertEquals(
                "an Aadhaar number grouped with U+%04X".format(spacing[0].code),
                DwEncodeRefusal.ID_LOOKS_SENSITIVE,
                refusalOf(encodeWorkshopCode("artisan", "2345${spacing}6789${spacing}0123"))
            )
        }
    }

    @Test
    fun `the identifier grammar's edges are the web's edges`() {
        assertEquals(DwEncodeRefusal.ID_NOT_AN_IDENTIFIER, refusalOf(encodeWorkshopCode("artisan", "cmsik2j")))
        assertEquals("DPW1:A:CMSIK2JG:WRSM", codeOf(encodeWorkshopCode("artisan", "cmsik2jg")))
        assertEquals("DPW1:A:${"A".repeat(64)}:NQSE", codeOf(encodeWorkshopCode("artisan", "a".repeat(64))))
        assertEquals(
            DwEncodeRefusal.ID_NOT_AN_IDENTIFIER,
            refusalOf(encodeWorkshopCode("artisan", "a".repeat(65)))
        )
        assertEquals("DPW1:A:CMSIK2JG-:C02V", codeOf(encodeWorkshopCode("artisan", "cmsik2jg-")))
        assertEquals(DwEncodeRefusal.ID_NOT_AN_IDENTIFIER, refusalOf(encodeWorkshopCode("artisan", "-cmsik2jg")))
        assertEquals(DwEncodeRefusal.ID_NOT_AN_IDENTIFIER, refusalOf(encodeWorkshopCode("artisan", "cm_sik2jg")))

        assertEquals(DwEncodeRefusal.ID_LOOKS_SENSITIVE, refusalOf(encodeWorkshopCode("artisan", "ABCDEFGHIJKLMNOPQRST")))
        assertEquals(
            DwEncodeRefusal.ID_NOT_AN_IDENTIFIER,
            refusalOf(encodeWorkshopCode("artisan", "ABCDEFGHIJKLMNOPQRSTU"))
        )

        assertEquals(
            DwEncodeRefusal.ID_NOT_AN_IDENTIFIER,
            refusalOf(encodeWorkshopCode("artisan", "\u0967\u0968\u0969\u096A\u096B\u096C\u096D\u096E"))
        )
    }

    @Test
    fun `the check is the web's, character for character`() {
        val golden = listOf(
            "DPW1:A:CMSIK2JG8000EH8XC1LCY661A" to "NEWD",
            "DPW1:P:CMSIK2JG8000EH8XC1LCY661A" to "63DP",
            "DPW1:P:CMT0PROTOTYPE0001ABC" to "5QVC",
            "DPW1:P:3F7A91C2-0B4D-4E19-9C2A-1D5E6F708A9B" to "K5T5",
            "DPW9:A:CMSIK2JG8000EH8XC1LCY661A" to "3ESN",
            "DPW2:A:CMSIK2JG8000EH8XC1LCY661A" to "8K9C",
            "DPW1:Z:CMSIK2JG8000EH8XC1LCY661A" to "VZCR",
            "DPW1:A:CMSIK2JG9000EH8XC1LCY661A" to "95WY",
            "DPW1:A:CMSIK2JG8000EH8XC1LCY661" to "T0GY",
            "DPW1:A:CMOTHER0000000000000000ZZ" to "F5K8",
            "DPW1:P:ABCDEFGH" to "62DV",
            "DPW1:A:" to "6HBE",
            "" to "S7E5",
            "DPW1:A:\u0B2A" to "8D8C",
            "\uD83D\uDE00" to "3H5R",
            "\u00E9" to "PV24",
        )
        for ((prefix, expected) in golden) {
            assertEquals("check over '$prefix'", expected, workshopCodeCheck(prefix))
        }
    }

    @Test
    fun `a payload can only contain characters a compact QR symbol carries`() {
        val alphanumeric = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"
        for (id in listOf(ARTISAN_ID, CLIENT_KEY, "cmsik2jg", "a".repeat(64), "cmsik2jg-")) {
            for (type in DwWorkshopRecordType.entries) {
                val code = codeOf(encodeWorkshopCode(type, id))!!
                for (character in code) {
                    assertTrue("'$character' in '$code' is outside QR alphanumeric mode", character in alphanumeric)
                }
            }
        }
    }

    @Test
    fun `the printed grouping is exactly what the decoder strips back off`() {
        assertEquals(
            "DPW1 :A:C MSIK 2JG8 000E H8XC 1LCY 661A :NEW D",
            formatWorkshopCodeForPrint("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")
        )
        assertEquals("", formatWorkshopCodeForPrint(""))
        assertEquals("ABCD E", formatWorkshopCodeForPrint("ABCDE"))
        assertEquals(
            EXPECTED_REF,
            refOf(decodeWorkshopCode(formatWorkshopCodeForPrint("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")))
        )
    }

    @Test
    fun `an unresolved code says the same thing about a missing record and a forbidden one`() {
        assertEquals("Artisan", workshopRecordTypeLabel(DwWorkshopRecordType.ARTISAN))
        assertEquals("Prototype", workshopRecordTypeLabel(DwWorkshopRecordType.PROTOTYPE))
        assertEquals(
            "No prototype in this workshop matches that tag. It may belong to another workshop, or " +
                "it may not be on this phone yet — open the workshop that made it, or find the " +
                "prototype in the list.",
            unresolvedWorkshopCodeMessage(DwWorkshopRecordType.PROTOTYPE)
        )
        assertEquals(
            "No artisan you can open matches that code. It may not exist, or it may " +
                "belong to work you do not have access to — search for the artisan by name instead.",
            unresolvedWorkshopCodeMessage(DwWorkshopRecordType.ARTISAN)
        )
        for (type in DwWorkshopRecordType.entries) {
            val message = unresolvedWorkshopCodeMessage(type).lowercase()
            for (giveaway in listOf("permission", "not allowed", "forbidden", "403", "exists")) {
                assertFalse("$type must not hint at $giveaway", message.contains(giveaway))
            }
        }
        assertEquals("Interview", workshopRecordTypeLabel(DwWorkshopRecordType.QUESTIONNAIRE))
        assertEquals("Media file", workshopRecordTypeLabel(DwWorkshopRecordType.MEDIA))
    }

    @Test
    fun `every record type has the same letter here as in the browser, and no two share one`() {
        val letters = linkedMapOf(
            DwWorkshopRecordType.ARTISAN to "A",
            DwWorkshopRecordType.CRAFT to "C",
            DwWorkshopRecordType.WORKSHOP to "W",
            DwWorkshopRecordType.PRODUCT to "D",
            DwWorkshopRecordType.PROCESS to "S",
            DwWorkshopRecordType.TOOL to "T",
            DwWorkshopRecordType.QUESTIONNAIRE to "Q",
            DwWorkshopRecordType.MEDIA to "M",
            DwWorkshopRecordType.DESIGN_WORKSHOP to "G",
            DwWorkshopRecordType.PROTOTYPE to "P",
        )

        assertEquals(letters.keys.toList(), DwWorkshopRecordType.entries.toList())

        val seen = mutableMapOf<String, DwWorkshopRecordType>()
        for ((type, letter) in letters) {
            assertEquals("$type's letter", letter, type.letter)
            assertNull("$letter is already ${seen[letter]}", seen.put(letter, type))
            assertFalse("$letter is a confusable", letter in "ILOU")

            val expected = "DPW1:$letter:CMSIK2JG8000EH8XC1LCY661A"
            assertEquals(
                "$expected:${workshopCodeCheck(expected)}",
                codeOf(encodeWorkshopCode(type, ARTISAN_ID))
            )
            assertEquals(
                DwWorkshopCodeRef(type, ARTISAN_ID),
                refOf(decodeWorkshopCode("$expected:${workshopCodeCheck(expected)}"))
            )
            assertEquals(type, DwWorkshopRecordType.ofWire(type.wire))
        }
    }

    @Test
    fun `the anti-PII gate applies to every record type, not only the two it was written for`() {
        for (type in DwWorkshopRecordType.entries) {
            for (sensitive in listOf("234567890123", "2345 6789 0123", "XXXX XXXX 9012", "VW1234567890")) {
                assertEquals(
                    "$type must refuse '$sensitive' as sensitive",
                    DwEncodeRefusal.ID_LOOKS_SENSITIVE,
                    refusalOf(encodeWorkshopCode(type, sensitive))
                )
            }
            assertEquals(DwEncodeRefusal.NO_ID, refusalOf(encodeWorkshopCode(type, null)))
        }
    }

    @Test
    fun `a row answers to both the identifiers it is known by`() {
        val synced = row("_entryId" to ARTISAN_ID, "_clientKey" to CLIENT_KEY)
        val unsynced = row("_clientKey" to CLIENT_KEY)
        assertEquals(ARTISAN_ID, workshopCodeIdForRow(synced))
        assertEquals(CLIENT_KEY, workshopCodeIdForRow(unsynced))
        assertNull(workshopCodeIdForRow(emptyMap()))
        assertEquals(CLIENT_KEY, workshopCodeIdForRow(mapOf("_entryId" to JsonNull, "_clientKey" to JsonPrimitive(CLIENT_KEY))))
        assertEquals("", workshopCodeIdForRow(row("_entryId" to "", "_clientKey" to CLIENT_KEY)))

        val tag = decodeWorkshopCode(codeOf(encodeWorkshopCode("prototype", CLIENT_KEY)))
        assertTrue(workshopCodeMatchesRow(refOf(tag)!!, synced))
        assertTrue(workshopCodeMatchesRow(refOf(tag)!!, unsynced))
        assertFalse(workshopCodeMatchesRow(refOf(tag)!!, row("_entryId" to "cmother0000000000000000zz")))
        assertFalse(workshopCodeMatchesRow(refOf(tag)!!, emptyMap()))
        assertFalse(workshopCodeMatchesRow(DwWorkshopCodeRef(DwWorkshopRecordType.ARTISAN, CLIENT_KEY), synced))
    }

    @Test
    fun `a workshop that exists only on one device gets no code, on either client's spelling of its id`() {
        val local = "local-3f7a91c2-0b4d-4e19-9c2a-1d5e6f708a9b"
        val webLocal = "dwlocal-3f7a91c2-0b4d-4e19-9c2a-1d5e6f708a9b"

        assertEquals(
            DwEncodeRefusal.ID_IS_DEVICE_LOCAL,
            refusalOf(encodeWorkshopCode(DwWorkshopRecordType.DESIGN_WORKSHOP, local))
        )
        assertEquals(
            DwEncodeRefusal.ID_IS_DEVICE_LOCAL,
            refusalOf(encodeWorkshopCode(DwWorkshopRecordType.DESIGN_WORKSHOP, webLocal))
        )

        for (type in DwWorkshopRecordType.entries) {
            assertEquals(
                "$type must refuse the browser's device-local prefix",
                DwEncodeRefusal.ID_IS_DEVICE_LOCAL,
                refusalOf(encodeWorkshopCode(type, webLocal))
            )
            if (type == DwWorkshopRecordType.DESIGN_WORKSHOP) continue
            assertNotEquals(
                "$type must NOT refuse a bare `local-` id — only a design workshop mints one",
                DwEncodeRefusal.ID_IS_DEVICE_LOCAL,
                refusalOf(encodeWorkshopCode(type, local))
            )
            assertNotNull(
                "$type must still PRINT a code for a bare `local-` id — the prefix is scoped to the " +
                    "design workshop, and refusing a real record's code is worse than the failure it prevents",
                codeOf(encodeWorkshopCode(type, local))
            )
        }

        val prefix = "DPW1:G:LOCAL-3F7A91C2-0B4D-4E19-9C2A-1D5E6F708A9B"
        val typed = "$prefix:${workshopCodeCheck(prefix)}"
        assertEquals(DwDecodeRefusal.DEVICE_LOCAL, refusalOf(decodeWorkshopCode(typed)))
        assertEquals(
            "This code was made before the workshop uploaded. Ask its creator to sync and send a new code.",
            messageOf(decodeWorkshopCode(typed))
        )

        assertEquals(
            "DPW1:G:CMSIK2JG8000EH8XC1LCY661A:${workshopCodeCheck("DPW1:G:CMSIK2JG8000EH8XC1LCY661A")}",
            codeOf(encodeWorkshopCode(DwWorkshopRecordType.DESIGN_WORKSHOP, ARTISAN_ID))
        )
    }

    @Test
    fun `a design workshop code that does not resolve names the admin, not a search box`() {
        assertEquals(
            "No workshop you can open matches this code. Ask an admin to add you — your work here is kept.",
            unresolvedWorkshopCodeMessage(DwWorkshopRecordType.DESIGN_WORKSHOP)
        )
        assertEquals("Design workshop", workshopRecordTypeLabel(DwWorkshopRecordType.DESIGN_WORKSHOP))
        val message = unresolvedWorkshopCodeMessage(DwWorkshopRecordType.DESIGN_WORKSHOP).lowercase()
        assertFalse(message.contains("request sent"))
        assertFalse(message.contains("we have asked"))
    }

    @Test
    fun `the answer to a scanned workshop this build cannot open names the build's limit, not the card`() {
        val message = designWorkshopCodeNotOpenableMessage()

        assertFalse(message.lowercase().contains("matches that code"))
        assertTrue(message.contains("If a colleague has just shared this one with you"))
        assertTrue(message.startsWith("This is a design workshop's code. Design workshops are opened from"))
        assertFalse(message.lowercase().contains("request sent"))
        assertFalse(message.lowercase().contains("we have asked"))
    }

    private fun row(vararg pairs: Pair<String, String>): DwDataRow =
        pairs.associate { (key, value) -> key to JsonPrimitive(value) }

    private fun codeOf(result: DwEncodeResult): String? = (result as? DwEncodeResult.Ok)?.code

    private fun refOf(result: DwEncodeResult): DwWorkshopCodeRef? = (result as? DwEncodeResult.Ok)?.ref

    private fun refOf(result: DwDecodeResult): DwWorkshopCodeRef? = (result as? DwDecodeResult.Ok)?.ref

    private fun refusalOf(result: DwEncodeResult): DwEncodeRefusal? = (result as? DwEncodeResult.Refused)?.reason

    private fun refusalOf(result: DwDecodeResult): DwDecodeRefusal? = (result as? DwDecodeResult.Refused)?.reason

    private fun messageOf(result: DwEncodeResult): String? = (result as? DwEncodeResult.Refused)?.message

    private fun messageOf(result: DwDecodeResult): String? = (result as? DwDecodeResult.Refused)?.message

    private companion object {
        const val ARTISAN_ID = "cmsik2jg8000eh8xc1lcy661a"

        const val CLIENT_KEY = "3f7a91c2-0b4d-4e19-9c2a-1d5e6f708a9b"

        val EXPECTED_REF = DwWorkshopCodeRef(DwWorkshopRecordType.ARTISAN, ARTISAN_ID)
    }
}
