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
 * ── IT WORKS WITH NO CONNECTION, WHICH IS THE POINT OF THE WHOLE FEATURE ─────────────────────────
 *
 * The list of workshops to choose from is asked of the server when the server can be reached, and
 * falls back to the workshops THIS DEVICE has already seen (drafts carrying a `remoteId`) when it
 * cannot. A designer in a cluster with one bar of signal, holding a stranded workshop, must not be
 * told to come back when they have wifi — that is the exact situation this app exists to work in.
 * The fallback is honest about being a fallback: it says the list is partial.
 *
 * ── WHY IT IS NOT A `useConfirm` PROMPT ─────────────────────────────────────────────────────────
 *
 * The designer has to CHOOSE, and choosing wrongly puts a fortnight of fieldwork into another
 * cluster's record. So it is a picker with the consequence written above it, and the confirm button
 * names the workshop rather than saying "OK".
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import { FolderInput } from "lucide-react";

import { FieldDialog } from "@/components/dialogs";
import { Dropdown } from "@/components/ui/Dropdown";
import { listDesignWorkshops, type DwSummary } from "@/lib/designWorkshops";
import { adoptDraftIntoWorkshop, type DwDraft } from "@/lib/designWorkshopStore";

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
  const [partial, setPartial] = useState(false);
  const [chosen, setChosen] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * The workshops this device already knows about, from the draft store alone.
   *
   * Used as the offline fallback AND as the immediate first paint, so the picker is never empty
   * while the network is being tried. A draft with a `remoteId` is a workshop the server owns and
   * this browser has opened at least once.
   */
  const known = useMemo<Candidate[]>(
    () =>
      drafts
        .filter((row): row is DwDraft & { remoteId: string } => typeof row.remoteId === "string")
        .map((row) => ({ id: row.remoteId, label: labelFor(row.header) })),
    [drafts]
  );

  const load = useCallback(async () => {
    setError(null);
    try {
      // A generous page: a designer picking between their own workshops should not have to
      // paginate, and the server scopes a non-admin to what they may open regardless.
      const found = await listDesignWorkshops({ page: 1, pageSize: 100 });
      const rows = found.items.map((row) => ({ id: row.id, label: labelFor(row) }));
      // MERGED WITH WHAT THE DEVICE KNOWS, not replaced by the server's answer. A workshop this
      // browser has open locally but which fell off page one of the server's list would otherwise
      // disappear from the picker the moment the network answered — the list would get WORSE when
      // the connection got better, which nobody would believe was deliberate.
      const byId = new Map<string, Candidate>([...known, ...rows].map((row) => [row.id, row]));
      setCandidates([...byId.values()]);
      setPartial(false);
    } catch {
      // The network is the thing that failed, and it is the thing this feature is least allowed to
      // depend on. Fall back to what is on the device and SAY that the list is partial, rather than
      // presenting a short list as though it were all of them.
      setCandidates(known);
      setPartial(true);
    }
  }, [known]);

  useEffect(() => {
    if (!open) return;
    setChosen("");
    setBusy(false);
    setError(null);
    setCandidates(known.length ? known : null);
    void load();
  }, [open, load, known]);

  const options = useMemo(
    () => [
      { value: "", label: "Choose the workshop this belongs to…" },
      ...(candidates ?? []).map((row) => ({ value: row.id, label: row.label }))
    ],
    [candidates]
  );

  const chosenLabel = (candidates ?? []).find((row) => row.id === chosen)?.label ?? "";

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
          <button type="button" className="field-button" onClick={move} disabled={busy || !chosen}>
            {busy ? "Moving…" : chosenLabel ? `Move into ${chosenLabel}` : "Move"}
          </button>
        </>
      }
    >
      <div className="grid gap-3 text-sm text-ink-700">
        <p className="leading-6">
          Starting a new design workshop is now done by an admin or the master admin. Ask an admin to create the workshop
          for this cluster and give you access, then choose it here — everything saved on this device
          {stageCount ? ` (${stageCount} stage${stageCount === 1 ? "" : "s"}, with their photographs and recordings)` : ""} is
          sent into it on the next sync. Nothing is deleted by this and nothing is retyped.
        </p>
        <Dropdown
          value={chosen}
          onChange={setChosen}
          options={options}
          ariaLabel="The workshop to move this into"
          searchable
        />
        {partial ? (
          <p className="rounded-md border border-amber-500/30 bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-900">
            There is no connection, so this lists only the workshops already open on this device. If the one you want is
            not here, move it when you next have signal — nothing on this device expires and nothing will be lost in the
            meantime.
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
