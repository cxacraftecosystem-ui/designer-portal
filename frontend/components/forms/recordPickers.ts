"use client";

/**
 * THE CRAFT AND ARTISAN PICKERS THE RECORD FORMS SHARE — and the ceiling they used to hide.
 *
 * ProductForm and ToolForm ask the identical question of the API ("which crafts, and which artisans
 * of the chosen craft") and had the identical bug in it, twice over, character for character. This
 * hook is that question asked once.
 *
 * WHAT WAS WRONG. Both forms did a single `listResource("/artisans", { pageSize: 100 })` at mount
 * and then filtered the result by craft in the browser. `pageSize` is clamped to `MAX_PAGE_SIZE =
 * 100` server-side (`backend/app/services/pagination.py`) so 100 is the ceiling and not a tunable,
 * `/artisans` orders `createdAt desc`, and this database holds **749 artisans over 178 crafts**
 * (counted against 127.0.0.1:55442 on 2026-08-15). So the dropdown held the newest hundred rows of
 * the whole table, and the craft filter then cut into THAT: a craft whose people were entered
 * before the newest hundred offered nothing at all, under the sentence "No artisans are linked to
 * this craft yet." — a statement about the repository that neither form had any basis for. 649
 * artisans were unpickable, and `total` was on the wire the whole time, discarded.
 *
 * WHAT THIS HOOK DOES INSTEAD, in three requests that only ever ADD rows:
 *
 * 1. the mount load, kept as it was, because `carryScope` reads this array to decide whether a
 *    carried record is reachable from this form and that judgement is about the repository, not
 *    about one craft;
 * 2. the chosen craft's own roster, asked for with the `craftId` the endpoint has always accepted —
 *    which turns a hundred-row window on 749 artisans into, in practice, the complete answer for
 *    the craft in hand;
 * 3. the record's own artisan, looked up by id when neither page holds them, so that "this artisan
 *    is not in the list" and "this artisan does not practise that craft" stop being the same
 *    observation. They were the same observation, and the craft-change handlers in both forms read
 *    the first as the second and cleared the link.
 *
 * WHAT IT REPORTS. `craftCut` and `craftArtisanCut` are `null` whenever the list is whole, which is
 * the normal case for a craft's roster and increasingly not the case for the crafts list itself
 * (178 crafts against a 100-row page). A cut list must say so — see `components/data/cappedList`
 * for why that is a rule here rather than a nicety.
 */

import { useEffect, useMemo, useRef, useState } from "react";

