/**
 * The languages an interview can be conducted in — ONE list, read by both clients.
 *
 * ── WHY THIS FILE EXISTS RATHER THAN AN ARRAY LITERAL ON THE PAGE ───────────────────────────────
 *
 * Android has shipped a Language DROPDOWN on the questionnaire form since it was written; the web
 * shipped a free `<TextInput placeholder="Bangla, Hindi, English...">`. Two researchers on the two
 * clients therefore wrote the same fact three ways — "Bangla", "Bengali", "bengali" — into a column
 * that `/data`, the consolidated questionnaire and every export group by. Closing that meant giving
 * the web the same closed vocabulary, and a vocabulary written down twice is the failure mode this
 * repository has already paid for more than once (see the guide's step count, SKILL.md §9.9). So the
 * list lives here, the page imports it, and the only other copy is Kotlin's.
 *
 * ── THE ORDER IS THE ANDROID ORDER AND IT IS NOT ALPHABETICAL ───────────────────────────────────
 *
 * Read verbatim on 2026-08-28 from
 * `android/app/src/main/java/com/designprototype/workshop/MainActivity.kt:13624-13635`
 * (`val languageOptions = remember(language) { ... }`, immediately under the "Place" field). Its own
 * comment states the intent: "Hindi primary, then English + the major scheduled Indian languages".
 * Hindi and English lead because they are what most interviews are actually conducted in; the rest
 * follow in the Eighth-Schedule-ish order Android chose; "Other" is last because it is the escape
 * hatch, not a language.
 *
 * Re-check the two lists agree with:
 *   grep -n "languageOptions" -A 12 android/app/src/main/java/com/designprototype/workshop/MainActivity.kt
 *
 * SORTING THIS ALPHABETICALLY WOULD BE A REGRESSION, not a tidy-up: it would bury Hindi and English
 * at H and E among twenty-two others and make the common case the slowest to reach on both clients.
 */

/**
 * The twenty-four options, in Android's order.
 *
 * `readonly` and exported as a value rather than a type: callers map over it, and the interview page
 * needs `.length` to reason about `SEARCH_THRESHOLD` without re-counting it in a comment.
 */
export const INTERVIEW_LANGUAGES: readonly string[] = [
  "Hindi",
  "English",
  "Bengali",
  "Marathi",
  "Telugu",
  "Tamil",
  "Gujarati",
  "Urdu",
  "Kannada",
  "Odia",
  "Malayalam",
  "Punjabi",
  "Assamese",
  "Maithili",
  "Sanskrit",
  "Konkani",
  "Nepali",
  "Manipuri (Meitei)",
  "Bodo",
  "Dogri",
  "Kashmiri",
  "Santali",
  "Sindhi",
  "Other"
] as const;

/**
 * What the trigger reads while nothing is chosen. Android's own `placeholder = "Select language"`
 * (MainActivity.kt:13640), copied verbatim per SKILL.md §1.3 — Android owns the wording.
 *
 * There is deliberately NO "None" / empty option in the list (Android passes `includeNone = false`).
 * An unanswered language is the ABSENCE of a value, and an option that means "no answer" is a thing
 * a researcher can pick on purpose and then cannot tell apart from never having reached the field.
 */
export const INTERVIEW_LANGUAGE_PLACEHOLDER = "Select language";

/** One row of the Language dropdown. Structurally `SelectOption`, without importing a UI type into `lib/`. */
export type InterviewLanguageOption = { value: string; label: string };

/**
 * The options to draw, given whatever language the record already carries.
 *
 * ── THE PRESERVE RULE, AND WHY IT IS LOAD-BEARING ───────────────────────────────────────────────
 *
 * Every interview saved before this dropdown existed holds free text: "Bangla", "Hindi/English",
 * "Kutchi", a dialect nobody put on a list. A dropdown whose options do not include that value shows
 * its placeholder — the field reads as never answered — and the next save of that record writes the
 * empty string over a fact somebody wrote down in a courtyard. So the stored value is offered as its
 * own option, at the FRONT (Android: `listOf(language) + base`), where it is the first thing read
 * rather than something to hunt for at position twenty-five.
 *
 * ── ONE DELIBERATE DIVERGENCE FROM ANDROID, AND IT IS A FIX ─────────────────────────────────────
 *
 * Android's predicate is `base.none { it.equals(language, ignoreCase = true) }` — case-INSENSITIVE —
 * while the control that then looks the value up compares case-SENSITIVELY
 * (`SearchableSelect.kt:217`, `options.firstOrNull { it.value == selectedValue }`). A record holding
 * "hindi" therefore satisfies neither side: it is not prepended (it matches ignoring case) and it
 * does not match any option (it differs by case), so the handset draws "Select language" over a
 * record that has one. This function compares EXACTLY, so "hindi" is prepended and the control tells
 * the truth about what is stored.
 *
 * Canonicalising instead — quietly swapping the stored "hindi" for "Hindi" — was rejected: it edits
 * a record on the way past without asking, and the whole point of this function is that nothing
 * rewrites what a researcher wrote.
 *
 * @param current the language already on the record, or null/undefined/"" for a new one.
 */
export function interviewLanguageOptions(current?: string | null): InterviewLanguageOption[] {
  const base = INTERVIEW_LANGUAGES.map((language) => ({ value: language, label: language }));
  // Whitespace-only is "not answered", the same as empty — trimmed for the TEST but never for the
  // VALUE: prepending the trimmed form would make the option and the stored string different
  // strings, and the control would go back to matching nothing.
  const existing = current ?? "";
  if (!existing.trim()) return base;
  if (INTERVIEW_LANGUAGES.some((language) => language === existing)) return base;
  return [{ value: existing, label: existing }, ...base];
}
