"use client";

import { useParams, useRouter } from "next/navigation";
import { useEffect } from "react";

import { qs } from "@/lib/questionnaire/qs";
import { consolidatedTarget } from "@/lib/questionnaireLinks";

/** The old consolidated view: the artisan's Questionnaire tab replaces it (UNIFY 4.2, DR audit M10). */
export default function ConsolidatedRedirectPage() {
  const params = useParams<{ artisanId: string }>();
  const router = useRouter();
  useEffect(() => {
    router.replace(consolidatedTarget(decodeURIComponent(params.artisanId)));
  }, [params.artisanId, router]);
  return <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>;
}
