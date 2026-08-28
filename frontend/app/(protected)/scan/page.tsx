"use client";

/**
 * Scan a code — the destination whose whole job is reading a card, a tag or a screenshot.
 *
 * ── WHY THIS EXISTS WHEN `/search` ALREADY MOUNTS THE SAME PANEL ─────────────────────────────────
 *
 * `RecordCodeScanPanel`'s own header argues, correctly, that a scan is a search whose query happens
 * to be exact, and that putting it beside the search box meant "no new entry has to be invented on
 * one surface and mirrored on the other". That argument is about where the CONTROL belongs and it
 * still holds — the panel stays on `/search`, unchanged, and nothing here replaces it.
 *
 * What the argument did not weigh is the ROUTE. The owner's report, 2026-08-28: *"make scanning the
 * QR codes easier, it is buried underneath a lot of pages right now."* That is a fair reading of the
 * only path there was. To scan a tag a designer had to open the menu, find "Browse records" under
 * Browse — a row named after reading a LIST, which is not what they are doing — and then notice a
 * panel above the search box. On the handset it is worse, because Search is a menu-only destination
 * there (`EntryMode.onDashboard = false`) with no tile at all. Three deliberate steps, none of them
 * named after the thing in the designer's hand, to reach a control whose entire purpose is to save
 * them from typing.
 *
 * A DESTINATION IS THE CHEAPEST FIX AND IT TAKES NOTHING AWAY. Both scanners stay where they are;
 * this adds a door named after the action, in the menu and on the dashboard, so the path is one tap
 * from the screen the app opens on. `frontend/e2e/dashboard-tile-parity-unit.spec.ts` and Android's
 * `DashboardTileParityTest` hold the two grids to each other, so the tile could not be added here
 * without being added on the handset in the same change — which is the point.
 *
 * ── IT IS A PAGE AND NOT A DIALOG ───────────────────────────────────────────────────────────────
 *
 * A camera preview inside a modal is a camera preview that has to be torn down and rebuilt every
 * time somebody looks at what they just scanned, and the result of a scan is a record the designer
 * then wants to OPEN — which a dialog would have to close itself to allow. A page keeps the result
 * on screen, keeps the "Open" link reachable by keyboard, and lets a second scan follow the first
 * without any of it being rebuilt.
 *
 * ── NO GUARD, MATCHING `/search` ────────────────────────────────────────────────────────────────
 *
 * There is deliberately no `ROUTE_GUARDS` row. Every endpoint behind `lookUpWorkshopCode` takes a
 * signed-in caller and scopes its answer per viewer on the server, and `require_record` raises 404
 * — never 403 — for a record the caller may not have, precisely so a code cannot be used to
 * enumerate the repository. A client-side rule here would be a rule the API does not have, which is
 * the reason `permissions.ts` gives for `/search` having no row either.
 */

import { QrCode } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { RecordCodeScanPanel } from "@/components/RecordCodeScanPanel";

export default function ScanCodePage() {
  return (
    <>
      <PageHeader
        title="Scan a code"
        description="Point the camera at a card or a tag, or read a code out of a picture you were sent, and open the record it names."
        icon={<QrCode className="h-5 w-5" aria-hidden />}
      />
      {/*
        The panel is mounted whole rather than re-composed from `WorkshopCodeScanner` plus a
        resolver. Two copies of that wiring is how one of them comes to resolve a reference by a
        different route and open a different record — the same argument `ResolvedRecordRow` gives
        for being shared with the workshop's Cards & tags page rather than written twice.
      */}
      <RecordCodeScanPanel />
      <section className="panel p-4" aria-labelledby="scan-what-else">
        <h2 id="scan-what-else" className="display-title text-base">
          Where else a code can be read
        </h2>
        {/*
          Said here because a designer who arrives at a destination named "Scan a code" and finds it
          cannot do the one thing they came for concludes the app cannot do it at all. Each of these
          three surfaces answers a question this page cannot: this one is repository-wide and knows
          nothing about which workshop anybody is standing in.
        */}
        <ul className="mt-2 grid gap-1.5 text-sm leading-6 text-ink-muted">
          <li>
            A design workshop&apos;s <strong className="font-semibold text-ink-900">Cards &amp; tags</strong>{" "}
            page reads its own workshop&apos;s codes out of the draft held on this device first, so a
            prototype tag still resolves in a village with no signal.
          </li>
          <li>
            Inside a stage form, the <strong className="font-semibold text-ink-900">reference picker</strong>{" "}
            takes a scan to LINK a record to what you are filling in, rather than to open it.
          </li>
          <li>
            <strong className="font-semibold text-ink-900">Browse records</strong> carries this same
            panel above its search box, for when you are already there.
          </li>
        </ul>
      </section>
    </>
  );
}
