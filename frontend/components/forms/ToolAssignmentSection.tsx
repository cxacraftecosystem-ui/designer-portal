"use client";

import { Link2, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import { Field } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext, type CarryScopeState } from "@/components/forms/CarryContextBanner";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { LIST_PAGE_CEILING, listCut, type ListCut } from "@/components/data/cappedList";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import { apiFetch, listResource } from "@/lib/api";
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

  useEffect(() => {
    (async () => {
      const [toolPage, craftPage, artisanPage] = await Promise.all([
        listResource<ToolDocumentation>("/tools", { pageSize: LIST_PAGE_CEILING }),
        listResource<Craft>("/crafts", { pageSize: LIST_PAGE_CEILING }),
        listResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING })
      ]);
      setTools(toolPage.items);
      setCrafts(craftPage.items);
      setArtisans(artisanPage.items);
      setCuts({
        tools: listCut(toolPage, "tools"),
        crafts: listCut(craftPage, "crafts"),
        artisans: listCut(artisanPage, "artisans")
      });
      setReferenceState("loaded");
    })().catch((err) => {
      setReferenceState("unavailable");
      setError(err instanceof Error ? err.message : "Failed to load options");
    });
  }, []);

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
          <CappedListNotice cuts={[cuts.tools]} />
        </Field>
        <Field label="Crafts">
          <MultiSelectDropdown
            values={craftIds}
            onChange={setCraftIds}
            placeholder="Select crafts"
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
            emptyLabel={craftIds.length ? "No artisans for these crafts" : "Select crafts first"}
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
