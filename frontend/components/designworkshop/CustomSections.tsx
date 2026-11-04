"use client";

import { useMemo } from "react";
import { Archive, HelpCircle } from "lucide-react";

import { EntityForm, rowRefusal, withoutRowRefusal } from "@/components/designworkshop/EntityForm";
import {
  customAnswerText,
  customSectionEntity,
  retiredFields,
  type DwCustomSection
} from "@/lib/customSections";
import type { DwEntryData, DwValue } from "@/lib/designWorkshops";
import type { StageFocus } from "@/lib/workshopSearch";

function RetiredAnswer({ label, answer, replaced }: { label: string; answer: string; replaced: boolean }) {
  return (
    <li className="rounded-md border border-dashed border-line-200 bg-surface-50 p-3">
      <div className="flex items-start gap-2">
        <Archive className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        <div className="min-w-0">
          <p className="text-sm text-ink-700 line-through decoration-ink-300">{label}</p>
          <p className="mt-1 wrap-break-word text-sm text-ink-900">{answer}</p>
          <p className="mt-1 text-xs leading-5 text-ink-500">
            {replaced
              ? "Reworded — the original wording and answer are kept."
              : "No longer asked. The answer is kept and still prints."}
          </p>
        </div>
      </div>
    </li>
  );
}

export function CustomSectionsForm({
  sections,
  values,
  onChange,
  onPatch,
  workshopId,
  stageKey,
  errors,
  disabled,
  source,
  stale,
  focus
}: {
  sections: DwCustomSection[];
  values: DwEntryData;
  onChange: (key: string, value: DwValue) => void;
  onPatch: (values: Record<string, DwValue>) => void;
  workshopId: string;
  stageKey: string;
  errors?: Record<string, string>;
  disabled?: boolean;
  source: "network" | "cache" | "unknown";
  stale?: boolean;
  focus?: StageFocus;
}) {
  const entities = useMemo(() => sections.map((section) => ({ section, entity: customSectionEntity(section) })), [sections]);

  const retired = useMemo(
    () => sections.map((section) => ({ section, fields: retiredFields(section, values) })),
    [sections, values]
  );

  const hasRetired = retired.some((entry) => entry.fields.length > 0);
  const hasLive = entities.some((entry) => entry.entity.fields.length > 0);

  const refusedRow = rowRefusal(errors);
  const fieldErrors = withoutRowRefusal(errors);

  if (source === "unknown") {
    return (
      <section className="panel p-4">
        {refusedRow ? (
          <p
            role="alert"
            className="mb-3 rounded-md bg-error-100 px-3 py-2 text-sm font-medium leading-5 text-red-800"
          >
            {refusedRow}
          </p>
        ) : null}
        <div className="flex items-start gap-2 rounded-md bg-amber-100 p-3 text-amber-800">
          <HelpCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          <p className="text-xs leading-5">
            Open this stage once online to load this workshop&apos;s own questions.
          </p>
        </div>
      </section>
    );
  }

  if (!hasLive && !hasRetired && !stale && !refusedRow) return null;

  return (
    <>
      {refusedRow ? (
        <p role="alert" className="rounded-md bg-error-100 px-3 py-2 text-sm font-medium leading-5 text-red-800">
          {refusedRow}
        </p>
      ) : null}

      {stale ? (
        <div className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          Questions have changed — reload online to update them.
        </div>
      ) : null}

      {source === "cache" && (hasLive || hasRetired) ? (
        <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
          Offline — showing saved questions.
        </div>
      ) : null}

      {entities.map(({ section, entity }) => {
        const retiredHere = retired.find((entry) => entry.section.key === section.key)?.fields ?? [];
        if (!entity.fields.length && !retiredHere.length) return null;
        return (
          <div key={section.key} className="grid gap-3">
            {entity.fields.length ? (
              <EntityForm
                entity={entity}
                data={values}
                onChange={onChange}
                onPatch={onPatch}
                workshopId={workshopId}
                errors={fieldErrors}
                disabled={disabled}
                stageKey={stageKey}
                focus={focus}
              />
            ) : (
              <section className="panel p-4">
                <h2 className="font-display text-lg font-bold text-ink-900">{section.title}</h2>
                {section.description ? (
                  <p className="mt-1 text-sm leading-6 text-ink-muted">{section.description}</p>
                ) : null}
              </section>
            )}

            {retiredHere.length ? (
              <section className="panel p-4">
                <h3 className="text-sm font-medium text-ink-900">
                  {section.retired ? "No longer asked" : "No longer asked in this section"}
                </h3>
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  No longer asked. Answers are kept and still print.
                </p>
                <ul className="mt-3 grid gap-2">
                  {retiredHere.map((field) => (
                    <RetiredAnswer
                      key={field.key}
                      label={field.label}
                      answer={customAnswerText(field, values[field.key])}
                      replaced={Boolean(field.supersededById)}
                    />
                  ))}
                </ul>
              </section>
            ) : null}
          </div>
        );
      })}
    </>
  );
}
