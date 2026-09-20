"use client";

/**
 * WHICH QUESTIONNAIRE AM I ANSWERING — the control this product did not have.
 *
 * ── THE COMPLAINT, AND WHY IT WAS RIGHT ────────────────────────────────────────────────────────
 *
 * Owner, 2026-09-20: *"there is no way to pick between multiple questionnaires like there is in
 * field repo app."* Measured before a line was written, that was exactly true, and for a structural
 * reason rather than an oversight:
 *
 *   • `/questionnaire` (SINGULAR) records against ONE global artisan instrument. `GET
 *     /questionnaire/sections` takes no id, no owner and no scope — there is literally one, seeded
 *     from `app/data/questionnaire_questions.json`.
 *   • `/questionnaires/[id]/answer` (PLURAL) records against a DESIGNER-AUTHORED form. Which form
 *     is decided by the URL path segment, and the only dropdown on that screen chooses the SITTING.
 *
 * So a designer who had built three of their own forms could reach them only by going back to the
 * list and clicking a different row, and nothing on either screen said the other screen existed.
 * The picker below is the missing move between them, and it is deliberately the SAME control in
 * both places, so "which instrument am I in" is answered the same way on both.
 *
 * ── WHAT IT DOES NOT DO, AND WHY NOT DOING IT IS THE CORRECT ANSWER ────────────────────────────
 *
 * It does not make `/questionnaire` record against a chosen custom form. That is not a UI change and
 * it must not be smuggled in as one — three things in the schema say so:
 *
 *   1. `QuestionnaireInterview` HAS NO POINTER to `Questionnaire`. There is no `questionnaireId`
 *      column and no relation (`schema.prisma`, the `QuestionnaireInterview` model).
 *   2. AND THE COLUMN ALONE WOULD NOT BE ENOUGH. `QuestionnaireResponse.questionId` is FK'd to the
 *      GLOBAL `QuestionnaireQuestion` with `onDelete: Restrict`, and `upsert_responses` 404s any
 *      question id it cannot find in that table. An interview can only ever answer global questions;
 *      answering a custom form means writing `QuestionnaireFormEntry`/`QuestionnaireFormAnswer`,
 *      which is what `/questionnaires/[id]/answer` already does.
 *   3. `QuestionnaireInterview.artisanSetKey` IS `@unique`, and `create_interview` FOLDS a submission
 *      into the existing row for that exact set of artisans. If one artisan set could be interviewed
 *      on two different instruments, the second submission would merge into the first — two
 *      instruments' answers on one row, silently. Re-keying that dedupe is a migration and a
 *      decision, not a dropdown — and the one time it HAS been re-keyed proves the size of it:
 *      migration `20260920120000_questionnaire_artisan_set_key_scoped` put the WORKSHOP inside the
 *      key so two workshops could interview the same artisans, and it took a recompute of every row,
 *      a new query parameter on `by-artisans`, and matching edits in three languages. The key carries
 *      no instrument and this picker must not pretend otherwise: within one workshop, one artisan set
 *      is still exactly one interview.
 *
 * So choosing a designer-authored form NAVIGATES to the screen that can actually record it. The
 * shared instrument stays exactly what it was, which is also why the questionnaire form contract and
 * `questionnaire-capture.spec.ts` are untouched by this.
 *
 * ── THE GATE, WHICH IS THE OTHER THING THAT MAKES THIS NON-OBVIOUS ─────────────────────────────
 *
 * The two screens have DIFFERENT audiences and `/questionnaire` is the wider one. It has no
 * `ROUTE_GUARDS` row at all and its nav entry is open to everyone signed in; every route under
 * `/api/questionnaires` begins with `_require_designer`. So a picker drawn unconditionally on
 * `/questionnaire` would 403 for exactly the volunteer and field-contributor accounts that page
 * exists to serve — a broken control in front of the people least able to tell it is not their
 * fault. `allowed` is how the caller states the entitlement, and this renders nothing without it.
 *
 * ── PAGING IS REAL EVEN THOUGH IT LOOKS LIKE IT IS NOT ─────────────────────────────────────────
 *
 * `GET /questionnaires` declares `pageSize` with `ge=1` and NO upper bound, and
 * `normalize_pagination` then clamps it to 100. Asking for 500 gets 100 and no warning. So this
 * walks pages the way Android's `QuestionnaireListing` already does, and — because a bound that is
 * never stated is the most repeated defect class in this repository — it SAYS SO on screen when the
 * walk hits its own ceiling rather than presenting a short list as a complete one.
 */

