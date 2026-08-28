/**
 * ONE PAGE OF A LIST, RENDERED AS THOUGH IT WERE THE WHOLE LIST — and the sentence that stops it.
 *
 * WHY THIS FILE EXISTS, because it is the whole point of it.
 *
 * Every list route in this application answers `{ items, total, page, pageSize, pages }` and clamps
 * `pageSize` to `MAX_PAGE_SIZE = 100` (`backend/app/services/pagination.py`). Eleven call sites
 * across the funnel, the record forms, the View Data browser and the /media upload form asked for
 * `pageSize: 100`, kept `.items`, and threw `total` away. A hundred is not a generous default that
 * somebody forgot to raise — it is the ceiling, so those lists could not be widened from the client
 * even in principle, and none of them said anything at all about the cut.
 *
 * THE CEILING IS NOT THEORETICAL AND IT IS NOT CLOSE. Counted against this repository's Postgres
 * (127.0.0.1:55442) on 2026-08-15, every table behind a picker is past it, most of them by an order
 * of magnitude:
 *
 *     MediaFile 2530 · ProductDocumentation 878 · Artisan 749 · Workshop 196 · Craft 178 ·
 *     ToolDocumentation 177 · Process 177 · QuestionnaireInterview 0
 *
 * So the artisan picker offered the newest 100 of 749 and the researcher hunting for one of the
 * other 649 was shown, in the ComboBox they typed the name into, an empty result. **"Not in this
 * list" and "not in the repository" rendered identically**, which is this codebase's single most
 * repeated bug class and the one the design-workshop viewer picker already paid for once — 353
 * eligible accounts invisible in a picker, indistinguishable from colleagues who had never been
 * empanelled (docs/OPEN_FINDINGS.md, closed 2026-08-13).
 *
 * Note the last two entries. Workshops (196) and crafts (178) were the two lists people assumed
 * were "small enough not to matter" — the audit itself wrote that the workshop dropdown "bites once
 * the repository passes 100 workshops". It has. QuestionnaireInterview at 0 is the reason no
 * assumption about size may be made from what a screen looks like today: the same picker is empty
 * on this database and cut on the next one.
 *
 * THE RULE THIS FILE ENFORCES is §1.10 of the frontend reference and the register's own standing
 * requirement: *a list that quietly stops is indistinguishable from a place with no records, so
 * every cap, truncation or skipped row must say so on screen.* This module is the one place that
 * decides whether there is anything to say and what the words are; the call sites hand it a
 * `PageResult` and render whatever comes back.
 *
 * WHY A PURE FUNCTION AND NOT A TERNARY IN EACH PANEL — the same argument
 * `lib/designWorkshopViewers.eligibleViewerNotice` and its Kotlin twin `dwViewerOfferNotice` make,
 * and this file deliberately copies their shape: one of the states below cannot be produced by any
 * live database, so a decision buried in JSX is only ever exercised by somebody looking at a
 * screen. Five sites also means five chances to word it differently, and two screens describing the
 * same cut in two different sentences is how a researcher learns that neither of them means much.
 */

import type { PageResult } from "@/lib/types";

/**
 * The largest page any list route in this application will serve.
 *
 * `normalize_pagination` does `min(page_size, MAX_PAGE_SIZE)` with `MAX_PAGE_SIZE = 100`
 * (`backend/app/services/pagination.py`), and every list route declares
 * `pageSize: int = Query(20, ge=1, le=100)` on top of that — so 100 is refused-past, not merely
 * defaulted. Exported so a call site asks for the ceiling by name rather than repeating the
 * literal, and so that the day the server raises it there is one number to change here and a
 * grep that finds every caller.
 *
 * **Raising this alone fixes nothing.** It moves the cut; it does not tell anybody where the cut
 * is. The notice below is the part that has to ship with it.
 */
export const LIST_PAGE_CEILING = 100;

/**
 * A list that stopped short of its own `total`, or `null` when it did not.
 *
 * `null` is the common answer and the whole point of the type: a complete list has nothing to
 * explain, and a standing note about pagination on every visit is padding these screens have twice
 * been asked for less of. Making "nothing to say" a distinct value rather than an empty string
 * keeps that decision here instead of in every caller's `&&`.
 *
 * `noun` is the plural the sentence is built around ("artisans", "media files"). It is the
 * caller's, not derived from the endpoint path, because the words on screen are Android's wording
 * and a route name is not a label.
 */
