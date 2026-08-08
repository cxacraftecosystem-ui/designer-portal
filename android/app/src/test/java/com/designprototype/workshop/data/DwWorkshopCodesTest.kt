package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `DwWorkshopCodes.kt` against the values `frontend/lib/workshopCodes.ts` actually produces.
 *
 * EVERY EXPECTED VALUE BELOW WAS PRINTED BY THE WEB MODULE, not derived from the Kotlin. The cases
 * are `frontend/e2e/workshop-codes.spec.ts` — which is the payload grammar's specification — plus
 * the ones that only exist because the JVM is not a browser. That distinction is the whole point of
 * the file: a card printed by the web is a physical object in a workshop, and the only thing that
 * makes it readable on a handset is that these two implementations agree character for character.
 * When they disagree, the TypeScript is right and the Kotlin is broken.
 *
 * A ROUND TRIP ON ITS OWN WOULD PROVE NOTHING. `encode` then `decode` inside one language passes for
 * any self-consistent pair of functions, including a hash with a transposed constant — so the code
 * STRINGS are asserted whole, and the check characters are pinned in a golden table. Those are the
 * assertions with power; the round trips are there for the sentences they let the tests state.
 */
class DwWorkshopCodesTest {

    // ----------------------------------------------------------------------------------
    // The payload
    // ----------------------------------------------------------------------------------

    @Test
    fun `a code round-trips to the same record, and the id comes back in the case the repository stores`() {
        val encoded = encodeWorkshopCode("artisan", ARTISAN_ID)

        // The whole shape is asserted, not just that it round-trips: the printed string is a contract
        // with every card already in a workshop, and a silent change to it strands them.
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
        // The letter is inside the hashed prefix, so an artisan card and a prototype tag for the same
        // id differ in their check as well as their letter. A check computed over the id alone would
        // let one be read as the other by changing a single printed character.
        assertEquals("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD", codeOf(encodeWorkshopCode("artisan", ARTISAN_ID)))
        assertEquals("DPW1:P:CMSIK2JG8000EH8XC1LCY661A:63DP", codeOf(encodeWorkshopCode("prototype", ARTISAN_ID)))
    }

    // ----------------------------------------------------------------------------------
    // What a human does to a printed code
    // ----------------------------------------------------------------------------------

    // The web's name for this case carries a semicolon, which the JVM will not have in an identifier
    // — a comma is the only difference between the two.
    @Test
    fun `what a human does to a printed code is tolerated, what it means is not`() {
        val code = "DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD"

        // Lower case, the grouping formatWorkshopCodeForPrint adds, and stray whitespace all survive.
        assertEquals(EXPECTED_REF, refOf(decodeWorkshopCode(code.lowercase())))
        assertEquals(EXPECTED_REF, refOf(decodeWorkshopCode(formatWorkshopCodeForPrint(code))))
        assertEquals(EXPECTED_REF, refOf(decodeWorkshopCode("  $code\n")))

        // Crockford's confusables, applied to the CHECK only: O reads as 0 and I/L as 1.
        assertEquals("5QVC", workshopCodeCheck("DPW1:P:CMT0PROTOTYPE0001ABC"))
        assertTrue(decodeWorkshopCode("DPW1:P:CMT0PROTOTYPE0001ABC:5QVC") is DwDecodeResult.Ok)
        assertEquals("1HDH", workshopCodeCheck("DPW1:P:CM0000001MPROTOTYPE"))
        for (typed in listOf("1HDH", "IHDH", "LHDH")) {
            val decoded = decodeWorkshopCode("DPW1:P:CM0000001MPROTOTYPE:$typed")
            assertEquals("cm0000001mprototype", refOf(decoded)?.id)
            // What comes back is the CANONICAL spelling, so a code typed with an I and one typed with
            // a 1 are one value downstream rather than two that compare unequal.
            assertEquals("DPW1:P:CM0000001MPROTOTYPE:1HDH", (decoded as DwDecodeResult.Ok).code)
        }
        assertEquals("0N94", workshopCodeCheck("DPW1:P:CM00000010PROTOTYPE"))
        assertTrue(decodeWorkshopCode("DPW1:P:CM00000010PROTOTYPE:ON94") is DwDecodeResult.Ok)
        // U is absent from Crockford's alphabet on purpose and is left to fail rather than guessed at.
        assertEquals(DwDecodeRefusal.CHECK_FAILED, refusalOf(decodeWorkshopCode("DPW1:P:CM00000010PROTOTYPE:UN94")))
    }

