/**
 * The code printed on — and drawn live beside — every record this repository issues, and the
 * reasoning behind every character of it.
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
 *      │   └─ record type: one letter, see TYPE_LETTER
 *      └─ namespace "DPW" + payload version 1
 *
 * **NOTHING IS EVER SAVED.** There is no stored PNG, no cached SVG, no column on the record. The
 * payload is a pure function of a record's type and id, so every surface that shows a code
 * regenerates it — encoding a payload and scoring eight QR masks is well under a millisecond, and
 * the alternative is an asset that goes stale, needs invalidating, and occupies storage on a field
 * handset for something that costs nothing to redraw. If you are reading this because you are about
 * to "optimise" it into a stored image: the id it depends on is immutable, so a cached image can
 * only ever be as correct as this function, and never more.
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
 * the most harm — never `Artisan.aadhaarNumber` or a Pehchan card number. Aadhaar is this
 * repository's deduplication key, it is regulated personal data, and `artisan_identity.py` is
 * deliberately the only place it is ever formatted for output. A card is a physical object that
 * gets photographed, left on a table and carried home; whatever is in the QR is public the moment
 * it is printed. {@link encodeWorkshopCode} therefore REFUSES, at runtime, to encode anything that
 * is not shaped like an identifier this repository issues — see {@link ID_PATTERN} and the digit
 * rule beneath it. That refusal is not decoration: the function is one string parameter away from
 * being handed the wrong field by a future caller, and a type signature does not survive a
 * refactor the way a thrown refusal does.
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
 * The check alphabet is **Crockford base32** (no I, L, O or U), and {@link decodeWorkshopCode}
 * applies Crockford's substitutions to it on the way in. The id is deliberately NOT normalised that
 * way: `0` and `o` are both legal base36 characters in a cuid, so "correcting" one would corrupt an
 * id that was typed correctly. A confusable typed into the id is caught by the check and refused —
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
 * {@link SUPPORTED_VERSIONS} is only ever ADDED to. A card printed today must still scan in five
 * years, so version 1 stays readable forever — and a *newer* card met by an older app must say so
 * ("update the app") rather than fail as though the card were damaged, because those two lead a
 * designer to completely different next actions.
 *
 * PURE — no React, no fetch, no DOM, no clock, no randomness — in the sense of
 * `lib/marketAnalysis.ts`, and for the same reason: everything here has to run on a laptop that has
 * had no signal for three days, and be testable without one.
 */

/** The three characters that say a code belongs to this application at all. */
export const WORKSHOP_CODE_NAMESPACE = "DPW";

/** The payload version this build WRITES. It reads every version in {@link SUPPORTED_VERSIONS}. */
export const WORKSHOP_CODE_VERSION = 1;

/**
 * Every payload version this build can read. Entries are only ever added — removing one strands
 * every card already printed against it, and cards live in a workshop for years.
 */
export const SUPPORTED_VERSIONS: ReadonlySet<number> = new Set([1]);

/**
 * The kinds of record a code can point at: every record type this repository issues.
 *
 * The order is the dashboard's — artisan, craft, workshop, product, process, tool, interview, media
 * — with the two DESIGN-WORKSHOP entries last because neither is a record in the repository proper:
 * `designWorkshop` is the twenty-two-stage container, and `prototype` is a row inside one. That
 * order is read back out in the "codes exist for…" refusal below, so both clients must declare it
 * identically or they explain one refusal two ways.
 *
 * `workshop` AND `designWorkshop` ARE TWO DIFFERENT THINGS AND BOTH HAVE CODES. `workshop` is the
 * `Workshop` row in the repository — the documented craft workshop, opened at `/workshops?edit=…`.
 * `designWorkshop` is the `DesignWorkshop` row, which is a separate table with its own id, its own
 * access rules and its own list page. They were run together once in a draft of this table and the
 * result was a scanned card that opened a craft-documentation record when the designer had scanned
 * the workshop they were standing in.
 */
export type WorkshopRecordType =
  | "artisan"
  | "craft"
  | "workshop"
  | "product"
  | "process"
  | "tool"
  | "questionnaire"
  | "media"
  | "designWorkshop"
  | "prototype";

