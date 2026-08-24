package com.designprototype.workshop.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The code drawn beside — and printed on — every record this repository issues, on the handset: the
 * Kotlin port of `frontend/lib/workshopCodes.ts`, and the reasoning behind every character of it.
 *
 * THE PROBLEM. The worst thing that happens in a workshop is not a lost file, it is a mis-typed
 * identifier: two days of prototype iteration attached to the wrong prototype, or a day of
 * measurements attached to the wrong artisan. Nobody finds out, because both records look complete.
 * Every record here already carries an identifier; a scan removes the typing, and removing the
 * typing removes the class of error.
 *
 * ── THE PAYLOAD ──────────────────────────────────────────────────────────────────────────────
 *
 *     DPW1:A:CMSIK2JG8000EH8XC1LCY661A:NEWD
 *     ─┬── ┬ ─────────────┬─────────── ─┬──
 *      │   │              │             └─ check: four characters over everything to its left
 *      │   │              └─ the record's id, upper-cased
 *      │   └─ record type: one letter, see [DwWorkshopRecordType]
 *      └─ namespace "DPW" + payload version 1
 *
 * **NOTHING IS EVER SAVED.** There is no stored PNG, no cached bitmap, no column on the record. The
 * payload is a pure function of a record's type and id, so every screen that shows a code
 * regenerates it — encoding a payload and scoring eight QR masks is under a millisecond on the
 * cheapest handset in the room, and the alternative is an asset that goes stale, needs invalidating,
 * and occupies storage on a field device for something that costs nothing to redraw. If you are
 * reading this because you are about to "optimise" it into a stored image: the id it depends on is
 * immutable, so a cached image can only ever be as correct as this function, and never more.
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
 * The kinds of record a code can point at — every record type this repository issues — and the
 * single letter each occupies in the payload.
 *
 * **NEVER REUSE A RETIRED LETTER, AND NEVER MOVE A LIVE ONE.** A letter is stamped on cards and tags
 * that outlive the build that printed them, so handing `A` to something that is not an artisan would
 * make every card already in a workshop resolve, confidently, to the wrong kind of record. That is
 * also why `P` stays with the prototype it has always meant and the product takes `D` instead: tags
 * reading `DPW1:P:…` are tied to objects in workshops today.
 *
 * The letters avoid `I`, `L`, `O` and `U`. The check digit's Crockford alphabet drops them because
 * they are what people get wrong reading a code off a card, and a type letter is typed by the same
 * person in the same box — but unlike the check it is NOT confusable-corrected on the way in (see
 * [decodeWorkshopCode]), because a corrected type letter would silently point at a different kind of
 * record. Not using them at all is the way to keep that true.
 *
 * DECLARATION ORDER IS THE DASHBOARD'S, and it is load-bearing rather than tidy: [entries] is read
 * back out by [knownRecordTypesSentence] into the sentence a caller sees when it asks for a code for
 * something that is not a record, and the web builds the same sentence out of the same order. Two
 * clients that listed the types differently would explain one refusal two different ways.
 *
 * ⚠ KEEP THIS ENUM IN EXACT STEP WITH `TYPE_LETTER` in `frontend/lib/workshopCodes.ts`. A letter that
 * means one thing on the handset and another in the browser produces a code that opens the wrong
 * record — which is the entire failure this file exists to prevent, arriving by the front door.
 * `DwWorkshopCodesTest` pins every letter, but it pins them BY HAND and so can only ever agree
 * with itself — it caught nothing when the browser added `G`. The pin that actually reads this
 * enum and `TYPE_LETTER` and compares them is `backend/tests/test_workshop_code_letters.py`; a
 * letter added, moved or renamed on either surface fails there, in either direction. It reads a
 * THIRD copy as well — `backend/app/services/design_workshop_access.py`, which decodes the code in
 * a join request and so has its own letter, namespace and id pattern to keep in step.
 *
 * [wire] is the spelling the web uses for the same value, so a record type that arrives as a string
 * — out of the stage registry, or off a saved intent — reaches [encodeWorkshopCode] unchanged.
 * [label] is the word for a heading, [plural] the word for the middle of a sentence.
 */
