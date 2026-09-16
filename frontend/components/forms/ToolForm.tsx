"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { mergeById } from "@/components/data/cappedList";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { Field, Select, TextInput } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext } from "@/components/forms/CarryContextBanner";
import type { CarryNode } from "@/lib/carryContext";
import { cmTextFromInches, inchesTextFromCm, propagate } from "@/components/forms/dimensionUnits";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import {
  forgetAcceptance,
  measurementMethodsFor,
  NO_ACCEPTED_MEASUREMENTS,
  rememberAcceptance,
  type AcceptedMeasurements
} from "@/components/forms/measurementMethods";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { seedHasArtisan, type InlineHostSeed, type InlineRecordSurfaceProps } from "@/components/forms/inlineRecordHost";
import {
  artisanPickerOptions,
  craftSelectionVerdict,
  craftsChangeClearsArtisans,
  joinCraftNames,
  useCraftAndArtisanOptions,
  useRecordsOffPage
} from "@/components/forms/recordPickers";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { MultiSelectDropdown } from "@/components/ui/Dropdown";
import { useWorkshopPicker, WorkshopPicker } from "@/components/forms/WorkshopPicker";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { GridMeasurement, MEASUREMENT_GRID_PURPOSE, type GridFiles, type GridGroup } from "@/components/media/GridMeasurement";
import { RecordPhotoMeasure, type MeasureColumn } from "@/components/media/RecordPhotoMeasure";
import { UploadProgress } from "@/components/media/UploadProgress";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RichTextField } from "@/components/richtext/RichTextField";
import { appendStoredParagraph } from "@/components/richtext/storedRichText";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { apiFetch } from "@/lib/api";
import { locationFromForm, numericValue, recordedAtFromForm, recordedTimezoneFromForm, requiredText, textValue, useUnsavedChanges } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, uploadMediaFile, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { hasRank } from "@/lib/permissions";
import type { Artisan, Craft, RecordStatus, ToolDocumentation } from "@/lib/types";
import { makerOptions, traditionOptions } from "@/lib/types";

/*
  ── THE LINKED-ARTISAN LABEL MOVED, IT WAS NOT DROPPED ──────────────────────────────────────────
  This file used to hold `artisanOptionLabel`, which built "Name · Place" for the single-select's
  `<option>` text. The picker is a MULTI-select now and its rows carry a craft heading, so the place
  belongs in `SelectOption.hint` (which is searched as well as drawn) rather than glued onto the
  label with a middle dot — printing it in both would put the village on a 36px row twice. The rule
  now lives in `recordPickers.artisanPickerOptions` beside the ordering it has to agree with, which
  is also the only place a test can drive it. `ProductForm` keeps its own copy because its picker is
  still a single-select of one craft's artisans, where there is no heading to carry the craft.
*/

/**
 * The ids a link multi-select OPENS with: THE RECORD'S OWN SCALAR FIRST, then its stored join rows.
 *
 * ── WHY THE SCALAR LEADS, AND WHY THIS IS NOT "the links, falling back to the column" ──────────
 * The server derives `tool.artisanId := artisanIds[0]` and `tool.craftId := craftIds[0]` from
 * whatever this form sends, and `routes/tools._write_links` REPLACES the whole link set on a PATCH
 * that carries the list. So the seed decides what element 0 is, and element 0 decides whether
 * merely opening a record and pressing Save changes which artisan it names.
 *
 * That is not hypothetical on the artisan side. `ToolArtisan` predates this multi-select: rows in it
 * were written by `POST /tools/{id}/artisans` — the "assign a tool to multiple artisans" panel —
 * which never included the tool's OWN `artisanId`, and `_order_links` returns them `createdAt asc`.
 * So an existing tool documented under A and assigned to B and C comes back with
 * `artisanLinks = [B, C]` and `artisanId = A`. Seeded from the links alone this form would open with
 * B and C ticked, A nowhere, `artisanName` still reading A's name — and the first save would set
 * `artisanId := B` while `artisanName`/`place` (which the server does NOT derive) went on naming A.
 * A record that names one person in its id and another in its text, written by opening it.
 *
 * The craft side is safe today by construction — the migration backfills `ToolCraft` from `craftId`,
 * and `_order_links` sorts `craftLinks` by the position of each name in `craftName`, whose first
 * name is that craft's — but it is safe by an invariant with a moving part in it: a craft RENAMED
 * since the save is no longer findable in the stored string and is appended LAST, which would make
 * `craftLinks[0]` some other craft. Leading with the scalar costs nothing when the invariant holds
 * and repairs the record when it does not.
 *
 * ── AND THE LINKS ARE NOT RE-SORTED ─────────────────────────────────────────────────────────────
 * After the scalar, they arrive in the order the server states — `craftLinks` in `craftName` order,
 * `artisanLinks` oldest first — and both are part of what a save writes back, so re-sorting here
 * would rewrite the record by merely opening it. Duplicates collapse keeping FIRST occurrence (the
 * scalar's position), which is the same `dict.fromkeys` rule the route applies on the way in, so
 * what this form holds is what the server will store. Blanks are dropped.
 *
 * The fallback alone is what a create (`?craftId=` / a seeded artisan) and a row saved before the
 * join table legitimately have.
 */
function initialLinkIds(linked: string[] | undefined, fallback: string | null | undefined): string[] {
  const ids = [...(fallback ? [fallback] : []), ...(linked ?? [])];
  return ids.map((id) => id.trim()).filter((id, index, all) => Boolean(id) && all.indexOf(id) === index);
}

/**
 * The dimension columns the on-device measurement may be accepted into, in the order the boxes are
 * drawn below.
 *
 * ── THE THIRD ENTRY NOW POINTS AT `heightInches`, WHICH EXISTS AS OF 2026-08-27 ───────────────
 * `ProductDocumentation` has carried `lengthInches` / `breadthInches` / `heightInches` since it was
 * written. `ToolDocumentation` stopped at two — `lengthInches` / `breadthInches` and then a plain
 * `height`, alongside `width`, `thickness`, `weight` and `radius` — until 2026-08-27, when
 * `heightInches` was added as a nullable `Decimal(10, 2)` in `backend/prisma/schema.prisma` (an
 * additive migration), listed in `tools.py`'s `_CLEARABLE_COLUMNS` so emptying the box empties the
 * column, and declared on `ToolCreate` / `ToolUpdate` in `backend/app/schemas/records.py` with the
 * same `ge=0` bound the boxes carry. Verified 2026-08-27; re-check with
 * `grep -n heightInches backend/prisma/schema.prisma backend/app/schemas/records.py`.
 *
 * ── WHAT THIS BLOCK SAID BEFORE, AND WHY THE COLUMN WAS WORTH ASKING FOR ──────────────────
 * Until that day the third entry read `key: "height"` and carried a `note` on screen explaining
 * that the column is called just "Height", declares no unit anywhere — not in its name, not in the
 * schema, not on the label a designer reads — and that the proposal was in inches because the two
 * boxes beside it say so. That was the most a client could do and it was not enough. The cost is
 * written down on the server: `measurement_provenance.DIMENSION_FIELDS` is exactly the three
 * `*Inches` names, so a method marker naming `height` was dropped, and an accepted machine reading
 * of a tool's height could never record HOW it was measured, whichever route produced it. The
 * schema's own comment above the new column says the same thing in the same terms — the plain
 * column was "losing the one fact the column name is there to carry". The fix was a column rather
 * than a client change; it was raised here rather than worked around, and it arrived.
 *
 * ── THE `height` COLUMN IS NOT REPLACED, AND SINCE 2026-09-15 IT IS THE CENTIMETRE HALF OF A PAIR ─
 * This paragraph used to end: *"It still holds every number already typed into it, in a unit nothing
 * can name, so its box stays on the form below and keeps working exactly as it did. What it no
 * longer receives is a MACHINE reading."* The first half is still true of the ROWS — a tool saved
 * before the pairing holds two unrelated numbers and nothing converts them on load — and the rest is
 * retired. `height` is now the centimetre partner of `heightInches` (`width` of `breadthInches`),
 * labelled "Height (cm)" on screen, and it DOES receive a machine reading: the accepted value lands
 * in the inch box and `dimensionUnits.propagate` fills the centimetre one from it, on both
 * measurement routes and on both clients.
 *
 * WHAT DID NOT CHANGE IS THE PROVENANCE, which is the whole reason this list still names only the
 * three `*Inches` columns: `measurement_provenance.DIMENSION_FIELDS` is exactly those three, a
 * marker naming `height` is a 422 on the entire save, and a centimetre box filled by conversion makes
 * no claim of its own — the claim is about the reading, and the reading landed in the inch box.
 * Telling the two boxes apart on screen is a copy problem, and the sentence that solves it is a
 * full-width row under the pair in the grid below, pointed at from BOTH inputs by `aria-describedby`.
 * Read the comment above it before rewording either label.
 *
 * ── WHY `thickness` AND `radius` ARE NOT OFFERED ─────────────────────────────────────────────
 * Not an oversight: they are uncontrolled `defaultValue` boxes read straight out of `FormData` at
 * submit, so a proposal has nowhere to land without making two more inputs controlled, and their
 * units are undeclared with no established convention to lean on. Offering a measurement into a box
 * whose unit nobody has ever written down would be inventing one.
 *
 * `width` USED TO BE ON THAT LIST AND IS NOT ANY MORE — it was named here as a third uncontrolled
 * box "as undeclared as `height`'s". It is controlled state now (its inch partner writes it) and its
 * label says "(cm)", so neither half of that sentence survives. It is still not a MEASURE_COLUMN,
 * for the provenance reason above rather than the plumbing one: the reading is proposed into
 * `breadthInches`, which is the column allowed to carry the method, and `width` follows by conversion.
 */
const MEASURE_COLUMNS: MeasureColumn[] = [
  { key: "lengthInches", label: "Length (inches)", unit: "in" },
  { key: "breadthInches", label: "Breadth (inches)", unit: "in" },
  // No `note`, and its absence IS the change: the column states its unit in its own name now, so
  // there is nothing left for a sentence under the button to disclose. `MeasureColumn.note` stays on
  // the type for the next column that needs it.
  { key: "heightInches", label: "Height (inches)", unit: "in" }
];

/**
 * Status policy (backend-enforced; the UI mirrors it): professor+ may pick any status and new
 * records default to APPROVED; everyone below sees a locked chip — creations are forced to PENDING
 * and unauthorized status changes are silently dropped server-side on update.
 */
function StatusField({
  canSetStatus,
  initialStatus,
  onDirty
}: {
  canSetStatus: boolean;
  initialStatus?: RecordStatus;
  onDirty?: () => void;
}) {
  if (canSetStatus) {
    const options: RecordStatus[] = ["DRAFT", "PENDING", "APPROVED", "REJECTED"];
    if (initialStatus === "NEEDS_REVISION") options.push("NEEDS_REVISION");
    return (
      <Field label="Status">
        <Select name="status" defaultValue={initialStatus ?? "APPROVED"} onChange={onDirty}>
          {options.map((status) => (
            <option key={status}>{status}</option>
          ))}
        </Select>
      </Field>
    );
  }
  const text = initialStatus ? initialStatus.charAt(0) + initialStatus.slice(1).toLowerCase().replace(/_/g, " ") : "Pending";
  return (
    <div className="grid content-start gap-1">
      <span className="field-label">Status</span>
      <span
        className="inline-flex h-10 w-fit items-center rounded-full border border-line-200 bg-surface-50 px-4 text-sm font-medium text-ink"
        title="Submitted for review — a reviewer sets the final status."
      >
        {text}
      </span>
    </div>
  );
}

/**
 * ── DICTATION ON THIS FORM: WHICH BOXES HAVE A MICROPHONE, AND WHY THE REST DO NOT ──────────────
 *
 * The owner's instruction (2026-08-28): "All the record pages should have dictation options
 * available, wherever applicable so as to reduce the friction as much as possible." The default
 * therefore flipped — a free-text box HAS a microphone unless there is a reason it must not — and
 * the reasons are written down here so a later reader can tell a decision from an oversight.
 *
 * DICTATED: Toolkit name · Local name · English name · Craft name · Artisan name · Place · Process
 * used in · Material · Suggestions for tool improvement · Remarks. (The last two are
 * `RichTextField`, whose editor carries the microphone at the caret rather than under the box.)
 *
 * NOT DICTATED, and each is a rule rather than a preference:
 *
 *  - **Workshop, Linked crafts, Linked artisans, Maker, Tradition type, Status** — closed
 *    vocabularies and record pickers behind a themed dropdown. There is no free text to speak.
 *  - **Years in use, Height (cm), Width (cm), Length (inches), Breadth (inches), Height (inches),
 *    Thickness, Weight, Radius, Replacement cost** — native number boxes bounded `min={0}`. A recogniser
 *    spells digits out in words, which a number input discards silently, so a spoken answer leaves
 *    the box empty with nothing on screen to say why. The three inch columns also have two
 *    measurement routes of their own, and both PROPOSE a number a person accepts — a spoken third
 *    route would record an acceptance for a reading nobody can re-derive from the photograph.
 *  - **Media capture, process-stage captures, measurement grid photographs** — file pickers.
 *  - **Location** — `LocationFields` is a separate component with its own owner; its free-text
 *    address boxes are named as a handoff rather than reached into from here.
 */
