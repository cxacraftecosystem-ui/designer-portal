"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { mergeById } from "@/components/data/cappedList";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { Field, Select, TextInput } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext } from "@/components/forms/CarryContextBanner";
import type { CarryNode } from "@/lib/carryContext";
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
import { craftChangeClearsArtisan, useCraftAndArtisanOptions, useRecordOffPage } from "@/components/forms/recordPickers";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import {
  DesignWorkshopSelect,
  useDesignWorkshopSelection
} from "@/components/forms/DesignWorkshopSelect";
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

/** Dropdown label for a linked artisan: always "Name · Place" (name alone if no place), never ids. */
function artisanOptionLabel(artisan: Artisan) {
  const name = artisan.name?.trim() || "Unnamed artisan";
  // "·" (middle dot), not "•" — Android joins every record label with the middle dot, and the
  // process form already does; using both marks in one tool form reads as two conventions.
  return artisan.place?.trim() ? `${name} · ${artisan.place.trim()}` : name;
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
 * ── THE PLAIN `height` COLUMN IS NOT REPLACED AND IS NOT BEING MIGRATED ───────────────────
 * It still holds every number already typed into it, in a unit nothing can name, so its box stays
 * on the form below and keeps working exactly as it did. What it no longer receives is a MACHINE
 * reading: both measurement routes on this form now propose into `heightInches`, the only one of
 * the two that can say what it measured. Telling the two boxes apart on screen is a copy problem,
 * and the sentence that solves it is a full-width row under the pair in the grid below, pointed at
 * from BOTH inputs by `aria-describedby`. Read the comment above it before rewording either label.
 *
 * ── WHY `width`, `thickness` AND `radius` ARE NOT OFFERED ────────────────────────────────────
 * Not an oversight: they are uncontrolled `defaultValue` boxes read straight out of `FormData` at
 * submit, so a proposal has nowhere to land without making three more inputs controlled, and their
 * units are as undeclared as `height`'s with no established convention to lean on. Offering a
 * measurement into a box whose unit nobody has ever written down would be inventing one.
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
 *  - **Workshop, Linked craft, Linked artisan, Maker, Tradition type, Status** — closed vocabularies
 *    and record pickers behind a themed dropdown. There is no free text to speak.
 *  - **Years in use, Height, Width, Length (inches), Breadth (inches), Height (inches), Thickness,
 *    Weight, Radius, Replacement cost** — native number boxes bounded `min={0}`. A recogniser
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
  const [craftId, setCraftId] = useState(initial?.craftId ?? searchParams.get("craftId") ?? "");
  const [artisanId, setArtisanId] = useState(initial?.artisanId ?? seed?.artisanId ?? searchParams.get("artisanId") ?? "");
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
   * TWO HEIGHTS, BECAUSE THEY ARE TWO COLUMNS — not two names for one.
   *
   * `heightInches` (2026-08-27) is what both measurement routes below propose into and the only one
   * of the pair a `measurementMethods` marker may ever name. `height` is the unit-less legacy
   * column, kept because it holds what is already stored. They are seeded separately so that an
   * edit shows both: a tool saved before that date has a `height` and no `heightInches`, and
   * folding either into the other would mean inventing a unit or hiding a number somebody typed.
   */
  const [height, setHeight] = useState(initial?.height != null ? String(initial.height) : "");
  const [heightInches, setHeightInches] = useState(initial?.heightInches != null ? String(initial.heightInches) : "");
  /**
   * WHICH OF THE THREE `*Inches` BOXES STILL HOLDS A MACHINE'S NUMBER, and what produced it.
   *
   * Written only by an accept button, cleared by a keystroke in the box it describes, and read once —
   * by `measurementMethodsFor` while the save body is built. It holds the accepted TEXT beside the
   * marker, which is the whole mechanism: see `components/forms/measurementMethods.ts` for why a
   * marker that outlives the number it describes is worse than no marker at all.
   *
   * THE PLAIN `height` ABOVE CAN NEVER APPEAR IN HERE. It is not in `DIMENSION_FIELDS`, so a marker
   * naming it is a 422 on the whole save rather than a dropped hint — `rememberAcceptance` refuses
   * the key itself, which is the guard that survives somebody later pointing a measurement route at
   * the wrong box.
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
    artisansLoadedForCraft
  } = useCraftAndArtisanOptions({ craftId, artisanId });
  /**
   * THIS TOOL'S OWN CRAFT IS ALWAYS AN OPTION, wherever it sorts.
   *
   * The hook above already does this for the ARTISAN and, until this line, for nobody else — so the
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
   * Same hook as `ArtisanForm`, called the same way. Do not write a variant of it: three forms
   * needed this rule, one got it, and that is precisely how the other two came to be missing it.
   */
  const offPageCraft = useRecordOffPage<Craft>("/crafts", craftId, crafts);
  const craftOptions = useMemo(() => (offPageCraft ? mergeById(crafts, [offPageCraft]) : crafts), [crafts, offPageCraft]);
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
  // The workshop this tool was documented at: shared picker, shared most-recent defaulting, and the
  // late-submission gate (see components/forms/WorkshopSelect).
  //
  // `seed.workshopId` is the design workshop's own linked Workshop and outranks the most-recent
  // probe: a tool created from a WORKSHOP-scoped picker that is filed against a different sitting is
  // a tool that picker can never show again. Passing it here also marks the selection `touched`,
  // which is what keeps the probe and the carry bag off it. See {@link InlineHostSeed}.
  const workshop = useWorkshopSelection({
    initialWorkshopId: initial?.workshopId ?? seed?.workshopId,
    isEdit,
    resetKey: initial?.id ?? null
  });
  /*
    THE DESIGN & PROTOTYPE WORKSHOP this record is filed under. Its own hook beside the ordinary
    workshop's, never folded into it: `workshopId` is gated by `WorkshopAssignment` and carries a
    submission window and a late-submission dialog; `designWorkshopId` is gated by
    `load_workshop_or_404` and has neither. Two access systems on one control is how a scope comes to
    be checked by whichever of them the caller remembered.

    `initial` on the control below is `undefined` on a CREATE and the stored value (or null) on an
    EDIT, which is what tells the picker whether it may prefill — the same convention
    `LocationFields` uses to decide whether it may auto-capture.
  */
  const designWorkshop = useDesignWorkshopSelection(initial?.designWorkshopId ?? null);

  /**
   * FINISH WHAT THE SEED (OR THE QUERY STRING) STARTED — an artisan id alone is not a usable answer.
   *
   * The "Linked artisan" dropdown below is `disabled={!craftId}` and its options are narrowed to the
   * chosen craft, so an `artisanId` arriving on its own lands in a control that is greyed out and
   * reading "Select a linked craft first". The id would still have been SUBMITTED — the payload
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
    if (known.craftId) setCraftId((current) => current || known.craftId || "");
    if (known.craft?.name) setCraftName((current) => current || known.craft?.name || "");
    if (known.name) setArtisanName((current) => current || known.name);
    if (known.place) setPlace((current) => current || known.place);
    // Deliberately does NOT call `markDirty`: this is the app filling a box in, not the researcher.
    // A blank new form announcing unsaved work before anybody has typed is what trains people to
    // click through the guard — see the same rule on `acceptFix` in LocationFields.
  }, [artisans, artisanId, isEdit]);

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
   * THE PLAIN `height` BOX DOES NOT USE IT and does not need to: it is not in `DIMENSION_FIELDS`, so
   * nothing can ever have recorded an acceptance against it to forget.
   *
   * A FACTORY RATHER THAN THREE INLINE HANDLERS, for a reason outside this file:
   * `e2e/record-number-bounds-unit.spec.ts` reads every number input on this form as ONE LINE of
   * source to check it declares `min={0}`, and a box broken across lines by a multi-statement
   * `onChange` silently drops out of that count. (Its filter is a substring match on the `type`
   * attribute, so this paragraph deliberately does not spell that attribute out — a COMMENT naming
   * it counts as an input and fails the same test, which is how this note was written the first time.)
   */
  const typeInto =
    (set: (value: string) => void, key: string) => (event: React.ChangeEvent<HTMLInputElement>) => {
      set(event.target.value);
      setAccepted((current) => forgetAcceptance(current, key));
    };

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
    onApply: (context) => {
      if (context.craftId) setCraftId(context.craftId);
      if (context.craftName) setCraftName(context.craftName);
      if (context.artisanId) setArtisanId(context.artisanId);
      if (context.artisanName) setArtisanName(context.artisanName);
      if (context.place) setPlace(context.place);
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });

  /** "Change": drop every carried value so the researcher picks from scratch. */
  function clearCarriedContext() {
    carry.change();
    setCraftId("");
    setCraftName("");
    setArtisanId("");
    setArtisanName("");
    setPlace("");
  }

  // Task 6: filter the artisan dropdown to the chosen craft (keeping any pre-existing selection).
  const artisansForCraft = craftId
    ? artisans.filter((artisan) => artisan.craftId === craftId || artisan.id === artisanId)
    : artisans;

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
        width: numericValue(form, "width"),
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
        workshopId: workshop.workshopId || null,
        designWorkshopId: designWorkshop.workshopId || null,
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
      // Bank the sitting the moment the record is accepted, so the next form opened from the
      // dashboard already knows where the researcher is.
      const sitting = {
        artisanId,
        artisanName: payload.artisanName,
        place: payload.place,
        craftId,
        craftName: payload.craftName,
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
              every other answer belongs to — not merely the first dropdown. */}
          <WorkshopSelect state={workshop} onDirty={markDirty} saving={saving} />
          {/*
            The design & prototype workshop, directly under the ordinary one — see the hook above.
            Its default is the server's answer to "most recently allocated" rather than this form's
            guess, so all seven forms and both clients agree; `lib/designWorkshopDefault.ts`.
          */}
          <DesignWorkshopSelect
            state={designWorkshop}
            initial={initial ? (initial.designWorkshopId ?? null) : undefined}
            onDirty={markDirty}
            saving={saving}
          />
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
            onChange={(next) => {
              setToolkitName(next);
              markDirty();
            }}
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
          <DictatedTextInput
            name="englishName"
            label="English name"
            titleCased
            explainWhenUnavailable={false}
            value={englishName}
            onChange={(next) => {
              setEnglishName(next);
              markDirty();
            }}
          />
          <Field label="Linked craft (fills craft name)">
            {/* `searchable` on both link pickers: crafts and artisans are records, both lists are
                capped (notices below), and the label is the only thing that tells two artisans of
                one craft apart. Status / product type / market demand / maker / tradition on this
                same form stay plain — they are fixed vocabularies of four to seven, where a filter
                box costs a tab stop and saves nothing. */}
            <Select
              name="craftId"
              searchable
              value={craftId}
              onChange={(event) => {
                const next = event.target.value;
                setCraftId(next);
                const craft = craftOptions.find((c) => c.id === next);
                if (craft) setCraftName(craft.name);
                // Drop the artisan ONLY when this form actually knows they practise a different
                // craft — never merely because it cannot see them. The distinction, and the silent
                // link deletion that made it necessary, are argued in `forms/recordPickers`.
                if (craftChangeClearsArtisan({ nextCraftId: next, artisanId, artisans })) {
                  setArtisanId("");
                }
                markDirty();
              }}
            >
              {/* "Unlinked" must mean unlinked. It is the placeholder a browser falls back to when
                  `value` matches no <option>, so it doubled as "linked to a craft that is not on
                  page one" until `craftOptions` carried that craft — see `offPageCraft` above. */}
              <option value="">Unlinked / type below</option>
              {craftOptions.map((craft) => (
                <option key={craft.id} value={craft.id}>
                  {craft.name}
                </option>
              ))}
            </Select>
            <CappedListNotice cuts={[craftCut]} />
          </Field>
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
          <Field label="Linked artisan (fills artisan + place)">
            <Select
              name="artisanId"
              searchable
              value={artisanId}
              onChange={(event) => {
                const next = event.target.value;
                setArtisanId(next);
                const artisan = artisans.find((a) => a.id === next);
                if (artisan) {
                  setArtisanName(artisan.name);
                  setPlace(artisan.place);
                  // An explicit pick replaces the remembered context and retires the banner: from
                  // here on the artisan on screen is the researcher's own choice, not a suggestion.
                  carry.remember(
                    { artisanId: artisan.id, artisanName: artisan.name, place: artisan.place, craftId, craftName },
                    { explicit: true }
                  );
                }
                markDirty();
              }}
              disabled={!craftId}
            >
              <option value="">{craftId ? "Unlinked / type below" : "Select a linked craft first"}</option>
              {artisansForCraft.map((artisan) => (
                <option key={artisan.id} value={artisan.id}>
                  {artisanOptionLabel(artisan)}
                </option>
              ))}
            </Select>
            {/* A claim about the REPOSITORY, so it waits for the repository's answer about THIS
                craft. Printed off a stale roster it said "no artisans are linked to this craft yet"
                over a craft with a dozen of them — the silent-emptiness failure in one sentence, and
                the reason `artisansLoadedForCraft` names a craft rather than being a boolean. */}
            {craftId && artisansLoadedForCraft === craftId && artisansForCraft.length === 0 ? (
              <p className="mt-1 text-xs text-ink-muted">No artisans are linked to this craft yet.</p>
            ) : null}
            <CappedListNotice cuts={[craftId ? craftArtisanCut : null]} />
          </Field>
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
          {/* ── ONE OF TWO HEIGHT BOXES — THE SENTENCE THAT TELLS THEM APART IS BELOW ───────────
              `height` and `heightInches` are different columns on `ToolDocumentation` (see
              MEASURE_COLUMNS above), and a form that draws both without saying which is which gets
              the same fact typed into both — worse than either box alone, because a later reader
              then has two numbers for one dimension and no rule for choosing between them.

              THE LABEL IS STILL ANDROID'S WORD, DELIBERATELY. `MainActivity.kt`'s tool form calls
              this box "Height" and has no inches box yet (checked 2026-08-27), so renaming it here
              would put the two clients out of step over a box a designer moving between them has to
              recognise. The disambiguation therefore lives in the note under the pair rather than in
              either label, and BOTH boxes point at that note through `aria-describedby` — which is
              also why the note is not written inside either `Field`: `Field` is a `<label>`, and a
              `<label>` folds every scrap of text inside it into the accessible NAME of the control
              it wraps, so a sentence in there is read out as part of the box's name on every focus
              ("Height Two heights, and they are different columns…"). Referenced by id from
              outside, the same sentence is announced as a description, which is what it is. */}
          <Field label="Height">
            <TextInput name="height" type="number" min={0} step="0.01" aria-describedby={`${formId}-heights`} value={height} onChange={(event) => setHeight(event.target.value)} />
          </Field>
          <Field label="Width">
            <TextInput name="width" type="number" min={0} step="0.01" defaultValue={initial?.width ?? ""} />
          </Field>
          {/* These three — and NOT the `height` box above — go through `typeInto`, which writes the
              box AND forgets whatever a machine proposed into it. See that helper for why a marker
              must not outlive the number it describes, why the unit-less box is excluded, and why
              these stay one line each. */}
          <Field label="Length (inches)">
            <TextInput name="lengthInches" type="number" min={0} step="0.01" value={length} onChange={typeInto(setLength, "lengthInches")} />
          </Field>
          <Field label="Breadth (inches)">
            <TextInput name="breadthInches" type="number" min={0} step="0.01" value={breadth} onChange={typeInto(setBreadth, "breadthInches")} />
          </Field>
          {/* Matched to the two boxes above it on purpose — same `type`, same `min`, same `step` and
              the same "(inches)" label convention. It is also the label Android already uses for this
              column, on its product form and in `RecordMeasureField.DwRecordDimension`. */}
          <Field label="Height (inches)">
            <TextInput name="heightInches" type="number" min={0} step="0.01" aria-describedby={`${formId}-heights`} value={heightInches} onChange={typeInto(setHeightInches, "heightInches")} />
          </Field>
          {/* ── THE COPY THAT KEEPS THE TWO HEIGHT BOXES APART ──────────────────────────
              A FULL-WIDTH ROW OF ITS OWN, AND THAT IS A LAYOUT DECISION AS WELL AS A COPY ONE. A
              grid item is `align-self: stretch` by default and an auto-sized grid ROW stretches with
              it (which is why `StatusField` above carries `content-start`), so a two-line hint
              tucked inside one of these cells would make its whole row taller and stretch the number
              boxes BESIDE it — `Years in use` and `Width` would grow to match a sentence that is not
              about them. Spanning every column costs one row of the form and distorts nothing.

              It sits directly under `Height (inches)` because that is the box a designer with a
              measurement in their hand should end up in, and `aria-describedby` on both inputs is
              what carries it back up to the plain `Height` box for anyone who cannot see the layout.

              THE TWO NAMES ARE THE BOX LABELS, VERBATIM. "Fill one of the two, not both" is the
              whole instruction; a sentence that explained the column history instead would be true
              and would not tell a designer what to do. */}
          <p id={`${formId}-heights`} className="text-xs leading-5 text-ink-500 md:col-span-2 xl:col-span-3">
            Two heights, and they are different columns. <strong>Height</strong> stores a bare number in whatever
            unit this record already used; it is kept for what is already saved, and nothing measures into it.{" "}
            <strong>Height (inches)</strong> is the one the measurement panels below fill, and the only one that records
            the unit it is in. Fill one of the two, not both.
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
          onPropose={(key, text, method) => {
            if (key === "lengthInches") setLength(text);
            else if (key === "breadthInches") setBreadth(text);
            // `heightInches` and NOT `height`, since 2026-08-27. A measured number belongs in the
            // column that says what unit it is in — and only that column can carry the method marker
            // `DIMENSION_FIELDS` gates. The plain box is left to whoever typed into it.
            else if (key === "heightInches") setHeightInches(text);
            // AFTER the box is written and keyed by the same `key`, so the remembered text is
            // exactly what went in. `rememberAcceptance` refuses anything outside `DIMENSION_FIELDS`
            // itself — which on THIS form is the guard that matters, because the wrong `key` here is
            // `height`, and a marker naming it is a 422 that loses the researcher the whole form.
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
              naming that same column; the unit-less `height` there is fed only by the box a designer
              types into, exactly as on this form. `grep -n "GridMeasurementSection(" MainActivity.kt`
              returns the declaration and two call sites — this form's and the product form's — and
              neither points a measured height at a unit-less column: the product form's local is
              *named* `height` but goes out as `ProductCreateRequest.heightInches`.
            */
            onHeight={(value, method) => {
              setHeightInches(value);
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
