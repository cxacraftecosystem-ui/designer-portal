"use client";

/**
 * WHO AN INTERVIEW IS WITH — one control, one workshop scope, and the rules behind both.
 *
 * ── THE TWO DEFECTS THIS FILE CLOSES ─────────────────────────────────────────────────────────────
 *
 * Both were reported against the sibling field repository and both were true of THIS form too, in
 * the same two places, for the same two reasons. They were measured here before anything moved:
 *
 *   (1) THE PICKER OFFERED THE WHOLE REPOSITORY UNDER A NAMED WORKSHOP. `loadMeta()` ran exactly one
 *       request at mount — `listResource<Artisan>("/artisans", { pageSize: RENDER_CAP })` — with no
 *       workshop parameter of any kind, and never asked again when either workshop picker moved. So
 *       every artisan in the deployment was offered at every workshop, out of a list that stops at
 *       `RENDER_CAP` rows of an endpoint ordered `createdAt desc`, with nothing on screen saying so.
 *       `GET /artisans` has accepted `workshopId`, `designWorkshopId` AND the plural `workshopIds`
 *       since long before that code was written (`backend/app/api/routes/artisans.py`,
 *       `list_artisans`); nothing was blocking the filter but the absence of the parameter.
 *
 *   (2) "PRIMARY ARTISAN" WAS NEVER A THING THE DATABASE HAS.
 *       `model QuestionnaireInterviewArtisan` (`backend/prisma/schema.prisma`) is
 *       `@@id([interviewId, artisanId])` plus `createdAt`. There is no rank, no ordinal and no
 *       `isPrimary` column, and `artisan_set_key` on the server SORTS and DE-DUPLICATES the ids
 *       before anything is stored (`backend/app/api/routes/questionnaire.py`), so the order the
 *       client sends them in reaches nothing. The page already flattened its two controls into ONE
 *       de-duplicated array before every call it made — the shared-entry lookup and the create both
 *       took `artisanIds`. The split therefore bought nothing at all and cost a real thing: a
 *       researcher who ticked somebody under "Additional artisans" and left "Primary artisan" blank
 *       got the RESP respondent block prefilled from nobody.
 *
 * ── WHAT STILL NEEDS ONE ARTISAN, AND THE RULE ───────────────────────────────────────────────────
 *
 * Two things do, and only two: the RESP respondent block (name / craft / place / gender / contact —
 * one person's details, on one form) and the carry-forward bag banked after a save, which names the
 * artisan the researcher is still sitting with. {@link primaryInterviewArtisanId} answers both with
 * **the head of the selected list, in the researcher's own tick order**.
 *
 * THAT IS THIS REPOSITORY'S EXISTING RULE, not a new one invented here. `_resolve_tool_links`
 * derives a tool's scalar artisan as `data["artisanId"] = artisan_ids[0]`
 * (`backend/app/api/routes/tools.py`), and the handset's tool sheet mirrors it with
 * `artisanIds.firstOrNull().orEmpty()`. One product, one answer to "which of these several people is
 * the record's own".
 *
 * IT IS STABLE ACROSS RE-RENDERS because the selection is an ORDERED array in React state and
 * `MultiSelectDropdown` only ever appends on tick and filters on untick
 * (`components/ui/SearchableSelect.tsx`). Nothing re-sorts it, so element 0 does not move unless the
 * researcher removes the person at element 0, which is their own act and not a silent change. The
 * REJECTED alternative was a sticky `primaryRef` pinning the first artisan ever ticked: it survives
 * its own artisan being unticked, so the carry bag and the RESP block would go on naming somebody
 * the interview no longer covers — and it is the "primary" concept being deleted here, smuggled back
 * in as a ref where no control shows it and nobody can correct it.
 *
 * ── SCOPING IS THE SERVER'S JOB AND NOT A `.filter()` ────────────────────────────────────────────
 *
 * An artisan belongs to an ordinary `Workshop` THREE ways and all three count —
 * `services/record_filters.artisan_workshop_clause`: the `Artisan.workshopId` column, the
 * `WorkshopArtisan` roster that carried the link before that column existed, and *having sat in an
 * interview taken at the workshop*. A browser holds only the first of those (`artisan.workshopId` on
 * the DTO), so a client-side filter would silently drop everybody linked the other two ways — on a
 * questionnaire form the third group is precisely the people most likely to be wanted, and the
 * second is every artisan recorded before the column existed. It would also disagree with the
 * handset, which asks the server (`ui/ConsolidatedQuestionnaireScreen.kt`:
 * `repository.artisans(workshopIds = scope.workshopIds)`). So the scope goes on the wire.
 *
 * ── ONE SCOPE AT A TIME, NEVER TWO — AND WHICH ONE ───────────────────────────────────────────────
 *
 * This form names TWO workshops today: the ordinary `Workshop` (`workshopId`) and the design &
 * prototype workshop (`designWorkshopId`). Under the owner's ruling there is ONE picker with two
 * destinations — a Design & Prototype type writes `designWorkshopId`, any other type writes
 * `workshopId` — so at most one of the two ids is ever set and "scope by the workshop the interview
 * is filed under" has exactly one answer. Until that picker lands, this form can set both, and the
 * rule has to say what happens then. {@link workshopArtisanParams} sends the ORDINARY workshop when
 * there is one and the design workshop otherwise, and NEVER both.
 *
 * SENDING BOTH IS THE ONE THING THAT WOULD BE WRONG. `list_artisans` ANDs its filters, and the two
 * rosters are different populations drawn from different tables: MEASURED on the local corpus on
 * 2026-09-16, of 774 artisans, 204 carry a `workshopId` and 4 carry a `designWorkshopId`. Sending
 * both narrows to the artisans linked BOTH ways, which is a handful of rows — and an empty picker
 * under a named workshop is this repository's most repeated bug class, written out at length in
 * `craft_workshop_clause`'s own docstring ("a scope that renders empty over a full corpus and looks
 * exactly like having no data"). Narrowing by the one workshop the record is filed under is the fix.
 * Narrowing by two is a new defect wearing the fix's clothes.
 *
 * ── PLURAL `workshopIds` AND NOT SINGULAR `workshopId`, DELIBERATELY ─────────────────────────────
 *
 * `GET /artisans` accepts both and **they are not the same filter**. The singular narrows on the
 * column OR the `WorkshopArtisan` roster (two of the three readings above); the plural goes through
 * `artisan_workshop_clause` and counts the interview membership as well. Since `list_artisans` ANDs
 * every filter it is given, sending BOTH spellings would silently intersect them down to the
 * singular's narrower answer — and that is the one thing that would break handset parity, because
 * the handset sends the plural.
 *
 * This is the opposite call from `components/forms/recordPickers`, which deliberately sends
 * `craftId` alongside `craftIds`, and the difference is worth stating because the next reader will
 * meet both. There, the two spellings are exactly equivalent for a one-element selection, so sending
 * both makes the request byte-identical against an API that predates the plural. Here they are not
 * equivalent, so "belt and braces" would be a wrong narrowing rather than a safe one.
 *
 * The design workshop has no plural spelling on this route — `designWorkshopId` is a plain equality
 * on the column and the route's own comment says why ("there is no second reading of this link") —
 * so there is no choice to make on that side.
 *
 * ── NO WORKSHOP SELECTED MEANS EVERY WORKSHOP ────────────────────────────────────────────────────
 *
 * See {@link workshopArtisanParams}. Stated there because that is the function that spells it.
 *
 * ── THE CONTRACT BOTH CLIENTS OWE ────────────────────────────────────────────────────────────────
 *
 * Every rule here is a rule the handset owes too, and the whole reason this is a file of exported
 * functions rather than logic inside `page.tsx` is that a rule nobody can name is a rule the other
 * client cannot copy. `shared/questionnaire-form-contract.json` declares it once and
 * `backend/tests/test_questionnaire_form_contract.py` holds the web to it:
 *
 *   1. THE OFFER IS THE FILED WORKSHOP'S ROSTER, fetched with the plural `workshopIds` (ordinary
 *      workshop) or `designWorkshopId` (design & prototype workshop), and unscoped when neither is
 *      named.
 *   2. IT IS PAGED TO {@link ARTISAN_PAGE_BUDGET} PAGES and then reported honestly.
 *   3. THE FIRST REQUEST IS HELD until the workshop picker has settled on its own default —
 *      {@link workshopScopeSettling}.
 *   4. A WORKSHOP CHANGE INVALIDATES THE OFFER IMMEDIATELY, before the await and not when the
 *      replacement lands. In the window between two workshops the picker offers NOBODY and says
 *      "Loading artisans…". It never shows the previous workshop's roster and never falls back to
 *      the repository-wide list; either of those IS the reported defect, merely briefer.
 *   5. A FAILED ROSTER REQUEST SAYS SO and offers nobody — not the previous workshop's people, not
 *      everybody, not silence over an empty box. {@link WorkshopArtisans.failed}.
 *   6. A WORKSHOP CHANGE NEVER UNTICKS ANYBODY. The roster is the OFFER; it is not a proof that a
 *      tick is wrong. A ticked artisan the roster does not hold is drawn anyway and NAMED in a
 *      sentence under the picker ({@link artisansNotAtWorkshop}, {@link outOfWorkshopNotice}).
 *   7. THE OPTIONS ARE THE SERVER'S ROWS IN THE SERVER'S OWN ORDER (`createdAt desc`) — neither
 *      client re-sorts.
 *   8. THE INTERVIEW LIST IS SCOPED BY THE SAME WORKSHOP. Already true here: `loadInterviews` sends
 *      `workshopId: funnel.workshopId` and `e2e/questionnaire-workshop-filter-unit.spec.ts` pins it.
 *   9. ANYTHING NEEDING ONE ARTISAN TAKES ELEMENT 0 of the selection.
 *
 * ── WHY RULE 6 IS THE WAY ROUND IT IS ────────────────────────────────────────────────────────────
 *
 * The obvious behaviour is "new workshop, drop the ticks the new roster does not hold". It is wrong
 * here, on three counts.
 *
 *   • **The roster cannot prove a tick wrong.** `artisan_workshop_clause` counts three links and the
 *     third is *having sat in an interview taken at the workshop* — a link THIS FORM CREATES. An
 *     artisan who travelled to this workshop is, by construction, absent from its roster until the
 *     interview naming them is filed. Unticking them is the form refusing to record the one fact it
 *     exists to record.
 *   • **The failure mode of dropping is a record saved EMPTY.** `/questionnaire?artisanId=…` from the
 *     artisans page, and the carry-forward bag after a tool or a product, both routinely name
 *     somebody filed at another workshop. Both would go in ticked and come out unticked about a
 *     second later, with the RESP block blanking itself on the way past, and `artisanIds: []` on the
 *     POST. The failure mode of keeping is a record saved with an artisan the researcher can SEE is
 *     ticked and can untick in one tap.
 *   • **Silence would be the actual defect.** A form that edits a researcher's selection on their
 *     behalf must say so — the rule this repository already applies to truncated lists in
 *     `components/data/cappedList`. Once you are obliged to print a sentence either way, the sentence
 *     that keeps the data is the better one.
 *
 * `e2e/questionnaire-artisan-scope-unit.spec.ts` asserts the web half.
 */