export function ToolForm({
  initial,
  seed,
  footerFields,
  onCreated,
  onCancel,
  onDiscardAndLeave,
  onQueued
}: {
  initial?: ToolDocumentation;
  /**
   * What the picker that opened this form already knows — see {@link InlineHostSeed} for the whole
   * argument, and for why every value it carries lands in a control the designer can see and change.
   */
  seed?: InlineHostSeed;
  /** Back out without saving, without navigating — see `InlineRecordHostProps.onCancel`. */
  onCancel?: () => void;
  /** Banked in the outbox, no id to link — see `InlineRecordHostProps.onQueued`. */
  onQueued?: () => void;
  /**
   * Hand the saved record back instead of navigating, so this form can be mounted INSIDE a dialog.
   *
   * The design-workshop stages pick tools from reference dropdowns, and a designer who found the
   * record missing had to leave the stage they were half-way through, create it on its own page,
   * and come back — losing their place in a 22-stage record. Mounting this same form in a dialog
   * removes that, and it has to be THIS form: a simpler "quick create" would be a second answer to
   * what the record requires, and the two would drift.
   */
  onCreated?: (record: ToolDocumentation) => void;
} & InlineRecordSurfaceProps) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { user } = useAuth();
  const canSetStatus = hasRank(user, "PROFESSOR");
  const formRef = useRef<HTMLFormElement>(null);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  /**
   * `useId` rather than a literal: this form is also embedded inside a design-workshop
   * stage, so two mounted copies must not mint the same id.
   */
  const formId = useId();
  const errorId = `${formId}-error`;
  /** The craft picker's refusal sentence — see `craftLimitNotice`. */
  const craftLimitId = `${formId}-craft-limit`;
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  /*
    THE SEED IS THE DIALOG'S QUERY STRING.

    `/tools/new?artisanId=…&artisanName=…` is how the full-page route learns whose toolkit this is;
    a dialog has no URL, so the same lines below read the seed instead. It sits AFTER the record
    being edited and BEFORE the query string for the only reason that ordering ever has: an edit is
    about a record that already has answers, and a form mounted in a dialog has no query string for
    the seed to be arguing with.
  */
  /*
    ── BOTH LINKS ARE PLURAL NOW, AND THE SCALAR COLUMNS ARE THE FIRST OF EACH ──────────────────

    A tool covers several crafts and is used by several artisans; `ToolCraft` and `ToolArtisan` hold
    all of them. `ToolDocumentation.craftId` / `.artisanId` keep holding the FIRST of each selection
    for backward compatibility with every filter, index, report and carry-forward that reads them —
    so they are DERIVED below rather than held as their own state, which is what makes it impossible
    for the two to drift apart.

    THE STORED LINKS OUTRANK THE SCALAR, AND THE SCALAR OUTRANKS NOTHING. An edit seeds from
    `craftLinks` when the payload carries them and falls back to the single column for a record saved
    before the join table existed (and for a build talking to an API that has not deployed yet) —
    reading the scalar FIRST would silently drop every craft after the first on every save.
  */
  const [craftIds, setCraftIds] = useState<string[]>(() =>
    initialLinkIds(initial?.craftLinks?.map((link) => link.craftId), initial?.craftId ?? searchParams.get("craftId"))
  );
  const [artisanIds, setArtisanIds] = useState<string[]>(() =>
    initialLinkIds(
      initial?.artisanLinks?.map((link) => link.artisanId),
      initial?.artisanId ?? seed?.artisanId ?? searchParams.get("artisanId")
    )
  );
  /*
    THE FIRST OF EACH, WHICH IS WHAT THE COLUMNS HOLD AND WHAT SIX CALL SITES BELOW STILL READ —
    `carryScope`, the carry bag, the payload's `craftId`/`artisanId`, and the seed-completion effect.
    Derived and never stored: a `useState` beside the arrays would be a second register for one fact.
  */
  const craftId = craftIds[0] ?? "";
  const artisanId = artisanIds[0] ?? "";
  /**
   * WHAT THE CRAFT PICKER HAS TO SAY ABOUT THE `craftName` CEILING — "" when there is nothing.
   *
   * TWO SENTENCES, NOT ONE, because there are two states worth distinguishing: a tick that was
   * REFUSED (the selection is unchanged, and saying so is the whole point — a picker that quietly
   * ignores a tick is indistinguishable from a picker that is broken), and a selection that is still
   * over the bound after an untick that WAS applied, which is an instruction rather than a refusal.
   * `craftSelectionVerdict` decides which.
   *
   * IT NEVER OUTLIVES THE SELECTION IT IS ABOUT: rewritten by every craft change, and cleared by
   * both paths that replace the selection wholesale ("Continue" on the carry banner, and "Change").
   * It is NOT raised on load — an inherited over-long record says nothing until the designer touches
   * the picker, which is the moment the instruction is actionable. See `onCraftsChanged` for what is
   * refused and `recordPickers.CRAFT_NAME_MAX_LENGTH` for why any of it is necessary.
   */
  const [craftLimitNotice, setCraftLimitNotice] = useState("");
  // Android parity: picking a linked craft fills the craft name; picking a linked artisan fills the
  // artisan name + place — so these three are controlled.
  const [craftName, setCraftName] = useState(initial?.craftName ?? searchParams.get("craftName") ?? "");
  const [artisanName, setArtisanName] = useState(
    initial?.artisanName ?? seed?.artisanName ?? searchParams.get("artisanName") ?? ""
  );
  const [place, setPlace] = useState(initial?.place ?? searchParams.get("place") ?? "");
  /*
    ── THE FIVE REMAINING FREE-TEXT BOXES, CONTROLLED FOR THE SAME REASON THE THREE ABOVE ARE ──────

    They were uncontrolled `defaultValue` inputs until the dictation sweep of 2026-08-28.
    `DictatedTextInput` is controlled by its caller and cannot be anything else (the argument is in
    that file: a self-controlled box repaints stale text on a form cleared by `formElement.reset()`),
    so a box with a microphone is a box this component holds the string for.

    NOTHING HAS TO CLEAR THEM: this form does not reset in place — it navigates to /tools or hands
    the record to its host and unmounts. If a reset-in-place button is ever added here, these five
    join it, the way `ArtisanForm`'s "Discard this entry" and "Add another artisan" lists work.
  */
  const [toolkitName, setToolkitName] = useState(initial?.toolkitName ?? "");
  const [localName, setLocalName] = useState(initial?.localName ?? "");
  const [englishName, setEnglishName] = useState(initial?.englishName ?? "");
  /**
   * IS "ENGLISH NAME" STILL FOLLOWING "TOOLKIT NAME"? A one-way door, per mount.
   *
   * Whatever is typed into Toolkit name is mirrored into English name AS IT IS TYPED, until the
   * designer touches the English box themselves — from that keystroke on it is theirs and nothing
   * writes it again. A "sticky divorce": there is no re-arming, because the only thing that could
   * re-arm it is a rule about what the two boxes currently hold, and any such rule would one day
   * decide that a name somebody typed was close enough to overwrite.
   *
   * ── WHAT "ALREADY THEIRS" MEANS ON AN EDIT, AND WHY IT IS COMPUTED ONCE, HERE ──────────────────
   * A stored record whose English name DIFFERS from its toolkit name is a record somebody filled in
   * on purpose, so this form opens DIVORCED and mirroring never runs — merely opening a tool and
   * correcting a typo in its toolkit name must not silently replace a saved English name. Empty, or
   * exactly equal to the toolkit name, is nothing to protect and opens armed. RAW equality: no trim
   * and no case fold, because the two columns run through the same server-side title-casing, so
   * anything that differs before it differs after it.
   *
   * ── A REF, AND NOT AN EFFECT ──────────────────────────────────────────────────────────────────
   * A `useRef` because {@link applyToolkitName} both reads and writes it and a stale closure over a
   * `useState` would re-arm a form the designer had already divorced. And the mirror is written at
   * the toolkit box's own write site rather than in a `useEffect` on `toolkitName`, because an
   * effect FIRES ON MOUNT: on an edit whose stored English name is empty — armed, legitimately —
   * it would write the toolkit name into the English box before anybody typed, making the form
   * dirty and changing a saved record by merely opening it. That is the exact failure the seed
   * effect below already forbids in as many words.
   */
  const mirrorArmed = useRef(
    !initial || (initial.englishName ?? "").trim() === "" || (initial.englishName ?? "") === (initial.toolkitName ?? "")
  );
  const [processUsedIn, setProcessUsedIn] = useState(initial?.processUsedIn ?? "");
  const [material, setMaterial] = useState(initial?.material ?? "");
  // Android parity: ordered "Process stages" captures, archived as STAGE_STEP_1, STAGE_STEP_2, …
  const [stageFiles, setStageFiles] = useState<File[]>([]);
  // The measurable dimensions are controlled state so that the two measurement routes below can
  // PROPOSE into them. Neither writes on its own — both end at a button the designer presses — which
  // is why this says "propose" where it used to say "auto-fill": the grid capture stopped filling
  // these boxes by itself when `gridProposal.ts` landed, and a comment describing the old behaviour
  // is how the old behaviour gets put back.
  const [length, setLength] = useState(initial?.lengthInches != null ? String(initial.lengthInches) : "");
  const [breadth, setBreadth] = useState(initial?.breadthInches != null ? String(initial.breadthInches) : "");
  /**
   * TWO HEIGHTS AND TWO WIDTHS, AND SINCE 2026-09-15 EACH PAIR IS ONE MEASUREMENT IN TWO UNITS.
   *
   * `height` is the CENTIMETRE box and `heightInches` its inch partner; `width` is the centimetre
   * box and `breadthInches` its inch partner. Typing in either fills the other through
   * `components/forms/dimensionUnits`. `lengthInches` is standalone and gains no centimetre column.
   *
   * ── EACH BOX IS SEEDED FROM ITS OWN COLUMN AND FROM NOTHING ELSE. NO CONVERSION ON LOAD ────────
   * This block used to read "TWO HEIGHTS, BECAUSE THEY ARE TWO COLUMNS — not two names for one",
   * and it was right about the rows that already exist: a tool saved before the pairing genuinely
   * holds two unrelated numbers, because `height` declared no unit and nothing kept the pair in
   * step. Those rows are still out there, so opening one must not rewrite either box — a load-time
   * conversion would overwrite a number somebody typed with a number derived from a different one,
   * on the next save, silently, for every historic row at once. Conversion fires on USER INPUT
   * ONLY; the note under the boxes says so on screen, and says which of the two to correct.
   *
   * `heightInches` is still the only one of its pair a `measurementMethods` marker may name — see
   * MEASURE_COLUMNS above. A machine reading is accepted into the INCH box and then fills its
   * centimetre partner; the centimetre box carries no provenance and is given none.
   *
   * ── `width` IS CONTROLLED NOW, WHERE IT WAS A `defaultValue` READ OUT OF FormData AT SUBMIT ────
   * It has to be: its partner writes it, and a box the app writes cannot also be read off the DOM
   * at save time without the two being able to disagree. The payload reads this state instead, the
   * way `heightInches` already did.
   */
  const [height, setHeight] = useState(initial?.height != null ? String(initial.height) : "");
  const [width, setWidth] = useState(initial?.width != null ? String(initial.width) : "");
  const [heightInches, setHeightInches] = useState(initial?.heightInches != null ? String(initial.heightInches) : "");
  /**
   * WHICH OF THE THREE `*Inches` BOXES STILL HOLDS A MACHINE'S NUMBER, and what produced it.
   *
   * Written only by an accept button, cleared by a keystroke in the box it describes, and read once —
   * by `measurementMethodsFor` while the save body is built. It holds the accepted TEXT beside the
   * marker, which is the whole mechanism: see `components/forms/measurementMethods.ts` for why a
   * marker that outlives the number it describes is worse than no marker at all.
   *
   * NEITHER CENTIMETRE BOX CAN EVER APPEAR IN HERE, and the pairing did not change that. `height`
   * and `width` are not in `DIMENSION_FIELDS`, so a marker naming one is a 422 on the whole save
   * rather than a dropped hint — `rememberAcceptance` refuses the key itself, which is the guard
   * that survives somebody later pointing a measurement route at the wrong box. A centimetre box
   * filled by its inch partner carries no claim of its own: the claim is about the reading, the
   * reading landed in the inch box, and that is where the marker names it.
   *
   * EMPTY ON AN EDIT FORM, deliberately. A stored dimension arrives with no marker in the payload —
   * its method, if it ever had one, is already in the record's own provenance — and this form has no
   * grounds to make a fresh claim about a number it did not watch anybody produce.
   */
  const [accepted, setAccepted] = useState<AcceptedMeasurements>(NO_ACCEPTED_MEASUREMENTS);
  const [gridFiles, setGridFiles] = useState<GridFiles>({});
  /**
   * The photograph the DETERMINISTIC panel measured from, and whether its reference was a grid.
   *
   * Kept beside `gridFiles` rather than inside it because the two are different evidence: a grid file
   * is a photograph a model was asked to read, and this one is a photograph a person marked. Both are
   * stored with the record — the number is worthless to a later reader without the frame it came off.
   */
  const [measurePhoto, setMeasurePhoto] = useState<{ file: File; isGrid: boolean } | null>(null);
  /**
   * ── THE ROWS THE RECORD ITSELF CARRIES, AND THE ONE SOURCE THAT DOES NOT NEED A NETWORK ───────
   *
   * `GET /tools/{id}` hydrates `ToolCraft.craft` and `ToolArtisan.artisan` (see `RELATIONS` in
   * `routes/tools.py`), and until this line THIS FORM THREW BOTH AWAY: `initialLinkIds` above reads
   * the payload for its IDS only, and the sole way to put a NAME to a link was `useRecordsOffPage`,
   * a by-id `apiFetch` with a silent `.catch` and an `attempted` ref that never retries. Offline —
   * the state this app is built for — that fetch always fails, so any craft past `/crafts`' 100-row
   * page or any artisan past `/artisans`' first page was permanently absent from the option lists.
   * A multi-select draws no placeholder for a row it does not have, so the control read "2 selected"
   * over one chip, and every rule keyed on "can this form place it" took its do-nothing branch:
   * `applyFirstArtisan` left `artisanName`/`place` naming the artisan the designer had just
   * unticked while the save moved `artisanId` to the invisible one, and `sittingCraftName` banked
   * the JOINED craft string into the singular carry bag.
   *
   * The handset never had this hole — `knownCraftNames` and `knownArtisans` in `MainActivity.kt` are
   * built from `editing.craftLinks[].craft.name` / `editing.artisanLinks[].artisan` FIRST, with a
   * comment saying exactly why — so this is the web half of a rule that already existed, not a new
   * one. `craft`/`artisan` are optional on the link types (an older build, or a masked read, carries
   * ids alone), which is what the `filter` is for.
   *
   * MERGED BEHIND the loaded rows everywhere, never in front: a row read from the register a moment
   * ago is fresher than the copy embedded in this payload, so a craft renamed since the tool was
   * saved still shows its current name.
   */
  const embeddedCrafts = useMemo(
    () => (initial?.craftLinks ?? []).map((link) => link.craft).filter((craft): craft is Craft => Boolean(craft)),
    [initial?.craftLinks]
  );
  const embeddedArtisans = useMemo(
    () =>
      (initial?.artisanLinks ?? [])
        .map((link) => link.artisan)
        .filter((artisan): artisan is Artisan => Boolean(artisan)),
    [initial?.artisanLinks]
  );
  /**
   * The craft and artisan dropdowns' contents, and what they are NOT showing.
   *
   * Shared with ProductForm, which asks the identical question and had the identical defect in it —
   * see `forms/recordPickers` for the three requests this makes and for the 100-row ceiling that
   * made the third one necessary. `referenceState` still means what it did ("can I see this
   * artisan?" and "is there any signal?" are different answers, and `useCarryContext` treats them
   * differently); it lives in the hook only because it is settled by the same load.
   */
  const {
    artisans,
    crafts,
    referenceState,
    craftCut,
    craftArtisanCut,
    craftRosterKey,
    artisansLoadedForCraft,
    // The four state sentences, built in the hook so this form and its twin cannot word one failure
    // two ways -- see `CraftAndArtisanOptions.craftNotice` for what each of them covers.
    craftNotice,
    craftEmptyLabel,
    craftArtisanNotice,
    craftArtisanEmptyLabel
  } = useCraftAndArtisanOptions({ craftIds, artisanIds, knownArtisans: embeddedArtisans });
  /**
   * EVERY CRAFT THIS TOOL IS LINKED TO IS ALWAYS AN OPTION, wherever each of them sorts.
   *
   * The hook above already does this for the ARTISANS and, until this line, for nobody else — so the
   * defect `useRecordOffPage` was written to close was still fully present on the craft dropdown of
   * this form and of ProductForm, on the same screen as the artisan dropdown that had been fixed.
   * `/crafts` is clamped to 100 rows and ordered NAME ASCENDING (deliberately, see the ordering
   * comment in `routes/crafts.py`), and this database holds 178 crafts (counted 2026-08-15), so the
   * cut is stable and always falls in the same place: every toolkit of a craft whose name sorts past
   * it opened with its craft dropdown reading "Unlinked / type below" — beside a REQUIRED "Craft
   * name" box holding the right name. The stored link was intact and would have been saved
   * untouched, but the form said it was not, and the obvious repair for a craft that looks unlinked
   * is to pick one, which is the single action that really does rewrite the link.
   *
   * ── PLURAL SINCE THE PICKER BECAME A MULTI-SELECT, AND THE FIRST ID IS NOT ENOUGH ─────────────
   * This read `useRecordOffPage<Craft>("/crafts", craftId, crafts)` while one craft was all a tool
   * could hold. Each linked craft is past the cut or not independently of the others, so recovering
   * only the first would leave a tool linked to three crafts opening with one tick drawn and two
   * missing — and a multi-select draws no placeholder for a row it does not have, so the link is
   * simply invisible rather than visibly wrong. `useRecordsOffPage` is the same rule in the plural;
   * do not write a variant of it, which is precisely how two forms out of three came to be missing
   * the singular.
   */
  const offPageCrafts = useRecordsOffPage<Craft>("/crafts", craftIds, crafts);
  /*
    AND THE RECORD'S OWN CRAFT ROWS BEHIND BOTH — see `embeddedCrafts` above. `useRecordsOffPage` is
    a network read, so offline it recovers nothing and a craft past the cut was unnameable for the
    whole life of the form; the payload's hydrated `ToolCraft.craft` is the one source that needs no
    signal. Last in the merge, so a row read from the register still wins over the embedded copy.
  */
  const craftOptions = useMemo(() => {
    const loaded = offPageCrafts.length ? mergeById(crafts, offPageCrafts) : crafts;
    return embeddedCrafts.length ? mergeById(loaded, embeddedCrafts) : loaded;
  }, [crafts, offPageCrafts, embeddedCrafts]);
  /** The ticked crafts, as rows — what names the artisan list's group headings. See `craftNameForArtisan`. */
  const selectedCrafts = useMemo(
    () => craftIds.map((id) => craftOptions.find((craft) => craft.id === id)).filter((craft): craft is Craft => Boolean(craft)),
    [craftIds, craftOptions]
  );
  const { dirty: typedSinceMount, markDirty, resetDirty } = useUnsavedChanges();
  const [backPromptOpen, setBackPromptOpen] = useState(false);
  /**
   * WHICH EXIT IS WAITING ON THAT PROMPT — this form's own Cancel button, or the back arrow in the
   * page header. Both raise the same dialog, and until this flag existed both got the same answer.
   *
   * ── THE DEFECT ────────────────────────────────────────────────────────────────────────────
   * "Discard" ran `resetDirty()` and then `leave()`, and `leave()` is `onCancel` — which in the
   * design-workshop stage embed REMOUNTS THIS FORM IN PLACE, because that host is not a dialog and
   * has nowhere to go. So a designer pressed Back, was asked, answered Discard, lost everything
   * they had typed AND STAYED ON THE PAGE, with a second press of Back still needed to do the thing
   * they had asked for. In a dialog the same wiring reads correctly, because the dialog visibly
   * closes; that is why this went unnoticed until the form had a third host.
   *
   * ── WHY THE FLAG MARKS THE CANCEL BUTTON AND NOT THE ARROW ────────────────────────────────
   * The arrow's route into the prompt is `useLeaveGuard`, which is handed a bare `onBlocked`
   * callback and is registered once for the life of the mount — there is no per-press hook to set a
   * flag from. The Cancel button is a call site of this component's own, so it is the one that can
   * say who it is. It is cleared on every way OUT of the prompt, so "set" only ever describes the
   * prompt currently on screen.
   */
  const [promptFromCancel, setPromptFromCancel] = useState(false);
  /**
   * Whether there is unsaved work this form must ask about — the same rule, spelled the same way,
   * as `ArtisanForm` and `ProductForm`.
   *
   * THERE IS DELIBERATELY NO "EMBEDDED, SO DO NOT PROMPT" FLAG; `inlineRecordHost.ts`'s header
   * argues it out. The design-workshop stage page has no unsaved-changes prompt because its draft
   * is durable, but that durability belongs to the stage's fields and not to this form's, which are
   * read only at submit — so suppressing the question here would discard real work in silence.
   */
  const dirty = typedSinceMount;
  // Hands the prompt to the round back control in the page header, which is now the only back
  // control on the page.
  useLeaveGuard(dirty, () => setBackPromptOpen(true));
  // The API includes the record's stored location (not yet in the TS type); pass it so the edit
  // form pre-fills coordinates instead of auto-capturing the editor's current position.
  const initialLocation = initial
    ? ((initial as ToolDocumentation & { location?: LocationInitialValues | null }).location ?? null)
    : undefined;
  const isEdit = Boolean(initial);
  /*
    THE WORKSHOP THIS TOOL WAS DOCUMENTED AT — ONE CONTROL, TWO DROPDOWNS.

    `forms/WorkshopPicker.tsx` owns all of it: the "Type of workshop" box, the "Workshop" box below
    it, the most-recent defaulting, the late-submission gate, and the rule that decides whether the
    chosen workshop is written to `workshopId` or to `designWorkshopId`.

    IT REPLACED THREE CONTROLS WITH TWO. This form used to mount `WorkshopSelect`, then a KIND box,
    then a second workshop box under it — three dropdowns for one question, one of which saved
    nothing and said so in its own hint. The owner's ruling: "we do not need one separately for each
    of the type of the workshops".

    THE DEFAULT IS FOR A NEW RECORD AND FOR NOTHING ELSE. `isEdit` and the two `initial*` ids are how
    the picker learns that this form is open on a record that ALREADY NAMES a workshop; it then opens
    the type box on whichever of the two columns that record uses and never applies "the most recent
    workshop this account can reach" over it. Getting that wrong re-files historic records under
    whatever is newest, and nothing on screen would say a link had moved.
    THE SEED OUTRANKS EVERY DEFAULT. `seed.workshopId` is the design workshop's own linked
    `Workshop`, and a tool created from a WORKSHOP-scoped picker that is filed against a different
    sitting is a tool that picker can never show again. It arrives as `initialWorkshopId`, which is
    the same door a STORED id comes through — so the picker treats it as an answer somebody already
    gave, opens the type box on the ordinary-workshop side, and keeps the probe and the carry bag off
    it. See {@link InlineHostSeed}.
  */
  const workshop = useWorkshopPicker({
    initialWorkshopId: initial?.workshopId ?? seed?.workshopId,
    initialDesignWorkshopId: initial?.designWorkshopId,
    isEdit,
    resetKey: initial?.id ?? null
  });

  /**
   * FINISH WHAT THE SEED (OR THE QUERY STRING) STARTED — an artisan id alone is not a usable answer.
   *
   * The "Linked artisans" picker below is disabled while no craft is ticked and its options are
   * narrowed to the ticked ones, so an `artisanId` arriving on its own lands in a control that is
   * greyed out and reading "Select a linked craft first". The id would still have been SUBMITTED — the payload
   * reads it from state, not from the disabled control — which is precisely the shape the seed is
   * forbidden to have: a parent asserted behind the form, invisible to the person who would have
   * spotted it was wrong. Android's inline record host refuses to assert a parent for that exact
   * reason.
   *
   * So the artisan's own record — already fetched by `useCraftAndArtisanOptions`' by-id lookup,
   * whatever page they sort on — fills the craft, the name and the place, and the dropdown lights
   * up showing who it is.
   *
   * ONLY BLANKS, AND ONLY ON A CREATE. Overwriting is the designer's business, and on an edit a
   * toolkit legitimately carries a craft its artisan does not: silently rewriting it here would make
   * merely OPENING a record change it.
   */
  useEffect(() => {
    if (isEdit || !artisanId) return;
    const known = artisans.find((artisan) => artisan.id === artisanId);
    if (!known) return;
    // ONLY ONTO AN EMPTY SELECTION, which is what `current.length ? current : [...]` says — the
    // multi-select's spelling of the `current || next` this line used while both links were single.
    if (known.craftId) setCraftIds((current) => (current.length ? current : [known.craftId as string]));
    if (known.craft?.name) setCraftName((current) => current || known.craft?.name || "");
    if (known.name) setArtisanName((current) => current || known.name);
    if (known.place) setPlace((current) => current || known.place);
    // Deliberately does NOT call `markDirty`: this is the app filling a box in, not the researcher.
    // A blank new form announcing unsaved work before anybody has typed is what trains people to
    // click through the guard — see the same rule on `acceptFix` in LocationFields.
  }, [artisans, artisanId, isEdit]);

  /**
   * AN EDIT OPENS SHOWING THE CRAFT NAME THAT WILL BE STORED, not the one that is stored today.
   *
   * ── WHAT WAS SILENT ───────────────────────────────────────────────────────────────────────────
   * The body always states `craftIds` (see the payload), so on any PATCH of a tool that has a craft
   * the server derives `tool.craftName := ", ".join(names)` and OVERRIDES whatever the body carries.
   * `onCraftsChanged` keeps the box honest for every selection change, and nothing kept it honest at
   * MOUNT: a tool whose `craftName` was hand-corrected to "Bandhani (Kutch variant)" opened showing
   * that, a designer fixed a typo in Remarks, pressed Save, and the column came back "Bandhani" —
   * a rewrite of a value somebody typed, with no message and no diff on screen. The handler's own
   * header claimed the box "never displays something other than what will be stored"; on an edit
   * that was false before this effect and is true with it.
   *
   * ── THE THREE GUARDS, AND EACH REFUSES A DIFFERENT WRONG WRITE ───────────────────────────────
   *  - ONCE, VIA A REF, AND ONLY ON AN EDIT (`!initial` opens already-reconciled). A create's box is
   *    filled by the carry banner, the seed effect and the picker, and re-deriving over any of them
   *    would be this form arguing with a control the designer just used.
   *  - NOT AFTER ANYBODY HAS TYPED. `typedSinceMount` is the whole form's dirty flag, so a designer
   *    who opens the form and corrects the Craft name box before the off-page craft rows land keeps
   *    their correction — the reconciliation is about what was already stored, not about them.
   *  - ONLY WHEN EVERY TICKED CRAFT CAN BE NAMED. `joinCraftNames` skips ids `craftOptions` cannot
   *    place, so reconciling mid-load would replace a two-craft name with a one-craft one and call
   *    it the truth. It simply waits; `useRecordsOffPage` is what makes the wait terminate, and a
   *    by-id lookup that never lands leaves the stored name standing, which is the honest fallback.
   *
   * Deliberately does NOT call `markDirty`, for the same reason the seed effect above does not: this
   * is the app showing what the record already says, not the researcher editing it.
   */
  const craftNameReconciled = useRef(!initial);
  useEffect(() => {
    if (craftNameReconciled.current) return;
    if (!craftIds.length || typedSinceMount) {
      craftNameReconciled.current = true;
      return;
    }
    if (!craftIds.every((id) => craftOptions.some((craft) => craft.id === id))) return;
    craftNameReconciled.current = true;
    const joined = joinCraftNames(craftIds, craftOptions);
    setCraftName((current) => (joined && joined !== current ? joined : current));
  }, [craftIds, craftOptions, typedSinceMount]);

  const toNum = (value: string) => {
    const n = Number(value);
    return value.trim() && Number.isFinite(n) ? n : null;
  };

  /**
   * A DIMENSION BOX A PERSON IS TYPING IN, which is two facts and not one: the new text, and that
   * whatever a machine proposed into this box is no longer what it holds.
   *
   * A marker is a claim about how THIS number was obtained, so a designer who accepts a geometry
   * reading and then edits the box has left a `PHOTO_GEOMETRY` claim standing over a typed number —
   * a false statement in a record an auditor cannot check, and strictly worse than the `UNRECORDED`
   * an absent marker earns. `forgetAcceptance` returns the same object when there is nothing to
   * forget, so this costs no re-render on a form nobody has measured on.
   *
   * IT IS THE SECOND OF TWO GUARDS AND NOT THE LOAD-BEARING ONE. `measurementMethodsFor` at the
   * payload re-checks each box against the accepted text regardless of how it came to differ; this
   * handler is what additionally catches a person typing the identical digits back by hand.
   *
   * THE CENTIMETRE BOXES DO NOT USE IT and do not need to: `height` and `width` are not in
   * `DIMENSION_FIELDS`, so nothing can ever have recorded an acceptance against either to forget.
   * They have their own factory below, which does the other half of this pairing.
   *
   * AND IT WRITES THE CENTIMETRE PARTNER, where there is one — `breadthInches` fills `width` and
   * `heightInches` fills `height`. `lengthInches` passes no partner because it has none. The
   * partner write is ONE-DIRECTIONAL by construction: it happens inside the handler of the box a
   * person is typing in, and React's `setState` does not re-invoke the partner's own `onChange`, so
   * the partner can never write back. A watcher effect over both values would, and `1 cm →
   * 0.39 in → 0.99 cm` is what that costs per keystroke.
   *
   * WRITING THE PARTNER DOES NOT DISTURB THE MARKER, and that is what makes the pairing safe here:
   * `measurementMethodsFor` compares the INCH box's text against the accepted text byte for byte,
   * and this writes the centimetre box. The acceptance is forgotten because the person typed in the
   * inch box, which is the rule this handler has always enforced.
   *
   * A FACTORY RATHER THAN FIVE INLINE HANDLERS, for a reason outside this file:
   * `e2e/record-number-bounds-unit.spec.ts` reads every number input on this form as ONE LINE of
   * source to check it declares `min={0}`, and a box broken across lines by a multi-statement
   * `onChange` silently drops out of that count. (Its filter is a substring match on the `type`
   * attribute, so this paragraph deliberately does not spell that attribute out — a COMMENT naming
   * it counts as an input and fails the same test, which is how this note was written the first time.)
   *
   * ONLY AN EMPTY BOX CLEARS THE PARTNER, because empty is the one input that means "no value"; a
   * string that cannot be a number at all leaves it exactly as it is. WHICH OF THE TWO A HALF-TYPED
   * DECIMAL IS depends on the control, and these are NATIVE NUMBER BOXES: such an input reports the
   * empty string for text the HTML floating-point grammar refuses, and `3.` is refused, so the
   * keystroke after the point arrives here EMPTY and blanks the centimetre partner until the next
   * digit refills it. `dimensionUnits.propagate`'s rule 4 — the one about a partner that tracks the
   * keystrokes — is therefore about the handset's Compose field and about the measurement routes,
   * which hand it a formatted reading directly; it is not what happens under this factory or its
   * centimetre twin below. That block carries the whole argument, including why these boxes keep the
   * attribute they have and what a save pressed on that one keystroke sends.
   */
  const typeInches =
    (set: (value: string) => void, key: string, setPartner?: (value: string) => void) =>
    (event: React.ChangeEvent<HTMLInputElement>) => {
      set(event.target.value);
      setAccepted((current) => forgetAcceptance(current, key));
      if (setPartner) propagate(event.target.value, cmTextFromInches, setPartner);
    };

  /**
   * A CENTIMETRE BOX A PERSON IS TYPING IN, which writes its INCH partner and nothing else.
   *
   * No acceptance to forget (see above), no marker to disturb, and the same one-directional shape:
   * this is only ever reached from the centimetre box's own change handler and only ever writes the
   * inch box. Same factory reason as `typeInches` — the number-bounds spec reads each box as one
   * line of source.
   */
  const typeCm =
    (set: (value: string) => void, setPartner: (value: string) => void) => (event: React.ChangeEvent<HTMLInputElement>) => {
      set(event.target.value);
      propagate(event.target.value, inchesTextFromCm, setPartner);
    };

  /**
   * WRITE "TOOLKIT NAME", AND MIRROR IT INTO "ENGLISH NAME" WHILE THAT IS STILL ALLOWED.
   *
   * THE ONLY WRITER OF `toolkitName` ON THIS FORM, and it has to stay that way: a second writer that
   * set the state directly would be a path the mirror does not run on, which is how "it works when I
   * type but not when the form fills it in" gets shipped. Today there is exactly one caller — the
   * box itself — because nothing else sets a toolkit name here (`carry.onApply` carries a craft, an
   * artisan, a place and a workshop; the seed carries an artisan). A carry node or a seed that ever
   * does must come through here with `user: false`.
   *
   * `user` SAYS WHO IS TYPING, AND IT IS ONLY ABOUT DIRTY TRACKING. A programmatic write is the app
   * filling a box in, not the researcher, and a blank new form announcing unsaved work before
   * anybody has typed is what trains people to click through the guard — the same rule the seed
   * effect above and `acceptFix` in LocationFields follow. It does NOT gate the mirror: a
   * programmatic toolkit name is still a toolkit name, and the English box is still following it.
   *
   * THE MIRROR COPIES THE RAW STRING, which is the whole of the normalisation question. Both columns
   * are in the server's `TITLE_CASE_FIELDS`, so they are title-cased by the same pure function on
   * write and `titleCase(x) == titleCase(x)`: mirroring the raw text guarantees the two STORED
   * values are identical. `TitleCasedInput` does not transform anything — it renders a "Will be
   * saved as …" hint and passes the event through — so normalising here would be a second
   * implementation of a server rule, and a second implementation can only drift.
   */
  function applyToolkitName(next: string, { user }: { user: boolean }) {
    setToolkitName(next);
    if (user) markDirty();
    if (mirrorArmed.current) setEnglishName(next);
  }

  /**
   * Which carried records this form is still willing to be told about.
   *
   * See the note beside `applies` below. Held in a `useMemo` because `useCarryContext` compares the
   * array's contents through a ref rather than by identity, and a fresh literal every render would
   * be harmless but misleading about that.
   */
  const carryApplies = useMemo<CarryNode[]>(() => {
    const nodes: CarryNode[] = [];
    if (!seedHasArtisan(seed)) nodes.push("craft", "artisan");
    if (!seed?.workshopId) nodes.push("workshop");
    return nodes;
  }, [seed]);

  // Offer the sitting this researcher was last working in, however they got here — the query string
  // only survives a click straight through from the save screen (lib/carryContext). The TOOL in the
  // bag is this form's own subject and is never applied here; a product or process in it belongs to
  // other forms and is left alone rather than dropped, so they still have it.
  const carry = useCarryContext({
    enabled: !isEdit,
    // Both dropdowns are built from exactly these two lists, so "absent from the list" is both
    // "you can no longer reach it" and "this form could not show it" — one check answers both.
    // `craftOptions`, not `crafts`: a carried craft that is merely off the picker's first page is
    // reachable — the by-id lookup fetched it — and pruning it would drop a perfectly good link from
    // the bag for the same "absent from page one" reason the artisan side already corrects.
    scopes: [carryScope("artisan", referenceState, artisans), carryScope("craft", referenceState, craftOptions)],
    // This form has no product, tool or process field, so it neither fills those in nor lets the
    // banner claim it did — they stay in the bag for the forms that do.
    // A KEY THE SEED ANSWERED IS DROPPED FROM THE OFFER TOO, and for the banner's own reason: it
    // names every record it brought so that no prefill is invisible, and naming an artisan that is
    // not the artisan on screen is worse than naming none. The row this form was opened from is a
    // better answer than the last artisan this designer documented anywhere, which is all the bag
    // knows. The craft goes with the artisan because the seeded artisan's own record supplies it
    // (see the completion effect above), and the two disagreeing would be the same lie one level up.
    applies: carryApplies,
    // THE BAG STAYS SINGULAR, and that is a decision rather than an omission. A "sitting" is one
    // craft and one artisan — `CarryContext` carries `craftId`/`craftName` for six other forms that
    // link exactly one — so a carried craft opens this picker with ONE tick, which the designer then
    // adds to. Banking a list here would change what the banner claims on every one of those forms.
    onApply: (context) => {
      // A refusal describes a selection, so it goes when the selection does — one carried craft
      // cannot be over the bound (`CraftCreate.name` is itself capped at 180), and a sentence left
      // standing over a selection that no longer exists is a sentence about nothing.
      if (context.craftId) {
        setCraftIds([context.craftId]);
        setCraftLimitNotice("");
      }
      if (context.craftName) setCraftName(context.craftName);
      if (context.artisanId) setArtisanIds([context.artisanId]);
      if (context.artisanName) setArtisanName(context.artisanName);
      if (context.place) setPlace(context.place);
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });

  /**
   * "Change": drop every carried value so the researcher picks from scratch.
   *
   * IT DOES NOT TOUCH THE ENGLISH NAME, and it never did. The banner carries no name-like field of
   * this record's own, so there is nothing of its doing to undo — and clearing the English box here
   * would be a write the designer did not ask for, on the one box whose whole contract is that the
   * form stops writing it the moment they do.
   */
  function clearCarriedContext() {
    carry.change();
    setCraftIds([]);
    // See `onApply`: the craft picker's refusal is about a selection, and there is none now.
    setCraftLimitNotice("");
    setCraftName("");
    setArtisanIds([]);
    setArtisanName("");
    setPlace("");
  }

  /**
   * THE ARTISANS THE PICKER MAY OFFER: everyone of every ticked craft, plus anyone already ticked.
   *
   * The second half is the rule that has been here since Task 6 and it matters more now, not less:
   * an artisan this form cannot place — off the loaded page, or of a craft whose row never arrived —
   * must still be drawable, or the control silently stops showing a link the record holds. A
   * multi-select has no placeholder to fall back on, so "not in `options`" is "not on screen at all".
   */
  const artisansForCraft = craftIds.length
    ? artisans.filter(
        (artisan) => (artisan.craftId && craftIds.includes(artisan.craftId)) || artisanIds.includes(artisan.id)
      )
    : artisans;

  /**
   * THE FIRST OF AN ARTISAN SELECTION, with the name and place boxes filled IF the first CHANGED.
   *
   * ONE RULE, TWO CALLERS. Both handlers below can move `artisanIds[0]` — one because the designer
   * ticked somebody, the other because a craft deselection dropped whoever was first — and
   * `artisanId` (= `artisanIds[0]`) is what the record links. `artisanName` and `place` are NOT
   * derived by the server: it stores the body's values, which is the courtesy `craftName` cannot
   * have (an artisan recorded under a married name, a hamlet the register spells differently), so
   * this form is the only thing that keeps the two columns agreeing with the link.
   *
   * ONLY ON A CHANGE OF FIRST. The single-select could not tell the difference, because picking
   * anything was picking a different person; a multi-select can. Ticking a SECOND artisan leaves
   * `artisanId` where it was, so rewriting the boxes from it would silently undo a correction the
   * designer typed over a name this form filled in a minute earlier.
   *
   * AND NOTHING AT ALL WHEN THE SELECTION IS EMPTY, or when this form cannot place the new first
   * artisan. Both boxes are required: blanking one because a link was removed refuses the save with
   * a browser bubble on a field nobody touched, and the stored artisan's name is still the honest
   * answer until somebody changes it.
   *
   * ⚠ THAT SECOND DO-NOTHING BRANCH WAS A CORRUPTION DOOR UNTIL `embeddedArtisans` EXISTED, and the
   * reason is worth keeping: a tool linked to A and to B, where B's row is past the first page of
   * `/artisans`, opened offline with B in no option list at all. Unticking A — the one chip on
   * screen — left `artisanIds` as `[B]`, this function could not place B so it wrote nothing, and
   * the save sent `artisanId: B` beside A's name and A's village, which the server stores verbatim
   * (it deliberately does not derive them). The record then linked B while every export printed A.
   * `artisans` now carries the rows the payload itself hydrates, so the lookup succeeds without a
   * network; the branch stays because a link whose artisan this account may not read is still a real
   * state, and doing nothing is still the right answer to it.
   *
   * Returns the first artisan (or `undefined`) so a caller that also banks a sitting need not look
   * the same row up twice.
   */
  function applyFirstArtisan(next: readonly string[]): Artisan | undefined {
    const first = next[0] ?? "";
    const artisan = first ? artisans.find((candidate) => candidate.id === first) : undefined;
    if (artisan && first !== artisanId) {
      setArtisanName(artisan.name);
      setPlace(artisan.place);
    }
    return artisan;
  }

  /**
   * THE CRAFT NAME A SITTING MAY BANK: the FIRST craft's own name, never the joined string.
   *
   * `craftName` on screen is every ticked craft's name joined ", " once several are linked, and
   * `CarryContext.craftId`/`craftName` are SINGULAR — a sitting is one craft, which is what lets six
   * other forms fill one required "Craft name" box from the bag. Banking the joined value puts
   * "Bandhani, Block Printing" in that box beside a craft link pointing at Bandhani alone; the
   * banner labels it "Craft", and `ProductForm` then SAVES it, with no `craftIds` on `ProductCreate`
   * for the server to re-derive anything from. A craft that does not exist is stored in the column
   * every export, report and exact-match craft lookup reads.
   *
   * ONE FUNCTION FOR EVERY `carry.remember(` IN THIS FILE, which is the point of hoisting it. The
   * save path had this expression inline and the tick path did not, so the bag was wrong for exactly
   * as long as the tool went unsaved — and a second writer copying either site could only reopen the
   * gap. The fallback differs by caller (the state box here, `payload.craftName` at submit, which is
   * the same value read off the form), so it is the argument.
   *
   * ── ⚠ AND THE FALLBACK IS REFUSED WHEN IT WOULD BE THE JOINED STRING, WHICH IS THE HALF THIS
   *    FUNCTION SHIPPED WITHOUT ────────────────────────────────────────────────────────────────
   *
   * `?? fallback` was taken whenever `craftOptions` could not NAME `craftIds[0]` — and both callers
   * pass the joined string — so the exact harm described above happened anyway, through the lookup
   * missing rather than through the expression being inline. Offline, a craft past `/crafts`' 100-row
   * page used to be unnameable for the life of the form; `embeddedCrafts` above is the root fix for
   * an edit, and this is the arm that has to hold when even that is absent (a create seeded with a
   * `craftId` from the query string or the bag, whose row is past the cut, with no signal).
   *
   * SO THE FALLBACK IS ONLY SAFE WHILE THE BOX IS ABOUT ONE CRAFT. With nothing ticked the box is a
   * craft name somebody typed and is the whole truth; with exactly one craft ticked it is that
   * craft's name, possibly hand-corrected, and banking it is both correct and the useful thing. With
   * TWO OR MORE ticked and the first unnameable, there is no singular name to give and the honest
   * answer is none: `mergeCarryContext` only overwrites a field when the incoming value is truthy,
   * and a contradicting `craftId` clears the node's old name first, so an ABSENT craft name in the
   * bag is recoverable — the next form simply asks — while a wrong one is stored and exported.
   *
   * IT MATTERS ON AN EDIT TOO. `useCarryContext`'s `enabled` flag (this form passes `!isEdit`) gates
   * only the APPLY effect; `remember` writes the bag unconditionally, by design — an edit is still
   * the researcher telling us where they are sitting. So "edit forms cannot reach this" is not a
   * thing to rely on.
   */
  function sittingCraftName(fallback: string): string {
    const named = craftId ? craftOptions.find((craft) => craft.id === craftId)?.name : null;
    if (named) return named;
    return craftIds.length > 1 ? "" : fallback;
  }

  /**
   * A CRAFT WAS TICKED OR UNTICKED — fill the craft-name box, and drop only what the change actually
   * contradicts.
   *
   * ── THE CRAFT NAME IS REWRITTEN WHILE CRAFTS ARE LINKED, AND THAT COSTS A HAND CORRECTION ─────
   * The server derives `tool.craftName` from `craftIds` (the names joined ", " in tick order) the
   * moment any craft is linked, because a body replayed out of the outbox a fortnight later must
   * still produce a `craftName` that agrees with its links. So a correction typed into the Craft
   * name box cannot survive a craft being ticked, and the honest thing is to show that: the box is
   * written on every selection change, so from that moment on it never displays something other than
   * what will be stored. THE MOUNT IS THE OTHER HALF OF THAT SENTENCE and is not this handler's:
   * an edit that opens over a hand-corrected `craftName` is reconciled once by the effect above
   * (`craftNameReconciled`), because the save would rewrite that column whether the picker was
   * touched or not. The future fix for BOTH halves is an explicit override flag on the wire, not a
   * heuristic here that compares against the previous value and guesses which one the designer meant.
   *
   * WITH NOTHING TICKED THE BOX IS LEFT ALONE, which is not an exception to that rule but the same
   * rule: the server derives `craftName` only when `craftIds` is non-empty, and with an empty list
   * the body's own value stands. Blanking it here would empty a REQUIRED box because a link was
   * removed, and refuse the save with a browser bubble on a field the designer did not touch.
   *
   * A CRAFT THIS FORM CANNOT YET NAME IS SKIPPED RATHER THAN PRINTED AS AN ID, so a box written
   * while an off-page craft's by-id lookup is still in flight can be one name short for a moment.
   * That is cosmetic and self-healing: the server derives the stored value from `craftIds`, which
   * carries the id either way, and `useRecordsOffPage` fills the picker in when the row lands. The
   * case that is NOT cosmetic is a selection this form can name NONE of, where the join answers "" —
   * see the guard at the bottom of the handler, which is on the joined name and not on the count.
   *
   * ── AND THE DESELECTION RULE KEEPS `craftChangeClearsArtisan`'S SPIRIT, IN THE PLURAL ─────────
   * Dropping an artisan is a link deletion, so it is done only where this form KNOWS the artisan
   * practises a craft the designer JUST UNTICKED — never because it cannot see them, and never
   * because their craft merely happens to be absent from the new list. See
   * `recordPickers.craftsChangeClearsArtisans` for the four clauses and for the silent deletion
   * each one exists to stop.
   *
   * THE REMOVED CRAFTS ARE COMPUTED HERE AND PASSED IN, WHICH IS THE WHOLE OF THE SECOND FIX. This
   * handler used to hand the rule only the NEXT list, and "their craft is not in the next list" is
   * true of an artisan whose craft was never ticked at all — which `POST /tools/{id}/artisans` makes
   * an ordinary state, since the assignment panel links an artisan to a tool with no craft check.
   * So ADDING a craft condemned them: a save the designer thought only added a link deleted a
   * `ToolArtisan` row, permanently, with nothing on screen saying so. Adding a craft removes nothing
   * and must therefore drop nothing, which is exactly what an empty `removed` says.
   */
  function onCraftsChanged(next: string[]) {
    /*
      THE ONE CHANGE THIS FORM REFUSES, AND IT IS REFUSED RATHER THAN TRUNCATED.

      `craftName` is bounded at 180 characters on `ToolCreate`/`ToolUpdate` and this box's value is
      echoed verbatim in the body, so a dozen ticks — or one press of "Select all 100" — builds a
      required box the wire will not take, and the 422 names a field the designer never typed in.
      Online that loses the save (`saveOrQueue` will not queue a 4xx); offline it queues a body that
      can never drain and offers no re-pick, so the only control left is Discard.

      Refused, because the two alternatives are both worse: truncating the box would put a name on
      screen that disagrees with the links it was derived from (`_resolve_craft_links`' docstring
      forbids exactly that server-side, for the same reason), and dropping `craftName` from the body
      is not available at all — it is REQUIRED on `ToolCreate`. The selection is left exactly as it
      was, which is what the sentence says, so nothing already ticked is lost. See
      `recordPickers.CRAFT_NAME_MAX_LENGTH` for the full argument and for the half of the fix that
      belongs to the backend and the handset.

      A CHANGE THAT SHORTENS AN ALREADY-OVER-LONG SELECTION IS NEVER REFUSED, which is why the rule
      is handed BOTH lists. An edit can open over the bound — a record saved by the handset, which
      has no limit of its own — and refusing every untick would leave the designer holding the one
      record they cannot repair, by the hand of the check meant to protect them.
    */
    const verdict = craftSelectionVerdict({ nextCraftIds: next, currentCraftIds: craftIds, crafts: craftOptions });
    setCraftLimitNotice(verdict.notice);
    if (verdict.refuse) return;
    const removed = craftIds.filter((id) => !next.includes(id));
    const dropped = craftsChangeClearsArtisans({
      nextCraftIds: next,
      removedCraftIds: removed,
      artisanIds,
      artisans
    });
    if (dropped.length) {
      /*
        A DROPPED FIRST ARTISAN RE-DERIVES THE NAME AND PLACE, and it has to be this handler that
        does it. `artisanId` is `artisanIds[0]`, so dropping the first of the selection changes which
        person the record LINKS while the Artisan name and Place boxes go on reading the old one —
        and the server derives neither (`tools.py`: they are the body's values, so a hand correction
        survives a save). The stored row would then name one person in its id and another in its
        text, in the two columns every report and export prints beside the link.

        `applyFirstArtisan` is the same first-changed rule `onArtisansChanged` obeys, called from
        both so a third writer cannot invent a second one: it writes only when the first ACTUALLY
        changes, and leaves both boxes alone when nothing survives — they are required, and blanking
        a required box because a link was removed refuses the save with a bubble on a field the
        designer never touched.
      */
      const remaining = artisanIds.filter((id) => !dropped.includes(id));
      applyFirstArtisan(remaining);
      setArtisanIds(remaining);
    }
    setCraftIds(next);
    /*
      THE GUARD IS ON THE JOINED NAME, NOT ON THE SELECTION'S LENGTH, and the difference is a
      REQUIRED box going empty. `joinCraftNames` skips ids `craftOptions` cannot name — an off-page
      craft whose by-id lookup is still in flight, or whose lookup 403'd and never will land — so it
      answers "" for a selection that is not empty at all. Guarding on `next.length` therefore blanked
      the Craft name box of a tool whose ONE surviving craft happened to be the unnameable one, and
      the save was then refused by the browser with "Please fill in this field" over a box the
      designer never touched. An unnameable selection leaves the stored name standing, which is the
      same argument the empty-selection arm above already makes.
    */
    const joined = joinCraftNames(next, craftOptions);
    if (joined) setCraftName(joined);
    markDirty();
  }

  /**
   * ARTISANS WERE TICKED OR UNTICKED — a CHANGE OF FIRST artisan fills the artisan name and place.
   *
   * `artisanName` and `place` are NOT NULL columns and the server does NOT derive them from
   * `artisanIds`: it takes the body's values. That is deliberate and it is the courtesy `craftName`
   * cannot have — an artisan recorded under a married name, a hamlet the register spells
   * differently — so a correction typed into either box has to survive the save.
   *
   * WHICH IS WHY THIS WRITES THEM ONLY WHEN THE FIRST ARTISAN ACTUALLY CHANGES, and why that rule
   * lives in `applyFirstArtisan` above rather than here — a craft deselection can move the first
   * artisan too, and one rule written twice is two rules waiting to disagree.
   */
  function onArtisansChanged(next: string[]) {
    const artisan = applyFirstArtisan(next);
    if (artisan) {
      // An explicit pick replaces the remembered context and retires the banner: from here on the
      // artisan on screen is the researcher's own choice, not a suggestion. Banked on every tick,
      // not only on a change of first — engaging with the control at all is the researcher
      // overruling the offer. The bag is singular (see `onApply` above), so it is the first of the
      // ticked artisans that is banked, with the first CRAFT'S OWN NAME beside it rather than the
      // joined string this form's box holds — `sittingCraftName` is why.
      carry.remember(
        {
          artisanId: artisan.id,
          artisanName: artisan.name,
          place: artisan.place,
          craftId,
          craftName: sittingCraftName(craftName)
        },
        { explicit: true }
      );
    }
    setArtisanIds(next);
    markDirty();
  }

  /**
   * Leave this form — to the host's idea of "away", which is not always a navigation.
   *
   * `router.back()` was the only exit, and it is wrong in a dialog: the dialog is not a route, so
   * back pops the REAL history entry and takes the designer out of the half-filled stage they were
   * standing in. Cancel is the most natural way to back out of a modal and it was the one control
   * that lost their place. The save path had already been audited for exactly this hazard (see the
   * `onCreated` branch in `submit`); the cancel path had not.
   */
  function leave() {
    if (onCancel) onCancel();
    else router.back();
  }

  /**
   * Finish the exit the HOST'S OWN back control began, after "Discard" has answered for the typing.
   *
   * `useLeaveGuard` does not delay a navigation, it REFUSES one: the interceptor returns true, the
   * back control abandons what it was doing, and this form is handed the question instead. So
   * nothing is left in flight to resume — only the host knows where the arrow was going, and only
   * the host can start it again. `onDiscardAndLeave` is how it says so.
   *
   * Falls back to the ordinary exit when no host supplies it, which is right for both other hosts:
   * on this form's own route `leave()` is `router.back()`, which IS the navigation the arrow
   * wanted, and in `InlineRecordDialog` closing the dialog is the whole of leaving it. Only a host
   * that can be left without being closed — the stage embed — has anything to add. See
   * `InlineRecordHostProps.onDiscardAndLeave`.
   */
  function leaveAfterDiscard() {
    if (onDiscardAndLeave) onDiscardAndLeave();
    else leave();
  }

  function handleBack() {
    // `promptFromCancel`: this is the form's own Cancel, so "Discard" must NOT complete a
    // navigation nobody started — see the flag's declaration.
    if (dirty) {
      setPromptFromCancel(true);
      setBackPromptOpen(true);
    } else leave();
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Read the form synchronously: React nulls event.currentTarget across the await below.
    const form = new FormData(event.currentTarget);
    // A workshop that has already ended makes this a late submission needing admin approval — say so
    // before anything is written. Resolves true immediately when there is nothing to warn about.
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    setError(null);
    try {
      const exifItems = await collectExifMetadata(
        [...Object.values(gridFiles), measurePhoto?.file, ...stageFiles, ...mediaFiles].filter(Boolean) as File[]
      );
      const exifRemark = exifMetadataToRemark(exifItems);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const location = locationFromForm(form);
      const payload = {
        craftName: requiredText(form, "craftName"),
        place: requiredText(form, "place"),
        artisanName: requiredText(form, "artisanName"),
        toolkitName: requiredText(form, "toolkitName"),
        localName: textValue(form, "localName"),
        englishName: textValue(form, "englishName"),
        processUsedIn: textValue(form, "processUsedIn"),
        material: textValue(form, "material"),
        yearsInUse: numericValue(form, "yearsInUse"),
        height: toNum(height),
        // FROM STATE AND NOT FROM `FormData`, since the centimetre boxes became controlled: this box
        // is written by its inch partner, and reading it off the DOM at submit is a second source
        // that can disagree with what is on screen. `heightInches` already carried this note.
        width: toNum(width),
        lengthInches: toNum(length),
        breadthInches: toNum(breadth),
        // Sent on BOTH the create and the update, because this one object is the POST body and the
        // PATCH body. `update_tool` dumps with `exclude_unset=True`, so a key omitted here would
        // mean "leave the stored value alone" and the single edit that could never be saved would be
        // the one that CLEARS the box. `tools.py` lists `heightInches` in `_CLEARABLE_COLUMNS`,
        // which is the other half of that guarantee: an explicit `null` empties the column.
        heightInches: toNum(heightInches),
        /*
          ── HOW EACH OF THE THREE `*Inches` DIMENSIONS ABOVE WAS MEASURED ────────────────────────
          `{"lengthInches": {"method": "PHOTO_GEOMETRY", "technique": "SCALE"}}` for a reading
          accepted out of `RecordPhotoMeasure`, or the vision model's own `methodMarker` echoed back
          verbatim for one accepted out of `GridMeasurement`. `records.merge_field_provenance` pops
          the key — it is not a column — and merges the method INTO the `{by, byName, at}` stamp it
          was already writing, so the row reads *a vision model estimated this, and this person
          accepted it into the record at that moment* instead of asserting they measured it by hand.

          ── THIS BLOCK USED TO SAY THE OPPOSITE, AND THE SENTENCE IS RETIRED, NOT DELETED ────────
          It was headed "NO `measurementMethods` KEY HERE YET, AND ADDING ONE TODAY BREAKS EVERY
          SAVE" and read: *"It is NOT sendable. `ToolCreate`/`ToolUpdate` do not declare
          `measurementMethods` and their shared `APIModel` is `ConfigDict(extra="forbid")`, so a
          body carrying it is rejected 422 in full — and `saveOrQueue` will not queue a 4xx ("the
          server saw it and said no"), so the record is neither saved nor retried."*

          Every clause of that was TRUE when it was written and the rollout it described has since
          run to the end. The fixed order was `access.REVISION_SKIP_FIELDS`, then the four schema
          declarations, then the clients; the first two landed on 2026-08-27 and this line is the
          third. Verified on 2026-08-27, and do not trust either version of this paragraph on its
          word — both re-checks must answer:

            grep -n "MARKER_BODY_KEY" backend/app/services/access.py
            grep -n "measurementMethods" backend/app/schemas/records.py

          THE PLAIN `height` ABOVE STILL CANNOT CARRY A MARKER, AND STILL SHOULD NOT. It declares no
          unit, so a method stamped on it would say how a quantity was measured without saying what
          the quantity is — and `DIMENSION_FIELDS` is the three `*Inches` names, so a marker naming
          `height` is a 422 that costs the researcher the whole form. Nothing machine-produced lands
          there any more (see MEASURE_COLUMNS above) and `rememberAcceptance` refuses the key even if
          something one day tries. Re-check the three:
          `grep -n "DIMENSION_FIELDS: frozenset" backend/app/services/measurement_provenance.py`.

          ── WHAT MAY BE IN IT, WHICH IS LESS THAN WHAT WAS ACCEPTED ──────────────────────────────
          `measurementMethodsFor` emits a marker ONLY for a box still holding the exact text the
          route proposed. Typed over, cleared, or never accepted and the key is simply not there —
          the server reads absence as `UNRECORDED`, which is honest and is never the false human
          claim. `undefined` and not `null` when there is nothing to say, so the key leaves the
          `JSON.stringify` entirely and a save with no machine measurement is byte-for-byte the save
          this form has always sent. See `components/forms/measurementMethods.ts` for both rules.
        */
        measurementMethods: measurementMethodsFor(accepted, {
          lengthInches: length,
          breadthInches: breadth,
          heightInches
        }),
        thickness: numericValue(form, "thickness"),
        weight: numericValue(form, "weight"),
        radius: numericValue(form, "radius"),
        maker: requiredText(form, "maker") || "UNKNOWN",
        traditionType: requiredText(form, "traditionType") || "UNKNOWN",
        replacementCost: numericValue(form, "replacementCost"),
        suggestionsForToolImprovement: textValue(form, "suggestionsForToolImprovement"),
        // `appendStoredParagraph` and NOT `appendRemarksWithExif`: remarks is a rich-text editor
        // now, so this column may hold a JSON document, and concatenating the EXIF summary onto the
        // end of a JSON string produces a value that is neither valid JSON nor readable prose. The
        // helper appends INTO the document when there is one and is byte-for-byte the old behaviour
        // when there is not.
        remarks: appendStoredParagraph(textValue(form, "remarks") as string | null, exifRemark),
        artisanId: artisanId || null,
        craftId: craftId || null,
        /*
          ── EVERY LINKED CRAFT AND EVERY LINKED ARTISAN, AND THE TWO COLUMNS ABOVE ARE THE FIRST ──
          Not columns: the route pops both and writes `ToolCraft` / `ToolArtisan` rows. When either
          list is present and non-empty the server DERIVES the scalar above it from element 0 and
          overrides whatever this body says, so the two can never disagree — they are sent anyway
          because a list may legitimately be empty and the scalar is then the whole of the answer.
          `craftName` is derived the same way (the names joined ", " in THIS order); `artisanName`
          and `place` are not, which is why `onArtisansChanged` fills those two here.

          ALWAYS SENT, AND `[]` IS A REAL ANSWER. Absent means "leave the stored links alone" and
          `[]` means "no links" — the one distinction the whole contract rests on, and the reason an
          explicit `null` is a 422 rather than a shrug. This form always knows its full selection,
          so it always states it: the single edit that could never be saved would otherwise be the
          one that REMOVES the last link. Same argument as `heightInches` above, one shape along.

          THE ORDER IS THE WIRE CONTRACT. `craftIds` is ordered, the server preserves it, and
          `craftLinks` comes back ordered to match — so these are the arrays exactly as ticked and
          are not sorted here.
        */
        craftIds,
        artisanIds,
        workshopId: workshop.workshopId || null,
        designWorkshopId: workshop.designWorkshopId || null,
        // Below professor no status control is rendered: create submits PENDING, edit resubmits the
        // current status (the backend drops unauthorized changes either way).
        status: requiredText(form, "status") || initial?.status || "PENDING",
        recordedAt,
        recordedTimezone,
        location,
        // extraMetadata stays programmatic (EXIF etc.) — the raw JSON textarea was removed.
        extraMetadata: exifItems.length ? { mediaExif: exifItems } : {}
      };
      // Offline this queues instead of failing. Three groups, three batches: the measurement grids,
      // the numbered process-stage captures and the general field media each keep their own caption,
      // because the caption is the only thing that says which photo is which.
      const outcome = await saveOrQueue<ToolDocumentation>({
        label: `Tool · ${payload.toolkitName || "Untitled"}`,
        endpoint: initial ? `/tools/${initial.id}` : "/tools",
        method: initial ? "PATCH" : "POST",
        body: payload,
        media: [
          ...(Object.entries(gridFiles) as [GridGroup, File][]).map(([group, file]) => ({
            files: [file],
            linkedRecordType: "tool",
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${payload.toolkitName || "tool"}`,
            location,
            recordedAt,
            recordedTimezone,
            // SEE THE MARKER'S NOTE ON THE ONLINE UPLOAD BELOW. It is written on both paths because
            // a queued save is the ORDINARY one in a village: a grid sheet that reached the
            // repository through the outbox is exactly as eligible to become the record's
            // photograph as one uploaded on the spot, and a marker only the online path writes
            // would leave the offline half of the fleet still printing ruled paper.
            extraMetadata: { purpose: MEASUREMENT_GRID_PURPOSE },
            transcribeAudio: false
          })),
          ...stageFiles.map((file, index) => ({
            files: [new File([file], `STAGE_STEP_${index + 1}_${file.name}`, { type: file.type, lastModified: file.lastModified })],
            linkedRecordType: "tool",
            caption: `Process stage step ${index + 1} for ${payload.toolkitName || "tool"}`,
            location,
            recordedAt,
            recordedTimezone,
            transcribeAudio: false
          })),
          {
            files: mediaFiles,
            linkedRecordType: "tool",
            caption: `Field media for ${payload.toolkitName || "tool"}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined
          },
          // The deterministic panel's frame, on the queued path. Offline is the ORDINARY case for
          // this control — it is the one measurement route that works with no signal at all — so a
          // photograph that only reached the repository through the outbox has to be as fully
          // described as one uploaded on the spot. See the online upload for why the marker is
          // conditional on the reference kind.
          ...(measurePhoto
            ? [
                {
                  files: [measurePhoto.file],
                  linkedRecordType: "tool",
                  caption: `Measured from this photograph — ${payload.toolkitName || "tool"}`,
                  location,
                  recordedAt,
                  recordedTimezone,
                  extraMetadata: measurePhoto.isGrid ? { purpose: MEASUREMENT_GRID_PURPOSE } : undefined,
                  transcribeAudio: false
                }
              ]
            : [])
        ]
      });
      /*
        Bank the sitting the moment the record is accepted, so the next form opened from the
        dashboard already knows where the researcher is.

        THE FIRST CRAFT'S OWN NAME, NOT `payload.craftName`. That field is the joined string once
        several crafts are linked, and the banner reads its value out as one craft — "Continuing
        with Bandhani, Block Printing" names a craft that does not exist, and the six other forms
        that apply the bag would fill their single craft box with it. `craftId` beside it is already
        the first of the selection, so the pair stays consistent. A sitting is one craft.

        THE EXPRESSION IS `sittingCraftName`, SHARED WITH `onArtisansChanged`. It was inline here and
        nowhere else, so the bag was banked correctly on save and incorrectly on every artisan tick
        before one — and a tool ticked and then left unsaved carried the joined string into the next
        form opened, for the twelve hours `CARRY_CONTEXT_TTL_MS` holds it.
      */
      const sitting = {
        artisanId,
        artisanName: payload.artisanName,
        place: payload.place,
        craftId,
        craftName: sittingCraftName(payload.craftName),
        workshopId: workshop.workshopId,
        workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
      };
      if (outcome.queued) {
        // Offline is the normal case, but a queued tool has no id yet, so nothing can be assigned to
        // it. Whatever tool was in the bag is dropped rather than left to stand in for the one just
        // recorded — an old tool offered under a new one's name is a wrong link.
        carry.prune("tool");
        carry.remember(sitting);
        resetDirty();
        setSaving(false);
        if (onQueued) {
          /*
            THE PAGE'S ANSWER IS UNREACHABLE FROM A DIALOG, so the host is told instead.

            `OutboxBanner` is mounted at the top of the protected layout — outside the portal,
            underneath `FieldDialog`'s overlay, on a body whose scroll `FieldDialog` has locked. So
            the banner is invisible and the scroll below is a no-op. `onCreated` is not called
            either (there is no record and no server id), which meant the button flipped back from
            "Saving…" to "Save tool" and nothing else on screen changed — indistinguishable from
            a save that FAILED, and the designer's next move is to press it again.
          */
          onQueued();
          return;
        }
        // OutboxBanner at the top of the PAGE names the entry and says where it lives; scroll so it
        // is the next thing seen. See the dialog branch above for why that reasoning does not travel.
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        return;
      }
      const saved = outcome.saved;
      // The tool itself now joins the bag, so "assign this tool to more artisans" opens with the
      // tool already picked instead of hunting it out of a dropdown of seventy.
      carry.remember({ ...sitting, toolId: saved.id, toolName: saved.toolkitName });
      // Store each captured grid photo as media linked to the tool (the measured value is already in
      // the field). Best-effort per file so one failure doesn't lose the record.
      for (const [group, file] of Object.entries(gridFiles) as [GridGroup, File][]) {
        try {
          await uploadMediaFile({
            file,
            linkedRecordType: "tool",
            linkedRecordId: saved.id,
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${saved.toolkitName}`,
            location,
            recordedAt,
            recordedTimezone,
            // MARKED SO IT SORTS LAST AND NEVER OUTRANKS A REAL PHOTOGRAPH — see
            // MEASUREMENT_GRID_PURPOSE. Last and not excluded: a tool whose only image is this
            // grid shot still gets a picture rather than a blank.
            extraMetadata: { purpose: MEASUREMENT_GRID_PURPOSE },
            transcribeAudio: false
          });
        } catch {
          /* keep the saved record even if a grid photo fails to store */
        }
      }
      /*
        The frame the deterministic panel was marked on. Same best-effort shape as the grid loop
        above and for the same reason: a photograph that fails to store must not cost the record.

        THE MARKER IS CONDITIONAL, AND THE CONDITION IS THE REFERENCE THE DESIGNER CHOSE.
        `MEASUREMENT_GRID_PURPOSE` means, in the words of `design_workshops.py`'s own comment, "a
        sheet of ruled paper photographed to fill a dimension box": the server sorts it LAST when
        picking the one image that represents this record, and `_record_media_note` does not count
        it as footage of the subject. Both are right for a grid shot and both would be wrong for the
        other case — a chisel photographed with a steel rule beside it IS a picture of the chisel,
        and marking it would sort a perfectly good catalogue photograph behind nothing and
        undercount the record's media by one. So the marker follows the reference kind rather than
        the control, and the panel reports which it was.

        BEFORE THE STAGE-STEP LOOP, NOT AFTER IT, because that loop has an early `return` on its
        failure branch: a tool whose stage captures failed would otherwise lose the frame its
        dimensions were read off, which is the one photograph that makes those numbers checkable.
      */
      if (measurePhoto) {
        try {
          await uploadMediaFile({
            file: measurePhoto.file,
            linkedRecordType: "tool",
            linkedRecordId: saved.id,
            caption: `Measured from this photograph — ${saved.toolkitName}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: measurePhoto.isGrid ? { purpose: MEASUREMENT_GRID_PURPOSE } : undefined,
            transcribeAudio: false
          });
        } catch {
          /* keep the saved record even if the measurement frame fails to store */
        }
      }
      // Android parity: each process-stage capture is stored as a numbered step (STAGE_STEP_n).
      const stageFailed: string[] = [];
      for (const [index, file] of stageFiles.entries()) {
        try {
          await uploadMediaFile({
            file: new File([file], `STAGE_STEP_${index + 1}_${file.name}`, { type: file.type, lastModified: file.lastModified }),
            linkedRecordType: "tool",
            linkedRecordId: saved.id,
            caption: `Process stage step ${index + 1} for ${saved.toolkitName}`,
            location,
            recordedAt,
            recordedTimezone
          });
        } catch {
          stageFailed.push(file.name);
        }
      }
      if (stageFailed.length) {
        setError(
          `${stageFailed.length} process stage file(s) failed to upload: ${stageFailed.join(", ")}. ` +
            "The tool record was saved; re-open it to retry those files."
        );
        setSaving(false);
        /*
          ── THE RECORD IS REPORTED FIRST, THE UPLOAD FAILURE SECOND ──────────────────────────
          The same rule as the field-media branch below, and it has to be on BOTH of this form's
          early returns or the defect simply moves: a tool whose stage-step captures failed is
          still a tool in the repository, and a host that is not told leaves its stage row unlinked
          over a record that exists. See the longer note on the branch below.
        */
        if (onCreated) onCreated(saved);
        return;
      }
      if (mediaFiles.length) {
        const { failed } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "tool",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.toolkitName}`,
          location,
          recordedAt,
          recordedTimezone,
          extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined,
          onProgress: setUploadProgress
        });
        setUploadProgress(null);
        if (failed.length) {
          setError(`${failed.length} of ${mediaFiles.length} file(s) failed to upload: ${failed.map((f) => f.name).join(", ")}. The record was saved; re-open it to retry those files.`);
          setSaving(false);
          /*
            ── THE RECORD IS REPORTED FIRST, THE UPLOAD FAILURE SECOND ────────────────────────
            This branch used to `return` here, and the sentence it has just written says why that
            was wrong: the tool IS in the repository — only the photographs are missing. The host
            was never told, so the stage row that opened this form stayed unlinked over a record
            that exists, and an unlinked REF is not something a designer can see and repair later:
            the stage 422s on submit, hours afterwards, naming a required reference to a tool they
            remember creating. The obvious next move is to create it a second time.

            A missing photograph is recoverable by re-opening the record, which is exactly what the
            message above says to do. A link nobody made is not. The error is set BEFORE the handoff
            and not instead of it: on this form's own page nothing unmounts and the banner reads as
            it always did; in the dialog the host closes over it, which is the same trade the queued
            branch above already makes.
          */
          if (onCreated) onCreated(saved);
          return;
        }
      }
      resetDirty();
      if (onCreated) {
        // Hosted in a dialog: the caller owns what happens next. Routing away would abandon the
        // stage the designer is standing in.
        onCreated(saved);
        return;
      }
      router.push("/tools");
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save tool record");
    } finally {
      setSaving(false);
      setUploadProgress(null);
    }
  }

  return (
    <>
      <form ref={formRef} onSubmit={submit} onInput={markDirty} onKeyDown={handleFormEnter} className="panel grid gap-4 p-4">
        {/* `role="alert"`: this banner is the ONLY place a save refusal reaches the
            researcher on this form. The browser's own constraint validation covers the
            `min={0}` boxes below and names the offending one — but nothing else does: an
            outbox replaying a queued body, Android, or a stored-negative row edited on a
            client that PATCHes a delta all reach `ge=0` on the server, and its refusal
            ("Input should be greater than or equal to 0") lands here and nowhere else.
            Without a role it is painted and never spoken. The id is for symmetry with
            ProcessForm, which carries the same banner. */}
        {error ? (
          <div id={errorId} role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
            {error}
          </div>
        ) : null}
        <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />
        {/*
          THE ONE PLACE THIS FORM EXPLAINS A MISSING MICROPHONE — see `DictationUnavailableNotice`.
          Every dictated box below passes `explainWhenUnavailable={false}`: on Firefox the same
          honest paragraph printed eight times down one form is grey text nobody reads. Delete this
          line and the explanation is gone from ALL of them, not from one.
        */}
        <DictationUnavailableNotice />
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {/* Android parity (ToolForm): the workshop opens the form, because it is the context
              every other answer belongs to — not merely the first dropdown. ONE cell of this grid
              holds both boxes, because they are one question: the type, then the workshop of that
              type. `markDirty` by hand, as every themed control on this form must — a dropdown is a
              `<button>` and fires no native input event for the form's `onInput` to catch. */}
          <WorkshopPicker state={workshop} onDirty={markDirty} saving={saving} />
          {/* Toolkit/English/craft/artisan names and place are title-cased by the API on write, so
              the box says what will be stored (Android parity — see forms/TitleCasedInput);
              `titleCased` mounts that exact component inside the dictated box rather than copying
              its hint. Local name is NOT title-cased: it is Devanagari/Gujarati, where capitalising
              means nothing — and it still gets a microphone, because the recogniser takes the
              language it is set to and Odia, Hindi and Gujarati are in that list. `markDirty` by
              hand: a dictated phrase fires no native input event for the form's `onInput` to see. */}
          <DictatedTextInput
            name="toolkitName"
            label="Toolkit name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={toolkitName}
            /* Every write of this box goes through `applyToolkitName`, which also mirrors into
               English name while that is still armed — see its declaration for the whole rule and
               for why the mirror is here rather than in an effect. */
            onChange={(next) => applyToolkitName(next, { user: true })}
          />
          <DictatedTextInput
            name="localName"
            label="Local name"
            explainWhenUnavailable={false}
            value={localName}
            onChange={(next) => {
              setLocalName(next);
              markDirty();
            }}
          />
          {/* THE BOX THAT ENDS THE MIRROR. Any write a person makes here — a keystroke, a paste, a
              dictated phrase, or emptying it by hand — is the designer taking the field over, and
              from that moment the toolkit name never writes it again. CLEARING IS AN EDIT, which is
              why there is no `if (next)` guard: a box somebody deliberately emptied must stay empty,
              and refilling it on the next toolkit keystroke would be the form arguing with them.
              The microphone stays, and dictating is exactly as much a user edit as typing. */}
          <DictatedTextInput
            name="englishName"
            label="English name"
            titleCased
            explainWhenUnavailable={false}
            value={englishName}
            onChange={(next) => {
              setEnglishName(next);
              mirrorArmed.current = false;
              markDirty();
            }}
          />
          {/* ── `FieldBlock`, NOT `Field`, AND THAT IS NOT A STYLE CHOICE ────────────────────────
              `Field` is a `<label>`, and a `<label>` folds every named descendant into the accessible
              NAME of the control it wraps and forwards a stray click to the first labelable thing
              inside it. Both are wrong for a multi-select: the cap notice and the failed-read
              sentence below would be read out as part of the picker's name, and `FormControls`'
              own header says a multi-select wants `FieldBlock`. The question still reaches the
              trigger — `FieldBlock` publishes its label's id and `SearchableMultiSelect` composes
              "Linked crafts 2 selected: …" out of it. */}
          <FieldBlock label="Linked crafts (fills craft name)">
            {/* `searchable` on both link pickers: crafts and artisans are records, both lists are
                capped (notices below), and the label is the only thing that tells two artisans of
                one craft apart. Status / product type / market demand / maker / tradition on this
                same form stay plain — they are fixed vocabularies of four to seven, where a filter
                box costs a tab stop and saves nothing. */}
            <MultiSelectDropdown
              values={craftIds}
              onChange={onCraftsChanged}
              searchable
              /* The refusal below is announced on the control it is about, not only drawn under it:
                 a reader who tabs onto the trigger to find out why their tick did nothing hears the
                 sentence, and a reader who never leaves the panel hears it from `role="alert"`.
                 There is no `invalid` companion on this primitive by design — see `SearchableSelect`. */
              describedBy={craftLimitNotice ? craftLimitId : undefined}
              placeholder="Select linked crafts"
              /* Never the primitive's literal "No options", which on a read that has not landed --
                 or one that failed -- is the claim that this repository records no crafts. */
              emptyLabel={craftEmptyLabel || undefined}
              /* THERE IS NO "UNLINKED" ROW ANY MORE, AND THERE MUST NOT BE. On the single-select it
                 was the answer `value=""`, and it doubled as the browser's fallback for "linked to a
                 craft that is not on page one" — which is the defect `offPageCrafts` above closes. A
                 multi-select says "unlinked" by holding nothing, so a row that meant it would be a
                 second spelling of the empty selection, and ticking it alongside a real craft would
                 be a state nobody can store. The Craft name box beside this one is still where a
                 craft that is not in the register gets typed. */
              options={craftOptions.map((craft) => ({ value: craft.id, label: craft.name }))}
            />
            <CappedListNotice cuts={[craftCut]} />
            {/* THE TICK THAT WAS REFUSED, AND IT IS THE ONLY SENTENCE ON THIS FIELD IN `error-600`.
                Nothing was lost — the selection is exactly what it was — but something the designer
                asked for did not happen, and the one failure a picker must never have is the silent
                one. See `onCraftsChanged`. */}
            {craftLimitNotice ? (
              <p id={craftLimitId} role="alert" className="mt-1 text-xs leading-5 text-error-600">
                {craftLimitNotice}
              </p>
            ) : null}
            {/* R3: a picker that is empty because the read failed has to say so on the page, not
                only inside a panel a reader has no reason to open. */}
            {craftNotice ? <p className="mt-1 text-xs leading-5 text-ink-500">{craftNotice}</p> : null}
          </FieldBlock>
          <DictatedTextInput
            name="craftName"
            label="Craft name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={craftName}
            onChange={(next) => {
              setCraftName(next);
              markDirty();
            }}
          />
          {/* `FieldBlock` for the same reason as the craft picker above — see that note. */}
          <FieldBlock label="Linked artisans (fills artisan + place)">
            {/* ── THE ROWS ARE GROUPED BY CRAFT, A→Z, AND THE ORDER IS NOT DECIDED HERE ─────────
                `artisanPickerOptions` sorts by craft name and then by artisan name and hands each
                row its craft as a `group`; `groupRows` buckets in first-appearance order, so an
                already-sorted array is what makes the headings read A→Z with "Unlinked craft" last.
                The collation is pinned in that function because the handset draws the same list over
                the same rows and the two must not disagree about where a Gujarati name sorts. The
                place travels as the `hint`, which `filterOptions` searches as well as draws. */}
            <MultiSelectDropdown
              values={artisanIds}
              onChange={onArtisansChanged}
              searchable
              placeholder={craftIds.length ? "Select linked artisans" : "Select a linked craft first"}
              /* "" once the roster has arrived, which hands the slot back to the primitive and to
                 the form's own sentence below. See `craftArtisanEmptyLabel`. */
              emptyLabel={craftArtisanEmptyLabel || undefined}
              options={artisanPickerOptions(artisansForCraft, selectedCrafts)}
              disabled={craftIds.length === 0}
            />
            {/* A claim about the REPOSITORY, so it waits for the repository's answer about THESE
                crafts. Printed off a stale roster it said "no artisans are linked to this craft yet"
                over a craft with a dozen of them — the silent-emptiness failure in one sentence, and
                the reason `artisansLoadedForCraft` names a craft SELECTION rather than being a
                boolean. `craftRosterKey` is that selection's key; the hook owns how it is built. */}
            {craftIds.length && artisansLoadedForCraft === craftRosterKey && artisansForCraft.length === 0 ? (
              <p className="mt-1 text-xs text-ink-muted">
                No artisans are linked to {craftIds.length > 1 ? "these crafts" : "this craft"} yet.
              </p>
            ) : null}
            <CappedListNotice cuts={[craftIds.length ? craftArtisanCut : null]} />
            {/* The roster request for these crafts failed. Distinct from the sentence above, which is
                a claim about the crafts and may only be printed off an answer that arrived. */}
            {craftArtisanNotice ? <p className="mt-1 text-xs leading-5 text-ink-500">{craftArtisanNotice}</p> : null}
          </FieldBlock>
          <DictatedTextInput
            name="artisanName"
            label="Artisan name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={artisanName}
            onChange={(next) => {
              setArtisanName(next);
              markDirty();
            }}
          />
          <DictatedTextInput
            name="place"
            label="Place"
            required
            titleCased
            explainWhenUnavailable={false}
            value={place}
            onChange={(next) => {
              setPlace(next);
              markDirty();
            }}
          />
          {/* FREE PROSE, NOT A CLOSED LIST. "Process used in" is a `String?` column nothing parses —
              a researcher writes "block printing, the second dyeing pass" into it — and "Material"
              answers "mango wood with an iron collar". Both are exactly the answer somebody standing
              at a bench would rather speak, and neither is a measurement or a vocabulary. */}
          <DictatedTextInput
            name="processUsedIn"
            label="Process used in"
            explainWhenUnavailable={false}
            value={processUsedIn}
            onChange={(next) => {
              setProcessUsedIn(next);
              markDirty();
            }}
          />
          <DictatedTextInput
            name="material"
            label="Material"
            explainWhenUnavailable={false}
            value={material}
            onChange={(next) => {
              setMaterial(next);
              markDirty();
            }}
          />
          {/* ── `min={0}` ON EVERY NUMBER ON THIS FORM, AND THE SAME BOUND ON THE SCHEMA ──────
              `yearsInUse` has carried this pair since it was added — `min={0}` here and `ge=0` on
              both `ToolCreate` and `ToolUpdate` — and it was the ONLY number on this form that did.
              Every measurement beside it, and the replacement cost below, took a negative from the
              box and stored it, while the workshop registry declares the fields they are carried
              into (`tool.lengthCm`, `tool.breadthCm`, `tool.cost`) as `min_value=0`. So the record
              accepted a quantity the workshop would later refuse on a row filled in FROM it.

              The bound has to be BOTH halves or it is neither: this one refuses the value in the
              box, by name, before a request is made (no `noValidate` on the form, so the browser's
              constraint validation runs and focuses the offending input); `ge=0` in
              `backend/app/schemas/records.py` refuses it for every client that is not this one. */}
          <Field label="Years in use">
            <TextInput name="yearsInUse" type="number" min={0} defaultValue={initial?.yearsInUse ?? ""} />
          </Field>
          {/* ── THE CENTIMETRE HALF OF A PAIR — THE SENTENCE THAT EXPLAINS IT IS BELOW ───────────
              `height` and `heightInches` are still different columns on `ToolDocumentation` (see
              MEASURE_COLUMNS above), and what changed on 2026-09-15 is that they are now two units
              of ONE measurement: typing in either fills the other. The note under the pair is where
              a designer is told that, and BOTH boxes point at it through `aria-describedby` — which
              is also why the note is not written inside either `Field`: `Field` is a `<label>`, and a
              `<label>` folds every scrap of text inside it into the accessible NAME of the control
              it wraps, so a sentence in there is read out as part of the box's name on every focus
              ("Height (cm) Height (cm) and Height (inches) are the same measurement…"). Referenced
              by id from outside, the same sentence is announced as a description, which is what it is.

              ── THE LABEL SAYS "(cm)" NOW, AND ALL FOUR CLIENTS MOVED TOGETHER ──────────────────
              This comment read: *"THE LABEL IS STILL ANDROID'S WORD, DELIBERATELY. `MainActivity.kt`'s
              tool form calls this box 'Height' and has no inches box yet (checked 2026-08-27), so
              renaming it here would put the two clients out of step over a box a designer moving
              between them has to recognise."* That was the whole argument for the bare name and its
              condition has now been met: the rename landed on the designer web form, the designer
              handset, and both of the field repository's clients in one change, which is the only
              way this box may ever be renamed. The COLUMN is still `height` — the wire key, the
              schema and `_CLEARABLE_COLUMNS` are untouched; only what a person reads changed. */}
          <Field label="Height (cm)">
            <TextInput name="height" type="number" min={0} step="0.01" aria-describedby={`${formId}-heights`} value={height} onChange={typeCm(setHeight, setHeightInches)} />
          </Field>
          <Field label="Width (cm)">
            <TextInput name="width" type="number" min={0} step="0.01" value={width} onChange={typeCm(setWidth, setBreadth)} />
          </Field>
          {/* These three — and NOT the two centimetre boxes above — go through `typeInches`, which
              writes the box, forgets whatever a machine proposed into it, and fills its centimetre
              partner where it has one. See that helper for why a marker must not outlive the number
              it describes, why the centimetre boxes are excluded from the marker, and why these stay
              one line each. `Length (inches)` passes no partner because it has none. */}
          <Field label="Length (inches)">
            <TextInput name="lengthInches" type="number" min={0} step="0.01" value={length} onChange={typeInches(setLength, "lengthInches")} />
          </Field>
          <Field label="Breadth (inches)">
            <TextInput name="breadthInches" type="number" min={0} step="0.01" value={breadth} onChange={typeInches(setBreadth, "breadthInches", setWidth)} />
          </Field>
          {/* Matched to the two boxes above it on purpose — same `type`, same `min`, same `step` and
              the same "(inches)" label convention. It is also the label Android already uses for this
              column, on its product form and in `RecordMeasureField.DwRecordDimension`. */}
          <Field label="Height (inches)">
            <TextInput name="heightInches" type="number" min={0} step="0.01" aria-describedby={`${formId}-heights`} value={heightInches} onChange={typeInches(setHeightInches, "heightInches", setHeight)} />
          </Field>
          {/* ── THE COPY THAT EXPLAINS THE TWO PAIRS ────────────────────────────────────
              A FULL-WIDTH ROW OF ITS OWN, AND THAT IS A LAYOUT DECISION AS WELL AS A COPY ONE. A
              grid item is `align-self: stretch` by default and an auto-sized grid ROW stretches with
              it (which is why `StatusField` above carries `content-start`), so a two-line hint
              tucked inside one of these cells would make its whole row taller and stretch the number
              boxes BESIDE it — `Years in use` and `Width (cm)` would grow to match a sentence that is
              not about them. Spanning every column costs one row of the form and distorts nothing.

              It sits directly under `Height (inches)` because that is the box a designer with a
              measurement in their hand should end up in, and `aria-describedby` on both height
              inputs is what carries it back up to `Height (cm)` for anyone who cannot see the layout.

              ── THE OLD INSTRUCTION IS RETIRED, NOT DELETED, BECAUSE IT SAID THE OPPOSITE ────────
              This paragraph read: *"Two heights, and they are different columns. **Height** stores a
              bare number in whatever unit this record already used; it is kept for what is already
              saved, and nothing measures into it. **Height (inches)** is the one the measurement
              panels below fill… **Fill one of the two, not both.**"* Every clause of that was true
              while the two columns were unrelated, and the pairing reverses the last one outright:
              filling either now fills the other, so a designer following the old sentence would be
              trying not to do the thing the form does for them. It is still only the two HEIGHT
              boxes that name this note in `aria-describedby`, deliberately — the paragraph names all
              four boxes by label, and pointing the width pair at it as well would say the same thing
              twice to a screen-reader user for no reader benefit.
              Re-check the pairing with `grep -n "typeCm\|typeInches" components/forms/ToolForm.tsx`. */}
          <p id={`${formId}-heights`} className="text-xs leading-5 text-ink-500 md:col-span-2 xl:col-span-3">
            <strong>Height (cm)</strong> and <strong>Height (inches)</strong> are the same measurement in two units, and
            filling either fills the other (1&nbsp;inch = 2.54&nbsp;cm, rounded to two decimals).{" "}
            <strong>Width (cm)</strong> and <strong>Breadth (inches)</strong> pair the same way.{" "}
            <strong>Length (inches)</strong> has no centimetre box. Records saved before this pairing existed can hold
            two numbers that disagree — opening one never rewrites either box, so correct whichever is wrong and its
            partner follows.
          </p>
          <Field label="Thickness">
            <TextInput name="thickness" type="number" min={0} step="0.01" defaultValue={initial?.thickness ?? ""} />
          </Field>
          <Field label="Weight">
            <TextInput name="weight" type="number" min={0} step="0.01" defaultValue={initial?.weight ?? ""} />
          </Field>
          <Field label="Radius">
            <TextInput name="radius" type="number" min={0} step="0.01" defaultValue={initial?.radius ?? ""} />
          </Field>
        </div>
        {/*
          ── THE PRIMARY MEASUREMENT ROUTE, AND WHY IT IS ABOVE THE OTHER ONE ────────────────────
          Deterministic, on this device, no connection and no per-call cost: the designer marks
          across N squares of the grid sheet they were already photographing the tool on, and the
          arithmetic is a ratio of two pixel distances. It is FIRST on the page because the owner's
          decision (2026-08-27) made it the primary path — the vision-model route below is too
          costly to be the default and cannot say how it reached a number. Order is not decoration
          here: whichever control a designer meets first is the one they learn.

          IT PROPOSES; IT NEVER WRITES. `setLength`/`setBreadth`/`setHeightInches` are reached only
          from `onPropose`, which the panel calls only from a button's `onClick`. (It was
          `setHeight` until 2026-08-27, when the third column became `heightInches`; the plain
          `height` box has no machine writer any more.)

          AND THE ACCEPTANCE IS NOW RECORDED, NOT JUST THE NUMBER (2026-08-27). The third argument is
          `photoMeasure.methodMarker(result)` — `{method: "PHOTO_GEOMETRY", technique: "SCALE"}` or
          `"RECTIFIED"`, whichever geometry actually produced the figure on the button — and it rides
          out on the save's `measurementMethods` for as long as the box still holds this number.
        */}
        <RecordPhotoMeasure
          columns={MEASURE_COLUMNS}
          values={{ lengthInches: length, breadthInches: breadth, heightInches }}
          /*
            AN ACCEPTED READING FILLS ITS CENTIMETRE PARTNER TOO, AND CARRIES NO PROVENANCE THERE.
            `breadthInches` fills `width`, `heightInches` fills `height`, `lengthInches` fills
            nothing because it has no partner. The marker is recorded for the INCH box alone —
            `DIMENSION_FIELDS` is exactly the three `*Inches` names and `rememberAcceptance` refuses
            anything else, so a centimetre box cannot be given a fake one even by mistake.

            AND THE CENTIMETRE WRITE IS A BARE SETTER, NOT `typeInches`. That factory calls
            `forgetAcceptance`, which is right for a person typing and wrong here: this IS the
            acceptance. It also writes the inch box, which would undo the marker being recorded two
            lines below. `propagate` is the same rule the typing handlers use — an unparseable
            proposal writes nothing rather than garbage — without either of those two side effects.
          */
          onPropose={(key, text, method) => {
            if (key === "lengthInches") setLength(text);
            else if (key === "breadthInches") {
              setBreadth(text);
              propagate(text, cmTextFromInches, setWidth);
            }
            // `heightInches` and NOT `height`, since 2026-08-27. A measured number belongs in the
            // column that says what unit it is in — and only that column can carry the method marker
            // `DIMENSION_FIELDS` gates. The centimetre box is filled from it as its partner, which
            // is a conversion and not a second claim about how the tool was measured.
            else if (key === "heightInches") {
              setHeightInches(text);
              propagate(text, cmTextFromInches, setHeight);
            }
            // AFTER the box is written and keyed by the same `key`, so the remembered text is
            // exactly what went in. `rememberAcceptance` refuses anything outside `DIMENSION_FIELDS`
            // itself — which on THIS form is the guard that matters, because the wrong `key` here is
            // `height`, and a marker naming it is a 422 that loses the researcher the whole form.
            //
            // WRITING THE PARTNER DOES NOT DISTURB IT: `measurementMethodsFor` compares the INCH
            // box's text against the accepted text byte for byte, and nothing above touched that.
            setAccepted((current) => rememberAcceptance(current, key, text, method));
            markDirty();
          }}
          onPhotoChange={(photo) => {
            setMeasurePhoto(photo);
            // Only when there IS one. The panel reports `null` once on mount, and a blank new form
            // announcing unsaved work before anybody has typed is what trains researchers to click
            // through the guard — the same rule `acceptFix` follows in LocationFields.
            if (photo) markDirty();
          }}
        />
        {/*
          ── THE FALLBACK, KEPT AND LABELLED ────────────────────────────────────────────────────
          `GridMeasurement` posts the photograph to `POST /media/analyze-measurement`, which asks a
          vision model to ESTIMATE the inches. It is retained deliberately: a tool that will not lie
          flat, or a designer who cannot mark the frame, still has it. What it is not any more is the
          first thing on the page, and this wrapper is where it says which of the two it is.

          THE HEADING SAYS "ESTIMATE" AND THE BADGE SAYS "NEEDS A CONNECTION", and neither is
          rhetoric. The route has no queue, no outbox entry and no retry (it is not in
          `ENQUEUEABLE_PROCESSING_REQUESTS`), so in a courtyard with no signal it fails every single
          time; and its answer is a model's guess, which nobody can re-derive from the photograph the
          way the panel above can. The component states the connection requirement in full in its own
          copy — this is the one-line summary above it, not a second sentence arguing with it.

          NOT COLLAPSED, AND THAT IS ON PURPOSE. Its capture state (which groups are ticked, the
          “Measured L 6 in · B 4 in” line) lives inside the component, while the FILES it has captured
          live up here in `gridFiles`. Unmounting it on collapse would drop the first and keep the
          second, leaving a photograph queued for upload with nothing on screen saying so.
        */}
        <section className="grid gap-2 rounded-lg border border-line-200 bg-card p-4">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-sm font-semibold text-ink-900">If you cannot mark it: estimate with the vision model</h3>
            <span className="rounded-full border border-amber-500 bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
              Needs a connection
            </span>
          </div>
          <p className="text-xs leading-5 text-ink-500">
            This asks a model to read the inches off the photograph. It is an <strong>estimate</strong>, not a
            measurement: it carries no error bar and nobody — including the model — can re-derive it from the picture
            afterwards. Prefer the panel above wherever the grid or a ruler is in the frame.
          </p>
          {/*
            THE MARKER THIS ONE CARRIES IS THE SERVER'S OWN, ECHOED BACK UNCHANGED. `POST
            /media/analyze-measurement` answers with `methodMarker` beside the analysis —
            `{method: "VISION_MODEL", provider, modelId, selfReportedConfidence}`, with any key the
            model did not answer OMITTED rather than invented — and a client's job is to hand it back
            on the save, not to compose one. `null` when the API predates that key, and
            `rememberAcceptance` then records no acceptance at all: the reading is stored
            `UNRECORDED`, because this client was told a number and not told how it was reached.
          */}
          <GridMeasurement
            includeHeight
            onLengthBreadth={(l, b, method) => {
              // Keyed one dimension at a time and only for the ones that actually arrived: a
              // photograph that yielded a length and no breadth must not leave a marker standing
              // over a breadth box this call never touched.
              if (l) {
                setLength(l);
                setAccepted((current) => rememberAcceptance(current, "lengthInches", l, method));
              }
              if (b) {
                setBreadth(b);
                // The breadth's centimetre partner — see the deterministic panel's `onPropose` above
                // for why this is `propagate` and not `typeInches`, and why the marker below is
                // unaffected by it. `l` gets none: `lengthInches` has no centimetre column.
                propagate(b, cmTextFromInches, setWidth);
                setAccepted((current) => rememberAcceptance(current, "breadthInches", b, method));
              }
              markDirty();
            }}
            /*
              THE SAME DESTINATION AS THE PANEL ABOVE, AND IT MOVED ON 2026-08-27. This callback's own
              parameter is named `inches` (`GridMeasurement`'s `onHeight: (inches: string, …)`), and
              until `ToolDocumentation.heightInches` existed the only box it could reach was the
              unit-less `height` — which is the defect the schema comment above the new column names:
              "an accepted height reading for a tool landed in the plain `height` column above, which
              declares no unit — losing the one fact the column name is there to carry." Two
              measurement routes on one form must also not land in two different boxes; a designer who
              tried the panel and then this fallback would otherwise be looking at two heights, having
              been told nothing about why there are two.

              ANDROID IS NOT BEHIND HERE — the two clients land this reading in the same column, and
              the handset was read to check it rather than assumed. The tool form's
              `GridMeasurementSection` in `MainActivity.kt` has `onHeight` write the `heightInches`
              state and `markers.accept("heightInches", …)`, and its `ToolCreateRequest` body sends
              `heightInches = heightInches.toDoubleOrNull()` beside a `measurementMethods` marker
              naming that same column. `grep -n "GridMeasurementSection(" MainActivity.kt` returns the
              declaration and two call sites — this form's and the product form's — and neither points
              a MARKED height at a column that cannot carry the marker: the product form's local is
              *named* `height` but goes out as `ProductCreateRequest.heightInches`.

              AND THE CLAUSE THAT USED TO END THAT PARAGRAPH IS RETIRED, BECAUSE 2026-09-15 REVERSED
              IT. It read: *"the unit-less `height` there is fed only by the box a designer types
              into, exactly as on this form."* Both halves are now false on both clients. `height` is
              no longer unit-less — it is the CENTIMETRE partner of `heightInches` (see the state
              block at the top of this file) — and it is no longer typed-only: the line directly
              below fills it by conversion, and the handset does the identical thing in the identical
              place (`propagateDimension(heightInches, ::cmTextFromInches) { v -> height = v }` in its
              own `onHeight`, and again in `onPropose`). What survives unchanged is the part that
              matters for provenance: the MARKER still names `heightInches` alone, because the reading
              landed there, and `height` carries no claim of its own.
            */
            onHeight={(value, method) => {
              setHeightInches(value);
              // And its centimetre partner, which is a conversion of the accepted reading rather than
              // a second one — see `onPropose` above for why it goes through `propagate`.
              propagate(value, cmTextFromInches, setHeight);
              // `heightInches` and not `height` here too — the marker has to name the same column the
              // number went into, or it describes a measurement of something else.
              setAccepted((current) => rememberAcceptance(current, "heightInches", value, method));
              markDirty();
            }}
            onFilesChange={(files) => {
              setGridFiles(files);
              markDirty();
            }}
          />
        </section>
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <Field label="Maker">
            <Select name="maker" defaultValue={initial?.maker ?? "UNKNOWN"} onChange={markDirty}>
              {makerOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          <Field label="Tradition type">
            <Select name="traditionType" defaultValue={initial?.traditionType ?? "UNKNOWN"} onChange={markDirty}>
              {traditionOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          {/* Money, and the same pairing rule as the measurements above. */}
          <Field label="Replacement cost">
            <TextInput name="replacementCost" type="number" min={0} step="0.01" defaultValue={initial?.replacementCost ?? ""} />
          </Field>
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          {/*
            THE TWO NARRATIVE BOXES ON THIS FORM. Both were already `<TextArea>` (`min-h-24`, no
            length cap) and both hold prose a researcher would rather speak than thumb in.

            `processUsedIn` above is DELIBERATELY LEFT ALONE even though the review registry
            (`components/review/reviewEditFields.ts`) marks it `multiline: true` and the CSV exports
            it as "Usage". On this form it is a single-line `TextInput`, and that disagreement
            predates this change by a long way; resolving it means deciding which of the two is
            right, which is a change to what the field IS rather than to what it can do. Recorded
            here so the next person does not read the omission as an oversight in the sweep.
          */}
          <RichTextField
            name="suggestionsForToolImprovement"
            label="Suggestions for improvement"
            defaultValue={initial?.suggestionsForToolImprovement ?? ""}
            className="md:col-span-2"
            onDirty={markDirty}
            // Said once at the top of this form by `DictationUnavailableNotice`; a copy under
            // every editor is the same paragraph over again. See the prop on `RichTextEditor`.
            explainWhenUnavailable={false}
          />
          <RichTextField
            name="remarks"
            label="Remarks"
            defaultValue={initial?.remarks ?? ""}
            className="md:col-span-2"
            onDirty={markDirty}
            // Said once at the top of this form by `DictationUnavailableNotice`; a copy under
            // every editor is the same paragraph over again. See the prop on `RichTextEditor`.
            explainWhenUnavailable={false}
          />
          <StatusField canSetStatus={canSetStatus} initialStatus={initial?.status} onDirty={markDirty} />
        </div>
        <MediaCaptureField
          files={stageFiles}
          onFilesChange={(files) => {
            setStageFiles(files);
            markDirty();
          }}
          title="Process stages"
          description="Document each step of making or using this tool. Captures are archived in order as STAGE_STEP_1, STAGE_STEP_2, …"
        />
        {initial ? <ExistingMedia linkedRecordType="tool" linkedRecordId={initial.id} /> : null}
        <MediaCaptureField
          files={mediaFiles}
          onFilesChange={(files) => {
            setMediaFiles(files);
            markDirty();
          }}
          title="Tool media"
          description="Attach or capture tool images, videos, audio notes, and documents. Image EXIF is retained and summarized in remarks."
        />
        <LocationFields initial={initialLocation} onDirty={markDirty} />
        {uploadProgress ? <UploadProgress progress={uploadProgress} /> : null}
        {/*
          THE HOST'S OWN QUESTIONS, AT THE BOTTOM OF THE SAME LIST OF FIELDS — see
          `InlineRecordHostProps.footerFields`. Inside the `<form>` and above the buttons, so a
          design-workshop stage embedding this page adds its extra fields to the end of one
          continuous form rather than to a second panel below a form that has already ended. The
          separator is the only styling, and with no host there is no element at all.
        */}
        {footerFields ? <div className="grid gap-3 border-t border-line-200 pt-4">{footerFields}</div> : null}
        <div className="flex justify-end gap-2">
          <button type="button" className="field-button-secondary" onClick={handleBack}>
            Cancel
          </button>
          <button className="field-button" disabled={saving}>
            {saving ? "Saving..." : initial ? "Update tool" : "Save tool"}
          </button>
        </div>
      </form>
      <UnsavedChangesDialog
        open={backPromptOpen}
        saving={saving}
        onKeepEditing={() => {
          setBackPromptOpen(false);
          setPromptFromCancel(false);
        }}
        onDiscard={() => {
          setBackPromptOpen(false);
          setPromptFromCancel(false);
          resetDirty();
          /*
            NEITHER BRANCH IS `router.back()`: the prompt is as load-bearing in a dialog as on a
            page — closing the dialog still throws the typing away — but what "discard" DOES
            afterwards belongs to the host.

            WHICH host act, though, depends on which control asked. Cancel means "empty this form,
            I am staying", and in the stage embed that is exactly what `leave()` does. The back
            arrow means "take me off this screen", and answering it with `leave()` alone is the
            defect `promptFromCancel` exists for: the work was discarded and the designer did not
            go anywhere.
          */
          if (promptFromCancel) leave();
          else leaveAfterDiscard();
        }}
        onSave={() => {
          setBackPromptOpen(false);
          setPromptFromCancel(false);
          formRef.current?.requestSubmit();
        }}
      />
    </>
  );
}
