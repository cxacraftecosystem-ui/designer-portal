"use client";

import { useParams, useRouter } from "next/navigation";
import { useEffect } from "react";

import { qs } from "@/lib/questionnaire/qs";
import { consolidatedTarget } from "@/lib/questionnaireLinks";

export default function ConsolidatedRedirectPage() {
  const params = useParams<{ artisanId: string }>();
  const router = useRouter();
  useEffect(() => {
    router.replace(consolidatedTarget(decodeURIComponent(params.artisanId)));
  }, [params.artisanId, router]);
  return <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>;
}
