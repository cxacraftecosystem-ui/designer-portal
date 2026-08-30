"use client";

/**
 * Design review — LEVEL TWO: the wider pool of designers, on pieces a workshop has finished.
 *
 * ── WHY THIS IS A ROUTE OF ITS OWN AND NOT A TAB UNDER THE WORKSHOP ─────────────────────────────
 *
 * Measured, not assumed. `load_workshop_or_404` (backend/app/services/design_workshops.py:127)
 * admits the workshop's creator, an admin, and the holder of a `DesignWorkshopViewer` grant, and
 * answers everybody else with the same 404 and the same "Record not found" a nonexistent id gets.
 * Every one of the 22 stage SAVE routes is gated by that same helper. A pool reviewer is by
 * definition one of the designers it turns away — so teaching it about the pool round would not
 * merely widen a read, it would hand every designer on the platform write access to every finished
 * workshop's fieldwork.
 *
 * The pool round therefore goes through `design_ratings.load_ratable_workshop_or_404`, a second and
 * deliberately narrow door: a design-workshop role is let through, and `pool_visible` then removes
 * every piece whose `peerRoundClosedAt` is blank. What is behind that door is the rateable rows and
 * their scores, and nothing else about the workshop.
 *
 * ── BOTH RATEABLE ENTITIES ARE OFFERED, AND THE COMMENT HERE ONCE SAID THE OPPOSITE ─────────────
 *
 * This page used to hardcode `entityKey="prototype"` under a comment asserting that
 * `peerRoundClosedAt` "only the prototype entity declares — a sketch has no such field, so
 * `pool_is_open` is false for every sketch". That was true of an older registry and is false of this
 * one. `stage_definitions.py` declares `f("peerRoundClosedAt", "Peer review closed on", DATE, A, …)`
 * on the `sketch` entity of stage 11, under a 25-line note whose own words are "The omission was the
 * outlier, not the rule" and "the field grants the ABILITY to open a sketch"; and
 * `design_ratings.POOL_OPENS_WHEN_FIELD` says in capitals "**A SKETCH CARRIES THE SAME KEY, AND THIS
 * NOTE USED TO SAY THE OPPOSITE**" and "nothing here special-cases the entity".
 *
 * The stale citation had a real cost, which is why it is written up rather than quietly deleted: the
 * set-aside sketches stage 11 exists to record — the designs never prototyped, which the registry's
 * own note calls "exactly the designs a wider pool might pick up" — were unreachable at level 2 for
 * every designer on the platform, because this page offered no way to ask for them. Nothing on the
 * server needed changing: `_entity_or_422` admits both members of `RATEABLE_ENTITIES` and
 * `pool_visible` gates per row, so this is a chooser and nothing more. Prototypes lead because they
 * are what most pool rounds are about; a workshop with nothing open in the chosen kind gets the same
 * one-sentence 404 as any other empty round.
 *
 * ── WHY THIS PAGE STILL ASKS FOR ONE WORKSHOP, WHICH HAS NOT CHANGED ────────────────────────────
 *
 * `GET /design-ratings/rounds/POOL` requires a `workshopId`, for both rounds, and the reason is
 * structural rather than an unfinished API: the placed order IS `DwStageEntry.ordinal`, which
 * orders the rows of ONE collection inside ONE workshop. Two prototypes in two workshops can both
 * be ordinal 0, and an arrangement a designer made across a mixed list would have nowhere to be
 * stored. So the pool round is the same list read by a wider audience, not a wider list, and a
 * cross-workshop BROWSE is a different feature that needs its own answer.
 *
 * That paragraph is untouched because it is still true. What used to follow it — "until that exists,
 * a pool reviewer arrives here with a workshop in hand" as the ONLY way in — is now only most of the
 * truth, and the next section is the honest version.
 *
 * ── TWO WAYS IN, AND THE DROPDOWN IS NARROWER THAN THE ROUND. SAY SO, DO NOT IMPLY OTHERWISE ────
 *
 * There are now a dropdown and a box, and they are not two spellings of one control:
 *
 *   THE DROPDOWN lists `GET /design-workshops` (`listDesignWorkshops`), one page of it. That
 *   endpoint scopes rows with `visible_to_clause` — `createdById = me OR viewers.some(userId = me)`
 *   — and for an admin returns the archive. So it is exactly "the design workshops THIS ACCOUNT
 *   CAN ALREADY OPEN", which is the same door `load_workshop_or_404` opens.
 *
 *   THE BOX is what it always was: a pasted link or a bare id, handed to the round untouched.
 *
 * THE TWO SETS ARE NOT NESTED THE FLATTERING WAY, and this is the fact the on-screen copy has to
 * carry rather than hide. The pool round is, by construction, workshops the caller is NOT a member
 * of: `load_ratable_workshop_or_404` lets any design-workshop role through the door and
 * `pool_visible` then keeps the rows whose `peerRoundClosedAt` is set. So the set the round can
 * serve is "every workshop on the platform holding at least one opened piece", membership
 * irrelevant — and the dropdown's set is "workshops I hold". For a designer those two overlap only
 * on their own workshops, and on those `pool_visible` returns `list(subjects)` unfiltered because
 * `is_member` is true, i.e. the same pieces the workshop's own Review tab already lists (scored in
 * the pool round rather than the peer round). The dropdown is therefore a CONVENIENCE over the
 * narrower set — genuinely useful to an admin, whose list is the whole archive, and a shortcut for
 * everybody else — and it is emphatically NOT a directory of what has been opened to the pool.
 *
 * WHICH IS WHY THE COPY BESIDE IT SAYS ALL OF THAT IN WORDS. A dropdown that merely appeared next to
 * the box would read as an exhaustive list, and an absent workshop would read as "no such round".
 * `sketches-and-prototypes/page.tsx`'s header prohibits exactly that mistake by name — "It is NOT
 * the set a POOL reviewer may rate … Do not repoint this chooser at `/design-review`" — and this is
 * not that: the list is fetched here, under a label that names its own scope, above a box that is
 * still the primary route and still the only route to anything else.
 *
 * AND THE DROPDOWN IS NOT A GATE. Presence in it opens a round and absence from it decides nothing:
 * the id goes to `ReviewPanel` either way and the API answers. This page is ALLOWED to work that
 * way where `/sketches-and-prototypes` is not, and the difference is one prop — `readsStageRows` is
 * false here, so `stageRows.readStageRows` routes to `loadDraft`, which creates nothing, instead of
 * `ensureDraft`, which is a check-AND-CREATE. Nothing is minted by mounting this page against a
 * stranger's id, so there is nothing for a pre-flight check to protect and a check would only be a
 * second refusal standing in front of the API's own.
 *
 * ── THE ENDPOINT THIS PAGE STILL DOES NOT HAVE, NAMED RATHER THAN HALF-BUILT ────────────────────
 *
 * `GET /design-ratings/workshops` — the workshops holding at least one piece opened to the pool.
 * Without it there is no honest chooser for this page's actual audience, and what is above is the
 * interim: a shortcut over the wrong-but-listable set, labelled as such.
 *
 *   * ON THE RATINGS ROUTER, so it inherits the POOL gate (`can_run_design_workshops` plus
 *     `get_current_user`) and not the workshop gate. Putting it under `/design-workshops` would put
 *     the pool's set behind `visible_to_clause`, which is the whole thing this page exists around.
 *   * PAYLOAD: title, date, and per-entity open counts. NOTHING ELSE — specifically not
 *     `workshop_summary`, which carries `craftName`, `clusterName`, `workshopCode` and `status`, and
 *     shipping it would turn a rating round into a directory of the ministry's archive.
 *   * WHY DISCLOSING THE TITLE IS ALLOWED AT ALL, since `GET /design-ratings/rounds/{round}` today
 *     deliberately answers with `{workshopId, entityKey, round, items}` and no title: the registry's
 *     own declaration of the field is the licence. `POOL_OPENS_WHEN_FIELD` reads "The day this
 *     prototype was declared finished **and opened to designers outside the workshop**." Setting
 *     that date is an act of publication by the workshop's own designers. The enumeration-oracle
 *     rule `pool_visible` enforces — "A caller left with nothing must be given the same 404 a
 *     missing workshop gets, or this route becomes an oracle for which workshop ids exist" — is
 *     about workshops with nothing open, and those are excluded from such a list by construction.
 *   * COST, MEASURED: no migration. It is the shape `/analytics/design-workshops` already reads, and
 *     `@@index([entityKey])` on `DwStageEntry` was added for that cross-workshop read. The honest
 *     caveat is that `peerRoundClosedAt` lives inside `DwStageEntry.data` (`Json`) rather than in a
 *     promoted column, so the discard happens in Python and the read grows with the number of
 *     sketches and prototypes in the archive rather than the number opened. A JSONB expression index
 *     is the escape hatch if that ever matters; it should not be pre-built.
 *
 * Until it exists, the sentence beside the box still says browsing the archive has no answer yet —
 * because it does not, and a dropdown over a different set is not that answer.
 *
 * ── WHAT A STRANGER IS TOLD ─────────────────────────────────────────────────────────────────────
 *
 * A workshop with nothing finished, a workshop this caller may not reach, and a workshop id that
 * never existed all answer 404 with one sentence. That is the API's decision and this page does not
 * try to tell them apart: the archive is keyed by cuid, and a page that distinguished them would
 * turn any designer login into an enumeration of the ministry's records one paste at a time.
 *
 * The dropdown does not weaken that by one row. Every option on it is a workshop the repository just
 * returned to this account, so it discloses nothing the workshops list does not, and a workshop's
 * absence from it is never reported as a refusal — see the copy under `design-review-scope`.
 *
 * ── THE PERMISSION GATE IS HERE AS WELL AS IN THE NAV, AND THE THIRD PLACE IS NOW PAID ──────────
 *
 * `load_ratable_workshop_or_404`'s first line refuses anybody outside `can_run_design_workshops`, so
 * a field contributor or a researcher who types this URL can never read a round. Until that was said
 * ON THE PAGE, what they got was the whole shell — header, workshop form and all — and then the
 * API's 404 rendered as "this round could not be read", which reads as a broken page rather than a
 * locked one. So the page refuses first, in the words the permission actually has.
 *
 * WHAT THIS COMMENT USED TO CALL "STILL OWED" HAS BEEN PAID, and leaving the claim standing was
 * itself a defect — a header that names a missing guard, about a guard that exists, is how a real
 * gap gets ignored the next time one is named. `ROUTE_GUARDS` carries `/design-review` at
 * `lib/permissions.ts:393` (the row that note asked for, with the reason spelled out: the route does
 * NOT ride on the `/design-workshops` prefix), and `docs/PERMISSIONS.md` §5 carries its twin, which
 * `docs/tools/check-docs.mjs` cross-checks. The gate below remains the SECOND line of three, not the
 * first.
 *
 * NOTHING IN THIS CHANGE ADDS A REGISTER ROW OR WIDENS ONE. The list the dropdown reads is behind
 * the same `get_current_user` and the same `visible_to_clause` as the workshops page, the round is
 * behind the same `load_ratable_workshop_or_404` it always was, and the refusal panel below is
 * untouched. A hidden entry point would not be a guard — so this one is not hidden: it is labelled
 * on screen with the scope it actually has.
 *
 * ── ANDROID PARITY: THIS WHOLE FEATURE IS WEB-ONLY, AND THE GAP JUST GOT ONE ROW WIDER ──────────
 *
 * `dashboard/page.tsx` already records "Design review — web-only outright. There is no ratings code
 * anywhere under `android/app/src/main`". The dropdown does not change that verdict, but it does add
 * a second affordance the handset lacks — the handset has neither this box nor a chooser — so it is
 * stated here rather than left implicit. If `GET /design-ratings/workshops` is ever built, that
 * route name belongs in the dashboard's parity note beside the rest.
 */