import { LIST_PAGE_CEILING, listCut, mergeById, type ListCut } from "@/components/data/cappedList";
import type { CarryScopeState } from "@/components/forms/CarryContextBanner";
import { apiFetch, listResource } from "@/lib/api";
import {
  loadCachedRegister,
  type CachedReferenceOption,
  type RegisterLoadOutcome
} from "@/lib/referenceCache";
import { formatDate } from "@/lib/format";
import {
  cachedListLine,
  deviceLooksOffline,
  workshopEmptyLabel,
  workshopListNotice,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";
import type { Artisan, Craft, ProductDocumentation, ToolDocumentation } from "@/lib/types";

/* ────────────────────────────────────────────────────────────────────────────
 * The register codecs
 *
 * Each pair reduces one record to the four display fields `lib/referenceCache.ts` stores and builds
 * it back. They live HERE rather than in that module for the reason Android puts `artisanToOption`
 * and its three siblings beside the record forms and not inside `DwReferenceStore`: the store's job
 * is a document with a key and a date on it, and WHICH COLUMNS A PICKER READS is a fact about the
 * forms. Adding a register means a pair here, not a change there.
 *
 * ── ON THE COLUMNS A DECODED ROW DOES NOT CARRY ───────────────────────────────────────────────
 *
 * A reconstructed row holds exactly what the controls in this folder read off it, and blanks for
 * every other required column of its type. That is the same trade `optionToArtisan` makes on
 * Android, and it is safe for the same checked reason rather than by hope: these rows are only ever
 * handed to a PICKER — the options array, the label builder, `carry.remember`, and the
 * `find(candidate => candidate.id === picked)` beside each one. Nothing in this folder reads a
 * status, a measurement, a price or a timestamp off a list it was given for LINKING; those mean
 * something on the record BEING EDITED, which is fetched whole by id and never comes from here.
 *
 * The consequence to keep in mind when adding a column: a control that starts reading a new field
 * off one of these arrays must add it to the codec, or it will read "" from a cached list and the
 * real value from a live one — which is a difference that only appears with no signal, which is the
 * condition nobody develops in.
 * ──────────────────────────────────────────────────────────────────────────── */

export function craftToOption(craft: Craft): CachedReferenceOption {
  return { id: craft.id, label: craft.name, hint: "", filterValue: "" };
}

export function optionToCraft(option: CachedReferenceOption): Craft | null {
  return option.id ? { id: option.id, name: option.label } : null;
}

/**
 * THE COLUMNS A DECODED ROW CANNOT HONESTLY CARRY, NAMED — and the one cast in this file.
 *
 * `status` is a `RecordStatus` union and `createdAt` a timestamp, and a reduced option carries
 * neither. The compiler is right to refuse `""` for the first, and the two ways round it are both
 * worse than saying so: FILLING IN a plausible status ("DRAFT", "APPROVED") would put a fabricated
 * judgement on a row a reviewer might one day read, which is the class of defect this repository
 * spends the most words on; and widening `Artisan.status` to optional would relax the type for the
 * forty readers that legitimately depend on it in order to serve four that never touch it.
 *
 * So the absence is expressed where it is true — a picker row is `Omit<T, MISSING>` — and this is
 * the single, named place where it is widened back for callers that hold `Artisan[]`. Anything
 * added to this tuple must first be checked NOT to be read off a register array; see the codec
 * block's header for the grep and for the failure that skipping it produces.
 */
type PickerRowMissing = "status" | "createdAt";

function asPickerRow<T>(row: Omit<T, PickerRowMissing>): T {
  return row as T;
}

export function artisanToOption(artisan: Artisan): CachedReferenceOption {
  return { id: artisan.id, label: artisan.name, hint: artisan.place, filterValue: artisan.craftId ?? "" };
}

export function optionToArtisan(option: CachedReferenceOption): Artisan | null {
  if (!option.id) return null;
  // `status` and `createdAt` HAVE NO HOME HERE AND DO NOT NEED ONE, and the absence is checked
  // rather than hoped: grepping this folder for a read of either off a `crafts`/`artisans`/`tools`
  // array handed to a PICKER returns nothing. Both mean something on the record BEING EDITED, which
  // every form fetches whole by id (`initial`), and never on a register offered for linking.
  // Android's `optionToArtisan` drops the same pair for the same reason. See `asPickerRow`.
  return asPickerRow<Artisan>({ id: option.id, name: option.label, place: option.hint, craftId: option.filterValue || null });
}

export function productToOption(product: ProductDocumentation): CachedReferenceOption {
  return {
    id: product.id,
    label: product.productName,
    hint: product.artisanName,
    // The cascade key: `ProcessForm`'s product picker is narrowed by the artisan chosen above it,
    // and `narrowedTo` needs the parent id on the option because offline there is no server to ask.
    filterValue: product.artisanId ?? "",
    data: { craftName: product.craftName, place: product.place, productType: product.productType, marketDemand: product.marketDemand }
  };
}

export function optionToProduct(option: CachedReferenceOption): ProductDocumentation | null {
  if (!option.id) return null;
  return asPickerRow<ProductDocumentation>({
    id: option.id,
    productName: option.label,
    artisanName: option.hint,
    artisanId: option.filterValue || null,
    craftName: option.data?.craftName ?? "",
    place: option.data?.place ?? "",
    productType: option.data?.productType ?? "",
    marketDemand: option.data?.marketDemand ?? ""
  });
}

export function toolToOption(tool: ToolDocumentation): CachedReferenceOption {
  return {
    id: tool.id,
    label: tool.toolkitName,
    hint: tool.craftName,
    filterValue: tool.craftId ?? "",
    // `ToolAssignmentSection` draws `toolkitName — craftName · artisanName` and banks the artisan
    // and the place into the carry bag on selection, so all three travel. See
    // `CachedReferenceOption.data` for why they are not folded into `hint` with a separator.
    data: { artisanName: tool.artisanName, artisanId: tool.artisanId ?? "", place: tool.place }
  };
}

export function optionToTool(option: CachedReferenceOption): ToolDocumentation | null {
  if (!option.id) return null;
  return asPickerRow<ToolDocumentation>({
    id: option.id,
    toolkitName: option.label,
    craftName: option.hint,
    craftId: option.filterValue || null,
    artisanName: option.data?.artisanName ?? "",
    artisanId: option.data?.artisanId || null,
    place: option.data?.place ?? "",
    // `maker` and `traditionType` are blank rather than absent: both are plain string columns with
    // an "UNKNOWN" member, both are drawn from `initial` on the tool form's own Selects, and
    // neither is read off the tools ARRAY.
    maker: "",
    traditionType: ""
  });
}

/**
 * THE RECORD THIS FORM IS ALREADY POINTING AT, fetched by id when no loaded page holds it.
 *
 * A picker holds one page of at most 100 rows. The record being EDITED does not care about that: a
 * product filed last season points at an artisan who is nowhere near the newest hundred of 749, and
 * a picker that cannot draw its own current value is not merely incomplete — it is wrong, and every
 * "is this still valid?" test written against its array answers about page one instead of about the
 * repository. That is what let a craft correction silently unlink an artisan.
 *
 * Returns the row, or null when the id is empty, already on a loaded page, or unreachable. Callers
 * `mergeById` it into their options; nothing here mutates the page.
 *
 * **The ref is not an optimisation.** `rows` must be a dependency (a page arriving late has to
 * re-test the guard), so without a record of what has already been attempted a 403 or a 404 would
 * re-fire the request on every merge, forever.
 */
export function useRecordOffPage<T extends { id: string }>(
  endpoint: string,
  id: string,
  rows: readonly T[]
): T | null {
  const [record, setRecord] = useState<T | null>(null);
  const attempted = useRef(new Set<string>());

  useEffect(() => {
    if (!id || rows.some((row) => row.id === id)) return;
    if (attempted.current.has(id)) return;
    attempted.current.add(id);
    let cancelled = false;
    apiFetch<T>(`${endpoint}/${id}`)
      .then((row) => {
        if (!cancelled) setRecord(row);
      })
      .catch(() => {
        // Not visible to this account, or gone. Deliberately NOT an error banner: nothing is broken
        // — the link is intact and the name beside the picker still says who it points at. This form
        // simply cannot offer to change it, which is the honest state to be in.
      });
    return () => {
      cancelled = true;
    };
  }, [endpoint, id, rows]);

  // A row fetched for a DIFFERENT id must never be merged into the options: the researcher has
  // moved on and it would appear as an option that is neither on a page nor selected.
  return record && record.id === id ? record : null;
}

export type CraftAndArtisanOptions = {
  /** Every artisan this form has learned about, from all three requests. Never narrowed. */
  artisans: Artisan[];
  crafts: Craft[];
  /**
   * "Have the reference lists arrived?" — handed straight to `carryScope`, which treats "not
   * visible to me" and "no signal" differently. Only the MOUNT load moves it: the craft-scoped
   * request and the by-id lookup are refinements, and letting either of them report "unavailable"
   * would prune a carried record because one follow-up request failed.
   */
  referenceState: CarryScopeState;
  /** The crafts dropdown's cut, or null when it holds every craft. */
  craftCut: ListCut | null;
  /** The chosen craft's artisan roster's cut, or null when it holds every one of them. */
  craftArtisanCut: ListCut | null;
  /**
   * WHICH craft the loaded roster belongs to — not a boolean.
   *
   * "No artisans are linked to this craft yet" is a claim about the repository, and printing it off
   * the previous craft's rows while the new craft's request is still in flight makes that claim
   * before the answer exists. A caller must test `artisansLoadedForCraft === craftId` before saying
   * anything about emptiness.
   */
  artisansLoadedForCraft: string | null;
  /**
   * WHY THE CRAFT PICKER IS EMPTY -- "" when it has nothing to explain.
   *
   * The mount load's `.catch` set `referenceState` and nothing else, so a failed read left both
   * forms drawing a craft dropdown holding only "Unlinked / type below" with not a word about it.
   * That is the same shape as the sentence one field down that this hook's header already
   * describes: absence rendering as non-existence. These are `DROPDOWN_DESIGN.md` section 3.5's
   * strings through `workshopListNotice`, with `noun: "crafts"` -- the app has one set of words for
   * "the read is outstanding / the read failed / the device is offline / there genuinely are none"
   * and this is not the place to invent a second.
   */
  craftNotice: string;
  /** The same four states, drawn inside the craft panel instead of under it. */
  craftEmptyLabel: string;
  /**
   * WHY THE ARTISAN PICKER IS EMPTY -- but only for the states the FORM cannot answer itself.
   *
   * "" while the craft's roster is loading (the panel covers that wait in its own slot) and ""
   * once it has arrived, because a roster that arrived empty is exactly the claim the form's own
   * "No artisans are linked to this craft yet." is entitled to make. What is left is the failed
   * read, which had no sentence anywhere: the request's `.catch` deliberately leaves the mount
   * load's artisans on screen and deliberately does NOT move `artisansLoadedForCraft`, so the form
   * printed nothing at all and the picker simply looked short.
   */
  craftArtisanNotice: string;
  /** The same, drawn inside the artisan panel. `""` hands the form's own label back. */
  craftArtisanEmptyLabel: string;
};

export function useCraftAndArtisanOptions({
  craftId,
  artisanId
}: {
  craftId: string;
  artisanId: string;
}): CraftAndArtisanOptions {
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [crafts, setCrafts] = useState<Craft[]>([]);
  const [referenceState, setReferenceState] = useState<CarryScopeState>("pending");
  const [craftCut, setCraftCut] = useState<ListCut | null>(null);
  const [craftArtisanCut, setCraftArtisanCut] = useState<ListCut | null>(null);
  const [artisansLoadedForCraft, setArtisansLoadedForCraft] = useState<string | null>(null);
  /**
   * WHICH craft's roster request FAILED -- a craft id, for the same reason `artisansLoadedForCraft`
   * is one rather than a boolean. A bare flag left over from the previous craft would describe this
   * craft's roster with the previous craft's outcome, which is the mistake the field above exists
   * to stop, one state along.
   */
  const [artisansFailedForCraft, setArtisansFailedForCraft] = useState<string | null>(null);
  /**
   * WHERE EACH OF THE TWO REGISTERS CAME FROM — live, this browser's store, or nowhere.
   *
   * `referenceState` cannot answer this and must not be widened to: `carryScope` reads it to decide
   * whether a carried record is REACHABLE, and a list served from storage is every bit as reachable
   * as a live one. What changes with the source is only the SENTENCE (§3.5's cached-and-stale line
   * carries the date the register last crossed the wire), so the source travels beside the state
   * rather than inside it.
   */
  const [craftSource, setCraftSource] = useState<RegisterLoadOutcome>({ source: "none", cachedAt: null });
  /**
   * THE CRAFT-SCOPED ROSTER'S OWN PROVENANCE, CARRYING THE CRAFT ID — never a bare stamp.
   *
   * Same rule and same reason as `artisansLoadedForCraft` and `artisansFailedForCraft` above: a
   * date left over from the previous craft would describe THIS craft's roster with the previous
   * one's age, on the one sentence a designer uses to decide whether a missing name means the
   * person has no record. Cleared to null the moment the live answer lands.
   */
  const [rosterCache, setRosterCache] = useState<{ craftId: string; cachedAt: string } | null>(null);

  /*
    (1) THE REPOSITORY-WIDE REFERENCE LOAD, NOW CACHE-FIRST — DROPDOWN_DESIGN §3.3, on the web.

    It used to be one `Promise.all` of two live reads whose `.catch` set `referenceState` to
    `unavailable`, and that meant a researcher who opened `/products/new` in a courtyard met a craft
    dropdown holding nothing but "Unlinked / type below". The outbox underneath was working
    perfectly and would have carried the record — with its craft empty, because the picker had
    nothing in it. The handset has never had that failure: `WorkshopRepository.crafts()` goes
    through `DwReferenceStore`, so it offers whatever this device last saw.

    `loadCachedRegister` calls `onList` once or twice — storage, then the network if it answers — so
    the dropdowns fill from yesterday's register the instant this hook mounts and quietly improve.
    `referenceState` is `loaded` when BOTH answered from EITHER source, which is exactly Android's
    `gotCrafts && gotArtisans`; a register nobody could produce at all is still `unavailable`, and
    the four sentences below take it from there.

    THE TWO ARE SEPARATE LOADS NOW, WHERE THEY USED TO SHARE ONE `Promise.all`'s FATE. That is a
    deliberate improvement rather than a side effect: a craft register served from storage and an
    artisan register that failed outright are two different facts, and one rejected promise used to
    collapse them into a screen with neither list on it.
  */
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const [artisanOutcome, craftOutcome] = await Promise.all([
        loadCachedRegister<Artisan>({
          model: "artisan",
          decode: optionToArtisan,
          encode: artisanToOption,
          fetch: async () => (await listResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING })).items,
          // `mergeById`, not a replace: the craft-scoped load below and the by-id lookup only ever
          // ADD rows, and a cached answer arriving before them must not be able to drop one.
          // A LIVE ROW OUTRANKS A CACHED ONE OF THE SAME ID, and `mergeById` keeps whichever array
          // it is handed FIRST and only ADDS unseen ids from the second. So the live answer goes
          // first and the cached extras — rows off an older page that this one no longer reaches —
          // are appended behind it. Handed the other way round, a reduced cached row would survive
          // the refresh that was meant to replace it, and every column the codec does not carry
          // would stay blank for the rest of the session.
          onList: (rows, cachedAt) => {
            if (!cancelled) setArtisans((previous) => (cachedAt ? mergeById(previous, rows) : mergeById(rows, previous)));
          }
        }),
        loadCachedRegister<Craft>({
          model: "craft",
          decode: optionToCraft,
          encode: craftToOption,
          fetch: async () => {
            const page = await listResource<Craft>("/crafts", { pageSize: LIST_PAGE_CEILING });
            // THE CUT IS SET ONLY FROM A LIVE PAGE, and never from storage. `listCut` reports "80 of
            // 178" from the envelope's `total`, and a cached document has no envelope — printing a
            // truncation sentence off one would be a number invented on this device about a corpus
            // it cannot see. R4 asks for the number to be stated where it is known, not guessed.
            if (!cancelled) setCraftCut(listCut(page, "crafts"));
            return page.items;
          },
          onList: (rows) => {
            if (!cancelled) setCrafts(rows);
          }
        })
      ]);
      if (cancelled) return;
      setCraftSource(craftOutcome);
      setReferenceState(artisanOutcome.source === "none" || craftOutcome.source === "none" ? "unavailable" : "loaded");
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // (2) The chosen craft's roster, from the server. This is the request that actually closes the
  // defect: without it the artisan dropdown can only ever show the intersection of one craft with
  // the newest hundred rows of a 749-row table.
  useEffect(() => {
    if (!craftId) return;
    let cancelled = false;
    /*
      CACHED UNDER A NARROWED KEY — `artisan__ALL__<craftId>`, beside the whole register.

      Two documents for one model is `DwReferenceStore`'s own design and not a duplication: the
      whole register and one craft's roster are different answers to different questions, either may
      be what this browser has to hand, and the narrowed one is what the picker actually offers. A
      researcher who documented a product for this craft last week gets its roster back with no
      signal instead of the intersection of the craft with whatever the whole-register cache holds.
    */
    void loadCachedRegister<Artisan>({
      model: "artisan",
      filterValue: craftId,
      decode: optionToArtisan,
      encode: artisanToOption,
      fetch: async () => {
        const result = await listResource<Artisan>("/artisans", { craftId, pageSize: LIST_PAGE_CEILING });
        if (!cancelled) setCraftArtisanCut(listCut(result, "artisans of this craft"));
        return result.items;
      },
      onList: (rows, cachedAt) => {
        if (cancelled) return;
        // Live first, cached extras behind — see the mount load above for why the order decides
        // which copy of a shared id survives.
        setArtisans((previous) => (cachedAt ? mergeById(previous, rows) : mergeById(rows, previous)));
        // `artisansLoadedForCraft` IS MOVED BY A CACHED ANSWER TOO, and that is the point of the
        // whole change: it gates the form's "No artisans are linked to this craft yet." sentence,
        // and a roster this browser holds is a roster the form may speak about. What it must never
        // be moved by is a FAILURE, which is why the arm below still leaves it alone.
        setArtisansLoadedForCraft(craftId);
        setArtisansFailedForCraft((failed) => (failed === craftId ? null : failed));
        // `null` on the live answer, which CLEARS a stamp the cached pass just set — the sentence
        // has to go the instant the roster is confirmed, or a refreshed list keeps apologising for
        // an age it no longer has.
        setRosterCache(cachedAt ? { craftId, cachedAt } : null);
      }
    }).then((outcome) => {
      // `source === "none"` is the old `.catch` arm: neither storage nor the network had this
      // craft's roster. A CACHED ANSWER IS NOT A FAILURE and must not land here, which is what the
      // guard says — before it, a fetch that failed behind a good cache would have set both
      // `artisansLoadedForCraft` and `artisansFailedForCraft` for the same craft, and the caller
      // reads the second first.
      if (cancelled || outcome.source !== "none") return;
      // Leave what is already loaded on screen: the mount load's artisans are still a legitimate,
      // narrower offer, and `artisansLoadedForCraft` deliberately stays put so the caller does not
      // print "no artisans are linked to this craft" off a failure.
      //
      // SAY SO, THOUGH. Until this line the failure was silent as well as harmless, which left the
      // one state a reader cannot deduce -- "this roster never arrived" -- looking exactly like
      // "this craft has few artisans". `craftArtisanNotice` below is the sentence.
      setArtisansFailedForCraft(craftId);
    });
    return () => {
      cancelled = true;
    };
  }, [craftId]);

  // (3) The record's own artisan, whatever page they are on — see `useRecordOffPage` for why a
  // picker that cannot draw its own current value is worse than one that is merely short.
  const offPageArtisan = useRecordOffPage<Artisan>("/artisans", artisanId, artisans);
  const allArtisans = useMemo(
    () => (offPageArtisan ? mergeById(artisans, [offPageArtisan]) : artisans),
    [artisans, offPageArtisan]
  );

  /*
    THE FOUR SENTENCES, BUILT ONCE FOR BOTH FORMS. `ProductForm` and `ToolForm` ask the identical
    question of the API and had the identical bug in it -- which is this hook's whole reason for
    existing -- so the wording of a failed read belongs here too, or it is one sentence written
    twice and eventually two sentences about one fact.

    `scoped: false` on both voices: neither request carries an access filter, so an empty answer is
    a statement about the REPOSITORY and never about this account's grants. `accessList: false` on
    both: a register is not a grant set, so R6's reason for never keeping one on the device does not
    apply and must not be printed as though it did. `cached: true` on both, and ONLY because this
    hook genuinely writes both registers to `lib/referenceCache.ts` — it is what lets the offline
    sentence end "Connect once and the list is kept on the device from then on", which would be a
    promise nobody keeps on a surface that does not cache. See `WorkshopListVoice`.
  */
  const online = !deviceLooksOffline();
  const craftList: WorkshopListState<Craft> =
    referenceState === "pending"
      ? { kind: "loading" }
      : referenceState === "unavailable"
        ? { kind: "failed" }
        : {
            kind: "ok",
            rows: crafts,
            total: craftCut?.total ?? crafts.length,
            // Only when the NETWORK has not answered. `loadCachedRegister` reports `live` the moment
            // it does, so the cached-and-stale sentence disappears by itself the instant the register
            // is confirmed — it never lingers over a list that has just been refreshed.
            cachedAt: craftSource.source === "cached" ? craftSource.cachedAt : null
          };
  const craftVoice: WorkshopListVoice = {
    table: "field",
    noun: "crafts",
    scoped: false,
    accessList: false,
    cached: true,
    online
  };

  /*
    The roster read for THE CRAFT IN HAND, and it is three states rather than two. With no craft
    chosen there is nothing to report: the control says "Select a linked craft first" and is
    disabled, which is a complete answer already. `rows` is empty in the "ok" arm on purpose -- this
    state is only ever consulted for its KIND, because the genuinely-empty sentence belongs to the
    form (see `craftArtisanNotice`), and handing it a row count it would then have to agree with is
    an invitation for the two to disagree.
  */
  const craftArtisanList: WorkshopListState<Artisan> = !craftId
    ? { kind: "ok", rows: [], total: 0 }
    : artisansFailedForCraft === craftId
      ? { kind: "failed" }
      : artisansLoadedForCraft === craftId
        ? { kind: "ok", rows: [], total: 0 }
        : { kind: "loading" };
  const artisanVoice: WorkshopListVoice = {
    table: "field",
    noun: "artisans",
    scoped: false,
    accessList: false,
    cached: true,
    online
  };

  /*
    THE ROSTER'S OWN CACHED-AND-STALE LINE, BUILT HERE RATHER THAN THROUGH `craftArtisanList`.

    That state deliberately carries `rows: []` — it is consulted only for its KIND, because the
    genuinely-empty sentence belongs to the form — so a stamp on it would never reach
    `workshopListNotice`'s row-count guard. The count therefore comes from the artisans this hook
    actually holds for the craft in hand, which is what the picker below is about to draw.

    IT IS THE SENTENCE THAT MATTERS MOST ON THIS CONTROL, and that is why it is worth the six lines.
    The form's own "No artisans are linked to this craft yet." is a claim about the repository, and
    the reader's next move from a SHORT list is the same one: they conclude the person is not on
    record and type a new artisan in. A roster nine days old cannot support either conclusion, and
    the date is the only thing that lets a designer tell the two apart.
  */
  const craftRosterCount = craftId ? allArtisans.filter((row) => row.craftId === craftId).length : 0;
  const craftRosterCachedAt =
    rosterCache && rosterCache.craftId === craftId && craftRosterCount > 0 ? rosterCache.cachedAt : null;

  return {
    artisans: allArtisans,
    crafts,
    referenceState,
    craftCut,
    craftArtisanCut,
    artisansLoadedForCraft,
    craftNotice: workshopListNotice(craftList, craftVoice),
    craftEmptyLabel: workshopEmptyLabel(craftList, craftVoice),
    // The failed arm, and now the cached one. The other three are the form's own to word -- see the
    // field's doc; a roster that ARRIVED, live, still says nothing, because a list confirmed a
    // moment ago has no fact to report.
    craftArtisanNotice:
      craftArtisanList.kind === "failed"
        ? workshopListNotice(craftArtisanList, artisanVoice)
        : craftRosterCachedAt
          ? cachedListLine(craftRosterCount, "artisans", formatDate(craftRosterCachedAt))
          : "",
    craftArtisanEmptyLabel: craftArtisanList.kind === "ok" ? "" : workshopEmptyLabel(craftArtisanList, artisanVoice)
  };
}

