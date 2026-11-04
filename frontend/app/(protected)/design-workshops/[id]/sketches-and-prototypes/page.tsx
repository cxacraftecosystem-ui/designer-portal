"use client";

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
      const query = next === "upload" ? "" : `?tab=${next}`;
      router.replace(`/design-workshops/${workshopId}/sketches-and-prototypes${query}`, { scroll: false });
    },
    [router, workshopId]
  );

  return (
    <div>
      <PageHeader
        title="Sketches and Prototypes"
        description="Upload, rate and rank this workshop's pieces."
        icon={<PencilRuler className="h-5 w-5" aria-hidden />}
      />

      <SketchesWorkspace workshopId={workshopId} tab={tab} onTabChange={changeTab} idPrefix="sketches" />
    </div>
  );
}
