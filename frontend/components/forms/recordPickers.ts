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
 * 2. the chosen crafts' own roster, asked for in ONE request — with the `craftId` the endpoint has
 *    always accepted where exactly one craft is ticked, and with the plural `craftIds` that landed
 *    on 2026-09-15 for the tool form's multi-select — which turns a hundred-row window on 749
 *    artisans into, in practice, the complete answer for the crafts in hand;
 * 3. the record's own artisans, looked up by id when no loaded page holds them, so that "this
 *    artisan is not in the list" and "this artisan does not practise that craft" stop being the same
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

/**
 * THE SAME RECOVERY, FOR A MULTI-SELECT — every id the record points at, not only the first.
 *
 * {@link useRecordOffPage} answers for one id, which is the whole of what a single-select needs. A
 * tool now links SEVERAL crafts and SEVERAL artisans, and each of them is on page one or not
 * independently of the others: a toolkit linked to "Ajrakh" and to "Warli" opens with one tick drawn
 * and one missing the day the second craft sorts past the 100-row cut. That is the defect
 * `useRecordOffPage` exists for, one selection along, and it is worse here — a multi-select shows no
 * placeholder for a value it cannot draw, so the row simply is not there, and the obvious repair
 * (tick it again) is the one action that really does rewrite the link.
 *
 * NOT `useRecordOffPage` IN A LOOP: a hook cannot be called per element. This holds one map keyed by
 * id and fetches only the ids no loaded page holds.
 *
 * Returns the rows that were fetched AND are still wanted, in `ids` order. Callers `mergeById` them
 * into their options; nothing here mutates the page.
 */