import { useEffect, useMemo, useState } from "react";

import { cutOf, LIST_PAGE_CEILING, mergeById, type ListCut } from "@/components/data/cappedList";
import type { CarryScopeState } from "@/components/forms/CarryContextBanner";
import type { DropdownOption } from "@/components/ui/Dropdown";
import { listResource } from "@/lib/api";
import type { Artisan } from "@/lib/types";

/**
 * HOW MANY PAGES OF THE ARTISAN LIST THIS FORM WILL WALK, and why it walks any at all.
 *
 * `pageSize` is clamped to `MAX_PAGE_SIZE = 100` server-side (`backend/app/services/pagination.py`)
 * and every list route declares `Query(20, ge=1, le=100)` on top of it, so 100 is refused-past and
 * not a tunable — a single request cannot hold more, however it is asked. On a form whose entire
 * purpose is linking an interview to the RIGHT person, "the artisan you want may simply not be in
 * the list" is not a defect that can be papered over with a sentence: a researcher who cannot find
 * the person in front of them cannot file the interview at all.
 *
 * So this picker PAGES, and then still says what it could not reach. Four pages past the first is
 * 500 artisans, which comfortably covers the largest table behind this picker (774 artisans across
 * every workshop on the local corpus; one workshop's share of that is far smaller) while bounding
 * the worst case at five requests — and pages 2..N go out together, so it is two round trips, not
 * five.
 *
 * RAISING THIS ALONE WOULD FIX NOTHING, which is `components/data/cappedList`'s standing rule and
 * the reason the notice ships beside it: moving a cut is not telling anybody where the cut is.
 *
 * ── `LIST_PAGE_CEILING` AND NOT `RENDER_CAP`, AND THE RULE THAT SOUNDS LIKE IT FORBIDS THAT ──────
 *
 * `e2e/dropdown-sweep-unit.spec.ts` encodes a standing rule — "a picker's fetch asks for exactly the
 * number of rows the panel can draw" — and `SearchableSelect`'s own header states it as "the caller
 * owns the page size, and it must be `RENDER_CAP`. Asking for 100 rows into a panel that draws 80 is
 * the dead band." That rule is real and this picker is outside it, for a reason worth writing down
 * rather than re-deriving.
 *
 * IT IS A RULE ABOUT `serverQuery` PICKERS. There, the panel holds the SERVER's answer to the term
 * in the box: `filtered.length` never exceeds the cap, so `capNoticeSentence` never fires and rows
 * 81..100 are on the wire with nothing on screen able to reach or mention them — see
 * `selectFilter.ts::flagCapNoticeSentence`, which exists for exactly that case. This control has no
 * `serverQuery`. It is handed an ARRAY, `filterOptions` narrows that array on every keystroke, and
 * the 80-row window is applied to what SURVIVES the filter (`SearchableSelect`: `filtered.length <=
 * RENDER_CAP ? filtered : filtered.slice(0, RENDER_CAP)`). So every row this hook loads is reachable
 * by typing, and the panel's own "Showing the first 80 of 437" is a true sentence about a real total.
 *
 * Capping the FETCH at 80 here would not kill a dead band; it would create the defect this file is
 * about, one layer down — a workshop's roster silently truncated at eighty with the researcher told
 * the list is complete. What is left to say is the cut at the END of the budget, and that is what
 * `cutOf` + `cappedListNotice(cut, "none")` say on the page: `"none"` because the term does NOT go
 * to the server here, so typing cannot reach past 500 either, and a sentence that told somebody to
 * keep typing would be telling them to do something impossible.
 */
