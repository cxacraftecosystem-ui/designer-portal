"use client";

import { useParams } from "next/navigation";

import { SessionWorkspace } from "@/components/questionnaire/SessionWorkspace";
import { V2Gate } from "@/components/questionnaire/V2Gate";

export default function SessionWorkspacePage() {
  const params = useParams<{ id: string }>();
  return (
    <V2Gate>
      <SessionWorkspace id={decodeURIComponent(params.id)} />
    </V2Gate>
  );
}
