"use client";

/**
 * Sketches & Prototypes — THE SCREEN. Extracted so its two routes cannot drift apart.
 *
 * ── THIS COMPONENT HAS TWO CALLERS, AND A CHANGE TO ONE MUST NOT BE MADE BY FORKING IT ──────────
 *
 *   1. `app/(protected)/design-workshops/[id]/sketches-and-prototypes/page.tsx` — the workshop's
 *      own tab. The id is a segment of the URL because the designer walked into the workshop first.
 *   2. `app/(protected)/sketches-and-prototypes/page.tsx` — the top-level Browse destination. There
 *      is no id in the path and there cannot be one: asking WHICH workshop is the first thing that
 *      page does, and this component is what it mounts once the question is answered.
 *
 * So a change wanted on one of those pages is a change to THIS FILE, or a new prop on it. Copying
 * the body into the other route is the failure this extraction exists to prevent, and the cost is
 * not hypothetical — every item below is a decision that took a shipped defect to arrive at, lives
 * in this file only, and would be silently absent from a fork:
 *
 *   * `readsStageRows` is TRUE here and is not a prop (see below). A fork that flipped it, or that
 *     grew a caller which is not a member of the workshop, re-arms the bug recorded at length in
 *     `stageRows.ts`'s header: `ensureDraft` is a check-AND-CREATE, so it mints a blank
 *     session-owned local draft for whatever workshop id it is handed, and
 *     `design-workshops/page.tsx` then prepends exactly such drafts to this device's own workshop
 *     list whenever it is offline. A stranger's workshop appeared as a blank row on a designer's
 *     own list because they had opened a review page.
 *   * The registry read below is mount-once with a cancel flag. A second copy that dropped the flag
 *     sets state after unmount on every fast tab-away.
 *   * `idPrefix` reaches three places — the strip and both panels — and a fork that hardcoded it in
 *     one of them gives a tab an `aria-controls` pointing at no element at all.
 *   * The two tabs are mounted EXCLUSIVELY, and that is load-bearing rather than incidental:
 *     keeping the review panel mounted behind the upload tab would change WHEN arrangements are
 *     persisted, because `ReviewPanel` flushes its coalescing timer on unmount.
 *
 * ── WHAT IS A PROP, AND WHY IT COULD NOT STAY A CONSTANT ────────────────────────────────────────
 *
 * `tab` / `onTabChange` — THE URL BELONGS TO THE ROUTE, NOT TO THIS COMPONENT, and this is the one
 * thing that could not simply be lifted. The body used to write its own address bar:
 *
 *     const query = next === "upload" ? "" : `?tab=${next}`;
 *     router.replace(`/design-workshops/${workshopId}/sketches-and-prototypes${query}`, …);
 *
 * Two properties of that line make it unshareable. The path is an absolute LITERAL, so the second
 * caller's first tab press would have thrown the browser out of `/sketches-and-prototypes` and onto
 * the per-workshop route; and the query is rebuilt FROM EMPTY, so the same press would have erased
 * the `?workshop=` the top-level page keeps its whole state in — the page would jump elsewhere and
 * lose the workshop it was showing, on a click that is supposed to change one tab. Each route
 * therefore writes its own URL and hands the answer down as a value. {@link sketchesTabFromQuery}
 * is exported so both of them PARSE `?tab=` by one rule instead of two strict-equality checks that
 * can drift.
 *
 * `idPrefix` — REQUIRED, NOT DEFAULTED, and the absence of a default is the point. `SketchTabs` and
 * `SketchTabPanel` each demand it for one stated reason ("so two strips on one page cannot
 * collide"), and a default here would make this the first link in that chain to hand a second mount
 * the same `sketches-tab-*` / `sketches-panel-*` ids as the first. Two routes cannot co-mount, so
 * both callers legitimately pass the same string today; the day one of them embeds two of these,
 * the compiler is the thing that asks.
 *
 * ── WHAT IS DELIBERATELY *NOT* A PROP ───────────────────────────────────────────────────────────
 *
 * `round="PEER"` AND `readsStageRows` ARE FIXED, because they are facts about the CALLER'S
 * PERMISSION rather than about the workshop, and for both callers they are the same fact: somebody
 * `load_workshop_or_404` admits. The per-workshop route, because that is the only way its URL comes
 * to exist. The top-level route, because its chooser is populated by `GET /design-workshops`, whose
 * rows for a non-admin are exactly `visible_to_clause` — created-by-me OR holding a
 * `DesignWorkshopViewer` grant — which is the same door asked in list form. Turning them into props
 * would advertise this component to the POOL surface, and the UPLOAD half cannot serve a pool
 * reviewer at all: it is unconditional `ensureDraft`, `stageLocalMedia` and `putDraftStage` against
 * `workshopId`. The pool round therefore stays where it is — `/design-review` mounts `ReviewPanel`
 * directly with `round="POOL"`, `readsStageRows={false}` and no upload tab — and it must keep doing
 * that rather than reaching for this.
 *
 * `PageHeader` IS THE CALLER'S. The per-workshop route renders it twice on purpose (title and icon
 * in the Suspense fallback, title, description and icon in the body) so the header does not appear
 * to load in; and the two routes do not describe themselves in the same words, because one of them
 * has a workshop chooser sitting above it. A header in here would be wrong on one route, or would
 * need three more props to be right on both.
 *
 * THE REGISTRY IS READ HERE rather than passed in, which is a move from the per-workshop page (where
 * the effect used to live) and removes the only thing both callers would otherwise have had to
 * duplicate. It costs nothing: `readRegistry` answers from a module memo, then IndexedDB, then the
 * network, so a device that has opened a workshop before never leaves the process. Note that
 * `ReviewPanel` reads it AGAIN for itself — it takes no registry prop — so the review tab reads
 * twice and the second read is the memo. Leaving that alone is deliberate; giving `ReviewPanel` a
 * registry prop would change a component `/design-review` also mounts.
 *
 * ── THE ONE RULE THIS COMPONENT CANNOT ENFORCE FOR ITSELF: KEY ON THE WORKSHOP ──────────────────
 *
 * A CALLER THAT CAN CHANGE `workshopId` WITHOUT NAVIGATING MUST PASS `key={workshopId}`. The
 * top-level page does. The per-workshop page does not need to, because its id cannot change without
 * a route change.
 *
 * Why: `ReviewPanel` applies a reorder to the list at once and coalesces the WRITE behind 1200 ms of
 * quiet, flushing on unmount. Its `persist` closes over `stageKey` and reads `draftId.current`.
 * Changing `workshopId` in place restarts its load but does NOT clear that pending timer — and the
 * stage key is derived from the registry, so it is the SAME STRING in every workshop. A designer who
 * nudged a card in workshop A and then switched the chooser to workshop B inside the window would
 * have A's arrangement written into B's draft: a wrong ordinal in a real record, with nothing on
 * screen to say it happened. Remounting turns the switch into an unmount, and the unmount's cleanup
 * runs before the new mount's effects, so the flush still sees A's `draftId.current` and A's
 * `stageKey` and lands where it was meant to.
 */

