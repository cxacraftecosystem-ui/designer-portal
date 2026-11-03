"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect } from "react";

import { SessionsList } from "@/components/questionnaire/SessionsList";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { qs } from "@/lib/questionnaire/qs";
import { legacyQuestionnaireTarget } from "@/lib/questionnaireLinks";

export default function QuestionnairePage() {
  return (
    <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>}>
      <Home />
    </Suspense>
  );
}

function Home() {
  const router = useRouter();
  const params = useSearchParams();
  const config = useQuestionnaireConfig();
  const target = legacyQuestionnaireTarget(new URLSearchParams(params.toString()));
  const off = config !== null && !config.v2Enabled;

  useEffect(() => {
    if (off) router.replace("/questionnaires");
    else if (target) router.replace(target);
  }, [off, target, router]);

  if (config === null || off || target) return <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>;
  return <SessionsList />;
}