/**
 * The single letter each record type occupies in the payload.
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
 * {@link decodeWorkshopCode}), because a corrected type letter would silently point at a different
 * kind of record. Not using them at all is the way to keep that true.
 *
 * ⚠ KEEP THIS TABLE IN EXACT STEP WITH `DwWorkshopRecordType` in
 * `android/app/src/main/java/com/designprototype/workshop/data/DwWorkshopCodes.kt`. A letter that
 * means one thing on the handset and another in the browser produces a code that opens the wrong
 * record — which is the entire failure this file exists to prevent, arriving by the front door.
 *
 * ⚠ AND IT IS OUT OF STEP RIGHT NOW, IN ONE KNOWN AND SAFE DIRECTION. `designWorkshop` / `G` exists
 * here and NOT yet in the Kotlin enum. The handset therefore refuses a `DPW1:G:…` code with
 * `UNKNOWN_RECORD_TYPE` — "points at a kind of record this version of the app does not open" — which
 * is the honest degradation and NOT the dangerous one: a letter absent from a table cannot resolve
 * to the wrong record, only to no record. The two tests that pin the tables (`DwWorkshopCodesTest`
 * on the handset, the letter map in `e2e/workshop-codes.spec.ts` here) hold their values by hand
 * rather than reading each other's source, so NEITHER of them fails on this gap — it is written down
 * here because nothing else in the tree will say it. The letter is already committed and must not be
 * chosen again.
 *
 * ⚠ A SECOND DIVERGENCE IS ALREADY LIVE, and it is the one the paragraph above forbids: the two
 * clients now EXPLAIN ONE REFUSAL TWO WAYS. `knownRecordTypesSentence()` is built from these tables,
 * so the browser's "codes exist for…" reads out ten types ending "…media files, design workshops and
 * prototypes" while the handset's reads out nine ending "…media files and prototypes". Nothing
 * resolves to the wrong record because of it, but it is a user-visible disagreement and it is the
 * reason the follow-up below is not optional.
 *
 * ⚠ THE ANDROID FOLLOW-UP IS THREE EDITS, NOT ONE. An earlier version of this note said adding the
 * enum entry "is the whole of the follow-up"; that was wrong and would have left the Kotlin suite
 * red. Both Kotlin tests hold their expectations by hand:
 *
 *   1. `DwWorkshopCodes.kt` — add `DESIGN_WORKSHOP("designWorkshop", "G", "Design workshop",
 *      "design workshops")` to `DwWorkshopRecordType`, POSITIONED IMMEDIATELY BEFORE `PROTOTYPE` so
 *      the enum's order matches this file's `WorkshopRecordType` union.
 *   2. `DwWorkshopCodesTest.kt`, the `letters` map in "every record type has the same letter here as
 *      in the browser" — add the entry in the same position. That test asserts
 *      `letters.keys.toList() == DwWorkshopRecordType.entries.toList()`, so it fails on SIZE if the
 *      map is not updated and on ORDER if the enum entry is placed anywhere else.
 *   3. `DwWorkshopCodesTest.kt`, the refusal-sentence assertion — it pins the exact string ending
 *      "…media files and prototypes.", which `knownRecordTypesSentence()` changes the moment the
 *      entry lands. Update it to the ten-type sentence this file already produces.
 *
 * The device-local gate is a THIRD Android-facing concern and is handled here rather than deferred —
 * see {@link ANDROID_DEVICE_LOCAL_ID_PREFIX}, which refuses the handset's `local-<uuid>` shape as
 * well as the browser's `dwlocal-<uuid>`.
 */
const TYPE_LETTER: Record<WorkshopRecordType, string> = {
  artisan: "A",
  craft: "C",
  workshop: "W",
  /** proDuct. `P` was already the prototype's when products got a code. */
  product: "D",
  /** proceSs. `P` and `C` were both taken by the time a process got a code. */
  process: "S",
  tool: "T",
  /** The record is a `QuestionnaireInterview`; `Q` is the letter its endpoint and its queue use. */
  questionnaire: "Q",
  media: "M",
  /**
   * desiGn workshop. `W` is the repository `Workshop` and CANNOT MOVE — tags reading `DPW1:W:…` are
   * tied to craft-documentation records today, and the rule at the top of this table is that a live
   * letter is never re-pointed. `D` and `S` were already spent on the product and the process, so
   * the letter is taken from the middle of the word exactly as those two were.
   */
  designWorkshop: "G",
  prototype: "P"
};

