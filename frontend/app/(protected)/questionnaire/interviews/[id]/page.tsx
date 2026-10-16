"use client";

import { useParams } from "next/navigation";
import { ClipboardList } from "lucide-react";

import { ExistingMedia } from "@/components/media/ExistingMedia";
import { RecordFieldList, RecordViewPage } from "@/components/records/RecordView";
import { interviewFields } from "@/components/records/recordFields";
import { plainFromStoredRichText } from "@/components/richtext/storedRichText";
import type { QuestionnaireInterview } from "@/lib/types";

/**
 * One interview sitting, read-only: who was interviewed, where and when, every answer under its
 * question, and the recordings. It is the first per-record page an interview has had on the web —
 * until 2026-10-16 the only way to read one back was the edit form at `/questionnaire?edit=`, where
 * a stray keystroke in an answer box was a change to the record. Update (in the header) still opens
 * that form.
 *
 * Answers are flattened with `plainFromStoredRichText`: a formatted answer is stored as the JSON of a
 * document, and printing the stored string would put braces where the artisan's words belong.
 */
export default function InterviewViewPage() {
  const { id } = useParams<{ id: string }>();
  return (
    <RecordViewPage<QuestionnaireInterview>
      kind="interview"
      id={id}
      endpoint={`/questionnaire/interviews/${encodeURIComponent(id)}`}
      noun="interview"
      icon={<ClipboardList className="h-5 w-5" aria-hidden />}
      titleOf={(interview) => interview.title?.trim() || "Untitled interview"}
      collabType="questionnaire"
    >
      {(interview) => {
        const answers = (interview.responses ?? []).filter((response) => plainFromStoredRichText(response.answerText).trim());
        return (
          <>
            <RecordFieldList rows={interviewFields(interview)} />
            <section className="panel overflow-hidden" aria-label="Answers">
              <h2 className="border-b border-line-200 px-5 py-3 font-display text-lg font-bold text-ink-900">
                Answers <span className="text-sm font-medium text-ink-500">({answers.length})</span>
              </h2>
              {answers.length === 0 ? (
                <p className="px-5 py-4 text-sm text-ink-500">No answers have been recorded in this sitting yet.</p>
              ) : (
                <ol className="divide-y divide-line-200">
                  {answers.map((response) => (
                    <li key={response.id} className="grid gap-1 px-5 py-3">
                      <div className="text-sm font-semibold text-ink-900">{response.question?.prompt ?? "Question"}</div>
                      <div className="whitespace-pre-wrap wrap-break-word text-sm leading-6 text-ink-700">
                        {plainFromStoredRichText(response.answerText)}
                      </div>
                    </li>
                  ))}
                </ol>
              )}
            </section>
            <ExistingMedia linkedRecordType="questionnaire" linkedRecordId={interview.id} recordHold={null} title="Recordings and media" readOnly />
          </>
        );
      }}
    </RecordViewPage>
  );
}
