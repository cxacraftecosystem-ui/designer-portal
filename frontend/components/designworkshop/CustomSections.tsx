"use client";

/**
 * This workshop's own questions, answered on the stage they belong to.
 *
 * A THIN WRAPPER OVER `EntityForm`, AND DELIBERATELY NOT A SECOND RENDERER. `lib/customSections.ts`
 * turns one custom section into a synthetic SINGLETON `DwEntity`, and everything below hands that to
 * the form this app already has. What comes along for nothing: the tier split with ADVANCED behind a
 * counted disclosure, `help` and "Measured in X." wired through `aria-describedby`, a per-field error
 * with `role="alert"` and `aria-invalid`, full- versus half-width cells decided from the type, MONEY
 * read as a string so "1250.10" survives the round trip, and the twelve v1 types drawn by the same
 * controls a registry field of that type is drawn by. A second renderer would be a second answer to
 * every one of those, and the day the two disagreed a designer would meet one question that behaved
 * unlike every other question on the same screen.
 *
 * **`FieldInput`'S UNION WAS NOT WIDENED AND MUST NOT BE.** It switches on `field.type` over a closed
 * union, which is what makes the compiler point at that file the day a twenty-third registry type is
 * added. A custom type is not a registry type: it arrives as a string from a per-workshop definition
 * and may be one this build has never heard of, or — worse — one it knows perfectly well and must not
 * draw. So the adapter re-tokenises anything outside the twelve v1 types, and it lands in
 * `FieldInput`'s `default:` arm as a disabled box that names the type. See
 * `customFieldToDwField`.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * THREE THINGS THIS COMPONENT OWES THE DESIGNER, AND THE FAILURE EACH ONE PREVENTS.
 *
 * 1. **A RETIRED QUESTION WITH AN ANSWER IS STILL SHOWN, read-only and crossed through.** The server
 *    returns retired sections and fields on every read for exactly this reason, and its own docstring
 *    says why: a copy missing every answer given under a superseded wording makes two copies of one
 *    report disagree about the fieldwork, with nothing in either saying so. A form that hid them would
 *    show four questions above six answers with nothing explaining where the other two went — which is
 *    the argument `QuestionRow` makes in the questionnaire editor, verbatim, and it holds here.
 * 2. **"THIS BROWSER HAS NOT READ THE DEFINITION" IS SAID OUT LOUD, never drawn as "no questions".**
 *    There is no bundled floor for a per-workshop definition — an APK cannot ship a workshop that did
 *    not exist when it was built, and nor can this bundle — so the worst case is genuinely "no
 *    definition at all", and it has to be a stated state. Drawing nothing instead would tell a designer
 *    standing in a cluster that the workshop asks nothing of them, which is indistinguishable from the
 *    truth for most workshops and wrong for theirs.
 * 3. **A STALE DEFINITION SAYS SO.** `customSchemaVersion` is a content digest; when the copy this
 *    browser holds does not match the one the server validated the last save against, the questions on
 *    screen may not be the questions being asked. That is a sentence, not a silence.
 */

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

/**
 * One retired question and the answer standing against it.
 *
 * PRINTED AS TEXT AND NOT AS A DISABLED INPUT, which is a different decision from the unsupported-type
 * arm in `FieldInput` and right for a different reason. A disabled input says "this is a box you cannot
 * use"; a retired question is not a box at all any more — it is a line of the record. Drawing it as a
 * control would invite a designer to try to correct it, and correcting it is precisely what the supersede
 * rule exists to prevent: the wording is part of the evidence.
 */