export function useRecordsOffPage<T extends { id: string }>(
  endpoint: string,
  ids: readonly string[],
  rows: readonly T[]
): T[] {
  const [records, setRecords] = useState<Record<string, T>>({});
  const attempted = useRef(new Set<string>());
  /*
    THE KEY, NOT THE ARRAY, IS WHAT THE EFFECT DEPENDS ON. `ids` is a fresh literal on every render
    of every caller, so an array dependency would re-run this on every keystroke anywhere on the
    form — and the `attempted` guard would make that silent rather than visible. Same rule, same
    reason, as the roster key in `useCraftAndArtisanOptions` below.
  */
  const key = ids.join(",");

  useEffect(() => {
    let cancelled = false;
    for (const id of key ? key.split(",") : []) {
      if (!id || rows.some((row) => row.id === id)) continue;
      if (attempted.current.has(id)) continue;
      attempted.current.add(id);
      apiFetch<T>(`${endpoint}/${id}`)
        .then((row) => {
          if (!cancelled) setRecords((current) => ({ ...current, [id]: row }));
        })
        .catch(() => {
          // Not visible to this account, or gone — see `useRecordOffPage` for why this is silent
          // rather than an error banner. Nothing is broken; this form simply cannot offer to change
          // a link it can still see the name of.
        });
    }
    return () => {
      cancelled = true;
    };
  }, [endpoint, key, rows]);

  // Rows fetched for ids the researcher has since unticked are dropped rather than offered: they
  // are neither on a page nor selected, which is the one shape a picker row must never have.
  return useMemo(
    () => (key ? key.split(",") : []).map((id) => records[id]).filter((row): row is T => Boolean(row)),
    [key, records]
  );
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
  /** The chosen crafts' artisan roster's cut, or null when it holds every one of them. */
  craftArtisanCut: ListCut | null;
  /**
   * WHICH CRAFT SELECTION the roster in hand belongs to — the key {@link artisansLoadedForCraft} is
   * compared against, and the one place the sort-and-join rule is written.
   *
   * The craft ids, SORTED and joined with ",". Sorted because the same three crafts ticked in a
   * different order are the same question and must reach the same cached document rather than
   * minting a second one; joined because an effect may depend on a string and may not depend on an
   * array literal rebuilt every render.
   *
   * For a single-craft caller it is exactly the craft id, which is what it has always been — see
   * {@link artisansLoadedForCraft}.
   */
  craftRosterKey: string;
  /**
   * WHICH CRAFTS the loaded roster belongs to — not a boolean.
   *
   * "No artisans are linked to this craft yet" is a claim about the repository, and printing it off
   * the previous craft's rows while the new craft's request is still in flight makes that claim
   * before the answer exists. A caller must test `artisansLoadedForCraft === craftRosterKey` before
   * saying anything about emptiness.
   *
   * ── IT HOLDS A ROSTER KEY NOW, AND FOR A SINGLE-CRAFT CALLER THAT IS THE SAME STRING ──────────
   * It was "the craft id the roster was loaded for" until the tool form became multi-craft. The
   * value is now {@link craftRosterKey} — the ticked craft ids sorted and joined — which for one
   * ticked craft IS that craft's id, so `artisansLoadedForCraft === craftId` keeps meaning exactly
   * what it meant on the forms that pass a single craft. Nothing about the rule changed: a claim
   * about a roster may only be made off the answer for the selection currently on screen.
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

/**
 * ── THE ARGUMENT IS SINGULAR *OR* PLURAL, AND THAT IS NOT A CONVENIENCE ─────────────────────────
 *
 * `ToolForm` links SEVERAL crafts and SEVERAL artisans since the multi-select landed; `ProductForm`
 * links one of each and always will — a product is made by a person, of a craft. One hook serves
 * both because the QUESTION is identical ("which crafts, and which artisans of the chosen ones"),
 * which is the whole reason this hook exists rather than two copies of it.
 *
 * So the plural keys are the general form and the singular keys are the one-element case spelled
 * the way its callers already spell it. A caller passes one pair or the other; passing `craftIds`
 * wins, because a form that has both is a form mid-edit and the plural is the one that can express
 * what it holds. Nothing here narrows on the singular any more — it is widened into a one-element
 * list at the top and every line below reads the list.
 */
export function useCraftAndArtisanOptions({
  craftId,
  craftIds,
  artisanId,
  artisanIds,
  knownArtisans
}: {
  /** One craft — the single-select callers' spelling. Ignored when `craftIds` is given. */
  craftId?: string;
  /** Every ticked craft, in tick order. `[]` means none, which is not the same as absent. */
  craftIds?: readonly string[];
  /** One artisan — the single-select callers' spelling. Ignored when `artisanIds` is given. */
  artisanId?: string;
  /** Every ticked artisan, in tick order. */
  artisanIds?: readonly string[];
  /**
   * THE ARTISAN ROWS THE RECORD ITSELF CARRIES — `initial.artisanLinks[].artisan`, hydrated by the
   * server on every read of a tool.
   *
   * ⚠ IT IS THE ONLY ONE OF THE THREE SOURCES THAT WORKS OFFLINE, which is why it exists. The other
   * two are a network page and a by-id fetch (`useRecordsOffPage`), and in a courtyard with no
   * signal BOTH fail: an artisan past the first page of `/artisans` (749 rows, one page loaded) is
   * then in no option list at all, so the multi-select draws no row for a link the record holds and
   * every rule keyed on "can this form place the artisan" silently takes its do-nothing branch. The
   * handset has never had that hole — `knownArtisans` in `MainActivity.kt` is built from
   * `editing.artisanLinks[].artisan` FIRST, and this key is the web's half of the same rule, named
   * the same so the two read as one decision.
   *
   * MERGED BEHIND the loaded rows, never in front: a row fetched a moment ago outranks the copy
   * embedded in a payload that may be minutes or (out of the outbox) days old.
   */
  knownArtisans?: readonly Artisan[];
}): CraftAndArtisanOptions {
  const rosterCraftIds = craftIds ?? (craftId ? [craftId] : []);
  const wantedArtisanIds = artisanIds ?? (artisanId ? [artisanId] : []);
  // See `CraftAndArtisanOptions.craftRosterKey` for why it is sorted and why it is a string.
  const craftRosterKey = [...rosterCraftIds].sort().join(",");
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [crafts, setCrafts] = useState<Craft[]>([]);
  const [referenceState, setReferenceState] = useState<CarryScopeState>("pending");
  const [craftCut, setCraftCut] = useState<ListCut | null>(null);
  const [craftArtisanCut, setCraftArtisanCut] = useState<ListCut | null>(null);
  const [artisansLoadedForCraft, setArtisansLoadedForCraft] = useState<string | null>(null);
  /**
   * WHICH craft selection's roster request FAILED -- a roster key, for the same reason
   * `artisansLoadedForCraft` is one rather than a boolean. A bare flag left over from the previous
   * selection would describe this selection's roster with the previous one's outcome, which is the
   * mistake the field above exists to stop, one state along.
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
   * THE CRAFT-SCOPED ROSTER'S OWN PROVENANCE, CARRYING THE ROSTER KEY — never a bare stamp.
   *
   * Same rule and same reason as `artisansLoadedForCraft` and `artisansFailedForCraft` above: a
   * date left over from the previous selection would describe THIS selection's roster with the
   * previous one's age, on the one sentence a designer uses to decide whether a missing name means
   * the person has no record. Cleared to null the moment the live answer lands.
   */
  const [rosterCache, setRosterCache] = useState<{ craftKey: string; cachedAt: string } | null>(null);

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

  /*
    (2) THE TICKED CRAFTS' ROSTER, FROM THE SERVER, IN ONE REQUEST. This is the request that
    actually closes the defect: without it the artisan dropdown can only ever show the intersection
    of the chosen crafts with the newest hundred rows of a 749-row table.

    ── ONE REQUEST, WHICH IS WHY `craftIds` HAD TO EXIST ────────────────────────────────────────
    `/artisans` took a SINGULAR `craftId` and nothing else until 2026-09-15. A multi-craft picker
    cannot be served by it: issuing one request per ticked craft means "Select all 178" fires 178 of
    them, and filtering one 100-row page in the browser gives the intersection of N crafts with the
    newest hundred artisans overall — the ceiling this whole hook was written about, wearing a
    different hat. `services/record_filters.resolve_craft_ids` is the plural parameter; it accepts a
    comma-joined value, which is what `buildQuery` can send (`lib/api` takes no arrays).

    THE SINGULAR IS SENT TOO, BUT ONLY FOR ONE CRAFT, and it is a deploy-skew belt rather than a
    preference: the web and the API deploy separately, so a browser that has this build against an
    API that does not yet declare `craftIds` would send an unknown query parameter, which FastAPI
    ignores — and an ignored craft filter is a roster of the newest hundred artisans of every craft,
    under a truncation sentence claiming to be about these crafts. With one craft ticked both
    parameters narrow to the same set on a new server and the singular still narrows on an old one.
    With several ticked there is no singular that could be right, so none is sent and a stale API
    degrades to the pre-2026-09-15 behaviour instead of lying.
  */
  useEffect(() => {
    if (!craftRosterKey) return;
    const ids = craftRosterKey.split(",");
    let cancelled = false;
    /*
      CACHED UNDER A NARROWED KEY — `artisan__ALL__<the roster key>`, beside the whole register.

      Two documents for one model is `DwReferenceStore`'s own design and not a duplication: the
      whole register and one selection's roster are different answers to different questions, either
      may be what this browser has to hand, and the narrowed one is what the picker actually offers.
      A researcher who documented a product for this craft last week gets its roster back with no
      signal instead of the intersection of the craft with whatever the whole-register cache holds.

      THE KEY IS SORTED, so ticking Ajrakh then Warli and ticking Warli then Ajrakh read and write
      ONE document rather than two half-answers this browser then has to choose between offline.

      AND IT IS A DIGEST OF THAT SORTED KEY, NOT THE KEY. `referenceCacheKey` cuts each segment of
      the document name at 80 characters, so raw ids put four or more crafts' selections into one
      document and the picker then goes silently short for the crafts the collision dropped. See
      `craftRosterCacheKey` for the whole failure and why the fix is here rather than in the store.
      `craftRosterKey` itself is untouched — the effect still splits it back into the ids it sends.
    */
    void loadCachedRegister<Artisan>({
      model: "artisan",
      filterValue: craftRosterCacheKey(craftRosterKey),
      decode: optionToArtisan,
      encode: artisanToOption,
      fetch: async () => {
        const result = await listResource<Artisan>("/artisans", {
          craftIds: ids.join(","),
          // See the block above: the belt, and only where a singular exists that cannot be wrong.
          craftId: ids.length === 1 ? ids[0] : undefined,
          pageSize: LIST_PAGE_CEILING
        });
        // The noun has to agree with what was asked for, or a designer reads a cut about "this
        // craft" over a list drawn from four of them.
        if (!cancelled) setCraftArtisanCut(listCut(result, ids.length > 1 ? "artisans of these crafts" : "artisans of this craft"));
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
        setArtisansLoadedForCraft(craftRosterKey);
        setArtisansFailedForCraft((failed) => (failed === craftRosterKey ? null : failed));
        // `null` on the live answer, which CLEARS a stamp the cached pass just set — the sentence
        // has to go the instant the roster is confirmed, or a refreshed list keeps apologising for
        // an age it no longer has.
        setRosterCache(cachedAt ? { craftKey: craftRosterKey, cachedAt } : null);
      }
    }).then((outcome) => {
      // `source === "none"` is the old `.catch` arm: neither storage nor the network had this
      // selection's roster. A CACHED ANSWER IS NOT A FAILURE and must not land here, which is what
      // the guard says — before it, a fetch that failed behind a good cache would have set both
      // `artisansLoadedForCraft` and `artisansFailedForCraft` for the same selection, and the
      // caller reads the second first.
      if (cancelled || outcome.source !== "none") return;
      // Leave what is already loaded on screen: the mount load's artisans are still a legitimate,
      // narrower offer, and `artisansLoadedForCraft` deliberately stays put so the caller does not
      // print "no artisans are linked to this craft" off a failure.
      //
      // SAY SO, THOUGH. Until this line the failure was silent as well as harmless, which left the
      // one state a reader cannot deduce -- "this roster never arrived" -- looking exactly like
      // "this craft has few artisans". `craftArtisanNotice` below is the sentence.
      setArtisansFailedForCraft(craftRosterKey);
    });
    return () => {
      cancelled = true;
    };
  }, [craftRosterKey]);

  // (3) The record's own artisans, whatever page they are on — see `useRecordsOffPage` for why a
  // picker that cannot draw its own current value is worse than one that is merely short, and why
  // a multi-select needs the plural of that rule rather than the first id of it.
  const offPageArtisans = useRecordsOffPage<Artisan>("/artisans", wantedArtisanIds, artisans);
  // (4) And the rows the RECORD carries, which is the only one of the four sources that answers with
  // no network at all — see `knownArtisans` above for the offline hole (3) cannot close. Last in the
  // merge, so a freshly read row always wins over the embedded copy.
  const allArtisans = useMemo(() => {
    const loaded = offPageArtisans.length ? mergeById(artisans, offPageArtisans) : artisans;
    return knownArtisans?.length ? mergeById(loaded, knownArtisans) : loaded;
  }, [artisans, offPageArtisans, knownArtisans]);

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
    The roster read for THE CRAFTS IN HAND, and it is three states rather than two. With no craft
    chosen there is nothing to report: the control says "Select a linked craft first" and is
    disabled, which is a complete answer already. `rows` is empty in the "ok" arm on purpose -- this
    state is only ever consulted for its KIND, because the genuinely-empty sentence belongs to the
    form (see `craftArtisanNotice`), and handing it a row count it would then have to agree with is
    an invitation for the two to disagree.
  */
  const craftArtisanList: WorkshopListState<Artisan> = !craftRosterKey
    ? { kind: "ok", rows: [], total: 0 }
    : artisansFailedForCraft === craftRosterKey
      ? { kind: "failed" }
      : artisansLoadedForCraft === craftRosterKey
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
    actually holds for the crafts in hand, which is what the picker below is about to draw.

    IT IS THE SENTENCE THAT MATTERS MOST ON THIS CONTROL, and that is why it is worth the six lines.
    The form's own "No artisans are linked to this craft yet." is a claim about the repository, and
    the reader's next move from a SHORT list is the same one: they conclude the person is not on
    record and type a new artisan in. A roster nine days old cannot support either conclusion, and
    the date is the only thing that lets a designer tell the two apart.
  */
  const craftRosterCount = craftRosterKey
    ? allArtisans.filter((row) => Boolean(row.craftId) && rosterCraftIds.includes(row.craftId as string)).length
    : 0;
  const craftRosterCachedAt =
    rosterCache && rosterCache.craftKey === craftRosterKey && craftRosterCount > 0 ? rosterCache.cachedAt : null;

  return {
    artisans: allArtisans,
    crafts,
    referenceState,
    craftCut,
    craftArtisanCut,
    craftRosterKey,
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

/**
 * THE SAME RULE FOR A MULTI-CRAFT PICKER: which artisans a craft DESELECTION must drop.
 *
 * `ToolForm`'s craft picker became a multi-select on 2026-09-15 and the single-craft rule above
 * cannot express what happens there: deselecting one of four crafts must drop exactly the artisans
 * this form knows practise ONLY the craft that went, and keep every other tick. Clearing the whole
 * artisan selection on any craft change — which is what the singular rule does, read naively — would
 * throw away three crafts' worth of links because a fourth was unticked.
 *
 * EVERY CLAUSE OF THE SINGULAR RULE SURVIVES, and they are the same clauses for the same reason:
 *
 *  * an artisan this form cannot find is KEPT. "Not on the list" and "not of that craft" are
 *    different observations, and reading the first as the second is the silent link deletion
 *    `craftChangeClearsArtisan` was written to end — against a 100-row page of 749 artisans that was
 *    the ordinary case on any older record, and `artisanId` is in the backend's `CLEARABLE_KEYS`, so
 *    the save wrote an explicit null under a 200 with nothing on screen saying so;
 *  * an artisan whose record names no craft at all is KEPT, for the same reason: this form has
 *    learned nothing about them that contradicts the tick;
 *  * an artisan of a craft that is STILL ticked is kept, which is the whole point of the plural.
 *
 * ── AND A FOURTH CLAUSE, WHICH IS WHY `removedCraftIds` IS A PARAMETER AND NOT DERIVED HERE ─────
 * An artisan is dropped ONLY when the craft this form knows they practise is one of the crafts the
 * designer JUST REMOVED. "Their craft is not in the next list" is a WIDER condition and reading the
 * two as one deleted links nobody touched: `POST /tools/{id}/artisans` (the "Assign a tool to
 * multiple artisans" panel) links an artisan to a tool with no craft check at all, so a tool
 * legitimately holds artisans of crafts that were never ticked on it. Under the wider condition,
 * ADDING a second craft condemned every one of them — `nextCraftIds` grew, their craft was still
 * absent from it, and a save the designer thought only added a craft deleted their `ToolArtisan`
 * rows through `_write_links`' delete_many/create_many, permanently and silently.
 *
 * So the argument is what CHANGED, not what the answer now is. A caller computes it as
 * `craftIds.filter((id) => !next.includes(id))` — the crafts that went — and an empty array means
 * nothing was removed and therefore nothing may be dropped, whatever else moved. It is REQUIRED
 * rather than optional-with-a-default for that reason: the old shape is exactly the defect, and a
 * default would let it back in by omission.
 *
 * Returns the ids to DROP, so a caller reads `next = artisanIds.filter(id => !dropped.includes(id))`
 * or sets them aside to say what was dropped. An empty array is the common case.
 *
 * THE SINGULAR IS NOT DELETED AND IS NOT A WRAPPER OF THIS. `ProductForm` links one artisan of one
 * craft and always will, and its rule answers a yes/no question about one id; folding the two would
 * make the simpler form carry the multi-select's vocabulary for nothing. Both are pinned by
 * `e2e/capped-lists-unit.spec.ts`.
 */
export function craftsChangeClearsArtisans({
  nextCraftIds,
  removedCraftIds,
  artisanIds,
  artisans
}: {
  nextCraftIds: readonly string[];
  /** The crafts this change UNTICKED. `[]` drops nothing — see the fourth clause above. */
  removedCraftIds: readonly string[];
  artisanIds: readonly string[];
  artisans: readonly Artisan[];
}): string[] {
  if (!removedCraftIds.length) return [];
  return artisanIds.filter((id) => {
    const known = artisans.find((artisan) => artisan.id === id);
    if (!known || !known.craftId) return false;
    // BOTH clauses, though a well-formed caller makes the second implied by the first: `removed` is
    // `craftIds \ next`, so a craft in it is by construction absent from `next`. Stated anyway,
    // because it is the invariant this function's answer depends on and a caller that passed an
    // inconsistent pair would otherwise drop an artisan of a craft that is still ticked.
    return removedCraftIds.includes(known.craftId) && !nextCraftIds.includes(known.craftId);
  });
}

/** The heading a ticked artisan whose craft this form cannot name is filed under. */
export const UNLINKED_CRAFT_GROUP = "Unlinked craft";

/**
 * WHICH CRAFT AN ARTISAN IS SHOWN UNDER — three answers, in this order, and the last is "".
 *
 * 1. the artisan's own hydrated craft (`GET /artisans` includes it);
 * 2. the ticked craft their `craftId` points at, which is what a row rebuilt out of
 *    `lib/referenceCache` carries — `optionToArtisan` keeps `craftId` and cannot keep `craft.name`;
 * 3. nothing, for an artisan whose craft this form genuinely cannot name.
 *
 * The SELECTED crafts, not the whole register, feed step 2: an artisan of an unticked craft cannot
 * be in this list at all, so a name found there would be a heading for a craft nobody chose.
 */
export function craftNameForArtisan(artisan: Artisan, selectedCrafts: readonly Craft[]): string {
  const own = artisan.craft?.name?.trim();
  if (own) return own;
  return selectedCrafts.find((craft) => craft.id === artisan.craftId)?.name?.trim() ?? "";
}

/**
 * THE CANONICAL ARTISAN ORDER — by craft name A→Z, then by artisan name A→Z.
 *
 * ── THE COLLATION IS PINNED, AND IT IS NOT NEGOTIABLE ───────────────────────────────────────────
 * The identical comparison exists on the handset, and the two clients draw the same picker over the
 * same rows. `String.prototype.toLowerCase` is locale-INDEPENDENT (Unicode default case conversion)
 * and matches Kotlin's no-argument `lowercase()`, which is `Locale.ROOT`; `<`/`>` on strings is
 * UTF-16 code-unit order and matches Kotlin's `compareTo`. `localeCompare`, `Intl.Collator`,
 * `java.text.Collator`, `String.CASE_INSENSITIVE_ORDER` and `compareTo(other, ignoreCase = true)`
 * are all FORBIDDEN here: each is ICU-version-dependent, locale-dependent, or compares in both
 * cases, and any of them would make a Devanagari or Gujarati craft name sort differently on a
 * laptop and on a phone showing the same list.
 *
 * ── AN ARTISAN WHOSE CRAFT IS UNKNOWN SORTS LAST, NEVER FIRST ───────────────────────────────────
 * "" sorts before every letter, so the naive key would put every row this form could not explain at
 * the top of a list headed by the craft it was narrowed to. The leading flag is what stops that.
 *
 * ── THE ORDER IS TOTAL, SO STABILITY DOES NOT MATTER ────────────────────────────────────────────
 * The key ends in the artisan's id, a cuid, which is unique. `Array.prototype.sort` is stable in
 * every modern engine and `sortedWith` is stable on the JVM, but neither client may depend on that
 * to agree with the other. The raw name is compared before the id so that two artisans whose folded
 * names tie still read in a settled order rather than by id.
 */
export function sortArtisansByCraft(artisans: readonly Artisan[], selectedCrafts: readonly Craft[]): Artisan[] {
  const fold = (value: string) => value.trim().toLowerCase();
  const compare = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0);
  return [...artisans].sort((left, right) => {
    const leftCraft = fold(craftNameForArtisan(left, selectedCrafts));
    const rightCraft = fold(craftNameForArtisan(right, selectedCrafts));
    const unknown = (leftCraft === "" ? 1 : 0) - (rightCraft === "" ? 1 : 0);
    if (unknown !== 0) return unknown;
    return (
      compare(leftCraft, rightCraft) ||
      compare(fold(left.name ?? ""), fold(right.name ?? "")) ||
      compare(left.name ?? "", right.name ?? "") ||
      compare(left.id, right.id)
    );
  });
}

/**
 * The artisan multi-select's rows: the canonical order above, with the craft as a GROUP HEADING.
 *
 * Built here rather than in the form because it is one rule with four clients and a form is the one
 * place a test cannot reach it. `groupRows` (components/ui/selectFilter) buckets in FIRST-APPEARANCE
 * order and only moves the ungrouped bucket to the front, so handing it this already-sorted array is
 * what makes the headings read A→Z with "Unlinked craft" last — the ordering is not restated there
 * and must not be.
 *
 * `hint` is the place and not the craft: `SelectOption.hint` is SEARCHED as well as drawn, so typing
 * a village narrows the list, and the craft is already a heading over the row. The label is the name
 * alone for the same reason — with the place in the hint, "Name · Place" in the label would print it
 * twice on one 36px row.
 */
export function artisanPickerOptions(
  artisans: readonly Artisan[],
  selectedCrafts: readonly Craft[]
): { value: string; label: string; hint?: string; group?: string }[] {
  return sortArtisansByCraft(artisans, selectedCrafts).map((artisan) => ({
    value: artisan.id,
    label: artisan.name?.trim() || "Unnamed artisan",
    hint: artisan.place?.trim() || undefined,
    group: craftNameForArtisan(artisan, selectedCrafts) || UNLINKED_CRAFT_GROUP
  }));
}

/**
 * The craft-name box's value for a multi-craft selection: the names joined ", " IN TICK ORDER.
 *
 * TICK ORDER AND NOT ALPHABETICAL, because this string is the wire contract. The server derives
 * `tool.craftName` from `craftIds` in the order it was sent and returns `craftLinks` ordered to
 * match, so a client that joined the names in a different order would put a box on screen that
 * disagrees with the record the moment it is reopened.
 *
 * An id the picker cannot name is skipped rather than printed as an id — the box is prose a designer
 * reads, and a cuid in it is worse than a shorter list.
 */
export function joinCraftNames(craftIds: readonly string[], crafts: readonly Craft[]): string {
  return craftIds
    .map((id) => crafts.find((craft) => craft.id === id)?.name?.trim())
    .filter((name): name is string => Boolean(name))
    .join(", ");
}

/**
 * THE LONGEST `craftName` THE WIRE ACCEPTS — `ToolCreate.craftName` / `ToolUpdate.craftName` are
 * `Field(min_length=1, max_length=180)` (`backend/app/schemas/records.py:731`, `:827`).
 *
 * A cap written for ONE craft name, inherited by a box that now holds every ticked craft's name
 * joined ", ". `Craft.name` is itself bounded at 180 (`records.py:336`), so TWO registered crafts of
 * ninety characters already cross it; in practice it is a dozen ticks, or one press of the craft
 * picker's "Select all 100".
 *
 * ── WHY THIS IS A REFUSAL AT THE PICKER AND NOT A TRUNCATION ANYWHERE ───────────────────────────
 * The box's value is echoed verbatim in the body on both the POST and the PATCH, and `craftName` is
 * NOT NULL and REQUIRED on `ToolCreate` — so "just stop sending it and let the server derive it"
 * is not available: a create with no `craftName` is a 422 before `_resolve_craft_links` runs.
 * Truncating is worse than refusing and the backend says so in as many words
 * (`_resolve_craft_links`' docstring: *"never to truncate here, because a truncated `craftName`
 * would disagree with the links it was derived from"*) — and a truncated box would also break this
 * form's own promise that the box always shows what will be stored.
 *
 * What an over-long selection costs if it is allowed through is not a cosmetic 422. `saveOrQueue`
 * refuses to queue a 4xx ("the server saw it and said no", `lib/offline.ts`), so online the save is
 * simply lost; OFFLINE — the ordinary case for this app — the body is queued without ever being
 * shown to a server, and on drain the 422 is not a dangling reference (`isDanglingReference` needs a
 * 404 or a "not found" message) and `craftName` is not in `OutboxBanner`'s `REPICK_SOURCES`, so the
 * entry gets no re-pick button: "Try again" refetches the same refusal for ever and the only other
 * control is Discard, which destroys the record and every photograph staged against it.
 *
 * THE COMPLETE FIX IS BIGGER THAN THIS CONSTANT AND IS NOT DONE. The backend docstring prescribes
 * raising the bound on both schema fields TOGETHER WITH the clients' own input limits; this is the
 * client half, and the handset (`MainActivity.kt`, `craftName = craftName.trim()` inside
 * `ToolCreateRequest`) sends the same joined string with no limit of its own and still fails at the
 * same threshold. Re-check with
 * `grep -n "craftName: str" backend/app/schemas/records.py`.
 */
export const CRAFT_NAME_MAX_LENGTH = 180;

/** Whether a craft change may be applied, and what the field says about it. `notice` is "" when all is well. */
export type CraftSelectionVerdict = { refuse: boolean; notice: string };

/**
 * MAY THIS CRAFT CHANGE BE APPLIED, and what does the field say about it?
 *
 * It judges a CHANGE and not a selection, and the difference is a dead end. A tick that would carry
 * `craftName` past {@link CRAFT_NAME_MAX_LENGTH} is refused — but an EDIT can open with a selection
 * that is ALREADY over it (a record saved by the handset, which has no limit of its own, or by any
 * build older than this one), and a rule that only looked at the new selection would then refuse
 * every untick as well: the designer would be holding the one record they cannot repair, by the hand
 * of the check meant to protect them. So a change that makes the name SHORTER is always applied,
 * whatever it still measures, and the sentence switches from "this was refused" to "keep going".
 *
 * MEASURED ON THE JOINED NAMES, which is what the box and the body carry, and not on the number of
 * crafts: twelve is the usual threshold and two 90-character craft names is reachable, so a count of
 * crafts would be a rule about the wrong quantity. The sentence counts characters for the same
 * reason — a selection holding a craft whose row has not loaded contributes a tick but no name, and
 * a refusal that misstates what it counted is how a reader concludes the rule is arbitrary.
 *
 * ONE RESIDUAL, STATED RATHER THAN HIDDEN: `joinCraftNames` skips ids whose craft row has not
 * loaded, so a selection holding an off-page craft measures short here and can still be refused by
 * the server once its name arrives. The gap closes itself the moment `useRecordsOffPage` fills the
 * row in — and it can only ever UNDER-count, so this never refuses a selection the wire would have
 * taken.
 */
export function craftSelectionVerdict({
  nextCraftIds,
  currentCraftIds,
  crafts
}: {
  nextCraftIds: readonly string[];
  /** What is ticked now — the change is judged against it, never in isolation. */
  currentCraftIds: readonly string[];
  crafts: readonly Craft[];
}): CraftSelectionVerdict {
  const next = joinCraftNames(nextCraftIds, crafts);
  if (next.length <= CRAFT_NAME_MAX_LENGTH) return { refuse: false, notice: "" };
  const current = joinCraftNames(currentCraftIds, crafts);
  if (next.length < current.length) {
    return {
      refuse: false,
      notice:
        `Still ${next.length} characters of craft name, and the longest this record stores is ` +
        `${CRAFT_NAME_MAX_LENGTH}. Keep unticking, or this tool cannot be saved.`
    };
  }
  return {
    refuse: true,
    notice:
      `The craft name this selection makes is ${next.length} characters and the longest this record stores is ` +
      `${CRAFT_NAME_MAX_LENGTH}. The selection is unchanged — untick a craft, or link fewer of them, and record ` +
      `the rest as a second tool.`
  };
}

/**
 * WHICH CACHED DOCUMENT ONE CRAFT SELECTION'S ARTISAN ROSTER BELONGS TO — a FIXED-LENGTH token.
 *
 * ── THE IDS THEMSELVES CANNOT GO IN THE KEY, AND THE REASON IS A SILENT TRUNCATION ──────────────
 * `referenceCache.referenceCacheKey` builds `<model>__ALL__<filter>` through `safeName`, which
 * CUTS EACH SEGMENT AT 80 CHARACTERS. A cuid is 25, so three ids and their separators are 77 and a
 * fourth is 103 — every selection of four or more crafts that shares its three lexicographically
 * smallest ids resolves to ONE document. That is not a corner: cuids sort roughly by creation time,
 * so "select all 178" and any 177 of them collide by construction.
 *
 * What the collision does is worse than a wrong list, because the form narrows the rows again
 * client-side: the extra craft's artisans are filtered back out and the MISSING craft's artisans
 * were never fetched, so the picker is silently short while `artisansLoadedForCraft` is stamped as
 * this selection's answer and the cached-and-stale line counts the rows that did arrive "for the
 * crafts in hand". A designer reads a craft with a dozen artisans as a craft with none and types a
 * new one in — the silent-narrowing failure this whole module exists to close, wearing the cache as
 * a hat. Online it is also self-perpetuating: the live answer overwrites the shared document, so the
 * two selections evict each other for ever.
 *
 * ── SO THE KEY IS A DIGEST, AND ITS LENGTH DOES NOT DEPEND ON THE SELECTION ─────────────────────
 * Two independent FNV-1a passes over the sorted joined ids, plus the count, is 64 bits of hash under
 * a constant ~26 characters — comfortably inside the 80 and holding only characters `safeName`
 * leaves alone. It is not a cryptographic digest and does not need to be: nothing about this key is
 * adversarial, and the property required is only that two DIFFERENT selections on one device get
 * different documents.
 *
 * THE COUNT IS IN THE TOKEN ON PURPOSE. It costs nothing, it makes a stored key legible in a
 * debugger ("crafts4-…" is four ticked crafts), and it means the one class of hash collision that
 * could matter — two selections of different sizes — cannot happen at all.
 *
 * DOCUMENTS WRITTEN UNDER THE OLD TRUNCATING KEY ARE ORPHANED, NOT MIGRATED, and that is the right
 * trade: they are caches, the next roster read refills them under the new token, and a migration
 * would have to preserve exactly the colliding documents this function exists to stop reading.
 *
 * Takes the ROSTER KEY (`craftRosterKey` — the ids sorted and joined ","), not the array, because
 * that string is already the canonical spelling of "which crafts" and re-deriving it here would be a
 * second place for the sort rule to live. `""` in, `""` out: no crafts is no document.
 */
export function craftRosterCacheKey(rosterKey: string): string {
  if (!rosterKey) return "";
  const count = rosterKey.split(",").length;
  return `crafts${count}-${fnv1a(rosterKey, 0x811c9dc5)}${fnv1a(rosterKey, 0x9e3779b1)}`;
}

/**
 * One FNV-1a pass, as eight hex characters. Two passes from different offset bases give 64 bits.
 *
 * `Math.imul` and the `>>> 0` are what keep it EXACT: a plain `*` on the 32-bit prime leaves the
 * float range after a few characters and starts losing low bits, which would make the digest depend
 * on where in the string it stopped being precise rather than on the string.
 */
function fnv1a(text: string, seed: number): string {
  let hash = seed;
  for (let index = 0; index < text.length; index += 1) {
    hash ^= text.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0).toString(16).padStart(8, "0");
}
