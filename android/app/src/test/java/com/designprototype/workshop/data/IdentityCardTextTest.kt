package com.designprototype.workshop.data

import com.designprototype.workshop.data.IdentityCardText.RecognisedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the handset is willing to call an Aadhaar number, given text a recogniser produced.
 *
 * ── WHAT THIS SUITE DOES AND DOES NOT PROVE ───────────────────────────────────────────────────
 *
 * It proves the FILTER. Feed in the lines a card produces and assert exactly which numbers come out.
 * Every rule in [IdentityCardText] has a case here, and the interesting ones are the refusals —
 * a text recogniser hands back every numeral printed on a card, and offering a wrong-but-plausible
 * twelve-digit string is worse than offering none, because the designer confirms it and it becomes a
 * deduplication key belonging to nobody.
 *
 * IT PROVES NOTHING ABOUT ML KIT. The recogniser itself cannot run in a JVM test — it is a native
 * pipeline behind a Play Services `Task` — and there is no device or emulator on the machine this was
 * written on. Whether ML Kit reads a laminated card in courtyard light, and whether it groups the
 * twelve digits onto one line, are HARDWARE CLAIMS that this repository has not made. The lines below
 * are what a card is expected to produce, written by hand; they are not a recording of a real read.
 * That gap is stated in `docs/DECISION-identity-card-ocr-on-android.md` as the thing still owed.
 *
 * The numbers are Verhoeff-valid twelve-digit strings computed for this test, not real Aadhaar
 * numbers. `234567890124` is the one the backend's own fixtures use.
 */
class IdentityCardTextTest {

    private fun read(vararg lines: String) =
        IdentityCardText.read(lines.map { RecognisedLine(it) })

