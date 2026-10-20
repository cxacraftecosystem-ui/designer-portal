"use client";

/**
 * Scan a code — the destination named after the act (owner, 2026-08-28: scanning was "buried
 * underneath a lot of pages"). Its body is the top-bar scanner's own panel (`components/scan`), so
 * the page, the header button and `/search`'s panel answer every code through one dispatcher
 * (`lib/scanDispatch.ts`). No `ROUTE_GUARDS` row, matching `/search`: every lookup behind it is
 * scoped per viewer by the API, which answers 404 for a record the caller may not have.
 */

import { QrCode } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { ScanPanel } from "@/components/scan/ScanSheet";

export default function ScanCodePage() {
  return (
    <>
      <PageHeader
        title="Scan a code"
        description="A card, a tag or a code in a picture."
        icon={<QrCode className="h-5 w-5" aria-hidden />}
      />
      <section className="panel mx-auto max-w-2xl p-4">
        <ScanPanel />
      </section>
    </>
  );
}