    @Test
    fun `a no-break space in a typed code is a refusal about nothing, so it is not one`() {
        // KOTLIN-ONLY HAZARD. `Char.isWhitespace()` is FALSE for U+00A0 and U+FEFF and Java's `\s` is
        // ASCII-only, so the obvious spellings of the web's `replace(/\s+/g, "")` would leave both
        // attached — and the code would then fail the identifier grammar and be reported to a designer
        // as a DAMAGED CARD. The card is fine; a long press on the space bar of an Android keyboard
        // produces U+00A0, and a code pasted out of a document carries U+FEFF at its head.
        val nbsp = "\u00A0"
        val zwnbsp = "\uFEFF"
        val figureSpace = "\u2007"
        assertEquals(EXPECTED_REF, refOf(decodeWorkshopCode("DPW1$nbsp:A:C${nbsp}MSIK2JG8000EH8XC1LCY661A:NEWD")))
        assertEquals(EXPECTED_REF, refOf(decodeWorkshopCode("${zwnbsp}DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")))
        assertEquals(EXPECTED_REF, refOf(decodeWorkshopCode("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD$figureSpace")))

        // The same set governs the id handed to the encoder, which JavaScript's `trim()` strips.
        assertEquals(
            "DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD",
            codeOf(encodeWorkshopCode("artisan", "$zwnbsp$figureSpace$ARTISAN_ID$nbsp"))
        )
        // An id that is nothing but those characters is NO id, not a malformed one.
        assertEquals(DwEncodeRefusal.NO_ID, refusalOf(encodeWorkshopCode("artisan", nbsp)))
    }

    // ----------------------------------------------------------------------------------
    // Refusals
    // ----------------------------------------------------------------------------------

    @Test
    fun `a corrupted code is refused, and refused for the right reason`() {
        val good = "DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD"

        // ONE character of the id changed. This is the case the check exists for: the mutated id is a
        // perfectly well-formed cuid that collides with nobody, so nothing downstream could ever notice.
        val oneOff = decodeWorkshopCode(good.replace("CMSIK2JG8", "CMSIK2JG9"))
        assertEquals(DwDecodeRefusal.CHECK_FAILED, refusalOf(oneOff))

        // A tag torn across its last character: still a well-formed identifier, still refused.
        assertEquals(
            DwDecodeRefusal.CHECK_FAILED,
            refusalOf(decodeWorkshopCode("DPW1:A:CMSIK2JG8000EH8XC1LCY661:NEWD"))
        )
        // Short enough that it is no longer an identifier at all, which is a different sentence: there
        // is nothing here to check against the card character by character.
        assertEquals(DwDecodeRefusal.MALFORMED, refusalOf(decodeWorkshopCode("DPW1:A:CM:NEWD")))
        // `_` cannot be carried by QR alphanumeric mode, so an id holding one was never printed here.
        assertEquals(
            DwDecodeRefusal.MALFORMED,
            refusalOf(decodeWorkshopCode("DPW1:A:CMSIK2JG8000EH8XC1LCY6_1A:NEWD"))
        )
        // A check of the wrong LENGTH is a check failure and not a parse failure — three characters
        // read off a card is a character missed, which is exactly what this refusal is for.
        assertEquals(DwDecodeRefusal.CHECK_FAILED, refusalOf(decodeWorkshopCode("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEW")))
        assertEquals(DwDecodeRefusal.CHECK_FAILED, refusalOf(decodeWorkshopCode("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWDD")))

        // Something that is not ours at all. It must not be reported as damaged — a shop barcode is not
        // a broken workshop tag, and telling a designer to "check the card" would be nonsense.
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
        // The right number of colons is part of "is this ours at all", not part of "is it damaged".
        assertEquals(DwDecodeRefusal.NOT_A_WORKSHOP_CODE, refusalOf(decodeWorkshopCode("$good:EXTRA")))
        assertEquals(
            DwDecodeRefusal.NOT_A_WORKSHOP_CODE,
            refusalOf(decodeWorkshopCode("DPW1:A:CMSIK2JG8000EH8XC1LCY661A"))
        )
    }

