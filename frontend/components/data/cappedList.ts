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
 * WHAT THE 2026-08-29 PASS ADDED, and it is a vocabulary rather than a feature. Once a control's
 * search box reaches the SERVER (DROPDOWN_DESIGN §2.8), one page of a list stops being one fact and
 * becomes three that a reader cannot tell apart unless the screen tells them: *there are 400 and
 * you are seeing 80*, *nothing matched what you typed*, and *there are none at all*. `CutReach`
 * gains `"search"` for the first; `searchCutNotice` holds all three and refuses to word the third
 * itself; `CUT_NOTICE_LIVE_REGION` is what makes any of them audible. Everything that was here
 * before renders byte for byte what it rendered before — the new arm is opt-in and the new
 * arguments default to what the callers already assumed.
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
 * - `"search"`: there is a box, and **the term it holds goes into the request**. Typing therefore
 *   reaches rows this page never held. Added 2026-08-29 with the unified select
 *   (DROPDOWN_DESIGN §2.8); before that it was deliberately absent, and the note below is the
 *   condition that made it absent rather than a preference that changed.
 *
 * THE ARM NOW EXISTS, AND WHAT QUALIFIES A CALLER FOR IT IS ONE QUESTION: does the term reach the
 * SERVER? Not "is there a box" — sixteen controls in this app had a box that filtered the array it
 * was handed, and over one server-truncated page that box answers "No matches" about records that
 * exist. Writing "type in the box above to reach the rest" over such a box is the same lie one
 * layer down, and it is precisely the mistake the design-workshop viewer picker's notice was fixed
 * for on 2026-08-13 (353 eligible accounts invisible, indistinguishable from never-empanelled
 * colleagues). So the test, and the only test:
 *
 *   - the control passes `SearchableSelectProps.serverQuery` (`components/ui/SearchableSelect`), or
 *   - the page owns the box itself and folds its value into the list request (`?search=…`), the
 *     shape `/settings/tasks` and the two admin rosters have —
 *
 * **and the page asks the route for `RENDER_CAP` rows, not the server's ceiling**, so the fetch and
 * the render are cut at one number and two truncation sentences with two different totals cannot
 * both be true (`components/ui/selectFilter.ts`, DROPDOWN_DESIGN §2.8 rule 1).
 *
 * A control that fails that test keeps `"none"`, whose sentence ends by saying flatly that typing
 * here searches only what is shown. **`"search"` on a locally-filtered box is worse than saying
 * nothing**: it is the one sentence on screen whose whole job is to describe this list, and a
 * reader who follows it, types a real record's name and is told there are no matches concludes the
 * record does not exist. That is the defect this entire module was written to close, re-entered
 * through the fix.
 *
 * `"search"` alone is the sentence for a reader who has NOT typed yet — it points at the box. A
 * caller that holds the live term must use {@link searchCutNotice} instead, which picks between
 * that sentence and the two the term makes possible: telling somebody to search when they already
 * have is how a picker teaches a reader that searching does not work.
 */
export type CutReach = "none" | "pager" | "search";

