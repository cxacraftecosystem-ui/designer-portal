"use client";

import { Grid3x3 } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { V2Gate } from "@/components/questionnaire/V2Gate";
import { WorkshopCoverage } from "@/components/questionnaire/WorkshopCoverage";
import { qs } from "@/lib/questionnaire/qs";

export default function QuestionnaireCoveragePage() {
  return (
    <>
      <PageHeader title={qs("coverageTitle")} description={qs("coverageDescription")} icon={<Grid3x3 className="h-5 w-5" aria-hidden />} />
      <V2Gate>
        <WorkshopCoverage />
      </V2Gate>
    </>
  );
}
