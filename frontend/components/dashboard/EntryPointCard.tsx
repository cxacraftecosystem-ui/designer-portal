"use client";

/**
 * A DESTINATION THAT BELONGS ON THE GRID AND MAY NOT BE A TILE.
 *
 * ── WHY THIS IS A SEPARATE COMPONENT AND NOT A `tiles` ENTRY ───────────────────────────────────
 *
 * `/questionnaires` — "My questionnaires", the designer's own .xlsx-derived instrument and its
 * pro-forma — is a first-class designer destination with no handset counterpart. The dashboard's
 * `tiles` array is held in lockstep with Android's by THREE parsers in three languages, and
 * `DashboardTileParityTest.kt` asserts `WEB_ONLY == emptyList()` in both directions: every web tile
 * label must also be an Android card label. There is no `EntryMode` for this destination, so adding
 * it to the array turns the Kotlin suite red on `main` rather than on the change that did it —
 * that suite is not in the frontend gate. `dashboard-tile-parity-unit.spec.ts` says the same thing
 * from this side, pinning `"/questionnaires": false` inside a closed FAMILY literal.
 *
 * ── AND WHY IT IS NO LONGER A FULL-WIDTH ROW ───────────────────────────────────────────────────
 *
 * It shipped on 2026-09-20 as a `<Link>` SIBLING of the tile grid, and the owner's next words were
 * *"My questionnaires, and the card size for this one currently is also different, fix that."* They
 * were right, and it was off in eight ways at once, measured: it spanned 100% of the section against
 * a tile's 33%/50%; `rounded-md` (12px) against `rounded-lg` (16px); an opaque ground against the
 * tiles' glass; a row layout against a column; a 36px pale chip with an 18px glyph against a 40px
 * filled chip with a 20px white one; `text-sm` against `text-base leading-snug`; no button at all
 * against one or two; and a resting height near 83px against 142px or 184px.
 *
 * So this is `DashboardCard`'s geometry, restated — the same `GlassSurface(GLASS_TILE)` shell, the
 * same padding, radius, chip, type scale and `mt-auto` button rail — with ONE addition the tiles do
 * not have: a two-line description. That line is kept because it is load-bearing copy rather than
 * decoration. It is the only place in the product that tells a designer the two instruments are
 * different things, and `questionnaires/page.tsx:430-467` and `guide/steps.ts:1291` both depend on
 * that distinction being stated rather than inferred.
 *
 * ⚠ THE GEOMETRY IS RESTATED HERE AND NOT SHARED WITH `DashboardCard`, ON PURPOSE. Adding a
 * `description` prop to that component would put a new optional field in front of the three parsers
 * that read its source, for the sake of one caller. The cost of this copy is that a change to the
 * tile's shell must be made twice; the cost of the alternative is a parity suite that goes red on
 * `main` for a reason nobody can see from the diff. `dashboard-megacard-unit.spec.ts` closes the gap
 * by asserting the two shells still agree, which is the cheap half of the trade.
 */

import Link from "next/link";
import { ArrowRight } from "lucide-react";
import type { LucideIcon } from "lucide-react";

import { GlassSurface, GLASS_TILE } from "@/components/ui/GlassSurface";

export function EntryPointCard({
  label,
  description,
  icon: Icon,
  href,
  actionLabel = "Open"
}: {
  label: string;
  description: string;
  icon: LucideIcon;
  href: string;
  /** "Open"/"Manage" never take the plus — the same rule `DashboardCard` draws, and Android's. */
  actionLabel?: string;
}) {
  return (
    <GlassSurface
      options={GLASS_TILE}
      className="flex flex-col gap-2 rounded-lg border border-line-200 bg-card/70 p-3 shadow-sm"
    >
      <div className="grid h-10 w-10 place-items-center rounded-md bg-purple-800">
        <Icon className="h-5 w-5 text-white" aria-hidden />
      </div>
      <div className="font-display text-base font-bold leading-snug text-ink-900">{label}</div>
      <p className="text-xs leading-5 text-ink-500">{description}</p>
      <div className="mt-auto flex flex-col gap-1.5 pt-1">
        <Link className="field-button h-9 min-h-0 px-3 text-xs" href={href}>
          <ArrowRight className="h-3.5 w-3.5" aria-hidden />
          {actionLabel}
        </Link>
      </div>
    </GlassSurface>
  );
}