/**
 * THE ONE SENTENCE UNDER A CAPPED LIST, or "" when the screen must say nothing.
 *
 * Five states, ordered so the impossible-looking one is tested first, exactly as
 * `eligibleViewerNotice` orders its own:
 *
 * 1. **Nothing loaded although the server says rows exist.** Not reachable from a picker today —
 *    page one of a non-empty list always holds rows — but it is reachable the moment a caller
 *    passes a `page` past the end, and it is the state where silence does the most damage: the
 *    control renders "No entries for this type" over a repository holding hundreds. It gets its own
 *    words, and it never tells the reader to search or to page, because neither would help.
 * 2. **Cut, with a pager on screen.** Say the arithmetic and point at the pager.
 * 3. **Cut, with a box whose term reaches the server.** Say the arithmetic and point at the box —
 *    and say that the rest are searched on the SERVER, because a reader who has met the sentence
 *    below on sixteen other controls has been taught that a box in this app only sifts what is
 *    already drawn. See `CutReach`'s header for what earns a caller this arm.
 * 4. **Cut, with no way past it from here.** Say the arithmetic and say plainly that typing in this
 *    box searches only what is shown — otherwise the empty result of that typing reads as a fact
 *    about the repository, which is the entire defect.
 * 5. **Not cut.** Silence.
 *
 * EVERY ARM POINTS AT A CONTROL, and that is a rule rather than a courtesy: a cap notice with no
 * next action tells the reader they have a problem and not what to do about it, which leaves them
 * exactly where the silent version did — except now they know to distrust the screen. Arm 1 is the
 * one exception and it is honest about it: nothing on screen reaches those rows, so it names none
 * and says instead what the reader would otherwise wrongly conclude.
 *
 * The numbers are always both printed. "Showing the first 100" alone still leaves the reader
 * guessing whether that is most of the corpus or an eighth of it, and the difference is whether
 * they go looking elsewhere or conclude the record was never created.
 *
 * `reach` DEFAULTS TO `"none"`, which is the arm every one of this module's callers had before the
 * `"search"` arm existed: one page, a box that filters it locally, no second page. The default is
 * what keeps this change additive — of the 19 `<CappedListNotice>` sites in the app, 18 pass no
 * `reach` at all and the nineteenth passes `"pager"` (View Data's browse panel), so every one of
 * them renders the byte-identical sentence it rendered before. Only a caller that has actually
 * wired its term into the request opts in.
 */
export function cappedListNotice(cut: ListCut | null, reach: CutReach = "none"): string {
  if (!cut) return "";
  if (cut.loaded === 0) {
    return `None of the ${cut.total} ${cut.noun} could be listed here — this is not an empty repository.`;
  }
  if (reach === "pager") {
    return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — use the pager to reach the rest, which are not searched by the box above.`;
  }
  if (reach === "search") {
    // The wording is DROPDOWN_DESIGN §2.8's, byte for byte, because the design doc is what three
    // other parcels and their specs are written against and a paraphrase here would fail them for
    // no reason a reader could see. "which are searched on the server" is the load-bearing half:
    // "type in the box above" alone is what every locally-filtering picker in this app could
    // truthfully have said, so it is not the clause that tells this box apart from those.
    return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — type in the box above to reach the rest, which are searched on the server.`;
  }
  return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} — the other ${cut.total - cut.loaded} are not on this list, and typing here searches only the ${cut.loaded} shown.`;
}

/**
 * THE WHOLE VOCABULARY OF A LIST WHOSE BOX GOES TO THE SERVER — three sentences that must never
 * collapse into two, and the caller's own fourth left untouched.
 *
 * THE THREE, AND WHY THE DISTINCTION IS THE POINT OF THE FUNCTION:
 *
 * 1. **"There are 400 and you are seeing 80."** A cut. The rest exist and the box reaches them.
 * 2. **"Nothing matched what you typed."** Not a cut. The corpus is full, this term is not in it —
 *    and because the term went to the SERVER, that is finally a statement about the whole list
 *    rather than about the rows this page happened to hold.
 * 3. **"There are none at all."** Not a cut either, and not about the term. A different fact with a
 *    different next move, and the only one of the three this module refuses to word itself (below).
 *
 * Collapsing any two of them is the defect, and each collapse has already shipped somewhere in this
 * repository. (1) into (2) is a filter box over a server-truncated page answering "No matches"
 * about records that exist — sixteen controls, and the 353 invisible accounts of 2026-08-13. (2)
 * into (3) is `"No crafts available."` (`MainActivity.kt:10125`): a claim about the repository made
 * from a read that was actually a query with a term in it. (3) into (2) is the mirror, and it sends
 * an admin hunting for a record that was never created.
 *
 * WHY THIS IS A FOURTH FUNCTION AND NOT A FOURTH `CutReach` VALUE — the same shape of argument
 * `flagCutNotice` and `queueCutNotice` make above, and here it is forced rather than chosen.
 * `cappedListNotice` takes `ListCut | null`, and `null` is the whole of "this list was not cut": it
 * carries no `loaded`, no `noun`, no numbers at all. But sentences 2 and 3 live exactly there — at
 * `loaded === 0` with nothing cut — so a `reach: "search"` on the existing signature could not tell
 * them apart even in principle. It would have to answer "" to both, which is the collapse itself.
 *
 * WHY `emptyLabel` IS THE CALLER'S AND IS NEVER COMPOSED WITH ANYTHING HERE. Sentence 3 is not one
 * sentence, it is six (DROPDOWN_DESIGN §3.5): bundled, cached-and-stale, empty-because-offline,
 * could-not-be-listed, genuinely-empty-scoped and genuinely-empty-unscoped. `"No workshops are open
 * to this account"` is a statement about a scope whose next move is an administrator;
 * `"No workshops have been recorded yet"` is a statement about the repository whose next move is to
 * create one; and either one printed over a read that FAILED is a lie about the repository dressed
 * as an observation. This function knows none of that — not whether the fetch succeeded, not
 * whether the list is scoped, not whether the device has ever been online — so it prints what the
 * caller hands it and nothing else. `selectFilter.ts::emptyListSentence` draws the same line at the
 * panel layer for the same reason, and the two together are why "the control must say WHICH it is
 * doing" can be satisfied at all.
 *
 * EVERY ARM POINTS AT A CONTROL. Arm 1-without-a-term points at the box; arm 1-with-a-term points
 * at narrowing what is already in it, because telling somebody to search when they have already
 * searched is how a picker teaches a reader that searching does not work (`flagCutNotice` makes the
 * identical two-branch split and this is deliberately its twin); arm 2 points at clearing the box,
 * which is the only move that gets the list back.
 *
 * RETURNS A STRING, so it goes straight into `<CappedListNotice cuts={[…]} />` beside
 * `flagCutNotice` — `""` is dropped there exactly as `null` is, so no caller writes its own `&&`
 * and no decision leaks into JSX. It must be drawn inside {@link CUT_NOTICE_LIVE_REGION}: the
 * sentence changes as the reader types, and a sentence that changes where nothing announces it is
 * a sentence only sighted readers get.
 */
