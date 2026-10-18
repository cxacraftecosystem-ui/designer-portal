"use client";

/**
 * "Import artisans from a spreadsheet" on stage 3 (owner decision C11, 2026-10-17).
 *
 * The officers' import, opened to the workshop's own designers: download the pro-forma (already
 * naming this workshop), fill it in, upload it. Same delete-after-reading rule, same ledger, same
 * report — `ImportReport` is the officers' component, reused. Closed until asked for: most stage-3
 * visits are not imports. After an import the participant rows exist on the server, so the panel
 * offers a reload rather than pretending the form beside it already shows them.
 */

import { useCallback, useEffect, useState } from "react";
import { ChevronDown, FileSpreadsheet } from "lucide-react";

import { ImportReport } from "@/app/(protected)/officers/ImportReport";
import {
  ARTISAN_ACCEPT,
  artisanUploadRefusal,
  type ArtisanImportReport,
  type DwArtisanImport
} from "@/app/(protected)/officers/oversight";
import { DropCard } from "@/components/sketches/upload/DropCard";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { ApiError } from "@/lib/api";
import { downloadRosterProForma, listRosterImports, uploadRoster } from "@/lib/designWorkshopRoster";
import { saveBlobToDisk } from "@/lib/fileDownload";

function sentence(error: unknown, fallback: string): string {
  if (error instanceof ApiError && error.message) return error.message;
  return fallback;
}

function when(iso: string | null): string {
  if (!iso) return "";
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? ""
    : date.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

export function RosterImportPanel({
  workshopId,
  disabled,
  disabledReason
}: {
  /** The SERVER id. A workshop only this device holds has nothing to import into yet. */
  workshopId: string;
  disabled?: boolean;
  disabledReason?: string | null;
}) {
  const [open, setOpen] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [report, setReport] = useState<ArtisanImportReport | null>(null);
  const [history, setHistory] = useState<DwArtisanImport[] | null>(null);

  const reloadHistory = useCallback(() => {
    listRosterImports(workshopId)
      .then((list) => setHistory(list.imports))
      .catch(() => setHistory([]));
  }, [workshopId]);

  useEffect(() => {
    if (open && history === null) reloadHistory();
  }, [open, history, reloadHistory]);

  async function download() {
    setDownloading(true);
    setRefusal(null);
    try {
      const file = await downloadRosterProForma(workshopId);
      saveBlobToDisk(file.blob, file.fileName);
    } catch (error) {
      setRefusal(sentence(error, "Couldn't download the pro-forma. Try again."));
    } finally {
      setDownloading(false);
    }
  }

  async function upload(files: File[]) {
    const [file] = files;
    if (!file || disabled) return;
    setUploading(true);
    setRefusal(null);
    setReport(null);
    try {
      setReport(await uploadRoster(workshopId, file));
      reloadHistory();
    } catch (error) {
      setRefusal(sentence(error, "Couldn't import that list. Try again."));
    } finally {
      setUploading(false);
    }
  }

  return (
    <section className="panel p-4">
      <button
        type="button"
        className="flex w-full items-center gap-3 rounded-md text-left"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <span className="grid h-9 w-9 place-items-center rounded-lg bg-field-200 text-field-600" aria-hidden>
          <FileSpreadsheet className="h-4 w-4" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block font-display text-base font-bold tracking-tight text-ink-900">
            Import artisans from a spreadsheet
          </span>
          <span className="block text-xs text-ink-500">Fill in the pro-forma and upload it.</span>
        </span>
        <ChevronDown
          className={`h-4 w-4 text-ink-500 transition-transform ${open ? "rotate-180" : ""}`}
          aria-hidden
        />
      </button>

      {open ? (
        <div className="mt-4 grid gap-3">
          {disabledReason ? <p className="text-xs text-ink-500">{disabledReason}</p> : null}
          <p className="rounded-md border border-amber-100 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
            Contains Aadhaar numbers. Don&rsquo;t email or share the filled file; delete it after uploading.
            The file isn&rsquo;t stored.
          </p>
          <ActionGroup>
            <button type="button" className="field-button-secondary" disabled={downloading} onClick={download}>
              {downloading ? "Preparing…" : "Download the pro-forma"}
            </button>
          </ActionGroup>
          {refusal ? (
            <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{refusal}</div>
          ) : null}
          <DropCard
            label="Filled-in artisan list"
            buttonLabel="Choose the filled-in pro-forma"
            accept={ARTISAN_ACCEPT}
            acceptSentence="One Excel workbook (.xlsx), up to 4 MB."
            disabled={uploading || Boolean(disabled)}
            validate={artisanUploadRefusal}
            onFiles={upload}
          >
            {uploading ? <p className="text-sm text-ink-700">Reading the list…</p> : null}
          </DropCard>
          {report ? (
            <>
              <ImportReport report={report} />
              <ActionGroup>
                <button type="button" className="field-button" onClick={() => window.location.reload()}>
                  Reload this stage
                </button>
              </ActionGroup>
            </>
          ) : null}
          <div>
            <h3 className="font-display text-sm font-bold tracking-tight text-ink-900">Earlier uploads</h3>
            {history === null ? (
              <p className="mt-1 text-sm text-ink-700">Loading…</p>
            ) : history.length === 0 ? (
              <p className="mt-1 text-sm text-ink-700">No uploads yet.</p>
            ) : (
              <ul className="mt-1 divide-y divide-line-200 rounded-md border border-line-200">
                {history.map((entry) => (
                  <li key={entry.id} className="px-3 py-2 text-sm">
                    <span className="block truncate font-medium text-ink-900">
                      {entry.sourceFilename ?? "An artisan list"}
                    </span>
                    <span className="block text-xs text-ink-500">
                      {when(entry.createdAt)}
                      {entry.uploadedBy ? ` · ${entry.uploadedBy}` : ""} · {entry.rowsRead} rows read ·{" "}
                      {entry.artisansCreated} created · {entry.artisansLinked} linked · {entry.rowsRefused} not added
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      ) : null}
    </section>
  );
}

/** The stage this panel sits on — stage 3, where the participant roster is built. */
export const PARTICIPANTS_STAGE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING";
