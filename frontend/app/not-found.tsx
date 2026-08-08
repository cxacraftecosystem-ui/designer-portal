"use client";

import { useEffect, useState } from "react";
import { MapPinOff } from "lucide-react";

import { DeadEndDetail, DeadEndFrame, DeadEndPanel, DeadEndText, DeadEndWayBack } from "@/components/DeadEnd";

/**
 * The 404 for the whole application.
 *
 * WHY THIS IS THE ONLY ONE, and there is no second `app/(protected)/not-found.tsx`.
 * Next.js routes an unmatched URL to the ROOT `not-found` and nowhere else; a nested one is a
 * boundary for `notFound()` calls inside its own subtree. Nothing in this app calls `notFound()` and
 * nothing easily could — there is no server-side data fetching anywhere (the token lives in
 * localStorage), so every record is loaded by a client component that already handles the API's 404
 * itself, in place, with copy that can name the actual record (see the `RestrictedPanel` on
 * `/designers/[userId]/profile`). A `(protected)/not-found.tsx` would therefore be a file nothing
 * ever renders, and the app is one typed URL away from proving it: `/crafts/{id}` matches no route
 * at all — crafts, workshops and processes have no `/[id]` route, their ids travel as `?edit=` — so
 * a perfectly ordinary link to one of those records lands HERE, outside the protected tree.
 *
 * One page serves both readers because `AuthProvider` sits in the ROOT layout, above this boundary:
 * `DeadEndWayBack` asks who is signed in and offers a designer their work or a visitor the front
 * door, and `DeadEndFrame` brings the island navigation back for whoever is entitled to it. Two
 * files would have split one screen's copy across two places to say the same two sentences.
 */
export default function NotFound() {
  /**
   * Rendered after mount, never during. This file is prerendered at build time, when the requested
   * path is a placeholder rather than the reader's, so putting `usePathname()` straight into the
   * markup would ship the build's path in the HTML and swap it at hydration. Read from the browser,
   * once, and the server renders nothing here at all.
   */
  const [asked, setAsked] = useState<string | null>(null);
  useEffect(() => {
    setAsked(window.location.pathname + window.location.search);
  }, []);

  return (
    <DeadEndFrame nav>
      <DeadEndPanel tone="notice" icon={<MapPinOff className="h-5 w-5" aria-hidden />} title="There is nothing at this address">
        {/*
          The deleted record gets its own sentence and goes FIRST because it is the common case: this
          is a repository, and ids in bookmarks and in other people's messages outlive the records
          they name. "This page could not be found" is true and useless.

          It must stay a disjunction. The API deliberately answers 404 rather than 403 for a record
          the caller may not see, so that nobody can enumerate records by asking about them
          (`_require` in backend/app/api/routes/data_browser.py:653 — "reported as 'not found' rather
          than 403 ... must not confirm that the record exists" — and `load_workshop_or_404` in
          backend/app/services/design_workshops.py:94, whose rule design_workshops.py:938 restates).
          Copy that said "this record was deleted" would hand back the exact
          fact the status code is chosen to withhold — it would confirm the id is real. Naming both
          causes and saying plainly that this page cannot tell them apart is the most a reader can
          honestly be given, and it is still enough to act on.
        */}
        <DeadEndText>
          If you followed a link to a record — a craft, a workshop, an artisan — it may have been deleted, or it may not
          be one this account can open. Nothing here can tell you which: the repository answers the same way to both, on
          purpose, so that a list of ids cannot be used to work out which records exist.
        </DeadEndText>
        <DeadEndText>
          The address may also simply be mistyped, or point at a page that has moved since the link was written. If a
          colleague sent it, the address below is the part worth sending back to them.
        </DeadEndText>
        {asked ? <DeadEndDetail label="You asked for" value={asked} /> : null}
        <DeadEndWayBack identity />
      </DeadEndPanel>
    </DeadEndFrame>
  );
}