export const ARTISAN_PAGE_BUDGET = 5;

/**
 * The scope this form files an interview under: at most one workshop, out of two tables.
 *
 * Both ids are carried because the form still draws two pickers. Neither is a `Workshop | null` —
 * `""` is this repository's spelling of "not linked to a workshop", it is what both picker states
 * hold, and it is a legal saved value (`workshopId: workshop.workshopId || null`).
 */
export type InterviewWorkshopScope = {
  /** The ordinary `Workshop` id, or `""`. */
  workshopId: string;
  /** The `DesignWorkshop` id, or `""`. */
  designWorkshopId: string;
};

/** The scope as one comparable string, so an effect can depend on "which workshop" and not on an object. */
export function interviewScopeKey(scope: InterviewWorkshopScope): string {
  return `${scope.workshopId}|${scope.designWorkshopId}`;
}

/** Is any workshop named at all? `false` is the legitimate "not linked to a workshop" state (R5). */
export function interviewScopeIsNarrowed(scope: InterviewWorkshopScope): boolean {
  return Boolean(scope.workshopId || scope.designWorkshopId);
}

/**
 * The plural noun the capped-list sentence is built around — "Showing 100 of 240 …".
 *
 * It names the SCOPE and not just the record type, because the two sentences answer different
 * questions: under a workshop the reader needs to know that this WORKSHOP has more people than are
 * listed, not that the repository does.
 */