import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Globe2, Lock } from "lucide-react";
import Link from "next/link";

import { useAuth } from "@/components/AuthProvider";
import { PageHeader } from "@/components/PageHeader";
import { refusalText } from "@/components/sketches/ratingsApi";
import { ReviewPanel } from "@/components/sketches/ReviewPanel";
import type { RateableEntityKey } from "@/components/sketches/reviewRanking";
import { Dropdown } from "@/components/ui/Dropdown";
import { RENDER_CAP } from "@/components/ui/selectFilter";
import { listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
/*
  `isUnreachable` FROM ITS OWN HOME AND NOT FROM `lib/offline`. Both work — `lib/offline` re-exports
  the predicates under the names the surfaces that already imported them use — but `failureTriage` is
  where the single verdict lives, and it is what the two components this page mounts (`ReviewPanel`
  and, under it, `ReviewCard`) import. A new caller reaching for the re-export instead is how a
  codebase ends up with six disagreeing copies of one question, which that module's header counts
  out by name.
*/
import { isUnreachable } from "@/lib/failureTriage";
import { canRunDesignWorkshops, roleLabel } from "@/lib/permissions";
import {
  designWorkshopOptions,
  UNTITLED_WORKSHOP,
  workshopCutSentence,
  workshopEmptyLabel,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";

/**
 * The two kinds of piece a pool round can be read over.
 *
 * The same pair as `ENTITIES` on the workshop's own tab and the same pair as the server's
 * `RATEABLE_ENTITIES`. The child rows of a prototype — its stage logs, its material usage — are
 * parts of one piece rather than things a designer ranks against each other, and the API refuses
 * them by name.
 */
/*
  THE HINTS SAY "IN THIS WORKSHOP'S POOL ROUND", NOT "DECLARED FINISHED", and the difference is the
  one the header description got wrong for a year. `pool_visible` returns the WHOLE collection to a
  member of the workshop and to any admin — `peerRoundClosedAt` is only consulted for a stranger —
  and `ranked_payload` does not put `pool_open` on the wire, so nothing on this screen can mark which
  rows were opened. Two of the three audiences therefore see pieces that have NOT been declared
  finished, and telling them otherwise is how a designer comes to believe a colleague released a
  sketch they are still working on.
*/
const ENTITIES: ReadonlyArray<{ key: RateableEntityKey; label: string; hint: string }> = [
  {
    key: "prototype",
    label: "Prototypes",
    hint: "The prototypes in this workshop's pool round."
  },
  {
    key: "sketch",
    label: "Sketches",
    hint: "The sketches in this workshop's pool round — including the ones it never prototyped."
  }
];

/**
 * How many workshops the shortcut list asks for.
 *
 * The server's ceiling rather than a preference: `normalize_pagination` clamps `pageSize` to
 * `MAX_PAGE_SIZE = 100`, so asking for 500 silently returns 100 and would leave this page believing
 * it held the whole of something. The reported `total` is kept beside the rows so the copy can say
 * when it is showing less than there is — a selector that quietly stops at a hundred is
 * indistinguishable from a repository with a hundred workshops in it.
 *
 * THIS NUMBER GOVERNS THE SIZE OF A CONTROL AND NOTHING ELSE. It is not a boundary of what may be
 * read: an id outside these rows goes to the round exactly as an id inside them does. That is the
 * one thing it must never come to mean — `/sketches-and-prototypes` shipped with this same constant
 * deciding a refusal, and told designers their own workshops did not exist.
 *
 * ── AND IT IS `RENDER_CAP`, NOT 100, BECAUSE TWO NUMBERS PRODUCED TWO TRUNCATION SENTENCES ───────
 *
 * It was 100 — the server's ceiling — while `SearchableSelect` draws at most `RENDER_CAP` (80) rows.
 * So on an archive of 350 the panel printed its own notice, "Showing the first 80 of 100", directly
 * above this page's "Showing the first 100 of 350": two cap statements, two different totals, and a
 * reader left to work out which of them describes the list in front of them. Worse, between 81 and
 * 100 rows `truncated` was false, so the page said nothing at all while the panel silently drew 80
 * of them — a cap that states itself only sometimes is the defect rule 10 of the frontend guide
 * exists to forbid. Asking for exactly as many rows as the control can draw makes the panel's own
 * notice unreachable and leaves ONE sentence, this page's, describing the whole truncation.
 */
const CHOOSER_PAGE = RENDER_CAP;

/**
 * How long after the last keystroke the workshop search goes out.
 *
 * 300 ms, the same number as `DesignWorkshopViewersPanel` and `StageReferenceField`, and for their
 * measured reason: the server matches the title with an `ILIKE '%term%'` no index can answer, so
 * every keystroke that escapes the debounce is a scan. Clearing the box does not wait — an empty
 * term is the unnarrowed list, the one request that is always about to be wanted.
 */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * What happened when the shortcut list was asked for, as ONE value with the distinction inside it.
 *
 * Two facts, and they are not one: `unreachable` is "nobody answered", and a `note` is the
 * repository's own words when it answered with a refusal. They lead to different sentences and to
 * the same fallback — the box below — so they are read together and printed apart. `workshops` is
 * left `null` alongside this rather than set to `[]`, because `[]` would let "no workshops are
 * listed for this account" win a race with its own error message.
 */
type ListFailure = { unreachable: boolean; note: string | null };

/*
  ── `workshopLabel` HAS MOVED, AND ITS OWN COMMENT NAMED THE TRIGGER ────────────────────────────

  It read: *"deliberately not imported from either… if a fourth caller wants it, that is the moment
  it moves to `lib/designWorkshops.ts`."* There were SEVEN callers, each with a copy, and between
  them they shipped six different label shapes for one question — this file's `title · date` with a
  `workshopCode` hint, the viewers panel's and the inspectors panel's bare `title · date`, the record
  picker's `title` with a `craft · cluster · date` hint, the questionnaires' `title` alone, and two
  more on the other table. An admin walking between three of those screens in one sitting met three
  spellings of the same workshop.

  The home is `lib/workshopOptions.ts` rather than `lib/designWorkshops.ts`, because the thing being
  shared is not an API concern: it is the LABEL, the grouping, the sort, the "none" row and the four
  empty sentences, for both workshop tables at once, and it is a pure module a spec can test without
  a DOM. Its ruling is that the label is the title ALONE and everything that tells two workshops
  apart goes in the `hint` — which `SearchableSelect` searches as well as draws, so the date and the
  craft stayed reachable when they stopped being in the label.

  `UNTITLED_WORKSHOP` is imported for the one place this file still names a workshop in prose. The
  fallback matters more than it looks: everything below `title` on a summary row is denormalised from
  stage 1 by `promoted_values()`, so a workshop created this morning legitimately has a title and
  nulls everywhere else.
*/

export default function DesignReviewPage() {
  return (
    <Suspense fallback={<PageHeader title="Design review" icon={<Globe2 className="h-5 w-5" aria-hidden />} />}>
      <DesignReview />
    </Suspense>
  );
}

function DesignReview() {
  const router = useRouter();
  const search = useSearchParams();
  const { user } = useAuth();
  const workshopId = (search.get("workshop") ?? "").trim();
  /*
    THE BOX NO LONGER MIRRORS THE URL, AND THAT IS A FIX RATHER THAN A LOSS. It used to be
    `useState(workshopId)`, which seeds ONCE: Back and Forward moved the round underneath while the
    text in the box stayed on whatever id was in the URL when this component first mounted, so the
    page showed one workshop and offered to open another. Re-seeding from the URL on every change is
    not the fix either — it would wipe an id somebody was halfway through typing.

    So the box is now only an INPUT, and the id being read is stated underneath in words (see "Now
    reading"), which cannot go stale because it is derived from the URL on every render.
  */
  const [typed, setTyped] = useState("");
  const [entityKey, setEntityKey] = useState<RateableEntityKey>("prototype");
  /**
   * THE SHORTCUT'S SEARCH, AND WHY IT IS THE SERVER'S RATHER THAN THE PANEL'S OWN FILTER BOX.
   *
   * The dropdown used to pass `searchable`, which filters the options ALREADY IN THE BROWSER. Those
   * options are one page of the archive, and the audience this control was built for is the one the
   * comment beside it names: "an admin's list here is the whole archive". So on an archive of 350,
   * typing a real workshop's title that happens to sit on page 4 answered "No matches" — absence
   * reading as non-existence, inside the one control whose surrounding copy exists to forbid exactly
   * that reading, and two paragraphs below a sentence promising that a workshop missing from the
   * list is not a workshop you cannot read.
   *
   * `listDesignWorkshops` has always accepted `search`, so the fix is to ask the repository instead
   * of the page. `DesignWorkshopViewersPanel` made this same move for the same reason and its header
   * states the rule this follows: ONE search box, and it is the one that can see past the page — two
   * boxes over two different scopes are two boxes, and the smaller one always looks broken.
   */
  const [titleQuery, setTitleQuery] = useState("");
  const [searching, setSearching] = useState(false);

  /**
   * `null` is "not answered yet" and `[]` is "answered, and there are none".
   *
   * The single most repeated bug class in this repository is collapsing those two — telling somebody
   * they have nothing, over a list that has not arrived. Here it would be milder than usual, because
   * the box below works regardless, but the sentence would still be a claim about this account made
   * by a request that had not returned.
   */
  const [workshops, setWorkshops] = useState<DwSummary[] | null>(null);
  const [total, setTotal] = useState(0);
  const [listFailure, setListFailure] = useState<ListFailure | null>(null);
  /** Bumped by Try again. A list that failed with no way back is a dead control. */
  const [attempt, setAttempt] = useState(0);

  const allowed = canRunDesignWorkshops(user);

  useEffect(() => {
    /*
      NOT ASKED FOR SOMEBODY WHO MAY NOT USE THE ANSWER. The refusal panel below renders whatever
      this effect does, so for a field contributor the request would be a round trip whose 200 with
      `items: []` nothing reads. `user` is never null inside `(protected)`: `AppShell` holds the
      frame while `loading` and returns null when there is no session, so there is no first render
      where `allowed` is falsely false and this skips a fetch it should have made.
    */
    if (!allowed) return;
    let cancelled = false;
    setSearching(true);
    /*
      DEBOUNCED, AND ONLY WHEN THERE IS A TERM. An empty box is the unnarrowed list — the request
      that cannot be superseded by the next letter — so clearing goes out at once and a typist's
      surname goes out once instead of six times. Same rule, same 300 ms, as the viewers panel.
    */
    const timer = window.setTimeout(() => {
    void (async () => {
      try {
        /*
          NOT MERGED WITH THIS DEVICE'S LOCAL DRAFTS, and no offline fallback to them either.

          This used to read as "a real difference from `/sketches-and-prototypes`", on the grounds
          that that page could fall back to `listDrafts` because the screen underneath reads and
          writes a LOCAL draft and is useful with no signal. IT NO LONGER CAN: that fallback was
          withdrawn (its header carries the argument — a cached list is stale in the PERMISSIVE
          direction and a chooser is the one control that must not be wired to one), and
          `/design-workshops` now prepends only this device's own unsent work. So the two pages agree
          and this is no longer the exception.

          The reason it was never wired here in the first place is unchanged and is stronger:
          nothing on this page works offline at all. `fetchRoundRanking` is a live read of another
          workshop's round, no pool round is cached anywhere, and `ReviewPanel` says exactly that
          when it cannot be reached. A device list here would be rows that all lead to one sentence
          about a connection.
        */
        const result = await listDesignWorkshops({
          page: 1,
          pageSize: CHOOSER_PAGE,
          search: titleQuery.trim() || undefined
        });
        if (cancelled) return;
        setWorkshops(result.items);
        setTotal(result.total);
        setListFailure(null);
        setSearching(false);
      } catch (error) {
        if (cancelled) return;
        /*
          `isUnreachable` AND NOT `isTransient`, the same reading every other surface in this feature
          takes: `isTransient` counts every 5xx as worth retrying, so a repository that ANSWERED and
          then failed would be reported as a connection problem and send the reader off to look at
          their signal. `refusalText` is asked SECOND and only on the other branch — its own doc
          warns that it has no opinion about the network and will happily print a fetch failure's
          technical message at a designer.
        */
        const offline = isUnreachable(error);
        setSearching(false);
        setListFailure({
          unreachable: offline,
          note: offline
            ? null
            : refusalText(error, "The repository could not list the design workshops you can open.")
        });
      }
    })();
    }, titleQuery.trim() ? SEARCH_DEBOUNCE_MS : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
    /*
      ONE TIMER, AND IT IS THIS EFFECT'S. The debounce lives in the fetch rather than in the input's
      handler so the box and a Try again press go through the same path — §14.5 of the frontend guide
      on why a debounced search and a clicked reload must not be two independent races.
    */
  }, [allowed, attempt, titleQuery]);

  /**
   * Ask for the list again.
   *
   * Returns it to "not answered yet" as well as clearing the failure, so the second attempt shows
   * the still-asking sentence rather than leaving the previous error on screen under a button that
   * looks inert.
   *
   * SAFE TO CALL WITH A ROUND OPEN BELOW, unlike its counterpart on `/sketches-and-prototypes`,
   * where emptying the list drops a four-valued verdict back to `unknown`, unmounts the workspace,
   * and the review tab's unmount flush is a WRITE. Here the list feeds a control and nothing else —
   * `ReviewPanel` is mounted on `workshopId` from the URL, which this does not touch — so the round
   * below is undisturbed by re-asking. That is why this button can sit beside the chooser instead of
   * only inside a whole-page failure state.
   */
  const retry = useCallback(() => {
    setListFailure(null);
    setWorkshops(null);
    setAttempt((n) => n + 1);
  }, []);

  /**
   * WHAT THE READ ANSWERED, as one value with the three states inside it.
   *
   * The page already held the distinction — `workshops === null` for "not answered", `listFailure`
   * for "the read failed", and this file's own comment calls collapsing them "the single most
   * repeated bug class in this repository". This assembles the same three facts into the shape
   * `lib/workshopOptions` reads, so that the sentences under the control are chosen once, by the
   * module that owns them, rather than re-derived here in ternaries that can disagree with each
   * other. Nothing about the page's own five-state machinery moves.
   */
  const listState = useMemo<WorkshopListState<DwSummary>>(
    () =>
      listFailure
        ? { kind: "failed" }
        : workshops === null
          ? { kind: "loading" }
          : { kind: "ok", rows: workshops, total },
    [listFailure, workshops, total]
  );

  /**
   * THE OFFLINE SPLIT IS THIS PAGE'S OWN, AND IT IS BETTER THAN THE SHARED FALLBACK.
   *
   * `deviceLooksOffline()` reads `navigator.onLine`, which is a cheap stand-in for the split the
   * outbox actually makes. This page already makes that split properly — `isUnreachable(error)`
   * distinguishes "nobody answered" from "the repository answered and refused" — so `online` is fed
   * from the verdict rather than from the browser's guess, and the picker's sentence agrees with the
   * failure panel three inches below it instead of being derived from a different question.
   *
   * SCOPED, because `list_design_workshops` narrows by `visible_to_clause` for everybody but an
   * admin: an empty answer here is about this account's grants and its next move is an administrator,
   * which is not the same sentence as "no design workshops have been recorded yet".
   */
  const voice = useMemo<WorkshopListVoice>(
    () => ({ table: "design", scoped: true, online: !listFailure?.unreachable }),
    [listFailure]
  );

  /**
   * The rows, in the one vocabulary — and `offPage: "refuse"`, decided rather than defaulted.
   *
   * ── WHY NOT `"recover"`, WHICH IS WHAT EVERY RECORD FORM PASSES ─────────────────────────────────
   *
   * `"recover"` merges the value being described in from outside the list's scope, under the heading
   * "Already on this record", because the record IS in that workshop and hiding the row would turn a
   * read-only fact into a wrong write. Neither half of that holds here. The id in the URL is not a
   * value stored on a record — it is a DESTINATION somebody pasted, and this page's whole argument is
   * that a workshop missing from this shortcut is not a workshop you cannot read: the id may well
   * belong to a pool round in a workshop this account is not in, where the by-id read would 404 and
   * "recovering" it would mean the shortcut silently offering a row it cannot describe.
   *
   * So the list stays exactly what it says it is — the workshops this account can open — and what is
   * being read is stated underneath in words that cannot go stale, which is what this control already
   * did and is the better answer for a chooser that navigates rather than files.
   *
   * ── AND `workshopCode` IS NO LONGER IN THE HINT ─────────────────────────────────────────────────
   *
   * It went there because two workshops sharing a title and a date drew as two identical options.
   * That reasoning is answered rather than overruled: the hint is now `craft · cluster · the day it
   * ran`, which tells those two apart with facts a reader recognises, while a workshop code is
   * something an admin reads off a join card and cannot place on sight. The code stays REACHABLE —
   * the server's `search` matches `workshopCode` (`design_workshops.py`), and this control's box is
   * the server's — so an admin sent a code in a message can still paste it in and land on the row.
   *
   * MEMOISED, WHICH IS LOAD-BEARING ON A `serverQuery` CONTROL: `SearchableSelect` re-takes its pin
   * snapshot on `options` identity, so a fresh array every render would set state on every render.
   */
  const set = useMemo(
    () => designWorkshopOptions(listState, { group: true, offPage: { mode: "refuse" } }),
    [listState]
  );

  /**
   * Is the round currently open one of the rows in the shortcut list?
   *
   * Used for two presentational things and for NO permission decision: it picks the dropdown's own
   * value (see below) and it picks which "Now reading" sentence is true. A `false` here means only
   * that this account does not hold that workshop, or that it is past `CHOOSER_PAGE`, or that the
   * list never arrived — never that the round may not be read.
   */
  const listedRow = useMemo(
    () => (workshops ?? []).find((summary) => summary.id === workshopId) ?? null,
    [workshops, workshopId]
  );

  /*
    ONE BUILDER, TWO WRITERS — the dropdown and the form both navigate, and a second hand-rolled
    query string is how one of them loses a parameter the day this page gains one. `URLSearchParams`
    rather than concatenation, so an id is escaped once and correctly.

    THE ENTITY CHOICE IS DELIBERATELY NOT IN HERE, because it is not in the URL: it is component
    state, as it was before this change, so a `/design-review` link still cannot land on Sketches.
    That is a real limitation and it is the one this change did not take on — putting it in the URL
    is a separate edit with its own copy, and half of it (a builder writing a parameter nothing
    reads) would be worse than neither.
  */
  const hrefFor = useCallback((id: string) => {
    const query = new URLSearchParams();
    if (id) query.set("workshop", id);
    const suffix = query.toString();
    return suffix ? `/design-review?${suffix}` : "/design-review";
  }, []);

  /*
    THE SELECTOR USES `replace` WHERE THE FORM USES `push`, ON PURPOSE — the asymmetry is the point
    and not an inconsistency left behind.

    `/sketches-and-prototypes`'s header argues the distinction from the other side: pasting an id and
    pressing "Open this round" is a deliberate navigation a reader may well want to undo, so it
    belongs in the history; adjusting a selector is not, and "a selector that fills the history is
    the reason people give up on Back". An admin running an eye down a dropdown of the whole archive
    would otherwise have to press Back once per workshop they glanced at to get off this page.

    THE CONSEQUENCE, STATED RATHER THAN DISCOVERED: a reader who pastes an id and then picks from the
    dropdown has that pasted entry replaced, so Back steps past it to wherever they came from. Same
    trade the sketches page took, and preferable to a history stack that has to be walked out of.
  */
  const chooseListed = useCallback(
    (next: string) => {
      router.replace(hrefFor(next), { scroll: false });
    },
    [hrefFor, router]
  );

  const open = useCallback(
    (event: React.FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      const value = typed.trim();
      if (!value) return;
      /*
        A PASTED LINK IS ACCEPTED AS WELL AS A BARE ID. A designer sent "come and look at this" will
        have the workshop's URL on their clipboard, not its cuid, and refusing it would send them
        editing a string by hand. The last non-empty path segment of a `/design-workshops/{id}/…`
        URL is the id; anything else is passed through untouched and the API answers for it.
      */
      const fromUrl = value.match(/design-workshops\/([^/?#]+)/);
      const id = fromUrl ? fromUrl[1] : value;
      /*
        THE BOX IS NOT CLEARED ON SUBMIT. The only feedback a wrong id gets is the panel's
        one-sentence 404 — which by design cannot say whether the id was mistyped, revoked, or never
        existed — so the reader needs the string they pasted still in front of them to compare it
        with the link they were sent. Emptying the box would take away the only evidence they have.
      */
      router.push(hrefFor(id));
    },
    [hrefFor, router, typed]
  );

  /*
    THE SAME PREDICATE THE API APPLIES FIRST, APPLIED FIRST HERE. Not a narrowing of it: this is a
    mirror of `load_ratable_workshop_or_404`'s own opening refusal, so nobody is stopped here whom
    the server would have served. It names the tier and offers two routes that are always open,
    rather than dead-ending on a padlock.
  */
  if (!canRunDesignWorkshops(user)) {
    return (
      <div>
        <PageHeader title="Design review" icon={<Globe2 className="h-5 w-5" aria-hidden />} />
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">Designer access required</h1>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
            Rating another workshop&apos;s finished pieces is part of the design work itself, so it belongs to
            designers, admins and the master admin. The rounds are read through a route that refuses everybody else
            before it looks at the workshop at all.
          </p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>. An admin can
            raise your access.
          </p>
          <div className="mt-7 flex flex-wrap items-center justify-center gap-3">
            <Link href="/dashboard" className="field-button">
              Back to dashboard
            </Link>
            <Link href="/guide" className="field-button-secondary">
              Open the walkthrough
            </Link>
          </div>
        </section>
      </div>
    );
  }

  const chosen = ENTITIES.find((entity) => entity.key === entityKey) ?? ENTITIES[0];
  /*
    THREE STATES OF ONE LIST, DERIVED ONCE. "Still asking", "answered", and "failed" drive the
    placeholder, the disabled flag and which sentence appears underneath, and deriving them here
    rather than re-testing `workshops === null` in five places is what stops two of those five
    disagreeing — the bug the sketches page fixed by resetting `source` in its retry.
  */
  const asking = workshops === null && !listFailure;
  const ready = workshops !== null && !listFailure;
  /**
   * WHAT THE LIST LEFT OUT, in `selectFilter.ts`'s words rather than this page's own.
   *
   * The arithmetic used to be assembled here and worded three inches below. It is now
   * `workshopCutSentence`, which is the same sentence the panel's own footer would draw and the same
   * one every other workshop picker in the app now prints — so a reader who meets two of these
   * controls in a sitting meets one wording. The paragraph below adds the fact this sentence cannot
   * carry: WHICH eighty they are, which is not the same question as how many there are.
   */
  const workshopCut = workshopCutSentence(set, { term: titleQuery, searchable: true });

  return (
    <div>
      <PageHeader
        title="Design review"
        /*
          ── THIS SENTENCE USED TO CONTRADICT THE ONE BESIDE THE CHOOSER, AND THE CHOOSER'S WAS THE
             TRUE ONE ───────────────────────────────────────────────────────────────────────────────

          It read "that a design workshop has declared finished, opened to every designer on the
          platform", which describes `pool_visible`'s STRANGER branch and only that branch.
          `design_ratings.pool_visible` is `if is_member or admin: return list(subjects)` — the whole
          collection, `peerRoundClosedAt` irrelevant — and `ranked_payload` does not put `pool_open`
          on the wire, so no client can even mark which rows were opened. For the two audiences this
          page is now one click away from (an admin, whose dropdown lists the entire archive, and a
          member reading their own workshop) the header was describing a filter that is not applied.
          A description that is false for the default path is worse than a vague one: it teaches a
          reader that a piece on this screen has been released, when for their own workshops it has
          not.
        */
        description="Sketches and prototypes from another workshop's round: for a workshop you are a member of, or any workshop if you are an admin, the same pieces its own Review tab lists; for everyone else, the ones it has declared finished and opened beyond itself. Rate them, say what you would change, and see where the scores put them."
        icon={<Globe2 className="h-5 w-5" aria-hidden />}
      />

      <section className="panel mb-5 grid gap-5 p-4">
        {/*
          ── WAY IN ONE: A SHORTCUT OVER THE WORKSHOPS THIS ACCOUNT ALREADY HOLDS ─────────────────

          NOT INSIDE THE `<form>` BELOW, and that is deliberate on two counts. It has nothing to
          submit — picking a row navigates — and `SearchableSelect` renders its panel through a
          portal, so its filter box would sit inside this form's React tree while being outside it in
          the DOM. `containEvents` does stop `keydown`/`input` escaping, which is exactly why the
          app's record forms survive a filter box at all; leaning on that here to stop Enter
          submitting a half-typed id would be leaning on it for correctness rather than convenience.
        */}
        <div className="grid gap-1">
          {/* A visible label beside an `ariaLabel` rather than a `<label htmlFor>`: `Dropdown`
              renders a button, not an input, and takes no id. A wrapping `<label>` would be worse
              than useless — it cannot name a button, and it forwards a stray click into the control,
              which is what slams a themed menu shut after one pick. Same arrangement as the
              workshops list page and the viewers panel. */}
          <span className="field-label">A workshop you can open yourself</span>
          {/*
            ── THE SEARCH BOX HAS MOVED INSIDE THE PICKER, AND IT IS STILL THE REPOSITORY'S ────────

            It was a `SearchInput` mounted here, above the control, with `searchable={false}`
            underneath it — the only arrangement available before the primitive could hand a term
            out, and the note beside it said so: "two search boxes over two different scopes are two
            boxes, and the narrower one always looks broken", so the narrower one was switched off.
            The cost was paid by everything that lives in the box that was off: the panel's diacritic
            folding (`fold`, so "Ahmedabad" finds "Ahmedābād"), its ranking, its `role="status"` live
            region, and its distinct "your query matched nothing" sentence.

            `serverQuery` is the arrangement that has neither cost. There is still exactly ONE box,
            the term still goes to `GET /design-workshops?search=`, and this control still never
            filters the array it was handed — `options` already IS the answer to the term, and
            filtering it again would drop rows the server matched on `workshopCode`, which the label
            deliberately no longer shows.

            The page's own "Asking the repository for workshops matching…" line went with it: the
            panel draws `Searching…` in the empty slot and announces it into its live region, in the
            same words every other server-searched picker in the app uses, and the reader is by
            definition looking at the panel while typing into it.
          */}
          <Dropdown
            /*
              EMPTY WHEN THE OPEN ROUND IS NOT ON THIS LIST, rather than a value with no matching
              option. `SearchableSelect` reads its trigger label out of `options`, so an unlisted id
              would render as the placeholder anyway — this says so explicitly instead of depending
              on a lookup miss, and it keeps the control from implying that the workshop being read
              is one of the rows on offer. What is being read is stated underneath, in words.
            */
            value={listedRow ? workshopId : ""}
            onChange={chooseListed}
            options={set.options}
            /*
              THE BOX IS THE SERVER'S. The caller owns the term, the 300 ms debounce and the
              generation counter — all three already existed here and are unchanged; what is new is
              that the box drawing them is the panel's own.

              `truncated` is deliberately NOT passed. `GET /design-workshops` reports a real `total`,
              so the paragraph below prints the honest "Showing the first 80 of 350"; setting the flag
              as well would draw the panel's vaguer "there are more and the server did not say how
              many" under the same list. Two sentences about one cut, in two wordings, is how a reader
              learns that neither is worth reading. The flag arm belongs to a route that cannot count.
            */
            serverQuery={{ value: titleQuery, onChange: setTitleQuery, pending: searching }}
            /*
              THE PLACEHOLDER IS THE TRIGGER'S, AND THE FOUR EMPTY STATES ARE THE PANEL'S. It used to
              carry both, in a five-way ternary that had to re-derive "did a search do this?" — a
              question the panel now answers with a stronger sentence than this page could write,
              because the box goes to the repository and "No matches" is finally a claim about the
              whole list rather than about one page of it.
            */
            placeholder={
              asking ? "Looking for your workshops…" : listFailure ? "This list could not be loaded" : "Choose one of your design workshops"
            }
            emptyLabel={workshopEmptyLabel(listState, voice)}
            /*
              NOT DISABLED WHILE A TERM IS TYPED, even with nothing matching it. Standing the control
              down would take away the box holding the very term that emptied it, leaving the reader
              looking at a dead control with no way back to the list they could see a moment ago —
              which is the failure this page's own copy is written against, arriving through the fix
              for it. With no term, an empty list means the answer is empty and the trigger has
              nothing to open.
            */
            disabled={!ready || (!titleQuery.trim() && set.options.length === 0)}
            ariaLabel="A design workshop you can open yourself"
            /*
              ── ALL THREE PARAGRAPHS, NOT JUST THE SCOPE ONE ────────────────────────────────────────
              The two sentences carrying this control's honesty — "answered, and the answer is none"
              and the truncation notice — were in no `describedBy` and no live region, so the only
              readers who got them were the ones looking at the screen. In the empty case that is
              worse than a gap: the trigger is `disabled`, so it is not focusable, and nothing at all
              announced WHY. `aria-describedby` takes a list; an id that is not in the document is
              ignored, which is what makes naming all three safe while only one or two are rendered.
            */
            describedBy="design-review-scope design-review-empty design-review-truncation"
            /*
              NO `searchable` AND NO `capHint`, AND BOTH ABSENCES ARE THE SAME CHANGE. This control
              passed `searchable={false}` because the only box available filtered CLIENT-SIDE, over
              the one page of rows this component fetched — so the control with the strongest case for
              a search box anywhere in the app (an admin's list here is the whole archive) had the one
              box that could not answer: type a title from page 4 of 350 and it said "No matches", two
              paragraphs below a sentence promising that a workshop missing from this list is not a
              workshop you cannot read. `serverQuery` forces the box ON and points it at the
              repository, which is what that argument wanted all along. `capHint` went with it: its
              whole job was to name the control that DID reach the rest, and that control is now this
              one, so the default clause ("Keep typing to narrow the list") is true for the first time.
            */
            /*
              A dropdown that changes the screen it sits on must NOT advance focus on select: jumping
              away from the control you are adjusting is wrong when the control IS the adjustment.
              Same reason the workshops list page and the sketches chooser pass it.
            */
            advanceOnSelect={false}
          />
          <p id="design-review-scope" className="max-w-3xl text-sm leading-6 text-ink-muted">
            <span className="font-medium text-ink-700">This is a shortcut, not a list of what is open to the pool.</span>{" "}
            It holds the design workshops <em>this account can already open</em> — the ones you created, the ones an
            admin granted you, and every workshop on the platform if you are an admin. The pool round is wider than that by design: any
            workshop can declare a piece finished and open it to designers outside it, and nothing lists those
            workshops, so they reach you as a link or an id in the box below. A workshop missing from this list is not
            a workshop you cannot read.
          </p>
          <p className="max-w-3xl text-xs leading-5 text-ink-500">
            And for a workshop you are a member of, the pool round holds the same pieces its own Review tab lists —
            scored in the pool round rather than the peer round. So this shortcut saves the most work for admins, whose
            list is the whole archive.
          </p>
          {asking ? (
            // "Still asking", in words. Never a bare spinner — that says something is happening
            // without saying what is being waited for — and never the empty state, which would be a
            // claim about this account made by a request that has not returned.
            <p className="text-xs text-ink-500" aria-live="polite">
              Looking for the design workshops you can open…
            </p>
          ) : null}
          {listFailure ? (
            /*
              A FAILED LIST IS NOT A FAILED PAGE, and this has to say so — the box below never went
              through this request. Two variants, because "nobody answered" and "the repository
              answered and refused" are two facts with two next moves; the offline one also has to
              admit that reading a round needs the same connection, or it would be pointing at a box
              that cannot reach anything either.

              THE DISABLED DROPDOWN IS LEFT ON SCREEN, WHICH IS THE OPPOSITE OF WHAT
              `/sketches-and-prototypes` DOES, and the difference is deliberate rather than a rule
              overlooked. That page replaces its whole chooser on a list failure and its comment says
              why — "a disabled dropdown above an error is furniture" — and it can, because with no
              list there is nothing whatever to offer there. Here there is: the box is a complete way
              in, unaffected by this request, so the alternative is a screen where a control the
              reader was about to use has vanished with the layout shifting under them. It stays,
              disabled, with the reason in the placeholder AND in the sentence beside it, so it reads
              as a shortcut that is out of order rather than as a control that never existed.
            */
            <div className="rounded-md border border-line-200 bg-field-100 px-3 py-2" aria-live="polite">
              <p className="text-xs leading-5 text-ink-700">
                {listFailure.unreachable
                  ? "The repository could not be reached, so this shortcut is empty — a list that could not be loaded, not a list with nothing in it. Reading a round needs the same connection, so the box below will not reach one until the signal is back."
                  : listFailure.note}
              </p>
              {listFailure.unreachable ? null : (
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  The box below does not go through this list — it asks the round about one workshop directly — so it
                  still works.
                </p>
              )}
              <button type="button" className="field-button-secondary mt-2" onClick={retry}>
                Try again
              </button>
            </div>
          ) : null}
          {ready && set.options.length === 0 ? (
            /*
              ANSWERED, AND THE ANSWER IS NONE — the ordinary state of a newly onboarded designer
              rather than an edge case, since `assert_can_create_design_workshops` is admin-only and
              both branches of `visible_to_clause` are empty until an admin grants access. Unlike the
              hub page, this does NOT take over the screen: a pool round is precisely the thing such
              a designer CAN read, so the copy points at the box rather than at an admin.
            */
            // `aria-live` as well as `describedBy`, and only on this one: the trigger it describes is
            // disabled here, so a reader can never land on it to hear its description. The truncation
            // paragraph below needs no live region — its trigger is enabled and carries the id.
            <p id="design-review-empty" className="text-xs leading-5 text-ink-500" aria-live="polite">
              {titleQuery.trim()
                ? `No design workshop this account can open matches “${titleQuery.trim()}”. That is this list answering about your own workshops — it says nothing about a pool round somebody sent you, which the box below opens directly.`
                : "No design workshop is listed for this account yet — an admin creates them and grants access. That does not stop you reading a pool round: a link or an id in the box below goes straight to one."}
            </p>
          ) : null}
          {workshopCut ? (
            // Said rather than left as an absence, so "not in this list" cannot be read as "not
            // readable". The cap is the size of a control and nothing else.
            /*
              ── "NEWEST FIRST" WAS A CLAIM ABOUT A DIFFERENT DATE FROM THE ONE ON THE ROWS, AND THE
                 TWO DATES HAVE NOW COME APART FURTHER RATHER THAN CLOSER ──────────────────────────

              The server orders `createdAt: "desc"` (`api/routes/design_workshops.py`) while the rows
              print the day the workshop RAN, which is a stage-1 promoted field typed in by hand — so
              the visible column was never monotonic and a reader told "newest first" was being
              pointed at the wrong end of eighty rows.

              `designWorkshopOptions` now SORTS by that second date, newest first, on the ruling that
              "a workshop entered into the system last is not the workshop that ran last". That fixes
              the reading order and it does not touch the cut: the eighty rows this page holds are
              still whichever eighty the server chose by entry date, and no client-side re-sort can
              recover a row the server already dropped. Which is why this paragraph says both things.
              The first sentence is `selectFilter.ts`'s, so it is the same wording every other
              workshop picker in the app prints; the rest is the part only this list can say.
            */
            <p id="design-review-truncation" className="text-xs leading-5 text-ink-500">
              {workshopCut} Those {set.drawn} are the ones most recently added to the repository.
              That is not the order the dates on the rows read, and it is not the order they are drawn in
              either: the rows are sorted by the day each workshop ran, while the cut was made by the day it
              was entered. So a workshop that ran two years ago and was entered last week is in this list, and
              one that ran last month and was entered last year may not be. Type in the picker&apos;s box to
              bring another into it — that box asks the repository, not this page — or open any of the rest
              from its link or its id below, or from its own page under{" "}
              <Link href="/design-workshops" className="underline">
                Design workshops
              </Link>
              .
            </p>
          ) : null}
        </div>

        {/*
          ── WAY IN TWO: THE BOX, WHICH IS STILL THE ONLY WAY TO EVERYTHING ELSE ──────────────────

          Retained deliberately, and not demoted to a fallback. It is the only route to the set this
          page exists for — a piece opened to the pool by a workshop this account is not in — and it
          is also the route that keeps working when the list above fails.
        */}
        <form onSubmit={open} className="grid gap-1">
          <label className="grid gap-1">
            <span className="field-label">Or any other workshop, from its link or its id</span>
            <input
              className="field-input"
              value={typed}
              onChange={(event) => setTyped(event.target.value)}
              placeholder="Paste the workshop's link, or its id"
              aria-describedby="design-review-why"
            />
          </label>
          <p id="design-review-why" className="max-w-3xl text-sm leading-6 text-ink-muted">
            The round is read one workshop at a time, because the ranking it shows is that workshop&apos;s own row order
            and there is no such thing as a place across two workshops. What does not exist yet is a list of every
            workshop that has opened a piece to the pool — so browsing the whole archive is still a different question
            with no answer, and a piece made outside your own workshops reaches you as a link its designers sent you.
          </p>
          <div>
            <button type="submit" className="field-button" disabled={!typed.trim()}>
              Open this round
            </button>
          </div>
        </form>

        {workshopId ? (
          /*
            WHICH ROUND IS ACTUALLY OPEN, derived from the URL on every render so Back and Forward
            cannot leave it lying — the staleness the seeded box used to have.

            ── THREE SENTENCES, AND THE THIRD IS A FIX ───────────────────────────────────────────────

            There were two: named-in-the-list, and not-in-the-list. But `listedRow` is derived from
            `workshops`, which is `null` for the whole duration of the fetch and stays `null` on
            failure — so "It is not in the shortcut above" was asserting absence from a list that had
            not answered. On the page's own primary flow (a shared link, a reload, a bookmark, Back or
            Forward onto a round) a member or admin whose own workshop IS in the shortcut was told the
            opposite of the truth for the length of the request, and then watched the id turn into a
            title. Both this paragraph and the chooser's status line are `aria-live="polite"`, so a
            screen reader announced the false claim and then the corrected one.

            It is also the exact null-vs-empty conflation this file's own comment calls "the single
            most repeated bug class in this repository", guarded for the placeholder and for the empty
            state and missed here. So `ready` now gates the claim: until the list has answered, the
            honest sentence says the shortcut cannot say either way YET, which is a different fact
            from the shortcut not holding it.
          */
          <p className="text-xs leading-5 text-ink-500" aria-live="polite">
            {listedRow ? (
              <>
                {/* The title alone, which is §2.3's label — a date after it would read as part of
                    the name in a sentence, and the row it came from is on screen above with the day
                    it ran in its hint. `UNTITLED_WORKSHOP` is the shared fallback: everything below
                    `title` on a summary row is denormalised from stage 1, so a workshop created this
                    morning legitimately has a title and nulls everywhere else. */}
                Now reading{" "}
                <span className="font-medium text-ink-700">{listedRow.title.trim() || UNTITLED_WORKSHOP}</span>.
              </>
            ) : ready ? (
              <>
                Now reading the workshop <span className="font-mono text-ink-700">{workshopId}</span>. It is not in the
                shortcut above, which tells you nothing either way — whether the round can be read is answered below.
              </>
            ) : (
              <>
                Now reading the workshop <span className="font-mono text-ink-700">{workshopId}</span>.{" "}
                {asking
                  ? "The shortcut above has not answered yet, so it cannot say whether this is one of yours."
                  : "The shortcut above could not be loaded, so it cannot say whether this is one of yours."}{" "}
                Either way it would tell you nothing about the round — that is answered below.
              </>
            )}
          </p>
        ) : null}
      </section>

      {workshopId ? (
        <>
          {/*
            THE TWO RATEABLE ENTITIES ARE A CHOICE HERE TOO, for the reason in the header: a sketch
            carries `peerRoundClosedAt` exactly as a prototype does, so a workshop can open one to
            the pool, and a page with no chooser made those sketches unreachable at level 2 for
            everybody. `pool_visible` decides per row what any given reader sees, so choosing a kind
            with nothing open in it is answered by the API and reported as an empty round, not
            prevented here.
          */}
          <div role="group" aria-label="What to review" className="mb-4 flex flex-wrap items-center gap-2">
            {ENTITIES.map((entity) => {
              const active = entity.key === entityKey;
              return (
                <button
                  key={entity.key}
                  type="button"
                  aria-pressed={active}
                  onClick={() => setEntityKey(entity.key)}
                  className={
                    active
                      ? "rounded-md border border-purple-700 bg-purple-700 px-3 py-1.5 text-sm font-semibold text-white"
                      : "rounded-md border border-line-200 bg-card px-3 py-1.5 text-sm font-medium text-ink-700 hover:border-purple-300 hover:bg-purple-50"
                  }
                >
                  {entity.label}
                  {/* The choice is carried by a word as well as by the fill — colour never carries
                      meaning on its own in this app. */}
                  <span className={active ? "ml-2 text-[11px] font-normal text-white/80" : "sr-only"}>
                    {active ? "showing" : "not showing"}
                  </span>
                </button>
              );
            })}
            <span className="text-xs text-ink-muted">{chosen.hint}</span>
          </div>
          {/*
            `readsStageRows` IS FALSE HERE AND THAT IS THE WHOLE DIFFERENCE BETWEEN THE TWO LEVELS.
            The panel then never touches the workshop's draft, never asks for its stage, and offers no
            arrangement controls — because the ordinal a reorder would write belongs to a stage this
            caller is refused. The rating, which is what a pool reviewer actually contributes, works
            exactly as it does inside the workshop.

            IT IS ALSO WHY THE CHOOSER ABOVE IS NOT A GATE. `readStageRows` routes to `loadDraft`
            here, which creates nothing, so mounting against a stranger's id mints nothing and the
            404 is a complete and safe answer. `/sketches-and-prototypes` cannot do that — its upload
            half goes through `ensureDraft`, a check-AND-CREATE — which is why that page asks the API
            about the id before mounting and this one hands it straight over.
          */}
          <ReviewPanel workshopId={workshopId} round="POOL" readsStageRows={false} entityKey={entityKey} />
        </>
      ) : (
        <p className="panel px-4 py-6 text-center text-sm text-ink-muted">
          Nothing is open yet. Choose one of your own workshops above, or paste the link a workshop&apos;s designers
          sent you, to read its finished sketches and prototypes.
        </p>
      )}
    </div>
  );
}