enum class DwWorkshopRecordType(
    val wire: String,
    val letter: String,
    val label: String,
    val plural: String,
) {
    ARTISAN("artisan", "A", "Artisan", "artisans"),
    CRAFT("craft", "C", "Craft", "crafts"),
    WORKSHOP("workshop", "W", "Workshop", "workshops"),

    /** proDuct. `P` was already the prototype's when products got a code. */
    PRODUCT("product", "D", "Product", "products"),

    /** proceSs. `P` and `C` were both taken by the time a process got a code. */
    PROCESS("process", "S", "Process", "processes"),
    TOOL("tool", "T", "Tool", "tools"),

    /**
     * The record is a `QuestionnaireInterview`; `Q` is the letter its endpoint and its queue use.
     * Labelled "Interview" and not "Questionnaire" because the questionnaire is the FORM and the
     * record is one sitting with one artisan set — the same distinction the dashboard tile makes
     * when it says "Take interview".
     */
    QUESTIONNAIRE("questionnaire", "Q", "Interview", "interviews"),
    MEDIA("media", "M", "Media file", "media files"),

    /**
     * desiGn workshop. `W` is the repository [WORKSHOP] and CANNOT MOVE — tags reading `DPW1:W:…`
     * are tied to craft-documentation records today, and the rule at the top of this table is that
     * a live letter is never re-pointed. `D` and `S` were already spent on the product and the
     * process, so the letter comes from the middle of the word exactly as those two do.
     *
     * Labelled "Design workshop" and not "Workshop", which is the OTHER record type in this same
     * enum: the two labels are read side by side in the "codes exist for…" refusal, and a reader
     * who met "Workshop" twice would have no way to tell which of them their card was.
     *
     * Placed here, immediately before [PROTOTYPE], because the enum's order is the order the
     * refusal sentence reads out and the web's `WorkshopRecordType` union puts `designWorkshop`
     * in this position. Anywhere else and the two clients explain one refusal in two orders.
     */
    DESIGN_WORKSHOP("designWorkshop", "G", "Design workshop", "design workshops"),
    PROTOTYPE("prototype", "P", "Prototype", "prototypes");

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

/**
 * "artisans, crafts, … and prototypes" — the list a refusal names when a caller asks for a code for
 * something that is not a record.
 *
 * Built from the enum rather than written as prose so that adding a record type updates the sentence
 * with it. A hand-written list is how a caller comes to be told that codes exist for two kinds of
 * record on a build that prints ten.
 */
private fun knownRecordTypesSentence(): String {
    val words = DwWorkshopRecordType.entries.map { it.plural }
    return words.dropLast(1).joinToString(", ") + " and " + words.last()
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
 *
 * CHECKED AGAINST EVERY RECORD TYPE IN [DwWorkshopRecordType], not assumed: `backend/prisma/schema.prisma`
 * declares `id String @id @default(cuid())` on Craft, Workshop, ProductDocumentation,
 * ToolDocumentation, Process, QuestionnaireInterview and MediaFile exactly as it does on Artisan, so
 * every id this grammar now admits is the same 25-character lower-case cuid it already admitted.
 */
private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{7,63}$")

/**
 * The prefix the BROWSER puts on a design-workshop draft that exists only in the tab that made it,
 * and — with [DW_LOCAL_ID_PREFIX], which is this handset's spelling of the same idea — the one
 * shape of id this grammar refuses despite it matching [ID_PATTERN] perfectly.
 *
 * ──── WHY THIS IS A REFUSAL AND NOT A CONVENIENCE ────────────────────────────────────────────
 *
 * `dwlocal-<uuid>` is minted by `frontend/lib/designWorkshopStore.ts::createLocalDraft`, and
 * `local-<uuid>` by `ui/designworkshop/WorkshopListScreen.kt`, for a workshop opened with no signal.
 * Each is a key into ONE device's storage. Neither is an identifier this repository issues, and
 * neither ever becomes one: `createDesignWorkshop` carries no client key and the create route
 * de-duplicates nothing (see [WorkshopSync]), so when the draft finally syncs the server allocates a
 * fresh cuid and the local id is discarded. Nothing anywhere resolves it again, on any device but
 * the one that minted it.
 *
 * THAT IS EXACTLY THE DIVERGENCE A SHARED CODE EXISTS TO PREVENT. A designer who could print this
 * would hand a colleague a tag that resolves to nothing, on every device, for ever — and a colleague
 * who scans a workshop tag and is told there is no such workshop does the only thing left to do,
 * which is start their own. Two devices, two workshops, one fortnight of fieldwork split between
 * them.
 *
 * THE TWO PREFIXES ARE SCOPED DIFFERENTLY, and the asymmetry is the web's, kept exactly. `dwlocal-`
 * is a string nothing else in this repository could plausibly issue, so refusing it for every record
 * type is free. Bare `local-` is not: it is six generic characters a future id space or an import
 * could legitimately begin with, and a gate that refused a real record's code would be worse than
 * the failure it prevents. A device-local workshop is the only record either client mints under that
 * prefix, so [DwWorkshopRecordType.DESIGN_WORKSHOP] is the only type that needs it.
 *
 * `isDeviceLocalId` in `frontend/lib/workshopCodes.ts` is the same predicate over the same two
 * constants. `backend/tests/test_workshop_code_letters.py` pins the LETTERS the two clients agree
 * on and not this, so a change here still has to be made in both places by hand.
 */
private const val WEB_DEVICE_LOCAL_ID_PREFIX = "dwlocal-"

/**
 * Is this an id only the device holding it can resolve — either client's spelling?
 *
 * One predicate for the encoder and the decoder so the two cannot come to disagree about which ids
 * are private, which would mean printing a code this same build then refuses to read.
 */
private fun isDeviceLocalId(recordType: DwWorkshopRecordType, id: String): Boolean {
    if (id.startsWith(WEB_DEVICE_LOCAL_ID_PREFIX)) return true
    return recordType == DwWorkshopRecordType.DESIGN_WORKSHOP && id.startsWith(DW_LOCAL_ID_PREFIX)
}

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

    /**
     * A draft that exists only on this device. Well-formed, and deliberately refused — see
     * [WEB_DEVICE_LOCAL_ID_PREFIX] for the divergence this is the whole guard against.
     */
    ID_IS_DEVICE_LOCAL,
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
            "No code can be printed for a “$recordType” — codes exist for ${knownRecordTypesSentence()}."
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

    // BEFORE THE GENERIC PATTERN, because `dwlocal-<uuid>` and this handset's `local-<uuid>` both
    // PASS that pattern — lower case, hyphenated, the right length — so the refusal underneath
    // would never fire and a tag naming one device's private draft would print cleanly. Named
    // separately so the designer is told the one thing that is actually true and actionable: the
    // workshop has not been shared yet. See [isDeviceLocalId].
    if (isDeviceLocalId(type, trimmed)) {
        return DwEncodeResult.Refused(
            DwEncodeRefusal.ID_IS_DEVICE_LOCAL,
            "This workshop exists only on this device, so there is nothing for anybody else to scan " +
                "yet — a code for it would open nothing on their phone. It gets a shareable code once " +
                "it has reached the server: connect this device, let it sync, and print the code then."
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

    /**
     * Ours and well-formed, but it names a draft private to some device. [encodeWorkshopCode]
     * refuses to print one, so this is reachable only by hand — and it is answered rather than
     * folded into [MALFORMED] because the code is not damaged, and telling somebody to re-read the
     * card would send them round a loop that cannot end.
     */
    DEVICE_LOCAL,

    /** Ours and well-formed, but the check does not agree with the id. */
    CHECK_FAILED,

    /**
     * Ours, and it is a JOIN CARD rather than a code naming a record — see [WORKSHOP_JOIN_LETTER].
     *
     * ANSWERED BY LETTER AND **BEFORE** THE VERSION GATE, which is the whole point of it existing.
     * A join card is written at version 2, so without this branch a genuine one fell into
     * [NEWER_VERSION] and every handset in the field told the person holding it to "update the app
     * to read it" — with no newer app to update to, and a perfectly good card in their hand. It is
     * not [UNKNOWN_RECORD_TYPE] either: `J` is not an unknown record type, it is deliberately not a
     * record type at all.
     *
     * A caller that can act on a join card should not reach this: [readWorkshopScan] dispatches on
     * the letter first and hands the card to the redemption path. This is the sentence for the
     * callers that only ever open records.
     */
    JOIN_CARD,
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

    if (parts[1] == WORKSHOP_JOIN_LETTER) {
        // A JOIN CARD MET BY THE RECORD PARSER, AND IT IS ANSWERED BEFORE THE VERSION GATE — the
        // same ordering `design_workshop_access.decode_design_workshop_code` keeps, for the same
        // reason: the honest next action does not depend on the version. This is a credential, not a
        // locator; nothing in [DwWorkshopRecordType] can resolve it, and it must never be filed as an
        // ask when the holder could simply have been let in.
        //
        // ⚠ THE CODE IS NOT ECHOED AND MUST NEVER BE. Every other refusal in this file may quote
        // what was read; this one carries a live 110-bit secret.
        return DwDecodeResult.Refused(
            DwDecodeRefusal.JOIN_CARD,
            "That is a join card, not a card that names a record — scanning it puts you on the " +
                "workshop rather than opening anything. Scan it from a design workshop's Cards & " +
                "tags screen, or from the code panel on Search."
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
    // Symmetric with the encoder's refusal, and checked BEFORE the pattern for the same reason: a
    // `dwlocal-` id (or this handset's `local-`) satisfies [ID_PATTERN], so without this it would
    // decode cleanly and every caller downstream would go looking for a workshop that cannot exist
    // for them — landing on "ask an admin to add you", which is confidently wrong about a workshop
    // that is on one device and nowhere else.
    if (isDeviceLocalId(recordType, id)) {
        return DwDecodeResult.Refused(
            DwDecodeRefusal.DEVICE_LOCAL,
            "That code names a workshop that had not been shared yet when it was written down — it " +
                "only ever meant anything on the device that made it. Ask whoever created the " +
                "workshop to sync their device and send you the code it prints then."
        )
    }
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
// The JOIN CARD: a second grammar, and the only string in this file that is a credential
// --------------------------------------------------------------------------------------

/**
 * The payload version a JOIN CARD is written at.
 *
 * ── WHY 2 AND NOT A NEW LETTER ON v1, WHICH IS THE SAME ARGUMENT THE SERVER MAKES ────────────
 *
 * `backend/app/services/design_workshop_grants.py` chose a new VERSION so that a build which had
 * already shipped would answer "that card was printed by a newer version of the app" — the right
 * sentence, on every handset in the field, for free. **THAT PLAN ONLY WORKS IF THERE IS A NEWER
 * VERSION TO UPDATE TO, AND THERE WAS NOT.** This build is it: the letter is answered before the
 * version gate (see [DwDecodeRefusal.JOIN_CARD]) and the card is redeemed rather than refused.
 *
 * DELIBERATELY NOT ADDED TO [SUPPORTED_VERSIONS]. That set gates [decodeWorkshopCode], which returns
 * a [DwWorkshopCodeRef] — a RECORD and a record type. A join card resolves to neither, and widening
 * that set would put a version into the record parser whose only letter is one no record type has.
 */
const val WORKSHOP_JOIN_CODE_VERSION = 2

/**
 * **J for Join, AND IT IS DELIBERATELY ABSENT FROM [DwWorkshopRecordType].**
 *
 * A join card is a credential, not a locator. Keeping this letter out of the record-type enum is
 * what makes it structurally impossible for the record-lookup path to resolve one: there is no entry
 * for [DwWorkshopRecordType.ofLetter] to find.
 *
 * ⚠ `J` IS RESERVED. Do not spend it on a record type later. The ten letters in use are
 * A C W D S T Q M G P, and `J` now means "this is a credential".
 */
const val WORKSHOP_JOIN_LETTER = "J"

/**
 * The alphabet a card's secret is drawn from — Crockford base32, the same one the check characters
 * use, so a card carries one character set and not two.
 */
private const val JOIN_SECRET_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/** 22 characters of that alphabet is 110 bits, which is the entire security argument for a card. */
private const val JOIN_SECRET_LENGTH = 22

/**
 * The shape of a whole secret after folding. Exactly the alphabet, exactly the length — a secret one
 * character short is not a near miss and there is nothing to be tolerant of.
 */
private val JOIN_SECRET_PATTERN = Regex("^[" + JOIN_SECRET_ALPHABET + "]{" + JOIN_SECRET_LENGTH + "}$")

/** Why a join card could not be read. Every one of these is a statement about the STRING. */
enum class DwJoinCardRefusal {
    /** Nothing was typed or scanned. */
    EMPTY,

    /** Whatever this is, it did not come from this application at all. */
    NOT_A_JOIN_CARD,

    /** Ours, and a code that NAMES a record rather than admitting anybody. */
    RECORD_TAG,

    /** A join card written by a build newer than this one. */
    NEWER_VERSION,

    /** Ours and well-formed, but it names a workshop that exists on one device only. */
    DEVICE_LOCAL,

    /** Ours, but the wrong number of parts, or an id or secret that is not a whole one. */
    MALFORMED,

    /** Ours and well-formed, but the check characters do not agree with the payload. */
    CHECK_FAILED,
}

/** What [decodeWorkshopJoinCard] made of a string. */
sealed interface DwJoinCardDecode {
    /**
     * A readable join card.
     *
     * @property workshopId the workshop it admits to, lower-cased — for keying a queue row and for
     *   showing which workshop is being joined. It is NOT authority for anything.
     * @property code the CANONICAL card, **including the live secret**. This is the string the
     *   redemption route is given and it is the only value in this file that is a credential.
     *
     * ⚠ DO NOT LOG [code], DO NOT PUT IT IN A REFUSAL MESSAGE, AND DO NOT SHOW IT ON A SCREEN THAT
     * IS NOT DELIBERATELY SHOWING A CARD. `redacted_code` on the server exists for this reason.
     */
    data class Ok(val workshopId: String, val code: String) : DwJoinCardDecode

    data class Refused(val reason: DwJoinCardRefusal, val message: String) : DwJoinCardDecode
}

/**
 * Does this string CLAIM to be a join card? Read from the letter alone, and it reads no secret.
 *
 * The dispatch predicate for [readWorkshopScan]: a scan is either a record code or a join card, and
 * the two have different parsers, different endpoints and different sentences. Deciding which by the
 * LETTER rather than by the version is what stops a v2 card being reported as a v1 card from the
 * future — the dead end that shipped.
 */
fun looksLikeJoinCard(input: String?): Boolean {
    val raw = (input ?: "").filterNot { isJsSpace(it) }.uppercase()
    val parts = raw.split(":")
    return parts.size == 4 &&
        parts[0].startsWith(WORKSHOP_CODE_NAMESPACE) &&
        parts[1] == WORKSHOP_JOIN_LETTER
}

/**
 * Read a scanned or typed JOIN CARD. A PORT of `design_workshop_grants.decode_join_code`.
 *
 * ── IT HAS TO AGREE WITH THAT FUNCTION CHARACTER FOR CHARACTER ────────────────────────────────
 *
 * Not merely on which cards it accepts — on the CANONICAL string it produces, because that string is
 * what is posted and the server recomputes the check over its own canonical form. The three places
 * the two languages could part company are the same three [decodeWorkshopCode] documents: the
 * whitespace rule (see [isJsSpace]), the 32-bit hash (see [workshopCodeCheck]), and the version
 * spelling — which cannot bite here, because a join card is refused unless its version is exactly
 * [WORKSHOP_JOIN_CODE_VERSION].
 *
 * ── THE CONFUSABLE FOLD IS APPLIED TO THE SECRET AND NOT TO THE ID ────────────────────────────
 *
 * The asymmetry the server states, for the same reason: the secret is drawn from an alphabet with no
 * `I`, `L`, `O` or `U`, so a `0` in it can only ever be a misread `O` — while a cuid legitimately
 * contains both `0` and `o`, so "correcting" one would corrupt an id that was typed correctly.
 *
 * ── AND THE CHECK IS STILL ONLY A TYPO DETECTOR ───────────────────────────────────────────────
 *
 * Said here because this is the one grammar in the file where somebody might hope otherwise. The
 * four characters are FNV-1a and the algorithm ships in this APK and in every browser. They catch a
 * card read one character wrong, which is what a courtyard produces. **They cannot catch a forgery.**
 * What catches a forgery is the 110-bit secret failing to match a row, and that only happens ONLINE
 * — which is why nothing on this device ever treats a well-formed card as admission.
 *
 * PURE — no network, no clock, no randomness — so `DwJoinCardTest` pins it.
 */
fun decodeWorkshopJoinCard(input: String?): DwJoinCardDecode {
    val raw = (input ?: "").filterNot { isJsSpace(it) }.uppercase()
    if (raw.isEmpty()) {
        return DwJoinCardDecode.Refused(DwJoinCardRefusal.EMPTY, "Nothing was scanned or typed.")
    }

    val parts = raw.split(":")
    if (parts.size != 4 || !parts[0].startsWith(WORKSHOP_CODE_NAMESPACE)) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.NOT_A_JOIN_CARD,
            "That is not a workshop join card. Join cards begin “DPW”; a shop barcode, a payment " +
                "code or a web address will not let you into a workshop."
        )
    }

    val versionText = parts[0].substring(WORKSHOP_CODE_NAMESPACE.length)
    if (versionText.isEmpty() || !versionText.all { it in '0'..'9' }) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.NOT_A_JOIN_CARD,
            "That is not a workshop join card. Join cards begin “DPW” followed by a version number."
        )
    }
    // `toDouble` and not `toIntOrNull`, on [decodeWorkshopCode]'s reasoning: the digits come off a
    // card, so "DPW02" is a version-2 card typed by somebody being careful and four hundred nines is
    // a corrupted scan. Neither should be reported as a damaged card for the wrong reason.
    val version = versionText.toDouble()

    if (parts[1] != WORKSHOP_JOIN_LETTER) {
        // OURS AND WELL FORMED, but it NAMES a record. Named separately from "not one of ours"
        // because somebody scanning the workshop's own tag when they were handed a join card needs
        // to be told which card to look for, not that their scanner is broken.
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.RECORD_TAG,
            "That code belongs to this application but is not a join card — it names a record. " +
                "Scan the join card you were handed, the one printed to let somebody in."
        )
    }
    if (version != WORKSHOP_JOIN_CODE_VERSION.toDouble()) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.NEWER_VERSION,
            "That join card was printed against a code format this version of the app does not " +
                "read. Update the app, or ask an administrator to add you from the workshop's " +
                "viewers screen."
        )
    }

    val body = parts[2]
    if (body.count { it == '.' } != 1) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.MALFORMED,
            "This join card is damaged or was typed incompletely. Check it against the card, " +
                "character by character."
        )
    }
    val idText = body.substringBefore('.')
    val secretText = body.substringAfter('.')

    val workshopId = idText.lowercase()
    // BEFORE the pattern below, which a `dwlocal-`/`local-` id passes perfectly well.
    if (workshopId.startsWith(WEB_DEVICE_LOCAL_ID_PREFIX) || workshopId.startsWith(DW_LOCAL_ID_PREFIX)) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.DEVICE_LOCAL,
            "That card names a workshop that had not been shared yet when it was printed — it only " +
                "ever meant anything on the device that made it. Ask whoever created the workshop " +
                "to sync their device and print a fresh card."
        )
    }
    if (!ID_PATTERN.matches(workshopId)) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.MALFORMED,
            "This join card is damaged or was typed incompletely — the identifier in it is not a " +
                "whole one. Check it against the card."
        )
    }

    val secret = buildString(secretText.length) {
        for (character in secretText) append(CHECK_CONFUSABLES[character] ?: character)
    }
    if (!JOIN_SECRET_PATTERN.matches(secret)) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.MALFORMED,
            "This join card is damaged or was typed incompletely — the part after the full stop is " +
                "not a whole one. Read it off the card again, character by character."
        )
    }

    val typedCheck = buildString(parts[3].length) {
        for (character in parts[3]) append(CHECK_CONFUSABLES[character] ?: character)
    }
    val prefix = WORKSHOP_CODE_NAMESPACE + WORKSHOP_JOIN_CODE_VERSION + ":" +
        WORKSHOP_JOIN_LETTER + ":" + idText + "." + secret
    if (typedCheck.length != CHECK_LENGTH || typedCheck != workshopCodeCheck(prefix)) {
        return DwJoinCardDecode.Refused(
            DwJoinCardRefusal.CHECK_FAILED,
            "This join card does not check out, so one of its characters is wrong. Read it off the " +
                "card again, character by character."
        )
    }

    return DwJoinCardDecode.Ok(workshopId = workshopId, code = prefix + ":" + typedCheck)
}

