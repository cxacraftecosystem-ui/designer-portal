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
 * ── HOW A DESIGNER REACHES THIS PAGE, AND THE ONE LINK THAT IS STILL MISSING ────────────────────
 *
 * NOTHING IN THE APPLICATION LINKS HERE YET, and it is written down rather than left to be
 * discovered: a grep for this route across `app/` and `components/` finds only this file. The row of
 * sibling links a designer actually uses — Cards & tags, Import photographs, AI layers, Ready to
 * submit?, Report — lives in `app/(protected)/design-workshops/[id]/page.tsx`, which is outside this
 * unit's files, so the entry has to be added by whoever holds that page:
 *
 *     <Link href={`/design-workshops/${id}/sketches-and-prototypes`} className="field-button-secondary">
 *       <PencilRuler className="h-4 w-4" aria-hidden />
 *       Sketches &amp; prototypes
 *     </Link>
 *
 * Until it exists this page is reachable only by typing or pasting its URL, which for the owner's
 * primary deliverable means no designer will find it. Nothing here can fix that from this side.
 *
 * ── THE TAB LIVES IN THE URL ────────────────────────────────────────────────────────────────────
 *
 * `?tab=review` so a designer can link a colleague straight to the round, and so the browser's Back
 * button steps between the two rather than leaving the page. `useSearchParams` needs a Suspense
 * boundary in Next 16; the page splits at exactly that line and nothing else is inside it.
 */

import { Suspense, use, useCallback, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { PencilRuler, Star, Upload } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { ReviewPanel } from "@/components/sketches/ReviewPanel";
import { SketchTabPanel, SketchTabs, type SketchTab } from "@/components/sketches/SketchTabs";
import { UploadTabHost } from "@/components/sketches/UploadTabHost";
import type { RateableEntityKey } from "@/components/sketches/reviewRanking";
import { readRegistry } from "@/components/sketches/stageRows";
import type { DwRegistry } from "@/lib/designWorkshops";

type TabKey = "upload" | "review";

const TABS: ReadonlyArray<SketchTab<TabKey>> = [
  {
    key: "upload",
    label: "Upload",
    icon: Upload,
    hint: "Add the drawings, photographs and 3D models of a piece to the record it belongs to."
  },
  {
    key: "review",
    label: "Review",
    icon: Star,
    hint: "Rate the other designers' work, say what you would change, and settle the order the pieces stand in."
  }
];

const ENTITIES: ReadonlyArray<{ key: RateableEntityKey; label: string }> = [
  { key: "prototype", label: "Prototypes" },
  { key: "sketch", label: "Sketches" }
];

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
  const tab: TabKey = search.get("tab") === "review" ? "review" : "upload";
  const [entityKey, setEntityKey] = useState<RateableEntityKey>("prototype");
  const [registry, setRegistry] = useState<DwRegistry | null>(null);

  /*
    THE REGISTRY IS READ ONCE HERE AND HANDED DOWN. Both tabs need it — the upload host to name the
    two stages and their four media fields, the review panel to find the stage a reorder writes
    through — and `readRegistry` answers from memory, then from IndexedDB, then from the network, so
    this costs nothing on a device that has opened a workshop before and still answers on one with
    no signal. A null is a state rather than an error and each tab says what it cannot do with it.
  */
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const answer = await readRegistry();
      if (!cancelled) setRegistry(answer);
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const changeTab = useCallback(
    (next: TabKey) => {
      // `replace`, not `push`: the two tabs are one screen, and pushing would make Back walk through
      // every tab press before it left the page.
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

      <SketchTabs
        tabs={TABS}
        active={tab}
        onChange={changeTab}
        label="Sketches and prototypes"
        idPrefix="sketches"
      />

      {tab === "upload" ? (
        <SketchTabPanel idPrefix="sketches" tabKey="upload">
          <UploadTabHost workshopId={workshopId} registry={registry} />
        </SketchTabPanel>
      ) : (
        <SketchTabPanel idPrefix="sketches" tabKey="review">
          {/*
            THE TWO RATEABLE ENTITIES ARE A CHOICE, NOT TWO PAGES. `RATEABLE_ENTITIES` on the server
            is exactly {sketch, prototype}; the child rows of a prototype are parts of one piece and
            are refused by name. Prototypes lead because they are what the pool round is about and
            what a workshop spends most of its second half on.
          */}
          <div role="group" aria-label="What to review" className="mb-4 flex flex-wrap gap-2">
            {ENTITIES.map((entity) => {
              const active = entity.key === entityKey;
              return (
                <button
                  key={entity.key}
                  type="button"
                  aria-pressed={active}
                  onClick={() => setEntityKey(entity.key)}
                  className={
                    active
                      ? "rounded-md border border-purple-700 bg-purple-700 px-3 py-1.5 text-sm font-semibold text-white"
                      : "rounded-md border border-line-200 bg-card px-3 py-1.5 text-sm font-medium text-ink-700 hover:border-purple-300 hover:bg-purple-50"
                  }
                >
                  {entity.label}
                </button>
              );
            })}
          </div>
          <ReviewPanel workshopId={workshopId} round="PEER" readsStageRows entityKey={entityKey} />
        </SketchTabPanel>
      )}
    </div>
  );
}
