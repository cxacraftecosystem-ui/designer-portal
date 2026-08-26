"use client";

/**
 * The two shapes a registry entity can take on screen, and nothing else.
 *
 * A stage's SINGLETON entity is one record per workshop and renders as {@link EntityForm}: a plain
 * grid of fields, BASIC and STANDARD together in declaration order, ADVANCED collapsed behind a
 * "More detail" disclosure. Its COLLECTION entities are repeating records — every sketch, every
 * prototype, every survey respondent — and render as {@link CollectionTable}: a list of rows with
 * add, edit, reorder and delete, each row titled by the entity's `labelField`.
 *
 * FOUR ENTITIES MIRROR A REPOSITORY RECORD, AND THOSE DRAW THEIR FIELDS IN THREE GROUPS INSTEAD OF
 * TWO. Where a stage asks for the same facts a record page already collects, the record page itself
 * is embedded — the picker, then the real `ArtisanForm`/`ToolForm`/`ProductForm`/`ProcessForm` with
 * the stage's own questions inside it, then the boxes the linked record fills in, collapsed. That
 * is {@link MirroredEntityBody}, and the argument for all of it is in
 * `components/designworkshop/StageRecordEmbed.tsx`, which also enumerates the four it ships and the
 * hydration mappings it refuses, each with its reason.
 *
 * ONE CONSEQUENCE REACHES THIS FILE'S OWN CONTROLS: a collection row's panel can now hold a whole
 * record form whose values are NOT durable, so COLLAPSING A ROW BECAME AN EXIT. See {@link toggleRow}.
 *
 * WHAT DOES NOT CHANGE THERE IS THE ONE THING THAT MATTERS HERE: every registry field is still drawn
 * by {@link FieldGrid} and wrapped in {@link FieldCell}. The embed decides WHICH GROUP a field is
 * drawn in and nothing else — it is handed the grids as nodes and cannot render a field itself. Four
 * things live in `FieldCell` and nowhere else (the search anchor, the per-field refusal, the
 * provenance stamp, and the stranded-refusal banner's assumption that a rendered entity's fields are
 * all drawn), and a field relocated out of it loses all four silently.
 *
 * WHY ADVANCED IS COLLAPSED BY DEFAULT AND BASIC NEVER IS. The three tiers exist so that a workshop
 * held in a village without mains power can still produce a complete report: BASIC is the minimum,
 * and the backend's `validate_registry` refuses to build a registry in which any other tier is
 * required. Stage 13 has upwards of thirty fields, most of them ADVANCED; showing all of them
 * flattens the distinction the tiers were created to draw, and a designer working through a form on
 * a handset in a courtyard cannot see what is actually being asked of them. The disclosure counts
 * the fields behind it so nobody has to open it to find out whether it is worth opening.
 *
 * WHY THE FORM IS CONTROLLED RATHER THAN UNCONTROLLED `FormData`. Every record form in this
 * repository is uncontrolled and read once at submit, which is right when the field list is fixed
 * and known at compile time. It cannot work here: the payload is a JSON object whose keys come from
 * a registry fetched at runtime, half the controls are themed buttons that emit no native input
 * event at all (dropdowns, tag chips, the media picker, the GPS card), and a collection row has to
 * survive being reordered — which remounts it — without losing what was typed. So the stage page
 * owns the data and passes values down. Dirty tracking is explicit for the same reason: there is no
 * `onInput` on a `<form>` that would ever see a dropdown change.
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { AlertTriangle, ArrowDown, ArrowUp, ChevronDown, GripVertical, Plus, Trash2 } from "lucide-react";

import { FieldInput, type StageCaptureContext } from "@/components/designworkshop/FieldInput";
import {
  MirroredFieldsDisclosure,
  StageRecordEmbed,
  embeddedRecordId,
  mirrorPointFor,
  mirrorRefField,
  splitMirroredFields
} from "@/components/designworkshop/StageRecordEmbed";
import { fieldFormatError } from "@/components/designworkshop/stageFieldFormats";
import { StageReferenceMultiPicker } from "@/components/designworkshop/StageReferenceField";
import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { moveIndex, useDragReorder } from "@/components/hooks/useDragReorder";
import { FLASH_MS } from "@/components/hooks/useRevealRow";
import { findMissingViews } from "@/lib/imageQuality";
import { FIELD_ANCHOR_ATTRIBUTE, ROW_ANCHOR_ATTRIBUTE, type StageFocus } from "@/lib/workshopSearch";
import {
  blankRow,
  hydrateFromReference,
  inputValue,
  captionFieldFor,
  formFields,
  isFilled,
  rowTitle,
  splitByTier,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwFieldStamp,
  type DwReferenceOption,
  type DwRow,
  type DwValue
} from "@/lib/designWorkshops";

/** Per-field messages for one record, as the save response's `errors` map holds them. */
export type FieldErrors = Record<string, string> | undefined;

/* ────────────────────────────────────────────────────────────────────────────
 * The field grid, shared by both shapes
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One field's cell — its layout span, its ADDRESS, and the arrival when a search sent someone here.
 *
 * WHY A FIELD NEEDS AN ADDRESS AT ALL. `FieldInput` names its control with `useId`, which is an
 * opaque per-render string, so until now nothing outside this tree could point at a box. The
 * workshop search on the overview page produces results that have to be navigable — a result a
 * designer cannot get to is not a result — and `data-dw-field` / `data-dw-row` are what
 * `lib/workshopSearch.readStageFocus` resolves a hit against. They are stamped from the registry's
 * own keys, so they cost nothing per stage and cannot drift from the field list.
 *
 * WHY THE ARRIVAL LIVES HERE AND NOT ON THE STAGE PAGE. The moment to scroll is the moment the box
 * EXISTS, and when that is depends on where the box lives: a singleton field is there on first
 * paint, a collection field appears when its row's panel opens, an ADVANCED field appears when its
 * disclosure opens. A mount effect on the cell itself is right in all three cases without any of
 * them being enumerated; a `querySelector` from the page would have to guess when to look.
 *
 * THE FLASH IS THE `.fr-flash-row` CONTRACT, reused rather than re-invented (`globals.css`, and
 * `components/hooks/useRevealRow.ts` for the map's version of the same problem): one pulse, plus a
 * STATIC outline that lingers. The outline is the half that survives `prefers-reduced-motion`, and a
 * signal that exists only as motion is a signal those readers never get. `data-flash` rather than a
 * class so that flipping it cannot disturb the conditional layout classes beside it.
 */
function FieldCell({
  entity,
  field,
  wide,
  rowKey,
  focused,
  children
}: {
  entity: DwEntity;
  field: DwField;
  wide: boolean;
  rowKey: string | null;
  focused: boolean;
  children: React.ReactNode;
}) {
  const reduce = useAppReducedMotion();
  /**
   * Read through a ref, never as a dependency. `useAppReducedMotion()` reads false on the server and
   * on the first client render by design and flips a tick later once ThemeProvider has read the
   * stored preferences — as a dependency that flip would tear this effect down and re-run it, so the
   * page would scroll a second time for exactly the readers who asked for less movement. Same
   * treatment, and the same reason, as `components/hooks/useEditDeepLink.ts`.
   */
  const reduceRef = useRef(reduce);
  useEffect(() => {
    reduceRef.current = reduce;
  });

  const cell = useRef<HTMLDivElement | null>(null);
  const [flash, setFlash] = useState(false);

  useEffect(() => {
    if (!focused) return;
    const node = cell.current;
    if (!node) return;
    // ONE FRAME LATER, for the reason `useRevealRow` defers its own scroll: this effect runs in the
    // same commit that opened the row panel or the disclosure above it, so measuring now measures a
    // layout the field is about to leave.
    const frame = requestAnimationFrame(() => {
      node.scrollIntoView({
        behavior: reduceRef.current ? "auto" : "smooth",
        // CENTRE, not "nearest": the field is being arrived at rather than kept in view, and a box
        // pinned to the top edge of the window hides the entity heading that says what it belongs to.
        block: "center",
        inline: "nearest"
      });
      setFlash(true);
    });
    const timer = setTimeout(() => setFlash(false), FLASH_MS);
    return () => {
      cancelAnimationFrame(frame);
      clearTimeout(timer);
    };
  }, [focused]);

  return (
    <div
      ref={cell}
      className={wide ? "fr-flash-row md:col-span-2" : "fr-flash-row"}
      data-flash={flash ? "true" : undefined}
      {...{ [FIELD_ANCHOR_ATTRIBUTE]: `${entity.key}.${field.key}` }}
      {...(rowKey ? { [ROW_ANCHOR_ATTRIBUTE]: rowKey } : {})}
    >
      {children}
    </div>
  );
}

function FieldGrid({
  entity,
  fields,
  data,
  onChange,
  onPatch,
  workshopId,
  errors,
  disabled,
  stageKey,
  rowKey,
  anchorRowKey,
  capture,
  focus,
  provenance,
  recordFormMountedOver = null
}: {
  entity: DwEntity;
  fields: DwField[];
  data: DwEntryData;
  onChange: (key: string, value: DwValue) => void;
  /**
   * Write several keys of this record in ONE commit.
   *
   * Needed by the reference pickers (choosing an artisan writes the id and every display field it
   * hydrates) and by the identity-card reader. A loop of `onChange` calls cannot substitute for it in
   * a collection: `patchRow` rebuilds the row array from the `rows` it captured at render, so the
   * second call in a loop would be built on top of a snapshot that predates the first and would
   * silently discard it.
   */
  onPatch: (values: Record<string, DwValue>) => void;
  workshopId: string;
  errors: FieldErrors;
  disabled?: boolean;
  /** Passed straight down to media fields so a file staged offline knows what it answers. */
  stageKey?: string;
  rowKey?: string | null;
  /**
   * How the WORKSHOP SEARCH names this row, which is not the same question as `rowKey` above.
   *
   * `rowKey` is the row's `_clientKey` and nothing else, because that is the idempotency key
   * `stageLocalMedia` files a staged photograph under. The anchor has to be whatever
   * {@link CollectionTable}'s own `keyOf` opened the row BY — `_clientKey`, else the server's
   * `_entryId` — so that "which row opened" and "which field is marked" can never come back as two
   * different answers. As things stand the two ladders agree, because `adoptServerStage` mints a
   * `_clientKey` for every server row it folds into the draft (the 244 rows of the flagship
   * workshop arrive from the API with only `_entryId` and are keyed on the way in). They are kept
   * separate anyway: one is a contract with the local media store and the other with
   * `lib/workshopSearch`, and sharing one field between two contracts is how the next change to
   * either quietly breaks the other.
   */
  anchorRowKey?: string | null;
  /** Where and when the stage is being recorded — stamped onto every file the grid uploads. */
  capture?: StageCaptureContext;
  /** The field a workshop search sent the designer to. See {@link FieldCell}. */
  focus?: StageFocus;
  /**
   * WHO LAST SET EACH FIELD OF THIS RECORD, keyed by field key.
   *
   * One record's worth, resolved by the caller — the singleton's map, the custom map, or the map for
   * THIS ROW's entry id. Keyed by entry id and never by array position, for the reason set out on
   * `DwStageProvenance`: the readers of that data sort their rows differently, and a positional
   * lookup shows one participant's edits against another participant's name.
   *
   * Optional, so a caller that does not pass it renders exactly as before.
   */
  provenance?: Record<string, DwFieldStamp>;
  /**
   * A REPOSITORY RECORD THAT ALREADY HAS AN EDIT SURFACE OPEN ON THIS PAGE.
   *
   * Passed by {@link MirroredEntityBody} and by nobody else, and read by the REF branch of
   * `FieldInput` and by nothing else: it suppresses the picker's "Edit this …" pencil over the very
   * record whose own page is mounted below it. Every other grid on every other stage passes null
   * and draws exactly what it always drew. See `StageReferenceSelect.recordFormMountedOver` for the
   * defect — two editors on one repository record, and the one opened first wins on Save.
   */
  recordFormMountedOver?: string | null;
}) {
  return (
    <div className="grid gap-4 md:grid-cols-2">
      {fields.map((field) => {
        // A caption belongs to its media field and is handed to FieldInput to draw underneath it.
        // It is never listed in `fields` (formFields filters every `captionFor` field out), so this
        // is the ONLY place a caption input can come from — which is what makes "never a separate
        // input" enforceable rather than a convention somebody eventually breaks. No media-type
        // test is needed: `validate_registry` refuses a registry in which `captionFor` points at
        // anything that is not a media field, so a hit here is a media field by construction.
        const captionField = captionFieldFor(entity, field.key);
        // Media, long prose and the GPS card need the full width; a photo grid squeezed into half a
        // row on a laptop is a thumbnail nobody can judge focus from.
        const wide =
          field.type === "LONG_TEXT" ||
          // A RICH_TEXT field is the widest thing on a stage: it carries a toolbar of twenty-odd
          // controls that would wrap into four rows in half a column, a find-and-replace bar, and
          // the prose itself — and prose set to a 30-character measure is prose nobody proof-reads.
          field.type === "RICH_TEXT" ||
          field.type === "GEO" ||
          field.type === "IMAGE" ||
          field.type === "IMAGE_LIST" ||
          field.type === "FILE" ||
          field.type === "AUDIO" ||
          field.type === "VIDEO";
        return (
          <FieldCell
            key={field.key}
            entity={entity}
            field={field}
            wide={wide}
            rowKey={anchorRowKey ?? rowKey ?? null}
            focused={
              focus?.entityKey === entity.key &&
              focus.fieldKey === field.key &&
              (focus.rowKey ?? null) === (anchorRowKey ?? rowKey ?? null)
            }
          >
            <FieldInput
              field={field}
              value={data[field.key]}
              onChange={(next) => onChange(field.key, next)}
              entity={entity}
              row={data}
              onPatch={onPatch}
              workshopId={workshopId}
              disabled={disabled}
              error={errors?.[field.key] ?? null}
              place={{ stageKey, entityKey: entity.key, rowKey }}
              capture={capture}
              stamp={provenance?.[field.key] ?? null}
              recordFormMountedOver={recordFormMountedOver}
              caption={
                captionField
                  ? {
                      field: captionField,
                      value: data[captionField.key],
                      onChange: (next) => onChange(captionField.key, next)
                    }
                  : undefined
              }
            />
          </FieldCell>
        );
      })}
    </div>
  );
}

