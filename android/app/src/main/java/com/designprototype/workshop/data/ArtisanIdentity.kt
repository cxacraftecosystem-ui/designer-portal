package com.designprototype.workshop.data

/**
 * Aadhaar and Pehchan rules, in ONE place on this handset.
 *
 * ── WHY THIS FILE EXISTS AT ALL ───────────────────────────────────────────────────────────────
 *
 * `Artisan.aadhaarNumber` is the repository's DEDUPLICATION KEY. A unique index enforces it, and the
 * whole point of the column is that the same person documented at two workshops by two designers
 * resolves to one record. That makes a WRONG number strictly worse than a missing one: a missing
 * number is visibly incomplete, while twelve plausible digits belonging to nobody pass every check
 * this system has, create exactly the duplicate the column exists to prevent, and are masked to
 * "XXXX XXXX 9012" on every surface afterwards so nobody ever reads them back and notices.
 *
 * UIDAI computes a Verhoeff check digit over the first eleven digits precisely because Verhoeff
 * catches EVERY single-digit error and EVERY transposition of adjacent digits — which is the error
 * class both a misread and a mistyped number produce. So the checksum is the load-bearing rule here,
 * not a nicety on the end of the length check.
 *
 * ── WHY IT IS ITS OWN FILE RATHER THAN A PRIVATE FUNCTION ON A SCREEN ─────────────────────────
 *
 * These rules had exactly one copy on Android and it was `private` inside MainActivity — a 14,000
 * line file that no other module may import, and that no JVM unit test can reach. So the artisan
 * form validated Aadhaar numbers and NOTHING ELSE COULD: the design-workshop stage field that holds
 * the same number (`artisanCardNo`) had no checksum at all, and the card-reading panel had no way to
 * refuse a candidate the server would have refused. The second implementation was already being
 * written when this file was made instead.
 *
 * A THIRD IMPLEMENTATION IS THE FAILURE THIS PREVENTS. `backend/app/services/artisan_identity.py` is
 * the authority and this is a faithful port of it — same three checks in the same order, same
 * wording, same masking, same normalisation. Two validators that disagree about one number mean a
 * designer offline is told the number is fine and the server refuses it after a sync they cannot
 * watch, with the card long since back in the artisan's pocket.
 *
 * ── WHAT IS DELIBERATELY NOT HERE ─────────────────────────────────────────────────────────────
 *
 * No logging, at any level, on any path. An Aadhaar number in a log line is the one copy nobody
 * remembers to delete, and this file is called from the exact places that hold a full number.
 */
object ArtisanIdentity {

    const val AADHAAR_LENGTH = 12

    /** Pehchan (PM Vishwakarma artisan ID) length bounds. Mirrors `PEHCHAN_MIN/MAX_LENGTH`. */
    const val PEHCHAN_MIN_LENGTH = 4
    const val PEHCHAN_MAX_LENGTH = 32

    // Verhoeff tables. `D` is the dihedral-group multiplication table, `P` the position permutation
    // applied to each digit by its distance from the right.
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

    /**
     * True when [digits] satisfies the Verhoeff checksum (the 12th digit checks the first 11).
     *
     * ASCII-only by contract: a caller that has not filtered the string first can hand this a
     * Devanagari "१", and `char - '0'` on one of those indexes far off the end of [P] — an
     * ArrayIndexOutOfBoundsException thrown straight out of a Compose callback. [aadhaarError]
     * checks the character range BEFORE reaching here, and so must anything else that calls it;
     * the guard below makes the contract enforceable rather than merely documented.
     */
    fun verhoeffOk(digits: String): Boolean {
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return false
        var checksum = 0
        digits.reversed().forEachIndexed { index, char ->
            checksum = D[checksum][P[index % 8][char - '0']]
        }
        return checksum == 0
    }

    /**
     * "1234 5678 9012" -> "123456789012"; blank collapses to "".
     *
     * Strips only the separators a card actually prints and a person actually types. It does NOT
     * strip letters — an Aadhaar number with a letter in it must reach [aadhaarError] and be
     * refused there by name, not be silently repaired into a shorter number that then fails a
     * length check the designer cannot explain.
     */
    fun normalizeAadhaar(value: String?): String =
        (value ?: "").filterNot { it.isWhitespace() || it == '-' || it == '‐' || it == '‑' ||
            it == '‒' || it == '–' || it == '—' || it == '―' }.trim()