import { useCallback, useEffect, useState } from "react";
import { Star, Upload } from "lucide-react";

import { ReviewPanel } from "@/components/sketches/ReviewPanel";
import { SketchTabPanel, SketchTabs, type SketchTab } from "@/components/sketches/SketchTabs";
import { UploadTabHost } from "@/components/sketches/UploadTabHost";
import type { RateableEntityKey } from "@/components/sketches/reviewRanking";
import { readRegistry } from "@/components/sketches/stageRows";
import type { DwRegistry } from "@/lib/designWorkshops";

/** The two tabs, spelled as they appear in `?tab=`. */
export type SketchesTab = "upload" | "review";

/**
 * `?tab=` → a tab, for both routes.
 *
 * TOTAL BY DESIGN. `?tab=REVIEW`, `?tab=`, an absent parameter and a stale bookmark from a build
 * with a third tab all mean "upload", which is the tab that opens by default anyway — so there is
 * no state here worth an error surface, and a route that rejected the value would be refusing a
 * link somebody was sent. The strictness is on the ONE recognised spelling, so the parameter cannot
 * quietly start matching something else.
 */
export function sketchesTabFromQuery(value: string | null | undefined): SketchesTab {
  return value === "review" ? "review" : "upload";
}

const TABS: ReadonlyArray<SketchTab<SketchesTab>> = [
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

export type SketchesWorkspaceProps = {
  /** The workshop whose `sketch` and `prototype` rows this screen reads and writes. */
  workshopId: string;
  /** Which tab to show — parsed from the caller's own URL with {@link sketchesTabFromQuery}. */
  tab: SketchesTab;
  /** Show this tab. The caller writes its own address bar; see the header. */
  onTabChange: (tab: SketchesTab) => void;
  /** Prefix for the strip's and the panels' DOM ids. No default, deliberately — see the header. */
  idPrefix: string;
};

export function SketchesWorkspace({ workshopId, tab, onTabChange, idPrefix }: SketchesWorkspaceProps) {
  const [entityKey, setEntityKey] = useState<RateableEntityKey>("prototype");
  const [registry, setRegistry] = useState<DwRegistry | null>(null);

  /*
    THE REGISTRY IS READ ONCE HERE AND HANDED DOWN. Both tabs need it — the upload host to name the
    two stages and their four media fields, the review panel to find the stage a reorder writes
    through — and `readRegistry` answers from memory, then from IndexedDB, then from the network, so
    this costs nothing on a device that has opened a workshop before and still answers on one with
    no signal. A null is a state rather than an error and each tab says what it cannot do with it.

    The first render is therefore always `registry === null`, and the upload host shows its "this
    browser holds no field registry yet" panel for that one frame. That is unchanged from before the
    extraction and is deliberately not given a spinner: on a device that has ever synced it resolves
    without a network round trip, and a spinner that appears and vanishes inside one frame reads as
    a flicker rather than as progress.
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

  /*
    Wrapped so the strip receives one stable callback whatever shape the caller's own has. Nothing
    about the address bar is decided in this file — `onTabChange` is where the URL is written, and
    the two routes write different ones.
  */
  const changeTab = useCallback((next: SketchesTab) => onTabChange(next), [onTabChange]);

  return (
    <>
      <SketchTabs
        tabs={TABS}
        active={tab}
        onChange={changeTab}
        label="Sketches and prototypes"
        idPrefix={idPrefix}
      />

      {tab === "upload" ? (
        <SketchTabPanel idPrefix={idPrefix} tabKey="upload">
          <UploadTabHost workshopId={workshopId} registry={registry} />
        </SketchTabPanel>
      ) : (
        <SketchTabPanel idPrefix={idPrefix} tabKey="review">
          {/*
            THE TWO RATEABLE ENTITIES ARE A CHOICE, NOT TWO PAGES. `RATEABLE_ENTITIES` on the server
            is exactly {sketch, prototype}; the child rows of a prototype are parts of one piece and
            are refused by name. Prototypes lead because they are what the pool round is about and
            what a workshop spends most of its second half on.

            The choice is component state and NOT in the URL, so it is not linkable and it resets on
            remount — including the remount the top-level page forces when its chooser changes
            workshop, which is right: a different workshop is a different screen. Putting it in the
            query would need both routes to agree on a second parameter name, and the per-workshop
            route's tab writer rebuilds its query from empty, so it would drop it on the next tab
            press.
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
    </>
  );
}
