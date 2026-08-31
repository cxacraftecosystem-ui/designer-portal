"use client";

/**
 * "Move into a workshop" — the way a design workshop that exists only on this device gets a server
 * record it is allowed to have.
 *
 * ── WHY THERE IS A DIALOG HERE AT ALL ────────────────────────────────────────────────────────────
 *
 * Starting a design workshop became an admin's job. The rule is right — a workshop is the container
 * the ministry indexes and funds, not a record — but it shipped onto laptops that were ALREADY
 * holding workshops a designer had started under the old rule and had not yet synced: a courtyard's
 * worth of stages, photographs and recordings with no `remoteId`.
 *
 * Deleting those is unthinkable and letting them sync anyway would be a permission any device can
 * grant itself. So they are ADOPTED: an admin creates the workshop — which they were always going
 * to have to do — and the designer points the draft at it here. Every stage, every photograph and
 * every recorded deletion then reaches that workshop by the ordinary sync path, because from the
 * store's point of view an adopted draft is indistinguishable from one that has been created.
 *
 * The correctness argument for what adoption clears (and what would be destroyed if it did not) is
 * on `adoptedIntoWorkshop` in `lib/designWorkshopStore.ts`. This file is the choosing.
 *
 * ── THE PICKER MAY ONLY OFFER WORKSHOPS THE SERVER WOULD ACTUALLY ADMIT THIS ACCOUNT TO ─────────
 *
 * A design workshop is visible only to its creator, to admins, and to whoever holds a
 * `DesignWorkshopViewer` row — enforced in the list query and in the single read, which refuses
 * with a 404 identical to a nonexistent id. So the SERVER'S list is the answer to "which workshops
 * may this draft be moved into", and it is the only thing that knows it.
 *
 * THIS PICKER USED TO MERGE the workshops this browser had merely CACHED into the server's answer,
 * so that a workshop which fell off page one did not disappear the moment the network replied. The
 * merge is gone, and the same reasoning that removed it from the list page on this route removes it
 * here, only harder: a cached row is the server's answer AS OF THE LAST SYNC and it is stale in the
 * PERMISSIVE direction — a grant revoked in March is still on this device in September, and offline
 * there is nobody to ask. On the list page that produced a row that opened to a 404. Here it would
 * produce a DESTINATION, and adoption is one-way and unrepeatable (`localDraftNeedsAWorkshop`
 * guards it): a fortnight of fieldwork would be filed against an id this account cannot open, with
 * nothing in either client able to undo it. The workshop that fell off page one is reached by the
 * search box instead, which asks the repository rather than this browser.
 *
 * ── THE SEARCH BOX IS THE SERVER'S ──────────────────────────────────────────────────────────────
 *
 * §11.5 of the frontend contract: a client-side filter over a server-truncated list is the WRONG
 * search box and looks exactly like the right one. This dialog had that shape — one page of
 * workshops fetched, `searchable` passed to a control that draws 80 rows, and nothing on screen
 * saying the list had been cut — so typing the name of a workshop that exists answered "No
 * matches". `GET /design-workshops` takes `search` over title, craft, cluster and workshop code, so
 * the box is above the picker, wired to the server, and the picker's own filter is off with a
 * `capHint` naming the box that DOES reach the rest.
 *
 * ── IT READS WITH NO CONNECTION. IT DOES NOT WRITE WITH NO CONNECTION ───────────────────────────
 *
 * With no connection there is no scoped answer to be had, so the picker falls back to the workshops
 * THIS DEVICE has already seen and says, in words, that the list is partial AND that a workshop on
 * it may since have been closed to this account. That much is unchanged.
 *
 * WHAT CHANGED IS THAT THE MOVE IS NOW HELD THERE, and the sentence this replaces is the argument
 * it overrules. It read: "A designer in a cluster with one bar of signal, holding a stranded
 * workshop, must not be told to come back when they have wifi — that is the exact situation this
 * app exists to work in." True of CAPTURE, and not true here, because adoption sends nothing. The
 * draft is safe on this device either way; nothing automatic may delete it (`Offline.kt:709`); and
 * not one stage can leave for the chosen workshop until there is a connection — the same moment the
 * live list becomes available. So the wait costs nothing, while acting on the cached list risks
 * everything: it is stale in the PERMISSIVE direction, and this write is one-way and unrepeatable.
 * `DROPDOWN_DESIGN.md` R6 is the general form and cites the paragraph above it; the fallback branch
 * was quietly the exception to the rule it was being quoted for. See {@link verified}.
 *
 * ── WHAT HAPPENS WHERE BOTH SIDES ANSWERED THE SAME BOX ─────────────────────────────────────────
 *
 * The target workshop is very often not empty. `POST /design-workshops` seeds stage 1 and stage 3
 * with a designer block the moment it is created, and a second designer may have been working in it
 * for a week. So "merge" has to have a stated rule, and it does — it is just not stated anywhere a
 * designer could read it, which is what this paragraph and the sentence on screen fix.
 *
 * THE RULE: `adoptedIntoWorkshop` clears `serverLoadedAt` on every stage, so the first PUT carries
 * `merge: true`, and `save_stage` folds the row as `{**previous, **clean}` — **a key this device
 * holds wins, a key only the workshop holds is kept, and nothing is deleted.** It also clears
 * `removedFrom`, so `replaceCollections` is off and collection rows are merged by `entryId` with no
 * sweep: a row somebody else added to the target survives.
 *
 * WHY LOCAL-WINS IS THE RIGHT WAY ROUND HERE, and it is not obvious. The draft is a fortnight
 * captured in the room, with the artisans in front of the designer; the target's colliding value is
 * either a seed the create wrote automatically or an answer typed at a desk. Losing the room's
 * answer to a seed is the worse error of the two, and it is the one that cannot be re-derived.
 *
 * AND IT IS SAID ON SCREEN RATHER THAN LEFT TO THE READER, because the sentence that used to stand
 * there — *"the workshop keeps whatever is already in it"* — was flatly untrue of a colliding key,
 * and a promise the code does not keep is worse than no promise. A move is one-way, so the moment
 * to say it is before the button, not after.
 *
 * ── WHY IT IS NOT A `useConfirm` PROMPT ─────────────────────────────────────────────────────────
 *
 * The designer has to CHOOSE, and choosing wrongly puts a fortnight of fieldwork into another
 * cluster's record. So it is a picker with the consequence written above it, and the confirm button
 * names the workshop rather than saying "OK".
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { FolderInput } from "lucide-react";

import { FieldDialog } from "@/components/dialogs";
import { SearchInput } from "@/components/SearchInput";
import { Dropdown } from "@/components/ui/Dropdown";
import { RENDER_CAP } from "@/components/ui/selectFilter";
import { listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
import { adoptDraftIntoWorkshop, type DwDraft } from "@/lib/designWorkshopStore";

/**
 * Matches every other server-backed search box in this feature. The list route filters four columns
 * with `ILIKE`, so each keystroke that escapes this is a scan.
 */
