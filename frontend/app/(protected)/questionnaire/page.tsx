"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect } from "react";

import { SessionsList } from "@/components/questionnaire/SessionsList";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { qs } from "@/lib/questionnaire/qs";
import { legacyQuestionnaireTarget } from "@/lib/questionnaireLinks";

/**
 * `/questionnaire` is the sessions list (UNIFY 4.2, B28). The retired global interview form and its
 * builder are gone (owner decision 2; UNIFY 4.4); their links still arrive here and are sent on:
 *
 * - `?new=1`, or an artisan carried from a record (`?artisanId=` / `?artisanIds=`) → the start sheet,
 *   with those artisans picked;
 * - `?edit=<id>` (an old interview's edit link) → this list.
 *
 * While `QUESTIONNAIRE_V2` is off on the server there are no sessions to list, and the forms
 * library is where the work is: the page goes there.
 */
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