export function artisanScopeNoun(scope: InterviewWorkshopScope): string {
  return interviewScopeIsNarrowed(scope) ? "artisans at this workshop" : "artisans";
}

/**
 * The query for one page of the artisan picker's options.
 *
 * ── ONE SCOPE, AND THE ORDINARY WORKSHOP WINS WHEN BOTH ARE NAMED ────────────────────────────────
 *
 * The argument is in the file header: `list_artisans` ANDs its filters, the two rosters are
 * different populations, and intersecting them is how a narrowed picker becomes an empty one. The
 * ordinary workshop wins because it is the reading with three ways in (`artisan_workshop_clause`:
 * the column, the `WorkshopArtisan` roster, and interview membership) against the design workshop's
 * single column, so it is the scope that can actually answer "who was at this workshop".
 *
 * ── "NO WORKSHOP SELECTED" SHOWS EVERY ARTISAN, AND HERE IS THE ARGUMENT ─────────────────────────
 *
 * The alternative — an empty picker under "choose a workshop first" — is rejected on three counts.
 *
 * 1. **It would block a legal record.** `submit` sends `workshopId: workshop.workshopId || null` and
 *    `designWorkshopId: designWorkshop.workshopId || null`, and both columns are nullable: an
 *    interview that belongs to no workshop is a real thing this product stores, and the owner's
 *    ruling keeps it ("Not linked to a workshop" survives). Refusing to offer anybody until a
 *    workshop is picked would make that record unfileable, and the researcher's only way out would be
 *    to attach the interview to a workshop it was not taken at — corrupting the very scoping this
 *    change exists to establish.
 * 2. **"Absent" already means "all" everywhere else in this stack.** `resolve_workshop_ids` returns
 *    `None` for an absent, empty or all-blank `workshopIds` and says so in as many words. Inventing a
 *    second meaning for the empty scope on ONE screen of ONE client is exactly how two surfaces come
 *    to answer one question differently.
 * 3. **It is not the misleading case.** An unnarrowed list under no workshop claims nothing it cannot
 *    support: the reader narrowed nothing, so they are shown everything, and the capped-list sentence
 *    tells them what "everything" left out. The misleading list is the one that LOOKS narrowed and is
 *    not — a workshop named in the box above and strangers in the box below. That is the defect, and
 *    it is fixed by scoping when there IS a scope, not by blanking when there is none.
 *
 * `undefined` rather than `""` for an absent scope: `buildQuery` drops `undefined`, `null` and `""`
 * alike (`lib/api.ts`), so both spellings happen to work today — `undefined` is the one that says
 * "not asked for" rather than "asked for, blankly".
 */
export function workshopArtisanParams(
  scope: InterviewWorkshopScope,
  page: number
): Record<string, string | number | undefined> {
  return {
    // The PLURAL alone for the ordinary workshop — see the file header. The singular `workshopId` is
    // a NARROWER filter on this route, not an equivalent one, and `list_artisans` ANDs them.
    workshopIds: scope.workshopId || undefined,
    // Only reached when no ordinary workshop is named, so the two can never intersect.
    designWorkshopId: scope.workshopId ? undefined : scope.designWorkshopId || undefined,
    page,
    pageSize: LIST_PAGE_CEILING
  };
}

