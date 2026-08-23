"use client";

/**
 * Sketches & Prototypes, CHOSEN-WORKSHOP-FIRST — the same screen as the workshop's own tab, entered
 * from the other end.
 *
 * ── WHY THIS ROUTE EXISTS AT ALL, GIVEN THAT THE SCREEN ALREADY DID ─────────────────────────────
 *
 * `/design-workshops/[id]/sketches-and-prototypes` can only be opened by somebody who has already
 * navigated into the workshop that owns it, because the id is a segment of its path. A designer who
 * knows they want to upload the drawing in their hand, and does not remember which of their four
 * workshops it belongs to, had to go to the workshops list, guess a row, open it, and look for the
 * tab. This page asks the question the other way round: WHICH WORKSHOP, first, out of the ones this
 * account can actually open — and then mounts the identical screen underneath.
 *
 * It is a TOP-LEVEL SIBLING of `/design-review` and not a child of `/design-workshops/:id`, and the
 * reason is mechanical rather than aesthetic: a route can only sit beneath `/design-workshops/:id`
 * if an id is known before the page renders, and being useful when it is NOT known is this page's
 * entire purpose. `lib/permissions.ts` carries the consequence — because the path is not nested, the
 * `/design-workshops` prefix rule does not cover it and it needed a `ROUTE_GUARDS` row of its own
 * (it has one, with its twin in `docs/PERMISSIONS.md` §5), while the per-workshop page needs none.
 *
 * ── THE BODY IS THE SHARED COMPONENT, NOT A SECOND IMPLEMENTATION ───────────────────────────────
 *
 * Everything below the chooser is `components/sketches/SketchesWorkspace`, which the per-workshop
 * page also mounts. Read its header before changing anything about the tabs, the entity chooser, the
 * registry read or the `readsStageRows` decision: it enumerates what a fork would silently lose, and
 * it is where the answer belongs. What lives HERE is the three things that are genuinely this
 * route's own — the chooser, the `?workshop=` half of the URL, and every way this page can fail to
 * have a workshop to show.
 *
 * ── WHAT THE CHOOSER'S LIST IS, EXACTLY ─────────────────────────────────────────────────────────
 *
 * `GET /design-workshops` (`listDesignWorkshops`), one page of it. That endpoint has NO role
 * dependency — only `get_current_user` — and scopes rows with `visible_to_clause`, which for a
 * non-admin is `createdById = me OR viewers.some(userId = me)`. Since creating a design workshop is
 * admin-only, a designer's list is in practice exactly their `DesignWorkshopViewer` grants, and an
 * admin's is the whole archive. Soft-deleted rows are excluded for everybody.
 *
 * WHICH IS THE SAME DOOR `load_workshop_or_404` OPENS — creator, admin, viewer grant — and that is
 * what makes it the right list to OFFER on this page. What it is not, and what an earlier version of
 * this comment claimed it was, is a complete enumeration of that door, so it cannot be the thing
 * that REFUSES. Two gaps, both real and both found in review:
 *
 *   * IT IS ONE PAGE. `CHOOSER_PAGE` rows, `createdAt desc`. An admin's list is the whole archive
 *     and a long-running designer's can pass a hundred grants, so the hundred-and-first workshop is
 *     absent from a list that is working perfectly.
 *   * `list_design_workshops` HARDCODES `deletedAt: None` for every caller, while
 *     `load_workshop_or_404` deliberately admits an ADMIN to a soft-deleted workshop so that it can
 *     be found and restored. So for exactly the account that most needs it, the list is narrower
 *     than the door.
 *
 * Presence in the page therefore PROVES a workshop may be opened and absence proves nothing, which
 * is why the refusal below asks the API about the single id rather than concluding from the list —
 * see "A WORKSHOP ID IN THE URL…". The list is still the right list: the shared workspace runs with
 * `readsStageRows` true — it reads and writes the workshop's own stage rows — so every workshop it
 * is handed must be one this account can open. It is NOT the set a POOL reviewer may rate: the pool
 * round is by construction workshops the caller is not a member of,
 * `load_ratable_workshop_or_404` exists as a second narrow door precisely so it does not reuse the
 * first, and no endpoint returns that set. Do not repoint this chooser at `/design-review`.
 *
 * ── THE LIST HAS THREE STATES AND THEY ARE NOT TWO ──────────────────────────────────────────────
 *
 * "Still asking", "there are genuinely none", and "we could not ask" are three different facts with
 * three different next moves, and collapsing them is named in this repository as its single most
 * repeated bug class — telling a designer they have no workshops over a fortnight of fieldwork they
 * can still read. So:
 *
 *   * `null` is "still asking" and renders a sentence that says so, never the empty state.
 *   * `[]` from a server that ANSWERED is "you have none", and for a newly onboarded designer that
 *     is the ordinary state rather than an edge case — a designer cannot create a workshop
 *     (`assert_can_create_design_workshops` is admin-only), so both branches of `visible_to_clause`
 *     are empty until an admin creates one and grants them access. The copy therefore says who to
 *     ask, not "nothing found".
 *   * AN UNREACHABLE REPOSITORY OFFERS NO CHOOSER AT ALL. It gets a panel of its own that says the
 *     repository could not be reached, and a Try again. It does NOT fall back to this device's
 *     cached workshops, and that is a reversal — this page used to list `listDrafts` +
 *     `draftSummary` here — so the reason is recorded rather than left as an absence.
 *
 *     THE DEVICE'S LIST IS THE SERVER'S ANSWER AS OF THE LAST SYNC, AND IT IS STALE IN THE
 *     PERMISSIVE DIRECTION. Every row in it was once served to this account, so no stranger's
 *     workshop can appear; but `draftSummary` keys rows on `remoteId ?? localId` and hardcodes
 *     `deletedAt: null`, so a workshop whose viewer grant has since been REVOKED — or which has
 *     since been soft-deleted — is still in this browser and still matches a bookmarked
 *     `?workshop=`. Offline there is nobody to ask, which is the whole premise of the branch, so
 *     that staleness cannot be detected here at all and it has no bound: a laptop that synced in
 *     March offers March's grants in September.
 *
 *     WHICH LIST IS AUTHORITATIVE: the server's, and only the server's. Access is decided by
 *     `visible_to_clause` over `DesignWorkshopViewer` rows this client never sees, and it changes
 *     without the client hearing. A cache of a past answer is therefore evidence about the past, and
 *     the requirement this page is now held to is that a designer may only ever be OFFERED a
 *     workshop they currently have access to. A control whose whole job is offering is the one place
 *     a permissive stale list must not be wired into, so the offer is withheld rather than qualified
 *     with a sentence.
 *
 *     WHAT THAT COSTS, STATED HONESTLY, because it is a real loss and the previous decision went the
 *     other way: a designer in a courtyard with no signal and a drawing in hand can no longer reach
 *     this screen through this route. Two things bound it. The per-workshop URL
 *     (`/design-workshops/[id]/sketches-and-prototypes`) is unchanged, so somebody who is already
 *     inside a workshop keeps working exactly as before; and the repository always had the last word
 *     anyway — the queued `PUT .../stages/…` is refused on sync — so what the old branch really
 *     bought was work that might be thrown away, offered against a workshop that might not be
 *     theirs. Bringing it back honestly needs a dated, server-issued grant cached beside the row so
 *     the client can say how old its evidence is; presence in an IndexedDB store cannot say that.
 *   * A server that SPOKE and refused gets its own sentence shown, and no fallback. `isUnreachable`
 *     and not `isTransient` for the reason the workshops list page states at length: the latter
 *     counts every 5xx as worth retrying, so a repository that answered and then failed would be
 *     reported as a connection problem and send the designer to look at their signal.
 *
 * ── A WORKSHOP ID IN THE URL THAT THIS ACCOUNT CANNOT OPEN ──────────────────────────────────────
 *
 * ONE SENTENCE, AND IT DOES NOT SAY WHICH. A stale bookmark, a typo, a workshop that was deleted,
 * and a colleague's workshop this account was never granted are all answered identically, because
 * that is what the API does: `load_workshop_or_404` returns the same 404 and the same "Record not
 * found" for a row that never existed as for one the caller may not reach, and its own comment says
 * a 403 there "would confirm the id exists to exactly the people the clause is turning away". A page
 * that told them apart would turn any designer login into an enumeration of the ministry's records
 * one paste at a time, and it would do it in words rather than in status codes.
 *
 * WHERE THAT ANSWER COMES FROM, WHICH IS THE PART THAT WAS WRONG. The chooser's page of rows decides
 * only the YES: an id on it is one `visible_to_clause` just returned for this account, so the screen
 * mounts with no further question. An id that is ABSENT gets ONE READ of that single id
 * (`getDesignWorkshop` → `GET /design-workshops/{id}`, which is `load_workshop_or_404` itself) and
 * the API's own verdict is used. This page originally read absence as refusal, and that is a defect
 * with copy attached: past `CHOOSER_PAGE` rows — an admin's archive, or a designer with more grants
 * than that — a shared link to a perfectly ordinary workshop rendered "that workshop is not one this
 * account can open … the repository answers all four the same way", told to somebody the repository
 * would have answered, about a workshop the nested twin URL opens on the next click. Four causes
 * offered and the true one absent from the list. The extra request happens only on that miss, and
 * `GET /design-workshops/{id}` is chosen over the lighter `/{id}/stages/{key}` because the lighter
 * one 404s for an unknown STAGE key with the identical status, so a registry rename would silently
 * turn every workshop into a refusal.
 *
 * THE THIRD ANSWER IS "COULD NOT ASK", and it is not folded into the refusal. Only a 404 is read as
 * a refusal, because 404 is the whole of what `load_workshop_or_404` says and it says it for every
 * cause the refusal panel lists. An unreachable repository, or one that answers the read with a 500
 * or an expired session, gets a panel that says the check could not be made — with the server's own
 * words when there were any — and a Try again. An unreachable repository never gets as far as this
 * question: with no list there is no chooser and no mounted screen, only the offline panel above.
 *
 * WHY THE PAGE STILL DECIDES BEFORE MOUNTING THE SCREEN, rather than mounting it and letting the
 * workspace's own requests answer, which is what `/design-review` does with its pasted id: because
 * this screen's upload half WRITES. It goes through `ensureDraft`, which is a check-AND-CREATE, so
 * mounting it against an id this account cannot open mints a blank session-owned local draft for a
 * stranger's workshop — and `design-workshops/page.tsx` prepends exactly such drafts to this
 * device's own list whenever it is offline. That is a shipped bug, written up in
 * `components/sketches/stageRows.ts`'s header. A read-only question asked first is what avoids it:
 * the gate is now the same door the workspace is about to walk through, asked in a way that cannot
 * create anything.
 *
 * AND THE GATE DISCLOSES NOTHING, which is why it is allowed to be a gate. Every yes is a 200 the
 * API just gave this account and every no is its 404 — the page adds no opinion of its own, and one
 * sentence covers every cause. It cannot even be used to time the difference: the miss path makes
 * the same single request whatever the answer turns out to be.
 *
 * ── THE PERMISSION GATE ─────────────────────────────────────────────────────────────────────────
 *
 * `canRunDesignWorkshops` — Designer, Admin, Master Admin, so a PROFESSOR IS REFUSED even though a
 * professor outranks a designer nearly everywhere else. This block is the SECOND of three lines:
 * `ROUTE_GUARDS` in `lib/permissions.ts` is applied by `AppShell` above every page and is the first,
 * the API's own dependencies are the third, and this exists because a page that renders its shell
 * before refusing shows a list of workshop NAMES, which is not a shell worth rendering to somebody
 * who may not act on any of them. Note that on the chooser this is a NARROWING and not a mirror:
 * `list_design_workshops` would answer a professor with an empty list rather than a refusal, so what
 * is withheld here is a page that would have shown nothing but its own empty state.
 *
 * ── THE URL ─────────────────────────────────────────────────────────────────────────────────────
 *
 * `?workshop=<id>` so the choice survives a reload and can be sent to a colleague, and `?tab=review`
 * alongside it so a link can land on the round rather than on the upload form. Both are written
 * through one builder (`hrefFor`) for a reason the per-workshop route illustrates: its tab writer
 * rebuilds the query from empty, which is harmless there and would erase `?workshop=` here. Two
 * writers and one builder is the arrangement that cannot drop a parameter.
 *
 * `replace` and not `push`, for both. Choosing a workshop and choosing a tab are adjustments to one
 * screen, and `push` would make Back walk backwards through every adjustment before it left the
 * page. `/design-review` uses `push` for what looks like the same act, and the difference is real:
 * there the workshop arrives by pasting into a form and pressing "Open this round", which is a
 * deliberate navigation a reader may want to undo; here it is a selector, and a selector that fills
 * the history is the reason people give up on Back.
 */