/**
 * The ONE front door for anything a camera read or a person typed: a record code, or a join card.
 *
 * ── WHY THE DISPATCH IS HERE AND NOT AT EACH CALL SITE ────────────────────────────────────────
 *
 * There are two scan surfaces (a workshop's Cards & tags screen and the code panel on Search) and
 * four routes into each (live camera, photograph, picked picture, typed box). Deciding "is this a
 * join card" eight times over is how one of the eight comes to disagree — and the failure mode is
 * specific and bad: whichever surface got it wrong tells the person holding a genuine card to update
 * an app that has no update.
 */
sealed interface DwWorkshopScan {
    /** A code that NAMES a record. [ref] is a locator and confers nothing. */
    data class RecordCode(val ref: DwWorkshopCodeRef, val code: String) : DwWorkshopScan

    /** A JOIN CARD. [card] carries a live secret — see [DwJoinCardDecode.Ok]. */
    data class JoinCard(val card: DwJoinCardDecode.Ok) : DwWorkshopScan

    /** Neither, with the sentence written by whichever parser was the right one to ask. */
    data class Refused(val message: String) : DwWorkshopScan
}

/**
 * Read whatever arrived, and say which of the two kinds of string it is.
 *
 * THE JOIN PARSER IS ASKED FIRST AND ONLY WHEN [looksLikeJoinCard] SAYS SO, so a record code is
 * still refused by the record parser's own sentences and nothing about the fourteen record types
 * changes. A string that claims the `J` letter is answered entirely by the join parser, including
 * its refusals: "read the card again" is the right advice for a mistyped join card and "update the
 * app" never is.
 */
