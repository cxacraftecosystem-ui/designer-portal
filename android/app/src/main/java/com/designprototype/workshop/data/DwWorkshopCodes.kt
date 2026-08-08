package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The code printed on an artisan card and on a prototype tag, on the handset — the Kotlin port of
 * `frontend/lib/workshopCodes.ts`, and the reasoning behind every character of it.
 *
 * THE PROBLEM. The worst thing that happens in a workshop is not a lost file, it is a mis-typed
 * identifier: two days of prototype iteration attached to the wrong prototype, or a day of
 * measurements attached to the wrong artisan. Nobody finds out, because both records look complete.
 * Prototype rows and artisan records already carry identifiers; a scan removes the typing, and
 * removing the typing removes the class of error.
 *
 * ── THE PAYLOAD ──────────────────────────────────────────────────────────────────────────────
 *
 *     DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD
 *     ─┬── ┬ ─────────────┬─────────── ─┬──
 *      │   │              │             └─ check: four characters over everything to its left
 *      │   │              └─ the record's id, upper-cased
 *      │   └─ record type: A artisan, P prototype
 *      └─ namespace "DPW" + payload version 1
 *
 * **It is not a URL, and that is the first decision.** A URL is what every QR generator produces by
 * default and it is wrong here twice over. A card printed at a workshop in 2026 has to scan in
 * 2031, and a hostname is the part of a system most likely to have moved, been renamed or been let
 * go by then — an artisan card that resolves to a parked domain is worse than one that resolves to
 * nothing, because a stranger's phone opens it. And a URL invites exactly the stranger's phone: a
 * QR photographed on a table in a courtyard should do NOTHING in a general scanner app, and an
 * opaque token does nothing. Only this application knows what `DPW1:` means.
 *
 * **It carries an opaque id and nothing else, and that is the second decision.** No name, no
 * village, no craft, no phone number and — stated explicitly because it is the field that would do
 * the most harm — never an artisan's Aadhaar number or a Pehchan card number. Aadhaar is this
 * repository's deduplication key, it is regulated personal data, and `artisan_identity.py` is
 * deliberately the only place it is ever formatted for output. A card is a physical object that
 * gets photographed, left on a table and carried home; whatever is in the QR is public the moment
 * it is printed. [encodeWorkshopCode] therefore REFUSES, at runtime, to encode anything that is not
 * shaped like an identifier this repository issues — see [ID_PATTERN] and the digit rule beneath
 * it. That refusal is not decoration: the function is one string parameter away from being handed
 * the wrong field by a future caller, and a type signature does not survive a refactor the way a
 * returned refusal does. It is also why the entry point below takes the record type as a STRING
 * rather than only as [DwWorkshopRecordType] — the web's does, an unknown type has its own honest
 * answer, and a caller reading a type out of the stage registry has a string in its hand.
 *
 * **What it does leak, said out loud.** A cuid encodes its creation time in its leading characters,
 * so a stranger who understood the format could tell when the record was made. Not who, not where,
 * not what. That is the whole of it, and it is the price of an id short enough to print.
 *
 * ── THE CHECK ────────────────────────────────────────────────────────────────────────────────
 *
 * Four characters of FNV-1a over everything before them. **It is a typo detector and nothing more.**
 * It is not a signature and must never be described as one: the algorithm is in this file, so
 * anyone can compute a valid check for any id. What it is for is the manual-entry path — when the
 * camera cannot be used, a designer types the code printed under the QR, and a single wrong
 * character in a 25-character cuid produces a *different valid-looking id* that collides with
 * nobody and silently resolves to nothing (or, one day, to somebody else's record). The check turns
 * that into an honest refusal. Over the QR path it is nearly redundant — Reed-Solomon already
 * guarantees the bytes — and it is kept anyway so that the printed text and the symbol are one
 * grammar with one validator rather than two.
 *
 * The check alphabet is **Crockford base32** (no I, L, O or U), and [decodeWorkshopCode] applies
 * Crockford's substitutions to it on the way in. The id is deliberately NOT normalised that way:
 * `0` and `o` are both legal base36 characters in a cuid, so "correcting" one would corrupt an id
 * that was typed correctly. A confusable typed into the id is caught by the check and refused —
 * which is the honest answer, because there is no way to know which of the two the card said.
 *
 * ── WHY UPPER CASE AND COLONS ────────────────────────────────────────────────────────────────
 *
 * QR alphanumeric mode carries 45 characters — digits, capitals, space and `$%*+-./:` — at 5.5 bits
 * each. Byte mode would carry lower case at 8 bits and produce a symbol a third larger for the same
 * id, on a card printed at 22mm. The ids this repository issues (cuid, and the UUID client keys a
 * row created offline carries until it syncs) are lower-case, so upper-casing is loss-free and
 * exactly reversible. An id containing a capital would NOT be, so one is refused rather than
 * silently mangled. `:` is the separator because no id this repository issues can contain one,
 * which makes the parse unambiguous; `-` could not be used for that, because UUIDs are full of it.
 *
 * ── VERSIONS ─────────────────────────────────────────────────────────────────────────────────
 *
 * [SUPPORTED_VERSIONS] is only ever ADDED to. A card printed today must still scan in five years,
 * so version 1 stays readable forever — and a *newer* card met by an older app must say so ("update
 * the app") rather than fail as though the card were damaged, because those two lead a designer to
 * completely different next actions. That matters more here than on the web: a handset in a village
 * is the client least likely to be up to date, so it is the client that will meet a newer card.
 *
 * ── WHERE KOTLIN WOULD SILENTLY DISAGREE, AND WHAT WAS DONE ABOUT IT ─────────────────────────
 *
 * A card printed by the web MUST read on the handset and the reverse, so this is a port and never a
 * second opinion: when this file and `workshopCodes.ts` disagree, the TypeScript is right and this
 * is broken. `DwWorkshopCodesTest` pins the two together by value rather than asserting it. Three
 * places make disagreement easy on the JVM specifically, and each is spelled out where it happens:
 *
 *  - **Whitespace.** `String.trim()` and `Regex("\\s")` are neither of them JavaScript's rule.
 *    `Char.isWhitespace` is FALSE for U+00A0 and U+FEFF, and Java's `\s` is ASCII-only; JavaScript
 *    strips all of them. See [isJsSpace] — an Aadhaar number typed with no-break spaces between its
 *    groups has to be caught by the anti-PII gate, not merely fall out of the identifier grammar
 *    with a different sentence attached.
 *  - **The 32-bit hash.** JavaScript's `*` is double arithmetic, so the web spells the FNV prime as
 *    shifts. See [workshopCodeCheck].
 *  - **The version number.** `${version}` in the web is `Number::toString`, and the version is
 *    whatever digits a card carries. See [decodeWorkshopCode].
 *
 * PURE — no Compose, no network, no clock, no randomness — in the sense of [DwMarketAnalysis], and
 * for the same reason: everything here has to run on a handset that has had no signal for three
 * days, and be testable without one.
 */

/** The three characters that say a code belongs to this application at all. */
const val WORKSHOP_CODE_NAMESPACE = "DPW"

/** The payload version this build WRITES. It reads every version in [SUPPORTED_VERSIONS]. */
const val WORKSHOP_CODE_VERSION = 1

/**
 * Every payload version this build can read. Entries are only ever added — removing one strands
 * every card already printed against it, and cards live in a workshop for years.
 */
val SUPPORTED_VERSIONS: Set<Int> = setOf(1)

/**
 * The kinds of record a code can point at, and the single letter each occupies in the payload.
 *
 * NEVER REUSE A RETIRED LETTER. A letter is stamped on cards that outlive the build that printed
 * them, so handing `A` to something that is not an artisan would make every card already in a
 * workshop resolve, confidently, to the wrong kind of record.
 *
 * [wire] is the spelling the web uses for the same value, so a record type that arrives as a string
 * — out of the stage registry, or off a saved intent — reaches [encodeWorkshopCode] unchanged.
 */
enum class DwWorkshopRecordType(val wire: String, val letter: String) {
    ARTISAN("artisan", "A"),
    PROTOTYPE("prototype", "P");

    companion object {
        /**
         * Exact match, deliberately: the web's lookup is a plain object index, so "Artisan" is not
         * "artisan" there and must not become one here. A case-insensitive port would accept a
         * spelling the web refuses and print a card the web could not have printed.
         */
        fun ofWire(raw: String): DwWorkshopRecordType? = entries.firstOrNull { it.wire == raw }

        fun ofLetter(raw: String): DwWorkshopRecordType? = entries.firstOrNull { it.letter == raw }
    }
}

/** What a code resolves to, once it has been read. */
data class DwWorkshopCodeRef(
    val recordType: DwWorkshopRecordType,
    /** The id as the repository stores it — lower case, never the upper-cased printed form. */
    val id: String,
)

/**
 * The shape of an identifier this repository issues: a cuid (`c` + 24 base36 characters) or the
 * UUID / `k-…` client key a row carries before it has ever reached the server.
 *
 * Lower case only, and that is load-bearing rather than fussy — see the file header. Hyphens are
 * allowed for UUIDs; `_` is not, because QR alphanumeric mode cannot carry it. The 8-character
 * floor keeps short human-typed strings ("prototype 3", "ram") out; the 64-character ceiling keeps
 * a runaway value from producing a symbol too dense to scan from a printed card.
 *
 * Matched with `Regex.matches`, which requires the WHOLE input. Java's `$` alone would also accept
 * a trailing newline, which JavaScript's does not, and a code carrying one would then encode into a
 * payload no scanner could read back.
 */
private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{7,63}$")

/** The check alphabet: Crockford base32, which has no I, L, O or U to confuse on a printed card. */
private const val CHECK_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
private const val CHECK_LENGTH = 4

/**
 * Crockford's reading rules, applied to the CHECK ONLY. `I` and `L` read as `1`, `O` as `0`. `U` is
 * absent from the alphabet on purpose (it is the character people most often produce when they mean
 * `V`), so it is left to fail rather than guessed at.
 */
private val CHECK_CONFUSABLES: Map<Char, Char> = mapOf('I' to '1', 'L' to '1', 'O' to '0')

// --------------------------------------------------------------------------------------
// Whitespace, by JavaScript's rule rather than Kotlin's
// --------------------------------------------------------------------------------------

/**
 * Is [c] whitespace to `String.prototype.trim` and to a JavaScript `\s`?
 *
 * NEITHER OF THE OBVIOUS KOTLIN SPELLINGS IS THIS SET, and both failures land on the anti-PII gate,
 * which is the one place in this file where being merely nearly right is not good enough.
 * `Char.isWhitespace()` is FALSE for U+00A0 NO-BREAK SPACE and U+FEFF; Java's `\s` is ASCII-only and
 * so is false for both plus every typographic space. An Aadhaar number pasted out of a spreadsheet
 * or a web form arrives grouped with those — "2345", U+00A0, "6789", U+00A0, "0123" — often enough
 * that this is not theoretical, and under either Kotlin spelling the digit gate below would then not
 * see twelve digits: the number would fall through to "that is not an identifier", which refuses the
 * card — correctly — while telling the caller the wrong thing about what it just handed over. The
 * whole reason the sensitive shapes are tested BEFORE the generic grammar is to say exactly that.
 *
 * Written as code points rather than character literals so the set is readable and so U+2028 and
 * U+2029 — which a Kotlin source file treats as line terminators — can be named here at all.
 *
 * `ui/designworkshop/RichTextOps.kt` holds the same predicate for the same reason; its copy is
 * private to the editor and the data layer does not import the UI layer, which is the only reason
 * there are two.
 */
private fun isJsSpace(c: Char): Boolean = when (c.code) {
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20 -> true   // tab, LF, VT, FF, CR, space
    0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
    // U+2000..U+200A are the typographic spaces, EN QUAD through HAIR SPACE, which JavaScript covers
    // as one range and which arrive in anything pasted out of a page-layout program.
    else -> c.code in 0x2000..0x200A
}

/** `String.prototype.trim`, character for character. See [isJsSpace] for why not `trim()`. */
private fun jsTrim(text: String): String = text.trim { isJsSpace(it) }

// --------------------------------------------------------------------------------------
// The check
// --------------------------------------------------------------------------------------

/**
 * The FNV prime, 16777619.
 *
 * The web spells this as five shifts and an add — `(hash << 1) + (hash << 4) + …` — because
 * JavaScript's `*` produces a double and past 2^53 the low bits, the only ones that matter here,
 * stop being exact. A Kotlin `Int` multiply IS arithmetic modulo 2^32 by definition, so the plain
 * product is bit-for-bit the number the web computes, and spelling it as shifts here would only
 * hide that. The golden checks in `DwWorkshopCodesTest` are what actually holds the two together.
 */
private const val FNV_PRIME = 16777619

/**
 * The four check characters for a payload prefix — everything up to but not including the final
 * separator.
 *
 * FNV-1a over the ASCII bytes, of which the low 20 bits become four base32 characters. FNV-1a is
 * chosen for being four lines long and identical in every language this is ported to (this port has
 * to agree character-for-character or a card printed on the web will not read on the handset). It
 * detects every single-character substitution and, with probability about 1 in a million, misses
 * nothing else that matters at this length.
 *
 * `Char.code` is the UTF-16 code UNIT, which is exactly what `charCodeAt` returns — so a prefix
 * carrying an astral character hashes identically on both clients rather than one of them folding it
 * to a code point. Nothing this file emits is outside ASCII, but the check is also computed over
 * whatever a card was typed as, and a hash that disagreed there would refuse a good card.
 */
fun workshopCodeCheck(prefix: String): String {
    // The FNV-1a offset basis, 0x811c9dc5. It does not fit a Kotlin Int literal (it is above
    // Int.MAX_VALUE), and `.toInt()` truncates to exactly the 32 bits JavaScript starts from.
    var hash = 0x811c9dc5.toInt()
    for (index in prefix.indices) {
        hash = hash xor prefix[index].code
        hash *= FNV_PRIME
    }
    val value = hash and 0xfffff
    val out = StringBuilder(CHECK_LENGTH)
    for (shift in (CHECK_LENGTH - 1) * 5 downTo 0 step 5) {
        out.append(CHECK_ALPHABET[(value ushr shift) and 31])
    }
    return out.toString()
}

// --------------------------------------------------------------------------------------
// Encoding
// --------------------------------------------------------------------------------------

enum class DwEncodeRefusal {
    /** No id at all — a row that has never been saved has nothing to point at yet. */
    NO_ID,

    /** A record type this build does not print codes for. */
    UNKNOWN_RECORD_TYPE,

    /** Not shaped like an identifier this repository issues. */
    ID_NOT_AN_IDENTIFIER,

    /** Shaped like regulated identity data. Refused loudly — see the file header. */
    ID_LOOKS_SENSITIVE,
}

sealed interface DwEncodeResult {
    data class Ok(val code: String, val ref: DwWorkshopCodeRef) : DwEncodeResult

    data class Refused(val reason: DwEncodeRefusal, val message: String) : DwEncodeResult
}

/**
 * A result rather than a thrown exception, because the caller is usually a print sheet building
 * thirty cards at once: one prototype whose row has never synced must produce one refusal on one
 * tag, not an exception that empties the page a designer is about to print.
 *
 * [recordType] is a String and not [DwWorkshopRecordType] on purpose — see the file header. The
 * typed overload below is for callers that already hold one.
 */
fun encodeWorkshopCode(recordType: String, id: String?): DwEncodeResult {
    val type = DwWorkshopRecordType.ofWire(recordType)
        ?: return DwEncodeResult.Refused(
            DwEncodeRefusal.UNKNOWN_RECORD_TYPE,
            "No code can be printed for a “$recordType” — codes exist for artisans and prototypes."
        )

    val trimmed = jsTrim(id ?: "")
    if (trimmed.isEmpty()) {
        return DwEncodeResult.Refused(
            DwEncodeRefusal.NO_ID,
            "This record has no identifier yet, so there is nothing to print on a code. Save it first."
        )
    }

    // THE ANTI-PII GATE, and the order matters: the sensitive shapes are named BEFORE the generic
    // "that is not an identifier" refusal, so a caller that has handed over an Aadhaar number is told
    // exactly what it did rather than being left to conclude the id was merely malformed.
    // Separators removed, because that is the form the identity fields are STORED in:
    // `normalize_aadhaar` strips the spacing people type ("1234 5678 9012") down to twelve digits and
    // `normalize_pehchan` strips separators and upper-cases. Testing the raw string alone would let
    // the spaced form of an Aadhaar number through the gate below.
    val stripped = trimmed.filterNot { isJsSpace(it) || it == '-' }
    // ASCII digits only, matching a JavaScript `\d`, which stays ASCII even under the `u` flag —
    // NOT `Char.isDigit()`, which is every Unicode decimal digit. A Devanagari numeral would then be
    // called an Aadhaar number here and merely "not an identifier" on the web, and the two clients
    // would explain the same refusal two different ways. `isNotEmpty` is the regex's `+`.
    if (stripped.isNotEmpty() && stripped.all { it in '0'..'9' }) {
        return DwEncodeResult.Refused(
            DwEncodeRefusal.ID_LOOKS_SENSITIVE,
            "That is a number, not a record identifier. Aadhaar and Pehchan card numbers must never " +
                "be printed in a QR code — a card is a public object once it leaves the room."
        )
    }
    if (stripped.isNotEmpty() &&
        stripped.all { it in 'A'..'Z' || it in '0'..'9' } &&
        stripped.length <= 20 &&
        stripped.any { it in 'A'..'Z' }
    ) {
        // `normalize_pehchan` stores a Pehchan card number as upper-case alphanumerics with the
        // separators stripped, which is precisely this shape and nothing this repository issues as an
        // id (every id here is lower case).
        return DwEncodeResult.Refused(
            DwEncodeRefusal.ID_LOOKS_SENSITIVE,
            "That looks like a card number rather than a record identifier. Aadhaar and Pehchan card " +
                "numbers must never be printed in a QR code."
        )
    }

    if (!ID_PATTERN.matches(trimmed)) {
        return DwEncodeResult.Refused(
            DwEncodeRefusal.ID_NOT_AN_IDENTIFIER,
            "That is not an identifier this repository issues, so no code can be printed for it. " +
                "Identifiers are the lower-case ids the app allocates when a record is saved."
        )
    }

    // `uppercase()` and not `toUpperCase()`: the latter is the DEFAULT locale, and on a handset set
    // to Turkish it maps `i` to `İ` — which is not in QR alphanumeric mode, does not survive the
    // round trip through `lowercase()`, and would put a payload on a printed card that no device
    // outside that locale could resolve. Everything reaching here is `[a-z0-9-]`, so the root-locale
    // mapping is loss-free and exactly reversible.
    val prefix = "$WORKSHOP_CODE_NAMESPACE$WORKSHOP_CODE_VERSION:${type.letter}:${trimmed.uppercase()}"
    return DwEncodeResult.Ok(
        code = "$prefix:${workshopCodeCheck(prefix)}",
        ref = DwWorkshopCodeRef(recordType = type, id = trimmed)
    )
}

/** [encodeWorkshopCode] for a caller that already holds a type, and so cannot name an unknown one. */
fun encodeWorkshopCode(recordType: DwWorkshopRecordType, id: String?): DwEncodeResult =
    encodeWorkshopCode(recordType.wire, id)

// --------------------------------------------------------------------------------------
// Decoding
// --------------------------------------------------------------------------------------

enum class DwDecodeRefusal {
    /** Nothing was typed or scanned. */
    EMPTY,

    /** Whatever this is, it did not come from this application. */
    NOT_A_WORKSHOP_CODE,

    /** Ours, but written by a build newer than this one. */
    NEWER_VERSION,

    /** Ours, but pointing at a kind of record this build does not know. */
    UNKNOWN_RECORD_TYPE,

    /** Ours, but the wrong number of parts, or an id that is not one. */
    MALFORMED,

    /** Ours and well-formed, but the check does not agree with the id. */
    CHECK_FAILED,
}

sealed interface DwDecodeResult {
    data class Ok(val ref: DwWorkshopCodeRef, val code: String) : DwDecodeResult

    data class Refused(val reason: DwDecodeRefusal, val message: String) : DwDecodeResult
}

/**
 * Read a scanned or typed code.
 *
 * TOLERANT OF WHAT A HUMAN DOES TO IT, STRICT ABOUT WHAT IT MEANS. Case, surrounding whitespace and
 * the spaces [formatWorkshopCodeForPrint] adds for legibility are all removed before the parse,
 * because a designer copying a code off a card under a tin roof will not reproduce them and refusing
 * over a space would be a refusal about nothing. Everything after that is exact.
 *
 * [DwDecodeResult.Ok.code] is the CANONICAL spelling of what was read, not the characters that were
 * typed: upper-cased, unspaced, with Crockford's substitutions applied to the check and the version
 * reduced to its number. That is the string to store or to show, so that the same card read off a
 * scanner and typed by hand produces one value rather than two.
 */
fun decodeWorkshopCode(input: String?): DwDecodeResult {
    // `filterNot` rather than a `\s+` regex: Java's `\s` is ASCII-only, so a code carrying the
    // no-break space a phone keyboard produces on a long press of the space bar would keep it,
    // fail the identifier grammar, and be reported as a damaged card. See [isJsSpace].
    val raw = (input ?: "").filterNot { isJsSpace(it) }.uppercase()
    if (raw.isEmpty()) {
        return DwDecodeResult.Refused(DwDecodeRefusal.EMPTY, "Nothing was scanned or typed.")
    }

    val parts = raw.split(":")
    if (parts.size != 4 || !parts[0].startsWith(WORKSHOP_CODE_NAMESPACE)) {
        return DwDecodeResult.Refused(
            DwDecodeRefusal.NOT_A_WORKSHOP_CODE,
            "That is not a workshop card or tag. Workshop codes begin “DPW”; a shop barcode, a " +
                "payment code or a web address will not open a record here."
        )
    }

    val versionText = parts[0].substring(WORKSHOP_CODE_NAMESPACE.length)
    if (versionText.isEmpty() || !versionText.all { it in '0'..'9' }) {
        return DwDecodeResult.Refused(
            DwDecodeRefusal.NOT_A_WORKSHOP_CODE,
            "That is not a workshop card or tag. Workshop codes begin “DPW” followed by a version number."
        )
    }
    // `Number(versionText)` and not `toIntOrNull()`, which is the trap here. The digits come off a
    // card, so they are whatever was printed or mistyped, and JavaScript reads "00000000000000000009"
    // as 9 and four hundred nines as Infinity where `toIntOrNull` gives up and returns null on both.
    // Null would have to become some refusal, and the refusal it would become is "damaged card" for
    // something that is not damaged. `Double.parseDouble` over an ASCII digit string is correctly
    // rounded, which is also `Number`'s rule, so the two read the same VALUE — and [jsNumber] then
    // spells it, because that number is printed in the sentence a designer reads.
    //
    // THE STATED LIMIT OF THAT SPELLING, because a port is trusted exactly as far as its limits are
    // true: [jsNumber] goes through `Double.toString`, which before JDK 19 is not the shortest
    // round-tripping decimal (JDK-4511638). Twenty-three nines is bit-for-bit the double JavaScript
    // calls `1e+23` and this device writes "9.999999999999999e+22". It cannot change whether a card
    // reads — [canonicalVersion] only enters a hashed prefix for a SUPPORTED version, and those are
    // the small integers in [SUPPORTED_VERSIONS], where the two agree exactly. It can only change one
    // number inside one "update the app" sentence, for a version string of seventeen or more
    // significant digits, which is a corrupted scan rather than any card this repository prints.
    val version = versionText.toDouble()
    val canonicalVersion = jsNumber(version)
    if (SUPPORTED_VERSIONS.none { it.toDouble() == version }) {
        // Deliberately its own answer rather than "malformed". The card is fine; this build is old, and
        // "update the app" and "the tag is damaged" send a designer to two different places.
        return DwDecodeResult.Refused(
            DwDecodeRefusal.NEWER_VERSION,
            "This card was printed by a newer version of the app (code format $canonicalVersion) than " +
                "the one on this device. Update the app to read it, or open the record from the list instead."
        )
    }

    val recordType = DwWorkshopRecordType.ofLetter(parts[1])
        ?: return DwDecodeResult.Refused(
            DwDecodeRefusal.UNKNOWN_RECORD_TYPE,
            "This is a workshop code, but it points at a kind of record this version of the app does not open."
        )

    val id = parts[2].lowercase()
    if (!ID_PATTERN.matches(id)) {
        return DwDecodeResult.Refused(
            DwDecodeRefusal.MALFORMED,
            "This code is damaged or was typed incompletely — the identifier in it is not a whole one. " +
                "Check it against the card."
        )
    }

    val typedCheck = buildString(parts[3].length) {
        for (character in parts[3]) append(CHECK_CONFUSABLES[character] ?: character)
    }
    // The check is computed over the CANONICAL version and the UPPER-CASE id, not over the characters
    // as typed — which is what lets "DPW01:…" read as the version-1 card it is.
    val prefix = "$WORKSHOP_CODE_NAMESPACE$canonicalVersion:${parts[1]}:${parts[2]}"
    if (typedCheck.length != CHECK_LENGTH || typedCheck != workshopCodeCheck(prefix)) {
        // The most valuable refusal in the file. A code that reaches here is one character out — and
        // one character out in an identifier is not a near miss, it is a different record or no record
        // at all, with nothing downstream able to tell.
        return DwDecodeResult.Refused(
            DwDecodeRefusal.CHECK_FAILED,
            "This code does not check out, so one of its characters is wrong. Read it off the card " +
                "again, character by character — a single wrong character points at a different record, " +
                "and nothing later would notice."
        )
    }

    return DwDecodeResult.Ok(
        ref = DwWorkshopCodeRef(recordType = recordType, id = id),
        code = "$prefix:$typedCheck"
    )
}

// --------------------------------------------------------------------------------------
// Presentation
// --------------------------------------------------------------------------------------

/**
 * The code as it is printed under the QR, in groups of four.
 *
 * The grouping is the whole point: this string is what somebody reads aloud or types when the
 * camera will not work, and an unbroken 37-character run is read wrong by everybody. The separators
 * are stripped again by [decodeWorkshopCode], so what is printed can be typed straight back.
 *
 * `chunked(4)` is the web's `/.{1,4}/g` for every character a code can hold. The two would part
 * company only over a line terminator, which JavaScript's `.` skips and this keeps — and a payload
 * cannot contain one, because [encodeWorkshopCode] emits nothing outside `[A-Z0-9:-]`.
 */
fun formatWorkshopCodeForPrint(code: String): String = code.chunked(4).joinToString(" ")

/** The word for a record type, for a heading or a refusal sentence. */
fun workshopRecordTypeLabel(recordType: DwWorkshopRecordType): String = when (recordType) {
    DwWorkshopRecordType.ARTISAN -> "Artisan"
    DwWorkshopRecordType.PROTOTYPE -> "Prototype"
}

/**
 * What to say when a well-formed code does not resolve.
 *
 * ONE SENTENCE FOR TWO SITUATIONS, ON PURPOSE. The record may not exist, or it may exist and belong
 * to somebody whose work this caller may not see. The API answers 404 rather than 403 for exactly
 * that reason — a 403 confirms the record is real, and confirming that from an identifier printed
 * on a card is a way to enumerate a repository one photograph at a time. This message must not
 * quietly undo that by distinguishing the two, so it says only what is true of both.
 *
 * A function rather than a constant so the record type can be named — "no prototype in this
 * workshop" is far more useful than "no record", and it gives nothing away.
 */
fun unresolvedWorkshopCodeMessage(recordType: DwWorkshopRecordType): String = when (recordType) {
    DwWorkshopRecordType.PROTOTYPE ->
        "No prototype in this workshop matches that tag. It may belong to another workshop, or the " +
            "row may not have reached this device yet — open the workshop that made it, or find the " +
            "prototype in the list."
    DwWorkshopRecordType.ARTISAN ->
        "No artisan you can open matches that card. It may not be in the repository, or it may " +
            "belong to work you do not have access to — search for the artisan by name instead."
}

// --------------------------------------------------------------------------------------
// The rows a tag is printed for
// --------------------------------------------------------------------------------------

/**
 * One of the row's bookkeeping keys as a string, or null when the row does not carry it.
 *
 * A JSON null and a missing key are the same answer, which is what the web's `??` does. A non-string
 * value is also treated as absent, matching [DwCostIntegrity]'s reading of `_entryId` on the same
 * rows: the protocol only ever writes these two keys as strings, and a number arriving in one is a
 * corrupt draft rather than an id worth printing on a card.
 */
private fun DwDataRow.bookkeepingText(key: String): String? {
    val value = this[key]
    if (value !is JsonPrimitive || value is JsonNull || !value.isString) return null
    return value.content
}

/**
 * The id a prototype row should be tagged with.
 *
 * `_entryId` when the row has reached the server, and the row's client key when it has not. A tag
 * has to be printable the moment a prototype exists — that is the afternoon the maker is holding
 * it — and a workshop can go a fortnight without a connection, so refusing to print until the row
 * has synced would mean refusing to print at all in the place this is used. The client key is
 * stable for the life of the row (it is what stops a retried save duplicating it), so a tag printed
 * on Monday still resolves after Friday's sync, against a row that now has a server id too.
 *
 * NOT `ifBlank`, and not `takeIf { isNotEmpty() }`. The web's `??` falls through on null and on
 * nothing else, so a row holding an empty `_entryId` answers with the empty string here too — and
 * that then reaches [encodeWorkshopCode], which refuses it as NO_ID. Substituting the client key
 * instead would print a tag whose id the row's own sync state says it should not yet be known by.
 */
fun workshopCodeIdForRow(row: DwDataRow): String? =
    row.bookkeepingText("_entryId") ?: row.bookkeepingText("_clientKey")

/**
 * Does this code point at [row]? True for either identifier the row is known by — see
 * [workshopCodeIdForRow] for why a row legitimately answers to two.
 */
fun workshopCodeMatchesRow(ref: DwWorkshopCodeRef, row: DwDataRow): Boolean {
    if (ref.recordType != DwWorkshopRecordType.PROTOTYPE) return false
    return ref.id == row.bookkeepingText("_entryId") || ref.id == row.bookkeepingText("_clientKey")
}
