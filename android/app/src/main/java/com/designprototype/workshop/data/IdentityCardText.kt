package com.designprototype.workshop.data

/**
 * Turning what a text recogniser saw on an identity card into numbers worth offering a designer.
 *
 * ── WHY THIS FILE HAS NO ANDROID IN IT ────────────────────────────────────────────────────────
 *
 * ML Kit's recogniser cannot run in a JVM unit test — it is a native pipeline behind a Play Services
 * `Task`, and `android.jar` on the test classpath is stubs. So the recogniser is split in two: the
 * part that TALKS TO THE MODEL ([IdentityCardRecognizer]) and the part that DECIDES WHAT IS A NUMBER,
 * which is this file. This half is pure Kotlin over plain data and `IdentityCardTextTest` holds it to
 * every rule below without a device.
 *
 * Stated plainly, because a green test suite must not be read as more than it is: NOTHING HERE
 * EXERCISES THE RECOGNISER. Whether ML Kit reads a laminated card in courtyard light, and whether it
 * groups the twelve digits into one line, are hardware claims and this repository cannot make them.
 * What is verified here is what happens to the text once it exists.
 *
 * ── ONLY AADHAAR IS EXTRACTED ON THE DEVICE, AND THAT IS A FINDING RATHER THAN AN OMISSION ────
 *
 * An Aadhaar number can be recognised from raw text because it carries its own proof: exactly twelve
 * ASCII digits, never starting 0 or 1, and a Verhoeff check digit that catches every single-digit
 * misread and every adjacent transposition. Nothing else on the card satisfies that by accident.
 *
 * A Pehchan (PM Vishwakarma artisan ID) has NO checksum and no published fixed length — this repo's
 * own rule is "4 to 32 letters and digits", which also describes the artisan's name, the state code,
 * the scheme name and half the card. There is no way to pick it out of an unlabelled bag of strings.
 * The server does not do it either: `identity_ocr.pehchan_candidates` receives values a VISION MODEL
 * has already LABELLED as the Pehchan number and only normalises them. So an on-device Pehchan rule
 * would have to be invented here, against a card no one on this side has seen, with no checksum to
 * catch it being wrong — and a wrong Pehchan number is confirmed by a designer and stored, because
 * unlike a misread Aadhaar digit there is nothing downstream that can refuse it.
 *
 * The honest answer is therefore: the phone reads Aadhaar, the server reads Pehchan, and the panel
 * says which one answered. See `docs/DECISION-identity-card-ocr-on-android.md`. This becomes writable
 * the day somebody photographs a real Pehchan card and puts the text in a test here.
 *
 * ── NO LOGGING, EVER ──────────────────────────────────────────────────────────────────────────
 *
 * Every string this file handles is either an identity number or the text printed next to one. There
 * is no log statement at any level, and the rejected candidates are returned as a COUNT rather than
 * as values, for the same reason `DwIdentityOcrDto.rejectedAadhaarCount` is a count: a misread number
 * is still somebody's identity number.
 */
object IdentityCardText {

    /**
     * How much taller than the card's ordinary text a line must be to count as "printed large".
     *
     * The Aadhaar number is set in a noticeably larger face than the address and the parentage below
     * it, so height is real evidence about which twelve digits are THE number. The threshold is a
     * judgement, not a measurement — there is no card here to measure — and it is allowed to be one
     * ONLY because of the rule in [read]: layout evidence orders candidates and never admits or
     * excludes one. A threshold that is wrong costs the designer a second glance down the list; it
     * cannot cost them a wrong number.
     */
    private const val LARGE_LINE_RATIO = 1.25

    /**
     * One printed row a recogniser found, and the box it found it in.
     *
     * The box is OPTIONAL — a caller with no geometry passes nothing and every line reads as height
     * zero, at which case the size evidence simply switches itself off and ordering falls back to the
     * "on a line of its own" signal. That is what makes this type usable from a test that cares about
     * digits rather than about pixels.
     */
    data class RecognisedLine(
        val text: String,
        val top: Int = 0,
        val bottom: Int = 0,
    ) {
        /** Zero when no box was supplied, or when a recogniser returned a degenerate one. */
        internal val height: Int get() = (bottom - top).coerceAtLeast(0)
    }

