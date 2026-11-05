"use client";

import { Link2, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";

import { Field } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext, type CarryScopeState } from "@/components/forms/CarryContextBanner";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { LIST_PAGE_CEILING, listCut, mergeById, type ListCut } from "@/components/data/cappedList";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import {
  artisanToOption,
  craftRosterCacheKey,
  optionToArtisan,
  optionToTool,
  toolToOption,
  useCatalogueCrafts
} from "@/components/forms/recordPickers";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { apiFetch } from "@/lib/api";
import { listAllPages } from "@/lib/listAllPages";
import { countNoun, formatDate } from "@/lib/format";
import { loadCachedRegister } from "@/lib/referenceCache";
import {
  cachedListLine,
  deviceLooksOffline,
  workshopEmptyLabel,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";
import type { Artisan, ToolDocumentation } from "@/lib/types";
import { ActionGroup } from "@/components/ui/ActionGroup";

export function ToolAssignmentSection() {
  const [tools, setTools] = useState<ToolDocumentation[]>([]);
  const { crafts, state: craftState } = useCatalogueCrafts();
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [toolCachedAt, setToolCachedAt] = useState<string | null>(null);
  const [toolId, setToolId] = useState("");
  const [craftIds, setCraftIds] = useState<string[]>([]);
  const [artisanIds, setArtisanIds] = useState<string[]>([]);
  const [assigned, setAssigned] = useState<Artisan[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [registerState, setRegisterState] = useState<CarryScopeState>("pending");
  const referenceState: CarryScopeState =
    registerState === "unavailable" || craftState === "unavailable"
      ? "unavailable"
      : registerState === "pending" || craftState === "pending"
        ? "pending"
        : "loaded";
  const [cuts, setCuts] = useState<{ tools: ListCut | null; crafts: ListCut | null; artisans: ListCut | null }>({
    tools: null,
    crafts: null,
    artisans: null
  });
  const [craftArtisanCut, setCraftArtisanCut] = useState<ListCut | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const [toolOutcome, artisanOutcome] = await Promise.all([
        loadCachedRegister<ToolDocumentation>({
          model: "tool",
          decode: optionToTool,
          encode: toolToOption,
          fetch: async () => {
            const page = await listAllPages<ToolDocumentation>("/tools", { pageSize: LIST_PAGE_CEILING });
            if (!cancelled) setCuts((previous) => ({ ...previous, tools: listCut(page, "tools") }));
            return page.items;
          },
          onList: (rows, cachedAt) => {
            if (cancelled) return;
            setTools(rows);
            setToolCachedAt(cachedAt);
          }
        }),
        loadCachedRegister<Artisan>({
          model: "artisan",
          decode: optionToArtisan,
          encode: artisanToOption,
          fetch: async () => {
            const page = await listAllPages<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING });
            if (!cancelled) setCuts((previous) => ({ ...previous, artisans: listCut(page, "artisans") }));
            return page.items;
          },
          onList: (rows, cachedAt) => {
            if (!cancelled) setArtisans((previous) => (cachedAt ? mergeById(previous, rows) : mergeById(rows, previous)));
          }
        })
      ]);
      if (cancelled) return;
      const missing = [toolOutcome, artisanOutcome].some((outcome) => outcome.source === "none");
      setRegisterState(missing ? "unavailable" : "loaded");
      if (missing) {
        setError("Couldn't load the options. Check your connection and reload.");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const craftRosterKey = useMemo(() => [...craftIds].sort().join(","), [craftIds]);

  useEffect(() => {
    setCraftArtisanCut(null);
    if (!craftRosterKey) return;
    const ids = craftRosterKey.split(",");
    let cancelled = false;
    void loadCachedRegister<Artisan>({
      model: "artisan",
      filterValue: craftRosterCacheKey(craftRosterKey),
      decode: optionToArtisan,
      encode: artisanToOption,
      fetch: async () => {
        const page = await listAllPages<Artisan>("/artisans", {
          craftIds: ids.join(","),
          craftId: ids.length === 1 ? ids[0] : undefined,
          pageSize: LIST_PAGE_CEILING
        });
        if (!cancelled) setCraftArtisanCut(listCut(page, ids.length > 1 ? "artisans of these crafts" : "artisans of this craft"));
        return page.items;
      },
      onList: (rows, cachedAt) => {
        if (!cancelled) setArtisans((previous) => (cachedAt ? mergeById(previous, rows) : mergeById(rows, previous)));
      }
    });
    return () => {
      cancelled = true;
    };
  }, [craftRosterKey]);

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
    cached: true,
    online: referenceOnline,
    reassurance: "Nothing you picked is lost. The message above says what happened."
  });
  const emptyLabelFor = (noun: string, own: string) =>
    referenceList.kind === "ok" ? own : workshopEmptyLabel(referenceList, voiceFor(noun));

  const carry = useCarryContext({
    scopes: [
      carryScope("artisan", referenceState, artisans),
      carryScope("craft", referenceState, crafts),
      carryScope("tool", referenceState, tools)
    ],
    applies: ["craft", "artisan", "tool"],
    onApply: (context) => {
      if (context.craftId) setCraftIds([context.craftId]);
      if (context.artisanId) setArtisanIds([context.artisanId]);
      if (context.toolId) setToolId(context.toolId);
    }
  });
  const pruneCarried = carry.prune;
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
      setMessage(`This tool is now assigned to ${countNoun(updated.length, "artisan")}.`);
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
        <Link2 className="h-5 w-5 text-field-700 dark:text-purple-300" aria-hidden />
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
              pruneCarried("tool");
              carry.remember({
                artisanId: tool.artisanId,
                artisanName: tool.artisanName,
                place: tool.place,
                craftId: tool.craftId,
                craftName: crafts.find((craft) => craft.id === tool.craftId)?.name ?? tool.craftName,
                toolId: tool.id,
                toolName: tool.toolkitName
              });
            }}
            placeholder="Select a tool"
            emptyLabel={emptyLabelFor("tools", "No tools have been documented yet")}
            searchable
            options={tools.map((tool) => ({ value: tool.id, label: `${tool.toolkitName} — ${tool.craftName} · ${tool.artisanName}` }))}
          />
          {toolCachedAt && tools.length > 0 ? (
            <p className="mt-1 text-xs text-ink-500">{cachedListLine(tools.length, "tools", formatDate(toolCachedAt))}</p>
          ) : null}
          <CappedListNotice cuts={[cuts.tools]} />
        </Field>
        <FieldBlock label="Crafts">
          <CraftSelect multiple ariaLabel="Crafts" value={craftIds} onChange={(ids) => setCraftIds(ids)} />
        </FieldBlock>
        <Field label="Artisans of selected crafts">
          <MultiSelectDropdown
            values={artisanIds}
            onChange={(next) => {
              setArtisanIds(next);
              const artisan = next.length === 1 ? artisans.find((a) => a.id === next[0]) : undefined;
              if (artisan) {
                carry.remember({ artisanId: artisan.id, artisanName: artisan.name, place: artisan.place, craftId: artisan.craftId }, { explicit: true });
              } else {
                carry.retire();
              }
            }}
            placeholder={craftIds.length ? "Select artisans" : "Select crafts first"}
            emptyLabel={emptyLabelFor(
              "artisans",
              craftIds.length ? "No artisans for these crafts" : "Select crafts first"
            )}
            disabled={craftIds.length === 0}
            searchable
            options={artisansForCrafts.map((artisan) => ({ value: artisan.id, label: `${artisan.name} · ${artisan.place}` }))}
          />
          {craftIds.length ? <CappedListNotice cuts={[craftArtisanCut ?? cuts.artisans]} /> : null}
        </Field>
      </div>

      <ActionGroup>
        <button type="button" className="field-button" onClick={assign} disabled={busy || !toolId || artisanIds.length === 0}>
          {busy ? "Assigning…" : `Assign tool to ${artisanIds.length || ""} artisan${artisanIds.length === 1 ? "" : "s"}`.trim()}
        </button>
      </ActionGroup>

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