/**
 * Has the workshop picker finished choosing for itself?
 *
 * `useWorkshopSelection` opens a CREATE form on the most recent workshop the user may actually
 * submit to, and it finds that workshop asynchronously: it loads `/workshops`, then walks the first
 * few in occurrence order asking `/workshops/{id}/submission-check` until one answers yes
 * (`components/forms/WorkshopSelect.tsx`). Between mount and the end of that walk `workshopId` is
 * `""` — which is indistinguishable, to anything reading the value, from a researcher who has
 * deliberately chosen no workshop.
 *
 * FIRING THE ARTISAN REQUEST DURING THAT WINDOW is the bug this guard exists to prevent, and it is
 * not hypothetical: it is two requests where one would do, and for as long as the second is in
 * flight the picker shows the repository-wide list under a workshop name that has just appeared in
 * the box above — the exact screen the defect report describes. `CompletionMatrixPanel` on this same
 * page holds its own first request for the same reason.
 *
 * The settled test is "the probe cannot still be running": it has loaded the workshop list, and
 * either it has landed on a workshop, or the user has touched the control, or there are no workshops
 * to land on. The last arm matters — on a deployment with no workshops at all the probe never runs
 * and `workshopId` stays `""` forever, so a guard that only asked "is `workshopId` empty" would hold
 * the artisan list hostage for the whole session.
 *
 * ONLY THE ORDINARY PICKER IS WAITED ON, and that is a deliberate asymmetry rather than an
 * oversight. `useDesignWorkshopSelection` exposes no `loading`: it starts at `""` and
 * `DesignWorkshopSelect` prefills it when `lib/designWorkshopDefault.ts` answers. So in the one state
 * where the design workshop's default is the scope — no ordinary workshop reachable at all — the
 * first request goes out unscoped and a second, scoped one follows when the default lands. That
 * costs one request in an uncommon state and shows NO wrong list at any point, because rule 4
 * invalidates the offer before awaiting. Waiting on it instead would mean holding the picker empty
 * on every ordinary-workshop form until an unrelated request finished.
 *
 * ── THE TWO-DROPDOWN RULING ADDED AN ARM, AND WITHOUT IT THIS GUARD HANGS ─────────────────
 *
 * `forms/WorkshopPicker.tsx` mounts BOTH halves at once and draws one, and forces `workshopId` to
 * `""` whenever the chosen type routes at a `DesignWorkshop` (R3). Feed that state to the three
 * tests below and the last arm is reached with an empty `workshopId`, an untouched control and a
 * non-empty ordinary list — so it answers "still settling", for ever, and the artisan roster never
 * loads on any design-routed form. The ordinary half's probe is genuinely irrelevant there: its
 * answer cannot reach the payload or this scope, so there is nothing to wait for.
 *
 * It is OPTIONAL and defaults to false, so the four record forms' own callers and every test that
 * passes a bare `useWorkshopSelection` shape are unchanged.
 */
export function workshopScopeSettling(workshop: {
  loading: boolean;
  touched: boolean;
  workshopId: string;
  workshops: readonly unknown[];
  routesToDesignWorkshop?: boolean;
}): boolean {
  if (workshop.routesToDesignWorkshop) return false;
  if (workshop.loading) return true;
  if (workshop.touched || workshop.workshopId) return false;
  return workshop.workshops.length > 0;
}

/**
 * One artisan as a row in the picker: "Name - Craft - Place".
 *
 * The separator is " - " and NOT the " · " some other pickers in this app use, because that is the
 * shape this control has always drawn and the shape `e2e/dropdown-option-labels.spec.ts` reads.
 * The craft and the place are in the label deliberately: it is what a researcher searches the list
 * by, and two artisans in a district routinely share a name.
 */
export function artisanPickerOptionLabel(artisan: Artisan): string {
  return `${artisan.name} - ${artisan.craft?.name ?? "No craft"} - ${artisan.place}`;
}

/**
 * THE OPTIONS THE ONE ARTISAN CONTROL OFFERS.
 *
 * `scoped` is the workshop's own roster as the server returned it, in the server's order. That is
 * the whole offer — nothing from the repository-wide reference load is folded in, and THAT IS THE
 * FIX: the defect was precisely a repository-wide list standing in for a workshop's.
 *
 * The one exception, and it is not an offer: an id that is ALREADY TICKED and that the roster does
 * not hold is drawn anyway, out of `known`. A multi-select renders its trigger from the labels of
 * the options that match its values, so a ticked id with no option makes the control say "Nothing
 * selected" over a selection that is about to be submitted — a blank chip for a person who IS on the
 * record.
 *
 * THAT ROW IS NOT A GAP THAT CLOSES ITSELF. Nothing narrows the selection (rule 6): a deep link
 * (`/questionnaire?artisanId=…`), a carried context, or a researcher who ticked somebody and then
 * corrected the workshop all leave a permanent ticked row the roster does not hold, and it stays
 * drawn for as long as it stays ticked. {@link artisansNotAtWorkshop} is what names those rows in a
 * sentence, so the reader is told rather than left to notice.
 *
 * NO EXTRA REQUEST IS MADE FOR THAT ROW. `known` is everything this page has already loaded — the
 * repository-wide reference page plus every scoped page — so the rescue costs nothing.
 */
export function artisanPickerOptions({
  scoped,
  known,
  selectedIds
}: {
  /** The workshop's roster, server order. */
  scoped: readonly Artisan[];
  /** Every artisan row this page has loaded, for labels only. */
  known: readonly Artisan[];
  /** The ticked ids, in the researcher's own order. */
  selectedIds: readonly string[];
}): DropdownOption[] {
  const offered = new Set(scoped.map((artisan) => artisan.id));
  const rescued = selectedIds
    .filter((id) => id && !offered.has(id))
    .map((id) => known.find((artisan) => artisan.id === id))
    .filter((artisan): artisan is Artisan => Boolean(artisan));
  return mergeById(scoped as Artisan[], rescued).map((artisan) => ({
    value: artisan.id,
    label: artisanPickerOptionLabel(artisan)
  }));
}

