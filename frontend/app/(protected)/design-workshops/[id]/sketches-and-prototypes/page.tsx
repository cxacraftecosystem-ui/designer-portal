"use client";

/**
 * Sketches and Prototypes — one workshop's own tab. LEVEL ONE of the two the owner asked for.
 *
 * ── THE TWO LEVELS, AND WHY THE SECOND ONE IS NOT A TAB ON THIS PAGE ────────────────────────────
 *
 * The owner asked for peers in the same design workshop first, and then — once prototypes are
 * finalised — the entire pool of designers. Those are two different audiences and they cannot share
 * a route, which is a measured fact about this API rather than a preference:
 *
 *   `load_workshop_or_404` (backend/app/services/design_workshops.py:127) admits exactly three
 *   parties — the workshop's creator, an admin, and the holder of a `DesignWorkshopViewer` grant —
 *   and answers everybody else with the same 404 and the same "Record not found" a nonexistent id
 *   gets. Every one of the 22 stage SAVE routes is gated by that same helper, so widening it to let
 *   a pool reviewer in would hand every designer in the country write access to every finished
 *   workshop's fieldwork, not merely a read.
 *
 * A pool reviewer is by definition somebody that helper turns away, so the pool round lives at
 * `/design-review`, which goes through `load_ratable_workshop_or_404` — a second, narrow door that
 * leads only to the rating reads. This page is for the people who can already open this workshop.
 *
 * ── WHAT THE TWO TABS ARE ───────────────────────────────────────────────────────────────────────
 *
 * UPLOAD opens by default, as asked. Its body is two units meeting: `components/sketches/upload/`
 * produces `File`s and knows nothing about media ids, and `components/sketches/UploadTabHost` says
 * WHICH sketch or prototype each one belongs to and writes it into that row's draft. REVIEW is the
 * rating and ranking surface. Both are VIEWS over the registry's own `sketch` and `prototype` rows
 * — there is no second store of prototypes anywhere in this feature.
 *
 * ── THE BODY IS NOT IN THIS FILE ANY MORE, AND THAT IS THE POINT ─────────────────────────────────
 *
 * Everything below the header is `components/sketches/SketchesWorkspace`, because THIS SCREEN NOW
 * HAS TWO ROUTES. The other is `/sketches-and-prototypes`, the top-level Browse destination, which
 * exists for the designer who knows they want to upload a sketch and does not remember which of
 * their workshops it belongs to: it asks for the workshop with a chooser and then mounts the same
 * component. Read that component's header before changing anything about the tabs, the entity
 * chooser or the registry read — a change made by copying the body back into one of the two pages
 * is the exact failure the extraction prevents, and its header enumerates what a copy would lose.
 *
 * WHAT STAYED HERE AND WHY: the page header, the Suspense boundary, and the URL. The header,
 * because the two routes do not describe themselves in the same words and one of them has a chooser
 * above it. The URL, because it is a fact about the route and not about the screen — this page's tab
 * writer emits an absolute `/design-workshops/{id}/sketches-and-prototypes` path and rebuilds its
 * query from empty, which is correct HERE (there is no other parameter on this URL to lose) and
 * would have been destructive on the top-level route, whose whole state is `?workshop=`.
 *
 * ── HOW A DESIGNER REACHES THIS PAGE ────────────────────────────────────────────────────────────
 *
 * Two ways now, and the older note here — "NOTHING IN THE APPLICATION LINKS HERE YET", followed by
 * the exact `<Link>` somebody else had to add — is out of date in both directions. That link was
 * added and is live: `app/(protected)/design-workshops/[id]/page.tsx:432`, in the row of sibling
 * buttons beside Cards & tags and Import photographs. And `/sketches-and-prototypes` is now on the
 * Browse menu for the case where the designer has no workshop in hand.
 *
 * BOTH DOORS STAY. The top-level route is an additional way in, not a replacement, and this one is
 * the only one that can be reached from inside a workshop the designer is already standing in —
 * without it, a designer who has just filled in stage 11 would have to leave the workshop and name
 * it again in a chooser to attach the drawing they are holding.
 *
 * ── THE TAB LIVES IN THE URL ────────────────────────────────────────────────────────────────────
 *
 * `?tab=review` so a designer can link a colleague straight to the round, and so the browser's Back
 * button steps between the two rather than leaving the page. `useSearchParams` needs a Suspense
 * boundary in Next 16; the page splits at exactly that line and nothing else is inside it.
 */

import { Suspense, use, useCallback } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { PencilRuler } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import {
  SketchesWorkspace,
  sketchesTabFromQuery,
  type SketchesTab
} from "@/components/sketches/SketchesWorkspace";

export default function SketchesAndPrototypesPage({ params }: { params: Promise<{ id: string }> }) {
  // Next 16 hands route params over as a promise; `use` unwraps it in a client component.
  const { id } = use(params);
  return (
    <Suspense fallback={<PageHeader title="Sketches and Prototypes" icon={<PencilRuler className="h-5 w-5" aria-hidden />} />}>
      <SketchesAndPrototypes workshopId={id} />
    </Suspense>
  );
}

function SketchesAndPrototypes({ workshopId }: { workshopId: string }) {
  const router = useRouter();
  const search = useSearchParams();
  const tab = sketchesTabFromQuery(search.get("tab"));

  const changeTab = useCallback(
    (next: SketchesTab) => {
      // `replace`, not `push`: the two tabs are one screen, and pushing would make Back walk through
      // every tab press before it left the page.
      //
      // The query is rebuilt from scratch, which is safe on THIS route and only this one: nothing
      // else is ever in this URL's query string. The top-level twin writes its own, because it has
      // a `?workshop=` to carry and this line would erase it. See `SketchesWorkspace`'s header for
      // why the writer is per-route rather than inside the shared component.
      const query = next === "upload" ? "" : `?tab=${next}`;
      router.replace(`/design-workshops/${workshopId}/sketches-and-prototypes${query}`, { scroll: false });
    },
    [router, workshopId]
  );

  return (
    <div>
      <PageHeader
        title="Sketches and Prototypes"
        description="Everything this workshop has drawn and made, in one place — added on the Upload tab, judged and ranked on the Review tab."
        icon={<PencilRuler className="h-5 w-5" aria-hidden />}
      />

      {/*
        NO `key` HERE, deliberately, and the shared component's header says why one is needed on the
        other route: `workshopId` comes out of the path, so it cannot change without a navigation
        that remounts this page anyway. Adding one would be harmless but would imply this id moves.
      */}
      <SketchesWorkspace workshopId={workshopId} tab={tab} onTabChange={changeTab} idPrefix="sketches" />
    </div>
  );
}
