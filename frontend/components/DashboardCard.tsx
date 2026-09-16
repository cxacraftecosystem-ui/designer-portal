"use client";

import Link from "next/link";
import { ArrowRight, Pencil, Plus, type LucideIcon } from "lucide-react";

import { GLASS_TILE, GlassSurface } from "@/components/ui/GlassSurface";

/**
 * One dashboard action tile (Android `DashboardActionCard` parity): a small dark-purple
 * icon tile, the display-font label, a filled purple "New" action and — where the record
 * type is editable — an outlined "Update" action leading to the list page.
 *
 * The card is liquid glass: `bg-card/70` instead of an opaque fill, because the filter
 * refracts what is behind the tile and an opaque background would hide it. The dashboard
 * paints a mesh under the grid to give it something worth bending.
 */
export function DashboardCard({
  label,
  icon: Icon,
  newHref,
  updateHref,
  newLabel = "New"
}: {
  label: string;
  icon: LucideIcon;
  /**
   * The primary action, and OPTIONAL since 2026-09-16 — a tile may legitimately offer Update and
   * not New.
   *
   * THE CASE THAT MADE IT OPTIONAL, because "one tile, two entitlements" sounds like an
   * over-generalisation and is not. `POST /workshops` moved to a MINISTRY_ADMIN floor
   * (`require_workshop_opener`) while `PATCH /workshops/{id}` stayed at Professor, so a professor
   * has the Update and not the New. Drawing both gave them a "New" button that landed on the
   * workshops page's explanatory panel — the correct destination, and one wasted click plus a
   * moment of believing the app was broken. Hiding the whole TILE instead would have taken away the
   * Update they do have.
   *
   * Absent means the button is not drawn at all, exactly as an absent `updateHref` already does for
   * the other one. A tile with neither should not be in the array.
   */
  newHref?: string;
  updateHref?: string;
  /**
   * The primary action's wording, from the Android app's `EntryMode.createButtonLabel()`.
   *
   * Every tile used to say "New", which is wrong for more than a third of them: you do not create
   * a new View Data, and "New" on the Users tile reads as "add a user" when the button opens the
   * list. The two apps are the same product and a researcher moves between them mid-workshop, so
   * the wording has to be the same word.
   */
  newLabel?: string;
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
      <div className="mt-auto flex flex-col gap-1.5 pt-1">
        {newHref ? (
          <Link className="field-button h-9 min-h-0 px-3 text-xs" href={newHref}>
            {/* "Open"/"Manage" are not creations, so they do not get the plus. Android draws the same
                distinction (`primaryIcon`), and a plus on a button that only navigates is a lie. */}
            {newLabel === "Open" || newLabel === "Manage" ? (
              <ArrowRight className="h-3.5 w-3.5" aria-hidden />
            ) : (
              <Plus className="h-3.5 w-3.5" aria-hidden />
            )}
            {newLabel}
          </Link>
        ) : null}
        {updateHref ? (
          <Link className="field-button-secondary h-9 min-h-0 px-3 text-xs" href={updateHref}>
            <Pencil className="h-3.5 w-3.5" aria-hidden />
            Update
          </Link>
        ) : null}
      </div>
    </GlassSurface>
  );
}