/**
 * The reverse table, INVERTED from {@link TYPE_LETTER} rather than written out a second time.
 *
 * Two hand-maintained tables is how a letter comes to encode one type and decode to another — the
 * code would print correctly and open the wrong record, with the check digit agreeing all the way.
 * Inversion cannot drift; it can only collapse two types onto one letter, and
 * `e2e/workshop-codes.spec.ts` asserts the table is injective so that a duplicate is a failing test
 * rather than a silently lost record type.
 */
const TYPE_FROM_LETTER: Record<string, WorkshopRecordType> = Object.fromEntries(
  Object.entries(TYPE_LETTER).map(([type, letter]) => [letter, type as WorkshopRecordType])
);

/**
 * Every record type, in the order the refusal below names them and a picker should offer them.
 *
 * Read out of {@link TYPE_LETTER}'s declaration order rather than listed a second time — a type
 * added to the letter table but forgotten in a hand-written list would be encodable and invisible.
 * That is why the letter table is written in dashboard order despite its letters not being.
 */
export const WORKSHOP_RECORD_TYPES: readonly WorkshopRecordType[] = Object.keys(TYPE_LETTER) as WorkshopRecordType[];

/** The word for a record type in a heading — Android's `workshopRecordTypeLabel`, verbatim. */
const TYPE_LABEL: Record<WorkshopRecordType, string> = {
  artisan: "Artisan",
  craft: "Craft",
  workshop: "Workshop",
  product: "Product",
  process: "Process",
  tool: "Tool",
  // "Interview" and not "Questionnaire": the questionnaire is the FORM, the record is one sitting
  // with one artisan set. Android's dashboard tile says "Take interview" for the same reason.
  questionnaire: "Interview",
  media: "Media file",
  // "Design workshop" and not "Workshop", which is the OTHER record type in this same table. The two
  // labels are read side by side in the "codes exist for…" refusal, and a reader who met "Workshop"
  // twice would have no way to tell which of them their card was.
  designWorkshop: "Design workshop",
  prototype: "Prototype"
};

/** The plural of {@link TYPE_LABEL}, lower case, for the middle of a sentence. */
const TYPE_PLURAL: Record<WorkshopRecordType, string> = {
  artisan: "artisans",
  craft: "crafts",
  workshop: "workshops",
  product: "products",
  process: "processes",
  tool: "tools",
  questionnaire: "interviews",
  media: "media files",
  designWorkshop: "design workshops",
  prototype: "prototypes"
};

/** The word for a record type, for a heading or a refusal sentence. */
export function workshopRecordTypeLabel(recordType: WorkshopRecordType): string {
  return TYPE_LABEL[recordType];
}

/**
 * "artisans, crafts, … and prototypes" — the list a refusal names when a caller asks for a code for
 * something that is not a record.
 *
 * Built from the table rather than written as prose so that adding a record type updates the
 * sentence with it. A hand-written list is how a caller comes to be told that codes exist for two
 * kinds of record on a build that prints nine.
 */
function knownRecordTypesSentence(): string {
  const words = WORKSHOP_RECORD_TYPES.map((type) => TYPE_PLURAL[type]);
  return `${words.slice(0, -1).join(", ")} and ${words[words.length - 1]}`;
}

/** What a code resolves to, once it has been read. */
export type WorkshopCodeRef = {
  recordType: WorkshopRecordType;
  /** The id as the repository stores it — lower case, never the upper-cased printed form. */
  id: string;
};

