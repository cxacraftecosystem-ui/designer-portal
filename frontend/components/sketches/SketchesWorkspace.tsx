"use client";

import { useCallback, useEffect, useState } from "react";
import { Star, Upload } from "lucide-react";

import { HeldPostNotice, heldPostReason, useHeldPostRefusal } from "@/components/designworkshop/HeldPostNotice";
import { ReviewPanel } from "@/components/sketches/ReviewPanel";
import { SketchTabPanel, SketchTabs, type SketchTab } from "@/components/sketches/SketchTabs";
import { UploadTabHost } from "@/components/sketches/UploadTabHost";
import type { RateableEntityKey } from "@/components/sketches/reviewRanking";
import { readRegistry } from "@/components/sketches/stageRows";
import type { DwRegistry } from "@/lib/designWorkshops";

export type SketchesTab = "upload" | "review";

export function sketchesTabFromQuery(value: string | null | undefined): SketchesTab {
  return value === "review" ? "review" : "upload";
}

const TABS: ReadonlyArray<SketchTab<SketchesTab>> = [
  {
    key: "upload",
    label: "Upload",
    icon: Upload,
    hint: "Add drawings, photos and 3D models."
  },
  {
    key: "review",
    label: "Review",
    icon: Star,
    hint: "Rate and rank the pieces."
  }
];

const ENTITIES: ReadonlyArray<{ key: RateableEntityKey; label: string }> = [
  { key: "prototype", label: "Prototypes" },
  { key: "sketch", label: "Sketches" }
];

export type SketchesWorkspaceProps = {
  workshopId: string;
  tab: SketchesTab;
  onTabChange: (tab: SketchesTab) => void;
  idPrefix: string;
};

export function SketchesWorkspace({ workshopId, tab, onTabChange, idPrefix }: SketchesWorkspaceProps) {
  const [entityKey, setEntityKey] = useState<RateableEntityKey>("prototype");
  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const postRefusal = useHeldPostRefusal(workshopId);

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

  const changeTab = useCallback((next: SketchesTab) => onTabChange(next), [onTabChange]);

  return (
    <>
      <HeldPostNotice refusal={postRefusal} sayPending={tab === "upload"} />
      <SketchTabs
        tabs={TABS}
        active={tab}
        onChange={changeTab}
        label="Sketches and prototypes"
        idPrefix={idPrefix}
      />

      {tab === "upload" ? (
        <SketchTabPanel idPrefix={idPrefix} tabKey="upload">
          <UploadTabHost workshopId={workshopId} registry={registry} readOnlyReason={heldPostReason(postRefusal)} />
        </SketchTabPanel>
      ) : (
        <SketchTabPanel idPrefix={idPrefix} tabKey="review">
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
          <ReviewPanel
            workshopId={workshopId}
            round="PEER"
            readsStageRows
            entityKey={entityKey}
            readOnlyReason={heldPostReason(postRefusal)}
          />
        </SketchTabPanel>
      )}
    </>
  );
}
