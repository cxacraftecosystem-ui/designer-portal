"use client";

import { useParams } from "next/navigation";
import { GitBranch } from "lucide-react";

import type { ProcessRecord } from "@/components/forms/ProcessForm";
import { ReadOnlyMediaGallery, RecordFieldList, RecordViewPage } from "@/components/records/RecordView";
import { processFields } from "@/components/records/recordFields";

/**
 * One documented process, read-only: its details, the pre-process clips, then every step in order
 * with that step's own captures. Update (in the header) opens the inline form on /processes.
 */
export default function ProcessViewPage() {
  const { id } = useParams<{ id: string }>();
  return (
    <RecordViewPage<ProcessRecord>
      kind="process"
      id={id}
      endpoint={`/processes/${encodeURIComponent(id)}`}
      noun="process record"
      icon={<GitBranch className="h-5 w-5" aria-hidden />}
      titleOf={(process) => process.name || "Process"}
      collabType="process"
    >
      {(process) => {
        const steps = [...(process.steps ?? [])].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
        return (
          <>
            <RecordFieldList rows={processFields(process)} />
            {process.media?.length ? (
              <section className="panel grid gap-3 p-4">
                <h2 className="font-display text-lg font-bold text-ink-900">Pre-process media</h2>
                <ReadOnlyMediaGallery title="Pre-process media" items={process.media} />
              </section>
            ) : null}
            <section className="panel grid gap-4 p-4" aria-label="Steps">
              <h2 className="font-display text-lg font-bold text-ink-900">Steps</h2>
              {steps.length === 0 ? (
                <p className="text-sm text-ink-500">No steps have been recorded for this process yet.</p>
              ) : (
                <ol className="grid gap-4">
                  {steps.map((step, index) => (
                    <li key={step.id} className="grid gap-2 rounded-md border border-line-200 p-3">
                      <div className="font-display font-semibold text-ink-900">
                        Step {index + 1} · {step.name}
                        <span className="ml-2 text-xs font-medium text-ink-500">
                          {step.stepType === "SEQUENTIAL" ? "Sequential" : "Group of activities"}
                        </span>
                      </div>
                      {step.notes ? <p className="whitespace-pre-line text-sm leading-6 text-ink-700">{step.notes}</p> : null}
                      <ReadOnlyMediaGallery title={`Step ${index + 1} media`} items={step.media ?? []} empty="No media for this step." />
                    </li>
                  ))}
                </ol>
              )}
            </section>
          </>
        );
      }}
    </RecordViewPage>
  );
}
