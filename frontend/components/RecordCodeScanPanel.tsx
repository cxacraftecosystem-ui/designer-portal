"use client";

/**
 * Scan any record's code and open the record — the repository-wide half of the feature.
 *
 * ── WHY IT IS NOT ONLY ON THE WORKSHOP'S CARDS & TAGS PAGE ───────────────────────────────────
 *
 * That page's scanner is scoped to one design workshop: it answers out of that workshop's local
 * draft first, because a prototype tag in a village with no signal has nowhere else to be answered
 * from. A tool tag, a product tag or an artisan card is not about one workshop — it is about the
 * repository — and asking a designer to open some workshop before scanning a tool would be asking
 * them to name the very thing they scanned the tag to avoid naming.
 *
 * IT LIVES ON `/search` BECAUSE THAT IS WHERE "FIND THE RECORD FOR THIS THING" ALREADY LIVES. A
 * scan is a search whose query happens to be exact, so it belongs beside the search box rather than
 * behind a new destination in the navigation — and `/search` is already in both clients' menus, so
 * no new entry has to be invented on one surface and mirrored on the other.
 *
 * ── IT REPORTS, THEN OFFERS TO OPEN. IT DOES NOT NAVIGATE BY ITSELF ──────────────────────────
 *
 * A scan that jumped straight to a record would leave nothing on screen to check, and the one
 * failure this whole feature exists to remove is work attached to the wrong record. So the panel
 * names what it found, and opening is one deliberate press on a real link — which is also what makes
 * the result reachable with a keyboard and readable by a screen reader after the announcement has
 * gone.
 *
 * ── A REFUSAL SAYS NOTHING ABOUT WHETHER THE RECORD EXISTS ───────────────────────────────────
 *
 * `lib/workshopCodeLookup.ts` flattens every answer the API gives into one sentence, on purpose;
 * read its header before adding a branch here.
 */

import { useCallback, useState } from "react";
import Link from "next/link";

import { WorkshopCodeScanner, type ScanResolution } from "@/components/designworkshop/WorkshopCodeScanner";
import { lookUpWorkshopCode, type WorkshopCodeHit } from "@/lib/workshopCodeLookup";
import { workshopRecordTypeLabel, type WorkshopCodeRef, type WorkshopRecordType } from "@/lib/workshopCodes";

export function RecordCodeScanPanel() {
  /** The last reference that resolved, and what it resolved to. Cleared by the next attempt. */
  const [found, setFound] = useState<{ ref: WorkshopCodeRef; hit: WorkshopCodeHit } | null>(null);

  const resolve = useCallback(async (ref: WorkshopCodeRef): Promise<ScanResolution> => {
    // Cleared BEFORE the request, not after it: the seconds a lookup takes are exactly when the
    // previous record's "Open" button would otherwise still be sitting under the new scan's spinner,
    // one press away from opening the wrong record.
    setFound(null);
    const answer = await lookUpWorkshopCode(ref);
    if (!answer.ok) return { ok: false, message: answer.message };
    setFound({ ref, hit: answer.hit });
    return { ok: true, label: answer.hit.label, detail: answer.hit.detail };
  }, []);

  return (
    <div className="mb-5 grid gap-3">
      <WorkshopCodeScanner
        resolve={resolve}
        description="A card, tag or code printed by this app, for any kind of record — an artisan, a craft, a workshop, a product, a process, a tool or an interview. Nothing about the record is inside the code: it holds a reference and a check digit, and nothing else."
      />
      {found ? <ResolvedRecordRow recordType={found.ref.recordType} hit={found.hit} /> : null}
    </div>
  );
}

/**
 * The record a scan resolved to, with the one press that opens it.
 *
 * Shared with the workshop's Cards & tags page rather than written twice: that page resolves an
 * artisan out of its own roster before it ever reaches the network, so it produces the same answer
 * by a different route, and two copies of this row are how one of them comes to link somewhere else.
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
        // A stored object rather than a page in this app — a media file's own bytes. New tab, and
        // `noreferrer` because the target is a signed storage URL and there is no reason for it to
        // learn which screen sent the reader.
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
