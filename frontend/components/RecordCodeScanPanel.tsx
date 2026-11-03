"use client";

import Link from "next/link";

import { ScanPanel } from "@/components/scan/ScanSheet";
import { SCAN_COPY } from "@/lib/scanDispatch";
import type { WorkshopCodeHit } from "@/lib/workshopCodeLookup";
import { workshopRecordTypeLabel, type WorkshopRecordType } from "@/lib/workshopCodes";

export function RecordCodeScanPanel() {
  return (
    <div className="mb-5 grid gap-3">
      <ScanPanel heading={SCAN_COPY.scanACode} />
    </div>
  );
}

export function ResolvedRecordRow({ recordType, hit }: { recordType: WorkshopRecordType; hit: WorkshopCodeHit }) {
  return (
    <div className="panel flex flex-wrap items-center justify-between gap-3 p-3">
      <div className="min-w-0">
        <p className="text-xs uppercase tracking-wide text-ink-500">{workshopRecordTypeLabel(recordType)}</p>
        <p className="truncate text-sm font-medium text-ink-900">{hit.label}</p>
        {hit.detail ? <p className="truncate text-xs text-ink-muted">{hit.detail}</p> : null}
      </div>
      {hit.external ? (
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