/**
 * "You have a front and a detail shot, but no back" — but ONLY for the one entity in the whole
 * registry that really has named view slots.
 *
 * Stage 6's `existingProduct` declares `viewFront` / `viewBack` / `viewDetail` as separate Advanced
 * IMAGE fields; nothing else does (stage 11's sketch has a single `image`, stage 16's finalProduct
 * has galleries). `findMissingViews` is keyed on the entity for that reason, and returns nothing for
 * every other entity — a warning that asked for a "back view" on a form with no such field would be
 * asking for something the designer cannot give it.
 *
 * Placed OUTSIDE the "More detail" disclosure even though the slots themselves live inside it. The
 * whole value of this hint is being seen while the product is still on the table; behind a collapsed
 * disclosure it would be read only by someone who had already gone looking.
 */
function MissingViewsHint({ entity, data }: { entity: DwEntity; data: DwEntryData }) {
  const findings = useMemo(
    () => findMissingViews(entity.key, data as Record<string, unknown>),
    [entity.key, data]
  );
  if (!findings.length) return null;
  return (
    <div className="mt-3 flex items-start gap-2 rounded-md bg-amber-100 p-3 text-amber-800">
      {/* Colour never carries this on its own: the icon is decorative and the sentence says it. */}
      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      <p className="text-xs leading-5">{findings[0].message}</p>
    </div>
  );
}

/**
 * Does `focus` point at a field this list holds? Used to open a disclosure or a row that a search
 * result is inside — an answer behind a collapsed control is an answer the search cannot deliver.
 */
function focusIsIn(focus: StageFocus | undefined, entity: DwEntity, fields: DwField[]): boolean {
  if (!focus || focus.entityKey !== entity.key) return false;
  // A caption is drawn by its media field and never appears in `fields` itself, so the media field
  // is what the focus names — see `anchorFieldKey` in `lib/workshopSearch`.
  return fields.some((field) => field.key === focus.fieldKey);
}

/**
 * How many of these fields the server came back refusing.
 *
 * Counted against the field list rather than against the error map's size, because the map is keyed
 * for the WHOLE record: a refusal on a BASIC field is drawn by the primary grid and must not be
 * counted by a disclosure that is not hiding it.
 *
 * EXPORTED SO IT CAN BE EXECUTED BY A TEST. This repository has no React renderer in its
 * devDependencies, so a rule left inside a component body can only ever be READ by a spec — and the
 * rule this one carries is the difference between a refusal a designer can find and one they are
 * only told about. See `e2e/stage-record-embed-unit.spec.ts`.
 */
export function refusedIn(fields: DwField[], errors: FieldErrors): number {
  if (!errors) return 0;
  return fields.filter((field) => Boolean(errors[field.key])).length;
}

/**
 * How many of these fields a designer has to go and look at: refused by the last save, OR holding a
 * value the NEXT save will refuse because it does not match the format the registry declares.
 *
 * ── WHY THE SECOND HALF HAD TO JOIN THE FIRST RATHER THAN GET ITS OWN PILL ────────────────────
 *
 * `AdvancedDisclosure` unmounts its panel while collapsed and `MirroredFieldsDisclosure` hides its
 * one, so on either of them a malformed value inside can be drawn nowhere at all — which is the
 * exact failure `refusedIn` was added for, one step earlier in time. A hydrated participant row
 * arrives with the artisan record's email address in a mirrored box that is closed by default; if
 * that address is malformed (and nothing in this repository has ever stopped one being stored, on
 * any path), the designer presses Save, the repository refuses that one field, and the only red text
 * on the page is behind a summary that says "Details from the artisan record" and nothing else.
 *
 * ONE PILL AND NOT TWO, because "3 to fix" is the whole of what a closed disclosure can usefully
 * say, and a designer deciding whether to open it does not need to know which of the two reasons
 * put the number there — they will see the sentence on the box the moment it opens. Two pills on one
 * button would also make the row header and this control disagree about how a count is spelled,
 * which `refusedIn`'s own note already argues against.
 *
 * COUNTED ONCE PER FIELD. A field can be both — the server refused it and the value still in the box
 * is still malformed, which is the ordinary state straight after a refused save — and "2 to fix" for
 * one box would send the designer looking for a second problem that does not exist.
 */
function needsAttentionIn(fields: DwField[], errors: FieldErrors, row: DwEntryData): number {
  return fields.filter(
    (field) =>
      // A RETIRED FIELD IS NOT SOMETHING TO GO AND LOOK AT. `validate_entry` skips a deprecated
      // spec outright, so the server neither refuses its value nor could ever have put a message
      // against it, and no grid draws one — `formFields()` filters them out before the groups are
      // built. Unreachable today, therefore, and kept because it is the guard the deleted
      // `formatViolationsIn` carried and the pill must not be able to send a designer looking for
      // a box that is not on the page. See the note at the foot of `stageFieldFormats.ts`.
      !field.deprecated &&
      (Boolean(errors?.[field.key]) || Boolean(fieldFormatError(field, row[field.key])))
  ).length;
}

/**
 * The "More detail" disclosure.
 *
 * `aria-controls` is set ONLY while the panel is mounted. The panel is unmounted on collapse (there
 * is no height animation to keep it alive for), and pointing `aria-controls` at an element id that
 * does not exist is worse for a screen reader than not pointing at anything.
 *
 * ── A REFUSAL BEHIND A COLLAPSED DISCLOSURE USED TO BE ANNOUNCED AND DRAWN NOWHERE ────────────
 * The panel is unmounted while collapsed, so the `FieldInput` that would draw a server message on an
 * ADVANCED field does not exist. The page said "The fields that need attention are marked below" and
 * nothing was marked — and because nothing could be corrected, the same refusal came back on every
 * subsequent save, for ever. The stranded-refusal banner cannot cover it either: `strandedRefusals`
 * receives no field list and no tiers, so it treats every key of a rendered singleton as drawn.
 *
 * So the count is answered TWICE, in the two ways a designer might meet it: a red "N to fix" pill on
 * this button, in the same words the collection row header already uses, and an effect that opens
 * the disclosure the moment the count goes from none to some. Both are needed. The pill alone leaves
 * a designer hunting; the auto-open alone says nothing once they have closed it again.
 *
 * WHY AN EFFECT AND NOT A `defaultOpen`. `defaultOpen` is read exactly once, through `useState`'s
 * initial value, and a refusal ARRIVES — it is the response to a save the designer just pressed, on
 * a component that has been mounted since before they pressed it. It would never be read again.
 *
 * THE TRANSITION AND NOT THE VALUE: the effect fires on none → some and not on every render where
 * some exist, so a designer who closes the disclosure to look at something else is not fought by a
 * panel that springs open again. A second save that is refused again re-opens it, because the count
 * goes to zero in between only if it is fixed — and if it is not, the response resets it from zero
 * only when the previous save came back clean. Either way the rule is "say it once per refusal".
 */