export type ListCut = {
  /** Plural noun for the records, lower case, as it should read mid-sentence. */
  noun: string;
  /** How many rows this client actually holds. */
  loaded: number;
  /** How many the server says exist under the same filters. */
  total: number;
};

/**
 * Was this answer cut, and by how much?
 *
 * Deliberately takes the WHOLE `PageResult` rather than two numbers: the defect being closed is
 * exactly that call sites reached for `.items` and dropped the envelope on the floor, so the
 * helper that fixes it should be the one that wants the envelope. Passing `result.items.length`
 * rather than `pageSize` is also load-bearing — a short final page is not a cut, and `pageSize`
 * would report one.
 *
 * Only ever called on a picker's single page. On a genuinely paged list (`Pagination` on screen,
 * `page` in the request) `loaded < total` is the normal state and says nothing interesting; those
 * callers pass `reach: "pager"` below so the sentence names the pager instead of implying the rest
 * are unreachable.
 */
export function listCut<T>(result: PageResult<T>, noun: string): ListCut | null {
  return cutOf(result.items.length, result.total, noun);
}

/**
 * The same question asked of two loose numbers, for a caller whose rows have already been mapped
 * out of their envelope (the View Data browser maps eight different record types into one row
 * shape). Exported rather than left as a private helper so nobody re-derives "is this cut" with a
 * `>` in a render — the `Number.isFinite` guard is the reason: `total` is a plain cast off the wire
 * (`apiFetch` does not parse a schema), and an older deployment that omits it must make the screen
 * say NOTHING rather than claim a cut of `NaN`.
 */
export function cutOf(loaded: number, total: number, noun: string): ListCut | null {
  const known = Number.isFinite(total) ? total : loaded;
  if (known <= loaded) return null;
  return { noun, loaded, total: known };
}

/**
 * How the rows past the cut can be got at — which changes the sentence, because a sentence that
 * tells somebody to do something impossible is worse than one that admits the limit.
 *
 * - `"none"`: this control holds one page and there is no second one. The ComboBox over it filters
 *   the array it was handed (`components/ui/SearchableSelect`), so typing cannot reach past the
 *   cut either. Every record picker in the web forms, the funnel's three dropdowns and the /media
 *   linked-entry picker are this.
 * - `"pager"`: a `Pagination` control is on screen and moving it re-requests from the server.
 *
 * There is deliberately no `"search"` arm yet. Giving these pickers the server-side `search=` all
 * the list routes already accept means threading a search term out of `SearchableSelect`, which is
 * a shared primitive this change does not own — see the follow-up note in the audit trail. Writing
 * "search to reach the rest" over a search box that only filters what is already loaded would be
 * the same lie one layer down, which is precisely the mistake the viewer picker's notice was fixed
 * for on 2026-08-13.
 */
export type CutReach = "none" | "pager";

/**
 * THE ONE SENTENCE UNDER A CAPPED LIST, or "" when the screen must say nothing.
 *
 * Four states, ordered so the impossible-looking one is tested first, exactly as
 * `eligibleViewerNotice` orders its own:
 *
 * 1. **Nothing loaded although the server says rows exist.** Not reachable from a picker today —
 *    page one of a non-empty list always holds rows — but it is reachable the moment a caller
 *    passes a `page` past the end, and it is the state where silence does the most damage: the
 *    control renders "No entries for this type" over a repository holding hundreds. It gets its own
 *    words, and it never tells the reader to search or to page, because neither would help.
 * 2. **Cut, with a pager on screen.** Say the arithmetic and point at the pager.
 * 3. **Cut, with no way past it from here.** Say the arithmetic and say plainly that typing in this
 *    box searches only what is shown — otherwise the empty result of that typing reads as a fact
 *    about the repository, which is the entire defect.
 * 4. **Not cut.** Silence.
 *
 * The numbers are always both printed. "Showing the first 100" alone still leaves the reader
 * guessing whether that is most of the corpus or an eighth of it, and the difference is whether
 * they go looking elsewhere or conclude the record was never created.
 */
