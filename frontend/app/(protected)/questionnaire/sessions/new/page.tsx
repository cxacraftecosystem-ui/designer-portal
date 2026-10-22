"use client";

import { Mic } from "lucide-react";
import { useSearchParams } from "next/navigation";
import { Suspense } from "react";

import { PageHeader } from "@/components/PageHeader";
import { SessionStartSheet } from "@/components/questionnaire/SessionStartSheet";
import { V2Gate } from "@/components/questionnaire/V2Gate";
import { qs } from "@/lib/questionnaire/qs";

/** The start sheet (DESIGN §4.2). `?artisanId=` (repeatable) preselects from the artisan record. */
export default function NewSessionPage() {
  return (
    <>
      <PageHeader title={qs("startSheetTitle")} icon={<Mic className="h-5 w-5" aria-hidden />} />
      <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>}>
        <V2Gate>
          <Body />
        </V2Gate>
      </Suspense>
    </>
  );
}

function Body() {
  const params = useSearchParams();
  return <SessionStartSheet initialArtisanIds={params.getAll("artisanId").filter(Boolean)} initialQuestionnaireId={params.get("questionnaireId")}
      initialWorkshopId={params.get("designWorkshopId") ?? params.get("workshopId")}
    />;
}