export function searchCutNotice({
  noun,
  loaded,
  total,
  term,
  emptyLabel = "",
  pending = false
}: {
  /** Plural noun for the records, lower case, as it should read mid-sentence — `ListCut`'s. */
  noun: string;
  /** Rows this client holds. `result.items.length`, never `pageSize` — a short last page is not a cut. */
  loaded: number;
  /** What the server says exists UNDER THIS TERM, because the term went into the request. */
  total: number;
  /** The term the request was made with — the applied one, not a keystroke that has not been sent. */
  term: string;
  /**
   * Sentence 3, the caller's, for `loaded === 0` with nothing typed.
   *
   * **Defaults to `""` — silence — and that default is what reproduces today's behaviour**: before
   * this function existed a caller in that state got `cappedListNotice(null)`, which is `""`, and
   * drew its own `EmptyState` or its picker's `emptyLabel` above. A caller that keeps doing so
   * passes nothing here and nothing changes. One that wants this line to carry the sentence passes
   * the §3.5 wording it has decided on.
   */
  emptyLabel?: string;
  /**
   * A request for this term is outstanding.
   *
   * **Defaults to `false` — "the answer in hand is the answer" — which is what every caller of this
   * module has always assumed.** Pass the real flag on any debounced box: an empty list mid-flight
   * is not an empty list, and "No designers match “ravi”" printed over a request that has not come
   * back is the most-repeated bug class in this repo arriving through a door that is a second and a
   * half wide on the connections this app is built for. Pending prints nothing at all rather than a
   * "Searching…" of its own, because the page already owns a loading state and two of them in one
   * column is how a reader learns to read neither.
   */
  pending?: boolean;
}): string {
  if (pending) return "";
  const trimmed = term.trim();
  // `cutOf` and not a `>` in this function: it carries the `Number.isFinite` guard, so a deployment
  // that does not send `total` yet reads as "not cut" rather than as a cut of NaN.
  const cut = cutOf(loaded, total, noun);
  // Nothing loaded although the server says rows match. `cappedListNotice`'s first arm owns those
  // words and owns them for both functions — it is the one state where no control on screen helps,
  // so it names none, and a second wording of it here would be a fifth sentence for a fact that
  // already has one. Deliberately BEFORE the term arm: with `total > 0` the server has said these
  // records match, so "No X match" would be flatly false.
  if (cut && cut.loaded === 0) return cappedListNotice(cut);
  if (cut) {
    if (trimmed) {
      return `Showing ${cut.loaded} of ${cut.total} ${cut.noun} matching “${trimmed}” — narrow the search above to reach the rest.`;
    }
    // One sentence, one place. §2.8's wording lives in `cappedListNotice` and is reached from here
    // so that a page and its picker cannot describe one cut two ways.
    return cappedListNotice(cut, "search");
  }
  // Not cut and rows on screen: the list is whole. Silence is the answer, and the reason it is worth
  // stating is that a standing note about searching under every complete list is what makes the
  // sentence above mean nothing when it does appear.
  if (loaded > 0) return "";
  if (trimmed) {
    return `No ${noun} match “${trimmed}” — the search ran on the server, over the whole list and not only the rows this page had loaded. Clear the box to see the list again.`;
  }
  return emptyLabel;
}

