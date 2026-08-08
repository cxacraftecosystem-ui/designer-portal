"use client";

/**
 * ONE component that renders any field descriptor the registry can hand it.
 *
 * There is no per-stage form code in this app and there must never be. `stage_definitions.py`
 * declares 22 stages, 43 entities and 496 typed fields; this file has one branch per `FieldType`
 * and nothing else knows what a "sketch number" or a "warp count" is. A field added to the registry
 * therefore appears here — and on Android — without a line changing, which is the only arrangement
 * under which the capture form, the completeness gate and the report writer can still agree about a
 * field list two field seasons from now.
 *
 * THREE RULES THIS FILE OBEYS EVERYWHERE, each of which has already shipped as a bug elsewhere:
 *
 * - **No hardcoded neutral, ever.** Every grey goes through the themed `ink-*` / `line-200` /
 *   `surface-50` / `field-*` / `card` ladders, which invert under `data-theme="dark"`. A literal
 *   `border` class alone resolves to preflight's `#e5e7eb`, and a bare `ring-2` to preflight's
 *   BLUE — both look right in light mode and wrong the moment a designer switches the theme in a
 *   dim room, which is where this app is used.
 * - **A `<label>` is never wrapped around a control that contains a button.** A label forwards a
 *   stray click to the first labelable descendant, which slams a themed dropdown shut after one
 *   pick, and it folds every named descendant into the accessible name, which is how a date field
 *   came to announce itself as "From Open calendar". Real inputs get `<label htmlFor>`; dropdowns,
 *   media pickers, tag boxes and the GEO card get a `<span className="field-label">` plus an
 *   explicit `aria-label`/`aria-labelledby`.
 * - **MONEY is a string on the wire.** It is seeded with `String()` and read with `Number()` behind
 *   `Number.isFinite`, through the helpers in `lib/designWorkshops`, never with a number round trip
 *   that would turn the server's "1250.10" into "1250.1".
 *
 * CAPTIONS. A field carrying `captionFor` is the caption OF that media field and is rendered by
 * this component directly underneath it, never as a separate input elsewhere in the form. A caption
 * box three fields away from its photo gets filled in about the wrong photo, and the report then
 * prints the mismatch with nothing on the page admitting it happened.
 */