export function cappedListNotice(cut: ListCut | null, reach: CutReach = "none"): string {
  if (!cut) return "";
  if (cut.loaded === 0) {
    return `None of the ${cut.total} ${cut.noun} could be listed here — this is not an empty repository.`;
  }
  if (reach === "pager") {
    return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — use the pager to reach the rest, which are not searched by the box above.`;
  }
  return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — the other ${cut.total - cut.loaded} are not on this list, and typing here searches only the ${cut.loaded} shown.`;
}

/**
 * THE SAME SENTENCE FOR A CUT THE SERVER REPORTS AS A FLAG RATHER THAN A TOTAL — and the one arm
 * `CutReach` above deliberately does not have, because here the search is real.
 *
 * WHY A SECOND FUNCTION RATHER THAN A THIRD `CutReach` VALUE. `cappedListNotice` prints arithmetic,
 * and arithmetic is the half of it that cannot be had from `GET /tasks/options`. That route is not
 * paginated: it reads `take=CAP + 1`, trims, and returns `assigneesTruncated` /
 * `workshopsTruncated` / `artisansTruncated` (backend/app/api/routes/tasks.py:1101-1140). A boolean
 * is all it knows, deliberately — counting 3632 accounts to print "of 3632" is a second query for a
 * number nobody acts on. Bending `ListCut` to carry `total: Infinity` and letting `cutOf` decide
 * would put a lie in the type that every other caller would then have to reason about; two
 * functions with two honest shapes is smaller than one function with an unknowable field.
 *
 * WHY THE `"search"` REACH IS LEGITIMATE HERE AND IS STILL BANNED ABOVE. The header on `CutReach`
 * refuses to write "search to reach the rest" over a `SearchableSelect`, whose box filters the array
 * it was handed — the same lie one layer down. This notice is drawn on `/settings/tasks`, where the
 * term goes into the request: the page holds a `search` box of its own, folds it into
 * `GET /tasks/options?search=…`, and the server puts it in the WHERE beside the `take`. So a reader
 * told to search can actually reach a colleague who sorts past the cut. If somebody later points
 * this function at a picker whose box filters locally, the sentence becomes the defect it was
 * written to close — check the request before reusing it.
 *
 * The two-branch wording matters. With no term typed, the reader has been shown a slice of the
 * roster and must be told the box is the way past it. With a term typed, the cut is a cut of the
 * MATCHES, and telling somebody to "search" when they already have is how a picker teaches a user
 * that searching does not work — the only useful instruction left is to narrow what they typed.
 */
export function flagCutNotice(truncated: boolean | undefined, noun: string, term: string): string {
  // `undefined` is the wire's shape on an older deployment: `apiFetch` casts, it does not validate,
  // so a field the server has not shipped yet arrives as `undefined` and must read as "nothing to
  // say" rather than as a cut. Same guard, same reason, as `cutOf`'s `Number.isFinite`.
  if (!truncated) return "";
  if (term.trim()) {
    return `More ${noun} match “${term.trim()}” than this list can hold, so some are not shown — narrow the search above.`;
  }
  return `There are more ${noun} than this list can hold, so some are not shown — search for a name above to reach them. The box in this picker only filters what is already listed.`;
}

/**
 * THE SENTENCE FOR A CUT NOTHING ON SCREEN CAN REACH PAST — the review queue, and the third honest
 * shape a cut comes in.
 *
 * WHY NEITHER ARM OF `CutReach` FITS, which is the only reason this is a third function. The
 * queue at `GET /review/pending` reads at most `cap` rows of each of SIX record types
 * (`backend/app/api/routes/review.py::PENDING_TAKE`), concatenates them and reports a real `total`
 * counted for the types that overflowed. So:
 *
 * - `"none"` ends "typing here searches only the N shown". There is no box to type in. That route
 *   takes no search parameter at all, and `list_pending_reviews`' own docstring says why.
 * - `"pager"` ends "use the pager to reach the rest". The pager on that page walks the rows already
 *   in the browser, so it reaches none of them. This is the worse of the two errors — it names a
 *   control that is on screen and does not do what the sentence says it does.
 *
 * What IS true there is that the per-source order is `createdAt desc`, so the rows behind the cap
 * are the OLDEST — the most overdue work is exactly what is missing — and the only thing that
 * brings them forward is deciding the ones on screen. That is an instruction the reader can act
 * on, which is the bar `CutReach`'s header sets and the reason the viewer picker's notice was
 * rewritten on 2026-08-13.
 *
 * `cap` is printed because it is the number the reader would otherwise have to infer from a
 * six-way sum that does not divide evenly, and it is the server's to change: it arrives on the
 * wire beside `truncated` rather than being repeated here.
 *
 * TAKES THE FLAG, LIKE `flagCutNotice` AND UNLIKE `cappedListNotice`, and the difference is not
 * stylistic. The server's `truncated` is the authority on whether the queue was cut — it is decided
 * by reading one row beyond the cap, not inferred from the arithmetic — so the flag decides, and
 * the numbers only decide which sentence. That also makes the call safe to hand straight in without
 * the caller writing its own `&&`, which is what keeps the decision in this module.
 *
 * Its Kotlin twin is `reviewQueueCutNotice` in
 * `android/app/src/main/java/com/designprototype/workshop/ui/ReviewQueueCopy.kt`, which must be
 * changed with it — the two clients are looking at one queue.
 */
export function queueCutNotice(truncated: boolean | undefined, cut: ListCut | null, cap: number): string {
  // `undefined` is the wire's shape on an older deployment: `apiFetch` casts, it does not validate.
  // Same guard, same reason, as `flagCutNotice`'s.
  if (!truncated) return "";
  // Arithmetic that would read as a contradiction is not printed. `total <= loaded` (which is what
  // a null `cut` means here) cannot happen beside a true flag from a server that sends both, and
  // `cap <= 0` is what a server predating the key sends; in either case the honest fallback is the
  // fact WITHOUT the numbers. Saying nothing at all would be the one unacceptable answer — the flag
  // said the list was cut, and an unstated cut is the defect this whole module exists to close.
  if (!cut || cap <= 0) {
    return "Some pending records are not shown — the queue holds a limited number of each record type, and the ones behind that limit are the oldest.";
  }
  // The server states that a cut answer can never carry an empty `items` — the cut is by count
  // alone, so a cut answer holds `cap` rows by construction. Handled anyway, and worded as
  // `cappedListNotice`'s own first arm is, because a reader meeting "Showing 0 of 340" with a cap
  // sentence after it would be reading a contradiction.
  if (cut.loaded === 0) {
    return `None of the ${cut.total} ${cut.noun} could be listed here — this is not an empty queue.`;
  }
  return (
    `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — the queue holds at most ${cap} of each ` +
    "record type, and the ones behind that limit are the oldest. They appear here as the queue is worked."
  );
}

/**
 * Add rows to a picker's option list without ever removing one — the other half of living with a
 * ceiling.
 *
 * A picker that can only hold one page has to be allowed to hold SEVERAL pages: the repository-wide
 * page it loaded at mount, the narrower page it fetched once a craft was chosen, and the single row
 * it looked up by id because the record being edited pointed at it. Those three overlap, arrive in
 * any order, and none of them is authoritative over the others.
 *
 * **Additive on purpose.** Replacing the array with the newest answer is what made an edit form
 * forget the artisan it was editing the moment a craft was picked, and these arrays are also handed
 * to `carryScope`, where a missing id is read as "this record is not reachable from this form" and
 * the carried prefill is dropped. A narrower list must therefore never be allowed to look like a
 * shorter world.
 *
 * First writer wins on a duplicate id, so a full row already on screen is not swapped for a
 * differently-shaped one mid-interaction.
 */
export function mergeById<T extends { id: string }>(previous: readonly T[], incoming: readonly T[]): T[] {
  if (incoming.length === 0) return previous as T[];
  const seen = new Set(previous.map((row) => row.id));
  const added = incoming.filter((row) => !seen.has(row.id));
  return added.length === 0 ? (previous as T[]) : [...previous, ...added];
}