/**
 * The shape of an identifier this repository issues: a cuid (`c` + 24 base36 characters) or the
 * UUID / `k-…` client key a row carries before it has ever reached the server.
 *
 * Lower case only, and that is load-bearing rather than fussy — see the file header. Hyphens are
 * allowed for UUIDs; `_` is not, because QR alphanumeric mode cannot carry it. The 8-character
 * floor keeps short human-typed strings ("prototype 3", "ram") out; the 64-character ceiling keeps
 * a runaway value from producing a symbol too dense to scan from a printed card.
 *
 * CHECKED AGAINST EVERY RECORD TYPE IN {@link TYPE_LETTER}, not assumed: `backend/prisma/schema.prisma`
 * declares `id String @id @default(cuid())` on Craft, Workshop, ProductDocumentation,
 * ToolDocumentation, Process, QuestionnaireInterview and MediaFile exactly as it does on Artisan, so
 * every id this grammar now admits is the same 25-character lower-case cuid it already admitted. A
 * record type whose ids were not that shape would have to widen this pattern deliberately — and
 * widening it is how a field that is not an identifier gets through the gate below.
 */
const ID_PATTERN = /^[a-z0-9][a-z0-9-]{7,63}$/;

/**
 * The prefix a design-workshop draft carries while it exists ONLY on the device that made it, and
 * the one shape of id this grammar refuses despite it matching {@link ID_PATTERN} perfectly.
 *
 * ── WHY THIS IS A REFUSAL AND NOT A CONVENIENCE ──────────────────────────────────────────────────
 *
 * `dwlocal-<uuid>` is minted by `lib/designWorkshopStore.ts::createLocalDraft` for a workshop opened
 * with no signal. It is a key into ONE browser's IndexedDB. It is not an identifier this repository
 * issues, and — this is the measured part, not an assumption — **it never becomes one.**
 * `DesignWorkshopCreate` in `backend/app/schemas/design_workshops.py` accepts title, templateId,
 * craftName, clusterName, state, district, startDate, endDate, workshopId and notes, AND NO CLIENT
 * KEY; `WorkshopSync.kt` says the same thing from the other side ("createDesignWorkshop carries no
 * client key and the create route de-duplicates nothing"). So when the draft finally syncs, the
 * server allocates a fresh cuid and the `dwlocal-` id is discarded. Nothing anywhere will ever
 * resolve it again, on any device but the one that minted it.
 *
 * THAT IS EXACTLY THE DIVERGENCE A SHARED CODE EXISTS TO PREVENT. A designer who could print this
 * would hand a colleague a tag that resolves to nothing, on every device, for ever — and a colleague
 * who scans a workshop tag and is told there is no such workshop does the only thing left to do,
 * which is start their own. Two devices, two workshops, one fortnight of fieldwork split between
 * them. Refusing the print is the whole mechanism: there is no code for a workshop that does not yet
 * exist for anybody else, so no code can send anybody to the wrong copy of it.
 *
 * The prefix is a SECOND copy of the string `designWorkshopStore.ts` declares as `LOCAL_ID_PREFIX`,
 * and it is duplicated rather than imported on purpose: this module is pure — no IndexedDB, no
 * React, no fetch — and importing the draft store to read one constant would drag the whole offline
 * database into every surface that merely prints a card. `e2e/workshop-codes.spec.ts` imports both
 * and asserts they are equal, so the duplication is pinned by a failing test rather than by hope.
 *
 * APPLIED TO EVERY RECORD TYPE, not only to `designWorkshop`. No other record this repository issues
 * has an id beginning `dwlocal-`, so the check costs nothing anywhere else — and a gate that only
 * guarded the one type it was written for is the gate that misses the caller nobody predicted.
 */
const DEVICE_LOCAL_ID_PREFIX = "dwlocal-";

/**
 * The prefix ANDROID uses for the same thing, which is A DIFFERENT STRING, and the reason this gate
 * takes two constants instead of one.
 *
 * `android/.../data/StageSchema.kt` declares `const val DW_LOCAL_ID_PREFIX: String = "local-"` — not
 * `"dwlocal-"` — and `ui/designworkshop/WorkshopListScreen.kt` mints `DW_LOCAL_ID_PREFIX + UUID`, so
 * a handset's device-only workshop is `local-<uuid>`. The two clients have simply never agreed here,
 * and several Kotlin PROSE comments (`WorkshopSync.kt` among them) say "mints a `dwlocal-` draft",
 * which is wrong about their own constant — reading that prose instead of the declaration is how an
 * earlier version of this docblock came to claim one shared string across three files.
 *
 * SO BOTH SHAPES ARE REFUSED, and the divergence is closed here rather than written down as a
 * follow-up: `DPW1:G:LOCAL-…` is a code the encoder would otherwise print cleanly (the id is lower
 * case, hyphenated and the right length, so {@link ID_PATTERN} accepts it) and the decoder would
 * otherwise resolve to the "ask an admin to add you" message — the confident wrong answer this whole
 * gate exists to prevent, on a workshop that exists on exactly one handset.
 *
 * SCOPED TO `designWorkshop`, UNLIKE THE ONE ABOVE, and that asymmetry is deliberate. `dwlocal-` is
 * a string nothing else in this repository could plausibly issue, so refusing it everywhere is free.
 * Bare `local-` is not: it is eight generic characters that a future id space, an import or another
 * repository's key could legitimately begin with, and a gate that refused a real record's code would
 * be a worse failure than the one it prevents. A device-local workshop is the only record either
 * client mints under this prefix, so this is the only type that needs it.
 */
