"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { Dropdown } from "@/components/ui/Dropdown";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { listQuestionnaires, questionnaireKindLabel, type QFormSummary } from "@/lib/questionnaireForms";

export const SHARED_INSTRUMENT = "shared";

export const SHARED_INSTRUMENT_LABEL = "Artisan questionnaire (shared)";

const PAGE_SIZE = 100;
const MAX_PAGES = 5;

export function InstrumentPicker({
  value,
  onChange,
  allowed,
  label = "Questionnaire",
  hint
}: {
  value: string;
  onChange: (next: string) => void;
  allowed: boolean;
  label?: string;
  hint?: React.ReactNode;
}) {
  const [forms, setForms] = useState<QFormSummary[]>([]);
  const [truncated, setTruncated] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const walked = useRef(false);

  const walk = useCallback(async () => {
    setLoading(true);
    try {
      const collected: QFormSummary[] = [];
      let page = 1;
      let pages = 1;
      for (; page <= pages && page <= MAX_PAGES; page += 1) {
        const result = await listQuestionnaires({ page, pageSize: PAGE_SIZE, activeOnly: true });
        collected.push(...result.items);
        pages = result.pages;
      }
      setForms(collected);
      setTruncated(pages > MAX_PAGES);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load your questionnaires");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!allowed || walked.current) return;
    walked.current = true;
    void walk();
  }, [allowed, walk]);

  if (!allowed) return null;

  return (
    <FieldBlock label={label} hint={hint}>
      <Dropdown
        value={value}
        onChange={onChange}
        options={[
          {
            value: SHARED_INSTRUMENT,
            label: SHARED_INSTRUMENT_LABEL,
            hint: "The one instrument every researcher answers, on Take interview"
          },
          ...forms.map((form) => ({
            value: form.id,
            label: form.title,
            group: "Your questionnaires",
            hint: `${form.isShared ? "Standard form · " : ""}${questionnaireKindLabel(form.kind)}${
              form.designWorkshopTitle ? ` · ${form.designWorkshopTitle}` : ""
            }`
          }))
        ]}
        ariaLabel={label}
        searchable
        advanceOnSelect={false}
        emptyLabel={loading ? "Loading your questionnaires..." : "You have not built a questionnaire yet"}
      />
      {error ? (
        <p role="alert" className="mt-1 text-xs leading-5 text-red-700 dark:text-red-400">
          {error} — only the shared artisan questionnaire is listed.
        </p>
      ) : null}
      {truncated ? (
        <p className="mt-1 text-xs leading-5 text-ink-500">
          Showing the first {PAGE_SIZE * MAX_PAGES} questionnaires. Open My questionnaires to search
          the rest.
        </p>
      ) : null}
    </FieldBlock>
  );
}
