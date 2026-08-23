/**
 * The two record-column derivations, in the browser: an artisan's AGE and their YEARS OF EXPERIENCE.
 *
 * WHY THIS FILE EXISTS AT ALL, given that the server already computes both. Neither number is
 * stored anywhere — `Artisan` holds `dateOfBirth` and `craftStartDate`, and `records.derive_age` /
 * `records.derive_experience_years` turn them into numbers on every read, which is the whole point:
 * a number written down is right on the day it is typed and silently wrong from then on, and
 * nothing in this system would ever say so. But a derivation that only happens on the server is a
 * number the researcher cannot see while they are filling the form in: they type a birthday, look
 * for the age the workshop is going to print, and find nothing. So the form shows it, and this is
 * the rule it shows it by.
 *
 * IT IS A PORT AND NOT A SECOND OPINION, and that distinction is the reason this file is worth
 * having rather than a two-line subtraction at the call site. `services/records.py` is the one
 * definition; every behaviour below is copied from it deliberately, including the ones that look
 * like edge-case fussiness:
 *
 *   * THE ANNIVERSARY CORRECTION IS SPELLED OUT, not divided. The server's comment says why —
 *     `(today - born).days // 365` drifts a day every four years and reports somebody as a year
 *     older than they are for a few days around their birthday, "which is exactly the kind of
 *     wrongness nobody checks".
 *   * NULL, NEVER 0, for a missing, unparseable, future or out-of-band date. "A blank box and 'zero
 *     years old' are different statements, and the second is one this repository would be making
 *     up." Zero experience, by contrast, is a REAL answer — an apprentice in their first month —
 *     which is why every reader of these values tests for null rather than for truthiness.
 *   * THE BANDS DIFFER, AND THAT IS LOAD-BEARING. Age is 0..130; experience is 0..90, because
 *     `participant.experienceYears` declares `min_value=0, max_value=90` and `ArtisanCreate`
 *     declares `ge=0, le=90`, and a value outside that range is not one the workshop can carry —
 *     `validate_entry` re-coerces every field on every save, so an out-of-range hydrated number
 *     becomes a refused answer on a box nobody typed in. Dropping it instead leaves the stated
 *     number and the legacy metadata behind it still readable.
 *
 * WHY IT IS NOT IN `lib/derivedFields.ts`, which is also a port of a server derivation. That file
 * interprets the design-workshop registry's `derivedKind`/`derivedFrom` — a field computing itself
 * from OTHER FIELDS ON THE SAME ENTRY, declared in the registry and served over the schema
 * endpoint. These two compute from a COLUMN ON A RECORD, are not declared anywhere a client can
 * read, and are needed on the record pages, which know nothing about the registry. Its date parser
 * is not reused for the same reason it is not exported: it produces a UTC-midnight `Date` so a
 * duration can be measured in days, and both derivations here must instead compare CALENDAR
 * COMPONENTS, because a day count is precisely the arithmetic the server refuses to use.
 *
 * THE REFERENCE DAY IS THE UTC ONE, matching `datetime.now(UTC).date()` on the server. It costs a
 * one-day window (a browser in IST between midnight and 05:30 computes against yesterday's UTC
 * date), and it is still the right choice: only a birthday or a joining anniversary falling inside
 * that window is affected, and the alternative is a form that shows one age and a report that
 * prints another. The report is the document that gets filed, so the form agrees with it. (That the
 * repository derives calendar dates in UTC at all is a wider open question — `services/public.py`
 * and `dictation_cap.ist_day` both argue for IST — and it belongs to `_iso_date`, not here: this
 * port's job is to say the same thing as the server, not to be the one place that disagrees.)
 */

