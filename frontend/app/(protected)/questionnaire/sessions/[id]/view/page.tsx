"use client";

import { useParams } from "next/navigation";

import { SessionReadView } from "@/components/questionnaire/SessionReadView";
import { V2Gate } from "@/components/questionnaire/V2Gate";
import { RecordCodeCard } from "@/components/RecordCode";

/**
 * A session, read-only, with the review controls for a reviewer (DESIGN §4.8), and — this portal's
 * own — the record code a designer prints and scans (`lib/workshopCodes.ts`, type `questionnaire`:
 * the record is the `QuestionnaireInterview` the session is).
 */
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
