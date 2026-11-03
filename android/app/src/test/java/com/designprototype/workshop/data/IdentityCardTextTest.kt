package com.designprototype.workshop.data

import com.designprototype.workshop.data.IdentityCardText.RecognisedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityCardTextTest {

    private fun read(vararg lines: String) =
        IdentityCardText.read(lines.map { RecognisedLine(it) })

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

    @Test
    fun `the sixteen-digit VID beside the number yields nothing at all`() {
        val reading = read("VID : 2345 6789 0124 5678")
        assertEquals(emptyList<String>(), reading.aadhaar)
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
        assertEquals(emptyList<String>(), read("Enrolment No.: 2078/91234/56789").aadhaar)
    }

    @Test
    fun `a number that fails its checksum is refused and counted`() {
        val reading = read("2345 6789 0123")
        assertEquals(emptyList<String>(), reading.aadhaar)
        assertEquals(1, reading.rejectedAadhaarCount)
    }

    @Test
    fun `numbers starting 0 or 1 are refused even when the checksum passes`() {
        val reading = read("123456789010", "012345678906")
        assertEquals(emptyList<String>(), reading.aadhaar)
        assertEquals(2, reading.rejectedAadhaarCount)
    }

    @Test
    fun `devanagari numerals are not digits here, and do not crash`() {
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
        assertEquals(listOf("234567890124"), read("2345 6789 0124  2013").aadhaar)
    }

    @Test
    fun `the number printed on a line of its own is offered first`() {
        val reading = IdentityCardText.read(
            listOf(
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
                RecognisedLine("345678901238", top = 100, bottom = 120),
                RecognisedLine("234567890124", top = 200, bottom = 260),
                RecognisedLine("Government of India", top = 10, bottom = 30),
                RecognisedLine("Jaipur, Rajasthan", top = 300, bottom = 320),
            )
        )
        assertEquals(listOf("234567890124", "345678901238"), reading.aadhaar)
    }

    @Test
    fun `ordering never drops a candidate`() {
        val reading = read("876543210988", "Issued 345678901238", "2345 6789 0124")
        assertEquals(3, reading.aadhaar.size)
        assertTrue(reading.aadhaar.containsAll(listOf("876543210988", "345678901238", "234567890124")))
    }

    @Test
    fun `candidates the evidence cannot separate keep the order they were read in`() {
        val reading = read("876543210988", "234567890124", "345678901238")
        assertEquals(listOf("876543210988", "234567890124", "345678901238"), reading.aadhaar)
    }

    @Test
    fun `a scan with no bounding boxes still reads`() {
        val reading = IdentityCardText.read(listOf(RecognisedLine("2345 6789 0124")))
        assertEquals(listOf("234567890124"), reading.aadhaar)
    }

    @Test
    fun `everything offered would also satisfy the validator the form applies`() {
        read("2345 6789 0124", "345678901238", "876543210988").aadhaar.forEach { value ->
            assertEquals(null, ArtisanIdentity.aadhaarError(value))
        }
    }
}