const ANDROID_DEVICE_LOCAL_ID_PREFIX = "local-";

/**
 * Is this an id that only the device holding it can resolve — either client's spelling?
 *
 * One predicate so the encoder and the decoder cannot come to disagree about which ids are private,
 * which would mean printing a code this build then refuses to read.
 */
function isDeviceLocalId(recordType: WorkshopRecordType, id: string): boolean {
  if (id.startsWith(DEVICE_LOCAL_ID_PREFIX)) return true;
  return recordType === "designWorkshop" && id.startsWith(ANDROID_DEVICE_LOCAL_ID_PREFIX);
}

/** The check alphabet: Crockford base32, which has no I, L, O or U to confuse on a printed card. */
const CHECK_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const CHECK_LENGTH = 4;

/**
 * Crockford's reading rules, applied to the CHECK ONLY. `I` and `L` read as `1`, `O` as `0`. `U` is
 * absent from the alphabet on purpose (it is the character people most often produce when they mean
 * `V`), so it is left to fail rather than guessed at.
 */
const CHECK_CONFUSABLES: Record<string, string> = { I: "1", L: "1", O: "0" };

/* ────────────────────────────────────────────────────────────────────────────
 * The check
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The four check characters for a payload prefix — everything up to but not including the final
 * separator.
 *
 * FNV-1a over the ASCII bytes, of which the low 20 bits become four base32 characters. FNV-1a is
 * chosen for being four lines long and identical in every language this will be ported to (a Kotlin
 * port has to agree character-for-character or a card printed on the web will not read on the
 * handset). It detects every single-character substitution and, with probability about 1 in a
 * million, misses nothing else that matters at this length.
 *
 * `>>> 0` after the multiply is not optional: JavaScript's `*` produces a double, and past 2^53 the
 * low bits — the only ones that matter here — stop being exact.
 */