/**
 * THE ATTRIBUTES THAT MAKE ANY SENTENCE IN THIS MODULE AUDIBLE — spread onto a wrapper that is
 * MOUNTED BEFORE IT HAS ANYTHING TO SAY.
 *
 * WHY THIS IS EXPORTED FROM THE DECIDING MODULE RATHER THAN LEFT TO EACH SCREEN. Assistive
 * technology announces mutations inside a region that ALREADY EXISTED when the page settled. A
 * region created in the same moment as its first sentence announces nothing at all — that is not a
 * theory, it is what `EntityForm`'s cap notice did until its `role="status"` was moved off the
 * amber box and onto a wrapper that never unmounts, and it is why `Toast` renders its viewport with
 * no toasts in it and why `DeletedWorkshopsCard` mounts an empty `<div aria-live="polite">`.
 *
 * **`CappedListNotice` returns `null` when there is nothing to say, so it can never be the region
 * itself.** That is correct — an empty box under every complete picker is padding this UI has twice
 * been asked to lose — and it is exactly what makes this constant necessary: the region has to be
 * the caller's wrapper, with the notice inserted into it.
 *
 *     <div {...CUT_NOTICE_LIVE_REGION}>
 *       <CappedListNotice cuts={[searchCutNotice({ … })]} />
 *     </div>
 *
 * The wrapper carries no styling and no margin of its own, so an empty one occupies nothing.
 *
 * `role="status"` (polite) and not `alert`: nothing is broken and nothing has been lost. These
 * sentences say what is not on screen, and interrupting somebody mid-word over a list that stopped
 * at eighty is not warranted.
 *
 * `role="status"` implies `aria-atomic`, so the whole region is re-read whenever any sentence in it
 * changes — which is why ONE region belongs to ONE control and its notice, and why a screen with
 * two independent lists gives each its own. It is also why these numbers are allowed to live in a
 * live region at all while a scroll-position readout is not (`SKILL.md` §17): they change only when
 * the reader has just typed, paged or filtered, and what is being announced is the consequence of
 * the act they performed.
 */
export const CUT_NOTICE_LIVE_REGION = {
  role: "status",
  "aria-live": "polite"
} as const;

/**
 * THE SAME SENTENCE FOR A CUT THE SERVER REPORTS AS A FLAG RATHER THAN A TOTAL — the `"search"`
 * arm's twin for a route that cannot count.
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
 * WHY TELLING THE READER TO SEARCH IS LEGITIMATE HERE, which is the test `CutReach`'s `"search"`
 * arm now states in general and which this function stated first. `CutReach` refuses to write
 * "search to reach the rest" over a `SearchableSelect` whose box filters the array it was handed —
 * the same lie one layer down. This notice is drawn on `/settings/tasks`, where the term goes into
 * the request: the page holds a `search` box of its own, folds it into
 * `GET /tasks/options?search=…`, and the server puts it in the WHERE beside the `take`. So a reader
 * told to search can actually reach a colleague who sorts past the cut. If somebody later points
 * this function at a picker whose box filters locally, the sentence becomes the defect it was
 * written to close — check the request before reusing it. Unchanged by the `"search"` arm landing:
 * that arm gave the same permission to a route that reports a TOTAL, and this one is still the only
 * answer where the route reports a boolean.
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
