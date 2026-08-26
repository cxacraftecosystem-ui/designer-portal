"use client";

/**
 * MY designer profile — the twenty-one values every report I generate prints under my name.
 *
 * WHY THIS PAGE EXISTS, in the requirement's own words: "instead of designers filling in their
 * information times and again for every report, they should have a page collecting their data as
 * well". Stage 1 of a design workshop asks for the designer's name and institution and stage 3 asks
 * for their profile paragraph and years of experience, and a designer who runs six workshops a
 * season was typing the same four answers twenty-four times. `prefill_from_profile` copies them in
 * at creation instead; this is where they are typed once.
 *
 * AND IT IS NO LONGER FOUR ANSWERS. On 2026-08-25 the owner's instruction widened the copy to every
 * writable column — designation, department, qualification, specialisation, the four address lines,
 * the phone, email and website, the empanelment number and date, and the photograph, signature and
 * CV — so stage 3 of a new workshop now arrives with the whole profile in it. Each one stays
 * editable in the stage like any other captured value, because a report is a record of who ran a
 * workshop at the time. See `designers.PREFILL_MAP`, which is the one place that copying is declared.
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
 * volunteer offers them a form whose twenty-one answers nothing in the app would ever read back — but a
 * gate in `ROUTE_GUARDS` would be a lie about the endpoint, so the URL stays open exactly as the
 * server leaves it.
 */

import { Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { IdCard, Sparkles } from "lucide-react";

import { DesignerProfileForm } from "@/components/designers/DesignerProfileForm";
import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import {
  getMyDesignerProfile,
  saveMyDesignerProfile,
  type DesignerProfile
} from "@/lib/designers";
import { canManageDesignerRoster } from "@/lib/permissions";

/**
 * The sentence a designer who was BROUGHT here reads, explaining why.
 *
 * `DesignerProfileOnboarding` redirects a designer's first signed-in landing to this page with
 * `?welcome=1`. Rule 4 of that component: an unexplained forced navigation is indistinguishable
 * from a bug, and the first thing it would teach a new designer is that the app moves on its own.
 * So the parameter exists for exactly one purpose — to let this page say "we brought you here, this
 * is why, and we will not keep doing it".
 *
 * IT IS NOT A DISMISSABLE BANNER AND IT IS NOT STICKY. It is drawn from a URL parameter, so it is
 * gone the moment the designer navigates anywhere — including on the redirect the save performs
 * nothing of. There is no state to persist and nothing to remember: the redirect already marks
 * itself once, and a "don't show me again" control would be a second, weaker copy of a mark that
 * already exists.
 *
 * AND IT DOES NOT PROMISE A SESSION. The sentence used to end "you will not be brought here again
 * this session", which is what the mark guarantees where `sessionStorage` answers and NOT what it
 * guarantees where the browser refuses it: there `DesignerProfileOnboarding` falls back to an
 * in-memory Set that dies with the loaded page, so a reload can bring the designer back and this
 * card would have been caught promising otherwise. It now claims only what holds in both cases —
 * that moving around the app will not keep dragging them here. A welcome card that is wrong about
 * the app's own behaviour teaches the new designer exactly what rule 4 of that component exists to
 * prevent.
 *
 * NOT STRIPPED WITH `router.replace`, unlike `useEditDeepLink`'s one-shot parameters. Those carry an
 * INTENT that must be consumed exactly once (a record to seed, a form to open) and re-applying one
 * would put a cancelled edit back under the back button. This carries a SENTENCE. Re-reading it on a
 * re-render costs nothing, and rewriting the URL underneath a reader who has not asked for anything
 * would be the more surprising act.
 */
function WelcomeNotice() {
  const params = useSearchParams();
  if (params.get("welcome") !== "1") return null;
  return (
    <div className="mb-4 flex items-start gap-3 rounded-md border border-line-200 bg-field-100 px-3 py-3">
      <Sparkles className="mt-0.5 h-5 w-5 shrink-0 text-field-600" aria-hidden />
      <div className="min-w-0 text-sm leading-6 text-ink-700">
        <p className="font-medium text-ink-900">Welcome — start here.</p>
        <p className="mt-0.5">
          Everything on this page is copied into every design workshop report you generate, so filling
          it in once saves retyping it in every workshop. You can leave now and come back from the
          menu at any time — we will not keep bringing you back as you move around the app.
        </p>
      </div>
    </div>
  );
}

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
        description="Typed once and copied into every design workshop you start — the name, institution, qualifications, contact details, empanelment identifiers, photograph, signature and CV your reports are signed with."
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

      {/*
        SUSPENSE IS MANDATORY, NOT DEFENSIVE. `useSearchParams` suspends in Next 16, and a page that
        reads it without a boundary opts its whole route out of static rendering with a build-time
        warning. The boundary's fallback is `null` because the thing inside it is one sentence of
        reassurance: showing a skeleton for it would be more visually disruptive than showing nothing
        for the frame it takes to resolve.
      */}
      <Suspense fallback={null}>
        <WelcomeNotice />
      </Suspense>

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