/**
 * The one artisan anything single-valued uses: the RESP prefill and the carry bag. `""` when nobody
 * is ticked. See the file header for why it is element 0 and why that is stable.
 */
export function primaryInterviewArtisanId(selectedIds: readonly string[]): string {
  return selectedIds.find((id) => Boolean(id)) ?? "";
}

/**
 * The SET key the shared-entry lookup is keyed on: sorted, blank-free, comma-joined.
 *
 * SORTED, because the interview is stored once per exact SET of artisans and ticking A then B is the
 * same interview as ticking B then A — the server's own `artisan_set_key` sorts for the same reason,
 * and `QuestionnaireInterview.artisanSetKey` is `@unique`, so two clients that disagreed about this
 * string would disagree about which interview a save folds into. This is deliberately NOT the order
 * the ids are SENT in: that order is the researcher's and is what {@link primaryInterviewArtisanId}
 * reads, so the two must not be collapsed into one array.
 */
export function artisanSetKey(selectedIds: readonly string[]): string {
  return [...new Set(selectedIds.filter(Boolean))].sort().join(",");
}

/**
 * WHICH TICKED ARTISANS THE WORKSHOP'S ROSTER DOES NOT ACCOUNT FOR — a SENTENCE, not a deletion.
 *
 * "Absent from the list" has three causes and only one of them is "this workshop does not know
 * them", so this function stays silent unless it is certain:
 *
 *   • The roster for THIS workshop has not landed (`loadedForScope !== scopeKey`). The page knows
 *     nothing at all, so it says nothing. Without this arm the sentence would appear on every mount
 *     and on every workshop change, for the length of a round trip, naming everybody — and a warning
 *     that is usually wrong is a warning nobody reads. It also covers the failed request, where
 *     `loadedForScope` stays null on purpose.
 *   • The roster IS CUT — it stopped at {@link ARTISAN_PAGE_BUDGET} pages with more rows behind it.
 *     An artisan absent from a truncated list may be perfectly well linked here and simply past the
 *     cut, and `components/data/cappedList` exists to stop a pagination boundary being reported as a
 *     fact about the world. The capped-list notice is already on screen saying the list is short;
 *     this one stays quiet rather than contradicting it.
 *   • Otherwise the roster is the complete answer for this workshop, so absence means absence — and
 *     THAT is worth a sentence.
 *
 * Returns the ids IN THE ORDER GIVEN, so the sentence lists people in the researcher's own tick
 * order and reads the same on two screens.
 */
export function artisansNotAtWorkshop({
  selectedIds,
  offeredIds,
  loadedForScope,
  scopeKey,
  narrowed,
  cut
}: {
  selectedIds: readonly string[];
  /** The ids on the loaded roster. */
  offeredIds: readonly string[];
  /** Which scope the loaded roster belongs to; null before the first load, and after a failure. */
  loadedForScope: string | null;
  /** The scope now selected — {@link interviewScopeKey}. */
  scopeKey: string;
  /** Is any workshop named? Nothing to say about "not at this workshop" when there is no workshop. */
  narrowed: boolean;
  /** The roster's cut, or null when it is whole. */
  cut: ListCut | null;
}): string[] {
  if (!narrowed) return [];
  if (loadedForScope !== scopeKey) return [];
  if (cut) return [];
  const offered = new Set(offeredIds);
  return selectedIds.filter((id) => Boolean(id) && !offered.has(id));
}

/**
 * How many names the sentence below prints before it starts counting. A selection of thirty would
 * otherwise print a paragraph.
 */
export const OUT_OF_WORKSHOP_NAMES_SHOWN = 4;

/**
 * THE SENTENCE UNDER THE PICKER naming the ticked artisans this workshop's roster does not hold, or
 * `""` when there is nothing to say.
 *
 * WHY IT DOES NOT READ AS A WARNING. Nothing is wrong yet, and in the commonest case nothing is
 * wrong at all: an interview taken at this workshop with somebody whose own record is filed at
 * another one is an ordinary event, and filing it is precisely what creates the link that would have
 * put them on this roster. So the sentence states the fact, states what saving will do, and stops.
 * Wording it as "these artisans do not belong here" would push a researcher into unticking a
 * perfectly good selection to make a message go away.
 *
 * NAMES AND NOT A COUNT. "1 artisan is not recorded at this workshop" makes the reader open the
 * control and compare it against a roster to find out who; the names are already in hand, and the
 * whole point of the sentence is that it can be acted on without looking anything up.
 */
export function outOfWorkshopNotice(names: readonly string[]): string {
  const clean = names.map((name) => name.trim()).filter(Boolean);
  if (clean.length === 0) return "";
  const shown = clean.slice(0, OUT_OF_WORKSHOP_NAMES_SHOWN);
  const remainder = clean.length - shown.length;
  const list = shown.join(", ") + (remainder > 0 ? ` and ${remainder} more` : "");
  const subject = clean.length === 1 ? `${list} is` : `${list} are`;
  return (
    `${subject} not recorded at this workshop yet. They stay ticked — saving this interview here ` +
    `is what links them to it. Untick anyone who should not be on it.`
  );
}

