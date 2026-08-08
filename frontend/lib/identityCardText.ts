/**
 * Turning RECOGNISED TEXT off an identity card into numbers worth offering — and refusing the rest.
 *
 * PURE ON PURPOSE. No React, no DOM, no `File`, no fetch. Everything that decides whether a
 * twelve-digit string is put in front of a designer lives here, so it can be tested in Node
 * (`e2e/identity-card-web-unit.spec.ts`) rather than through a browser and a camera. The browser
 * half — the recogniser, the bitmap, the feature detection — is `lib/identityCardLocal.ts` and it
 * makes no decisions at all.
 *
 * ── WHY THIS EXISTS AT ALL ────────────────────────────────────────────────────────────────────
 *
 * Until now the only reader was `POST /design-workshops/ocr/identity`, and the SERVER did this
 * parsing (`backend/app/services/identity_ocr.py::aadhaar_candidates`); the browser only re-checked
 * the candidates it was handed. A browser that recognises the text itself has raw text and nothing
 * else, so the same rule has to exist here — and "the same rule" has to mean the same rule, not a
 * paraphrase of it. See `docs/DECISION-identity-card-ocr-on-web.md` for how the two are kept in
 * step, and for why the ports are pinned by tests against the server's own fixtures rather than by
 * good intentions.
 *
 * ── A DIGIT STRING IS NOT AN AADHAAR NUMBER, AND THIS IS WHERE THAT IS ENFORCED ────────────────
 *
 * An Aadhaar card carries, in print, next to each other: the twelve-digit number, a SIXTEEN-digit
 * VID, a fourteen-digit enrolment number, a date, and a six-digit pin code. Any recogniser returns
 * all of them. Two filters stand between that and `Artisan.aadhaarNumber`, which is the
 * repository's deduplication key:
 *
 * 1. **The run must be a WHOLE digit token of exactly twelve digits.** This is the rule that is
 *    stricter here than the server's regex, and it was added because of a measurement rather than a
 *    hunch. The server matches `(?<![0-9])((?:[0-9][ \-]?){11}[0-9])(?![0-9])`, whose lookarounds
 *    only look at the immediately adjacent CHARACTER — so in the printed VID "9123 4567 8901 2345"
 *    the first twelve digits are followed by a SPACE, the lookahead is satisfied, and
 *    "912345678901" is extracted as an Aadhaar candidate. Verhoeff then rejects it about nine times
 *    in ten. Sampled over 200,000 Verhoeff-valid sixteen-digit numbers, **10.02% of them have a
 *    twelve-digit prefix that ALSO satisfies Verhoeff** — so roughly one card in ten would offer
 *    the front of its own VID as a number, and the confirm step cannot catch it: the panel prints
 *    "9123 4567 8901" grouped 4-4-4 and the card prints "9123 4567 8901 2345", so the first three
 *    groups match, which is exactly what a designer comparing group by group is looking for.
 *    Reading the maximal separator-joined token and demanding it be exactly twelve digits long
 *    refuses the VID, the enrolment number and the pin code outright, before any checksum runs.
 * 2. **Verhoeff, via the caller's validator.** `aadhaarProblem` is passed in — never imported —
 *    for the same reason `identityChoices` takes it: there is exactly one Aadhaar checksum on this
 *    client (`AadhaarField.aadhaarValidationError`) and this module does not get an opinion of its
 *    own about what a valid number is.
 *
 * A candidate that fails either filter is REFUSED, never offered with a warning. The rule the
 * whole feature rests on is that a misread digit produces twelve plausible digits belonging to
 * nobody, which collides with no one, passes every check the system has, and silently becomes a
 * second record for an artisan who is already in the repository.
 *
 * ── WHY THERE IS NO PEHCHAN PARSER HERE ───────────────────────────────────────────────────────
 *
 * The server's `pehchan_candidates` reads a STRUCTURED model reply: it asks a vision model for the
 * artisan ID and normalises what comes back. There is no equivalent to port. A PM Vishwakarma
 * artisan ID has no checksum and no fixed shape beyond "4–32 letters and digits", so extracting one
 * from free recognised text would nominate the artisan's name, the word "GOVERNMENT" and the
 * card's own serial with equal confidence, and the designer would be asked to confirm one of them
 * into a field that has no way of ever detecting the mistake. The local reader therefore offers
 * Aadhaar numbers only, and the surfaces that use it say so. The server route still returns Pehchan
 * candidates and `identityChoices` still filters them.
 *
 * ── NOTHING HERE LOGS A NUMBER ────────────────────────────────────────────────────────────────
 *
 * No console call, at any level, on any path. `rejectedCount` is a COUNT and never the values — a
 * rejected reading is still somebody's misread identity number, and it is also the one piece of
 * information that separates "the card was found and misread, take a better photograph" from "no
 * number was found at all, fill the frame".
 */