import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { CloudOff, Lock, PencilRuler } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import {
  SketchesWorkspace,
  sketchesTabFromQuery,
  type SketchesTab
} from "@/components/sketches/SketchesWorkspace";
import { Dropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { ApiError } from "@/lib/api";
import { getDesignWorkshop, listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import { isUnreachable } from "@/lib/offline";
import { canRunDesignWorkshops, roleLabel } from "@/lib/permissions";

/**
 * How many workshops the chooser asks for.
 *
 * A page, not "everything", and the number is the server's ceiling rather than a preference:
 * `normalize_pagination` clamps `pageSize` to `MAX_PAGE_SIZE = 100`, so asking for 500 silently
 * gives 100 and would leave this page believing it had the whole archive. The reported `total` is
 * kept beside the rows so the chooser can say when it is showing less than there is — the same
 * decision `DesignWorkshopViewersPanel` documents, for the same reason: a selector that quietly
 * stops at a hundred is indistinguishable from a repository with a hundred workshops in it.
 *
 * WHAT THIS NUMBER MUST NEVER AGAIN GOVERN IS A REFUSAL. It is the size of a control, and for a
 * while it was also the boundary of what this page believed the account could open — so raising or
 * lowering it changed who was told a real workshop did not exist. The single-id read in
 * `ChooseWorkshopThenSketches` now asks the API about any id this page of rows does not contain,
 * which leaves the cap answering the one question it is qualified to answer: how many rows are in
 * the dropdown.
 */
const CHOOSER_PAGE = 100;

/**
 * WHY THERE IS NO `ListSource` HERE ANY MORE.
 *
 * There used to be one — `"server" | "device"` — because the rows on screen could come from this
 * browser's IndexedDB copies when the repository could not be reached, and every sentence and every
 * verdict on the page had to know which. They cannot any more: the only list allowed to fill the
 * chooser is `GET /design-workshops`, for the reason the header sets out at length (a cached access
 * list is stale in the permissive direction and has no bound). So "where did these rows come from"
 * has one answer, and a state variable holding a constant is a state variable somebody will branch
 * on again. `offline` below is the fact that replaced it, and it is about the REQUEST rather than
 * about the rows: it is only ever true when there are no rows at all.
 */

/**
 * The answer to "may this account open the id in the URL", which is FOUR-VALUED and not a boolean.
 *
 * `unknown` is "nobody has answered yet" and must never render as a refusal — the same rule the list
 * itself follows above, for the same reason and one layer down. `uncheckable` is the one that a
 * boolean cannot hold and that this page shipped without: the chooser's page of rows does not contain
 * the id AND the single-id read could not be made, which is not the repository refusing. Collapsing
 * it into `refused` prints "the repository answers all four the same way" over a repository that
 * never spoke, and hides the Try again that is the only way out.
 */
type ChosenVerdict = "unknown" | "allowed" | "refused" | "uncheckable";

/**
 * Title plus the day it ran, never an id.
 *
 * The same shape `DesignWorkshopViewersPanel.designWorkshopLabel` gives — deliberately not imported,
 * because that one is a module-private helper in a settings panel and lifting it would make an
 * admin screen's formatting a shared contract. If a third caller wants it, that is the moment it
 * moves to `lib/designWorkshops.ts`. The fallback title matters more than it looks: everything below
 * `title` on a summary row is denormalised from stage 1 by `promoted_values()`, so a workshop
 * created this morning legitimately has a title and nulls everywhere else — and a workshop created
 * offline may not even have that.
 */
function workshopLabel(summary: DwSummary): string {
  const title = summary.title?.trim() || "Untitled design workshop";
  const when = formatDate(summary.startDate ?? summary.createdAt ?? null);
  return when === "-" ? title : `${title} · ${when}`;
}

export default function SketchesAndPrototypesHubPage() {
  return (
    <Suspense fallback={<PageHeader title="Sketches and Prototypes" icon={<PencilRuler className="h-5 w-5" aria-hidden />} />}>
      <ChooseWorkshopThenSketches />
    </Suspense>
  );
}

function ChooseWorkshopThenSketches() {
  const router = useRouter();
  const search = useSearchParams();
  const { user } = useAuth();

  const chosen = (search.get("workshop") ?? "").trim();
  const tab = sketchesTabFromQuery(search.get("tab"));

  /**
   * null is "not answered yet" and `[]` is "answered, and there are none". See the header — these
   * are two states and the screen must never render the second one while it is in the first.
   */
  const [workshops, setWorkshops] = useState<DwSummary[] | null>(null);
  const [total, setTotal] = useState(0);
  /**
   * The repository could not be REACHED (as against answered-and-refused, which is `problem`).
   *
   * A separate flag rather than a sentence hung off `problem`, because the two have different next
   * moves and different panels: one is the signal in this courtyard, the other is somebody else's to
   * fix. Both leave `workshops` null, so neither can be mistaken for "you have none".
   */
  const [offline, setOffline] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  /** Bumped by the Try again button. A load that failed with no way out is a dead page. */
  const [attempt, setAttempt] = useState(0);
  /**
   * The API's answer about ONE id that the chooser's page of rows does not contain, kept WITH the id
   * it is about.
   *
   * Keyed rather than bare, because the id can change under it: choosing another workshop while this
   * read is in flight, or arriving with a second `?workshop=` from the same tab, would otherwise let
   * a verdict about the previous id decide the current one — an "allowed" leaking one row sideways
   * mounts the writing screen against an id nothing has vouched for. Every read of this compares the
   * stored id with `chosen` first, and a mismatch counts as "not answered yet".
   *
   * `note` carries what the repository SAID when it said something other than 404 — see the catch
   * below. It is the difference between "nobody could be reached" and "the repository answered, with
   * an error", which are two sentences and one verdict.
   */
  const [checked, setChecked] = useState<{
    id: string;
    verdict: Exclude<ChosenVerdict, "unknown">;
    note?: string | null;
  } | null>(null);

  const allowed = canRunDesignWorkshops(user);

  useEffect(() => {
    /*
      NOT ASKED FOR SOMEBODY WHO MAY NOT USE THE ANSWER. The refusal below is rendered whatever this
      effect does, so the request would only ever have been a round trip whose 200 with `items: []`
      nothing reads. `user` is never null inside `(protected)`: `AppShell` holds the frame while
      `loading` and returns null when there is no session, so there is no first render where
      `allowed` is falsely false and this skips a fetch it should have made.
    */
    if (!allowed) return;
    let cancelled = false;
    void (async () => {
      try {
        /*
          THE SERVER'S LIST AS IT STANDS, AND IT IS THE ONLY LIST THIS PAGE WILL DRAW. It is not
          merged with this device's local drafts the way `/design-workshops` merges them — which is a
          deliberate difference and not an omission, because that page LISTS workshops and this one
          hands one to a screen that writes.

          Two independent reasons, and each is sufficient on its own. (1) ACCESS. A cached row proves
          this account could open that workshop when this browser last synced, which is not the same
          claim as "can open it now" and is wrong in the permissive direction — see the header. (2) A
          DEAD END. A workshop created offline has no server row, and the upload tab refuses to attach
          anything until `readStageRows` has folded the repository's copy of the stage in
          (`reconciled`); on a workshop the repository has never heard of there is no copy to fold, so
          the designer would arrive at a panel saying the stage could not be read and be unable to do
          the one thing they came for.
        */
        const result = await listDesignWorkshops({ page: 1, pageSize: CHOOSER_PAGE });
        if (cancelled) return;
        setWorkshops(result.items);
        setTotal(result.total);
        setOffline(false);
        setProblem(null);
      } catch (error) {
        if (cancelled) return;
        if (isUnreachable(error)) {
          /*
            NO LIST, AND THEREFORE NO CHOOSER. `workshops` is left NULL and `offline` carries the
            reason to the panel below.

            This is where `listDrafts` + `draftSummary` used to be. The header records the reversal in
            full; the short version is that presence in this browser's store is evidence about a past
            grant with no bound on its age, and the one control that must never be permissive about
            access is the one that offers it.
          */
          setOffline(true);
          setProblem(null);
          return;
        }
        /*
          `workshops` IS LEFT NULL, deliberately. Setting it to `[]` here would let the "you have no
          design workshops" empty state win a race with its own error message, which is the same
          collapse the header is about — from the other side.
        */
        setOffline(false);
        setProblem(
          error instanceof Error && error.message
            ? error.message
            : "The repository could not list your design workshops."
        );
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [allowed, attempt]);

  /**
   * Ask again after a failure.
   *
   * It clears the failure and returns the list to "not answered yet" as well as bumping the token,
   * so the second attempt shows the still-asking sentence rather than leaving the previous refusal
   * on screen under a button that looks inert.
   *
   * ONLY EVER CALLED FROM A STATE WITH NO WORKSPACE MOUNTED — the two list failures and the
   * could-not-check panel — and it must stay that way: emptying `workshops` while the screen below is
   * showing would drop the verdict back to `unknown`, unmount the workspace, and the review tab's
   * unmount flush is a WRITE. A Try again beside the chooser would need to leave the rows alone.
   */
  const retry = useCallback(() => {
    setProblem(null);
    setWorkshops(null);
    /*
      `offline` IS CLEARED TOO, and its predecessor `source` was the same trap: a flag describing the
      LAST attempt, left standing over a fresh one, prints a claim about a list that has not answered
      yet — "the repository could not be reached" directly beside "looking for the design workshops
      you can open…", with the Try again gone for the length of an offline request timeout. A retry
      returns every one of these to the state a first load starts from.
    */
    setOffline(false);
    /*
      And the single-id verdict, so a "could not check" is genuinely re-asked. The probe below skips
      any id it has already answered for, which is what stops it looping; without this line that
      memory would survive the button meant to clear it and Try again would re-list the workshops
      while leaving the same "could not check" panel on screen.
    */
    setChecked(null);
    setAttempt((n) => n + 1);
  }, []);

  /**
   * Is the chosen id one of the rows currently on screen?
   *
   * The cheap half of the gate and the only half that can say YES on its own: these rows came from
   * `visible_to_clause` moments ago, over the network, for this account. `false` here is NOT a
   * refusal — see `ChosenVerdict` and the header.
   */
  const listed = useMemo(
    () => workshops !== null && workshops.some((summary) => summary.id === chosen),
    [workshops, chosen]
  );

  useEffect(() => {
    /*
      THE ONE-ID READ, AND EVERY CONDITION BELOW IS A REASON NOT TO MAKE IT.

      This is the fix for a refusal that was decided by a page size: `CHOOSER_PAGE` rows come back
      `createdAt desc`, so an admin whose archive is longer than that — or a designer with more
      grants than that — could be handed a link to a workshop the API serves perfectly well and be
      told by this page that it may not be one they can open. `GET /design-workshops/{id}` IS
      `load_workshop_or_404`, so asking it moves the verdict from "is it in the hundred rows I
      happen to be holding" to "does the door open", which is the question the screen underneath
      needs answered.

      It is a READ and it creates nothing, which is why it can be made before the gate rather than
      after: the thing this page exists to avoid is `ensureDraft`, a check-AND-CREATE, running
      against a workshop this account cannot open.

      Not made when: the account is refused outright (nothing below renders anyway); no id has been
      chosen; the list has not answered, because an id absent from a list that has not arrived is not
      absent from anything yet; the id IS in the list, which already answers yes; or this id has
      already been answered for, which is also what stops the state this effect sets from
      re-triggering it.

      There is no longer an offline case to skip: an unreachable repository leaves `workshops` null
      and renders its own panel, so the `workshops === null` guard already covers it.
    */
    if (!allowed) return;
    if (!chosen || workshops === null || listed) return;
    if (checked?.id === chosen) return;
    let cancelled = false;
    void (async () => {
      try {
        await getDesignWorkshop(chosen);
        if (cancelled) return;
        setChecked({ id: chosen, verdict: "allowed" });
      } catch (error) {
        if (cancelled) return;
        /*
          THREE OUTCOMES OUT OF ONE CATCH, AND ONLY ONE OF THEM IS A REFUSAL.

          `isUnreachable` AND NOT `isTransient` for the first, the same reading the list above takes
          and for the reason the workshops list page argues at length: `isTransient` counts every 5xx
          as worth retrying, so a repository that ANSWERED and then failed would be reported as a
          connection problem and send the designer to look at their signal.

          ONLY A 404 IS A REFUSAL, and the narrowness is the point. `load_workshop_or_404` answers
          404 for every cause it has — no such id, not yours, deleted — which is exactly the set of
          causes the refusal panel lists without choosing between them. A 500, a 502 from a proxy, a
          401 on a session that expired between two requests: none of those is "that workshop is not
          one this account can open", and printing that sentence over one of them repeats, one
          workshop at a time, the mistake this whole gate was just corrected for. They land in
          `uncheckable` WITH what the server said, so the reader sees the error rather than a
          verdict invented from it.

          What is never done is mounting the screen. An answer this page did not understand is not
          permission, and the workspace's upload half writes.
        */
        if (isUnreachable(error)) {
          setChecked({ id: chosen, verdict: "uncheckable", note: null });
          return;
        }
        if (error instanceof ApiError && error.status === 404) {
          setChecked({ id: chosen, verdict: "refused" });
          return;
        }
        setChecked({
          id: chosen,
          verdict: "uncheckable",
          note:
            error instanceof Error && error.message
              ? error.message
              : "The repository could not say whether that workshop can be opened."
        });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [allowed, chosen, workshops, listed, checked]);

  const options = useMemo<DropdownOption[]>(
    () => (workshops ?? []).map((summary) => ({ value: summary.id, label: workshopLabel(summary) })),
    [workshops]
  );

  /**
   * May this account open the id in the URL?
   *
   * EVERY YES IS THE SERVER'S, WHICH IS THE CHANGE. There used to be a second source — offline, the
   * chooser WAS this device's cached list, so being on it counted as permission — and that is exactly
   * the permissive stale answer this page no longer gives. Now:
   *
   *   1. The id is on screen in the chooser. `visible_to_clause` just returned it, so nothing more
   *      is asked — this is the ordinary path and it costs no request.
   *   2. Otherwise the single-id read decides, and until it lands this is `unknown` rather than
   *      `refused` — which is not cosmetic. See the mount guard below: rendering the workspace for a
   *      moment and taking it away is a WRITE, and rendering the refusal for a moment tells a
   *      designer their own workshop is not theirs.
   *
   * With no connection there is no yes at all: `workshops` is null, so this is `unknown` and the
   * offline panel is what renders.
   */
  const verdict = useMemo<ChosenVerdict>(() => {
    if (!chosen || workshops === null) return "unknown";
    if (listed) return "allowed";
    return checked?.id === chosen ? checked.verdict : "unknown";
  }, [chosen, workshops, listed, checked]);

  /**
   * What the repository said, when the reason the check failed is that it said something.
   *
   * Null both when there is nothing to report and when the failure was a dropped connection — the
   * panel below reads it as "did the server speak", so it must not be filled in with a sentence this
   * page wrote itself.
   */
  const uncheckableNote =
    checked?.id === chosen && checked.verdict === "uncheckable" ? checked.note ?? null : null;

  /*
    ONE BUILDER, TWO WRITERS. `URLSearchParams` rather than string concatenation so an id is escaped
    once and correctly, and `tab=upload` is omitted rather than written — the same convention the
    per-workshop route follows, so the two pages produce the same shape of link for the same state.
  */
  const hrefFor = useCallback((workshopId: string, next: SketchesTab) => {
    const query = new URLSearchParams();
    if (workshopId) query.set("workshop", workshopId);
    if (next === "review") query.set("tab", next);
    const suffix = query.toString();
    return suffix ? `/sketches-and-prototypes?${suffix}` : "/sketches-and-prototypes";
  }, []);

  const chooseWorkshop = useCallback(
    (next: string) => {
      // The chosen tab is carried across, so somebody who was looking at the round for one workshop
      // stays on the round when they switch. Dropping it would silently answer a different question.
      router.replace(hrefFor(next, tab), { scroll: false });
    },
    [hrefFor, router, tab]
  );

  const changeTab = useCallback(
    (next: SketchesTab) => {
      router.replace(hrefFor(chosen, next), { scroll: false });
    },
    [chosen, hrefFor, router]
  );

  const header = (
    <PageHeader
      title="Sketches and Prototypes"
      description="Pick one of your design workshops and work on its pieces — add drawings, photographs and 3D models on the Upload tab, rate and rank them on the Review tab."
      icon={<PencilRuler className="h-5 w-5" aria-hidden />}
    />
  );

  /*
    THE SAME PREDICATE THE TABLE AND THE API APPLY, APPLIED HERE TOO. Not a narrowing of the API's
    door and not the first line of defence — see the header. It names the tier and offers two routes
    that are always open, rather than dead-ending on a padlock.
  */
  if (!allowed) {
    return (
      <div>
        {header}
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          {/* `h2`, where `/design-review`'s otherwise identical lock panel uses `h1`: `PageHeader`
              above has already emitted the page's one `h1`, and two of them leave a screen reader's
              heading list with no top level. Not copied for the sake of symmetry. */}
          <h2 className="font-display text-xl font-bold tracking-tight text-ink-900">Designer access required</h2>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
            Sketches and prototypes are a named designer&apos;s work in progress, uploaded to a workshop and then
            ranked against other designers&apos; pieces under the name of whoever ranked them. So this page belongs to
            designers, admins and the master admin.
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

  /*
    THE REPOSITORY COULD NOT BE REACHED — NO CHOOSER, AND NOTHING MOUNTED UNDERNEATH IT.

    This branch is the whole of the offline behaviour now, and the header carries the argument. In one
    line: the only list that may fill the chooser is the server's, because access is decided by rows
    this client never sees and can be withdrawn without it hearing, so a cache of a past answer may
    not be turned into an offer. It says what happened, it says where the work CAN still be done, and
    it offers the retry — it does not apologise for an empty archive, because it does not know of one.

    `offline` is checked before `problem`: they are mutually exclusive by construction (each clears
    the other) and the order is only about which fact is the more specific if that ever stops being
    true.
  */
  if (offline) {
    return (
      <div>
        {header}
        <section className="panel px-4 py-6" aria-live="polite">
          <div className="mb-3 grid h-10 w-10 place-items-center rounded-full bg-field-200 text-field-600">
            <CloudOff className="h-5 w-5" aria-hidden />
          </div>
          <h2 className="text-base font-medium text-ink">The repository could not be reached</h2>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-muted">
            This is not an empty archive — it is a list that could not be loaded. Which workshops you can open is
            decided by the repository and can change while a browser is away, so this chooser is not offered from a
            saved copy: an old list would offer a workshop that may no longer be yours, and anything filed against it
            would be refused when the connection returns.
          </p>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-muted">
            A workshop you are already inside is unaffected — open it from{" "}
            <Link href="/design-workshops" className="underline">
              Design workshops
            </Link>{" "}
            and work on its Sketches &amp; prototypes page there.
          </p>
          <button type="button" className="field-button-secondary mt-4" onClick={retry}>
            Try again
          </button>
        </section>
      </div>
    );
  }

  /*
    A SERVER THAT SPOKE AND REFUSED gets its own words, and no chooser — there is no list to offer,
    and a disabled dropdown above an error is furniture. The retry is the way out; `apiFetch` has
    already unpacked FastAPI's 422 list into a readable sentence if that is what came back.
  */
  if (problem) {
    return (
      <div>
        {header}
        <section className="panel px-4 py-6" aria-live="polite">
          <h2 className="text-base font-medium text-ink">Your design workshops could not be listed</h2>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-muted">{problem}</p>
          <button type="button" className="field-button-secondary mt-4" onClick={retry}>
            Try again
          </button>
        </section>
      </div>
    );
  }

  /*
    ANSWERED, AND THE ANSWER IS NONE. The default state of a newly onboarded designer rather than an
    edge case, so the copy is the whole explanation: why there is no way to start one here, who to
    ask, and what happens after they have asked.

    ONE BRANCH, WHERE THERE USED TO BE TWO. The other was "offline and this browser has nothing
    cached" — unreachable now goes no further than the panel above, so an empty list here can only
    mean the repository answered and said none, which is the one thing this state is entitled to
    claim.
  */
  if (workshops !== null && workshops.length === 0) {
    return (
      <div>
        {header}
        <EmptyState
          title="No design workshops to open yet"
          body="Sketches and prototypes belong to a workshop, and the workshops you have access to will appear in the chooser here. Starting a new one is done by an admin or the master admin — ask them to create it for your cluster and give you access, and it will show up ready for all 22 stages."
        />
      </div>
    );
  }

  const truncated = workshops !== null && total > workshops.length;

  return (
    <div>
      {header}

      {/*
        THE CHOOSER IS RENDERED IN EVERY REMAINING STATE, disabled while the list has not answered
        rather than withheld until it has. A control that appears after a beat moves everything under
        it, and on a slow connection that means the workshop a designer was reaching for arrives
        under their cursor a moment after they clicked where it used to be.
      */}
      <section className="panel mb-5 grid gap-3 p-4">
        <div className="grid gap-1">
          {/* A visible label beside an `ariaLabel` rather than a `<label htmlFor>`: `Dropdown` renders
              a button, not an input, and takes no id — the same arrangement the workshops list page
              and the viewers panel use. */}
          <span className="field-label">Which workshop</span>
          <Dropdown
            value={chosen}
            onChange={chooseWorkshop}
            options={options}
            placeholder={workshops === null ? "Looking for your workshops…" : "Choose a design workshop"}
            disabled={workshops === null}
            ariaLabel="Which design workshop"
            describedBy="sketches-workshop-why"
            // A dropdown that changes the screen it sits on must NOT advance focus on select:
            // jumping away from the control you are adjusting is wrong when the control IS the
            // adjustment. Same reason the workshops list page passes it.
            advanceOnSelect={false}
            /*
              FORCED ON, not left to the option count, and this chooser is the reason the rule got
              written down. `SearchableSelect` grows a filter box at eight options, which for a list
              of workshops means the control has a filter box on a mature deployment and none on a
              fresh one — the same screen behaving two ways depending on how much work has been
              filed. It reads as a bug on whichever deployment you meet second.

              This list is `CHOOSER_PAGE = 100` rows deep and reports its own truncation, so it is a
              corpus to hunt through by definition. Every other workshop picker in the app already
              says so explicitly — the workshops list page, the viewers panel, all four
              questionnaire pickers, the upload dialog, the adopt-draft dialog — and `ComboBox`'s own
              doc names this exact control while explaining why: "the workshop picker is one row on
              this deployment and will be forty on the next".
            */
            searchable
          />
        </div>
        <p id="sketches-workshop-why" className="max-w-3xl text-sm leading-6 text-ink-muted">
          Every design workshop you can open — the ones you were given access to, and every one on the platform if you
          are an admin. The same screen is also inside each workshop, under Sketches &amp; prototypes on its own page,
          if you would rather start from there.
        </p>
        {workshops === null ? (
          // "Still asking", said out loud and in words. Never the empty state — a designer told they
          // have no workshops goes and asks an admin for access they already hold — and never a bare
          // spinner, which says that something is happening without saying what is being waited for.
          <p className="text-xs text-ink-500" aria-live="polite">
            Looking for the design workshops you can open…
          </p>
        ) : null}
        {truncated ? (
          // Said rather than left as an absence: the reader has to be able to tell "not in this
          // dropdown" from "not yours". It no longer means a refusal — an id outside these rows is
          // resolved against the API — so the sentence names both ways in rather than sending the
          // reader off this page as the only option.
          <p className="text-xs text-ink-500">
            Showing the first {workshops?.length ?? 0} of {total}. A workshop that is not in this chooser still opens
            here from a link that names it, and from its own page under{" "}
            <Link href="/design-workshops" className="underline">Design workshops</Link>.
          </p>
        ) : null}
      </section>

      {/*
        ── WHAT IS SHOWN UNDERNEATH, IN THE ORDER THE QUESTIONS HAVE TO BE ASKED ──────────────────

        NOTHING IS MOUNTED UNTIL THE VERDICT IS KNOWN, and that is not tidiness. The workspace's
        review tab flushes its coalescing timer ON UNMOUNT, so mounting the screen for a moment and
        then taking it away because the answer arrived without that id is a WRITE — and it would be a
        write against a workshop this account may not open. Waiting for the answer costs one sentence.

        FOUR BRANCHES AND NOT THREE. "Could not check" is its own panel because it is its own fact
        with its own next move: the refusal below asserts that the repository has answered, and
        printing it over a read that never completed is the same collapse this file's header names as
        the most repeated bug class in the repository — told this time about one workshop instead of
        about the whole list.
      */}
      {!chosen ? (
        // No selection, and this is a sentence rather than a spinner: nothing is loading, the page is
        // waiting for a person. The same shape `/design-review` uses for the same state.
        <p className="panel px-4 py-6 text-center text-sm text-ink-muted">
          Nothing is open yet. Choose one of your design workshops above to add its sketches and prototypes, or to rate
          and rank the ones that are already there.
        </p>
      ) : verdict === "unknown" ? (
        <p className="panel px-4 py-6 text-center text-sm text-ink-muted" aria-live="polite">
          Checking that workshop…
        </p>
      ) : verdict === "uncheckable" ? (
        /*
          THE WORKSHOP IS NOT IN THE CHOOSER AND THE CHECK ON IT DID NOT COMPLETE. Note what this
          state is NOT: it is not the unreachable-repository branch, which renders its own panel above
          instead of a chooser. It is a list that arrived and a single-id read that then did not — a
          signal lost between the two, a shared link opened as the connection went, or a repository
          that answered the read with something other than a verdict.

          It says nothing about the workshop, deliberately, because nothing is known about it. Try
          again is the whole point of the panel: without it the reader is left with copy claiming a
          refusal that never happened, and no way to make the check that would settle it. The two
          sentences are split on whether the server SPOKE, because "we could not reach it" and "it
          answered with an error" have different next moves — one is the signal, the other is
          somebody else's to fix — and `uncheckableNote` is the server's own words rather than this
          page's summary of them.
        */
        <section className="panel px-4 py-6" aria-live="polite">
          <div className="mb-3 grid h-10 w-10 place-items-center rounded-full bg-field-200 text-field-600">
            <CloudOff className="h-5 w-5" aria-hidden />
          </div>
          <h2 className="text-base font-medium text-ink">That workshop could not be checked</h2>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-muted">
            {uncheckableNote
              ? `It is not one of the workshops in the chooser above, so the repository was asked about it directly and answered with an error: ${uncheckableNote}`
              : "It is not one of the workshops in the chooser above, so the repository had to be asked about it directly and could not be reached."}{" "}
            This says nothing about whether the workshop is yours — nothing has refused it. Try again, or choose one of
            the workshops above in the meantime.
          </p>
          <button type="button" className="field-button-secondary mt-4" onClick={retry}>
            Try again
          </button>
        </section>
      ) : verdict === "refused" ? (
        /*
          ONE SENTENCE FOR FOUR CAUSES, AND IT DOES NOT SAY WHICH — see the header. The possibilities
          are listed together precisely so that none of them is confirmed: a reader learns that this
          account cannot open that workshop, and nothing about whether the id exists.

          THE CLAIM IN THE MIDDLE OF IT IS NOW TRUE, WHICH IT WAS NOT WHEN IT WAS WRITTEN. "The
          repository answers all four the same way and so does this page" was printed by a page that
          had not asked the repository at all — it had checked one capped page of a list, so a fifth
          cause the sentence does not admit, "your workshop is newer than the hundred rows I am
          holding", reached an admin as a refusal. This branch is now only ever reached because
          `load_workshop_or_404` itself answered 404 — nothing else counts, see the catch above, and
          the offline reading that used to reach it here (absent from this device's cached list) is
          gone with the cached list.
        */
        <section className="panel px-4 py-6" aria-live="polite">
          <h2 className="text-base font-medium text-ink">That workshop is not one this account can open</h2>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-muted">
            The link may be old, the id may be mistyped, the workshop may have been deleted, or it may belong to
            designers who have not given this account access — the repository answers all four the same way and so does
            this page. Choose one of your own workshops above.
          </p>
          <Link href="/design-workshops" className="field-button-secondary mt-4 inline-flex">
            Open the workshops list
          </Link>
        </section>
      ) : (
        /*
          `key={chosen}` IS LOAD-BEARING AND `SketchesWorkspace`'s HEADER EXPLAINS IT IN FULL. In
          short: the review tab coalesces a reorder for 1200 ms and flushes on unmount, its writer
          reads the draft id out of a ref, and the stage key is identical in every workshop — so
          changing the workshop in place would let an arrangement nudged in one land in another's
          record. Remounting makes the switch flush the old workshop's arrangement against the old
          workshop's refs, which is where it was meant to go.
        */
        <SketchesWorkspace key={chosen} workshopId={chosen} tab={tab} onTabChange={changeTab} idPrefix="sketches" />
      )}
    </div>
  );
}