const SEARCH_DEBOUNCE_MS = 300;

export type AdoptLocalDraftDialogProps = {
  open: boolean;
  onClose: () => void;
  /** The device-only draft being moved. Null renders nothing — the dialog is opened per row. */
  draft: DwDraft | null;
  /**
   * Every draft this browser holds, so the offline fallback can offer the workshops the device has
   * already seen. Passed in rather than subscribed to here: the list page already holds this
   * snapshot, and a second `useSyncExternalStore` on the same store would re-render this dialog on
   * every autosave of every stage in another tab.
   */
  drafts: readonly DwDraft[];
  /** Called with the chosen workshop id once the draft has been re-pointed. */
  onAdopted: (remoteId: string) => void;
};

/** One choosable workshop, reduced to what the picker shows. */
type Candidate = { id: string; label: string };

/** "Ikat revival, Barpali — Sambalpuri ikat" — enough to tell two of a designer's workshops apart. */
function labelFor(row: Pick<DwSummary, "title" | "craftName" | "clusterName" | "district">): string {
  const place = [row.clusterName, row.district].filter(Boolean).join(", ");
  const parts = [row.title || "Untitled design workshop", row.craftName, place].filter(Boolean);
  return parts.join(" · ");
}

export function AdoptLocalDraftDialog({ open, onClose, draft, drafts, onAdopted }: AdoptLocalDraftDialogProps) {
  const [candidates, setCandidates] = useState<Candidate[] | null>(null);
  /** True while the list on screen is this DEVICE'S memory rather than the repository's answer. */
  const [partial, setPartial] = useState(false);
  /**
   * Is the list on screen the repository's LIVE answer to "which workshops may this account open"?
   *
   * THE MOVE IS HELD UNTIL IT IS, and that is not a spinner. The picker's first paint is this
   * device's cached list, so that somebody with no signal is not staring at "Loading…" through a
   * request that is going to time out; but a cached row is the server's answer as of the last sync
   * and stale in the PERMISSIVE direction, and adoption is one-way. So the list may be READ early
   * and may not be ACTED ON early.
   *
   * ── IT USED TO BE SET BY BOTH ARMS, AND THAT WAS THE DEFECT ──────────────────────────────────
   *
   * The old note here read "Set by both arms — a failure is an answer too, and the partial notice
   * then says what the list is", and the catch below stamped it. That made a FAILED fetch unlock
   * the button over the cached list: the exact write this file's header spends four paragraphs
   * forbidding. The header removed the cached rows from the MERGE and then handed them back as
   * DESTINATIONS one branch later, so a grant revoked in March was still choosable in September —
   * and adoption is one-way and unrepeatable, so a fortnight would be filed against an id this
   * account cannot open with nothing in either client able to undo it. `DROPDOWN_DESIGN.md`'s R6
   * names this dialog as its own authority and calls caching an ACCESS list FORBIDDEN rather than
   * unattractive; the code disagreed with the document that cited it.
   *
   * WHAT THE OLD ARM WAS PROTECTING, AND WHY IT COSTS NOTHING TO WITHDRAW IT. The argument was that
   * a designer with one bar of signal must not be told to come back when they have wifi. That is
   * right about CAPTURE and wrong about this: adoption sends nothing. The draft is safe here either
   * way, nothing automatic may delete it (`Offline.kt:709`), and the stages cannot leave this
   * device until there is a connection — which is the same moment the live list becomes available.
   * So waiting costs the designer nothing at all and removes the one write on this screen that
   * cannot be taken back. The list itself is still shown offline, and still says what it is.
   */
  const [verified, setVerified] = useState(false);
  /** How many workshops the repository has in scope for this account, or null when it did not answer. */
  const [total, setTotal] = useState<number | null>(null);
  const [search, setSearch] = useState("");
  const [searching, setSearching] = useState(false);
  const [chosen, setChosen] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * A generation counter rather than an abort: `listDesignWorkshops` takes no signal, and what
   * matters is IGNORING the late answer. Two searches are in flight whenever somebody types
   * quickly, and a slow answer for "bar" landing after the fast one for "barpali" would leave the
   * wrong list under the typed word — which on this dialog is the moment a fortnight of fieldwork
   * is filed somewhere.
   */
  const generation = useRef(0);

  /**
   * The workshops this device already knows about, from the draft store alone.
   *
   * THE OFFLINE FALLBACK, AND THE FIRST PAINT — and nothing else. It is never merged into a server
   * answer; see the file header for the one-way, unrepeatable write that made that merge unsafe.
   */
  const known = useMemo<Candidate[]>(
    () =>
      drafts
        .filter((row): row is DwDraft & { remoteId: string } => typeof row.remoteId === "string")
        .map((row) => ({ id: row.remoteId, label: labelFor(row.header) })),
    [drafts]
  );

  /**
   * `known` READ THROUGH A REF, so `load` has no dependencies and the search effect below re-runs on
   * the search term alone.
   *
   * `drafts` is the whole live draft store, so it changes on every stage autosave in every other
   * tab. With `known` in `load`'s dependency array, `load` was a new function on each of those and
   * the debounced search effect tore itself down and re-issued — a `%term%` scan of the workshop
   * table every time a colleague typed a sentence into stage 7, and the answer landing under a
   * choice the designer had already made.
   */
  const knownRef = useRef(known);
  useEffect(() => {
    knownRef.current = known;
  });

  const load = useCallback(
    async (term: string) => {
      const mine = generation.current + 1;
      generation.current = mine;
      setSearching(true);
      setError(null);
      try {
        // `RENDER_CAP` ROWS AND NOT A ROUND NUMBER. The control draws 80; asking for 100 printed two
        // truncation sentences with two different totals and said nothing at all between 81 and 100.
        const found = await listDesignWorkshops({ page: 1, pageSize: RENDER_CAP, search: term || undefined });
        if (generation.current !== mine) return;
        setCandidates(found.items.map((row) => ({ id: row.id, label: labelFor(row) })));
        setTotal(found.total);
        setPartial(false);
        setVerified(true);
      } catch {
        if (generation.current !== mine) return;
        // The network is the thing that failed, and it is the thing this feature is least allowed to
        // depend on. Fall back to what is on the device and SAY that the list is partial, rather than
        // presenting a short list as though it were the scoped answer.
        setCandidates(knownRef.current);
        setTotal(null);
        setPartial(true);
        // FALSE, AND THIS IS THE ONE LINE THAT DECIDES THE RULE — see {@link verified}. A failure is
        // an answer about the NETWORK and no answer at all about ACCESS, so the cached rows above
        // may be read and may not be chosen. Written on every failure and not only the first: a
        // search that fails after a successful open has replaced the live list with the cached one,
        // and the button must go back down with it.
        setVerified(false);
      } finally {
        if (generation.current === mine) setSearching(false);
      }
    },
    []
  );

  useEffect(() => {
    if (!open) return;
    setChosen("");
    setBusy(false);
    setError(null);
    setSearch("");
    setTotal(null);
    setPartial(false);
    setVerified(false);
    // THE FIRST PAINT ONLY, and it cannot be moved on: `verified` above holds the Move button until
    // the repository has answered. It fetches nothing — the effect below issues the one request.
    // Read through the REF rather than the value, and that is what keeps `open` the only dependency
    // here: `known` derives from the whole live draft store, so it changes on every stage autosave in
    // every other tab, and re-running this effect would reset a choice the designer had already made
    // mid-thought.
    setCandidates(knownRef.current.length ? knownRef.current : null);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const term = search.trim();
    // No debounce on the empty term — that is the read the dialog opens with, and making somebody
    // wait 300ms to see the list they just opened is a delay with nothing to pay for it. It is also
    // the ONE place the request is issued, so opening the dialog costs exactly one.
    const timer = window.setTimeout(() => void load(term), term ? SEARCH_DEBOUNCE_MS : 0);
    return () => window.clearTimeout(timer);
  }, [search, open, load]);

  const options = useMemo(
    () => [
      { value: "", label: "Choose the workshop this belongs to…" },
      ...(candidates ?? []).map((row) => ({ value: row.id, label: row.label }))
    ],
    [candidates]
  );

  const chosenLabel = (candidates ?? []).find((row) => row.id === chosen)?.label ?? "";
  const shown = candidates?.length ?? 0;
  const searchTerm = search.trim();

  /**
   * AT MOST ONE LINE ABOUT THE LIST ITSELF: what the search is doing, that the answer was cut, or
   * that nothing matched. Nothing when the list is whole — a standing note on every visit is
   * padding.
   *
   * The truncation sentence names a TOTAL, because "showing the first 80" without it is a cap that
   * cannot be reasoned about: the designer cannot tell whether they are missing one workshop or
   * four hundred.
   */
  const listNotice =
    searching && !verified
      ? // The first read of this opening, and the Move button is held until it answers — so the
        // sentence says what is being waited for rather than describing a search nobody ran.
        "Checking which workshops are open to you…"
      : searching
        ? "Searching…"
        : partial
          ? ""
          : total !== null && shown < total
            ? `Showing ${shown} of ${total} workshops open to you. Search above to reach the rest — it asks the repository, not this device.`
            : searchTerm && shown === 0
              ? "No workshop open to you matches that search."
              : "";

  async function move() {
    if (!draft || !chosen) return;
    setBusy(true);
    setError(null);
    try {
      const moved = await adoptDraftIntoWorkshop(draft.localId, chosen);
      if (!moved) {
        // `mutate` answers null when the write was refused or the draft is gone. Neither is
        // something to paper over: the designer must not walk away believing a fortnight of work
        // has been filed when it has not.
        setError(
          "This browser would not save the change, so the workshop has NOT been moved and nothing " +
            "has been lost. Try again; if it keeps failing, do not clear this browser's storage — " +
            "report it, because everything captured here is still in it."
        );
        return;
      }
      onAdopted(chosen);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to move this workshop.");
    } finally {
      setBusy(false);
    }
  }

  if (!draft) return null;

  const title = draft.header.title || "Untitled design workshop";
  const stageCount = Object.keys(draft.stages ?? {}).length;
  /**
   * The repository answered, it answered with nothing, and no search is narrowing it.
   *
   * THE CONTROL MUST NOT OFFER ITSELF WHERE IT WOULD DO NOTHING. An empty picker over an empty
   * scope is the silent-emptiness state rule 10 forbids — indistinguishable from a list that failed
   * to load — and on this dialog it is worse than uninformative, because the designer's own reading
   * of it is "the app has lost my workshops". The honest answer is that no workshop has been opened
   * for them YET, and the next move is the one thing that changes it: an admin creating one and
   * naming them on it.
   */
  const nothingToMoveInto = verified && !partial && !searching && !searchTerm && shown === 0;

  return (
    <FieldDialog
      open={open}
      onClose={onClose}
      busy={busy}
      title="Move this workshop into an existing one"
      description={`“${title}” was started on this device and has never been sent.`}
      icon={<FolderInput className="h-4 w-4" aria-hidden />}
      className="max-w-lg"
      footer={
        <>
          <button type="button" className="field-button-secondary" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          {nothingToMoveInto ? null : (
            <button
              type="button"
              className="field-button"
              // `!verified` HOLDS THE ONE-WAY WRITE until the repository's LIVE answer is what is on
              // screen — see the state's own note, which records the failed-fetch arm that used to
              // unlock this over the cached list. Nothing else on this dialog waits for it; the list
              // is readable from the first frame and stays readable with no connection at all.
              disabled={busy || !chosen || !verified}
              onClick={move}
            >
              {busy ? "Moving…" : chosenLabel ? `Move into ${chosenLabel}` : "Move"}
            </button>
          )}
        </>
      }
    >
      <div className="grid gap-3 text-sm text-ink-700">
        {/*
          WHAT MOVING ACTUALLY DOES, IN THE ORDER IT MATTERS: everything here goes there, nothing is
          deleted, and this is what happens where both sides answered the same box.

          TWO SENTENCES THAT USED TO BE FOUR. The paragraph opened by telling the reader that
          starting a workshop is an admin's job and to go and ask for one — which is the right
          sentence for an account with NOTHING to move into, and that account is already answered by
          `nothingToMoveInto` below, which is the one branch that KNOWS it. On this branch there is a
          list of the designer's own workshops on screen and the next move is to pick one, so the
          admin sentence was two lines of reading between a person and the control they came for.
          The owner's 2026-08-30 ruling on UI copy; the reasoning is in the file header.

          THE LAST CLAUSE REPLACES A CLAIM THAT WAS NOT TRUE. It read "the workshop keeps whatever is
          already in it", and `save_stage`'s merge is `{**previous, **clean}` — this device wins a
          box both sides answered. See the header for the rule and for why local-wins is the right
          way round; what matters here is that a designer reads it BEFORE a one-way move.
        */}
        <p className="leading-6">
          Everything saved on this device
          {stageCount ? ` (${stageCount} stage${stageCount === 1 ? "" : "s"}, with their photographs and recordings)` : ""} is
          sent into the workshop you choose, on the next sync. Nothing is deleted and nothing is retyped: answers already in
          that workshop are kept, and where both have the same box, this device’s answer wins.
        </p>

        {nothingToMoveInto ? (
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
            No design workshop is open to this account yet, so there is nothing to move this into. A design workshop can
            only be seen by the designers named on it — ask an admin to create the workshop for this cluster and name you
            as one of its designers, then come back here. Nothing on this device expires and nothing will be lost in the
            meantime.
          </p>
        ) : (
          <>
            {/* The one search box, and it asks the REPOSITORY — the only thing that can see past the
                page this dialog draws, and the only thing that knows which workshops this account
                may open at all. */}
            <SearchInput
              onChange={setSearch}
              placeholder="Search by title, craft, cluster or workshop code"
              value={search}
            />
            {listNotice ? <p className="text-xs leading-5 text-ink-500">{listNotice}</p> : null}
            <Dropdown
              value={chosen}
              onChange={setChosen}
              options={options}
              ariaLabel="The workshop to move this into"
              // OFF, deliberately: the box above is the search and it reaches every workshop open to
              // this account, while this control's own filter would search only the rows already
              // fetched — answering "No matches" about a workshop that exists. §11.5.
              searchable={false}
              // `searchable={false}` does not switch the render cap off, so the panel's own notice
              // still fires; its default last clause would tell the reader to type into a filter box
              // this control deliberately does not have.
              capHint="Use the search box above to reach the rest — it asks the repository, so it sees every workshop open to you."
            />
          </>
        )}

        {partial ? (
          /*
            IT NOW SAYS THE MOVE IS HELD, because the button below it is disabled and a disabled
            control whose reason is nowhere is the control people press repeatedly. Two sentences:
            what the list is, and why waiting costs nothing. TERSE per the owner's 2026-08-30 ruling
            — the reasoning lives on {@link verified}, not on screen.
          */
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
            There is no connection, so this lists only the workshops already open on this device and cannot check whether
            you are still on them. Moving waits for signal — nothing would be sent before then anyway. Nothing on this
            device expires in the meantime.
          </p>
        ) : null}

        <p className="text-xs leading-5 text-ink-500">
          Choose carefully: this decides which workshop a fortnight of fieldwork is filed under. It can only be done once
          per workshop — after the move, the stages belong to the workshop you pick.
        </p>
        {error ? (
          <p role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs leading-5 text-red-700">
            {error}
          </p>
        ) : null}
      </div>
    </FieldDialog>
  );
}
