"use client";

/**
 * File a grievance, suggestion, recommendation or bug report.
 *
 * ── WHY THIS IS NOT THE RATING FORM WITH EXTRA BOXES ────────────────────────────────────────────
 *
 * `PUT /feedback/me` upserts ONE row per account. A person who reports a bug on Monday and files a
 * grievance on Friday overwrote the bug report, and there is no version of "more detailed and
 * exhaustive" that survives that: a register whose second entry destroys the first is not a
 * register. So this posts to `POST /feedback/reports`, which creates, and the rating form beside it
 * keeps upserting — a standing answer to "how is the app working for you" is genuinely one thing
 * per person, revisable as the answer changes.
 *
 * ── THE DICTATION BUTTON IS THE ON-DEVICE ONE, AND IT HAS TO BE ────────────────────────────────
 *
 * There are two dictation implementations in this client and only one of them can be used here.
 * `components/designworkshop/Dictation.tsx` is the server-backed one: it posts to
 * `/design-workshops/{id}/dictate`, which REQUIRES a workshop id and a granted dictation consent on
 * that workshop. A feedback report has no workshop and never will — it is about the software, not
 * about a place — so that component has no id to send and no consent to check, and there is nothing
 * to pass it. `OnDeviceDictationButton` (through the `DictatedTextInput` / `DictatedTextArea`
 * wrappers, which already compose it with a label) uses the browser's own Web Speech API and sends
 * nothing anywhere. That is also the better answer on the merits for this particular form: a person
 * dictating a grievance about a colleague should not have that audio leave their laptop, and the
 * structural guarantee that it cannot is asserted by reading source in
 * `e2e/record-form-dictation-unit.spec.ts` §1.
 *
 * ── WHAT IT ASKS AND WHAT IT TAKES ─────────────────────────────────────────────────────────────
 *
 * Asked: kind (required), severity, area, subject, details. Taken without asking: which app, which
 * version, which platform, which screen — see `captureClientContext`. The panel under the button
 * SAYS what is being taken, in one line, because a form that silently attaches a user-agent string
 * to a complaint is a form that took something. Terse, per the owner's standing instruction; the
 * reasoning lives here in the comment, not on screen.
 */

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

/** Matches the server's `max_length` on each column, so the browser refuses before the API does. */
const SUBJECT_MAX = 200;
const DETAILS_MAX = 5000;

export function FeedbackReportForm({
  vocabulary,
  onFiled,
  onDirtyChange,
  formRef
}: {
  /**
   * The served lists. REQUIRED, never optional with a fallback: `Select` seeds its uncontrolled
   * value from `options[0]` in a `useState` initialiser that runs ONCE, so a dropdown mounted
   * before its options arrive would keep an empty value for ever and the form would post no kind.
   * The page therefore holds the form back until the fetch lands — one small request, made once.
   */
  vocabulary: FeedbackVocabulary;
  /** Called with the created report, so the page can drop it into "Your reports" without refetching. */
  onFiled: (report: FeedbackReport) => void;
  /** Raised the moment anything is typed, and lowered on a successful send. Drives the leave guard. */
  onDirtyChange: (dirty: boolean) => void;
  /**
   * The page's handle on the `<form>` element, so the unsaved-changes dialog's "Save" can call
   * `requestSubmit()` on it.
   *
   * `requestSubmit()` AND NOT THE SUBMIT HANDLER DIRECTLY: it runs the browser's own constraint
   * validation first, so a report with no subject stops on the form with the field highlighted
   * instead of being posted and 422'd. That is what `UnsavedChangesDialog`'s own header promises
   * that button does ("a missing required field keeps them on the form"), and calling the handler
   * would quietly break the promise on this page alone.
   */
  formRef?: React.RefObject<HTMLFormElement | null>;
}) {
  const pathname = usePathname();
  /**
   * Bumped after a successful send, which REMOUNTS the whole form.
   *
   * A remount and not a `reset()`: `DictatedTextArea` owns its value internally (seeded from
   * `defaultValue`), so `formElement.reset()` would rewrite the DOM node and tell React nothing —
   * the previous report's text would repaint on the next render. `DictatedTextInput.tsx`'s own
   * header records that exact failure on `/questionnaire`. A key change destroys the state instead.
   */
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
  // `DictatedTextInput` is controlled by its caller (its sibling textarea is not) — see that
  // component's header for why the two differ. It still renders a real `name`, so `FormData` reads
  // this box like any other and the submit handler below does not have to know about it.
  const [subject, setSubject] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const context = captureClientContext(pathname);

  /**
   * A themed dropdown is a `<button>` and fires NO native input event (§12.1), so `onInput` on the
   * form never sees one. Every `Select` below therefore calls this by hand — the same obligation
   * every record form in this app carries, and the same way of discharging it.
   */
  function markDirty() {
    onDirtyChange(true);
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    // FIRST STATEMENT, before any await: React nulls `event.currentTarget` across an await, and a
    // form read after one is a form read as null.
    const data = new FormData(event.currentTarget);
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const report = await apiFetch<FeedbackReport>("/feedback/reports", {
        method: "POST",
        body: JSON.stringify({
          kind: String(data.get("kind") ?? ""),
          // "" is what the "Not saying" row submits, and the server reads empty as "not answered"
          // and stores NULL. Sending it through unchanged is deliberate: translating it to null
          // here would be a second place that decides what an unanswered dropdown means.
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
      {/*
        Said ONCE for the whole form rather than under each of the two boxes: both microphones
        disappear together on a browser with no recogniser (Firefox implements none), and two copies
        of the same paragraph a few centimetres apart is the reader learning to skip grey text. Each
        box below therefore passes `explainWhenUnavailable={false}`, which is the obligation this
        component discharges — see its own header.
      */}
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
          {/*
            The empty row is FIRST and reads as a real answer, not as a prompt. Severity is optional
            on purpose — nobody filing a grievance should have to rank their own distress on a
            four-point scale before the app will take it — and a list that opens on "Minor" would
            put a rating on every report by default.
          */}
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
        // Immediately above the buttons rather than pinned to the top: this form is tall enough that
        // a banner at the top of the page reads as a button that did nothing (§12.11, treatment 2).
        <div className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600" role="alert">
          {error}
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-3">
        <button className="field-button" disabled={busy} type="submit">
          <MessageSquarePlus className="h-4 w-4" aria-hidden />
          {busy ? "Sending..." : "Send report"}
        </button>
        {/*
          TERSE, AND IT NAMES WHAT IS TAKEN. The form attaches the browser, the build and the screen
          without asking, which is right — nobody should have to look up a user-agent string — but a
          form that attaches something to a complaint silently has taken it. One line, no paragraph.
          `id`/`aria-describedby` is not used here: this describes the SUBMIT, not a field, and the
          sentence is in reading order immediately beside it.
        */}
        <p className="text-xs text-ink-500" id={`${reactId}-capture`}>
          Sends your browser, build and current screen too.
        </p>
      </div>
    </form>
  );
}
