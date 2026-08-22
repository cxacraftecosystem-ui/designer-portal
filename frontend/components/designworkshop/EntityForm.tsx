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
import { AlertTriangle, ArrowDown, ArrowUp, ChevronDown, Plus, Trash2 } from "lucide-react";

import { FieldInput, type StageCaptureContext } from "@/components/designworkshop/FieldInput";
import {
  MirroredFieldsDisclosure,
  StageRecordEmbed,
  embeddedRecordId,
  mirrorPointFor,
  mirrorRefField,
  splitMirroredFields
} from "@/components/designworkshop/StageRecordEmbed";
import { StageReferenceMultiPicker } from "@/components/designworkshop/StageReferenceField";
import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
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
   * How many fields BEHIND this control the last save refused. Drives the pill and the auto-open.
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
            refused={refusedIn(groups.workshopAdvanced, errors)}
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
          refused={refusedIn(groups.mirrored, errors)}
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
            refused={refusedIn(advanced, errors)}
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
  focus
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

  const keyOf = (row: DwRow, index: number) => row._clientKey ?? row._entryId ?? `index-${index}`;

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

  function addFromReferences(options: DwReferenceOption[]) {
    if (!bulkField) return;
    const created = options.map((option) => {
      const row = blankRow();
      // Hydrated exactly as a single pick is, through the same table, so a roster built in one go
      // and one built name by name hold identical records. The server re-hydrates at save either
      // way; this is what makes the thirty rows readable before then.
      return { ...row, ...hydrateFromReference(entity, bulkField, option, row, ""), [bulkField.key]: option.id };
    });
    onRowsChange([...rows, ...created]);
    // Deliberately NOT opened: thirty freshly expanded panels is not a form, it is a wall. Each row
    // already shows its name and its required-field count, which is what a designer checks next.
    setOpenKey(null);
  }

  function move(index: number, delta: number) {
    const target = index + delta;
    if (target < 0 || target >= rows.length) return;
    const next = [...rows];
    [next[index], next[target]] = [next[target], next[index]];
    // The ordinal is rewritten from the ARRAY ORDER on save, not stored per row here — a row
    // carrying a stale `_ordinal` after a swap would be re-sorted back to where it came from the
    // next time the stage was loaded.
    onRowsChange(next);
  }

  function addRow() {
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
              disabled={disabled}
              triggerLabel={`Add several from ${bulkField.label.toLowerCase()}`}
            />
          ) : null}
          <button type="button" className="field-button" disabled={disabled} onClick={addRow}>
            <Plus className="h-4 w-4" aria-hidden />
            Add {entity.title.replace(/s$/, "").toLowerCase()}
          </button>
        </div>
      </header>

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
            return (
              <li key={rowKey} className="rounded-md border border-line-200 bg-card">
                <div className="flex flex-wrap items-center gap-2 px-3 py-2">
                  {/* The ordinal comes from the array position and is printed on the row, so the
                      list and any reference to it ("prototype 3") name the same thing. */}
                  <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-field-200 text-xs font-semibold text-ink-700">
                    {index + 1}
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
                          refused={refusedIn(advanced, rowErrors)}
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
          Reordering and deleting take effect when the stage is saved. Nothing is written until then.
        </p>
      ) : null}
    </section>
  );
}
