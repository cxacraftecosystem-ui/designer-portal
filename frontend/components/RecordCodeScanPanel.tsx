"use client";

/**
 * Scan any record's code and open the record — the repository-wide panel on `/search`.
 *
 * It DELEGATES to the top-bar scanner's panel (`components/scan/ScanSheet.tsx` over
 * `lib/scanDispatch.ts`), so `/search`, `/scan` and the header button cannot drift: one dispatcher
 * decides what a code is, and one card offers the one press that acts on it. It reports, then offers
 * to open; it never navigates by itself. A refusal never says whether a record exists — see
 * `lib/workshopCodeLookup.ts`.
 */

import Link from "next/link";

import { ScanPanel } from "@/components/scan/ScanSheet";
import type { WorkshopCodeHit } from "@/lib/workshopCodeLookup";
import { workshopRecordTypeLabel, type WorkshopRecordType } from "@/lib/workshopCodes";

export function RecordCodeScanPanel() {
  return (
    <div className="mb-5 grid gap-3">
      <ScanPanel />
    </div>
  );
}

/**
 * The record a scan resolved to, with the one press that opens it.
 *
 * Used by the workshop's Cards & tags page, which resolves an artisan out of its own roster before
 * it ever reaches the network.
 */
export function ResolvedRecordRow({ recordType, hit }: { recordType: WorkshopRecordType; hit: WorkshopCodeHit }) {
  return (
    <div className="panel flex flex-wrap items-center justify-between gap-3 p-3">
      <div className="min-w-0">
        <p className="text-xs uppercase tracking-wide text-ink-500">{workshopRecordTypeLabel(recordType)}</p>
        <p className="truncate text-sm font-medium text-ink-900">{hit.label}</p>
        {hit.detail ? <p className="truncate text-xs text-ink-muted">{hit.detail}</p> : null}
      </div>
      {hit.external ? (
        // A stored object rather than a page in this app — a media file's own bytes.
        <a className="field-button" href={hit.href} target="_blank" rel="noreferrer">
          Open file
        </a>
      ) : (
        <Link className="field-button" href={hit.href}>
          Open
        </Link>
      )}
    </div>
  );
}
