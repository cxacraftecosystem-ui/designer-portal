"use client";

import { useParams } from "next/navigation";

import { SessionReadView } from "@/components/questionnaire/SessionReadView";
import { V2Gate } from "@/components/questionnaire/V2Gate";
import { RecordCodeCard } from "@/components/RecordCode";

export default function SessionViewPage() {
  const params = useParams<{ id: string }>();
  const id = decodeURIComponent(params.id);
  return (
    <V2Gate>
      <div className="grid gap-4">
        <SessionReadView id={id} />
        <RecordCodeCard recordType="questionnaire" id={id} title="Session" />
      </div>
    </V2Gate>
  );
}