export function workshopCodeCheck(prefix: string): string {
  let hash = 0x811c9dc5;
  for (let index = 0; index < prefix.length; index++) {
    hash ^= prefix.charCodeAt(index);
    // The FNV prime, 16777619, spelled as shifts so the product never leaves 32-bit range.
    hash = (hash + ((hash << 1) + (hash << 4) + (hash << 7) + (hash << 8) + (hash << 24))) >>> 0;
  }
  const value = hash & 0xfffff;
  let out = "";
  for (let shift = (CHECK_LENGTH - 1) * 5; shift >= 0; shift -= 5) out += CHECK_ALPHABET[(value >>> shift) & 31];
  return out;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Encoding
 * ──────────────────────────────────────────────────────────────────────────── */

export type EncodeRefusal =
  /** No id at all — a row that has never been saved has nothing to point at yet. */
  | "NO_ID"
  /** A record type this build does not print codes for. */
  | "UNKNOWN_RECORD_TYPE"
  /** Not shaped like an identifier this repository issues. */
  | "ID_NOT_AN_IDENTIFIER"
  /** Shaped like regulated identity data. Refused loudly — see the file header. */
  | "ID_LOOKS_SENSITIVE"
  /**
   * A draft that exists only on this device. Well-formed, and deliberately refused —
   * see {@link DEVICE_LOCAL_ID_PREFIX} for the divergence this is the whole guard against.
   */
  | "ID_IS_DEVICE_LOCAL";

export type EncodeResult =
  | { ok: true; code: string; ref: WorkshopCodeRef }
  | { ok: false; reason: EncodeRefusal; message: string };

/**
 * A result rather than a thrown error, because the caller is usually a print sheet building thirty
 * cards at once: one prototype whose row has never synced must produce one refusal on one tag, not
 * an exception that empties the page a designer is about to print.
 */
export function encodeWorkshopCode(ref: {
  recordType: WorkshopRecordType | string;
  id: string | null | undefined;
}): EncodeResult {
  const letter = TYPE_LETTER[ref.recordType as WorkshopRecordType];
  if (!letter) {
    return {
      ok: false,
      reason: "UNKNOWN_RECORD_TYPE",
      message: `No code can be printed for a “${ref.recordType}” — codes exist for ${knownRecordTypesSentence()}.`
    };
  }

  const id = (ref.id ?? "").trim();
  if (!id) {
    return {
      ok: false,
      reason: "NO_ID",
      message: "This record has no identifier yet, so there is nothing to print on a code. Save it first."
    };
  }

  // THE ANTI-PII GATE, and the order matters: the sensitive shapes are named BEFORE the generic
  // "that is not an identifier" refusal, so a caller that has handed over an Aadhaar number is told
  // exactly what it did rather than being left to conclude the id was merely malformed.
  // Separators removed, because that is the form the identity fields are STORED in:
  // `normalize_aadhaar` strips the spacing people type ("1234 5678 9012") down to twelve digits and
  // `normalize_pehchan` strips separators and upper-cases. Testing the raw string alone would let
  // the spaced form of an Aadhaar number through the gate below.
  const stripped = id.replace(/[\s-]/g, "");
  if (/^\d+$/.test(stripped)) {
    return {
      ok: false,
      reason: "ID_LOOKS_SENSITIVE",
      message:
        "That is a number, not a record identifier. Aadhaar and Pehchan card numbers must never be printed in a QR code — a card is a public object once it leaves the room."
    };
  }
  if (/^[A-Z0-9]+$/.test(stripped) && stripped.length <= 20 && /[A-Z]/.test(stripped)) {
    // `normalize_pehchan` stores a Pehchan card number as upper-case alphanumerics with the
    // separators stripped, which is precisely this shape and nothing this repository issues as an
    // id (every id here is lower case).
    return {
      ok: false,
      reason: "ID_LOOKS_SENSITIVE",
      message:
        "That looks like a card number rather than a record identifier. Aadhaar and Pehchan card numbers must never be printed in a QR code."
    };
  }

  // BEFORE THE GENERIC PATTERN, because `dwlocal-<uuid>` and Android's `local-<uuid>` both PASS that
  // pattern — lower case, hyphenated, the right length — so the refusal underneath would never fire
  // and a tag naming one device's private draft would print cleanly. Named separately so the
  // designer is told the one thing that is actually true and actionable: the workshop has not been
  // shared yet. Both spellings, because the two clients mint different ones — see
  // {@link ANDROID_DEVICE_LOCAL_ID_PREFIX}.
  if (isDeviceLocalId(ref.recordType as WorkshopRecordType, id)) {
    return {
      ok: false,
      reason: "ID_IS_DEVICE_LOCAL",
      message:
        "This workshop exists only on this device, so there is nothing for anybody else to scan yet — a code for it would open nothing on their phone. It gets a shareable code once it has reached the server: connect this device, let it sync, and print the code then."
    };
  }

  if (!ID_PATTERN.test(id)) {
    return {
      ok: false,
      reason: "ID_NOT_AN_IDENTIFIER",
      message:
        "That is not an identifier this repository issues, so no code can be printed for it. Identifiers are the lower-case ids the app allocates when a record is saved."
    };
  }

  const prefix = `${WORKSHOP_CODE_NAMESPACE}${WORKSHOP_CODE_VERSION}:${letter}:${id.toUpperCase()}`;
  return { ok: true, code: `${prefix}:${workshopCodeCheck(prefix)}`, ref: { recordType: ref.recordType as WorkshopRecordType, id } };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Decoding
 * ──────────────────────────────────────────────────────────────────────────── */

export type DecodeRefusal =
  /** Nothing was typed or scanned. */
  | "EMPTY"
  /** Whatever this is, it did not come from this application. */
  | "NOT_A_WORKSHOP_CODE"
  /** Ours, but written by a build newer than this one. */
  | "NEWER_VERSION"
  /** Ours, but pointing at a kind of record this build does not know. */
  | "UNKNOWN_RECORD_TYPE"
  /** Ours, but the wrong number of parts, or an id that is not one. */
  | "MALFORMED"
  /**
   * Ours and well-formed, but it names a draft private to some device. {@link encodeWorkshopCode}
   * refuses to print one, so this is reachable only by hand — and it is answered rather than folded
   * into `MALFORMED` because the code is not damaged and telling somebody to re-read the card would
   * send them round a loop that cannot end.
   */
  | "DEVICE_LOCAL"
  /** Ours and well-formed, but the check does not agree with the id. */
  | "CHECK_FAILED";

export type DecodeResult =
  | { ok: true; ref: WorkshopCodeRef; code: string }
  | { ok: false; reason: DecodeRefusal; message: string };

/**
 * Read a scanned or typed code.
 *
 * TOLERANT OF WHAT A HUMAN DOES TO IT, STRICT ABOUT WHAT IT MEANS. Case, surrounding whitespace and
 * the spaces {@link formatWorkshopCodeForPrint} adds for legibility are all removed before the
 * parse, because a designer copying a code off a card under a tin roof will not reproduce them and
 * refusing over a space would be a refusal about nothing. Everything after that is exact.
 */
export function decodeWorkshopCode(input: string | null | undefined): DecodeResult {
  const raw = (input ?? "").replace(/\s+/g, "").toUpperCase();
  if (!raw) return { ok: false, reason: "EMPTY", message: "Nothing was scanned or typed." };

  const parts = raw.split(":");
  if (parts.length !== 4 || !parts[0].startsWith(WORKSHOP_CODE_NAMESPACE)) {
    return {
      ok: false,
      reason: "NOT_A_WORKSHOP_CODE",
      message:
        "That is not a workshop card or tag. Workshop codes begin “DPW”; a shop barcode, a payment code or a web address will not open a record here."
    };
  }

  const versionText = parts[0].slice(WORKSHOP_CODE_NAMESPACE.length);
  if (!/^\d+$/.test(versionText)) {
    return {
      ok: false,
      reason: "NOT_A_WORKSHOP_CODE",
      message: "That is not a workshop card or tag. Workshop codes begin “DPW” followed by a version number."
    };
  }
  const version = Number(versionText);
  if (!SUPPORTED_VERSIONS.has(version)) {
    // Deliberately its own answer rather than "malformed". The card is fine; this build is old, and
    // "update the app" and "the tag is damaged" send a designer to two different places.
    return {
      ok: false,
      reason: "NEWER_VERSION",
      message: `This card was printed by a newer version of the app (code format ${version}) than the one on this device. Update the app to read it, or open the record from the list instead.`
    };
  }

  const recordType = TYPE_FROM_LETTER[parts[1]];
  if (!recordType) {
    return {
      ok: false,
      reason: "UNKNOWN_RECORD_TYPE",
      message: "This is a workshop code, but it points at a kind of record this version of the app does not open."
    };
  }

  const id = parts[2].toLowerCase();
  // Symmetric with the encoder's refusal, and checked BEFORE the pattern for the same reason: a
  // `dwlocal-` id (or Android's `local-`) satisfies ID_PATTERN, so without this it would decode
  // cleanly and every caller downstream would go looking for a workshop that cannot exist for them —
  // landing on "ask an admin to add you", which is confidently wrong about a workshop that is on one
  // handset and nowhere else.
  if (isDeviceLocalId(recordType, id)) {
    return {
      ok: false,
      reason: "DEVICE_LOCAL",
      message:
        "That code names a workshop that had not been shared yet when it was written down — it only ever meant anything on the device that made it. Ask whoever created the workshop to sync their device and send you the code it prints then."
    };
  }
  if (!ID_PATTERN.test(id)) {
    return {
      ok: false,
      reason: "MALFORMED",
      message: "This code is damaged or was typed incompletely — the identifier in it is not a whole one. Check it against the card."
    };
  }

  const typedCheck = parts[3]
    .split("")
    .map((character) => CHECK_CONFUSABLES[character] ?? character)
    .join("");
  const prefix = `${WORKSHOP_CODE_NAMESPACE}${version}:${parts[1]}:${parts[2]}`;
  if (typedCheck.length !== CHECK_LENGTH || typedCheck !== workshopCodeCheck(prefix)) {
    // The most valuable refusal in the file. A code that reaches here is one character out — and
    // one character out in an identifier is not a near miss, it is a different record or no record
    // at all, with nothing downstream able to tell.
    return {
      ok: false,
      reason: "CHECK_FAILED",
      message:
        "This code does not check out, so one of its characters is wrong. Read it off the card again, character by character — a single wrong character points at a different record, and nothing later would notice."
    };
  }

  return { ok: true, ref: { recordType, id }, code: `${prefix}:${typedCheck}` };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Presentation
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The code as it is printed under the QR, in groups of four.
 *
 * The grouping is the whole point: this string is what somebody reads aloud or types when the
 * camera will not work, and an unbroken 37-character run is read wrong by everybody. The separators
 * are stripped again by {@link decodeWorkshopCode}, so what is printed can be typed straight back.
 */
export function formatWorkshopCodeForPrint(code: string): string {
  return (code.match(/.{1,4}/g) ?? []).join(" ");
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
 * Exported as a function rather than a constant so the record type can be named — "no prototype in
 * this workshop" is far more useful than "no record", and it gives nothing away.
 */
export function unresolvedWorkshopCodeMessage(recordType: WorkshopRecordType): string {
  if (recordType === "prototype") {
    // A prototype is a row inside one design workshop rather than a repository record, so the two
    // places it can be — another workshop, or a device this one has not synced with — are named.
    return "No prototype in this workshop matches that tag. It may belong to another workshop, or the row may not have reached this device yet — open the workshop that made it, or find the prototype in the list.";
  }
  if (recordType === "designWorkshop") {
    /*
      THE JOIN CASE, AND THE ONE MESSAGE ON THIS SCREEN THAT MUST NOT SAY "NO SUCH WORKSHOP".

      For every other record type the two states behind a 404 are "it is not there" and "it is not
      yours", and the API refuses to distinguish them so that a card cannot be used to enumerate the
      repository. That holds here too. But a design workshop is the ONE type where the second state
      is overwhelmingly the likely one and is also completely fixable: somebody has just been handed
      this code by the colleague standing next to them, precisely so they can work on it together.
      The workshop is real, they are looking at the person who made it, and the only thing missing is
      a `DesignWorkshopViewer` row.

      So this names the next move instead of the diagnosis, which gives away nothing a 403 would not
      have: it does not confirm the workshop exists — it says what to do IF it does, which is true
      advice in both states and is the same sentence either way.

      AND IT DOES NOT SAY "REQUEST SENT", because nothing has been sent. Granting access is
      `PUT /design-workshops/{id}/viewers`, which is `Depends(require_admin)` — a designer cannot put
      themselves on a workshop even with a perfect connection. The honest instruction is to ask an
      admin, and any UI over this must not dress that up as a submitted request.
    */
    return "No design workshop you can open matches that code. If a colleague has just shared it with you, the workshop is there and you have not been added to it yet — only an admin can do that, so send them the code and ask to be put on it. Everything you have already recorded on this device stays where it is.";
  }
  const noun = TYPE_LABEL[recordType].toLowerCase();
  return `No ${noun} you can open matches that code. It may not be in the repository, or it may belong to work you do not have access to — search for the ${noun} by name instead.`;
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
 */
export function workshopCodeIdForRow(row: { _entryId?: string; _clientKey?: string }): string | null {
  return row._entryId ?? row._clientKey ?? null;
}

/**
 * Does this code point at `row`? True for either identifier the row is known by — see
 * {@link workshopCodeIdForRow} for why a row legitimately answers to two.
 */
export function workshopCodeMatchesRow(ref: WorkshopCodeRef, row: { _entryId?: string; _clientKey?: string }): boolean {
  if (ref.recordType !== "prototype") return false;
  return ref.id === row._entryId || ref.id === row._clientKey;
}