import { useCallback, useEffect, useId, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { Paperclip, X } from "lucide-react";

import { DictationButton } from "@/components/designworkshop/Dictation";
import { IdentityCardReader } from "@/components/designworkshop/IdentityCardReader";
import { RichTextEditor } from "@/components/designworkshop/RichTextEditor";
import { deriveValue, isDerived } from "@/lib/derivedFields";
import {
  dateRangePartner,
  identityNumberField,
  measurableLengthFields,
  offersIdentityOcr,
  offersPhotoMeasure,
  offersSignaturePad,
  offersSketchRectify,
  sketchSourceFields
} from "@/components/designworkshop/stageFieldRoles";
import { PhotoMeasureField } from "@/components/designworkshop/PhotoMeasureField";
import { SketchRectifyField, type SketchSource } from "@/components/designworkshop/SketchRectifyField";
import { SignaturePad } from "@/components/SignaturePad";
import { StageGeoField } from "@/components/designworkshop/StageGeoField";
import { StageReferenceSelect } from "@/components/designworkshop/StageReferenceField";
import { DateField, TimeField } from "@/components/forms/DateTimeField";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import { apiFetch } from "@/lib/api";
import {
  inputValue,
  listValue,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwGeoValue,
  type DwValue
} from "@/lib/designWorkshops";
import {
  isLocalMediaRef,
  readLocalMedia,
  removeLocalMedia,
  stageLocalMedia,
  LOCAL_MEDIA_PREFIX
} from "@/lib/designWorkshopStore";
import {
  getServerStagingSnapshot,
  getStagingSnapshot,
  subscribeStaging,
  uploadMediaBatch
} from "@/lib/media";
import { isTransient } from "@/lib/offline";
import type { MediaFile, MediaType } from "@/lib/types";

/**
 * The record type media captured against a design workshop is linked by.
 *
 * A single literal, shared with the Android client's own upload calls, because `GET /media` filters
 * on this exact string: a second spelling would upload the photograph successfully and then be
 * unable to find it again, which reads to the designer as a file that vanished.
 */
export const DW_MEDIA_RECORD_TYPE = "designWorkshop";

/**
 * Provenance for everything a stage uploads: where the device was and when.
 *
 * Threaded down from the stage page rather than asked for here, because a stage has ONE place of
 * recording and stage 13 has eleven media fields. Eleven GPS cards would ask the same question
 * eleven times and then disagree about the answer.
 */
export type StageCaptureContext = {
  location?: unknown;
  recordedAt?: string;
  recordedTimezone?: string;
  /** The same fix as a coordinate, so a GEO field can offer to copy it in one press. */
  point?: DwGeoValue | null;
};

export type FieldInputProps = {
  field: DwField;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  /**
   * The entity this field belongs to, and the whole record it sits on.
   *
   * Three things need more than the field descriptor and none of them can be faked from it: the
   * cascade reads a SIBLING field's value (`refFilterBy`), hydration writes SIBLING fields when a
   * reference is chosen, and the identity-card reader has to find the box its number belongs in. A
   * field that only ever knew about itself is why the picker used to be a dropdown of ids.
   */
  entity: DwEntity;
  row: DwEntryData;
  /**
   * Write several keys of this record in one commit.
   *
   * Hydration is a multi-key write by nature — the artisan's id, name, village, gender and phone
   * belong to one act — and applying them as five separate `onChange` calls would let a render
   * between any two of them see a row that names a record whose name it has not copied yet, which
   * is the state the report reads as "a reference to a deleted record".
   */
  onPatch: (values: Record<string, DwValue>) => void;
  /**
   * The workshop the value belongs to. Media uploaded from a field is linked to it, so the photo
   * survives as a findable record rather than as an orphan object nobody can list.
   */
  workshopId: string;
  /**
   * The caption field for a media field, already resolved by the caller from `captionFor`. Rendered
   * beneath the media control — see the file header for why it may not live anywhere else.
   */
  caption?: { field: DwField; value: DwValue | undefined; onChange: (value: DwValue) => void };
  /*
   * NOTE — THERE IS NO `refOptions` PROP HERE, AND NOTHING SHOULD ADD ONE BACK. The REF branch below
   * hands the field to `StageReferenceSelect`, which fetches and caches the options it needs. A
   * `refOptions` map used to be built by the stage page and threaded down through `EntityForm` to
   * here, where it was destructured and never read — up to five requests per stage open, one of them
   * the whole workshop, discarded before they reached a control.
   */

  disabled?: boolean;
  /** A per-field message from the save response's `errors` map. */
  error?: string | null;
  /**
   * Where in the workshop this field sits, so a file staged with no connection can say which stage,
   * which entity and which row it answers.
   *
   * Only media uses it, and only offline. It is carried on the descriptor in the local draft store
   * rather than derived later because a photograph whose field is unknown cannot be re-attached by
   * anything: the bytes survive and nobody can say what they were of.
   */
  place?: { stageKey?: string | null; entityKey?: string | null; rowKey?: string | null };
  /** Where and when this stage is being recorded. Stamped onto every file the field uploads. */
  capture?: StageCaptureContext;
};

/* ────────────────────────────────────────────────────────────────────────────
 * Shared chrome
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The unit and help line under a control, and the per-field message a save came back with.
 *
 * BOTH CARRY IDS, and the ids are what make them reach somebody who cannot see them. Until they did,
 * a designer using a screen reader pressed Save on a 30-field stage, the server refused four
 * answers, and the only thing that happened on their machine was that four paragraphs turned red
 * several hundred pixels apart. Nothing was announced, nothing was marked invalid, and tabbing back
 * through the form gave no hint which four boxes had been refused — the stage was unsubmittable and
 * unfixable at the same time. `field.help` had the same problem in the quieter direction: "Measured
 * in metres" was drawn under the box and belonged to nothing, so the one instruction that says what
 * unit to type was invisible to the reader most likely to need it.
 *
 * `role="alert"` on the error and not on the hint: the message appears in response to a Save the
 * designer just pressed, so interrupting is what they asked for. The hint is present from the start
 * and is reached through `aria-describedby` when focus lands on the control, which is the moment it
 * is useful. An alert on a hint would read the whole form aloud on arrival.
 */
/**
 * What a DERIVED field computes to right now, worded for a placeholder — or undefined when the box
 * is filled, the field is not derived, or the sources are not all there yet.
 *
 * ONE FUNCTION FOR BOTH NUMERIC BRANCHES, and the duplication it replaces was a live gap rather than
 * a tidiness point. The `INT | DECIMAL | PERCENT` branch carried this inline, and MONEY — a
 * completely separate control, because a money value is a STRING on the wire and a number input
 * would eat its trailing zero — carried nothing. Four of the registry's five derived fields are
 * MONEY (`prototypeCostLine.amount`, `costMaterialLine.amount`, `costLabourLine.amount`,
 * `costSheet.totalCost`), so a designer read "Derived as persons × days × rate" under a box that
 * stayed empty however carefully they filled the row — the exact promise `derived_kind` was added to
 * the registry to keep. The handset kept it: `FieldRenderer` hands `rowValues` to MONEY along with
 * the other three.
 *
 * THE WORDING IS ANDROID'S, character for character (`FieldRenderer.derivedHint`), unit and all. A
 * designer who checks an amount on the phone and again on a laptop must not have to work out whether
 * two differently-phrased lines are saying the same thing.
 */
function derivedPlaceholder(field: DwField, value: DwValue | undefined, row: DwEntryData): string | undefined {
  if (!isDerived(field)) return undefined;
  if (!(value === null || value === undefined || value === "")) return undefined;
  const computed = deriveValue(field, row);
  if (computed === null) return undefined;
  return `${computed}${field.unit ? ` ${field.unit}` : ""} (computed)`;
}

function FieldHint({
  field,
  error,
  hintId,
  errorId
}: {
  field: DwField;
  error?: string | null;
  hintId: string;
  errorId: string;
}) {
  const hint = [field.help, field.unit ? `Measured in ${field.unit}.` : ""].filter(Boolean).join(" ");
  return (
    <>
      {hint ? (
        <p id={hintId} className="text-xs leading-5 text-ink-500">
          {hint}
        </p>
      ) : null}
      {/* error-600 is one of the two literal status colours in the palette — it deliberately does
          not invert, because "this is wrong" must read identically in both themes. The word
          "Error:" is not added: `role="alert"` already announces it as one, and the message is
          written as a sentence a sighted reader reads unprefixed. */}
      {error ? (
        <p id={errorId} role="alert" className="text-xs font-medium leading-5 text-error-600">
          {error}
        </p>
      ) : null}
    </>
  );
}

/**
 * The label text.
 *
 * The asterisk is only ever drawn on a BASIC field, because `validate_registry` refuses any other
 * tier being required — but it is derived from `field.required` rather than from the tier, so a
 * registry change is reflected here rather than restated.
 */
function labelText(field: DwField): string {
  return field.required ? `${field.label} *` : field.label;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The component
 * ──────────────────────────────────────────────────────────────────────────── */

export function FieldInput({
  field,
  value,
  onChange,
  entity,
  row,
  onPatch,
  workshopId,
  caption,
  disabled,
  error,
  place,
  capture
}: FieldInputProps) {
  const controlId = useId();
  const labelId = `${controlId}-label`;
  const hintId = `${controlId}-hint`;
  const errorId = `${controlId}-error`;

  /**
   * What the control points at with `aria-describedby`, or nothing when there is nothing to say.
   *
   * Order matters and is the reading order on screen: the instruction first, then what went wrong
   * with the answer. An empty string here would be a describedby pointing at no element, which some
   * readers announce as a blank — worse than the attribute being absent.
   */
  const hasHint = Boolean(field.help || field.unit);
  const describedBy = [hasHint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ") || undefined;
  /** Undefined rather than `false`: a valid field says nothing, it does not announce its validity. */
  const invalid = error ? true : undefined;

  /**
   * Append a dictated phrase to whatever the box already holds.
   *
   * APPEND, never replace. The recogniser is stopped and started many times across a long answer,
   * and a commit that overwrote the field would delete the previous three sentences the moment
   * somebody paused for breath. The separator is a space unless the box already ends in one, so a
   * paragraph dictated in five goes does not come out as "…the warpis sized…".
   */
  const appendDictated = useCallback(
    (text: string) => {
      const existing = inputValue(value);
      const joiner = !existing || /\s$/.test(existing) ? "" : " ";
      onChange(`${existing}${joiner}${text}`);
    },
    [value, onChange]
  );

  /**
   * Wrapper for a control that IS a labelable element — the `<label htmlFor>` case.
   *
   * `min-w-0` is load-bearing on the wrapper: a grid item will not shrink below its content's
   * intrinsic width unless told to, so a long unbroken value (a URL, a pasted transcript, a 40-word
   * enum label) widens its column and spills over the field beside it. `truncate` cannot save it —
   * truncation clips inside a box that has already grown.
   */
  const labelled = (control: React.ReactNode) => (
    <div className="grid min-w-0 gap-1">
      <label className="field-label" htmlFor={controlId}>
        {labelText(field)}
      </label>
      {control}
      <FieldHint field={field} error={error} hintId={hintId} errorId={errorId} />
    </div>
  );

  /** Wrapper for a control that contains a button — see the `<label>` rule in the file header. */
  const unlabelled = (control: React.ReactNode) => (
    <div className="grid min-w-0 gap-1">
      <span className="field-label" id={labelId}>
        {labelText(field)}
      </span>
      {control}
      <FieldHint field={field} error={error} hintId={hintId} errorId={errorId} />
    </div>
  );

  switch (field.type) {
    case "TEXT":
    case "URL":
    case "PHONE":
    case "EMAIL":
      return labelled(
        <>
          <input
            id={controlId}
            className="field-input"
            // `type` carries the mobile keyboard as much as the validation: a phone field that opens
            // the alphabetic keyboard is a phone field a designer mistypes on a handset in a
            // courtyard. `url`/`email` also give the browser its own inline validation for free.
            type={field.type === "URL" ? "url" : field.type === "EMAIL" ? "email" : field.type === "PHONE" ? "tel" : "text"}
            inputMode={field.type === "PHONE" ? "tel" : undefined}
            maxLength={field.maxLength || undefined}
            aria-describedby={describedBy}
            aria-invalid={invalid}
            value={inputValue(value)}
            disabled={disabled}
            onChange={(event) => onChange(event.target.value)}
          />
          {/* Prose only. A speech recogniser hands back words, so dictating into a URL, an email
              address or a phone number produces "double you double you double you dot" and a
              designer who has to delete it — the button is a cost on those three, not a help. */}
          {field.type === "TEXT" ? (
            <DictationButton onCommit={appendDictated} disabled={disabled} fieldLabel={field.label} />
          ) : null}
        </>
      );

    case "LONG_TEXT":
      return labelled(
        <>
          <textarea
            id={controlId}
            className="field-input min-h-24"
            maxLength={field.maxLength || undefined}
            aria-describedby={describedBy}
            aria-invalid={invalid}
            value={inputValue(value)}
            disabled={disabled}
            onChange={(event) => onChange(event.target.value)}
          />
          {/* The narrative fields are the ones that come back empty from the field, because nobody
              types four hundred words on a handset while holding a swatch. This is the whole reason
              the dictation control exists. */}
          <DictationButton onCommit={appendDictated} disabled={disabled} fieldLabel={field.label} />
        </>
      );

    case "RICH_TEXT":
      /*
       * `unlabelled`, and it is not a stylistic choice. The editor's contextual toolbar is a row of
       * twenty-odd buttons: a wrapping `<label>` would forward a stray click into the first of them
       * AND fold every one of their names into the field's accessible name, so the field would
       * announce itself as "Introduction Bold Italic Underline Strikethrough…". The `field-label`
       * span names it once, through `aria-labelledby`.
       *
       * WHAT GOES DOWN is the stored value untouched — which may be `{"blocks":[…]}`, or a plain
       * STRING for a field the registry has just promoted from LONG_TEXT. `fromStored` reads a
       * string as unformatted prose, exactly as `rich_text.from_json` does, so a promotion does not
       * blank the paragraphs a designer wrote last season.
       *
       * WHAT COMES BACK is the document, or `null` when it holds no text — the same thing
       * `coerce_value` would have stored for an empty editor, which is what keeps the round trip
       * stable and the caret still (see point 5 of the editor's file header).
       *
       * NO DICTATION BUTTON *ON THIS BRANCH*, and still deliberately — but the editor now carries
       * one of its own. `appendDictated` above commits a STRING, and a string written into a
       * RICH_TEXT field is read by the server as unformatted prose, so routing dictation through
       * it would flatten every heading, list and bold run already in the field. The fix was always
       * to insert into the document model at the caret, which is the editor's job; `RichTextEditor`
       * does exactly that beside its word count, through the same `insertText` a keystroke uses.
       * Adding a second button here would give a narrative field two, one of which destroys work.
       */
      return unlabelled(
        <RichTextEditor
          value={value}
          onChange={(next) => onChange(next)}
          disabled={disabled}
          ariaLabelledBy={labelId}
          ariaLabel={field.label}
          maxLength={field.maxLength || undefined}
          // A BULLETS field IS a list — its help says "One deliverable per line" and the report
          // prints it as one — so the editor opens inside a numbered item rather than making the
          // designer type "1. " to get the behaviour the label already promised them.
          listKind={field.reportRole === "BULLETS" ? "ORDERED_ITEM" : undefined}
          /*
            WHERE A PHOTOGRAPH PLACED INSIDE THIS PROSE IS FILED.

            The same record type and record id every other upload on this stage uses, so an inline
            picture is an ordinary `MediaFile` row of this workshop — listed by `GET /media`,
            loadable by the report's `MediaResolver`, and safe from the orphan sweeper. Passing it
            as a pair keeps the editor ignorant of design workshops; without it the editor shows no
            picture button at all, which is the honest state for a caller that cannot say which
            record an upload belongs to.
          */
          upload={{ recordType: DW_MEDIA_RECORD_TYPE, recordId: workshopId }}
        />
      );

    case "INT":
    case "DECIMAL":
    case "PERCENT":
      /*
        A DERIVED FIELD SHOWS ITS ANSWER WHILE THE BOX IS STILL EMPTY.

        `durationDays` has always said "Leave blank to derive it from the start and end dates" and
        nothing derived it — the box stayed empty and so did the cover page. Now the computed value
        appears as the placeholder the moment both dates are present, so the designer can check it
        against the sanction order in front of them instead of saving to find out.

        PLACEHOLDER AND NOT A WRITTEN VALUE, deliberately. Writing it into the field would make the
        box look filled, and the designer could no longer clear it to go back to "derive this for
        me" — they would have to remember the rule and retype it. Blank still means derived, the
        server computes the same value from the same declaration on save, and typing over it is
        still how you override.
      */
      return labelled(
        <input
          id={controlId}
          className="field-input"
          type="number"
          placeholder={derivedPlaceholder(field, value, row)}
          // A DECIMAL with step=1 is a spinner that refuses 12.5 and a browser that reports the
          // field invalid on a perfectly good answer; "any" is the only correct step for a
          // non-integer. INT keeps 1 so the arrows do what an integer field promises.
          step={field.type === "INT" ? 1 : "any"}
          min={field.minValue ?? undefined}
          max={field.maxValue ?? (field.type === "PERCENT" ? 100 : undefined)}
          aria-describedby={describedBy}
          aria-invalid={invalid}
          value={inputValue(value)}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value === "" ? null : event.target.value)}
        />
      );

    case "MONEY":
      return labelled(
        <div className="relative">
          {/* The rupee sign is decoration on a control that already declares its unit; a screen
              reader announcing "rupee" before every amount in a 40-row cost sheet is noise. */}
          <span aria-hidden className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-sm text-ink-500">
            ₹
          </span>
          <input
            id={controlId}
            className="field-input pl-7"
            // NOT type="number". The stored value is a STRING with two decimals preserved by the
            // server; a number input normalises "1250.10" to "1250.1" the moment it is focused,
            // and the trailing zero the API took care to keep is gone from the cost sheet.
            type="text"
            inputMode="decimal"
            // A DERIVED AMOUNT SHOWS ITSELF HERE TOO — see derivedPlaceholder for why this branch
            // went without one and what it cost. Placeholder and not a written value, for the reason
            // written there: blank still means "derive this for me".
            placeholder={derivedPlaceholder(field, value, row)}
            aria-describedby={describedBy}
            aria-invalid={invalid}
            value={inputValue(value)}
            disabled={disabled}
            onChange={(event) => onChange(event.target.value === "" ? null : event.target.value)}
          />
        </div>
      );

    case "DATE": {
      /*
       * The app's own field, NOT `<input type="date">`, and the reason is ambiguity rather than
       * appearance.
       *
       * A native date input renders whatever the BROWSER's locale says: the same box shows dd/mm/yyyy
       * to a designer on a handset set to en-IN and mm/dd/yyyy to the reviewer on a laptop set to
       * en-US. The wire value is `yyyy-mm-dd` either way, so nothing ever errors — a workshop logged
       * as 02/03/2026 is simply February to one person and March to the other, and the report prints
       * whichever reading the box happened to offer on the day. There is no field season in which that
       * is recoverable, because the two readings are both perfectly plausible dates.
       *
       * `DateField` fixes the display to dd/mm/yyyy on every browser, accepts the same typed forms
       * everywhere, and puts a calendar grid beside the box so the month is picked by NAME rather than
       * inferred from a number. It also inherits the theme, which the native control does not — a
       * white box on the dark page a designer is using in a dim room is the other half of the report.
       *
       * `required` is deliberately NOT passed, exactly as it is not passed on TEXT or INT above.
       * Completeness is judged by `stage_completeness` when a report is generated, not by the browser
       * at save time; a native constraint here would refuse to save a half-filled stage, which is the
       * normal state of a stage being filled in over three days.
       *
       * THE BOUND BELOW IS THE OTHER HALF OF THE SAME BUG. Several stages declare a range as two
       * ordinary DATE fields — `startDate`/`endDate`, `surveyStartDate`/`surveyEndDate`,
       * `startDate`/`completedDate` — and two independent boxes cannot know about one another, so an
       * end date a month BEFORE its start saves without a word from anywhere: both are valid dates and
       * no validator on either side looks at the pair. What is printed is a workshop that finished
       * before it began, on the cover of a DCH report. Reading the SIBLING'S value off `row` and
       * handing it to `DateField` as `min`/`max` is what closes it — the calendar greys out the
       * impossible half of the month and a typed date outside it is not committed. It is the same
       * sibling read the REF cascade does, and for the same reason: the pair is the unit of meaning,
       * not either field alone.
       *
       * An EMPTY partner deliberately imposes no bound. A stage is filled in over three days and the
       * end is very often typed before the start is known; refusing the only date the designer
       * currently has would be the completeness gate all over again, in the one place it was never
       * meant to run.
       */
      const range = dateRangePartner(entity, field);
      const partnerIso = range ? inputValue(row[range.partner.key]).slice(0, 10) : "";
      return labelled(
        <DateField
          id={controlId}
          value={inputValue(value).slice(0, 10)}
          min={range?.role === "end" ? partnerIso || undefined : undefined}
          max={range?.role === "start" ? partnerIso || undefined : undefined}
          describedBy={describedBy}
          invalid={Boolean(error)}
          disabled={disabled}
          onChange={(iso) => onChange(iso || null)}
        />
      );
    }

    case "TIME":
      /*
       * Same argument, one step smaller: a native time input picks 12- or 24-hour presentation from
       * the browser locale, so "07:30" is read back as half past seven in the morning by one designer
       * and typed in as half past seven in the evening by another. `TimeField` shows both readings at
       * once — the 24-hour value and its am/pm reading side by side in the list — so the two cannot be
       * confused, and it stores the same `HH:mm` the native control did.
       */
      return labelled(
        <TimeField
          id={controlId}
          value={inputValue(value).slice(0, 5)}
          describedBy={describedBy}
          invalid={Boolean(error)}
          disabled={disabled}
          onChange={(hhmm) => onChange(hhmm || null)}
        />
      );

    case "BOOL":
      return unlabelled(
        <BoolField
          labelId={labelId}
          describedBy={describedBy}
          value={value}
          onChange={onChange}
          disabled={disabled}
          required={field.required}
        />
      );

    case "ENUM":
      return unlabelled(
        <Dropdown
          value={inputValue(value)}
          onChange={(next) => onChange(next || null)}
          options={field.options ?? []}
          placeholder="Select"
          disabled={disabled}
          ariaLabel={field.label}
          describedBy={describedBy}
        />
      );

    case "MULTI_ENUM":
      return unlabelled(
        <MultiSelectDropdown
          values={listValue(value)}
          onChange={(next) => onChange(next)}
          options={field.options ?? []}
          placeholder="Select"
          emptyLabel="No options in this list"
          disabled={disabled}
          ariaLabel={field.label}
          describedBy={describedBy}
          confirmLabel="Confirm"
        />
      );

    case "TAGS":
      return unlabelled(
        <TagsField labelId={labelId} describedBy={describedBy} value={value} onChange={onChange} disabled={disabled} />
      );

    case "GEO":
      return unlabelled(
        <StageGeoField
          labelId={labelId}
          value={value}
          onChange={onChange}
          disabled={disabled}
          recordingPlace={capture?.point ?? null}
        />
      );

    case "REF": {
      if (!field.refModel) {
        // A REF with no model names nothing, so there is no list to draw and no cascade to run. It
        // stays a text box rather than becoming an empty dropdown: a closed list with no members
        // cannot be answered at all, and on a BASIC/required field that makes the stage permanently
        // unsubmittable — the same shape as the offline state-list bug that had to stand a required
        // district field down rather than block a whole interview.
        return labelled(
          <>
            <input
              id={controlId}
              className="field-input"
              type="text"
              // The standing instruction below is part of what this box means, so it is named here
              // alongside the registry's own hint rather than being left as prose only a sighted
              // reader can connect to the field.
              aria-describedby={[`${controlId}-manual`, describedBy].filter(Boolean).join(" ")}
              aria-invalid={invalid}
              value={inputValue(value)}
              disabled={disabled}
              onChange={(event) => onChange(event.target.value || null)}
            />
            <p id={`${controlId}-manual`} className="text-xs leading-5 text-ink-500">
              Type the reference by hand.
            </p>
          </>
        );
      }
      return unlabelled(
        <StageReferenceSelect
          workshopId={workshopId}
          entity={entity}
          field={field}
          row={row}
          value={value}
          onChange={onChange}
          onPatch={onPatch}
          disabled={disabled}
          labelId={labelId}
        />
      );
    }

    case "IMAGE":
    case "IMAGE_LIST":
    case "FILE":
    case "AUDIO":
    case "VIDEO": {
      const identityTarget = offersIdentityOcr(entity, field) ? identityNumberField(entity) : null;
      // A signature field is drawn on, not photographed. The pad is ADDITIVE: the ordinary capture
      // card below it still takes a photograph of a paper sheet, which is the path for a designer
      // who cannot use a pad at all. See the header of SignaturePad for why it may never be the
      // only way in.
      const signature = offersSignaturePad(field);
      // Empty for every field on an entity that records no length, which is 38 of the registry's 43
      // — the panel is not rendered at all there rather than rendered with nowhere to propose into.
      const measureTargets = offersPhotoMeasure(entity, field) ? measurableLengthFields(entity) : [];
      /*
        The photographs a plate could be made FROM, read off the SIBLING image fields of this row.
        Empty on all but stage 11's `sketch` entity today.

        WHY THE SOURCES COME FROM ANOTHER FIELD AND THE PANEL LIVES ON THIS ONE. A single IMAGE field
        replaces its value when a file is attached to it (see `settle` below), so producing a plate
        into `sketch.image` would detach the original photograph — the outcome MEDIA_PIPELINE.md §5
        refuses. Rendering the panel on the FILE field that the registry already declares for a plate
        (`lineArtFile`, "An SVG or vector export, if one was produced") means `attach` writes exactly
        where a plate belongs and the photograph is never written to at all.
      */
      const sketchSources: SketchSource[] = offersSketchRectify(entity, field)
        ? sketchSourceFields(entity).flatMap((source) =>
            listValue(row[source.key]).map((ref) => ({ ref, fieldLabel: source.label }))
          )
        : [];
      return unlabelled(
        <>
          <MediaField
            labelId={labelId}
            describedBy={describedBy}
            field={field}
            value={value}
            onChange={onChange}
            workshopId={workshopId}
            disabled={disabled}
            place={place}
            capture={capture}
            /*
              THE EXTRAS ARE COMPOSED, NOT CHOSEN BETWEEN. This used to be a chain of ternaries
              picking exactly one, which was correct while the two were mutually exclusive — a field
              is a signature pad or a card photograph, never both. The measuring panel is not
              exclusive with anything: it is offered on EVERY image field of an entity that records a
              length, and stage 13's prototype photographs are also the entity's only images. A
              ternary would have silently suppressed whichever extra lost, on a field where both are
              legitimate, and nothing on screen would have said so.
            */
            extra={({ files, originals, attach, local }) => (
              <>
                {signature ? (
                  <SignaturePad
                    label={field.label}
                    disabled={disabled}
                    // Straight into the capture card's pending list, so the signature is staged,
                    // uploaded, retried and — with no connection — kept on the device exactly like
                    // a photograph. Nothing about a signature is special downstream of here.
                    onCapture={attach}
                  />
                ) : null}
                {identityTarget ? (
                  <IdentityCardReader
                    files={files}
                    originals={originals}
                    targetLabel={identityTarget.label}
                    currentValue={inputValue(row[identityTarget.key])}
                    disabled={disabled}
                    // The ONLY write in the whole OCR path, and it happens because a person read
                    // the candidate against the card in their hand and pressed Confirm. See the
                    // header of IdentityCardReader for why nothing before this point may commit.
                    onConfirm={(digits) => onPatch({ [identityTarget.key]: digits })}
                  />
                ) : null}
                {measureTargets.length ? (
                  <PhotoMeasureField
                    // Uploaded photographs and ones still only on this device, in that order, both
                    // measurable. See the `local` note on MediaField's `extra` for why the second
                    // half is the half that matters in the field.
                    photos={[
                      ...files
                        .filter((media) => media.mediaType === "IMAGE" && media.url)
                        .map((media) => ({ key: media.id, name: media.originalFilename, url: media.url as string })),
                      ...local
                    ]}
                    targets={measureTargets}
                    row={row}
                    disabled={disabled}
                    // The one write, and only from a button. Routed through `onPatch` for the same
                    // reason the card reader is: a proposal accepted is one act, and a render between
                    // two separate writes would show a row half-updated.
                    onPropose={(key, proposed) => onPatch({ [key]: proposed })}
                  />
                ) : null}
                {sketchSources.length ? (
                  <SketchRectifyField
                    sources={sketchSources}
                    targetLabel={field.label}
                    disabled={disabled}
                    // Straight into THIS field's pending list — the same door a camera photograph
                    // goes in by, so eager pre-upload, retry and the offline draft store all already
                    // apply. The sketch photograph is in a different field and is not touched.
                    onAttach={attach}
                  />
                ) : null}
              </>
            )}
          />
          {/* The caption sits INSIDE the media block, indented under it, so the two cannot be read
              as two independent questions. See the file header. */}
          {caption ? (
            <div className="mt-2 border-l-2 border-line-200 pl-3">
              <label className="field-label" htmlFor={`${controlId}-caption`}>
                {labelText(caption.field)}
              </label>
              <input
                id={`${controlId}-caption`}
                className="field-input mt-1"
                type="text"
                maxLength={caption.field.maxLength || undefined}
                value={inputValue(caption.value)}
                disabled={disabled}
                onChange={(event) => caption.onChange(event.target.value)}
              />
            </div>
          ) : null}
        </>
      );
    }
  }
}

/* ────────────────────────────────────────────────────────────────────────────
 * BOOL
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Yes / No as two buttons rather than a checkbox.
 *
 * A checkbox has two states and this field has THREE: yes, no, and not yet answered. An unticked
 * checkbox is indistinguishable from "no", so `stage_completeness` would count an untouched field
 * as filled the moment the form rendered — a stage reading 100% complete that nobody had opened.
 * The third state is reachable through Clear, which appears only once there is something to clear.
 */
function BoolField({
  labelId,
  describedBy,
  value,
  onChange,
  disabled,
  required
}: {
  labelId: string;
  /** The field's hint and refusal message — see `FieldHint`. */
  describedBy?: string;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  disabled?: boolean;
  required: boolean;
}) {
  const current = typeof value === "boolean" ? value : null;
  const choice = (label: string, choiceValue: boolean) => {
    const active = current === choiceValue;
    return (
      <button
        type="button"
        // `aria-pressed` and not `aria-checked`: these are toggle buttons, not radios, and a radio
        // group would additionally have to answer arrow keys, which two buttons do not.
        aria-pressed={active}
        disabled={disabled}
        onClick={() => onChange(choiceValue)}
        className={
          active
            ? "inline-flex min-h-10 items-center justify-center rounded-md bg-purple-700 px-4 py-2 text-sm font-medium text-white transition"
            : "inline-flex min-h-10 items-center justify-center rounded-md border border-line-200 bg-card px-4 py-2 text-sm font-medium text-ink-900 transition hover:border-purple-300 hover:bg-purple-50"
        }
      >
        {label}
      </button>
    );
  };

  return (
    <div className="flex flex-wrap items-center gap-2" role="group" aria-labelledby={labelId} aria-describedby={describedBy}>
      {choice("Yes", true)}
      {choice("No", false)}
      {current !== null && !required ? (
        <button type="button" className="text-xs font-medium text-ink-500 underline" disabled={disabled} onClick={() => onChange(null)}>
          Clear
        </button>
      ) : null}
      {current === null ? <span className="text-xs text-ink-500">Not answered</span> : null}
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * TAGS
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A free-form list — no canonical list exists for these (the registry says so by using TAGS rather
 * than MULTI_ENUM), so this is a chip box rather than a dropdown.
 *
 * Enter AND comma both commit, and the commit also runs on blur. Committing on blur is the part
 * that matters: a designer who types "indigo" and then taps Save has typed a value the form would
 * otherwise throw away, and nothing on screen would say a word about it.
 */
function TagsField({
  labelId,
  describedBy,
  value,
  onChange,
  disabled
}: {
  labelId: string;
  /** The field's hint and refusal message — see `FieldHint`. */
  describedBy?: string;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  disabled?: boolean;
}) {
  const tags = listValue(value);
  const [draft, setDraft] = useState("");

  const commit = useCallback(
    (raw: string) => {
      const parts = raw
        .split(",")
        .map((part) => part.trim())
        .filter(Boolean);
      if (!parts.length) return;
      // De-duplicated case-insensitively: "Indigo" and "indigo" are one dye, and two chips for one
      // answer become two rows in a report table that is meant to summarise.
      const seen = new Set(tags.map((tag) => tag.toLowerCase()));
      const next = [...tags];
      for (const part of parts) {
        if (seen.has(part.toLowerCase())) continue;
        seen.add(part.toLowerCase());
        next.push(part);
      }
      if (next.length !== tags.length) onChange(next);
      setDraft("");
    },
    [tags, onChange]
  );

  return (
    <div className="grid gap-2">
      {tags.length ? (
        <ul className="flex flex-wrap gap-1.5">
          {tags.map((tag) => (
            <li key={tag}>
              <span className="inline-flex items-center gap-1 rounded-full bg-field-200 px-2.5 py-1 text-xs font-medium text-ink-900">
                {tag}
                <button
                  type="button"
                  aria-label={`Remove ${tag}`}
                  disabled={disabled}
                  onClick={() => onChange(tags.filter((item) => item !== tag))}
                  className="text-ink-500 transition hover:text-ink-900"
                >
                  <X className="h-3 w-3" aria-hidden />
                </button>
              </span>
            </li>
          ))}
        </ul>
      ) : null}
      <input
        className="field-input"
        type="text"
        aria-labelledby={labelId}
        aria-describedby={describedBy}
        placeholder="Type a value and press Enter"
        value={draft}
        disabled={disabled}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={() => commit(draft)}
        onKeyDown={(event) => {
          if (event.key !== "Enter" && event.key !== ",") return;
          // Enter inside a form otherwise submits it, and in this app it additionally walks focus
          // to the next field — either of which would end tag entry after one chip.
          event.preventDefault();
          event.stopPropagation();
          commit(draft);
        }}
      />
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * Media
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * What each registry media type will accept, in the shared capture card's own vocabulary.
 *
 * FILE is deliberately absent, which the card reads as "everything": it means "an attachment", and
 * narrowing it would refuse the scanned consent form somebody photographed rather than scanned.
 */
const ALLOWED_TYPES: Partial<Record<DwField["type"], MediaType[]>> = {
  IMAGE: ["IMAGE"],
  IMAGE_LIST: ["IMAGE"],
  AUDIO: ["AUDIO"],
  VIDEO: ["VIDEO"]
};

/**
 * A media field: IMAGE / IMAGE_LIST / FILE / AUDIO / VIDEO.
 *
 * WHAT IS STORED IS A MEDIA ID, never a URL. `media_resolver` on the server looks each id up in the
 * `MediaFile` table when the report is built, so a client that stored a presigned URL here would
 * write a value that expires — the report would render fine on the day and print an empty frame a
 * week later, which is precisely the class of failure this feature exists to prevent.
 *
 * Removing a file here removes the REFERENCE and deliberately does not delete the object. The same
 * photograph is legitimately referenced from several stages (a loom shot used as a process image
 * and again in the cluster background), and deleting the bytes because one stage stopped pointing
 * at them would empty the other stage with no warning anywhere.
 *
 * WITH NO CONNECTION THE BYTES GO INTO THE LOCAL DRAFT STORE INSTEAD, and the value becomes
 * `dwlocal:<id>` — a reference this feature resolves out of IndexedDB and no server can be handed
 * by accident. That copy is retained until the server acknowledges a media id for it; see the
 * header of `lib/designWorkshopStore.ts` for why "the request was sent" is not good enough.
 *
 * THE CAPTURE SURFACE IS THE APP'S OWN `MediaCaptureField`, not a bare `<input type="file">`, and
 * everything that comes with it was already written, tested and shipped on five other forms:
 * take-a-photo and record-video straight from the handset camera, an in-page audio recorder with a
 * live waveform that asks the browser what container it can actually produce (Safari records
 * `audio/mp4`, and a hardcoded `.webm` lies about the bytes), drag and drop, a tap-to-preview
 * lightbox, per-file byte progress with an independent retry, audio queued for transcription on the
 * way past — and EAGER PRE-UPLOAD, so the transfer overlaps the twenty minutes spent filling the
 * stage in rather than starting when the designer is waiting to leave.
 *
 * THE ORDER OF THE TWO STEPS BELOW IS LOAD-BEARING. `uploadMediaBatch` claims the eagerly-staged
 * objects SYNCHRONOUSLY, before its first await; only after that claim is made is the file removed
 * from the capture card's list. Reversed, the card's own effect would see the file leave, call
 * `discardStagedFile` on an object that is about to be linked, and delete the bytes out from under
 * the save. Claim first, then remove — the discard then finds nothing, which is exactly right.
 */
function MediaField({
  labelId,
  describedBy,
  field,
  value,
  onChange,
  workshopId,
  disabled,
  place,
  capture,
  extra
}: {
  labelId: string;
  /** The field's hint and refusal message — see `FieldHint`. */
  describedBy?: string;
  field: DwField;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  workshopId: string;
  disabled?: boolean;
  place?: { stageKey?: string | null; entityKey?: string | null; rowKey?: string | null };
  capture?: StageCaptureContext;
  /**
   * Rendered under the attached list — the identity-card reader, or the signature pad.
   *
   * `attach` is how an extra HANDS A FILE IT PRODUCED to this field, and it deliberately goes in at
   * the same door a camera photograph does: the capture card's pending list. Everything downstream
   * is then already solved and already tested — eager pre-upload, per-file progress and retry,
   * `uploadMediaBatch`, and the local draft store when there is no connection. An extra that
   * uploaded its own file would be a second upload path to keep working offline, in an app whose
   * whole point is working offline.
   */
  extra?: (context: {
    files: MediaFile[];
    originals: Record<string, File>;
    attach: (file: File) => void;
    /**
     * Photographs held on THIS DEVICE ONLY — a `dwlocal:` reference whose blob is in the draft store,
     * with the object URL this component already made for its own thumbnail.
     *
     * Here because an extra that can only see `files` can only work on photographs the server has
     * already acknowledged, which in a village is every photograph except the one just taken. The
     * measuring panel needs nothing but a displayable URL and the geometry of where somebody
     * pointed, so it works identically on both — and that is the difference between measuring a
     * prototype in the courtyard and measuring it next week from a desk.
     */
    local: { key: string; name: string; url: string }[];
  }) => React.ReactNode;
}) {
  const multiple = field.type === "IMAGE_LIST";
  const ids = useMemo(() => listValue(value), [value]);
  const [files, setFiles] = useState<Record<string, MediaFile>>({});
  /** Staged-on-this-device tiles: what to draw for a `dwlocal:` reference. */
  const [staged, setStaged] = useState<Record<string, { name: string; url: string | null; sizeBytes: number }>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /** What the capture card is holding right now: attached, transferring, or failed. */
  const [pending, setPending] = useState<File[]>([]);
  /** Files already handed to a link or a local stage, so the drain below cannot hand one over twice. */
  const claimedRef = useRef(new Set<File>());
  /**
   * The `File` each linked media id came from, kept for as long as this field is mounted.
   *
   * Only the identity-card reader uses it, and it uses it because the alternative is much worse:
   * reading the number back off the uploaded copy means fetching a presigned S3 URL from the page,
   * which needs a CORS rule nothing else in this app requires, and failing that way would look like
   * the reader being broken rather than like a missing bucket policy.
   *
   * STATE AND NOT A REF, even though nothing here re-renders on it changing. A ref read during
   * render is a value React did not schedule the render for, so the reader would mount holding an
   * empty map and stay that way until something else happened to re-render this field — which on a
   * page whose only other activity is typing means "until the designer types somewhere else". The
   * lint rule that refuses it is right.
   */
  const [originals, setOriginals] = useState<Record<string, File>>({});
  const staging = useSyncExternalStore(subscribeStaging, getStagingSnapshot, getServerStagingSnapshot);

  // Resolve each stored id to its filename and URL. Ids the effect has already resolved are not
  // re-fetched, so re-rendering the row (which happens on every keystroke in a sibling field) does
  // not fire one request per attachment per character typed.
  useEffect(() => {
    let cancelled = false;
    const unknown = ids.filter((id) => !isLocalMediaRef(id) && !files[id]);
    if (!unknown.length) return;
    (async () => {
      const resolved = await Promise.all(
        unknown.map(async (id) => {
          try {
            return [id, await apiFetch<MediaFile>(`/media/${id}`)] as const;
          } catch {
            // A media row the caller is not entitled to read, one deleted out from under this stage,
            // or simply no connection. The id stays in the value — dropping it would silently
            // rewrite research data on a page the designer only opened to read — and the tile below
            // says it cannot be shown rather than rendering a broken frame.
            return [id, null] as const;
          }
        })
      );
      if (cancelled) return;
      setFiles((current) => {
        const next = { ...current };
        for (const [id, file] of resolved) if (file) next[id] = file;
        return next;
      });
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ids.join(",")]);

  /**
   * Object URLs for the files still on this device.
   *
   * Created and revoked in ONE effect, which is what keeps them paired: an object URL revoked
   * anywhere other than the cleanup of the effect that made it either leaks for the life of the tab
   * or is revoked while an `<img>` is still reading it, and the second failure looks like a
   * photograph that vanished.
   */
  useEffect(() => {
    let cancelled = false;
    const created: string[] = [];
    const localRefs = ids.filter(isLocalMediaRef);
    if (!localRefs.length) {
      setStaged({});
      return;
    }
    (async () => {
      const tiles: Record<string, { name: string; url: string | null; sizeBytes: number }> = {};
      /**
       * References the sync pass has since turned into real media ids.
       *
       * The store rewrites the DRAFT when a file is confirmed, but this component's `value` came
       * from React state that predates the upload, so it can still be holding the local reference
       * with the blob already (correctly) released. Swapping it here is what stops the tile going
       * blank on the one photograph that actually made it — and the swap terminates, because the new
       * value has no local reference left to find.
       */
      const healed = new Map<string, string>();
      for (const ref of localRefs) {
        const media = await readLocalMedia(ref);
        if (!media) continue;
        if (media.remoteMediaId) {
          healed.set(ref, media.remoteMediaId);
          continue;
        }
        const url = media.blob && media.mimeType.startsWith("image/") ? URL.createObjectURL(media.blob) : null;
        if (url) created.push(url);
        tiles[ref] = { name: media.name, url, sizeBytes: media.sizeBytes };
      }
      if (cancelled) {
        created.forEach((url) => URL.revokeObjectURL(url));
        return;
      }
      setStaged(tiles);
      if (healed.size) onChange(multiple ? ids.map((id) => healed.get(id) ?? id) : (healed.get(ids[0]) ?? ids[0]));
    })();
    return () => {
      cancelled = true;
      created.forEach((url) => URL.revokeObjectURL(url));
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ids.join(",")]);

  /** Put the bytes in the local draft store and point the field at them. */
  async function stageOffline(chosen: File[]): Promise<void> {
    const refs: string[] = [];
    for (const file of chosen) {
      const { ref } = await stageLocalMedia(workshopId, file, {
        stageKey: place?.stageKey ?? null,
        entityKey: place?.entityKey ?? null,
        fieldKey: field.key,
        clientKey: place?.rowKey ?? null,
        caption: field.label
      });
      refs.push(ref);
    }
    onChange(multiple ? [...ids, ...refs] : refs[0]);
    setNotice(
      `${refs.length} file${refs.length === 1 ? " is" : "s are"} saved on this device only. ${refs.length === 1 ? "It uploads" : "They upload"} when the connection returns, and the copy here is kept until the server confirms it.`
    );
  }

  /**
   * Turn finished files into media ids, or — where there is no connection — into local references.
   *
   * `chosen` has already been claimed and removed from the capture card's list by the drain below,
   * so nothing here can be discarded out from under it.
   */
  const settle = useCallback(
    async (chosen: File[]) => {
      setProblem(null);
      setNotice(null);
      try {
        // Known-offline: do not burn a presign request and a stall timer to learn what the browser
        // already knows. The same shortcut `saveOrQueue` takes.
        if (typeof navigator !== "undefined" && navigator.onLine === false) {
          await stageOffline(chosen);
          return;
        }
        const { uploaded, failed } = await uploadMediaBatch({
          files: chosen,
          linkedRecordType: DW_MEDIA_RECORD_TYPE,
          linkedRecordId: workshopId,
          caption: field.label,
          // The stage's one answer to "where and when was this recorded", stamped on every file so a
          // photograph can be judged later. Undefined when the designer has not captured a place,
          // which is the honest state — an absent location is better than the desk it was uploaded
          // from being filed as the courtyard it was taken in.
          location: capture?.location,
          recordedAt: capture?.recordedAt,
          recordedTimezone: capture?.recordedTimezone
        });
        if (uploaded.length) {
          setFiles((current) => {
            const next = { ...current };
            for (const media of uploaded) next[media.id] = media;
            return next;
          });
          setOriginals((current) => {
            const next = { ...current };
            uploaded.forEach((media, index) => {
              const source = chosen[index];
              if (source) next[media.id] = source;
            });
            return next;
          });
          const uploadedIds = uploaded.map((media) => media.id);
          // Read through `listValue(value)` rather than the `ids` computed at render: two files
          // finishing in the same tick would otherwise each append to the same snapshot and the
          // first one would be silently lost.
          onChange(multiple ? [...listValue(value), ...uploadedIds] : uploadedIds[uploadedIds.length - 1]);
        }
        // `uploadMediaBatch` RESOLVES on a partly-failed batch and throws only when nothing landed at
        // all, so a caller that treats a resolved promise as success loses files without a word. Name
        // the ones that did not make it.
        if (failed.length) {
          setProblem(
            `${failed.length} of ${chosen.length} file(s) did not upload: ${failed.map((item) => item.name).join(", ")}. Attach those again.`
          );
        }
      } catch (err) {
        // A throw from `uploadMediaBatch` means NOTHING landed. If the request never reached a server
        // the file is not lost — it goes on this device and the sync pass carries it. Only a refusal
        // the server actually made is reported as a failure, because replaying one of those forever
        // would hide the real problem behind a queue that never drains.
        if (isTransient(err)) {
          try {
            await stageOffline(chosen);
            return;
          } catch (stageError) {
            setProblem(
              stageError instanceof Error
                ? `The upload failed and this browser would not keep a copy either: ${stageError.message}`
                : "The upload failed and this browser would not keep a copy either."
            );
            return;
          }
        }
        setProblem(err instanceof Error ? err.message : "The upload failed.");
      }
    },
    // `stageOffline` closes over the render's `ids` and is recreated every render by design; it is
    // deliberately not a dependency, because listing it would rebuild `settle` on every keystroke in
    // a sibling field and re-run the drain effect below with it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [workshopId, field.label, capture, multiple, onChange, value]
  );

  /**
   * Move every finished file out of the capture card and into the field's value.
   *
   * A file is finished when the eager-staging store reports its object `ready`. A zero-byte file is
   * never staged at all (the store skips them), so it is passed straight through rather than sitting
   * in the strip forever waiting for a status that will not arrive — an empty file is still a file
   * somebody attached on purpose and must not disappear into limbo.
   *
   * With NO CONNECTION nothing will ever reach `ready`, so an offline attachment is drained
   * immediately into the local draft store. Without that branch the capture card would sit there
   * retrying a presign that cannot succeed, and the photograph the designer took would exist only in
   * a page that is one tab-close away from losing it.
   *
   * A file whose transfer FAILED while online is left exactly where it is: the capture card draws it
   * with its error and its own Retry, which is a better place to recover than a sentence down here.
   */
  useEffect(() => {
    if (!pending.length) return;
    const offline = typeof navigator !== "undefined" && navigator.onLine === false;
    const finished = pending.filter((file) => {
      if (claimedRef.current.has(file)) return false;
      if (offline) return true;
      const entry = staging.get(file);
      if (!entry) return file.size === 0;
      return entry.status === "ready";
    });
    if (!finished.length) return;
    finished.forEach((file) => claimedRef.current.add(file));
    // CLAIM FIRST — see the file header. `uploadMediaBatch` takes the staged objects synchronously,
    // so by the time the capture card reacts to the shorter list there is nothing left for its own
    // `discardStagedFile` to delete.
    const settling = settle(finished);
    setPending((current) => current.filter((file) => !finished.includes(file)));
    void settling;
  }, [pending, staging, settle]);

  /**
   * Drop one attachment.
   *
   * A `dwlocal:` reference is the only copy of those bytes, so removing it also removes the staged
   * blob — an explicit removal by the designer is the one thing allowed to delete a staged file, and
   * leaving it behind would mean a photograph nothing references sitting in browser storage for the
   * rest of the fortnight. A server id is only unreferenced here; the object stays, because the same
   * photograph is legitimately used by another stage.
   */
  function detach(id: string) {
    const remaining = ids.filter((item) => item !== id);
    onChange(multiple ? remaining : (remaining[0] ?? null));
    if (isLocalMediaRef(id)) void removeLocalMedia(id.slice(LOCAL_MEDIA_PREFIX.length));
  }

  return (
    <div
      className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3"
      role="group"
      aria-labelledby={labelId}
      aria-describedby={describedBy}
    >
      {ids.length ? (
        <ul className="grid gap-2">
          {ids.map((id) => {
            const local = isLocalMediaRef(id) ? staged[id] : undefined;
            const file = local ? undefined : files[id];
            const thumbnail = local?.url ?? (file?.mediaType === "IMAGE" ? file.url : null);
            return (
              <li key={id} className="flex items-center gap-2 rounded-md border border-line-200 bg-card px-3 py-2">
                {thumbnail ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img
                    src={thumbnail}
                    alt={local ? local.name : file?.caption || file?.originalFilename || ""}
                    className="h-10 w-10 shrink-0 rounded-sm object-cover"
                    loading="lazy"
                  />
                ) : (
                  <span className="grid h-10 w-10 shrink-0 place-items-center rounded-sm bg-field-200 text-field-600">
                    <Paperclip className="h-4 w-4" aria-hidden />
                  </span>
                )}
                <span className="min-w-0 flex-1 truncate text-sm text-ink-900">
                  {local
                    ? local.name
                    : file
                      ? file.originalFilename
                      : isLocalMediaRef(id)
                        ? // A local reference whose blob has gone is the one case this store is built
                          // to make impossible, so it is stated rather than drawn as an empty tile.
                          "This file was kept on this device and can no longer be found here."
                        : "This file is no longer readable from here."}
                </span>
                {local ? (
                  // The static, worded counterpart to the banner at the top of the page: colour and
                  // position never carry this on their own.
                  <span className="shrink-0 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                    On this device only
                  </span>
                ) : null}
                <button
                  type="button"
                  className="text-xs font-medium text-ink-500 underline"
                  disabled={disabled}
                  onClick={() => detach(id)}
                >
                  Remove
                </button>
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="text-sm text-ink-500">Nothing attached yet.</p>
      )}

      {extra
        ? extra({
            files: ids.map((id) => files[id]).filter((file): file is MediaFile => Boolean(file)),
            originals,
            // Appended rather than replacing the list: a designer may sign, then attach a
            // photograph of the paper sheet as well, and neither may evict the other.
            attach: (file) => setPending((current) => [...current, file]),
            // Only the entries that produced an object URL, which is exactly the image ones: the
            // effect above makes a URL for `image/*` blobs and leaves `url` null for anything else,
            // so an audio file staged offline cannot arrive somewhere expecting to be looked at.
            local: ids
              .filter(isLocalMediaRef)
              .map((ref) => {
                const tile = staged[ref];
                return tile && tile.url ? { key: ref, name: tile.name, url: tile.url } : null;
              })
              .filter((entry): entry is { key: string; name: string; url: string } => entry !== null)
          })
        : null}

      {/* A single-value field still accepts several at once and keeps the last: refusing the second
          file would mean a designer who picked the wrong photograph has to find a Remove before they
          can pick the right one, on a handset, in a courtyard. */}
      <MediaCaptureField
        files={pending}
        onFilesChange={setPending}
        title={multiple ? `Add to ${field.label.toLowerCase()}` : field.label}
        description={
          multiple
            ? "Every file attached here uploads straight away and joins this field when it lands. Audio is queued for transcription."
            : "The file attached here uploads straight away and replaces whatever this field held. Audio is queued for transcription."
        }
        allowedTypes={ALLOWED_TYPES[field.type]}
        allowDocuments={field.type === "FILE"}
      />

      {/* Both of these report something that has ALREADY happened to the designer's files — a batch
          that only partly landed, or bytes kept on this device because the network was gone. Left as
          plain paragraphs they were coloured text and nothing else, so a designer using a screen
          reader attached four photographs, heard nothing, and walked away from a workshop believing
          all four were in the repository. `status` for the notice (nothing is wrong, do not
          interrupt) and `alert` for the problem (files were lost and must be attached again). */}
      {notice ? (
        <p role="status" className="text-xs leading-5 text-amber-800">
          {notice}
        </p>
      ) : null}
      {problem ? (
        <p role="alert" className="text-xs font-medium text-error-600">
          {problem}
        </p>
      ) : null}
    </div>
  );
}