    /**
     * What the device is willing to say about a photograph.
     *
     * [aadhaar] is BEST FIRST — see [read] for what "best" means and for why it is only an ordering.
     * [rejectedAadhaarCount] carries the same meaning as the server's field of that name: how many
     * distinct twelve-digit runs were found and failed their checks. It is the difference between
     * "the card was found and misread" (move to better light) and "nothing was found" (fill the
     * frame), which are different next actions for the designer.
     */
    data class IdentityCardReading(
        val aadhaar: List<String> = emptyList(),
        val rejectedAadhaarCount: Int = 0,
    )

    /**
     * Every Aadhaar number worth offering from [lines], best first, plus the rejected count.
     *
     * ── THE FILTER, WHICH IS THE WHOLE POINT ──────────────────────────────────────────────────
     *
     * A text recogniser hands back every numeral on the card: the VID, the enrolment number, the date
     * of birth, the pin code, the issue timestamp, the helpline. A digit string is not an Aadhaar
     * number, and offering a wrong-but-plausible one is worse than offering none, because the designer
     * confirms it and it becomes a deduplication key that belongs to nobody. So a run must survive all
     * four of:
     *
     *  1. it is a MAXIMAL run of digits-and-single-separators — see [scanDigitRuns];
     *  2. that run holds exactly twelve digits;
     *  3. it does not start 0 or 1, which UIDAI never issues;
     *  4. it satisfies the Verhoeff checksum ([ArtisanIdentity.verhoeffOk]).
     *
     * Rules 2-4 are `artisan_identity.aadhaar_error`'s three rules in the server's own order, so the
     * handset cannot offer a number the API would then refuse — the failure that leaves a designer
     * with a form they can neither submit nor correct, hours from the artisan whose card it was.
     *
     * ── RULE 1 IS DELIBERATELY STRICTER THAN THE SERVER, AND HERE IS THE CARD IT SAVES ────────
     *
     * The server matches `(?<![0-9])((?:[0-9][ \-]?){11}[0-9])(?![0-9])`, which accepts a twelve-digit
     * PREFIX of a longer grouped run. Every Aadhaar card also prints a sixteen-digit VID, grouped
     * 4-4-4-4. Against "9174 9963 5947 4681" that pattern matches "9174 9963 5947" — the lookahead
     * sees the SPACE after the twelfth digit, not the four digits after that — and yields a number
     * that is printed nowhere on the card. It then has to pass Verhoeff, which one arbitrary
     * twelve-digit string in ten does. The device therefore refuses to split a run: a group of digits
     * is offered only when the WHOLE run is twelve digits long.
     *
     * The device cannot defend against this on the server's behalf, either — a fabricated number only
     * reaches the client if it already passed Verhoeff, so re-checking it here cannot catch it. That
     * is a defect in `backend/app/services/identity_ocr.py` and is reported as one.
     *
     * ── AND THE ORDERING, WHICH ONLY ORDERS ───────────────────────────────────────────────────
     *
     * When more than one candidate survives, the one printed the way the card prints its number goes
     * first: on a line of its own, in a face larger than the surrounding text. EVERY survivor is still
     * returned and the panel still shows them all — this is the order they are read in, not a choice
     * made on the designer's behalf. Ranking that could DROP a candidate would be a silent decision
     * about which of two identity numbers a person gets, and no evidence available here is strong
     * enough to make that.
     */
    fun read(lines: List<IdentityCardText.RecognisedLine>): IdentityCardReading {
        // The median of the lines that actually carry a box. Median rather than mean because a card
        // photographed with a doorway in the frame produces a handful of enormous background lines,
        // and a mean would let them declare the real card text "small".
        val heights = lines.map { it.height }.filter { it > 0 }.sorted()
        val medianHeight = if (heights.isEmpty()) 0 else heights[heights.size / 2]

        val accepted = mutableListOf<Ranked>()
        var rejected = 0
        val seen = mutableSetOf<String>()

        lines.forEach { line ->
            scanDigitRuns(line.text).forEach { run ->
                val digits = run.digits
                // Not twelve digits: never a candidate, so never a REJECTION either. A date of birth
                // and a pin code are not failed readings of an Aadhaar number, and counting them
                // would tell the designer the card was misread when it was read perfectly.
                if (digits.length != ArtisanIdentity.AADHAAR_LENGTH) return@forEach
                // The same run read twice — the number and a second copy of it — is one candidate and
                // one rejection at most. Mirrors the server's `seen` set.
                if (!seen.add(digits)) return@forEach
                if (digits[0] == '0' || digits[0] == '1' || !ArtisanIdentity.verhoeffOk(digits)) {
                    rejected += 1
                    return@forEach
                }
                accepted += Ranked(
                    value = digits,
                    // The run is the whole of the line once whitespace is discounted. On the card the
                    // number sits on a line by itself; a twelve-digit run buried in a sentence is
                    // almost certainly something else that happens to be twelve digits long.
                    alone = line.text.removeRange(run.start, run.end).none { !it.isWhitespace() },
                    large = medianHeight > 0 && line.height >= medianHeight * LARGE_LINE_RATIO,
                )
            }
        }

        // Stable: candidates the evidence cannot separate stay in the order they were read, which is
        // top-to-bottom down the card. `sortedByDescending` is documented stable in Kotlin/JVM.
        return IdentityCardReading(
            aadhaar = accepted.sortedByDescending { it.score }.map { it.value },
            rejectedAadhaarCount = rejected,
        )
    }

