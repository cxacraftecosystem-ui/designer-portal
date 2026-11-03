"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { FolderInput } from "lucide-react";

import { FieldDialog } from "@/components/dialogs";
import { SearchInput } from "@/components/SearchInput";
import { Dropdown } from "@/components/ui/Dropdown";
import { RENDER_CAP } from "@/components/ui/selectFilter";
import { listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
import { adoptDraftIntoWorkshop, type DwDraft } from "@/lib/designWorkshopStore";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

const SEARCH_DEBOUNCE_MS = 300;

export type AdoptLocalDraftDialogProps = {
  open: boolean;
  onClose: () => void;
  draft: DwDraft | null;
  drafts: readonly DwDraft[];
  onAdopted: (remoteId: string) => void;
};

type Candidate = { id: string; label: string };

function labelFor(row: Pick<DwSummary, "title" | "craftName" | "clusterName" | "district">): string {
  const place = [row.clusterName, row.district].filter(Boolean).join(", ");
  const parts = [workshopDisplayTitle(row), row.craftName, place].filter(Boolean);
  return parts.join(" · ");
}

export function AdoptLocalDraftDialog({ open, onClose, draft, drafts, onAdopted }: AdoptLocalDraftDialogProps) {
  const [candidates, setCandidates] = useState<Candidate[] | null>(null);
  const [partial, setPartial] = useState(false);
  const [verified, setVerified] = useState(false);
  const [total, setTotal] = useState<number | null>(null);
  const [search, setSearch] = useState("");
  const [searching, setSearching] = useState(false);
  const [chosen, setChosen] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const generation = useRef(0);

  const known = useMemo<Candidate[]>(
    () =>
      drafts
        .filter((row): row is DwDraft & { remoteId: string } => typeof row.remoteId === "string")
        .map((row) => ({ id: row.remoteId, label: labelFor(row.header) })),
    [drafts]
  );

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
        const found = await listDesignWorkshops({ page: 1, pageSize: RENDER_CAP, search: term || undefined });
        if (generation.current !== mine) return;
        setCandidates(found.items.map((row) => ({ id: row.id, label: labelFor(row) })));
        setTotal(found.total);
        setPartial(false);
        setVerified(true);
      } catch {
        if (generation.current !== mine) return;
        setCandidates(knownRef.current);
        setTotal(null);
        setPartial(true);
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
    setCandidates(knownRef.current.length ? knownRef.current : null);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const term = search.trim();
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

  const listNotice =
    searching && !verified
      ?
        "Checking which workshops are open to you…"
      : searching
        ? "Searching…"
        : partial
          ? ""
          : total !== null && shown < total
            ? `Showing ${shown} of ${total} workshops open to you. Search above to find the rest.`
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
        setError(
          "Couldn't move it; nothing was lost. Try again, and don't clear browser data."
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

  const title = workshopDisplayTitle(draft.header);
  const stageCount = Object.keys(draft.stages ?? {}).length;
  const nothingToMoveInto = verified && !partial && !searching && !searchTerm && shown === 0;

  return (
    <FieldDialog
      open={open}
      onClose={onClose}
      busy={busy}
      title="Move this workshop into an existing one"
      description={`“${title}” is only on this device.`}
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
        <p className="leading-6">
          Everything saved on this device
          {stageCount ? ` (${stageCount} stage${stageCount === 1 ? "" : "s"}, with their photographs and recordings)` : ""} moves
          into the workshop you choose on the next sync. Nothing is deleted: answers already in that workshop are kept, and
          where both have the same box, this device’s answer wins.
        </p>

        {nothingToMoveInto ? (
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
            No design workshop is open to you yet. Ask an admin to add you to one — your work stays safe here.
          </p>
        ) : (
          <>
            <SearchInput
              onChange={setSearch}
              ariaLabel="Search design workshops"
              placeholder="Search by title, craft, cluster or workshop code"
              value={search}
            />
            {listNotice ? <p className="text-xs leading-5 text-ink-500">{listNotice}</p> : null}
            <Dropdown
              value={chosen}
              onChange={setChosen}
              options={options}
              ariaLabel="The workshop to move this into"
              searchable={false}
              capHint="Use the search box above to find the rest."
            />
          </>
        )}

        {partial ? (
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
            Offline — showing workshops on this device; we can&rsquo;t check whether you&rsquo;re still on them.
            You can move it once you&rsquo;re back online.
          </p>
        ) : null}

        <p className="text-xs leading-5 text-ink-500">
          Choose carefully. It can only be done once — after the move, the stages belong to the workshop you pick.
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
