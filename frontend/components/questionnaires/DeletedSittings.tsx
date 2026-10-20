"use client";

import { useCallback, useEffect, useState } from "react";
import { Loader2, Undo2 } from "lucide-react";

import { RowActions, rowAction } from "@/components/RowActions";
import { readableError } from "@/components/review/reviewErrors";
import { formatDateTime } from "@/lib/format";
import { listDeletedEntries, restoreEntry, type QFormDeletedEntry } from "@/lib/questionnaireForms";

/**
 * The sittings deleted from one questionnaire, each with Restore. Mounted by the questionnaire page
 * in admin view only; the server answers 403 to anyone but a content admin. Draws nothing until it
 * knows there is something deleted, so a form with no deleted sittings gains no empty section.
 */
export function DeletedSittings({
  questionnaireId,
  refreshToken,
  onRestored
}: {
  questionnaireId: string;
  /** Bumped by the page after it deletes a sitting, so the new row appears here. */
  refreshToken: number;
  onRestored: () => void;
}) {
  const [rows, setRows] = useState<QFormDeletedEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setRows(await listDeletedEntries(questionnaireId));
      setError(null);
    } catch (err) {
      setError(readableError(err, "The deleted sittings could not be listed."));
    }
  }, [questionnaireId]);

  useEffect(() => {
    void load();
  }, [load, refreshToken]);

  async function restore(row: QFormDeletedEntry) {
    setBusyId(row.id);
    setError(null);
    try {
      await restoreEntry(questionnaireId, row.id);
      onRestored();
      await load();
    } catch (err) {
      setError(readableError(err, "Those answers could not be restored."));
    } finally {
      setBusyId(null);
    }
  }

  if (!error && (!rows || rows.length === 0)) return null;

  return (
    <section className="panel mt-6 p-4" aria-labelledby="deleted-sittings-heading">
      <h2 id="deleted-sittings-heading" className="font-display text-lg font-bold text-ink-900">
        Deleted answers
      </h2>
      {error ? (
        <p className="mt-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
      ) : null}
      {rows && rows.length > 0 ? (
        <ul className="mt-3 grid gap-2">
          {rows.map((row) => (
            <li key={row.id} className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 p-3">
              <div className="min-w-0">
                <p className="font-medium text-ink-900">{row.respondentName || row.title}</p>
                <p className="text-xs text-ink-500">
                  Deleted {formatDateTime(row.deletedAt)}
                  {row.deletedByName ? ` by ${row.deletedByName}` : ""}
                </p>
              </div>
              <RowActions>
                <button type="button" className={rowAction("edit")} disabled={busyId !== null} onClick={() => void restore(row)}>
                  {busyId === row.id ? (
                    <>
                      <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                      Restoring…
                    </>
                  ) : (
                    <>
                      <Undo2 className="h-4 w-4" aria-hidden />
                      Restore
                    </>
                  )}
                </button>
              </RowActions>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}