    /** A surviving candidate with the layout evidence that decides where it sits in the list. */
    private data class Ranked(val value: String, val alone: Boolean, val large: Boolean) {
        /**
         * "On its own line" outweighs "printed large" because it is the more specific claim: plenty
         * of things on a card are set large, but only the number is set alone.
         */
        val score: Int get() = (if (alone) 2 else 0) + (if (large) 1 else 0)
    }

    /** A maximal digit run and where it sat in the line, so the rest of the line can be inspected. */
    private data class DigitRun(val digits: String, val start: Int, val end: Int)

    /**
     * Every MAXIMAL run of ASCII digits joined by at most one space or hyphen, in [text].
     *
     * Maximal is the load-bearing word: a run ends only where the next character is neither a digit
     * nor a single separator followed by a digit, so "9174 9963 5947 4681" is ONE run of sixteen
     * digits and cannot yield a twelve-digit candidate. See [read] for the card that costs.
     *
     * ASCII `'0'..'9'` ONLY, never `Char.isDigit`, which is Unicode-aware and returns true for the
     * Devanagari "१" and the fullwidth "２". Those would be carried through as-is and stored verbatim,
     * at which point the unique index that deduplicates artisans sees "१२३४५६७८९०१२" and
     * "123456789012" as two different people — the identical note sits over `aadhaarError` and over
     * the server's `_AADHAAR_RUN`, and it is the reason the Devanagari recognition model is NOT
     * shipped: nothing it could read would survive this line.
     */
    private fun scanDigitRuns(text: String): List<DigitRun> {
        val runs = mutableListOf<DigitRun>()
        var i = 0
        while (i < text.length) {
            if (text[i] !in '0'..'9') {
                i += 1
                continue
            }
            val start = i
            val digits = StringBuilder()
            var j = i
            while (j < text.length) {
                val c = text[j]
                when {
                    c in '0'..'9' -> {
                        digits.append(c)
                        j += 1
                    }
                    // A single separator counts as part of the run only when a digit follows it. Two
                    // spaces end the run, which is what keeps "1234  5678" two numbers rather than one
                    // — the same tolerance the server's `[ \-]?` allows, no more.
                    (c == ' ' || c == '-') && j + 1 < text.length && text[j + 1] in '0'..'9' -> j += 1
                    else -> break
                }
            }
            runs += DigitRun(digits.toString(), start, j)
            i = j
        }
        return runs
    }
}