function RetiredAnswer({ label, answer, replaced }: { label: string; answer: string; replaced: boolean }) {
  return (
    <li className="rounded-md border border-dashed border-line-200 bg-surface-50 p-3">
      <div className="flex items-start gap-2">
        <Archive className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        <div className="min-w-0">
          {/* The wording is struck through and the ANSWER is not: the question stopped being asked, the
              answer did not stop being true. */}
          <p className="text-sm text-ink-700 line-through decoration-ink-300">{label}</p>
          <p className="mt-1 break-words text-sm text-ink-900">{answer}</p>
          <p className="mt-1 text-xs leading-5 text-ink-500">
            {replaced
              ? "This question was reworded. Its original wording and the answer given under it are kept — an answer means what it meant when it was given."
              : "No longer asked. The answer recorded against it stays in the record and prints in the report."}
          </p>
        </div>
      </div>
    </li>
  );
}

/**
 * Every custom section of one stage, answered.
 *
 * `values` is the whole `_custom` bucket for the stage, flat and keyed by the designer's own field
 * keys — ONE bucket across every section of the stage, because that is the shape of the row it is
 * stored in. Field keys are unique across the whole workshop (the server enforces it, so that two
 * sections on one stage cannot write into one key of one container), which is what makes a single flat
 * bucket safe behind several forms.
 */
