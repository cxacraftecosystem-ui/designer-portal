package com.designprototype.workshop.data

import com.designprototype.workshop.pincodeValidationError
import com.designprototype.workshop.ui.artisanPhoneValidationError

/**
 * `FieldSpec.text_format`, ANSWERED ON THIS HANDSET WITH THE SAME RULE AND THE SAME SENTENCE THE
 * SERVER WILL USE.
 *
 * The registry now declares that a field holds an email address, an Indian phone number, an Aadhaar
 * number, a Pehchan card number or a PIN code, and `coerce_value` REFUSES a value that does not
 * match — between the `max_length` check and the `store_masked` mask. [DwValues.coerce] is this
 * app's port of that function, so the dispatch has to land in the same arm, in the same order, or
 * the phone will accept a value the next sync refuses and the designer will find out a fortnight
 * later in another district.
 *
 * ── WHY THIS FILE EXISTS: EMAIL WAS THE CONTROL EXPERIMENT AND ALL THREE SIDES FAILED IT ────────
 *
 * Before the declaration there were three answers to "is this a valid email address" and no test
 * between any two of them:
 *
 *   - this handset refused a missing `@` and a leading or trailing `@`, in [DwValues.coerce]'s own
 *     EMAIL arm, and nothing else;
 *   - the browser applied `[^\s@]+@[^\s@]+\.[^\s@]+` — on the RECORD page only, where the rule was
 *     private to `ArtisanForm` and restated a second time as a `pattern` attribute;
 *   - the server applied nothing at all, on either path.
 *
 * So this phone refused addresses the browser accepted, and the repository accepted addresses this
 * phone had refused. Nobody noticed, because a malformed address carries perfectly well: it is not
 * wrong until somebody tries to write to it, months later and in a different building.
 *
 * [EMAIL_RE] is now the one rule, matching `lib/textFormats.ts` and `contact_formats.py` character
 * for character, and [DwValues.coerce]'s local EMAIL arm has been deleted in favour of it.
 *
 * ── THE OTHER FOUR ARE REUSES, AND ONE OF THEM IMPORTS "UPWARDS" ON PURPOSE ─────────────────────
 *
 * AADHAAR and PEHCHAN are [ArtisanIdentity] in this same package. PINCODE is
 * `pincodeValidationError` in the root package. PHONE_IN is `artisanPhoneValidationError` in the
 * `ui` package — which means a file under `data` imports from `ui`, and that is the wrong direction
 * for a layered module.
 *
 * IT IS STILL THE RIGHT CALL, and the reason is measured rather than aesthetic. The alternative is a
 * second implementation of "+91 means exactly ten digits" living down here, and this file's entire
 * subject is what happens when one rule has two implementations: they diverge, nothing reports it,
 * and only the person typing pays. `artisanPhoneValidationError` also owns [parseArtisanPhone]'s
 * legacy arm — a bare run of digits is an Indian national, ten digits under +91 — which is
 * load-bearing and easy to lose in a re-write: `coerce_value` re-coerces EVERY field on EVERY save,
 * so a rule that read a bare "9876543210" as "no country, therefore invalid" would refuse every row
 * written before the dial-code picker existed, the next time anybody touched the stage it sits on.
 * One rule in the wrong package beats two rules in the right ones.
 */
internal object DwTextFormats {

    /**
     * The email shape. The browser's `EMAIL_PATTERN` anchored, and the server's `_EMAIL_RE`.
     *
     * DELIBERATELY NOT AN RFC 5322 GRAMMAR. The rule is "something, an @, something, a dot,
     * something, and no spaces" — enough to catch what a person standing in a courtyard actually
     * mistypes (a name with no domain, a pasted line with a space in it, a domain with no dot) and
     * permissive enough never to refuse a real address it does not recognise. A stricter grammar
     * would reject valid addresses on a device with no signal and no way to override it, which is
     * the worse of the two failures.
     */
    private val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    /**
     * ONE SENTENCE FOR A MALFORMED EMAIL ADDRESS, EVERYWHERE.
     *
     * The record page's wording, character for character. This arm used to answer
     * "${'$'}{field.label} is not a valid email address" — the registry label followed by a
     * restatement of the label — which is both worse prose and a SECOND sentence for one fault. A
     * designer who corrects an address on this phone and again on a laptop must not have to work out
     * whether two differently-phrased lines are the same complaint. The shape is named because it is
     * the only actionable thing anyone can say about an address nobody can look up.
     */
    private const val EMAIL_MESSAGE = "Enter a valid email address (name@example.com)."