/**
 * THE FOUR-SENTENCE EMPTY STATE, and why an empty array is not enough to choose between them.
 *
 * An empty roster means one of four different things — the request is still in flight, the request
 * failed, nobody is recorded at this workshop, nobody is recorded at all — and only two of them are
 * facts about the repository. Printing "No artisans are recorded at this workshop yet" off an empty
 * array while the answer is still coming makes a claim before it exists, and the researcher who
 * believes it goes off to create a duplicate of an artisan who is already there.
 *
 * Kept as a function beside the rules rather than inline in the JSX so the handset has one thing to
 * copy rather than four strings to find.
 */
export function artisanPickerEmptyLabel({
  loadedForScope,
  scopeKey,
  failed,
  narrowed
}: {
  loadedForScope: string | null;
  scopeKey: string;
  failed: boolean;
  narrowed: boolean;
}): string {
  if (failed) return "The artisan list could not be loaded. Pick the workshop again to retry.";
  if (loadedForScope !== scopeKey) return "Loading artisans…";
  if (narrowed) return "No artisans are recorded at this workshop yet";
  return "No artisans are recorded yet";
}

export type WorkshopArtisans = {
  /** The selected workshop's roster, server order. What the picker offers. */
  scoped: Artisan[];
  /** Every artisan row loaded so far, for labels only. Never narrowed. */
  known: Artisan[];
  /**
   * Which scope `scoped` belongs to — and the flag that says "this roster is about the workshop on
   * screen" rather than about some other one.
   *
   * NULL IS THE COMMON CASE AND NOT JUST THE FIRST ONE: it is null before the first load, null again
   * from the instant the workshop changes (rule 4 clears the roster before awaiting), and null after
   * a failed request. Every consumer therefore has to treat "null" and "some other workshop" alike,
   * which is what both {@link artisansNotAtWorkshop} and {@link artisanPickerEmptyLabel} do.
   */
  loadedForScope: string | null;
  /** What the roster could not reach, or null when it holds every row. */
  cut: ListCut | null;
  /**
   * Did the roster request for the workshop now on screen FAIL?
   *
   * Rule 5. It is not the same fact as "the roster is empty" and it is not the same fact as "the
   * roster has not landed", and a picker that cannot tell the three apart prints "No artisans are
   * recorded at this workshop yet" over a dropped request — a claim about the repository assembled
   * out of a claim about the network. Cleared at the start of every attempt, so a workshop change is
   * a fresh question and not an inherited verdict.
   */
  failed: boolean;
  /**
   * "Have the reference lists arrived?" — handed straight to `carryScope`, which treats "not visible
   * to me" and "no signal" differently. Moved ONLY by the repository-wide mount load; see the hook.
   */
  referenceState: CarryScopeState;
};

/**
 * THE ARTISAN OPTIONS FOR ONE WORKSHOP, RE-FETCHED WHENEVER THE WORKSHOP MOVES.
 *
 * ── WHY THERE ARE TWO LOADS AND NOT ONE ──────────────────────────────────────────────────────────
 *
 * (A) A repository-wide page at mount, unscoped, used for NOTHING the researcher sees. Its one job
 *     is `carryScope("artisan", …)`: `useCarryContext` reads that id list to decide whether a
 *     carried artisan is still REACHABLE, and prunes the carried record — and everything hanging off
 *     it — when it is not (`components/forms/CarryContextBanner`). Handing it the workshop-scoped
 *     list instead would answer a different question with the same array: an artisan who is alive,
 *     visible and simply documented at another workshop would be pruned as though they had been
 *     deleted. Worse, the carried bag also carries a WORKSHOP, which `onApply` writes into the
 *     picker — so the prune would happen while the form was still on its default workshop and would
 *     destroy the very context that was about to move it.
 *
 *     Page one only. That is what this page has always done, so carry's reach is unchanged by this
 *     work; an artisan past the first page of the whole table is not offered as a carried prefill
 *     today either. Paging it would be a change to a different feature's behaviour, made silently
 *     from here.
 *
 * (B) The workshop-scoped roster, re-fetched on every settled change of the scope, PAGED up to
 *     {@link ARTISAN_PAGE_BUDGET} and then reported honestly. This is the picker's offer.
 *
 * When no workshop is selected the two requests are identical, and that is accepted rather than
 * deduplicated: the alternative is a shared cache entry whose two readers have different lifetimes
 * and different meanings, to save one GET in the one state this form is rarely in (it opens on a
 * workshop by default).
 *
 * ── REPLACE, NEVER MERGE ─────────────────────────────────────────────────────────────────────────
 *
 * `scoped` is ASSIGNED from each answer. `mergeById` is the right rule for a record form's picker,
 * where three requests describe one world and a narrower answer must not look like a shorter one —
 * and it is exactly the wrong rule here, where a narrower answer is the entire point. Merging would
 * leave the previous workshop's artisans on offer at the next workshop, which is the reported defect
 * reintroduced by a helper written to prevent a different one. `known` is the array that merges.
 */