    @Test
    fun `the sentences a designer reads are the web's, word for word`() {
        // These are the whole of what the feature says out loud when it refuses, and they are written
        // to send a designer somewhere specific. Paraphrasing one on the handset would mean the same
        // card explained two different ways depending on which client was holding it.
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
            "This is a workshop code, but it points at a kind of record this version of the app does not open.",
            messageOf(decodeWorkshopCode("DPW1:Z:CMSIK2JG8000EH8XC1LCY661A:VZCR"))
        )
        assertEquals(
            "This code is damaged or was typed incompletely — the identifier in it is not a whole one. " +
                "Check it against the card.",
            messageOf(decodeWorkshopCode("DPW1:A:CM:NEWD"))
        )
        assertEquals(
            "No code can be printed for a “craft” — codes exist for artisans and prototypes.",
            messageOf(encodeWorkshopCode("craft", ARTISAN_ID))
        )
        assertEquals(
            "This record has no identifier yet, so there is nothing to print on a code. Save it first.",
            messageOf(encodeWorkshopCode("artisan", null))
        )
        assertEquals(
            "That is not an identifier this repository issues, so no code can be printed for it. " +
                "Identifiers are the lower-case ids the app allocates when a record is saved.",
            messageOf(encodeWorkshopCode("artisan", "Ram Kumar"))
        )
    }

    // ----------------------------------------------------------------------------------
    // Versions
    // ----------------------------------------------------------------------------------

    @Test
    fun `versions - this build writes 1, reads every version it declares, and says so about a newer one`() {
        assertEquals(1, WORKSHOP_CODE_VERSION)
        assertTrue(1 in SUPPORTED_VERSIONS)
        assertTrue(codeOf(encodeWorkshopCode("artisan", ARTISAN_ID))!!.startsWith("DPW1:"))

        // A card printed by a LATER build. "Update the app" and "the tag is damaged" send a designer to
        // two completely different places, so these must not collapse into one refusal — and a handset
        // in a village is the client most likely to be the old one holding the newer card.
        val future = decodeWorkshopCode("DPW9:A:CMSIK2JG8000EH8XC1LCY661A:3ESN")
        assertEquals(DwDecodeRefusal.NEWER_VERSION, refusalOf(future))
        assertTrue(messageOf(future)!!.contains("newer version"))
        assertTrue(messageOf(future)!!.contains("code format 9"))

        // A record type a later build might add, met by this one.
        assertEquals(
            DwDecodeRefusal.UNKNOWN_RECORD_TYPE,
            refusalOf(decodeWorkshopCode("DPW1:Z:CMSIK2JG8000EH8XC1LCY661A:VZCR"))
        )
    }

    @Test
    fun `a version is a number and not the digits printed, so a leading zero is the same card`() {
        // The check is computed over the CANONICALISED prefix — "DPW1", never "DPW01" — which is what
        // makes a code with a padded version read as the version-1 card it is rather than fail its
        // check. Reading the version as text would break that silently and only for the cards a person
        // typed, which is the path with no Reed-Solomon under it.
        for (padded in listOf("DPW01", "DPW001", "DPW0000000001")) {
            val decoded = decodeWorkshopCode("$padded:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")
            assertEquals(EXPECTED_REF, refOf(decoded))
            assertEquals("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD", (decoded as DwDecodeResult.Ok).code)
        }
    }

    @Test
    fun `a version too large for an Int is still named in the sentence, not turned into a damaged card`() {
        // KOTLIN-ONLY HAZARD. `toIntOrNull()` returns null for both of these, and null would have to
        // become some refusal — the one it would become is "this card is damaged", about a card that is
        // not. JavaScript's `Number` reads them, so this reads them.
        val padded = decodeWorkshopCode("DPW00000000000000000009:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")
        assertEquals(DwDecodeRefusal.NEWER_VERSION, refusalOf(padded))
        assertTrue(messageOf(padded)!!.contains("code format 9"))

        val overflowed = decodeWorkshopCode("DPW${"9".repeat(400)}:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")
        assertEquals(DwDecodeRefusal.NEWER_VERSION, refusalOf(overflowed))
        assertTrue(messageOf(overflowed)!!.contains("code format Infinity"))

        // A VERSION OF SEVENTEEN OR MORE SIGNIFICANT DIGITS IS THE ONE CASE THIS PORT DOES NOT MATCH,
        // and it is written down rather than asserted away. Twenty-three nines is bit-for-bit the
        // double JavaScript prints as `1e+23`; `jsNumber` prints "9.999999999999999e+22", because it
        // goes through `Double.toString`, which before JDK 19 is not the shortest round-tripping
        // decimal (JDK-4511638). That belongs to `jsNumber` in `PlaceSearch.kt` and is not fixed from
        // here. What is asserted is what this file owns and what actually reaches a designer: the card
        // is called NEWER_VERSION rather than damaged, and the sentence names a number.
        val absurd = decodeWorkshopCode("DPW${"9".repeat(23)}:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")
        assertEquals(DwDecodeRefusal.NEWER_VERSION, refusalOf(absurd))
        assertTrue(messageOf(absurd)!!.contains("code format 9"))
    }

    // ----------------------------------------------------------------------------------
    // The anti-PII gate
    // ----------------------------------------------------------------------------------

    @Test
    fun `no sensitive field can be encoded, whatever a caller hands over`() {
        // The one that matters most. An artisan's Aadhaar number is the repository's deduplication key
        // and is regulated personal data; a QR is public the moment the card is printed.
        assertEquals(DwEncodeRefusal.ID_LOOKS_SENSITIVE, refusalOf(encodeWorkshopCode("artisan", "234567890123")))
        // The spaced form people actually type, and the mask the API hands to a caller not entitled to
        // the real number.
        assertEquals(DwEncodeRefusal.ID_LOOKS_SENSITIVE, refusalOf(encodeWorkshopCode("artisan", "2345 6789 0123")))
        assertEquals(DwEncodeRefusal.ID_LOOKS_SENSITIVE, refusalOf(encodeWorkshopCode("artisan", "2345-6789-0123")))
        assertEquals(DwEncodeRefusal.ID_LOOKS_SENSITIVE, refusalOf(encodeWorkshopCode("artisan", "XXXX XXXX 9012")))
        // Pehchan, in the shape normalize_pehchan stores it: upper-case alphanumerics, no separators.
        assertEquals(DwEncodeRefusal.ID_LOOKS_SENSITIVE, refusalOf(encodeWorkshopCode("artisan", "VW1234567890")))

        // Everything else a caller could wrongly pass, refused by the identifier grammar rather than by
        // a list of banned shapes — a list would only ever cover the fields somebody thought of.
        for (value in listOf("Ram Kumar", "ram@example.org", "+91 98765 43210", "Bagru, Rajasthan", "ram", "", null)) {
            assertTrue(
                "encoding '$value' must be refused",
                encodeWorkshopCode("artisan", value) is DwEncodeResult.Refused
            )
        }

        // An id with a capital in it is refused rather than upper-cased into an id that does not exist:
        // the payload is upper case, so a mixed-case id could not be decoded back to itself.
        assertEquals(
            DwEncodeRefusal.ID_NOT_AN_IDENTIFIER,
            refusalOf(encodeWorkshopCode("artisan", "CmSik2jg8000eh8xc1lcy661a"))
        )
        // A record type this build does not print, in the two spellings a caller could reach for.
        assertEquals(DwEncodeRefusal.UNKNOWN_RECORD_TYPE, refusalOf(encodeWorkshopCode("craft", ARTISAN_ID)))
        assertEquals(DwEncodeRefusal.UNKNOWN_RECORD_TYPE, refusalOf(encodeWorkshopCode("Artisan", ARTISAN_ID)))
    }

    @Test
    fun `an Aadhaar number spaced the way it is printed is refused as sensitive, not as malformed`() {
        // KOTLIN-ONLY HAZARD, and the reason the gate strips by JavaScript's whitespace rule. Java's
        // `\s` is ASCII-only, so with the obvious spelling a number grouped with no-break spaces —
        // which is what a paste out of a spreadsheet or a web form carries — would keep them, miss the
        // twelve-digit gate, and fall through to "that is not an identifier". The card is refused
        // either way; what is lost is the sentence that tells a caller it just handed over an Aadhaar
        // number, which is the only reason the sensitive shapes are tested first at all.
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
        // The floor keeps short human-typed strings out; the ceiling keeps a runaway value from
        // producing a symbol too dense to scan off a 22mm card. Both are exact, so both are pinned.
        assertEquals(DwEncodeRefusal.ID_NOT_AN_IDENTIFIER, refusalOf(encodeWorkshopCode("artisan", "cmsik2j")))
        assertEquals("DPW1:A:CMSIK2JG:WRSM", codeOf(encodeWorkshopCode("artisan", "cmsik2jg")))
        assertEquals("DPW1:A:${"A".repeat(64)}:NQSE", codeOf(encodeWorkshopCode("artisan", "a".repeat(64))))
        assertEquals(
            DwEncodeRefusal.ID_NOT_AN_IDENTIFIER,
            refusalOf(encodeWorkshopCode("artisan", "a".repeat(65)))
        )
        // A hyphen is legal inside and at the end (UUIDs are full of them) but not as the first
        // character, where it would make the payload's own separator grammar ambiguous to read aloud.
        assertEquals("DPW1:A:CMSIK2JG-:C02V", codeOf(encodeWorkshopCode("artisan", "cmsik2jg-")))
        assertEquals(DwEncodeRefusal.ID_NOT_AN_IDENTIFIER, refusalOf(encodeWorkshopCode("artisan", "-cmsik2jg")))
        // `_` is legal in a lot of id schemes and cannot be carried by QR alphanumeric mode.
        assertEquals(DwEncodeRefusal.ID_NOT_AN_IDENTIFIER, refusalOf(encodeWorkshopCode("artisan", "cm_sik2jg")))

        // The Pehchan gate's ceiling: twenty upper-case alphanumerics is a card number, twenty-one is
        // merely not an identifier. Both are refused; they are refused with different sentences.
        assertEquals(DwEncodeRefusal.ID_LOOKS_SENSITIVE, refusalOf(encodeWorkshopCode("artisan", "ABCDEFGHIJKLMNOPQRST")))
        assertEquals(
            DwEncodeRefusal.ID_NOT_AN_IDENTIFIER,
            refusalOf(encodeWorkshopCode("artisan", "ABCDEFGHIJKLMNOPQRSTU"))
        )

        // Devanagari numerals are not what a JavaScript `\d` matches, so they reach the identifier
        // grammar rather than the "that is a number" refusal. A Unicode-aware digit class on the JVM
        // would have quietly moved this case to the other sentence.
        assertEquals(
            DwEncodeRefusal.ID_NOT_AN_IDENTIFIER,
            refusalOf(encodeWorkshopCode("artisan", "\u0967\u0968\u0969\u096A\u096B\u096C\u096D\u096E"))
        )
    }

    // ----------------------------------------------------------------------------------
    // The check itself
    // ----------------------------------------------------------------------------------

    @Test
    fun `the check is the web's, character for character`() {
        // A hash agrees with another hash or it does not; there is no nearly. These sixteen values were
        // printed by `workshopCodes.ts` itself, and they are what stops a transposed constant or a
        // sign-extended shift from producing a self-consistent Kotlin that no web-printed card matches.
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
            // The empty prefix pins the offset basis on its own, which nothing else here does.
            "" to "S7E5",
            // Not reachable from a payload this file emits, and pinned anyway: the check is also
            // computed over what a person TYPED, and `Char.code` has to stay the UTF-16 code unit
            // `charCodeAt` returns rather than becoming a code point.
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
        // Byte mode would produce a symbol a third larger for the same id, on a card printed at 22mm.
        // This is `isQrAlphanumeric` in `frontend/lib/qrEncode.ts`, spelled out because the symbol
        // builder itself has not been ported to the handset.
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

    // ----------------------------------------------------------------------------------
    // Presentation
    // ----------------------------------------------------------------------------------

    @Test
    fun `the printed grouping is exactly what the decoder strips back off`() {
        assertEquals(
            "DPW1 :A:C MSIK 2JG8 000E H8XC 1LCY 661A :NEW D",
            formatWorkshopCodeForPrint("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")
        )
        assertEquals("", formatWorkshopCodeForPrint(""))
        assertEquals("ABCD E", formatWorkshopCodeForPrint("ABCDE"))
        // And what is printed can be typed straight back, which is the only reason to group it.
        assertEquals(
            EXPECTED_REF,
            refOf(decodeWorkshopCode(formatWorkshopCodeForPrint("DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD")))
        )
    }

    @Test
    fun `an unresolved code says the same thing about a missing record and a forbidden one`() {
        assertEquals("Artisan", workshopRecordTypeLabel(DwWorkshopRecordType.ARTISAN))
        assertEquals("Prototype", workshopRecordTypeLabel(DwWorkshopRecordType.PROTOTYPE))
        // The API answers 404 rather than 403 so that a card cannot be used to confirm a record exists.
        // A message that distinguished the two here would undo that from the other side.
        assertEquals(
            "No prototype in this workshop matches that tag. It may belong to another workshop, or the " +
                "row may not have reached this device yet — open the workshop that made it, or find the " +
                "prototype in the list.",
            unresolvedWorkshopCodeMessage(DwWorkshopRecordType.PROTOTYPE)
        )
        assertEquals(
            "No artisan you can open matches that card. It may not be in the repository, or it may " +
                "belong to work you do not have access to — search for the artisan by name instead.",
            unresolvedWorkshopCodeMessage(DwWorkshopRecordType.ARTISAN)
        )
    }

    // ----------------------------------------------------------------------------------
    // The rows a tag is printed for
    // ----------------------------------------------------------------------------------

    @Test
    fun `a row answers to both the identifiers it is known by`() {
        val synced = row("_entryId" to ARTISAN_ID, "_clientKey" to CLIENT_KEY)
        val unsynced = row("_clientKey" to CLIENT_KEY)
        assertEquals(ARTISAN_ID, workshopCodeIdForRow(synced))
        assertEquals(CLIENT_KEY, workshopCodeIdForRow(unsynced))
        assertNull(workshopCodeIdForRow(emptyMap()))
        // A JSON null is the same answer as a missing key, which is what the web's `??` does.
        assertEquals(CLIENT_KEY, workshopCodeIdForRow(mapOf("_entryId" to JsonNull, "_clientKey" to JsonPrimitive(CLIENT_KEY))))
        // …but an EMPTY `_entryId` is not. `??` falls through on null and on nothing else, so this
        // answers "" and the encoder then refuses it as NO_ID — rather than printing a tag under the
        // client key for a row whose own sync state says it has a server id.
        assertEquals("", workshopCodeIdForRow(row("_entryId" to "", "_clientKey" to CLIENT_KEY)))

        // A tag printed on Monday, before the row synced, must still resolve on Friday after it has.
        val tag = decodeWorkshopCode(codeOf(encodeWorkshopCode("prototype", CLIENT_KEY)))
        assertTrue(workshopCodeMatchesRow(refOf(tag)!!, synced))
        assertTrue(workshopCodeMatchesRow(refOf(tag)!!, unsynced))
        assertFalse(workshopCodeMatchesRow(refOf(tag)!!, row("_entryId" to "cmother0000000000000000zz")))
        assertFalse(workshopCodeMatchesRow(refOf(tag)!!, emptyMap()))
        // An artisan card never matches a prototype row, whatever its id says.
        assertFalse(workshopCodeMatchesRow(DwWorkshopCodeRef(DwWorkshopRecordType.ARTISAN, CLIENT_KEY), synced))
    }

    // ----------------------------------------------------------------------------------
    // Fixtures and readers
    // ----------------------------------------------------------------------------------

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
        /** The artisan `DwReferenceWireTest` and the web's own spec both use, so the codes line up. */
        const val ARTISAN_ID = "cmsik2jg8000eh8xc1lcy661a"

        /** What a prototype row is known by before it has ever reached the server. */
        const val CLIENT_KEY = "3f7a91c2-0b4d-4e19-9c2a-1d5e6f708a9b"

        val EXPECTED_REF = DwWorkshopCodeRef(DwWorkshopRecordType.ARTISAN, ARTISAN_ID)
    }
}