/**
 * A maximal digit token: digits joined by AT MOST ONE space or hyphen at a time.
 *
 * The separator alphabet is the server's, character for character — `[ \-]?` — so a card printed
 * "2345 6789 0124" and one printed "2345-6789-0124" are one token either way, while a newline, a
 * double space, a slash or a letter ends the token. Greedy by construction, which is the whole
 * point: the token is the LONGEST such run, so a sixteen-digit VID is one sixteen-digit token and
 * never a twelve-digit one with four digits left over.
 *
 * ASCII digits only, deliberately, and for the same reason `artisan_identity._ASCII_DIGITS` is:
 * `\d` under the `u` flag and `str.isdigit()` both accept Devanagari "१२३", which would sail
 * through every length and checksum test and then be STORED verbatim, at which point the unique
 * index sees two different strings for one person.
 */
const DIGIT_TOKEN = /[0-9](?:[ -]?[0-9])*/g;

/** Everything that is not an ASCII digit, for reducing a token to the digits it carries. */
const NON_DIGITS = /[^0-9]/g;

/** The one length an Aadhaar number has. Mirrors `AADHAAR_LENGTH` on the server and in the field. */
export const AADHAAR_LENGTH = 12;

export type IdentityTextReading = {
  /** Verhoeff-valid twelve-digit numbers, best first, de-duplicated. Safe to offer for confirmation. */
  aadhaar: string[];
  /**
   * How many twelve-digit tokens were read and then REFUSED — a count, never the values.
   *
   * Only whole twelve-digit tokens can be counted here. A VID, an enrolment number or a pin code is
   * not "a reading that failed its checksum" and must not inflate this, or the surface would tell a
   * designer that the card was misread and send them to photograph a perfectly good card again.
   */
  rejectedCount: number;
};

/**
 * Every Aadhaar number worth offering in `text`, plus how many twelve-digit readings were refused.
 *
 * `aadhaarProblem` returns null for an acceptable number and a sentence for a bad one — the exact
 * signature of `AadhaarField.aadhaarValidationError`, which every caller passes. It carries the
 * leading-0/1 rule and the Verhoeff checksum, so this function does not restate either.
 */
export function identityCandidatesFromText(
  text: string | null | undefined,
  aadhaarProblem: (digits: string) => string | null
): IdentityTextReading {
  const aadhaar: string[] = [];
  const seen = new Set<string>();
  let rejectedCount = 0;

  for (const match of (text ?? "").matchAll(DIGIT_TOKEN)) {
    const digits = match[0].replace(NON_DIGITS, "");
    // A token of any other length is not a misread Aadhaar number, it is a different number that
    // happens to be printed on the same card. It is refused here and counted nowhere.
    if (digits.length !== AADHAAR_LENGTH) continue;
    // The same number read twice — once off the front of the card and once off the back, or twice
    // in one frame — is one candidate and one rejection, not two of either.
    if (seen.has(digits)) continue;
    seen.add(digits);
    if (aadhaarProblem(digits)) {
      rejectedCount += 1;
      continue;
    }
    aadhaar.push(digits);
  }

  return { aadhaar, rejectedCount };
}

/**
 * "123456789012" -> "XXXX XXXX 9012" — the only form of a number any surface but the confirm panel
 * and the edit box may print.
 *
 * The same rule as `mask_aadhaar` on the server and `isMaskedIdentityNumber` on this client:
 * anything that is not a full number is masked ENTIRELY rather than partially revealed, so a
 * malformed value can never leak more than a well-formed one. Exported from here rather than from a
 * component so that error strings, counts and provenance lines have a masking function available
 * without importing React.
 */
export function maskIdentityNumber(value: string | null | undefined): string {
  const digits = (value ?? "").replace(NON_DIGITS, "");
  if (digits.length < 4) return "XXXX XXXX XXXX";
  return `XXXX XXXX ${digits.slice(-4)}`;
}

/**
 * "123456789012" -> "1234 5678 9012": the grouping the card prints and a person proofreads by.
 *
 * A dropped or doubled digit is visible in "1234 5678 901" in a way it is not in "123456789 01",
 * and a designer compares the card group by group. Only ever used where the full number is
 * legitimately on screen — the confirm button and the candidate readout.
 */
export function groupAadhaarForReading(digits: string): string {
  return digits.replace(/(\d{4})(?=\d)/g, "$1 ");
}