    /**
     * The reason [value] is not a usable Aadhaar number, or null when it is fine (blank included —
     * presence is the caller's rule, not this one's).
     *
     * Each message names the specific problem, because "invalid Aadhaar number" gives a designer
     * standing in a shed nothing to act on. Wording matches `aadhaar_error` in
     * `backend/app/services/artisan_identity.py` character for character, so the inline error seen
     * offline is the sentence the server would have sent back.
     */
    fun aadhaarError(value: String?): String? {
        val digits = (value ?: "").trim()
        if (digits.isEmpty()) return null
        // ASCII digits only, NOT Char.isDigit(): that returns true for Devanagari "१", fullwidth "２"
        // and every other decimal script. Those would be STORED verbatim, at which point the unique
        // index sees "१२३४५६७८९०१२" and "123456789012" as two different values — the same person
        // recorded twice, which is precisely what this column exists to prevent.
        if (!digits.all { it in '0'..'9' }) return "Aadhaar number must be 12 digits — remove any letters or symbols."
        if (digits.length != AADHAAR_LENGTH) return "Aadhaar number must be exactly 12 digits (this one has ${digits.length})."
        if (digits[0] == '0' || digits[0] == '1') return "Aadhaar numbers never start with 0 or 1 — please re-check the first digit."
        if (!verhoeffOk(digits)) {
            return "That Aadhaar number fails its checksum, so at least one digit is wrong. " +
                "Please re-read the card and enter it again."
        }
        return null
    }

    /** True when [value] is a well-formed Aadhaar number by all three rules. */
    fun isAadhaar(value: String?): Boolean {
        val digits = (value ?: "").trim()
        return digits.isNotEmpty() && aadhaarError(digits) == null
    }

    /**
     * "123456789012" -> "XXXX XXXX 9012", the form every surface EXCEPT an edit box uses.
     *
     * THE ONE STRING THAT MAY BE LOGGED, TOASTED, PUT IN A SNACKBAR OR PRINTED IN A REPORT. Aadhaar
     * is regulated personal data: the last four digits are enough for a designer to confirm they
     * have the right person and not enough to be a usable identifier.
     *
     * Anything shorter than four digits is masked ENTIRELY rather than partially revealed, so a
     * malformed value can never leak more than a well-formed one — the same refusal
     * `mask_aadhaar` makes on the server.
     */
    fun mask(value: String?): String? {
        val digits = normalizeAadhaar(value)
        if (digits.isEmpty()) return null
        if (digits.length < 4) return "XXXX XXXX XXXX"
        return "XXXX XXXX ${digits.takeLast(4)}"
    }

    /**
     * Digits in the 4-4-4 grouping the card prints, for a panel a person is PROOFREADING.
     *
     * Only ever for a number the designer is being asked to check against the card in their hand.
     * An unbroken twelve-digit run is the single hardest thing to proofread and proofreading it is
     * the entire purpose of that panel; a dropped digit is visible in "1234 5678 901" in a way it
     * is not in "12345678901". Anywhere else, use [mask].
     */
    fun grouped(value: String?): String {
        val digits = normalizeAadhaar(value)
        if (digits.length != AADHAAR_LENGTH || digits.any { it !in '0'..'9' }) return digits
        return digits.chunked(4).joinToString(" ")
    }

    /**
     * Strip separators and upper-case a Pehchan card number so one card has one spelling.
     *
     * There is no checksum on a PM Vishwakarma artisan ID — it is a plain government reference
     * number — so normalisation is the ONLY defence against one card being stored as two strings.
     * Mirrors `normalize_pehchan`: everything that is not a letter or a digit goes, and the rest is
     * upper-cased.
     */
    fun normalizePehchan(value: String?): String =
        (value ?: "").filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.uppercase()

    /** The reason [value] is not a usable Pehchan card number, or null when it is fine. */
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

    /** True when [value] is a well-formed Pehchan card number. */
    fun isPehchan(value: String?): Boolean {
        val cleaned = normalizePehchan(value)
        return cleaned.isNotEmpty() && pehchanError(cleaned) == null
    }
}