    // ── The ordinary card ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the number as the card prints it, grouped in fours`() {
        val reading = read("Government of India", "2345 6789 0124", "MALE / पुरुष")
        assertEquals(listOf("234567890124"), reading.aadhaar)
        assertEquals(0, reading.rejectedAadhaarCount)
    }

    @Test
    fun `an unbroken run of twelve digits`() {
        assertEquals(listOf("234567890124"), read("234567890124").aadhaar)
    }

    @Test
    fun `hyphens between the groups, which is how some prints and most typing look`() {
        assertEquals(listOf("234567890124"), read("2345-6789-0124").aadhaar)
    }

    // ── The refusals, which are the point ────────────────────────────────────────────────────────

    @Test
    fun `the sixteen-digit VID beside the number yields nothing at all`() {
        // THE CASE THIS RULE EXISTS FOR. Every Aadhaar card prints a 16-digit VID grouped 4-4-4-4.
        // The server's regex — `(?<![0-9])((?:[0-9][ \-]?){11}[0-9])(?![0-9])` — matches the first
        // twelve digits of it, because the lookahead sees the SPACE after the twelfth digit rather
        // than the four digits after that. The prefix chosen here is deliberately Verhoeff-VALID, so
        // it would survive every remaining check and be offered as a number printed nowhere on the
        // card. The device refuses to split a run, so it offers nothing.
        val reading = read("VID : 2345 6789 0124 5678")
        assertEquals(emptyList<String>(), reading.aadhaar)
        // Not a REJECTION either: nothing twelve digits long was ever found, so telling the designer
        // "a number was read and failed its checksum" would be false and would send them back for a
        // better photograph of a card that was read perfectly.
        assertEquals(0, reading.rejectedAadhaarCount)
    }

    @Test
    fun `a thirteen-digit run is not a twelve-digit number with a digit after it`() {
        assertEquals(emptyList<String>(), read("2345678901245").aadhaar)
    }

    @Test
    fun `a date of birth is not an aadhaar number`() {
        val reading = read("DOB: 01/01/1985")
        assertEquals(emptyList<String>(), reading.aadhaar)
        assertEquals(0, reading.rejectedAadhaarCount)
    }

    @Test
    fun `a pin code and a mobile number are not aadhaar numbers`() {
        assertEquals(emptyList<String>(), read("Jaipur, Rajasthan - 302001", "9876543210").aadhaar)
    }

    @Test
    fun `an enrolment number is not an aadhaar number`() {
        // Printed 4/5/5 with slashes, which end a run — so no twelve-digit run exists in it at all.
        assertEquals(emptyList<String>(), read("Enrolment No.: 2078/91234/56789").aadhaar)
    }

    @Test
    fun `a number that fails its checksum is refused and counted`() {
        // One digit changed from the valid number: twelve digits, starts 2, belongs to nobody. This
        // is exactly the shape of an OCR misread, and it is the error class Verhoeff exists to catch.
        val reading = read("2345 6789 0123")
        assertEquals(emptyList<String>(), reading.aadhaar)
        // Counted, because "read and misread" tells the designer to move to better light, while
        // "nothing found" tells them to fill the frame. Two different next actions.
        assertEquals(1, reading.rejectedAadhaarCount)
    }

    @Test
    fun `numbers starting 0 or 1 are refused even when the checksum passes`() {
        // UIDAI never issues these. Both strings below satisfy Verhoeff, so the checksum alone would
        // let them through — this is the rule that does not.
        val reading = read("123456789010", "012345678906")
        assertEquals(emptyList<String>(), reading.aadhaar)
        assertEquals(2, reading.rejectedAadhaarCount)
    }

    @Test
    fun `devanagari numerals are not digits here, and do not crash`() {
        // `Char.isDigit` returns true for "१" and for the fullwidth "２". If either reached storage,
        // the unique index that deduplicates artisans would see "१२३४५६७८९०१२" and "123456789012" as
        // two different people — the exact duplicate the column exists to prevent. This is also the
        // measured reason the 2,015,832-byte Devanagari recognition model is not shipped: nothing it
        // could read would survive this line.
        val reading = read("१२३४ ५६७८ ९०१२", "２３４５６７８９０１２４")
        assertEquals(emptyList<String>(), reading.aadhaar)
        assertEquals(0, reading.rejectedAadhaarCount)
    }

    @Test
    fun `the same number read twice is one candidate and one rejection at most`() {
        val accepted = read("2345 6789 0124", "234567890124")
        assertEquals(listOf("234567890124"), accepted.aadhaar)

        val refused = read("2345 6789 0123", "234567890123")
        assertEquals(1, refused.rejectedAadhaarCount)
    }

    @Test
    fun `a blank scan is an ordinary answer rather than an error`() {
        val reading = IdentityCardText.read(emptyList())
        assertEquals(emptyList<String>(), reading.aadhaar)
        assertEquals(0, reading.rejectedAadhaarCount)
    }

    @Test
    fun `two spaces end a run, so a year printed after the number does not swallow it`() {
        // "1234  5678" is two numbers and not one, in this scanner and in the server's `[ \-]?`
        // alike. Without that the number would be glued to whatever the recogniser put after it on
        // the same line and refused as a sixteen-digit run.
        assertEquals(listOf("234567890124"), read("2345 6789 0124  2013").aadhaar)
    }

    // ── Ordering: layout evidence orders, and never admits or excludes ───────────────────────────

    @Test
    fun `the number printed on a line of its own is offered first`() {
        val reading = IdentityCardText.read(
            listOf(
                // Twelve valid digits buried in a line of other text. Still offered — it might be the
                // number and this file does not get to decide otherwise — but offered second.
                RecognisedLine("Issued 345678901238 at Jaipur"),
                RecognisedLine("2345 6789 0124"),
            )
        )
        assertEquals(listOf("234567890124", "345678901238"), reading.aadhaar)
    }

    @Test
    fun `a larger line wins between two candidates that are both alone`() {
        val reading = IdentityCardText.read(
            listOf(
                RecognisedLine("345678901238", top = 100, bottom = 120),  // 20 tall
                RecognisedLine("234567890124", top = 200, bottom = 260),  // 60 tall
                // Two more ordinary lines so the median height is the small one.
                RecognisedLine("Government of India", top = 10, bottom = 30),
                RecognisedLine("Jaipur, Rajasthan", top = 300, bottom = 320),
            )
        )
        assertEquals(listOf("234567890124", "345678901238"), reading.aadhaar)
    }

    @Test
    fun `ordering never drops a candidate`() {
        // The rule that makes an unverified layout threshold safe to ship: evidence decides the ORDER
        // and the panel still shows everything. A wrong guess costs a second glance down the list, and
        // cannot cost a designer a number they never saw.
        val reading = read("876543210988", "Issued 345678901238", "2345 6789 0124")
        assertEquals(3, reading.aadhaar.size)
        assertTrue(reading.aadhaar.containsAll(listOf("876543210988", "345678901238", "234567890124")))
    }

    @Test
    fun `candidates the evidence cannot separate keep the order they were read in`() {
        // Top to bottom down the card, which is the order a person scanning the photograph sees them.
        val reading = read("876543210988", "234567890124", "345678901238")
        assertEquals(listOf("876543210988", "234567890124", "345678901238"), reading.aadhaar)
    }

    @Test
    fun `a scan with no bounding boxes still reads`() {
        // Every line reads as height zero, the size evidence switches itself off, and ordering falls
        // back to "on a line of its own". A recogniser that returns no geometry must not return no
        // numbers.
        val reading = IdentityCardText.read(listOf(RecognisedLine("2345 6789 0124")))
        assertEquals(listOf("234567890124"), reading.aadhaar)
    }

    // ── The rule the two readers share ──────────────────────────────────────────────────────────

    @Test
    fun `everything offered would also satisfy the validator the form applies`() {
        // The handset must never offer a number the artisan form would then refuse — that is a form a
        // designer can neither submit nor correct, hours from the artisan whose card it was.
        read("2345 6789 0124", "345678901238", "876543210988").aadhaar.forEach { value ->
            assertEquals(null, ArtisanIdentity.aadhaarError(value))
        }
    }
}
