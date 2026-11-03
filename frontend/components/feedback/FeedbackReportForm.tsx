"use client";

import { useEffect, useId, useState } from "react";
import { usePathname } from "next/navigation";
import { MessageSquarePlus } from "lucide-react";

import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Select } from "@/components/FormControls";
import { apiFetch } from "@/lib/api";
import { handleFormEnter } from "@/lib/formNav";
import { captureClientContext, type FeedbackReport, type FeedbackVocabulary } from "@/lib/feedback";

const SUBJECT_MAX = 200;
const DETAILS_MAX = 5000;

export function FeedbackReportForm({
  vocabulary,
  onFiled,
  onDirtyChange,
  formRef
}: {
  vocabulary: FeedbackVocabulary;
  onFiled: (report: FeedbackReport) => void;
  onDirtyChange: (dirty: boolean) => void;
  formRef?: React.RefObject<HTMLFormElement | null>;
}) {
  const pathname = usePathname();
  const [generation, setGeneration] = useState(0);

  return (
    <FeedbackReportFormBody
      key={generation}
      vocabulary={vocabulary}
      pathname={pathname}
      formRef={formRef}
      onDirtyChange={onDirtyChange}
      onFiled={(report) => {
        onDirtyChange(false);
        setGeneration((n) => n + 1);
        onFiled(report);
      }}
    />
  );
}

function FeedbackReportFormBody({
  vocabulary,
  pathname,
  formRef,
  onFiled,
  onDirtyChange
}: {
  vocabulary: FeedbackVocabulary;
  pathname: string;
  formRef?: React.RefObject<HTMLFormElement | null>;
  onFiled: (report: FeedbackReport) => void;
  onDirtyChange: (dirty: boolean) => void;
}) {
  const reactId = useId();
  const [subject, setSubject] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const context = captureClientContext(pathname);

  function markDirty() {
    onDirtyChange(true);
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    const data = new FormData(event.currentTarget);
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const report = await apiFetch<FeedbackReport>("/feedback/reports", {
        method: "POST",
        body: JSON.stringify({
          kind: String(data.get("kind") ?? ""),
          severity: String(data.get("severity") ?? ""),
          area: String(data.get("area") ?? ""),
          subject: String(data.get("subject") ?? "").trim(),
          details: String(data.get("details") ?? "").trim(),
          ...context
        })
      });
      onFiled(report);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not send that report.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="grid gap-4" ref={formRef} onSubmit={submit} onInput={markDirty} onKeyDown={handleFormEnter}>
      <DictationUnavailableNotice />

      <div className="grid gap-3 md:grid-cols-3">
        <FieldBlock label="What is this?" required>
          <Select name="kind" required searchable={false} onChange={markDirty} aria-label="What is this?">
            {vocabulary.kind.map((choice) => (
              <option key={choice.value} value={choice.value}>
                {choice.label}
              </option>
            ))}
          </Select>
        </FieldBlock>

        <FieldBlock label="How pressing is it?">
          <Select name="severity" searchable={false} onChange={markDirty} aria-label="How pressing is it?">
            <option value="">Not saying</option>
            {vocabulary.severity.map((choice) => (
              <option key={choice.value} value={choice.value}>
                {choice.label}
              </option>
            ))}
          </Select>
        </FieldBlock>

        <FieldBlock label="Which part of the app?">
          <Select name="area" searchable={false} onChange={markDirty} aria-label="Which part of the app?">
            <option value="">Not sure</option>
            {vocabulary.area.map((choice) => (
              <option key={choice.value} value={choice.value}>
                {choice.label}
              </option>
            ))}
          </Select>
        </FieldBlock>
      </div>

      <DictatedTextInput
        name="subject"
        label="In one line"
        value={subject}
        onChange={(next) => {
          setSubject(next);
          markDirty();
        }}
        maxLength={SUBJECT_MAX}
        placeholder="The photo upload stops at 90%"
        required
        explainWhenUnavailable={false}
      />

      <DictatedTextArea
        name="details"
        label="What happened, and what you expected"
        rows={6}
        maxLength={DETAILS_MAX}
        required
        explainWhenUnavailable={false}
        onDirty={markDirty}
        helper="For a bug, the steps that led to it. For a grievance, what you would like done."
      />

      {error ? (
        <div className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800" role="alert">
          {error}
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-3">
        <button className="field-button" disabled={busy} type="submit">
          <MessageSquarePlus className="h-4 w-4" aria-hidden />
          {busy ? "Sending..." : "Send report"}
        </button>
        <p className="text-xs text-ink-500" id={`${reactId}-capture`}>
          Also sends your browser, app version and current screen.
        </p>
      </div>
    </form>
  );
}