    /** The reason [value] is not a usable email address, or null when it is fine (blank included). */
    fun emailError(value: String?): String? {
        val trimmed = (value ?: "").trim()
        if (trimmed.isEmpty()) return null
        return if (EMAIL_RE.matches(trimmed)) null else EMAIL_MESSAGE
    }

    /**
     * The two shapes [ArtisanIdentity.mask] can emit, matched against the NORMALISED value: eight
     * X's then the real last four, or twelve X's for a value too short to reveal any.
     *
     * Case-sensitive on purpose. Both mask producers — `mask_aadhaar` and [ArtisanIdentity.mask] —
     * emit upper-case X's, and folding case is exactly what lets a run of prose containing "xxxx"
     * through the door.
     */
    private val AADHAAR_MASK_RE = Regex("^X{8}(?:\\d{4}|X{4})$")

    /**
     * The reason [value] is neither a usable Aadhaar number nor a mask this application produced.
     *
     * ── WHY THE MASK MUST BE ACCEPTED, WHICH IS NOT A CONVENIENCE ──────────────────────────────
     *
     * `hydrate_entries` writes a masked identity number into `participant.aadhaarNumber` when a row
     * is hydrated from an artisan record, and both `validate_entry` and [DwValues.validate]
     * re-coerce every field on every save. A validator that knew only [ArtisanIdentity.aadhaarError]
     * would look at "XXXX XXXX 9012" and answer "Aadhaar number must be 12 digits — remove any
     * letters or symbols." `save_stage` then restores the key from `previous` — the same mask — so
     * the stored value never changes, and a red error appears on that box on EVERY SAVE, FOREVER, on
     * a row nobody touched, naming a fault the designer cannot fix because the digits are not theirs
     * to see. [ArtisanIdentity.mask] is idempotent, so an accepted mask stores byte-identical.
     *
     * ── AND WHY THE ACCEPT-ARM IS NOT "CONTAINS AN X" ──────────────────────────────────────────
     *
     * `is_masked_aadhaar` / `isMaskedIdentityNumber` answer "an X anywhere", and that rule is right
     * where it is used: `ArtisanUpdate` DROPS the key entirely before the write, so a false positive
     * costs a skipped update. [DwValues.coerce] has no drop — whatever it accepts is what gets
     * STORED and then masked. Under "an X anywhere", "XxamplE 1234" is a mask, passes, and is stored
     * as "XXXX XXXX 1234": the original defect, unchanged, now behind a validator that calls it
     * clean. That is the exact failure this whole feature exists to end, so it must not be the door.
     */
    fun aadhaarOrMaskError(value: String?): String? {
        val raw = (value ?: "").trim()
        if (raw.isEmpty()) return null
        /*
         * A VALUE THAT NORMALISES TO NOTHING IS MEASURED RAW, WHICH IS WHAT THE SERVER DOES AND WHAT
         * THIS FUNCTION USED TO GET WRONG.
         *
         * The server's dispatch entry is `_aadhaar_format_error`, and its first line is
         * `if normalize_aadhaar(text) is None: return aadhaar_error(text)` — the RAW text. This file
         * ported `aadhaar_or_mask_error`, which is the SECOND half, so a box holding "---"
         * normalised to "" and was answered null: no error on the screen, the sync refusing that one
         * field, `save_stage` restoring it from `previous`, and the value back as it was on the next
         * read with nothing said. A silent revert, which is the exact failure this feature exists to
         * end, rebuilt inside the preview.
         *
         * `ifEmpty { raw }` is the server's two branches as one expression, and it can only ever
         * REFUSE more: a value that normalises to nothing cannot be a mask and cannot be twelve
         * digits.
         */
        val measured = ArtisanIdentity.normalizeAadhaar(raw).ifEmpty { raw }
        if (AADHAAR_MASK_RE.matches(measured)) return null
        return ArtisanIdentity.aadhaarError(measured)
    }

    /**
     * The reason the value in a field with a declared [FieldDto.format] will be refused, or null.
     *
     * ── AN UNRECOGNISED FORMAT ENFORCES NOTHING, AND THAT DIRECTION IS CHOSEN ──────────────────
     *
     * The server refuses at install any `TextFormat` member its own dispatch has no entry for — a
     * published flag nothing enforces is the silent failure this feature exists to end. This client
     * cannot make that refusal: the schema arrives from a server that may be a release ahead, and it
     * arrives ONCE and is then read from disk for as long as the handset is out of signal. The two
     * ways of being wrong are not symmetrical:
     *
     *   - enforce nothing: the value goes up, the SERVER refuses that one field, and the refusal
     *     comes back as a `DwStageRefusal` against this exact box. One sync lost, nothing wrong
     *     stored.
     *   - guess a rule: the box refuses an answer the server would have taken, on a stage a designer
     *     cannot get past, on a device with no signal and nobody to ask.
     *
     * So an unknown token returns null. It is the same forgiveness [DwFieldType.of] applies to an
     * unknown TYPE, and for the same reason — with the same caveat, that forgiveness is only safe
     * where the server is still the authority, which for a format it is.
     */
    fun error(format: String, value: String): String? = when (format) {
        "" -> null
        "EMAIL" -> emailError(value)
        "PHONE_IN" -> artisanPhoneValidationError(value)
        "AADHAAR" -> aadhaarOrMaskError(value)
        "PEHCHAN" -> pehchanError(value)
        "PINCODE" -> pincodeError(value)
        else -> null
    }

