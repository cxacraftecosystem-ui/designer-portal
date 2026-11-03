"use client";

import { useParams, useRouter } from "next/navigation";
import { useEffect } from "react";

import { qs } from "@/lib/questionnaire/qs";
import { interviewTarget } from "@/lib/questionnaireLinks";

export default function InterviewRedirectPage() {
  const params = useParams<{ id: string }>();
  const router = useRouter();
  useEffect(() => {
    router.replace(interviewTarget(decodeURIComponent(params.id)));
  }, [params.id, router]);
  return <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>;
}