/**
 * The year, month and day written in an ISO date or datetime, or null when it is not one.
 *
 * THE SERVER PARSES THE WHOLE STRING (`datetime.fromisoformat`, having swapped a trailing "Z" for
 * "+00:00") and then takes `.date()`, so the components are the ones WRITTEN IN THE STRING — a zone
 * suffix qualifies a time neither derivation ever looks at. Both spellings a client can produce are
 * accepted: the bare "1994-03-12" a date field submits, and the "1994-03-12T00:00:00Z" the API
 * returns for a `DateTime` column. The basic form ("19940312") is accepted for the one reason
 * `lib/derivedFields.ts` accepts it — Python's parser does, so refusing it here would be a
 * divergence rather than a simplification.
 *
 * IT MATCHES THE WHOLE STRING RATHER THAN THE FIRST TEN CHARACTERS, and that is not fussiness: a
 * ten-character slice reads "1994-03-12nonsense" as a date, where `fromisoformat` raises and the
 * server answers null. Two spellings `fromisoformat` also takes are deliberately NOT handled — the
 * week form "2026-W33-4" and the ordinal form "2026-227" — because no date control on any of the
 * three clients can produce one, and growing an ISO-8601 parser here to cover them would be this
 * file writing its own opinion instead of porting one. `e2e/record-derivations-unit.spec.ts` pins
 * the difference so it is a known edge rather than a surprise.
 */
function isoParts(value: string | null | undefined): [number, number, number] | null {
  const text = value === null || value === undefined ? "" : String(value);
  const match =
    /^(\d{4})-(\d{2})-(\d{2})(?:[T ].*)?$/.exec(text) ?? /^(\d{4})(\d{2})(\d{2})(?:[T ].*)?$/.exec(text);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  // THE COMPONENTS ARE CHECKED BACK, which is the part a `Date` constructor will not do: `Date.UTC`
  // ROLLS OVER, so "1994-02-30" is not an error to it — it is the 2nd of March, and this would have
  // returned a confident number for a date the server answers null for. A date nobody can be at is
  // not a date.
  const probe = new Date(Date.UTC(year, month - 1, day));
  if (
    Number.isNaN(probe.getTime()) ||
    probe.getUTCFullYear() !== year ||
    probe.getUTCMonth() !== month - 1 ||
    probe.getUTCDate() !== day
  ) {
    return null;
  }
  return [year, month, day];
}

/**
 * Whole years between `from` and `on`, by the calendar. Shared by both derivations below, because
 * the server's two functions differ in exactly one respect — the band — and nothing else.
 */
function wholeYearsSince(from: string | null | undefined, on: Date): number | null {
  const parts = isoParts(from);
  if (!parts) return null;
  const [year, month, day] = parts;
  // The birthday-not-yet-reached correction, component by component, exactly as the server writes
  // it. `<` on the [month, day] pair in Python is this comparison; JavaScript has no tuple order,
  // so it is written out.
  const reached = on.getUTCMonth() + 1 > month || (on.getUTCMonth() + 1 === month && on.getUTCDate() >= day);
  return on.getUTCFullYear() - year - (reached ? 0 : 1);
}

/** Today, as the server's `datetime.now(UTC).date()` sees it. */
function utcToday(): Date {
  return new Date();
}

/**
 * The artisan's age in whole years, or null when there is no usable date of birth.
 *
 * `on` exists for the reason it exists on the server: "an age function tested against `now()` passes
 * in March and fails in September, on the birthday of whatever fixture it uses."
 */
export function deriveAge(dateOfBirth: string | null | undefined, on?: Date): number | null {
  if (!dateOfBirth) return null;
  const years = wholeYearsSince(dateOfBirth, on ?? utcToday());
  return years !== null && years >= 0 && years <= 130 ? years : null;
}

/**
 * Whole years practising, from the date the artisan began, or null when there is no usable date.
 *
 * The 0..90 band is `participant.experienceYears`' own — see the file header for why an
 * out-of-band value must be dropped here rather than shown and then refused by the workshop.
 */
export function deriveExperienceYears(craftStartDate: string | null | undefined, on?: Date): number | null {
  if (!craftStartDate) return null;
  const years = wholeYearsSince(craftStartDate, on ?? utcToday());
  return years !== null && years >= 0 && years <= 90 ? years : null;
}
