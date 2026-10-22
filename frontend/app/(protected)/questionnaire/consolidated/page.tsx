"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { qs } from "@/lib/questionnaire/qs";

/** The old consolidated index: each artisan's record is on their page now; the sessions list is here. */
export default function ConsolidatedIndexRedirectPage() {
  const router = useRouter();
  useEffect(() => {
    router.replace("/questionnaire");
  }, [router]);
  return <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>;
}