import { useCallback, useEffect, useRef, useState } from "react";

import { Dropdown } from "@/components/ui/Dropdown";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { listQuestionnaires, questionnaireKindLabel, type QFormSummary } from "@/lib/questionnaireForms";

/** The value that means "the one global artisan questionnaire", which has no id of its own. */
export const SHARED_INSTRUMENT = "shared";

/** Its name on screen. One string, so the two clients cannot word it differently. */
export const SHARED_INSTRUMENT_LABEL = "Artisan questionnaire (shared)";

/** The server clamps `pageSize` to 100; five pages is five hundred forms, and it is stated below. */
const PAGE_SIZE = 100;
const MAX_PAGES = 5;

export function InstrumentPicker({
  value,
  onChange,
  allowed,
  label = "Questionnaire",
  hint
}: {
  /** {@link SHARED_INSTRUMENT}, or a `Questionnaire` id. */
  value: string;
  /** Called with the same vocabulary. The caller owns what switching MEANS — see the header. */
  onChange: (next: string) => void;
  /**
   * Whether this account may call `GET /questionnaires` at all. Render nothing rather than a
   * control that 403s: see the gate paragraph in the header.
   */
  allowed: boolean;
  label?: string;
  hint?: React.ReactNode;
}) {
  const [forms, setForms] = useState<QFormSummary[]>([]);
  const [truncated, setTruncated] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // One walk per mount. Without this guard React 18's development double-invoke issues the whole
  // page walk twice, and every reader of the network tab reports it as a bug in the picker.
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
      // `pages` stopped the loop only if it was within the ceiling; otherwise the ceiling did, and
      // that is the case a reader has to be told about.
      setTruncated(pages > MAX_PAGES);
      setError(null);
    } catch (err) {
      /*
        A FAILED LIST IS NOT A LIST OF NONE. The dropdown keeps the shared instrument — which is
        always answerable and needs no request — and says why the rest is missing. Silently offering
        one option would tell a designer with nine forms that they have none.
      */
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
            // `hint` is secondary text that is MATCHED as well as shown, so typing "take interview"
            // finds this row. And it carries no `group`, which is what draws it first and ungrouped:
            // the shared instrument is not one of the designer's forms and must not be listed among
            // them. The whole confusion this control ends is that two different things are called
            // "the questionnaire".
            hint: "The one instrument every researcher answers, on Take interview"
          },
          ...forms.map((form) => ({
            value: form.id,
            label: form.title,
            group: "Your questionnaires",
            /*
              THE HINT IS `REFERENCE_MODELS["Questionnaire"]`'s LABEL/SUBLABEL PAIR, restated. A
              designer's list can contain a form they did not upload — an administrator publishes one
              with `isShared` — and a row that cannot say so reads as somebody else's work leaking
              in. The kind label comes from the SERVER so the two clients cannot word one value
              differently, and "Kind not stated" is a real answer rather than an error.
            */
            hint: `${form.isShared ? "Standard form · " : ""}${questionnaireKindLabel(form.kind)}${
              form.designWorkshopTitle ? ` · ${form.designWorkshopTitle}` : ""
            }`
          }))
        ]}
        ariaLabel={label}
        // The options are fetched records rather than a written-out vocabulary, which is exactly the
        // case `Dropdown.searchable` says to pass `true` for.
        searchable
        // This dropdown SWITCHES THE SCREEN it sits on rather than filling in a form field, so focus
        // must not jump onward to the next control — the same reason the sitting dropdown on
        // `/questionnaires/[id]/answer` sets it, and the same bug if it is dropped.
        advanceOnSelect={false}
        emptyLabel={loading ? "Loading your questionnaires..." : "You have not built a questionnaire yet"}
      />
      {error ? (
        <p role="alert" className="mt-1 text-xs leading-5 text-red-700">
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
