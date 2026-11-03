"use client";

import { KeyRound } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { WorkshopAccessRequestPanel } from "@/components/settings/WorkshopAccessRequestPanel";

export default function WorkshopAccessRequestPage() {
  return (
    <>
      <PageHeader
        title="Workshop access"
        description="Ask to work in a workshop, and follow what happened to every request you have made."
        icon={<KeyRound className="h-5 w-5" aria-hidden />}
      />
      <WorkshopAccessRequestPanel />
    </>
  );
}