/**
 * Should a craft change clear the artisan link?
 *
 * ONLY when this form actually knows the artisan practises a different craft. Both record forms
 * asked `!artisans.some((a) => a.id === artisanId && a.craftId === next)`, which is false for two
 * unrelated reasons — the craft differs, or the artisan is not in the loaded array at all — and
 * treated both as "wrong craft". Against a 100-row page of 749 artisans the second reason was the
 * ordinary one on any older record: opening a product or a tool to CORRECT ITS CRAFT blanked the
 * artisan field, and `artisanId` is in the backend's `CLEARABLE_KEYS`, so the save wrote an explicit
 * null and destroyed the artisan link under a 200 with nothing on screen saying so.
 *
 * When the artisan cannot be found even after the by-id lookup, the link is KEPT. That is the safe
 * direction and the choice is deliberate: an artisan wrongly left linked is visible on the form and
 * one click from being corrected; an artisan silently unlinked is neither.
 */
export function craftChangeClearsArtisan({
  nextCraftId,
  artisanId,
  artisans
}: {
  nextCraftId: string;
  artisanId: string;
  artisans: readonly Artisan[];
}): boolean {
  if (!nextCraftId || !artisanId) return false;
  const known = artisans.find((artisan) => artisan.id === artisanId);
  return Boolean(known) && known?.craftId !== nextCraftId;
}