    /**
     * The reason [value] is not a usable PIN code, measured AFTER separators come off.
     *
     * ── WHY `pincodeValidationError` IS NOT CALLED DIRECTLY ────────────────────────────────────
     *
     * That function refuses anything which is not six bare digits, and it is right where it lives:
     * the record page's PIN code box strips non-digits as they are typed, so a space cannot get into
     * it. A STAGE box is not that box. `participant.pincode` is a hydration target with
     * `max_length = 10`, and its own comment in `stage_definitions.py` names "768 029" — typed
     * exactly that way by somebody reading an address aloud — as a value already in the column.
     *
     * So the server's `_pincode_format_error` normalises before it checks, and leaves the STORED
     * string as typed. A preview on this handset that skipped the normalisation would be STRICTER
     * THAN THE ENFORCEMENT — a red line under a value the repository accepts, on a stage a designer
     * is trying to submit from a village, with nothing they can do about it. That is a fourth answer,
     * which is the exact class of divergence this file exists to end. Measured against the server's
     * own dispatch, not assumed.
     */
    private fun pincodeError(value: String): String? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        // `ifEmpty { raw }` for the reason spelled out in [aadhaarOrMaskError]: the server's
        // `_pincode_format_error` measures the RAW string when normalisation leaves nothing, so a box
        // holding only dashes is refused there and used to be accepted here.
        return pincodeValidationError(SEPARATORS.replace(raw, "").ifEmpty { raw })
    }

    /**
     * The reason [value] is not a usable Pehchan card number, measured after normalisation.
     *
     * NORMALISED FIRST, matching `_pehchan_format_error`, which is what makes the "letters and digits
     * only" arm of [ArtisanIdentity.pehchanError] unreachable from here — kept anyway, because it is
     * reachable from the record form that hands it a raw value.
     *
     * AND IT IS EXACTLY WHY NO FIELD DECLARES THIS FORMAT. "XXXX XXXX 3456" normalises to twelve
     * alphanumerics, sits inside the 4-32 window, and there is no checksum to fail — so this ACCEPTS
     * a mask, and `PEHCHAN` on `participant.artisanCardNo` would be a no-op that looks like
     * protection. Attaching it needs its own mask-aware predicate and an owner call about
     * `IdentityCardCapture kind="PEHCHAN"`, whose whole purpose is to write full numbers into that
     * box.
     */
    private fun pehchanError(value: String): String? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        // `ifEmpty { raw }` for the reason spelled out in [aadhaarOrMaskError], and it is what makes
        // the "letters and digits only" arm reachable here in the same case the server reaches it.
        //
        // ONE RESIDUAL DIFFERENCE, NAMED BECAUSE NOTHING PINS IT: `pehchan_error` on the server tests
        // `str.isalnum()`, which is true of Devanagari and every other script, while
        // [ArtisanIdentity.pehchanError] is ASCII. So a value of nothing but non-ASCII letters gets
        // "letters and digits only" on both clients and the length sentence on the server. Left
        // rather than papered over because NO FIELD DECLARES THIS FORMAT (see the note below), so
        // there is nothing to pin it against and nobody standing in front of it; the day
        // `participant.artisanCardNo` declares PEHCHAN this is the line to fix first, and a row in
        // the shared vector table is how to prove it.
        return ArtisanIdentity.pehchanError(ArtisanIdentity.normalizePehchan(raw).ifEmpty { raw })
    }

    /**
     * The server's `_SEPARATORS`: whitespace, the ASCII hyphen, and the six dashes U+2010 to U+2015.
     *
     * Spelled with escapes rather than the characters themselves — the Python copy is a range whose
     * two ends are visually indistinguishable dashes followed by a third one meant literally, which
     * is three characters nobody can tell apart in a diff, in a character class where getting the
     * order wrong silently widens the set.
     */
    private val SEPARATORS = Regex("[\\s\\u2010-\\u2015-]+")
}