function AdvancedDisclosure({
  id,
  count,
  refused = 0,
  defaultOpen = false,
  children
}: {
  id: string;
  count: number;
  /**
   * How many fields BEHIND this control need attention — refused by the last save, or holding a
   * value the next save will refuse. Drives the pill and the auto-open. See `needsAttentionIn`.
   *
   * Zero, and this renders exactly as it always did.
   */
  refused?: number;
  /**
   * Open on first render. Only ever true when a search result points at a field inside — an
   * ADVANCED field is behind this control precisely so a designer is not shown forty optional boxes
   * in a courtyard, and opening it for any other reason would undo that.
   */
  defaultOpen?: boolean;
  children: React.ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  /**
   * Open on the none → some edge, and never on the level.
   *
   * The ref rather than the previous render's prop because this must survive the re-render the
   * `setOpen` itself causes: keyed on the value, the effect would re-open the panel every time
   * anything else on the stage re-rendered while a refusal stood, which is a form fighting the
   * person reading it.
   */
  const refusedBefore = useRef(refused);
  useEffect(() => {
    const had = refusedBefore.current;
    refusedBefore.current = refused;
    if (refused > 0 && had === 0) setOpen(true);
  }, [refused]);

  if (!count) return null;
  return (
    <div className="mt-4 border-t border-line-200 pt-4">
      <button
        type="button"
        className="inline-flex items-center gap-2 text-sm font-medium text-purple-700 transition hover:text-purple-800"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        onClick={() => setOpen((current) => !current)}
      >
        <ChevronDown className={`h-4 w-4 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
        More detail
        {/* The count is what lets a designer decide whether to open it without opening it. */}
        <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs font-medium text-ink-700">{count}</span>
        {/* THE SAME WORDS THE ROW HEADER USES ("3 to fix"), so the two places a refusal can be
            summarised do not teach a designer two vocabularies for one thing. Colour never carries
            it alone: the number and the word are the message. */}
        {refused ? (
          <span className="rounded-full bg-error-100 px-2 py-0.5 text-xs font-medium text-error-600">
            {refused} to fix
          </span>
        ) : null}
      </button>
      {open ? (
        <div id={id} className="mt-4">
          {children}
        </div>
      ) : null}
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * MIRROR POINTS — the record page, embedded
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The three-group body an entity gets when it MIRRORS a repository record.
 *
 * Used by both shapes: a singleton draws it once, a collection draws it inside each open row. It
 * exists so the two cannot drift — the whole failure mode this replaces is "the record and the
 * stage each hold their own copy of one fact and they disagree the first time either is corrected",
 * and two copies of the composition would be the same mistake one level up.
 *
 * WHAT THIS FUNCTION DECIDES: which of three groups each registry field is drawn in. It does not
 * decide field order (the registry does, and `splitMirroredFields` preserves it), it does not draw
 * a field (every one goes through {@link FieldGrid}), and it does not touch the save (the record
 * form saves a real record and `StageRecordEmbed` links it).
 *
 * THE MIRRORED GROUP IS NOT TIER-SPLIT, and that is deliberate. The tiers answer "what is the
 * minimum a designer must fill in on a handset in a courtyard", which is not a question about boxes
 * NOBODY TYPES INTO — they are filled in from the record. Two nested disclosures to express a
 * distinction that does not apply would be a control a designer has to open twice to see one thing.
 * Declaration order is kept across both tiers, so the group reads as the record does.
 */
function MirroredEntityBody({
  entity,
  refField,
  data,
  onChange,
  onPatch,
  workshopId,
  errors,
  disabled,
  stageKey,
  rowKey,
  anchorRowKey,
  capture,
  focus,
  provenance,
  idPrefix
}: {
  entity: DwEntity;
  /** The mirror point's REF field, already resolved by {@link mirrorFor}. */
  refField: DwField;
  data: DwEntryData;
  onChange: (key: string, value: DwValue) => void;
  onPatch: (values: Record<string, DwValue>) => void;
  workshopId: string;
  errors: FieldErrors;
  disabled?: boolean;
  stageKey?: string;
  rowKey?: string | null;
  anchorRowKey?: string | null;
  capture?: StageCaptureContext;
  focus?: StageFocus;
  provenance?: Record<string, DwFieldStamp>;
  /** Unique per rendered instance, so two rows' disclosures do not share an element id. */
  idPrefix: string;
}) {
  const groups = useMemo(() => {
    // Split into the three DISPLAY groups first and by TIER second, in that order. The other way
    // round would tier-split the pickers as well, and a cascade picker is not optional detail
    // wherever the registry happens to have tiered it — see `splitMirroredFields`.
    const { pickers, mirrored, workshopOnly } = splitMirroredFields(entity, refField, formFields(entity));
    const workshop = splitByTier(workshopOnly);
    return {
      pickers,
      workshopPrimary: workshop.primary,
      workshopAdvanced: workshop.advanced,
      // Declaration order across both tiers — see the note above on why the mirrored group is not
      // tier-split at all.
      mirrored
    };
  }, [entity, refField]);

  /**
   * `recordFormMountedOver` defaults to null and is passed by the PICKER group alone — see the
   * `picker` prop below. The other two groups hold no REF field by construction
   * (`splitMirroredFields` collects every one of them into `pickers`), so handing it to all three
   * would be a prop that could not be read, on the two grids where the reason for it does not
   * apply.
   */
  const grid = (fields: DwField[], recordFormMountedOver: string | null = null) => (
    <FieldGrid
      entity={entity}
      fields={fields}
      data={data}
      onChange={onChange}
      onPatch={onPatch}
      workshopId={workshopId}
      errors={errors}
      disabled={disabled}
      stageKey={stageKey}
      rowKey={rowKey}
      anchorRowKey={anchorRowKey}
      capture={capture}
      focus={focus}
      provenance={provenance}
      recordFormMountedOver={recordFormMountedOver}
    />
  );

  /**
   * IS THE SEARCH RESULT POINTING AT *THIS* ROW?
   *
   * `focusIsIn` answers only "does this entity have a field by that name", which is the whole
   * question for a singleton and half of it for a collection. The non-mirror branch below has
   * always ANDed the row identity in; this branch did not, so on the 244-row roster a `?find=`
   * result pointing at row 17's village default-opened the mirrored and advanced disclosures of
   * whichever participant row the designer happened to open. Compared the way every other reader
   * compares it — against `anchorRowKey`, which is the value `CollectionTable`'s `keyOf` opened the
   * row by and therefore the value `focus.rowKey` holds. Both sides are null on a singleton, so
   * this is a no-op there.
   */
  const focusHere = (focus?.rowKey ?? null) === (anchorRowKey ?? rowKey ?? null);

  /**
   * IS A REQUIRED MIRRORED BOX STILL EMPTY?
   *
   * The mirrored group is not "optional detail" the way an advanced group is: it holds
   * `participant.name`, `tool.name` and `existingProduct.name`/`price` on today's registry, all
   * BASIC and all required. Collapsed with one of those blank, the stage's own "still needed" count
   * points at a box drawn nowhere — and because the panel is `display: none`, a readiness jump to it
   * scrolls to a hidden node and lands silently on nothing. So the disclosure opens itself while
   * there is something in it that must be answered, which is also the honest reading of its label:
   * a box the linked record has not filled in is not "filled in from the linked record".
   */
  const mirroredNeeded = groups.mirrored.some((field) => field.required && !isFilled(data[field.key]));

  return (
    <StageRecordEmbed
      entity={entity}
      refField={refField}
      row={data}
      workshopId={workshopId}
      onPatch={onPatch}
      /*
        A COLLECTION ROW DOES NOT MOUNT THE RECORD FORM UNTIL IT IS ASKED FOR — see
        `StageRecordEmbed`'s `mountOnRequest` for the whole reason, which is that a create-mode mount
        starts a high-accuracy `watchPosition` and the reverse-geocode chain behind it, and a roster
        has 244 rows a designer opens to READ. Decided by `rowKey`/`anchorRowKey` rather than by an
        entity name: a singleton passes neither, and stage 5's `traditionalProcess` — the one
        singleton mirror point — mounts `ProcessForm`, which has no location card at all.
      */
      mountOnRequest={(anchorRowKey ?? rowKey ?? null) !== null}
      /*
        PART 1 — THE PICKERS, drawn by the ordinary grid from the ordinary registry fields, so
        scan-to-pick, the cascade notice, the scope notice and the create/edit buttons all behave
        exactly as they do on every other stage. Their own `FieldCell`s are what make them navigable
        from a workshop search result.

        PLURAL, AND IN DECLARATION ORDER: stage 6's product picker cascades from an artisan picker
        that has to be answerable first, and a REF field anywhere in this entity has to be above the
        record form rather than inside it — a dialog it opens would bubble its submit into that form
        through the React tree even though it is portalled out of the DOM. `splitMirroredFields`
        carries both arguments.
      */
      /*
        AND THE ONE THING THE PICKERS ARE TOLD ABOUT THE FORM BELOW THEM: which record it is open
        over. `StageReferenceSelect` draws "Edit this {noun}" beside a chosen record, which opens
        `InlineRecordDialog` on the SAME record the embedded page is already mounted over — two
        editors on one repository record, `initial` read once at mount, and the older of the two
        posting its pre-edit snapshot over the correction made in the other. The picker suppresses
        its pencil where the id matches its own choice; on stage 6 the artisan cascade picker's id
        does not match the product below it, so that pencil stays, which is the reason this is an id
        rather than a flag. `embeddedRecordId` is the embed's own function, so the two surfaces
        cannot come to different answers about which record is open.
      */
      picker={grid(groups.pickers, embeddedRecordId(refField, data) || null)}
      /*
        PART 2's FOOTER — THE WORKSHOP'S OWN QUESTIONS, rendered INSIDE the record form's `<form>`,
        as the last fields above its buttons. `MissingViewsHint` comes with them because the named
        view slots it is about (`viewFront`/`viewBack`/`viewDetail` on stage 6) are workshop-only
        fields, and the hint is only worth anything while the product is still on the table.
      */
      workshopFields={
        <>
          {grid(groups.workshopPrimary)}
          <MissingViewsHint entity={entity} data={data} />
          <AdvancedDisclosure
            id={`${idPrefix}-advanced`}
            count={groups.workshopAdvanced.length}
            refused={needsAttentionIn(groups.workshopAdvanced, errors, data)}
            defaultOpen={focusHere && focusIsIn(focus, entity, groups.workshopAdvanced)}
          >
            {grid(groups.workshopAdvanced)}
          </AdvancedDisclosure>
        </>
      }
      /*
        PART 3 — THE MIRRORED BOXES, collapsed but STILL IN THE TREE. `MirroredFieldsDisclosure`
        hides rather than unmounts, which is the one way it differs from `AdvancedDisclosure` above
        it: unmounting would take the `data-dw-field` anchors, the per-field refusals and the
        provenance stamps with it, and these are the boxes a designer most often has to CORRECT —
        hydration only fills blanks, so a wrong village arriving from the record is theirs to fix.
      */
      mirroredFields={
        <MirroredFieldsDisclosure
          id={`${idPrefix}-mirrored`}
          count={groups.mirrored.length}
          refused={needsAttentionIn(groups.mirrored, errors, data)}
          defaultOpen={mirroredNeeded || (focusHere && focusIsIn(focus, entity, groups.mirrored))}
        >
          {grid(groups.mirrored)}
        </MirroredFieldsDisclosure>
      }
    />
  );
}

/**
 * The REF field this entity's embedded record page hangs off — or null, meaning "render the ordinary
 * generated grid".
 *
 * BOTH HALVES HAVE TO RESOLVE, and null is a supported answer rather than an error case: a build can
 * be holding a registry OLDER or NEWER than itself, and if the field the table names is gone, has
 * been re-typed, or points at a model the inline host cannot mount, the honest fallback is the form
 * this stage had before the feature existed. `design-workshop-schema-skew.spec.ts` is a whole spec
 * about that situation being survivable rather than fatal.
 */
function mirrorFor(entity: DwEntity): DwField | null {
  const point = mirrorPointFor(entity);
  return point ? mirrorRefField(entity, point) : null;
}

/* ────────────────────────────────────────────────────────────────────────────
 * SINGLETON
 * ──────────────────────────────────────────────────────────────────────────── */

export function EntityForm({
  entity,
  data,
  onChange,
  onPatch,
  workshopId,
  errors,
  disabled,
  stageKey,
  capture,
  focus,
  provenance
}: {
  entity: DwEntity;
  data: DwEntryData;
  onChange: (key: string, value: DwValue) => void;
  /** Several keys of the singleton in one commit — see the note on FieldGrid's own `onPatch`. */
  onPatch: (values: Record<string, DwValue>) => void;
  workshopId: string;
  errors?: FieldErrors;
  disabled?: boolean;
  stageKey?: string;
  capture?: StageCaptureContext;
  /** The field a workshop search sent the designer here for, when it is one of this entity's. */
  focus?: StageFocus;
  /** Who last set each field of this singleton. See FieldGrid's own `provenance`. */
  provenance?: Record<string, DwFieldStamp>;
}) {
  const { primary, advanced } = useMemo(() => splitByTier(formFields(entity)), [entity]);
  /**
   * Does this singleton mirror a repository record? Stage 5's `traditionalProcess` is the one that
   * does today. Null for every other singleton, which then renders exactly as it always has.
   */
  const mirror = useMemo(() => mirrorFor(entity), [entity]);

  return (
    <section className="panel p-4">
      <header className="mb-4">
        <h2 className="font-display text-lg font-bold text-ink-900">{entity.title}</h2>
        {entity.description ? <p className="mt-1 text-sm leading-6 text-ink-muted">{entity.description}</p> : null}
      </header>
      {mirror ? (
        <MirroredEntityBody
          entity={entity}
          refField={mirror}
          data={data}
          onChange={onChange}
          onPatch={onPatch}
          workshopId={workshopId}
          errors={errors}
          disabled={disabled}
          stageKey={stageKey}
          capture={capture}
          focus={focus}
          provenance={provenance}
          idPrefix={`entity-${entity.key}`}
        />
      ) : (
        <>
          <FieldGrid
            entity={entity}
            fields={primary}
            data={data}
            onChange={onChange}
            onPatch={onPatch}
            workshopId={workshopId}
            errors={errors}
            disabled={disabled}
            stageKey={stageKey}
            capture={capture}
            focus={focus}
            provenance={provenance}
          />
          <MissingViewsHint entity={entity} data={data} />
          <AdvancedDisclosure
            id={`advanced-${entity.key}`}
            count={advanced.length}
            refused={needsAttentionIn(advanced, errors, data)}
            defaultOpen={focusIsIn(focus, entity, advanced)}
          >
            <FieldGrid
              entity={entity}
              fields={advanced}
              data={data}
              onChange={onChange}
              onPatch={onPatch}
              workshopId={workshopId}
              errors={errors}
              disabled={disabled}
              stageKey={stageKey}
              capture={capture}
              focus={focus}
              /*
                PASSED HERE TOO, WHICH IT WAS NOT. The collection path below hands `provenance` to both of
                its grids and this one handed it only to the primary, so a singleton's ADVANCED field was
                the one field in the registry that could never answer "did I write this, or did a
                colleague?" — on precisely the fields where that is least obvious, because they are the
                ones nobody looks at until a report disagrees with somebody's memory.
              */
              provenance={provenance}
            />
          </AdvancedDisclosure>
        </>
      )}
    </section>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * COLLECTION
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A summary line for a collapsed row: how much of it is answered.
 *
 * Counting is done against BASIC-tier fields only, matching `stage_completeness` exactly. Two
 * different answers to "is this row finished" — one on the row and one in the stage's progress bar
 * — is a form that says a stage is complete and a Save that refuses it.
 */
function rowProgress(entity: DwEntity, row: DwRow): { filled: number; total: number } {
  const required = entity.fields.filter((field) => field.required && !field.deprecated);
  return {
    filled: required.filter((field) => isFilled(row[field.key])).length,
    total: required.length
  };
}

/* ── Which removals are DELETIONS ─────────────────────────────────────────────
 *
 * WHAT THESE TWO FUNCTIONS ARE FOR, AND THE ROWS THAT WERE LOST WITHOUT THEM.
 *
 * The stage page arms the server's sweep from `removedFrom`: a save that names an entity there
 * sends `replaceCollections: true` plus `emptiedEntities: [...]`, and `sweep_entities` on the API
 * side then soft-deletes every row of that collection the payload does not carry. That is the ONLY
 * way a deletion can reach the repository — there is no per-row delete endpoint — so `removedFrom`
 * has to be exactly right in BOTH directions: a missing entry loses a real deletion, and a spurious
 * entry destroys rows nobody deleted.
 *
 * `patchCollection` used to decide by COUNT alone — "the array came back shorter, so a row was
 * deleted". That is true of the array and false of the repository. Press "Add prototype", look at
 * the empty panel, realise it belongs on the next stage, press the bin: net change nothing, and the
 * count test wrote `removedFrom: ['prototype']` for a row that had never been anywhere. That
 * phantom is sticky — `putDraftStage` UNIONS `removedFrom` on the way in, precisely so a form
 * cannot disarm a deletion the draft is still holding — so it cannot be withdrawn by anything the
 * designer does afterwards.
 *
 * It used to be merely noisy, because a browser that had never read the stage was refused the
 * authority to sweep. It is not noisy now: `foldStageInto` (designWorkshopStore.ts) EARNS that
 * authority on the next online open, and on the way it reads `removedFrom` as an instruction —
 * a collection named there is deliberately NOT folded back in, and its server rows are counted as
 * `sweptRows`. So one phantom entry makes the fold withhold the office's rows AND stamp
 * `serverLoadedAt`, and the next save deletes every one of them under an HTTP 200. Measured
 * against the pre-fix code with the fixture in `e2e/design-workshop-phantom-deletion-unit.spec.ts`:
 * six server rows withheld and swept for an Add-then-Delete that changed nothing.
 *
 * So the table now reports WHICH row went, and the page asks whether that row could possibly exist
 * on the server. A row minted by `blankRow()` in this render has never been anywhere and its
 * removal is not a deletion.
 *
 * DO NOT "SIMPLIFY" THIS BACK TO A COUNT, and do not make the fold or the withheld-rows sentence
 * apply their own version of the test: `removedFrom` is the single input all three read, so the
 * test belongs at the one place a removal is RECORDED.
 */

/** The `_clientKey`s of the rows the repository could be holding, by entity key. */
export type ServerHeldRows = Record<string, Set<string>>;

/** The three fields of a draft stage that say whether its rows have ever left this browser. */
export type StageRowProvenance = {
  serverLoadedAt: number | null;
  lastPushedAt: number | null;
  collections: Record<string, DwRow[]>;
};

/**
 * Which of a stage's rows the repository could be holding — the page's answer to "is a removal a
 * deletion".
 *
 * THE TEST IS PROVENANCE, NOT PRESENCE. A row sitting in the local draft proves nothing on its own:
 * the autosave banks a freshly added row 800 ms after it is added, so "is it in the draft" would
 * still call the Add-then-Delete phantom a deletion whenever the designer took longer than that to
 * change their mind — which is most of the time, and is the exact sequence in the report. What the
 * server could be holding is decided by whether this stage has ever been READ from it
 * (`serverLoadedAt`) or ever been PUSHED to it (`lastPushedAt`). Neither: nothing in this stage has
 * ever crossed the wire, so no removal out of it needs a sweep. Either: every row in it might be up
 * there — a row created here and pushed keeps its `_clientKey` and comes back with no `_entryId`
 * this page ever sees, so nothing finer than "this stage has been pushed" can be honest about it.
 *
 * ERRING TOWARDS RECORDING THE DELETION IS DELIBERATE and the asymmetry is not laziness. A
 * `removedFrom` entry this save did not need costs one redundant `replaceCollections` over rows the
 * payload carries anyway; a missing one loses a deletion the designer watched happen, permanently
 * and silently. Every uncertain case below therefore resolves to "yes, it is a deletion".
 *
 * `carried` is unioned in so the answer can GROW: the push that lands mid-session puts the rows it
 * carried up on the server, and a set rebuilt from scratch after it would forget everything the
 * session had already established.
 */
export function rowsTheServerCouldHold(
  stage: StageRowProvenance | null | undefined,
  carried: ServerHeldRows = {}
): ServerHeldRows {
  const held: ServerHeldRows = {};
  for (const [entityKey, keys] of Object.entries(carried)) held[entityKey] = new Set(keys);
  if (!stage) return held;
  if (stage.serverLoadedAt === null && stage.lastPushedAt === null) return held;
  for (const [entityKey, rows] of Object.entries(stage.collections ?? {})) {
    const set = held[entityKey] ?? new Set<string>();
    for (const row of rows) if (row._clientKey) set.add(row._clientKey);
    held[entityKey] = set;
  }
  return held;
}

/**
 * Is this removal a deletion the repository has to be told about?
 *
 * Read the argument for the whole rule above. The three "assume the worst" arms exist because a
 * wrongly withheld deletion is the more expensive mistake: an unreported row (a caller that shrank
 * the array without saying which row went), a row the SERVER minted the id for, and a row with no
 * `_clientKey` at all — for none of those can this function prove the row never existed upstream,
 * so it does not pretend to.
 */
export function removalIsADeletion(removed: DwRow | undefined, held: ReadonlySet<string> | undefined): boolean {
  if (!removed) return true;
  if (removed._entryId) return true;
  if (!removed._clientKey) return true;
  return held?.has(removed._clientKey) ?? false;
}

/**
 * One collection row's stable identity, as {@link CollectionTable} has always computed it.
 *
 * LIFTED TO MODULE SCOPE RATHER THAN LEFT IN THE COMPONENT, because it is now an input to a `useMemo`
 * (the drag ids) and a closure re-created on every render cannot be a dependency of one — the memo
 * would either be re-computed on every keystroke of a 244-row workshop or lie about why it was not.
 * It closes over nothing, so there was never a reason for it to be inside.
 *
 * THE FALLBACK IS POSITIONAL AND THAT IS THE LEAST BAD OPTION. `blankRow()` always mints a
 * `_clientKey` and every row the server has ever seen carries an `_entryId`, so `index-n` is reached
 * only by a row that has neither — a hand-built fixture, or a draft migrated from before client keys
 * existed. It is stable enough for React's `key` and for one drag gesture, which is all either needs.
 */
function keyOf(row: DwRow, index: number): string {
  return row._clientKey ?? row._entryId ?? `index-${index}`;
}

/**
 * The most rows one stage save may carry — `MAX_STAGE_ROWS` in
 * `backend/app/schemas/design_workshops.py`.
 *
 * `StageSaveIn._bound_rows` refuses a payload with more entries than this, and the refusal is a 422
 * over the WHOLE stage rather than over the row that crossed the line: nothing on the stage saves,
 * including the twenty fields the designer typed in the same sitting. The count is ENTRIES IN ONE
 * REQUEST, which for this form is every collection row on the stage, plus one entry per singleton
 * entity, plus the designer's own `_custom` container — so a single table can never see the total from
 * inside itself. It is handed the total instead: {@link stageEntryBudget} counts the payload on the
 * stage page, which owns every list, and {@link CollectionTable} thresholds on THAT and not on the
 * length of the one array it was given.
 *
 * IT IS STATED ON SCREEN BECAUSE RULE 10 SAYS SO. A cap nobody is told about is a save that begins
 * failing for a reason the designer cannot see, on the workshop with the most work in it — and it is
 * not a theoretical ceiling: the flagship workshop already carries 244 rows.
 */
const STAGE_ROW_CAP = 500;

/**
 * Where the sentence starts appearing — 90% of the cap.
 *
 * Early enough that a designer still has room to act on it (move a list to another stage, or split
 * the workshop) rather than being told at the moment the save stops working, which is a warning with
 * no remedy left in it.
 */
const STAGE_ROW_CAP_NOTICE_AT = Math.floor(STAGE_ROW_CAP * 0.9);

/**
 * How many of a bulk add's refused records are named in the sentence, before "and N more".
 *
 * Six, which is `ReportChart`'s figure for its "Not shown:" line, and the same argument: enough that a
 * designer who ticked ten and lost four can see all four, short enough that the sentence stays a
 * sentence when a picker serving `REFERENCE_PAGE_MAX` (200) options has 180 of them refused.
 */
const DROPPED_NAMES_SHOWN = 6;

/**
 * What one save of a WHOLE stage would carry, counted in the unit {@link STAGE_ROW_CAP} is counted in.
 *
 * ── WHY THIS EXISTS, AND THE SILENCE IT ENDS ─────────────────────────────────────────────────────
 *
 * {@link CollectionTable}'s cap sentence used to threshold on `rows.length` — the length of THIS list
 * — while `StageSaveIn._bound_rows` refuses on the length of the whole payload. A stage carrying
 * three collections of 200 rows sends 600-odd entries, so every save 422s over the whole stage, and
 * no single list comes within a hundred rows of the 450 the sentence fires at: NO SENTENCE APPEARED
 * ANYWHERE. That is precisely the rule-10 silence the sentence was written to prevent, arriving on the
 * workshop with the most work in it — the one whose saves stop first.
 *
 * A table cannot close that from inside itself. It is handed one entity's rows and has never been
 * told what else the stage declares. The stage page owns `collections`, `singleton` and `custom`, so
 * it is the only thing that CAN count the payload, and this is what it counts with. A pure function
 * beside the component rather than anything inside it, for the reason {@link rowsTheServerCouldHold}
 * is one: the page calls it once for the whole stage and hands the one answer to every table, where a
 * per-table derivation would be n copies of the same arithmetic free to disagree about the total.
 *
 * ── IT COUNTS WHAT `buildStageEntries` ACTUALLY SENDS, ARM FOR ARM ───────────────────────────────
 *
 * `lib/designWorkshopStore.ts` builds the payload in three arms and this mirrors all three:
 *
 *   * every COLLECTION row, one entry each — `rows.forEach` / `entries.push` at
 *     `designWorkshopStore.ts:3585-3586`;
 *   * one entry per SINGLETON entity — `entries.push` at `:3575`, gated on `!neverRead || answered`;
 *   * one entry for the designer's own `_custom` container — `:3668`, gated on the container having
 *     keys AND `!neverRead || answered`.
 *
 * So a stage's payload is ALWAYS bigger than the sum of its lists, and bigger by up to one entry per
 * singleton entity plus one for `_custom`. `emptiedEntities` is bounded separately by the same 500 and
 * is not counted here: it is a list of entity KEYS, never rows, and a stage cannot declare more
 * entities than it can hold rows.
 *
 * ── AND WHERE IT CANNOT BE CERTAIN IT SAYS SO, RATHER THAN GUESSING ──────────────────────────────
 *
 * Both non-row arms are gated on `neverRead` — `serverLoadedAt === null` on the BANKED DRAFT — which
 * is a fact about IndexedDB and not about anything on screen. A blank singleton and a
 * present-but-blank `_custom` container are therefore SENT by a stage this browser has read and
 * WITHHELD by one it has never downloaded, and this function will not pretend to know which. It
 * returns both bounds: `otherCertain` counts only the non-row entries that go up whatever this browser
 * has read (the answered ones), `other` counts what a read stage sends. The gap is at most one per
 * singleton plus one for `_custom`, and `stage_schema` validates that a stage declares at most one
 * singleton — so at most two — and the sentence prints a RANGE across it rather than a number it
 * cannot stand behind. A figure a designer cannot trust, at the moment they are deciding whether to
 * keep recording, is worse than a range that is honest about its own width.
 *
 * `carried` — {@link DwDraftStage.unknownSingleton} — deliberately does NOT enter this count, and that
 * is a fact about the store rather than an approximation. Every site that writes `unknownSingleton`
 * stamps `serverLoadedAt` in the same object literal (`designWorkshopStore.ts:2418`+`:2427`, `:2771`,
 * `:3093`), so a non-empty `carried` implies the stage HAS been read — and a read stage sends its
 * singleton entry whether anything in it is answered or not. Carried keys change what is INSIDE an
 * entry; they can never change whether there is one.
 */
export type StageEntryBudget = {
  /** Every collection row on the stage, this list's included. Exact — the page owns every array. */
  rows: number;
  /** The non-row entries a stage this browser HAS read sends: one per singleton, one for `_custom`. */
  other: number;
  /** How many of `other` are sent whatever this browser has read. Equal to `other` ⇒ exact. */
  otherCertain: number;
  /** `other` in the designer's own words, for the breakdown sentence. Empty when `other` is 0. */
  otherLabel: string;
};

export function stageEntryBudget(
  entities: readonly DwEntity[],
  collections: Record<string, DwRow[]>,
  /** Per-entity, as `splitSingletons` returns it — NOT the flat map the form binds to. */
  singletons: Record<string, DwEntryData>,
  custom: DwEntryData
): StageEntryBudget {
  let rows = 0;
  let singletonEntries = 0;
  let singletonAnswered = 0;
  for (const entity of entities) {
    if (entity.cardinality === "SINGLETON") {
      singletonEntries += 1;
      // `some(isFilled)` and NEVER a test of the container: a singleton map has keys the moment a form
      // was rendered over it, so `Object.keys(...).length` would report every stage as answered and
      // collapse the two bounds onto the wrong one. `isFilled` is character-for-character the server's
      // `_is_filled`, which is the same test `buildStageEntries` applies at `:3557`.
      if (Object.values(singletons[entity.key] ?? {}).some((value) => isFilled(value))) singletonAnswered += 1;
      continue;
    }
    rows += (collections[entity.key] ?? []).length;
  }
  /*
    THE `_custom` ARM IS GATED ON THE CONTAINER HAVING KEYS AND NOT ON ITS BEING ANSWERED, because
    that is the store's own gate: `plan_custom_write` treats "no entry" and "an entry carrying `{}`"
    as two different instructions, so a browser holding a keyed-but-blank container on a READ stage
    sends an entry that clears the row. It counts towards the cap exactly like any other entry.
  */
  const customEntry = Object.keys(custom).length > 0 ? 1 : 0;
  const customCertain = customEntry && Object.values(custom).some((value) => isFilled(value)) ? 1 : 0;
  const parts: string[] = [];
  if (singletonEntries > 0) parts.push(singletonEntries === 1 ? "this stage's own fields" : "this stage's own field sets");
  if (customEntry) parts.push("its custom questions");
  return {
    rows,
    other: singletonEntries + customEntry,
    otherCertain: singletonAnswered + customCertain,
    otherLabel: parts.join(" and ")
  };
}

export function CollectionTable({
  entity,
  rows,
  onRowsChange,
  workshopId,
  provenance,
  errorsByIndex,
  disabled,
  stageKey,
  capture,
  focus,
  stageEntries
}: {
  entity: DwEntity;
  rows: DwRow[];
  /**
   * The new list — and, when this call is a REMOVAL, the row that went.
   *
   * The second argument is not decoration and it is not for undo. It is the only evidence the page
   * has for deciding whether a shorter array means "delete rows on the server": see
   * {@link removalIsADeletion}. Every other caller here (add, edit, reorder, bulk add) leaves it
   * undefined, and the page treats undefined on a shrinking list as "assume a deletion", so a new
   * removal path that forgets to report its row is safe rather than silently destructive.
   */
  onRowsChange: (rows: DwRow[], removed?: DwRow) => void;
  workshopId: string;
  /** Per-row field errors, indexed the same way `rows` is. */
  errorsByIndex?: Record<number, FieldErrors>;
  disabled?: boolean;
  stageKey?: string;
  capture?: StageCaptureContext;
  /** The row and field a workshop search sent the designer here for, when it is one of these. */
  focus?: StageFocus;
  /**
   * Who last set each field of each row, keyed BY ENTRY ID then by field key.
   *
   * The whole entity's slice of `DwStageProvenance.collections`, handed down as-is; the row lookup
   * happens at the grid, where the row is in scope. See the note at that lookup for why an index
   * would be wrong.
   */
  provenance?: Record<string, Record<string, DwFieldStamp>>;
  /**
   * What one save of the WHOLE stage would carry — {@link stageEntryBudget}'s answer, from the page.
   *
   * REQUIRED, WITH NO DEFAULT, and that is the point of it. The `500` is a bound on the payload and
   * not on this array, so a table left to guess from `rows.length` is a table that stays silent on
   * exactly the stage where the cap bites — three lists of 200 rows refuses every save and no list
   * reaches the threshold. There is one call site (the stage page) and it is the only surface that can
   * answer this, so the prop is required: an omission is a compile error rather than a screen that
   * says nothing.
   */
  stageEntries: StageEntryBudget;
}) {
  const { primary, advanced } = useMemo(() => splitByTier(formFields(entity)), [entity]);
  /**
   * Does a row of this collection mirror a repository record?
   *
   * Resolved once for the table rather than per row: it is a property of the ENTITY, and computing
   * it inside the row map would re-derive it for all 244 rows of the flagship workshop on every
   * keystroke. Null for every collection that is not a mirror point, which then renders exactly as
   * it always has.
   */
  const mirror = useMemo(() => mirrorFor(entity), [entity]);
  /**
   * Which row is open for editing, identified by its client key rather than its array index.
   *
   * BY KEY AND NOT BY INDEX, deliberately: reordering rewrites every index, so an index-based
   * "which one is open" silently follows the row that moved into that slot. A designer who opened
   * the third prototype and then moved it up would be editing the one it swapped with, and nothing
   * on screen would say so.
   *
   * Seeded from `focus` so that a search result inside row seventeen of the participant roster opens
   * that row. Seeded rather than forced, and only on FIRST RENDER: the designer may close it again
   * and go looking at another row, and a panel that sprang back open on every render would be a form
   * fighting the person filling it in.
   */
  const [openKey, setOpenKey] = useState<string | null>(
    focus?.entityKey === entity.key ? focus.rowKey : null
  );


  /**
   * OPEN ONE ROW AND CLOSE WHICHEVER WAS OPEN — after asking anything mounted inside it.
   *
   * ── WHY A COLLAPSE IS NOW AN EXIT ─────────────────────────────────────────────────────────────
   * The panel is UNMOUNTED on collapse, deliberately, and that was harmless while everything it held
   * was durable: the stage's own answers are written straight through to IndexedDB. It is not
   * harmless now. Two of the four mirror points are collections, so a row's panel can hold a whole
   * `ArtisanForm`/`ToolForm`/`ProductForm` whose name, identity digits, attached FILES and captured
   * fix live in React state and uncontrolled DOM and are read only at submit. Collapsing over that
   * destroyed all of it with no prompt — and worse than "with no prompt" for a file, because
   * `useEagerStaging` then releases its owner and the object already in storage is deleted about two
   * seconds later.
   *
   * `interceptLeave` is the mechanism the back arrow already uses and the four forms already
   * register with, so the question is asked by the form that knows the answer and phrased in its own
   * words. It returns true only when a form has TAKEN RESPONSIBILITY (it is dirty and has put its
   * own dialog on screen), so a row holding nothing unsaved — and every row of every entity that is
   * not a mirror point — collapses exactly as before.
   *
   * OPENING ANOTHER ROW IS THE SAME EVENT, because `openKey` is one slot: the row that was open is
   * unmounted either way, so both paths go through here rather than only the visible "collapse".
   *
   * THE COLLAPSE ITSELF IS HANDED OVER, not just refused. `interceptLeave` banks what it refuses so
   * that the answer meaning "leave" can finish it — see `UnsavedChangesGuard`'s `PendingLeave`, and
   * note that no form calls `completeLeave()` yet, so today the collapse still costs a second press.
   * Of the four guarded acts this is one of the two that are NOT navigations (the other is
   * `StageReferenceField` re-pointing a row), which is exactly why it has to travel: a
   * `router.back()` guessed at the other end would throw the designer off the stage over a row they
   * were only folding up.
   *
   * THE BANKED ACT IS A FUNCTIONAL UPDATE AND NOT `setOpenKey(openKey === rowKey ? null : rowKey)`.
   * Read from the closure it would decide against the `openKey` of the render the press happened in,
   * and a banked act runs later, after a Discard that may have re-rendered this table several times.
   * Asking React for the value at the moment it applies is the same answer on the immediate path and
   * the only correct one on the resumed path.
   */
  const interceptLeave = useLeaveInterceptor();
  function toggleRow(rowKey: string) {
    const closing = openKey !== null && openKey !== rowKey ? openKey : openKey === rowKey ? rowKey : null;
    const open = () => setOpenKey((current) => (current === rowKey ? null : rowKey));
    if (closing !== null && interceptLeave(open)) return;
    open();
  }

  function patchRow(index: number, key: string, value: DwValue) {
    onRowsChange(rows.map((row, position) => (position === index ? { ...row, [key]: value } : row)));
  }

  /**
   * Several keys of one row, in ONE commit.
   *
   * A loop of `patchRow` calls cannot stand in for this: each one rebuilds the array from the `rows`
   * captured at render, so the second call is built on a snapshot that predates the first and throws
   * it away. Choosing an artisan writes nine keys at once, so the loop would have stored the id and
   * lost the name, village, gender and phone that came with it — and the server's own hydration
   * fills only BLANK fields, so it would not have put them back.
   */
  function patchRowMany(index: number, values: Record<string, DwValue>) {
    if (!Object.keys(values).length) return;
    onRowsChange(rows.map((row, position) => (position === index ? { ...row, ...values } : row)));
  }

  /**
   * The multi-select the roster asked for.
   *
   * Offered only where the collection's rows ARE the chosen records, and that is decided by three
   * conditions rather than by naming an entity — this file must not learn what a "participant" is.
   *
   *  * **BASIC tier**, because BASIC is the registry's own word for "this is what the row is for".
   *  * **No `refFilterBy`**, because a cascading picker depends on a value that lives on a row which
   *    does not exist yet, so there is nothing for the list to narrow to.
   *  * **Exactly one such field**, because two of them means a row is a PAIRING — stage 13's
   *    prototype names both a sketch and an artisan — and ticking thirty of one leaves thirty rows
   *    half-answered while looking finished.
   *
   * On the current registry that resolves to the stage-3 roster and to nothing else, which is the
   * intent; it will pick up any future collection built the same way without a line changing here.
   */
  const bulkField = useMemo(() => {
    const refs = formFields(entity).filter(
      (field) => field.type === "REF" && field.refModel && !field.refFilterBy && field.tier === "BASIC"
    );
    return refs.length === 1 ? refs[0] : null;
  }, [entity]);

  /* ══════════════════════════════════════════════════════════════════════════════════════════════
     THE 500-ENTRY CAP, COUNTED IN THE UNIT THE SERVER COUNTS IT IN — RULE 10
     ══════════════════════════════════════════════════════════════════════════════════════════════

     ── WHAT WAS WRONG WITH COUNTING THIS LIST ─────────────────────────────────────────────────────

     This block used to threshold on `rows.length`, and the cap it was describing has never been a
     bound on `rows`. `MAX_STAGE_ROWS` is enforced by `StageSaveIn._bound_rows` over `entries` — the
     WHOLE payload — so the case where it actually bites is the one where no single list is anywhere
     near it: three collections of 200 rows is 600-odd entries, every save 422s over the entire stage,
     and at 200 rows each no list reached the 450 the sentence fired at. Nothing appeared on any
     screen. The one arrangement that made the notice necessary was the one that silenced it.

     So the number now comes from {@link stageEntryBudget}, computed once on the stage page — the only
     surface that can see every list, the stage's own fields and the designer's custom questions at
     the same time — and every control here thresholds on the STAGE total. That includes Add and the
     bulk picker: the budget is the stage's, so a list holding three rows is at the cap when the stage
     is, and closing Add on it while the sentence explains why is the honest reading of that.

     ── AND THE SENTENCE THAT USED TO BE HERE MADE A CLAIM THAT WAS FALSE ──────────────────────────

     It said that at exactly 500 rows in one list "the save may still land", because `_bound_rows`
     refuses more than 500 rather than 500. The arithmetic was right about the server and wrong about
     the payload: 500 collection rows plus one entry for an answered singleton plus one for a `_custom`
     container is 502, which is refused — and a stage with an answered singleton or any custom answer
     is the ordinary stage, not an unusual one. The claim was therefore false almost every time it was
     drawn. It is only sayable at all once the whole payload is counted, which is what `stageTotal` is,
     and it is said in the `full` branch below where it is finally true.

     ── THE RANGE, AND WHY IT IS NOT A GUESS DRESSED UP ───────────────────────────────────────────

     `stageEntryBudget` returns two bounds because two of the payload's arms are gated on a fact that
     lives in IndexedDB and not on screen (see its header). The width is at most two entries. The
     THRESHOLDS use the upper bound, deliberately: a warning that arrives late is the failure this
     whole block exists to prevent, and being two rows early at a 500-row ceiling costs a designer
     nothing. The SENTENCE prints the range, because a number this component cannot stand behind is
     the thing the previous version was faulted for.
  */
  const rowsHere = rows.length;
  /** This stage's rows that are NOT in this list. Clamped: the page derives both from one `collections`. */
  const rowsElsewhere = Math.max(0, stageEntries.rows - rowsHere);
  /** The most a save could carry — used for every threshold, so the notice is never late. */
  const stageTotal = stageEntries.rows + stageEntries.other;
  /** The least it could carry. Equal to `stageTotal` whenever the budget is exact. */
  const stageFloor = stageEntries.rows + stageEntries.otherCertain;
  const stageExact = stageEntries.other === stageEntries.otherCertain;
  /** An en dash, not a hyphen: this is a range between two numbers, not a compound word. */
  const stageHeld = stageExact ? `${stageTotal}` : `${stageFloor}–${stageTotal}`;
  /** Rows that certainly fit. Derived from the upper bound, so it never promises room that is not there. */
  const stageRoom = Math.max(0, STAGE_ROW_CAP - stageTotal);
  const blankEntries = stageEntries.other - stageEntries.otherCertain;

  /**
   * A bulk add this list could not take in full — the fact, in the designer's words.
   *
   * STATE RATHER THAN DERIVED, because it is a fact about an ACT and nothing on screen still carries
   * it: the rows that were refused were never created, so there is nothing to look at and count. The
   * dictation refusal in `FieldInput` is the same shape for the same reason.
   *
   * Cleared by the next bulk add that fits, and by nothing else. It stays true after a deletion —
   * "these names were not added" does not stop having happened because room appeared afterwards, and
   * a sentence that vanished on the next keystroke would be one a designer never got to read.
   */
  const [bulkRefusal, setBulkRefusal] = useState<string | null>(null);

  /**
   * Add every ticked record as a row — UP TO WHAT FITS, and say what did not.
   *
   * ── THE FENCE THIS USED TO JUMP IN ONE PRESS ───────────────────────────────────────────────────
   *
   * The picker serves up to `REFERENCE_PAGE_MAX` (200) options and this appended every one of them
   * unconditionally, while the only guard anywhere near it was `disabled={disabled || rowsAtCap}` on
   * the trigger. So at 300 rows — below the notice threshold, nothing on screen — ticking 200 names
   * landed the stage on 500-plus entries in a single press, with no warning at press time and no
   * sentence afterwards until the designer tried to save and got a 422 over the whole stage.
   *
   * ── TAKE WHAT FITS AND NAME WHAT DID NOT, which is this repository's settled answer here ───────
   *
   * `MediaCaptureField.acceptFiles` makes the same choice against the same kind of ceiling and its
   * argument transfers whole: a REFUSAL at this door is a picker that appears to do nothing, and a
   * SILENT truncation is the failure every rule-10 line in this file exists to rule out. There is
   * somebody to tell, right now, before anything is written — so the honest act is to take the rows
   * that fit, leave the list valid, and name the count that did not make it and why.
   *
   * AND IT NAMES THE RECORDS, up to a readable few. "40 were not added" tells a designer who ticked
   * 200 nothing about which 40 to re-pick, and they cannot go and look: the picker clears `picked` and
   * closes itself the moment it calls this, so its ticks are gone from the screen along with the panel.
   * Reopening it would tick back the ones that LANDED and leave the rest bare, which is an answer — but
   * an answer that costs a second trip through a 200-row panel, and one nobody thinks to make unless
   * they were told there was something to look for. Six then "and N more" is the shape
   * `ReportChart`'s "Not shown:" line already uses for the same problem; a 200-name sentence in a
   * `role="status"` region is not readable by anyone, sighted or listening.
   *
   * `stageRoom` AND NOT `STAGE_ROW_CAP - rows.length`: the budget being spent is the stage's, so a
   * roster with room in its own list has none at all when the stage's other lists have filled it.
   */
  function addFromReferences(options: DwReferenceOption[]) {
    if (!bulkField) return;
    const taken = options.slice(0, stageRoom);
    const dropped = options.slice(stageRoom);
    const refused = dropped.length;
    const created = taken.map((option) => {
      const row = blankRow();
      // Hydrated exactly as a single pick is, through the same table, so a roster built in one go
      // and one built name by name hold identical records. The server re-hydrates at save either
      // way; this is what makes the thirty rows readable before then.
      return { ...row, ...hydrateFromReference(entity, bulkField, option, row, ""), [bulkField.key]: option.id };
    });
    // GUARDED, so a press that could take nothing does not commit an identical array: `onRowsChange`
    // is the page's `patchCollection`, whose own guard is a value comparison, but arming an autosave
    // and a `removedFrom` decision over a no-op is work nobody asked for.
    if (created.length) onRowsChange([...rows, ...created]);
    // Deliberately NOT opened: thirty freshly expanded panels is not a form, it is a wall. Each row
    // already shows its name and its required-field count, which is what a designer checks next.
    setOpenKey(null);
    setBulkRefusal(
      refused === 0
        ? null
        : `Added ${taken.length} of the ${options.length} records you chose. ` +
            `${refused === 1 ? "The other one was" : `The other ${refused} were`} not added: this stage was already ` +
            `holding ${stageHeld} of the ${STAGE_ROW_CAP} entries one save can carry — counted across every list on ` +
            `it together — so there was room for ${stageRoom} more. Not added: ` +
            `${dropped
              .slice(0, DROPPED_NAMES_SHOWN)
              .map((option) => option.label)
              .join(", ")}` +
            `${refused > DROPPED_NAMES_SHOWN ? ` and ${refused - DROPPED_NAMES_SHOWN} more` : ""}. Nothing has been ` +
            `recorded for ${refused === 1 ? "it" : "them"} — choose ${refused === 1 ? "it" : "them"} again after ` +
            `deleting rows here or in another of this stage's lists, or record ${refused === 1 ? "it" : "them"} on ` +
            `another stage.`
    );
  }

  /* ══════════════════════════════════════════════════════════════════════════════════════════════
     REORDERING A COLLECTION'S ROWS — A PLUS, TWO ARROWS, A GRIP, AND ONE COMMIT BEHIND ALL OF THEM
     ══════════════════════════════════════════════════════════════════════════════════════════════

     The owner asked on 2026-08-25 for reordering to be served by "a plus button, up down arrows and
     drag and drop as well". Two of the three were already here — the Add button in the header, the
     two arrows on every row — and this is the third. It is the SAME gesture the custom-sections
     editor got, through `components/hooks/useDragReorder.ts`, and not a second implementation of it:
     `components/sketches/RankableList.tsx` is the first renderer over that hook, `CustomSectionsEditor`
     the second, this is the third. Read that hook's header before changing anything here — it carries
     the five rules that make the gesture honest (rectangles snapshotted at pointerdown; the
     ARRANGEMENT snapshotted with them so a gesture whose ground moved is abandoned rather than
     guessed at; nothing committed until release; every move announced in words; teardown on unmount),
     and each of them is a bug already paid for once.

     ── THE ORDER IS EXPRESSIBLE ALL THE WAY TO THE PRINTED REPORT ─────────────────────────────────

     That is the only reason any of these controls may exist. A control that appeared to arrange a
     report and did not would be worse than no control at all, so the chain was read end to end
     rather than assumed, and it is written down here to be re-checked rather than re-derived:

       * `buildStageEntries` (`lib/designWorkshopStore.ts`) sends `ordinal: rowIndex` — the ARRAY
         ORDER at send time, deliberately not any stored `_ordinal`.
       * `save_stage` (`backend/app/services/design_workshops.py`) writes it:
         `ordinal = entry.ordinal if entry.ordinal is not None else index`, and the UPDATE branch's
         four columns are exactly `{data, ordinal, deletedAt}` + provenance.
       * `entry_rows` reads the stage back with `order={"ordinal": "asc"}`.
       * `assemble_workshop_data` — the report builder's own input — sorts `entries` by `r.ordinal`
         before grouping them into `collections`, and nothing in `report_model.py` sorts again.

     So the row a designer drags to the top is the row that prints first in the .docx, and the same
     number is what `design_ratings` calls `placedPosition` and what the provenance page prints as
     "row 3". `StageEntryIn.ordinal` says so in the request schema in as many words: "orders a
     collection's rows and is what a client sends after a drag-to-reorder."

     ── AND A REORDER IS A CHANGE, SO IT HAS TO REACH THE DRAFT ────────────────────────────────────

     It does, through the one path every field edit already uses: `onRowsChange` → the stage page's
     `patchCollection` → `setCollections`, which the page's autosave effect watches. There is no
     `markDirty` to call on this screen — the stage form replaced the unsaved-changes prompt with the
     draft store plus a flush before every navigation (decision 6 in the stage page's header), so
     "dirty" here means `dirtyAt` on the banked draft. What makes a reorder visible to that effect is
     that its guard is a VALUE comparison, `sameSnapshot` → `sameStoredValue`, and `sameStoredValue`
     walks an array INDEX BY INDEX: two rows in a new order genuinely differ from the banked snapshot,
     so the write is armed and `dirtyAt` set exactly as typing into a box would set it. If that guard
     is ever made cheaper — lengths, a set of ids, a per-row hash compared unordered — a reorder
     becomes invisible to it, the arrangement never reaches IndexedDB or the wire, and these controls
     become the lie the previous paragraph exists to rule out. This comment is the one that says so.

     ── WHY THE ARROWS ARE THE PRIMARY PATH ───────────────────────────────────────────────────────

     A drag is a pointer gesture: unreachable from a keyboard, from a switch device and from a screen
     reader. So the arrows are never hidden or disabled in favour of the grip, the grip answers the
     arrow keys too, and an arrow press announces itself in the hook's own words rather than silently.

     ── ANDROID DELIBERATELY HAS NO GRIP, AND THAT DIVERGENCE IS RECORDED RATHER THAN COPIED ───────

     `ui/designworkshop/StageScreen.kt` says so at its collection list: "Reorder is two arrow buttons
     rather than a drag handle, and that is a dependency decision as much as an ergonomic one: a
     reorderable LazyColumn means either a third-party library or a hand-rolled …". So the two clients
     agree on the ARROWS, which is the path every designer has, and the web adds a third affordance
     the handset does not — an addition, not a disagreement, and the report is identical either way
     because both write the same `ordinal`. What they must NOT diverge on is the semantics of a move,
     and until now they did: the handset does `reordered.add(target, reordered.removeAt(index))` — a
     move — while these arrows swapped two elements in place. Identical for the ±1 an arrow asks for,
     and this file now expresses it the same way the handset does, so the drag and the phone cannot
     mean two different things by "put this third".

     ── WHY NOTHING HERE IS A `useCallback`, unlike the sections editor ────────────────────────────

     That screen owns its list in `useState` and commits through updater functions, so its callbacks
     can genuinely be stable across renders. Here `rows` and `onRowsChange` are PROPS — the stage page
     owns the array — so a memo over them would be rebuilt on every render anyway and would only hide
     that fact. The hook keeps the in-flight drag in its own `useState`, so a fresh handler object per
     render costs nothing and loses nothing.
  */

  /** The rows' drag ids, which are their React keys: one identity, so the two cannot disagree. */
  const dragIds = useMemo(() => rows.map(keyOf), [rows]);

  /**
   * The one commit both the arrows and the drag go through.
   *
   * `moveIndex` — the hook's own pure helper — rather than the swap this function used to do. For the
   * ±1 the arrows ask for, a swap and a move are the same operation; for a drag across five rows a
   * swap is not a reorder at all, and letting the two paths write through two different array
   * operations is exactly how they would drift into disagreeing about what "move this to position 2"
   * means.
   *
   * THE ORDINAL IS NOT STORED ON THE ROW HERE, and that is not an omission. It is rewritten from the
   * array order at send time by `buildStageEntries`, and a row carrying a stale `_ordinal` after a
   * move would be sorted straight back to where it came from the next time the stage was read — the
   * reorder would look like it had not taken. `entryDataOf` strips `_ordinal` on the way out for the
   * same reason.
   */
  function commitMove(from: number, to: number) {
    if (from === to) return;
    if (from < 0 || from >= rows.length) return;
    if (to < 0 || to >= rows.length) return;
    onRowsChange(moveIndex(rows, from, to));
  }

  const drag = useDragReorder({
    order: dragIds,
    // A save in flight, or no entitlement to edit this stage. The hook refuses to start a gesture,
    // and the grip is rendered disabled beside the arrows for the same `disabled` — a control that
    // looked live and did nothing is the failure this pair avoids.
    locked: Boolean(disabled),
    labelFor: (key) => {
      // BY KEY, NOT BY THE INDEX THE CALLER HAPPENED TO HOLD. `announceMove` is called after a
      // commit, when this render's `rows` still describes the OLD arrangement; looking the row up by
      // its key finds the right row either side of that commit, where an index would name the row it
      // swapped with and announce the wrong title.
      const index = dragIds.indexOf(key);
      const row = rows[index];
      return row ? rowTitle(entity, row, index) : entity.title;
    },
    onReorder: commitMove
  });

  function move(index: number, delta: number) {
    const target = index + delta;
    if (target < 0 || target >= rows.length) return;
    commitMove(index, target);
    // BOTH PATHS SPEAK ALIKE. The drag announces itself from inside the hook; an arrow press has to
    // say the same sentence by hand, or the PRIMARY path is the silent one — the reader who cannot
    // use the pointer gesture is precisely the reader who needs to hear where the row went.
    // `announceMove` is the hook's own wording, so the two cannot drift into two vocabularies.
    drag.announceMove(dragIds[index], target);
  }

  function addRow() {
    // The button is already `disabled` at the cap, and this says so a second time on purpose: the same
    // budget bounds three controls now (this, the bulk picker, the sentence), and a fourth path added
    // later that forgets the gate would take the stage over the line silently. One row, so there is
    // nothing to report — the sentence above the list is already saying why nothing can be added.
    if (stageRoom < 1) return;
    const row = blankRow();
    onRowsChange([...rows, row]);
    // Opened immediately: adding a row and being shown a collapsed empty strip makes the button
    // look like it did nothing, and the second press then adds a second empty row.
    setOpenKey(row._clientKey ?? null);
  }

  function removeRow(index: number) {
    const removed = rows[index];
    // THE REMOVED ROW IS HANDED OVER, NOT JUST THE SHORTER LIST. The page cannot tell a deleted
    // repository row from a blank one added and binned in the same breath by comparing lengths —
    // and it used to try, which is how "Add prototype, change your mind, press the bin" armed a
    // sweep that deleted every prototype the office had written. See {@link removalIsADeletion}.
    onRowsChange(
      rows.filter((_, position) => position !== index),
      removed
    );
    if (openKey && removed && keyOf(removed, index) === openKey) setOpenKey(null);
  }

  /**
   * The cap, said out loud once it is within reach — RULE 10. Three states, and they are not the same
   * fact: the stage is over the line, it is exactly on it, or it is close enough to act on.
   *
   * THRESHOLDED ON THE STAGE TOTAL, NEVER ON `rows.length` — the whole argument is in the block above
   * `addFromReferences`. `over` means a save of this stage is refused as it stands; `full` means it
   * still lands and one more row does not; `near` starts at 90% of the cap, early enough that a
   * designer can still move a list to another stage rather than being told at the moment saving stops.
   *
   * WHERE THE OTHER ROWS ARE IS PART OF THE SENTENCE. "This list holds 470" is not actionable when the
   * remedy — delete rows, or move some of this to another stage — may belong to a different list
   * entirely; "470 of the 500 one save can carry — 200 in this list, 269 in this stage's other lists and
   * 1 for this stage's own fields" tells the designer where to go.
   */
  const capState: "over" | "full" | "near" | null =
    stageTotal > STAGE_ROW_CAP
      ? "over"
      : stageTotal >= STAGE_ROW_CAP
        ? "full"
        : stageTotal >= STAGE_ROW_CAP_NOTICE_AT
          ? "near"
          : null;
  /** Add and the bulk picker are closed on every list of a stage that has no room for one more row. */
  const rowsAtCap = capState === "over" || capState === "full";
  const capBreakdown = [
    `${rowsHere} in this list`,
    rowsElsewhere > 0 ? `${rowsElsewhere} in this stage's other lists` : null,
    stageEntries.other > 0 ? `${stageEntries.other} for ${stageEntries.otherLabel}` : null
  ]
    .filter((part): part is string => part !== null)
    .reduce((sentence, part, index, parts) =>
      index === 0 ? part : index === parts.length - 1 ? `${sentence} and ${part}` : `${sentence}, ${part}`
    );
  /*
    THE WIDTH OF THE RANGE IS EXPLAINED WHERE THE RANGE IS PRINTED, or it reads as a component that
    cannot count. It is never more than two entries and it is always the same two: a blank singleton
    and a keyed-but-blank custom container are sent by a stage this browser has downloaded and withheld
    by one it has not, and nothing on this screen distinguishes those two stages.
  */
  const rangeWhy = stageExact
    ? ""
    : ` A range because ${blankEntries} of those entries ${blankEntries === 1 ? "is" : "are"} blank: a blank one is ` +
      `sent by a stage this browser has already downloaded and withheld by one it has never read, and which of the two ` +
      `this is cannot be seen from the form.`;
  const capNotice =
    capState === null
      ? null
      : capState === "over"
        ? `This stage holds ${stageHeld} of the ${STAGE_ROW_CAP} entries one save can carry — ${capBreakdown}. ` +
          `${
            stageFloor > STAGE_ROW_CAP
              ? "It is over that cap, so every save of this stage is being refused as a whole"
              : "It may already be over that cap, and if it is, every save of this stage is refused as a whole"
          }: a 422 over the stage, not over the row that crossed the line, so nothing on it can be sent — including ` +
          `answers typed into other lists. Adding is closed on every list of this stage. Nothing already recorded is ` +
          `lost: delete rows from this stage's lists, or record the rest on another stage.${rangeWhy}`
        : capState === "full"
          ? `This stage holds ${stageHeld} of the ${STAGE_ROW_CAP} entries one save can carry — ${capBreakdown}. That ` +
            `is the cap exactly: this stage still saves, and one more row does not, so adding is closed on every list ` +
            `of it. Delete a row here or in another of this stage's lists to make room, or record the rest on another ` +
            `stage.${rangeWhy}`
          : `This stage holds ${stageHeld} of the ${STAGE_ROW_CAP} entries one save can carry, counted across every ` +
            `list on it together — ${capBreakdown}. There is room for ${stageRoom} more row` +
            `${stageRoom === 1 ? "" : "s"} anywhere on this stage, this list included. Over the cap the stage refuses ` +
            `to save as a whole, not just the list that crossed the line.${rangeWhy}`;

  /**
   * The two sentences this table's status region carries, newest fact first.
   *
   * ONE REGION AND ONE BOX FOR BOTH, rather than a second amber block: they are two halves of one
   * subject and a designer who has just been told 40 names were refused needs the stage's total in the
   * same breath. Keyed by a constant so React reuses each paragraph across renders — a key derived from
   * the text would remount the node on every recount and, inside a live region, re-announce a sentence
   * that had not changed.
   */
  const statusNotices = [
    bulkRefusal ? { key: "bulk", text: bulkRefusal } : null,
    capNotice ? { key: "cap", text: capNotice } : null
  ].filter((notice): notice is { key: string; text: string } => notice !== null);

  return (
    <section className="panel p-4">
      <header className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-display text-lg font-bold text-ink-900">{entity.title}</h2>
          {entity.description ? <p className="mt-1 text-sm leading-6 text-ink-muted">{entity.description}</p> : null}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {bulkField ? (
            <StageReferenceMultiPicker
              workshopId={workshopId}
              field={bulkField}
              // Ids already on the list, so a name cannot be added twice — a duplicate roster row is
              // a participant counted twice in every table the report prints.
              alreadyChosen={rows.map((row) => inputValue(row[bulkField.key])).filter(Boolean)}
              onAdd={addFromReferences}
              /*
                CLOSED AT THE CAP AND BOUNDED BELOW IT, and the two halves are both needed. `rowsAtCap`
                is the stage's total, so this trigger is shut on a roster of three rows when the stage's
                other lists have spent the budget. Below the cap the trigger is live — and it can serve
                up to `REFERENCE_PAGE_MAX` (200) options in one press, which is why `addFromReferences`
                bounds what it takes instead of trusting this flag: 300 rows plus 200 names is over the
                line in a single press, from a state in which this gate is legitimately open.
              */
              disabled={disabled || rowsAtCap}
              triggerLabel={`Add several from ${bulkField.label.toLowerCase()}`}
            />
          ) : null}
          {/* THE ADD CONTROL, AND ITS LABEL SAYS WHAT IT ADDS rather than "Add" or "New row": in a
              stage carrying three collections, three identical buttons name nothing. The text is the
              entity's own singular title, so it is the registry's word and not this file's.

              NOTHING MAY BE ADDED INSIDE THIS BUTTON. Its accessible name is its rendered text, and
              `e2e/stage19-attendance-signature.spec.ts` matches it exactly (`/^Add certificates &
              attendance$/i`) — an sr-only hint tucked in here would rename the control and break that
              assertion. The cap sentence is a sibling below for exactly that reason. */}
          <button type="button" className="field-button" disabled={disabled || rowsAtCap} onClick={addRow}>
            <Plus className="h-4 w-4" aria-hidden />
            Add {entity.title.replace(/s$/, "").toLowerCase()}
          </button>
        </div>
      </header>

      {/*
        WHY A DRAG DID SOMETHING, IN WORDS — the consumer half of `useDragReorder`'s rule 4.

        ALWAYS MOUNTED, whether or not it holds anything: assistive technology announces mutations
        only inside a region that already existed when the page settled, which is the rule `Toast`'s
        always-mounted viewport follows and the reason a region created at the moment of the
        announcement says nothing at all. One region per table, because one table is one list — and a
        stage renders one `CollectionTable` per collection, so two lists never share a region and an
        announcement about a prototype cannot be cut off mid-sentence by one about a cost line.
      */}
      <p aria-live="polite" className="sr-only">
        {drag.announcement}
      </p>

      {/*
        THE CAP AND THE REFUSED BULK ADD — rule 10, in a region that is MOUNTED BEFORE IT HAS ANYTHING
        TO SAY.

        The `role="status"` used to be on the amber box itself, so the region came into existence in the
        same commit as its first sentence — and assistive technology announces mutations only inside a
        region that ALREADY EXISTED when the page settled. A designer using a screen reader therefore
        heard nothing at all: not the cap arriving, and not the forty roster names a bulk add had just
        declined to take. That is the same defect the drag announcement above is mounted-always to avoid,
        and the rule `Toast`'s permanently-present viewport follows.

        So the region is this wrapper, which never unmounts, and the box is inserted INTO it. The wrapper
        carries no styling and no margin, so an empty one occupies nothing; `mb-3` lives on the box, or a
        table with nothing to say would still push its list down. `role="status"` rather than `alert`
        because nothing is broken and nothing has been lost — the sentences say what will not fit, and
        interrupting a designer mid-sentence over a ceiling they have not reached yet is not warranted.

        Amber-100 over amber-800 because those are the two rungs of the brand amber that pair
        (`amber-50`/`amber-200` are stock Tailwind and do not), and the triangle is decorative and drawn
        once for the box — the sentences carry the whole message.

        A LIVE COUNT IS ACCEPTED HERE AND WOULD NOT BE EVERYWHERE. `role="status"` implies
        `aria-atomic`, so the whole box is re-read whenever either sentence changes — and the cap
        sentence carries a number that moves. That is the shape §17 forbids for a scroll-position
        readout, and the difference is what makes it right here: this number changes only when somebody
        deliberately adds or deletes a row, it exists only inside the last 10% of the allowance, and the
        thing being announced is the consequence of the act just performed. A continuous readout would
        have to be `aria-live="off"` with the sentence said some other way.
      */}
      <div role="status">
        {statusNotices.length ? (
          <div className="mb-3 flex items-start gap-2 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
            <div className="grid min-w-0 flex-1 gap-2">
              {statusNotices.map((notice) => (
                <p key={notice.key}>{notice.text}</p>
              ))}
            </div>
          </div>
        ) : null}
      </div>

      {rows.length === 0 ? (
        <p className="rounded-md border border-dashed border-line-200 bg-surface-50 px-4 py-6 text-center text-sm text-ink-muted">
          No {entity.title.toLowerCase()} yet. An empty list is a legitimate state on day one of a workshop — add one when
          there is something to record.
        </p>
      ) : (
        <ol className="grid gap-2">
          {rows.map((row, index) => {
            const rowKey = keyOf(row, index);
            const open = openKey === rowKey;
            const panelId = `row-${entity.key}-${rowKey}`;
            const progress = rowProgress(entity, row);
            const rowErrors = errorsByIndex?.[index];
            const rowShift = drag.shiftFor(rowKey);
            const rowDragging = drag.draggingKey === rowKey;
            return (
              <li
                key={rowKey}
                ref={drag.registerRow(rowKey)}
                /*
                  The transform is INLINE because it is a live pixel offset, and `transition-transform`
                  is a CLASS because the global reduced-motion rules in `globals.css` can zero a CSS
                  transition for both of their sources — the OS preference and the in-app toggle — and
                  cannot reach an inline style. That is also why there is no framer-motion here: it
                  would write the same property from JavaScript and the two would fight over it.

                  The dragged row is lifted with a ring rather than dimmed: a translucent row over
                  another row is unreadable at this density, and the ring is the half a reduced-motion
                  reader still gets once the slide has been zeroed.
                */
                style={rowShift ? { transform: `translateY(${rowShift}px)` } : undefined}
                className={
                  rowDragging
                    ? "relative z-10 rounded-md border border-line-200 bg-card shadow-panel ring-2 ring-purple-600/40 transition-transform"
                    : "relative rounded-md border border-line-200 bg-card transition-transform"
                }
              >
                <div className="flex flex-wrap items-center gap-2 px-3 py-2">
                  {/* The ordinal comes from the array position and is printed on the row, so the
                      list and any reference to it ("prototype 3") name the same thing — and it is the
                      same number `save_stage` stores as `ordinal`, the provenance page prints as
                      "row 3" and the report prints these rows in.

                      AND IT IS SAID IN WORDS FOR ANYONE WHO CANNOT SEE THE LIST. A bare "3" in a chip
                      is announced as "3", with nothing to attach it to: a rank that exists only as a
                      place in a visual list is one a screen-reader user cannot read back, which is
                      exactly the question a reorderable list has to be able to answer. The digit is
                      hidden from the accessibility tree and the sentence stands in for it, so the
                      number is announced once rather than twice. */}
                  <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-field-200 text-xs font-semibold text-ink-700">
                    <span aria-hidden>{index + 1}</span>
                    <span className="sr-only">
                      Position {index + 1} of {rows.length}.
                    </span>
                  </span>
                  <button
                    type="button"
                    className="min-w-0 flex-1 truncate text-left text-sm font-medium text-ink-900"
                    aria-expanded={open}
                    aria-controls={open ? panelId : undefined}
                    onClick={() => toggleRow(rowKey)}
                  >
                    {rowTitle(entity, row, index)}
                  </button>
                  {progress.total ? (
                    <span
                      className={
                        progress.filled >= progress.total
                          ? "rounded-full bg-success-100 px-2 py-0.5 text-xs font-medium text-success-600"
                          : "rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800"
                      }
                    >
                      {progress.filled}/{progress.total} required
                    </span>
                  ) : null}
                  {rowErrors && Object.keys(rowErrors).length ? (
                    <span className="rounded-full bg-error-100 px-2 py-0.5 text-xs font-medium text-error-600">
                      {Object.keys(rowErrors).length} to fix
                    </span>
                  ) : null}
                  <div className="flex shrink-0 items-center gap-1">
                    {/*
                      THE GRIP ANSWERS THE KEYBOARD TOO, even though the two arrows beside it already
                      do. It is the affordance that LOOKS like reordering, so a reader who finds it
                      must not have to go and find two other buttons — and a handle that swallowed the
                      arrow keys while doing nothing would read as broken.

                      `touch-none` is what stops the browser claiming the gesture as a scroll before
                      the first `pointermove` arrives. On the handset-shaped screens a designer
                      actually arranges prototypes on, that is the difference between a working drag
                      and one that silently does nothing — and silently doing nothing on touch is why
                      the hook uses pointer events rather than the HTML5 drag API in the first place.

                      DISABLED BELOW TWO ROWS, because there is then nothing to reorder; the arrows
                      are disabled at the ends of the list for the same reason. The wording is
                      `CustomSectionsEditor`'s, verbatim, so the same gesture is described the same way
                      wherever it appears.
                    */}
                    <button
                      type="button"
                      className="grid h-8 w-8 cursor-grab touch-none place-items-center rounded-md border border-line-200 text-ink-500 transition hover:bg-surface-50 active:cursor-grabbing disabled:opacity-40"
                      aria-label={`Reorder ${rowTitle(entity, row, index)} — drag, or use the arrow keys`}
                      disabled={disabled || rows.length < 2}
                      {...drag.handleProps(rowKey)}
                      onKeyDown={(event) => {
                        if (event.key === "ArrowUp") {
                          event.preventDefault();
                          move(index, -1);
                        } else if (event.key === "ArrowDown") {
                          event.preventDefault();
                          move(index, 1);
                        }
                      }}
                    >
                      <GripVertical className="h-4 w-4" aria-hidden />
                    </button>
                    <button
                      type="button"
                      className="grid h-8 w-8 place-items-center rounded-md border border-line-200 text-ink-700 transition hover:bg-surface-50 disabled:opacity-40"
                      aria-label={`Move ${rowTitle(entity, row, index)} up`}
                      disabled={disabled || index === 0}
                      onClick={() => move(index, -1)}
                    >
                      <ArrowUp className="h-4 w-4" aria-hidden />
                    </button>
                    <button
                      type="button"
                      className="grid h-8 w-8 place-items-center rounded-md border border-line-200 text-ink-700 transition hover:bg-surface-50 disabled:opacity-40"
                      aria-label={`Move ${rowTitle(entity, row, index)} down`}
                      disabled={disabled || index === rows.length - 1}
                      onClick={() => move(index, 1)}
                    >
                      <ArrowDown className="h-4 w-4" aria-hidden />
                    </button>
                    <button
                      type="button"
                      className="grid h-8 w-8 place-items-center rounded-md border border-red-200 text-error-600 transition hover:bg-error-100 disabled:opacity-40"
                      aria-label={`Delete ${rowTitle(entity, row, index)}`}
                      disabled={disabled}
                      onClick={() => removeRow(index)}
                    >
                      <Trash2 className="h-4 w-4" aria-hidden />
                    </button>
                  </div>
                </div>
                {open ? (
                  <div id={panelId} className="border-t border-line-200 p-3">
                    {mirror ? (
                      <MirroredEntityBody
                        entity={entity}
                        refField={mirror}
                        data={row}
                        onChange={(key, value) => patchRow(index, key, value)}
                        onPatch={(values) => patchRowMany(index, values)}
                        workshopId={workshopId}
                        errors={rowErrors}
                        disabled={disabled}
                        stageKey={stageKey}
                        rowKey={row._clientKey ?? null}
                        anchorRowKey={rowKey}
                        capture={capture}
                        focus={focus}
                        // BY ENTRY ID, never by index — the same rule as the grids below. See the
                        // note there for what a positional lookup shows a designer.
                        provenance={row._entryId ? provenance?.[String(row._entryId)] : undefined}
                        idPrefix={panelId}
                      />
                    ) : (
                      <>
                        <FieldGrid
                          entity={entity}
                          fields={primary}
                          data={row}
                          onChange={(key, value) => patchRow(index, key, value)}
                          onPatch={(values) => patchRowMany(index, values)}
                          capture={capture}
                          workshopId={workshopId}
                          errors={rowErrors}
                          disabled={disabled}
                          stageKey={stageKey}
                          rowKey={row._clientKey ?? null}
                          anchorRowKey={rowKey}
                          focus={focus}
                          // BY ENTRY ID, never by index. `DwStageProvenance` is keyed that way on
                          // purpose — the server, the report builder and the handset each sort these
                          // rows differently, so a positional lookup would show one participant's edits
                          // under another participant's name in the table that proves who attended.
                          // A row the server has never seen has no entry id and no stamps yet, which is
                          // correct: nobody has set anything on it but the person typing.
                          provenance={row._entryId ? provenance?.[String(row._entryId)] : undefined}
                        />
                        <MissingViewsHint entity={entity} data={row} />
                        <AdvancedDisclosure
                          id={`${panelId}-advanced`}
                          count={advanced.length}
                          refused={needsAttentionIn(advanced, rowErrors, row)}
                          defaultOpen={focus?.rowKey === rowKey && focusIsIn(focus, entity, advanced)}
                        >
                          <FieldGrid
                            entity={entity}
                            fields={advanced}
                            data={row}
                            onChange={(key, value) => patchRow(index, key, value)}
                            onPatch={(values) => patchRowMany(index, values)}
                            capture={capture}
                            workshopId={workshopId}
                            provenance={row._entryId ? provenance?.[String(row._entryId)] : undefined}
                            errors={rowErrors}
                            disabled={disabled}
                            stageKey={stageKey}
                            rowKey={row._clientKey ?? null}
                            anchorRowKey={rowKey}
                            focus={focus}
                          />
                        </AdvancedDisclosure>
                      </>
                    )}
                  </div>
                ) : null}
              </li>
            );
          })}
        </ol>
      )}
      {/* Deleting a row here only removes it from the list on screen, and saying so removes the
          reflex worry that the button is destructive before Save. It is also the honest
          description: the stage save sends the rows that remain, and the server sweeps the rest —
          see the note on `replaceCollections` in the stage page for why that sweep is armed only
          when something was actually deleted. */}
      {rows.length ? (
        <p className="mt-3 text-xs text-ink-500">
          Reordering and deleting take effect when the stage is saved. Nothing is written until then.{" "}
          {/* THE SECOND SENTENCE IS WHY THE CONTROLS EXIST, and it is a claim that was read end to
              end before it was made — see the reorder block above for the four hops from this array
              to the printed table. Telling a designer the order matters when it did not would be the
              worse error of the two, so if that chain is ever broken this sentence comes out with it.
              The third sentence names the three ways in, because a grip is only obvious to somebody
              who already knows it is there: the owner asked for all three affordances, and a designer
              on a laptop reaches for a different one than a designer on a touchscreen. */}
          The order here is the order these rows print in the report. Use the up and down arrows, the
          arrow keys, or drag a row by its handle.
        </p>
      ) : null}
    </section>
  );
}