export function CustomSectionsForm({
  sections,
  values,
  onChange,
  onPatch,
  workshopId,
  stageKey,
  errors,
  disabled,
  /**
   * What this browser knows about the definition, and it is a THREE-state answer.
   *
   * "unknown" is not a failure code: it is the honest floor for a per-workshop definition, which has no
   * bundled copy anywhere. Collapsing it into "there are none" is the failure Android needed three
   * states to avoid — warning on both puts an apology on the majority of workshops, which is how a
   * designer learns to stop reading warnings.
   */
  source,
  /** True when the digest this browser holds is not the one the server last validated a save against. */
  stale,
  /**
   * The question a readiness link or a search sent the designer here for.
   *
   * THREADED THROUGH RATHER THAN LEFT OUT, because the readiness screen's address walk builds its links
   * against {@link customSectionEntityKey} — the same string {@link customSectionEntity} renders under —
   * and without this the link would land on the stage with nothing highlighted. That degradation is
   * survivable by design (an unresolvable focus costs a highlight, never an item), which is exactly why
   * it would never have been noticed as a bug.
   */
  focus
}: {
  sections: DwCustomSection[];
  values: DwEntryData;
  onChange: (key: string, value: DwValue) => void;
  onPatch: (values: Record<string, DwValue>) => void;
  workshopId: string;
  stageKey: string;
  /** The per-field messages the server filed under `_custom`, keyed by the designer's field key. */
  errors?: Record<string, string>;
  disabled?: boolean;
  source: "network" | "cache" | "unknown";
  stale?: boolean;
  focus?: StageFocus;
}) {
  /*
    MEMOISED ON THE SECTIONS OBJECT, whose identity `fetchCustomDefinition` preserves across a refresh
    that returns the same digest. Without both halves of that — the cache's identity rule and this
    memo — every keystroke in a neighbouring box would rebuild every synthetic entity, and `EntityForm`
    memoises its tier split on `entity`, so the split would rebuild too.
  */
  const entities = useMemo(() => sections.map((section) => ({ section, entity: customSectionEntity(section) })), [sections]);

  const retired = useMemo(
    () => sections.map((section) => ({ section, fields: retiredFields(section, values) })),
    [sections, values]
  );

  const hasRetired = retired.some((entry) => entry.fields.length > 0);
  const hasLive = entities.some((entry) => entry.entity.fields.length > 0);

  /*
    THE ROW-LEVEL REFUSAL IS HOISTED, BECAUSE `_custom` IS ONE ROW BEHIND SEVERAL FORMS.

    Every section below is handed the same `_custom` bucket, which is correct for field messages —
    each section draws the ones belonging to the boxes it renders. A refusal filed under `_row` names
    no box and belongs to the stored row itself, so passing it down would print one refusal once per
    section and read as three. It is said once here, and each section is handed the field messages
    alone. (2026-09-03)
  */
  const refusedRow = rowRefusal(errors);
  const fieldErrors = withoutRowRefusal(errors);

  if (source === "unknown") {
    return (
      <section className="panel p-4">
        {/* A save can be refused at the row on a stage whose questions this browser has never read —
            the refusal is about the stored row, not about a box — so it is said here too rather than
            being lost behind the early return. */}
        {refusedRow ? (
          <p
            role="alert"
            className="mb-3 rounded-md bg-error-100 px-3 py-2 text-sm font-medium leading-5 text-error-600"
          >
            {refusedRow}
          </p>
        ) : null}
        <div className="flex items-start gap-2 rounded-md bg-amber-100 p-3 text-amber-800">
          {/* Colour never carries this on its own — the icon is decorative and the sentence says it. */}
          <HelpCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          <p className="text-xs leading-5">
            This browser has not read this workshop&apos;s own questions, so none are shown below. That is not
            the same as the workshop having none: if the designer added questions to this stage, they are on
            the server and this device has not seen them yet. Open this stage once with a connection and they
            are kept here for every visit afterwards, including offline ones. Anything you fill in on the rest
            of this form is unaffected.
          </p>
        </div>
      </section>
    );
  }

  /*
    Nothing to say and nothing to hide: this workshop genuinely has no questions of its own at this stage,
    and a panel announcing that would be furniture on twenty-two stages of every workshop that never uses
    the feature.

    **`stale` IS CHECKED HERE AND NOT ONLY BELOW, and leaving it out was a silent gap.** A definition this
    browser read last week may not have HAD a section on this stage; if the designer has since added one,
    there is nothing here to draw and nothing to hang a warning off — so an early return on emptiness alone
    would show a form with no custom questions on a stage that is now asking three, with nothing on screen
    saying so. That is the silent-emptiness class of failure this repository keeps meeting, and the digest
    is the one thing that can see it.
  */
  // `!refusedRow` JOINS THE GUARD, or the one state that has nothing to draw would also be the state
  // that silently swallows a refusal: `strandedRefusals` treats `_custom` as always drawn, so a row
  // refusal returned from here would be counted in the sentence and printed nowhere.
  if (!hasLive && !hasRetired && !stale && !refusedRow) return null;

  return (
    <>
      {refusedRow ? (
        <p role="alert" className="rounded-md bg-error-100 px-3 py-2 text-sm font-medium leading-5 text-error-600">
          {refusedRow}
        </p>
      ) : null}

      {stale ? (
        <div className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          This workshop&apos;s own questions have been edited since this browser last read them, so what is
          shown here may not be what is being asked now — a question may have been added to this stage, or one
          on screen may have been reworded or retired. Reload this stage with a connection to pick up the
          current set. Answers already recorded are not affected either way.
        </div>
      ) : null}

      {source === "cache" && (hasLive || hasRetired) ? (
        // The same argument as the cached-registry banner above it: which question list a designer is
        // looking at is not a detail, and saying so is the difference between "this stage does not ask
        // for that" and "this browser has not been told about it".
        <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
          The questions below are the copy saved in this browser, because the server could not be reached.
          They are whatever was current the last time this laptop had a connection.
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
                /*
                  THE SERVER FILES EVERY CUSTOM COERCION FAILURE UNDER THE ONE RESERVED KEY, `_custom`,
                  keyed by the designer's field key — so the same map is handed to every section of the
                  stage and each one shows the messages for the fields it draws. It cannot collide with a
                  core field's errors, which are filed under the registry entity's key.
                */
                errors={fieldErrors}
                disabled={disabled}
                stageKey={stageKey}
                focus={focus}
              />
            ) : (
              // A section whose every live question was retired still prints its heading, or the answers
              // below it would sit under nothing.
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
                  {retiredHere.length === 1 ? "This question is" : "These questions are"} not asked any more.
                  What was recorded against {retiredHere.length === 1 ? "it" : "them"} is kept, counts towards
                  nothing, and prints in the report under the wording it was asked as.
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
