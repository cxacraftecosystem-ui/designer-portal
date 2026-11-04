package com.designprototype.workshop.data

object ArtisanIdentity {

    const val AADHAAR_LENGTH = 12

    const val PEHCHAN_MIN_LENGTH = 4
    const val PEHCHAN_MAX_LENGTH = 32

    private val D = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
        intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
        intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
        intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
        intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
    )

    private val P = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
        intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
        intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
        intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8)
    )

    fun verhoeffOk(digits: String): Boolean {
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return false
        var checksum = 0
        digits.reversed().forEachIndexed { index, char ->
            checksum = D[checksum][P[index % 8][char - '0']]
        }
        return checksum == 0
    }

    fun normalizeAadhaar(value: String?): String =
        (value ?: "").filterNot { it.isWhitespace() || it == '-' || it == '‐' || it == '‑' ||
            it == '‒' || it == '–' || it == '—' || it == '―' }.trim()

    fun aadhaarError(value: String?): String? {
        val digits = (value ?: "").trim()
        if (digits.isEmpty()) return null
        if (!digits.all { it in '0'..'9' }) return "Aadhaar number must be 12 digits — remove any letters or symbols."
        if (digits.length != AADHAAR_LENGTH) return "Aadhaar number must be exactly 12 digits (this one has ${digits.length})."
        if (digits[0] == '0' || digits[0] == '1') return "Aadhaar numbers never start with 0 or 1 — please re-check the first digit."
        if (!verhoeffOk(digits)) {
            return "That Aadhaar number fails its checksum, so at least one digit is wrong. " +
                "Please re-read the card and enter it again."
        }
        return null
    }

    fun isAadhaar(value: String?): Boolean {
        val digits = (value ?: "").trim()
        return digits.isNotEmpty() && aadhaarError(digits) == null
    }

    fun mask(value: String?): String? {
        val digits = normalizeAadhaar(value)
        if (digits.isEmpty()) return null
        if (digits.length < 4) return "XXXX XXXX XXXX"
        return "XXXX XXXX ${digits.takeLast(4)}"
    }

    fun grouped(value: String?): String {
        val digits = normalizeAadhaar(value)
        if (digits.length != AADHAAR_LENGTH || digits.any { it !in '0'..'9' }) return digits
        return digits.chunked(4).joinToString(" ")
    }

    fun normalizePehchan(value: String?): String =
        (value ?: "").filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.uppercase()

    fun pehchanError(value: String?): String? {
        val cleaned = (value ?: "").trim()
        if (cleaned.isEmpty()) return null
        if (!cleaned.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) {
            return "Pehchan card number must be letters and digits only."
        }
        if (cleaned.length !in PEHCHAN_MIN_LENGTH..PEHCHAN_MAX_LENGTH) {
            return "Pehchan card number must be between $PEHCHAN_MIN_LENGTH and $PEHCHAN_MAX_LENGTH characters."
        }
        return null
    }

    fun isPehchan(value: String?): Boolean {
        val cleaned = normalizePehchan(value)
        return cleaned.isNotEmpty() && pehchanError(cleaned) == null
    }
}