fun readWorkshopScan(input: String?): DwWorkshopScan {
    if (looksLikeJoinCard(input)) {
        return when (val decoded = decodeWorkshopJoinCard(input)) {
            is DwJoinCardDecode.Ok -> DwWorkshopScan.JoinCard(decoded)
            is DwJoinCardDecode.Refused -> DwWorkshopScan.Refused(decoded.message)
        }
    }
    return when (val decoded = decodeWorkshopCode(input)) {
        is DwDecodeResult.Ok -> DwWorkshopScan.RecordCode(decoded.ref, decoded.code)
        is DwDecodeResult.Refused -> DwWorkshopScan.Refused(decoded.message)
    }
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
fun workshopRecordTypeLabel(recordType: DwWorkshopRecordType): String = recordType.label

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
fun unresolvedWorkshopCodeMessage(recordType: DwWorkshopRecordType): String {
    if (recordType == DwWorkshopRecordType.PROTOTYPE) {
        // A prototype is a row inside one design workshop rather than a repository record, so the two
        // places it can be — another workshop, or a device this one has not synced with — are named.
        return "No prototype in this workshop matches that tag. It may belong to another workshop, or the " +
            "row may not have reached this device yet — open the workshop that made it, or find the " +
            "prototype in the list."
    }
    if (recordType == DwWorkshopRecordType.DESIGN_WORKSHOP) {
        // ⚠ NO PRODUCTION CALL SITE REACHES THIS ARM YET, AND THAT IS WORTH KNOWING BEFORE YOU
        // TRUST IT. All four production call sites either pass a literal type or are guarded:
        // `WorkshopCodesScreen`'s lookup panel reaches it under `ref.recordType == PROTOTYPE`, its
        // artisan lookup passes `ARTISAN`, and `RecordCodeLookup.lookUpRecordCode` passes `PROTOTYPE`
        // from its exhaustive `when` and `ref.recordType` from its `HttpException` catch — which a
        // `G` code never reaches, because that function refuses a design workshop before it asks the
        // network at all (see [designWorkshopCodeNotOpenableMessage]).
        // The sentence is kept, written, and pinned against the browser's anyway: the browser DOES
        // reach it (`frontend/lib/workshopCodeLookup.ts` on an `ApiError`), so leaving it out would
        // leave the browser's live sentence with nothing to agree with, and it becomes the handset's
        // answer the moment `MainActivity` can route a scanned workshop to the workshop it names.
        // Until then, do not cite this arm as the words a designer reads.
        //
        // THE JOIN CASE, AND THE ONE MESSAGE HERE THAT MUST NOT SAY "SEARCH FOR IT BY NAME".
        //
        // For every other record type the two states behind a 404 are "it is not there" and "it is
        // not yours", and the API refuses to distinguish them so that a card cannot be used to
        // enumerate the repository. That holds here too. But a design workshop is the ONE type where
        // the second state is overwhelmingly the likely one and is also completely fixable: somebody
        // has just been handed this code by the colleague standing next to them, precisely so they
        // can work on it together. The generic sentence would send them to search a workshop list
        // that cannot show them a workshop they are not on yet, which is a loop with no end in it.
        //
        // It names the next move instead of the diagnosis, which gives away nothing a 403 would not
        // have: it does not confirm the workshop exists — it says what to do IF it does, which is
        // true advice in both states and is the same sentence either way.
        //
        // AND IT DOES NOT SAY "REQUEST SENT", because nothing has been sent. Granting access is
        // `PUT /design-workshops/{id}/viewers`, which is admin-only — a designer cannot put
        // themselves on a workshop even with a perfect connection.
        //
        // Word for word the web's, in `frontend/lib/workshopCodes.ts`: two clients explaining one
        // refusal two ways is the failure this whole file is written against.
        return "No design workshop you can open matches that code. If a colleague has just shared " +
            "it with you, the workshop is there and you have not been added to it yet — only an " +
            "admin can do that, so send them the code and ask to be put on it. Everything you have " +
            "already recorded on this device stays where it is."
    }
    // `lowercase()` and not `toLowerCase()`: the latter is the DEFAULT locale, and on a handset set to
    // Turkish it maps `I` to a dotless `ı`, so an "Interview" would be described to a designer with a
    // character the web never writes. Every label here is ASCII, so the root-locale mapping is exact.
    val noun = recordType.label.lowercase()
    return "No $noun you can open matches that code. It may not be in the repository, or it may " +
        "belong to work you do not have access to — search for the $noun by name instead."
}

/**
 * ⚠ RETIRED ON 2026-08-24. NO PRODUCTION CALL SITE REACHES THIS FUNCTION ANY MORE.
 *
 * ── WHAT IT WAS, AND WHAT REPLACED IT ─────────────────────────────────────────────────────────
 *
 * This was the answer a scanned `DPW1:G:…` card got: "this version of the app cannot open a design
 * workshop from a code". It was honest and it was a DEAD END. A designer handed a card by the
 * colleague standing next to them — which is the entire situation the card exists for — was told the
 * app could not act on it and sent to go and find an administrator.
 *
 * It has been replaced by a real join path, `ui/DwWorkshopJoin.kt`, which posts the ask to
 * `POST /design-workshop-access/requests` and shows THAT ROUTE'S OWN sentence. The route was shipped
 * and had no client at all; the refusal below was never the server's limit, only this app's.
 *
 * ── WHY THE FUNCTION IS STILL HERE ────────────────────────────────────────────────────────────
 *
 * `DwWorkshopCodesTest` pins its wording by value, character for character, and that test file
 * belongs to a wave that is not this one. Deleting the function would break a build for a reason
 * that has nothing to do with the change. It goes when the test that pins it goes — together, in one
 * change, by whoever owns both. DO NOT CALL IT: the join path is [designWorkshopJoinAskingMessage]
 * and `dwJoinDesignWorkshop`.
 *
 * Everything below this line is the argument as it stood, kept because the reversal is only legible
 * beside it.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * What to say when a design-workshop code is READ SUCCESSFULLY and this build still cannot act on it.
 *
 * A DIFFERENT SITUATION FROM [unresolvedWorkshopCodeMessage], AND THE DIFFERENCE IS WHOSE LIMIT IT
 * IS. That sentence answers a lookup that was made and came back with nothing. This one answers a
 * lookup that was never made: `RecordCodeLookup`'s typed-code box decodes a `DPW1:G:…` card
 * perfectly and then has nowhere to send it, because opening a design workshop from a code needs
 * routing in `MainActivity` that does not exist yet — its `searchRecordEntryMode` falls back to
 * `EntryMode.ARTISAN`, so a resolved workshop id would be opened AS AN ARTISAN one press later,
 * which is precisely the wrong-record failure the codes exist to remove.
 *
 * SO IT NAMES THE BUILD'S LIMIT AND NOT THE CARD'S NATURE. "That is a design workshop code, not a
 * record code" would be a claim about the code that the browser flatly contradicts — there,
 * `workshopCodeLookup.ts` resolves a `G` code and opens `/design-workshops/{id}` — so a designer
 * comparing the two surfaces would be told the card is the wrong kind of thing on one of them. A
 * capability this version does not have is true on both, and stops being true here without anybody
 * having to re-argue the wording.
 *
 * AND IT NEVER SAYS THE WORKSHOP EXISTS. Nothing was asked, so nothing is known: the access half is
 * conditional ("if a colleague has just shared this one with you"), the same hedge the web's
 * unresolved sentence carries. A card naming a deleted workshop, or one from another deployment,
 * must not send a designer to an admin about a workshop that is not there.
 */
fun designWorkshopCodeNotOpenableMessage(): String =
    "This version of the app cannot open a design workshop from a code — a design workshop is " +
        "opened from the design workshop list. If a colleague has just shared this one with you and " +
        "it is not in your list yet, only an admin can add you to it, so send them the code and ask " +
        "to be put on it."

/**
 * What to say the moment a design-workshop card has been read and the ask is going out.
 *
 * A TRANSIENT SENTENCE AND THE ONLY ONE THIS FILE WRITES ABOUT JOINING. The sentence a designer is
 * LEFT with comes from the server — `design_workshop_access.py`'s `RECEIVED_DETAIL`, shown as given,
 * for the reason that module states at length: one reply for all seven of its outcomes, because a
 * confirmation that varied by outcome would let anybody read the existence of a workshop off the
 * wording. This one covers the second in between.
 *
 * IT DOES NOT SAY THE WORKSHOP EXISTS and it does not say anybody has been asked yet. It says what
 * the card IS and what is happening — both of which are true whatever the id turns out to name.
 *
 * IT ALSO DOES NOT SAY "OPENING THE WORKSHOP". Being put on a workshop is not the same act as
 * opening one, an admin still has to decide, and a sentence that blurred the two would leave a
 * designer waiting for a screen that is not coming.
 */
fun designWorkshopJoinAskingMessage(): String =
    "That card is for a design workshop. Asking to be put on it…"

/**
 * What a design workshop's own card is FOR, printed and on screen beside the symbol.
 *
 * ── THE ONE RECORD TYPE WHOSE CODE IS ABOUT PEOPLE ────────────────────────────────────────────
 *
 * Every other code in this grammar NAMES a record. A design workshop's code is the one somebody is
 * handed in order to be let in, so it is the one whose card needs a sentence about what happens
 * next — and the two halves of that sentence are the two things a designer holding it gets wrong.
 *
 * FIRST HALF: SCANNING **THIS** CARD ASKS, IT DOES NOT ADMIT. An administrator decides. Saying so on
 * the card is what stops somebody scanning it, seeing nothing appear, and scanning it again.
 *
 * ⚠ AND IT NOW HAS TO SAY WHICH CARD IT IS TALKING ABOUT, because a card that DOES admit exists:
 * [designWorkshopJoinCardPurposeMessage]. Both are printed for the same workshop and both are a
 * square of black and white to whoever is holding one, so a sentence that did not name the
 * difference would be read as a statement about the other card half the time — describing a live
 * credential as "not a password", or describing an induction as a request nobody will answer.
 *
 * SECOND HALF: THE CODE IS NOT A KEY AND MUST NEVER BE DESCRIBED AS ONE. The four check characters
 * are FNV-1a over the payload and the algorithm ships in every browser, so — in
 * `design_workshop_access.py`'s own words — "anyone can compute a valid check for any id" and a valid
 * code is "EVIDENCE for the admin reading the queue and nothing else". A card described as a key is
 * a card people treat as a secret, and treating a printed line as a secret is how somebody comes to
 * believe a photograph of it was safe.
 */
fun designWorkshopCardPurposeMessage(): String =
    "This is the workshop's own tag, not a join card. Scanning it asks an administrator to put " +
        "somebody on the workshop — it does not let them in by itself, and it is not a password: " +
        "the code is a reference with a check digit, so anybody who has it can only ask, exactly " +
        "as they could by typing the code. To put somebody on straight away, print a join card " +
        "below instead."

/**
 * What a JOIN CARD is for, printed and on screen beside its symbol.
 *
 * ── IT IS THE OPPOSITE SENTENCE FROM [designWorkshopCardPurposeMessage], AND THAT IS THE POINT ─
 *
 * That one is about the workshop's TAG, which names the workshop and lets somebody ASK. This is
 * about a card whose 110-bit secret IS the credential: scanning it puts the holder on the workshop
 * with no administrator in the loop, exactly as if an admin had ticked their name. Printing the two
 * with the same sentence would be the worst possible outcome — either a real credential described as
 * harmless, or an induction described as a request nobody will answer.
 *
 * ── SO IT SAYS THE THREE THINGS A PERSON HOLDING ONE HAS TO KNOW ──────────────────────────────
 *
 *  1. IT LETS SOMEBODY IN. No admin, no waiting, no queue.
 *  2. IT IS A KEY, SO IT IS SPENT AND IT IS NOT SHAREABLE. One card, one person, by default — and
 *     "do not photograph it" is said out loud, because a printed line people are told is safe is how
 *     somebody comes to put a credential in a group chat. The v1 tag's own sentence argues at length
 *     that a code with a check digit must never be described as a key; this card must never be
 *     described as anything else.
 *  3. IT RUNS OUT. A card is good for weeks, not for ever, which is what stops one living in a bag.
 *
 * It does NOT promise the scan will succeed. Somebody else may have used the card first, and the
 * honest answer to that is the server's — see `DwJoinCardOutcome`.
 */
fun designWorkshopJoinCardPurposeMessage(): String =
    "This card lets ONE person onto this workshop, straight away and with no administrator " +
        "involved. Treat it like a key: hand it to the person it is for, do not photograph it or " +
        "send it in a message, and do not leave it where it can be copied. Once somebody has used " +
        "it, it will not let anybody else in, and it stops working after a few weeks."

/** Said before the network is touched, so the panel is not blank while a card is being redeemed. */
fun designWorkshopJoinCardRedeemingMessage(): String =
    "That is a join card. Putting you on the workshop…"

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
