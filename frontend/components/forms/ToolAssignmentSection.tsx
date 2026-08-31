"use client";

import { Link2, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import { Field } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext, type CarryScopeState } from "@/components/forms/CarryContextBanner";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { LIST_PAGE_CEILING, listCut, type ListCut } from "@/components/data/cappedList";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import {
  artisanToOption,
  craftToOption,
  optionToArtisan,
  optionToCraft,
  optionToTool,
  toolToOption
} from "@/components/forms/recordPickers";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { loadCachedRegister } from "@/lib/referenceCache";
import {
  cachedListLine,
  deviceLooksOffline,
  workshopEmptyLabel,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";
import type { Artisan, Craft, ToolDocumentation } from "@/lib/types";

/**
 * "Assign a tool to multiple artisans": the same documented tool can be mapped to several artisans
 * across the same or different crafts, so the tool need not be re-entered per craft. Pick the tool,
 * choose one or more crafts, then tick the artisans of those crafts to assign.
 */
export function ToolAssignmentSection() {
  const [tools, setTools] = useState<ToolDocumentation[]>([]);
  const [crafts, setCrafts] = useState<Craft[]>([]);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  /**
   * ISO-8601 when the TOOL register came out of storage, null once the network confirms it.
   *
   * Only the tool list carries one, and that is not an oversight. It is the control a researcher
   * hunts a specific toolkit in — the one where a name that is not on the list is read as "this tool
   * was never documented, I will document it again" — while the craft and artisan pickers below it
   * are narrowing controls whose own empty sentences already cover them.
   */
  const [toolCachedAt, setToolCachedAt] = useState<string | null>(null);
  const [toolId, setToolId] = useState("");
  const [craftIds, setCraftIds] = useState<string[]>([]);
  const [artisanIds, setArtisanIds] = useState<string[]>([]);
  const [assigned, setAssigned] = useState<Artisan[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // "Can I see this artisan?" and "is there any signal?" are different answers, and the carry-
  // forward prefill treats them differently — see useCarryContext. All three lists land together,
  // so one state covers every scope built from them.
  const [referenceState, setReferenceState] = useState<CarryScopeState>("pending");
  /**
   * WHAT EACH OF THE THREE DROPDOWNS IS NOT SHOWING — see `components/data/cappedList`.
   *
   * All three loads below ask for the ceiling `normalize_pagination` clamps to (100) and all three
   * tables are past it: 177 tools, 178 crafts, 749 artisans, counted against this repository's
   * Postgres on 2026-08-15. The artisan arm is the one that compounds — `artisansForCrafts` filters
   * that hundred-row page by the ticked crafts, so an artisan of the right craft who was entered
   * before the newest hundred is absent from a list headed "Artisans of selected crafts", and the
   * panel's own `emptyLabel` then reads "No artisans for these crafts" over crafts that have plenty.
   *
   * THIS IS THE ONE SITE IN THIS PASS THAT IS NOT FULLY CLOSED, and the reason is a missing server
   * parameter rather than a preference. `/artisans` takes a SINGULAR `craftId`; the product and tool
   * forms use it and are therefore whole. This picker is a MULTI-select, so the equivalent request
   * needs a plural `craftIds` (the shape `workshopIds` already has on the same route). Issuing one
   * request per ticked craft is not a substitute — "Select all 178" would fire 178 of them. Until
   * that parameter exists the cut is at least stated rather than silent, which is the difference
   * between a short list and a list that lies.
   */
  const [cuts, setCuts] = useState<{ tools: ListCut | null; crafts: ListCut | null; artisans: ListCut | null }>({
    tools: null,
    crafts: null,
    artisans: null
  });

  /*
    CACHE-FIRST FOR ALL THREE REGISTERS — DROPDOWN_DESIGN §3.3, on the web.

    This panel used to be one `Promise.all` of three live reads whose single `.catch` set
    `referenceState` to `unavailable` and put a red line where the tool picker should be. None of the
    three is a grant set — a tool, a craft and an artisan are records, and §3.3 rules the opposite
    way for a REGISTER than it does for an access list — so all three now come out of
    `lib/referenceCache.ts` first and are refreshed behind that. `loadCachedRegister` calls back once
    or twice; the state below is `loaded` when all three answered from EITHER source, which is the
    same test Android's `loadToolRegister` / `loadCraftRegister` / `loadArtisanRegister` trio makes.

    THE ERROR LINE IS NOW ONLY FOR THE CASE THAT REALLY HAS NOTHING. It used to fire on any rejected
    read, including one behind a perfectly good cache, and this panel's message sits above three
    dropdowns that would then be full of options — a red sentence contradicting the screen under it.
  */
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const [toolOutcome, craftOutcome, artisanOutcome] = await Promise.all([
        loadCachedRegister<ToolDocumentation>({
          model: "tool",
          decode: optionToTool,
          encode: toolToOption,
          fetch: async () => {
            const page = await listResource<ToolDocumentation>("/tools", { pageSize: LIST_PAGE_CEILING });
            // Live pages only. A cached document carries no envelope, and a truncation sentence
            // built from one would be a number invented here about a corpus this browser cannot see.
            if (!cancelled) setCuts((previous) => ({ ...previous, tools: listCut(page, "tools") }));
            return page.items;
          },
          onList: (rows, cachedAt) => {
            if (cancelled) return;
            setTools(rows);
            setToolCachedAt(cachedAt);
          }
        }),
        loadCachedRegister<Craft>({
          model: "craft",
          decode: optionToCraft,
          encode: craftToOption,
          fetch: async () => {
            const page = await listResource<Craft>("/crafts", { pageSize: LIST_PAGE_CEILING });
            if (!cancelled) setCuts((previous) => ({ ...previous, crafts: listCut(page, "crafts") }));
            return page.items;
          },
          onList: (rows) => {
            if (!cancelled) setCrafts(rows);
          }
        }),
        loadCachedRegister<Artisan>({
          model: "artisan",
          decode: optionToArtisan,
          encode: artisanToOption,
          fetch: async () => {
            const page = await listResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING });
            if (!cancelled) setCuts((previous) => ({ ...previous, artisans: listCut(page, "artisans") }));
            return page.items;
          },
          onList: (rows) => {
            if (!cancelled) setArtisans(rows);
          }
        })
      ]);
      if (cancelled) return;
      const missing = [toolOutcome, craftOutcome, artisanOutcome].some((outcome) => outcome.source === "none");
      setReferenceState(missing ? "unavailable" : "loaded");
      if (missing) {
        setError("Failed to load options, and this browser has not been given them before. Reconnect and reload.");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  /*
    THE THREE LISTS AS THE SHARED VOCABULARY SEES THEM.

    All three arrive in one `Promise.all`, so one state answers for all three: they land together or
    not at all. Read only for its KIND -- each picker below keeps its own genuinely-empty sentence,
    because "no artisans for THESE CRAFTS" is a narrower claim than the shared "No artisans have
    been recorded yet" and the two must not be swapped.

    `scoped: false`: none of the three requests carries an access filter, so an empty answer is a
    statement about the repository. `accessList: false`: a register is not a grant set, so R6's
    reason for never keeping one on the device does not apply and must not be printed as though it
    did. `reassurance`: the shared clause is "this record can be saved without it", and nothing on
    this panel is a record -- the assignment cannot be sent at all without a tool and an artisan, so
    what an admin needs instead is that the list is short rather than the register empty.
  */
  const referenceList: WorkshopListState<never> =
    referenceState === "pending"
      ? { kind: "loading" }
      : referenceState === "unavailable"
        ? { kind: "failed" }
        : { kind: "ok", rows: [], total: null };
  const referenceOnline = !deviceLooksOffline();
  const voiceFor = (noun: string): WorkshopListVoice => ({
    table: "field",
    noun,
    scoped: false,
    accessList: false,
    // All three lists are written to `lib/referenceCache.ts` by the effect above, so the offline
    // sentence may end "Connect once and the list is kept on the device from then on" — a promise
    // only a caller that actually caches is allowed to make. See `WorkshopListVoice.cached`.
    cached: true,
    online: referenceOnline,
    reassurance: "Nothing you picked is lost. The message above says what happened."
  });
  /** The picker's own sentence once the read has ANSWERED; the shared ones before that. */
  const emptyLabelFor = (noun: string, own: string) =>
    referenceList.kind === "ok" ? own : workshopEmptyLabel(referenceList, voiceFor(noun));

  // Open with the sitting already loaded: the craft and artisan last documented, and the tool
  // itself. Documenting a tool and then assigning it to the other artisans in the same courtyard is
  // one continuous act, so the tool the researcher just wrote up is the one this panel opens on —
  // it is no longer "this panel's own subject" but a record the bag genuinely holds.
  const carry = useCarryContext({
    // Each of the three dropdowns is built from exactly one of these lists, so "absent from the
    // list" answers both "can this researcher still reach it" and "could this panel show it".
    scopes: [
      carryScope("artisan", referenceState, artisans),
      carryScope("craft", referenceState, crafts),
      carryScope("tool", referenceState, tools)
    ],
    // The panel assigns a tool to artisans of a craft; it has no workshop, product or process
    // field, so those are neither filled in nor claimed.
    applies: ["craft", "artisan", "tool"],
    onApply: (context) => {
      if (context.craftId) setCraftIds([context.craftId]);
      if (context.artisanId) setArtisanIds([context.artisanId]);
      if (context.toolId) setToolId(context.toolId);
    }
  });
  const pruneCarried = carry.prune;
  /** "Change": drop every carried value so the researcher picks from scratch. */
  function clearCarriedContext() {
    carry.change();
    setToolId("");
    setCraftIds([]);
    setArtisanIds([]);
  }

  const artisansForCrafts = useMemo(
    () => artisans.filter((artisan) => artisan.craftId && craftIds.includes(artisan.craftId)),
    [artisans, craftIds]
  );

  // Keep the artisan selection within the chosen crafts.
  useEffect(() => {
    setArtisanIds((ids) => ids.filter((id) => artisansForCrafts.some((artisan) => artisan.id === id)));
  }, [artisansForCrafts]);

  useEffect(() => {
    if (!toolId) {
      setAssigned([]);
      return;
    }
    apiFetch<Artisan[]>(`/tools/${toolId}/artisans`)
      .then(setAssigned)
      .catch(() => setAssigned([]));
  }, [toolId]);

  async function assign() {
    if (!toolId || artisanIds.length === 0) return;
    setBusy(true);
    setMessage(null);
    setError(null);
    try {
      const updated = await apiFetch<Artisan[]>(`/tools/${toolId}/artisans`, {
        method: "POST",
        body: JSON.stringify({ artisanIds })
      });
      setAssigned(updated);
      setArtisanIds([]);
      setMessage(`This tool is now assigned to ${updated.length} artisan(s).`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Assignment failed");
    } finally {
      setBusy(false);
    }
  }

  async function unassign(artisanId: string) {
    if (!toolId) return;
    try {
      await apiFetch(`/tools/${toolId}/artisans/${artisanId}`, { method: "DELETE" });
      setAssigned((prev) => prev.filter((artisan) => artisan.id !== artisanId));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not remove the assignment");
    }
  }

  return (
    <section className="panel mt-6 grid gap-4 p-4">
      <div className="flex items-center gap-2">
        <Link2 className="h-5 w-5 text-field-700" aria-hidden />
        <div>
          <h2 className="font-display font-bold text-xl text-ink">Assign a tool to multiple artisans</h2>
          <p className="text-sm text-ink-muted">
            Map one documented tool to several artisans — across the same or different crafts — instead of re-entering the
            same tool for each craft.
          </p>
        </div>
      </div>

      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {message ? <div className="rounded-md border border-green-200 bg-green-50 px-3 py-2 text-sm text-green-700">{message}</div> : null}
      <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />

      <div className="grid gap-3 md:grid-cols-3">
        <Field label="Tool">
          <Dropdown
            value={toolId}
            onChange={(next) => {
              setToolId(next);
              const tool = tools.find((candidate) => candidate.id === next);
              if (!tool) return;
              // Pruning first stops the banner claiming the tool it offered — the researcher has
              // just overruled it — and remembering then banks the one they chose, with the artisan
              // and craft that tool was documented under so nothing above it goes stale.
              pruneCarried("tool");
              carry.remember({
                artisanId: tool.artisanId,
                artisanName: tool.artisanName,
                place: tool.place,
                craftId: tool.craftId,
                craftName: tool.craftName,
                toolId: tool.id,
                toolName: tool.toolkitName
              });
            }}
            placeholder="Select a tool"
            // Never the primitive's literal "No options", which on a read that has not landed is the
            // claim that this repository documents no tools.
            emptyLabel={emptyLabelFor("tools", "No tools have been documented yet")}
            /*
              All three pickers in this section pass `searchable`, and none of them may be left to
              the option count: tools, crafts and artisans are all records, all three are capped
              (see the `CappedListNotice` under each), and the tool list is the longest in the app
              after the dial codes at 74 rows of `toolkitName — craftName · artisanName`. Reading
              that by scrolling is not a thing anybody does twice. Leaving it to the count would also
              make the three disagree with one another on a young deployment — 74 tools searchable,
              6 crafts not — for one task done in one glance.
            */
            searchable
            options={tools.map((tool) => ({ value: tool.id, label: `${tool.toolkitName} — ${tool.craftName} · ${tool.artisanName}` }))}
          />
          {/*
            §3.5's CACHED-AND-STALE SENTENCE, above the cut and never instead of it. They report two
            different things — one is how old this copy is, the other is how much of the corpus it
            holds — and a reader hunting a toolkit that is not on the list needs both to know which
            of the two explains it.
          */}
          {toolCachedAt && tools.length > 0 ? (
            <p className="mt-1 text-xs text-ink-500">{cachedListLine(tools.length, "tools", formatDate(toolCachedAt))}</p>
          ) : null}
          <CappedListNotice cuts={[cuts.tools]} />
        </Field>
        <Field label="Crafts">
          <MultiSelectDropdown
            values={craftIds}
            onChange={setCraftIds}
            placeholder="Select crafts"
            emptyLabel={emptyLabelFor("crafts", "No crafts have been recorded yet")}
            searchable
            options={crafts.map((craft) => ({ value: craft.id, label: craft.name }))}
          />
          <CappedListNotice cuts={[cuts.crafts]} />
        </Field>
        <Field label="Artisans of selected crafts">
          <MultiSelectDropdown
            values={artisanIds}
            onChange={(next) => {
              setArtisanIds(next);
              // An explicit pick retires the banner and re-points the remembered context at the
              // single artisan they settled on (a multi-select of many is nobody's "context").
              const artisan = next.length === 1 ? artisans.find((a) => a.id === next[0]) : undefined;
              if (artisan) {
                carry.remember({ artisanId: artisan.id, artisanName: artisan.name, place: artisan.place, craftId: artisan.craftId }, { explicit: true });
              } else {
                carry.retire();
              }
            }}
            placeholder={craftIds.length ? "Select artisans" : "Select crafts first"}
            /* "No artisans for these crafts" is a claim about the register, narrowed to the ticked
               crafts, and it may only be made off a read that answered -- see `emptyLabelFor`. */
            emptyLabel={emptyLabelFor(
              "artisans",
              craftIds.length ? "No artisans for these crafts" : "Select crafts first"
            )}
            disabled={craftIds.length === 0}
            searchable
            options={artisansForCrafts.map((artisan) => ({ value: artisan.id, label: `${artisan.name} · ${artisan.place}` }))}
          />
          {/* Rendered only once crafts are ticked: with none ticked the control says "Select crafts
              first" and is disabled, and a truncation notice over a control nobody can open is
              noise. */}
          {craftIds.length ? <CappedListNotice cuts={[cuts.artisans]} /> : null}
        </Field>
      </div>

      <div className="flex items-center gap-3">
        <button type="button" className="field-button" onClick={assign} disabled={busy || !toolId || artisanIds.length === 0}>
          {busy ? "Assigning…" : `Assign tool to ${artisanIds.length || ""} artisan${artisanIds.length === 1 ? "" : "s"}`.trim()}
        </button>
      </div>

      {toolId ? (
        <div className="grid gap-2">
          <div className="field-label">Currently assigned to</div>
          {assigned.length === 0 ? (
            <p className="text-sm text-ink-muted">Not assigned to any additional artisans yet.</p>
          ) : (
            <ul className="flex flex-wrap gap-2">
              {assigned.map((artisan) => (
                <li key={artisan.id} className="inline-flex items-center gap-2 rounded-full border border-line-200 bg-field-100 px-3 py-1 text-sm text-ink">
                  <span>
                    {artisan.name}
                    {artisan.craft?.name ? ` · ${artisan.craft.name}` : ""}
                  </span>
                  <button type="button" aria-label={`Remove ${artisan.name}`} className="text-ink-muted hover:text-red-700" onClick={() => unassign(artisan.id)}>
                    <X className="h-3.5 w-3.5" aria-hidden />
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </section>
  );
}
