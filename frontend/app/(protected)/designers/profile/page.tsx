"use client";

/**
 * MY designer profile — the twenty values every report I generate prints under my name.
 *
 * WHY THIS PAGE EXISTS, in the requirement's own words: "instead of designers filling in their
 * information times and again for every report, they should have a page collecting their data as
 * well". Stage 1 of a design workshop asks for the designer's name and institution and stage 3 asks
 * for their profile paragraph and years of experience, and a designer who runs six workshops a
 * season was typing the same four answers twenty-four times. `prefill_from_profile` copies them in
 * at creation instead; this is where they are typed once.
 *
 * `"use client"` is not a preference. There is no server-side data fetching anywhere in this app —
 * the bearer token lives in `localStorage`, which a server component cannot read — so a server
 * component here would render an empty form to everybody and nothing would say why.
 *
 * NO ROUTE GUARD, DELIBERATELY, and it is worth saying why the nav entry is narrower than the page.
 * `GET`/`PUT /designers/me/profile` take `get_current_user` and nothing more: a professor or an
 * admin filling in for an absent designer signs a report the same way a designer does and needs the
 * same details on file, so the API refuses nobody. The menu entry and the dashboard tile are gated
 * on `canRunDesignWorkshops` (Android does the same) because offering the screen to a crowdsource
 * volunteer offers them a form whose twenty answers nothing in the app would ever read back — but a
 * gate in `ROUTE_GUARDS` would be a lie about the endpoint, so the URL stays open exactly as the
 * server leaves it.
 */

import { useEffect, useState } from "react";
import Link from "next/link";
import { IdCard } from "lucide-react";

import { DesignerProfileForm } from "@/components/designers/DesignerProfileForm";
import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import {
  getMyDesignerProfile,
  saveMyDesignerProfile,
  type DesignerProfile
} from "@/lib/designers";
import { canManageDesignerRoster } from "@/lib/permissions";

export default function MyDesignerProfilePage() {
  const { user } = useAuth();
  const [profile, setProfile] = useState<DesignerProfile | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getMyDesignerProfile()
      .then((loaded) => {
        if (!cancelled) setProfile(loaded);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Unable to load your designer profile");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <>
      <PageHeader
        title="My designer profile"
        description="Typed once and copied into every design workshop you start — the name, institution, profile paragraph and years of experience your reports are signed with."
        icon={<IdCard className="h-5 w-5" aria-hidden />}
        actions={
          // The roster is the OTHER designer fact and the two are constantly confused, so an admin
          // standing on one gets a door to the other. Gated on the same predicate the roster route
          // is: a link nobody may follow is worse than no link.
          canManageDesignerRoster(user) ? (
            <Link href="/admin/designers" className="field-button-secondary">
              Designer roster
            </Link>
          ) : null
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      {profile === null ? (
        // The row is created empty by the GET itself, so "still loading" is the only state between
        // arriving and having a form — there is no "you have no profile yet" to render.
        !error ? <section className="panel p-4 text-sm text-ink-700">Loading your profile…</section> : null
      ) : (
        <DesignerProfileForm
          profile={profile}
          save={saveMyDesignerProfile}
          onSaved={setProfile}
          possessive="my"
        />
      )}
    </>
  );
}
