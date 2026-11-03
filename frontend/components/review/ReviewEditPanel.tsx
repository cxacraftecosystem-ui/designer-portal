"use client";

import { useEffect, useId, useState } from "react";

import { apiFetch } from "@/lib/api";
import {
  HeldPostNotice,
  reviewEditHold,
  reviewRecordWorkshopIds,
  useHeldPostRefusals,
  writesHeld
} from "@/components/designworkshop/HeldPostNotice";
import {
  reviewEditableFields,
  reviewRecordEndpoint,
  reviewValuesFrom,
  type ReviewField
} from "@/components/review/reviewEditFields";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { INTERVIEW_LANGUAGE_PLACEHOLDER, interviewLanguageOptions } from "@/lib/interviewLanguages";
import { readableError } from "@/components/review/reviewErrors";
import { RequiredMark } from "@/components/ui/RequiredMark";
import { ActionGroup } from "@/components/ui/ActionGroup";

export function ReviewEditPanel({
  recordType,
  recordId,
  label,
  onSaved,
  onApproved,
  onClose
}: {
  recordType: string;
  recordId: string;
  label: string;
  onSaved: (message: string) => void;
  onApproved: (message: string) => void;
  onClose: () => void;
}) {
  const fields = reviewEditableFields(recordType);
  const [baseline, setBaseline] = useState<Record<string, string> | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [filing, setFiling] = useState<string[]>([]);

  useEffect(() => {
    let cancelled = false;
    const endpoint = reviewRecordEndpoint(recordType, recordId);
    if (!endpoint || fields.length === 0) {
      setError("This record can't be edited here. Open it from its own page to make changes.");
      return;
    }
    (async () => {
      try {
        const record = await apiFetch<unknown>(endpoint);
        if (cancelled) return;
        const loaded = reviewValuesFrom(record, fields);
        setFiling(reviewRecordWorkshopIds(recordType, record));
        setBaseline(loaded);
        setValues(loaded);
      } catch (err) {
        if (!cancelled) setError(readableError(err, `Unable to open ${label} for editing`));
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recordType, recordId]);

  const changedKeys = baseline
    ? fields
        .filter((field) => !field.readOnly)
        .map((field) => field.key)
        .filter((key) => (values[key] ?? "") !== (baseline[key] ?? ""))
    : [];
  const blanked = fields.filter((field) => field.required && changedKeys.includes(field.key) && !values[field.key]?.trim());

  const heldFor = useHeldPostRefusals(filing);
  const hold = baseline ? heldFor(filing) : null;
  const heldNotice = reviewEditHold(hold, recordType);
  const locked = busy || writesHeld(hold);
  const heldNoticeId = useId();

  async function submit(approve: boolean) {
    if (!baseline || changedKeys.length === 0 || blanked.length > 0 || writesHeld(hold)) return;
    setBusy(true);
    setError(null);
    setSaved(null);
    try {
      const changed = Object.fromEntries(changedKeys.map((key) => [key, values[key] ?? ""]));
      await apiFetch(`/review/${recordType}/${recordId}/edit`, {
        method: "POST",
        body: JSON.stringify({ fields: changed, note: note.trim() || null, approve })
      });
      const count = `${changedKeys.length} field${changedKeys.length === 1 ? "" : "s"}`;
      if (approve) {
        onApproved(`Edited ${count} and approved "${label}".`);
        return;
      }
      const endpoint = reviewRecordEndpoint(recordType, recordId);
      if (endpoint) {
        const record = await apiFetch<unknown>(endpoint);
        const fresh = reviewValuesFrom(record, fields);
        setFiling(reviewRecordWorkshopIds(recordType, record));
        setBaseline(fresh);
        setValues(fresh);
      }
      setNote("");
      setSaved(`Saved ${count}. This record is still pending — approve it when you are ready.`);
      onSaved(`Edited ${count} on "${label}".`);
    } catch (err) {
      setError(readableError(err, "Unable to save this edit"));
    } finally {
      setBusy(false);
    }
  }

  function renderField(field: ReviewField) {
    const value = values[field.key] ?? "";
    const dirty = baseline ? value !== (baseline[field.key] ?? "") : false;
    const empty = field.required && dirty && !value.trim();
    const inputClass = `field-input ${dirty ? "border-purple-300" : ""}`;
    if (field.readOnly) {
      return (
        <label key={field.key} className="grid gap-1">
          <span className="field-label">{field.label}</span>
          <input className="field-input" value={value || "—"} readOnly disabled />
        </label>
      );
    }
    if (field.vocabulary === "interviewLanguage") {
      return (
        <FieldBlock
          key={field.key}
          label={field.label}
          required={field.required}
          hint={
            empty ? <span className="text-xs text-error-600">{field.label} cannot be left empty.</span> : undefined
          }
        >
          <Dropdown
            value={value}
            options={interviewLanguageOptions(value)}
            placeholder={INTERVIEW_LANGUAGE_PLACEHOLDER}
            disabled={locked}
            onChange={(next) => setValues((prev) => ({ ...prev, [field.key]: next }))}
            searchable={false}
          />
          {dirty ? <span className="text-xs font-semibold text-purple-700 dark:text-purple-300">changed</span> : null}
        </FieldBlock>
      );
    }
    return (
      <label key={field.key} className="grid gap-1">
        <span className="field-label">
          {field.label}
          <RequiredMark when={field.required} />
          {dirty ? <span className="ml-1.5 font-semibold normal-case text-purple-700 dark:text-purple-300">changed</span> : null}
        </span>
        {field.multiline ? (
          <textarea
            className={`${inputClass} min-h-20`}
            rows={3}
            value={value}
            disabled={locked}
            onChange={(event) => setValues((prev) => ({ ...prev, [field.key]: event.target.value }))}
          />
        ) : (
          <input
            className={inputClass}
            value={value}
            disabled={locked}
            onChange={(event) => setValues((prev) => ({ ...prev, [field.key]: event.target.value }))}
          />
        )}
        {empty ? <span className="text-xs text-error-600">{field.label} cannot be left empty.</span> : null}
      </label>
    );
  }

  const feedback = (
    <>
      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {saved ? (
        <div className="rounded-md border border-success-600/30 bg-success-100 px-3 py-2 text-sm text-success-600">{saved}</div>
      ) : null}
    </>
  );

  return (
    <div className="grid gap-3">
      <p className="text-xs text-ink-500">
        Fix values here instead of sending it back. Saving does <strong>not</strong>{" "}
        approve it; the edit is logged under your name.
      </p>
      <HeldPostNotice refusal={heldNotice} id={heldNoticeId} className="" sayPending />
      {!baseline ? (
        <>
          {feedback}
          {error ? null : <p className="text-sm text-ink-700">Loading {recordType}...</p>}
        </>
      ) : (
        <>
          <div className="grid gap-3 md:grid-cols-2">{fields.map(renderField)}</div>
          <label className="grid gap-1">
            <span className="field-label">Why? (optional, logged with the edit)</span>
            <input
              className="field-input"
              value={note}
              disabled={locked}
              onChange={(event) => setNote(event.target.value)}
            />
          </label>
          {feedback}
          <ActionGroup>
            <button
              type="button"
              className="field-button h-9 min-h-0 px-3 text-xs"
              disabled={locked || changedKeys.length === 0 || blanked.length > 0}
              aria-describedby={typeof heldNotice === "string" ? heldNoticeId : undefined}
              onClick={() => submit(false)}
            >
              {busy
                ? "Saving..."
                : changedKeys.length === 0
                  ? "No changes yet"
                  : `Save ${changedKeys.length} change${changedKeys.length === 1 ? "" : "s"}`}
            </button>
            <button
              type="button"
              className="field-button-secondary h-9 min-h-0 px-3 text-xs"
              disabled={locked || changedKeys.length === 0 || blanked.length > 0}
              aria-describedby={typeof heldNotice === "string" ? heldNoticeId : undefined}
              onClick={() => submit(true)}
            >
              Save and approve
            </button>
            <button type="button" className="field-button-secondary h-9 min-h-0 px-3 text-xs" disabled={busy} onClick={onClose}>
              Cancel
            </button>
          </ActionGroup>
        </>
      )}
    </div>
  );
}