export function useWorkshopArtisans({
  scope,
  settling
}: {
  /** The workshop this interview is being filed under. */
  scope: InterviewWorkshopScope;
  /** True while the workshop picker is still choosing its own default — {@link workshopScopeSettling}. */
  settling: boolean;
}): WorkshopArtisans {
  const [scoped, setScoped] = useState<Artisan[]>([]);
  const [known, setKnown] = useState<Artisan[]>([]);
  const [loadedForScope, setLoadedForScope] = useState<string | null>(null);
  const [cut, setCut] = useState<ListCut | null>(null);
  const [failed, setFailed] = useState(false);
  const [referenceState, setReferenceState] = useState<CarryScopeState>("pending");

  // (A) The reachability probe. Never rendered; see the header.
  useEffect(() => {
    let cancelled = false;
    listResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        if (cancelled) return;
        setKnown((previous) => mergeById(previous, result.items));
        setReferenceState("loaded");
      })
      .catch(() => {
        if (!cancelled) setReferenceState("unavailable");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // (B) The offer. Keyed on the SCOPE STRING and not on the object, so a re-render that rebuilds an
  // identical `{ workshopId, designWorkshopId }` does not re-fetch the same roster.
  const scopeKey = interviewScopeKey(scope);
  const workshopId = scope.workshopId;
  const designWorkshopId = scope.designWorkshopId;
  useEffect(() => {
    if (settling) return;
    let cancelled = false;
    /*
      INVALIDATED HERE, BEFORE THE AWAIT — rule 4, and the line this page was missing entirely.

      `scoped` describes ONE workshop and the workshop it describes has just changed. Assigning the
      replacement on success and leaving the old rows up until then looks like caution and is not:
      for the whole length of a field connection's round trip the picker lists the PREVIOUS
      workshop's people under the new workshop's name, with `cut` still saying "Showing 100 of 240
      artisans at this workshop" about a workshop nobody is looking at. That is the reported defect
      exactly, and it would be reachable on every single workshop change, not only on a failure.

      `loadedForScope` goes with them, and it is the important half. It is the flag every consumer
      reads to tell "this roster is about the workshop on screen" from "this roster is about some
      other workshop": {@link artisanPickerEmptyLabel} prints "Loading artisans…" off it, and
      {@link artisansNotAtWorkshop} refuses to name anybody while it disagrees. A stale roster left
      standing under a stale label is a lie that type-checks.

      SHOWING NOBODY IS SAFE, which is what makes clearing possible at all. Ticked rows are drawn
      from `known` by {@link artisanPickerOptions} whatever the roster holds, so a selection never
      disappears from the control; nothing unticks anybody (rule 6); and the empty state has its own
      four sentences. The rejected alternative — hold the previous rows and dim the control — keeps
      the wrong names on screen and merely apologises for them.
    */
    setScoped([]);
    setLoadedForScope(null);
    setCut(null);
    setFailed(false);
    const current: InterviewWorkshopScope = { workshopId, designWorkshopId };
    (async () => {
      try {
        const first = await listResource<Artisan>("/artisans", workshopArtisanParams(current, 1));
        if (cancelled) return;
        // Pages two onward go out TOGETHER rather than one after another. `total` and `pages` came
        // back with page one, so the remaining page numbers are known exactly — waiting for each
        // answer to learn the next number would turn a field connection's latency into a multiple of
        // itself for no extra information. `Promise.all` preserves input order, so concatenating the
        // results keeps the server's own `createdAt desc` ordering intact end to end, which is the
        // ordering half of rule 7.
        const lastPage = Math.min(first.pages || 1, ARTISAN_PAGE_BUDGET);
        const rest =
          lastPage > 1
            ? await Promise.all(
                Array.from({ length: lastPage - 1 }, (_, index) =>
                  listResource<Artisan>("/artisans", workshopArtisanParams(current, index + 2))
                )
              )
            : [];
        if (cancelled) return;
        const rows = [first, ...rest].flatMap((result) => result.items);
        setScoped(rows);
        setKnown((previous) => mergeById(previous, rows));
        setCut(cutOf(rows.length, first.total, artisanScopeNoun(current)));
        setLoadedForScope(interviewScopeKey(current));
      } catch {
        /*
          SAID, NOT SWALLOWED — rule 5.

          There is nothing left to "leave standing": the invalidation above already dropped the
          previous workshop's rows, deliberately. What remains is an empty offer, and an empty offer
          with no explanation is the worst of the four states this picker can be in — it reads as
          "this workshop has nobody in it", which is a claim about the repository assembled out of a
          dropped packet.

          STILL NOT THE PAGE'S ERROR BANNER. That banner is driven by the sections load, which is the
          failure that stops the form working at all; a roster that could not be narrowed stops the
          form OFFERING, which is a smaller thing and belongs beside the control it is about.
          Guarded on `cancelled` so a request superseded by the next workshop cannot paint a failure
          over the answer that replaced it.
        */
        if (!cancelled) setFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
    // `scopeKey` is the dependency that matters; the two ids are listed because they are read above.
  }, [scopeKey, workshopId, designWorkshopId, settling]);

  return useMemo(
    () => ({ scoped, known, loadedForScope, cut, failed, referenceState }),
    [scoped, known, loadedForScope, cut, failed, referenceState]
  );
}
