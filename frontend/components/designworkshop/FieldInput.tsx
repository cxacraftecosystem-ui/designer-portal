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

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore
} from "react";
import type { DwFieldStamp } from "@/lib/designWorkshops";
import { FieldProvenance } from "@/components/designworkshop/FieldProvenance";
import { Paperclip, X } from "lucide-react";

import { DictationButton } from "@/components/designworkshop/Dictation";
import { IdentityCardReader } from "@/components/designworkshop/IdentityCardReader";
import { MediaAiVerbs } from "@/components/designworkshop/MediaAiVerbs";
import { RichTextEditor } from "@/components/designworkshop/RichTextEditor";
import { deriveValue, isDerived } from "@/lib/derivedFields";
import {
  addressListRole,
  dateRangePartner,
  identityNumberField,
  measurableLengthFields,
  offersIdentityOcr,
  offersPhotoMeasure,
  offersSignaturePad,
  offersSketchRectify,
  recordMediaNoteRole,
  sketchSourceFields,
  workshopTitleRole
} from "@/components/designworkshop/stageFieldRoles";
/*
 * THE FORMAT THE REGISTRY DECLARES FOR THIS FIELD, AND THE RECORD PAGE'S OWN VALIDATOR FOR IT.
 *
 * Read, never inferred from the key — same rule as `storeMasked` two imports down. See
 * {@link stageFieldFormats} for why the rule is a declaration on the field rather than a check in
 * this file: the box this file draws is a SECOND box for the same fact, and it is the one the report
 * prints, so a check that lives here governs the copy that is printed and nothing else.
 */
import {
  fieldFormatError,
  formatShownByControl,
  isAadhaarMaskValue
} from "@/components/designworkshop/stageFieldFormats";
import { PhotoMeasureField } from "@/components/designworkshop/PhotoMeasureField";
import { SketchRectifyField, type SketchSource } from "@/components/designworkshop/SketchRectifyField";
import { SignaturePad } from "@/components/SignaturePad";
import { StageAddressField } from "@/components/designworkshop/StageAddressField";
import { StageGeoField } from "@/components/designworkshop/StageGeoField";
import { StageMediaNoteField } from "@/components/designworkshop/StageMediaNoteField";
import { StageWorkshopField } from "@/components/designworkshop/StageWorkshopField";
import { StageReferenceSelect } from "@/components/designworkshop/StageReferenceField";
/*
 * THREE RECORD-FORM CONTROLS, MOUNTED WHOLE RATHER THAN REIMPLEMENTED.
 *
 * The owner's requirement is that "the fields have certain input methods that need to be emulated
 * just as they are", and the only way to emulate a checksum, a dial-code column or a bullet boundary
 * without eventually disagreeing with it is to render the same component. A second implementation of
 * any of the three would be a second answer that drifts, and the drift would be invisible: the DATA
 * carries fine either way, so nothing would report it — only the person typing pays.
 */
import { aadhaarValidationError, isMaskedIdentityNumber } from "@/components/forms/AadhaarField";
import { DateField, TimeField } from "@/components/forms/DateTimeField";
import { IdentityCardCapture } from "@/components/forms/IdentityCardCapture";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { DocumentPreview } from "@/components/media/DocumentPreview";
import { MediaCarousel, type CarouselItem } from "@/components/media/MediaCarousel";
import { NumberedListField } from "@/components/forms/NumberedListInput";
import { PhoneField } from "@/components/forms/PhoneField";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import { apiFetch } from "@/lib/api";
import {
  DW_DEFAULT_MAX_ITEMS,
  fieldTypeName,
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

/* ────────────────────────────────────────────────────────────────────────────
 * Attached-but-not-yet-linked files, held ABOVE the field that attached them
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Where in the stage a file was attached: the triple that identifies ONE media control.
 *
 * The same triple `FieldInputProps.place` already carries, plus the field key — `place` names the
 * row and this names the box on it. It is spelled out as its own type because it is now a KEY in a
 * store that outlives the control, and a key assembled ad hoc at two call sites is a key that
 * eventually disagrees with itself.
 *
 * `rowKey` is the row's `_clientKey`, not its array index and not its `_entryId`: the index moves
 * when a row is reordered and the entry id does not exist until the server has seen the row, and
 * either would file a photograph against a different participant. Null for a singleton, which has
 * exactly one instance of every field.
 */
export type StageMediaPlace = { entityKey: string; rowKey: string | null; fieldKey: string };

function pendingKeyOf(place: StageMediaPlace): string {
  // NUL-joined rather than colon-joined: a registry key cannot contain one, so no two distinct
  // triples can collide by a separator turning up inside a part.
  return [place.entityKey, place.rowKey ?? "", place.fieldKey].join("\u0000");
}

type PendingMediaStore = {
  /**
   * The whole map, as STATE rather than behind a getter.
   *
   * A getter over a ref would not re-render anything: the context value would be referentially
   * stable, so no consumer would be notified, and the provider re-rendering does not re-render
   * `children` either — that is the same element object from the page above, and React bails out of
   * a subtree whose element has not changed. Handing the map itself over means the context value
   * changes identity on every write, which is precisely the signal the media controls need. It is
   * also the rule this file already argues for one component down, on `originals`: "a ref read
   * during render is a value React did not schedule the render for".
   */
  held: Record<string, File[]>;
  /**
   * AN UPDATER AND NOT A LIST, and the difference is a photograph.
   *
   * The map-level `setHeld` below is already an updater, which makes two DIFFERENT controls settling
   * in one tick safe. It does nothing at all for two writes to the SAME control if the `File[]`
   * handed in was computed from a render closure: a signature committed by the capture card and the
   * drain effect's filter, issued against the same committed render, would each build a whole list
   * from the same stale slice and the second would win entire. That is the same data loss this whole
   * store exists to end, so the shape has to make the safe call the only call.
   */
  write: (key: string, update: PendingUpdate) => void;
};

/** A new list, or how to make one from the current one. Only the second is safe — see `write`. */
type PendingUpdate = File[] | ((current: File[]) => File[]);

const StagePendingMediaContext = createContext<PendingMediaStore | null>(null);

/** One frozen empty array, so "nothing attached" is a stable reference rather than a new render. */
const EMPTY_PENDING: File[] = [];

/**
 * THE ATTACHED FILES OF EVERY MEDIA CONTROL ON THE STAGE, KEPT WHERE THE CONTROLS ARE NOT.
 *
 * ── THE DEFECT THIS EXISTS FOR ────────────────────────────────────────────────────────────────
 * A collection row's panel is UNMOUNTED the moment the row is collapsed — deliberately, and the
 * 244-row flagship workshop is why: `CollectionTable` renders a list, not forty open forms.
 * `MediaField`'s `pending` list was `useState` inside that panel, and it is the ONLY reference
 * anywhere to a file that has been attached but not yet linked. So collapsing the row destroyed it,
 * and destroyed it twice over: about two seconds later the staged-owner release in `lib/media`
 * aborted the transfer AND DELETED THE OBJECT ALREADY IN OBJECT STORAGE. Reopening the row said
 * "Nothing attached yet", with no error anywhere, over a photograph that no longer existed in
 * either place. The same unmount also destroyed a file being HELD for its own Retry after a failed
 * transfer — the one case the capture card deliberately keeps on screen rather than draining.
 *
 * ── WHY NOT SIMPLY KEEP THE PANEL MOUNTED ─────────────────────────────────────────────────────
 * Because that is the thing the unmount exists to avoid, and buying a data-loss fix with a 244-row
 * form's responsiveness pays for it in the defect that made the unmount necessary. The file list is
 * small and plain and has no reason to live inside the control; the control is the expensive part.
 *
 * ── WHY A CONTEXT AND NOT TWO MORE PROPS ON FOUR SIGNATURES ───────────────────────────────────
 * The route from the stage page to a media control is page → `EntityForm`/`CollectionTable` →
 * `FieldGrid` → `FieldInput` → `MediaField`. Threading a store and its setter through all five
 * would put two parameters on four signatures for a value only the last one reads, which is the
 * argument `LinkedWorkshopProvider` is written under one file away. What the defect turns on is the
 * LIFETIME, and a provider mounted by the stage page has the stage page's lifetime.
 *
 * ── IT IS HALF OF A PAIR AND MUST NOT SHIP ALONE ──────────────────────────────────────────────
 * Keeping the `File[]` alive keeps the BROWSER's reference. It does not keep the eagerly-uploaded
 * OBJECT alive: `useEagerStaging` releases its owner on unmount and `lib/media` deletes anything
 * unclaimed two seconds later. So every media control also passes a `stagingOwnerId` derived from
 * this same triple, which makes a remount CANCEL the release instead of racing it. Shipping only
 * the stable owner id would be worse than shipping neither: the object would survive with nothing
 * left in the browser able to link it, so it would leak rather than be cleaned up.
 *
 * ── WITHOUT A PROVIDER NOTHING CHANGES ────────────────────────────────────────────────────────
 * `usePendingMedia` falls back to local state, which is exactly what this replaced, so a surface
 * that mounts a `FieldInput` outside a stage page needs no change.
 */
export function StagePendingMediaProvider({ children }: { children: React.ReactNode }) {
  const [held, setHeld] = useState<Record<string, File[]>>({});

  /**
   * THE WRITE IS AN UPDATER AND NOT A READ-THEN-WRITE, which is what makes two controls settling in
   * the same tick safe.
   *
   * Reading the map out of the render closure and writing a new one back would build the second
   * write on a snapshot that predates the first, so the first would be silently discarded — the same
   * hazard `patchRowMany` exists for one component up, and the same one that loses a photograph
   * here. The updater is pure and idempotent (React re-invokes it in development, and again whenever
   * a render is discarded under concurrent rendering), so returning `current` unchanged is the
   * whole of the no-op path: React bails out on an identical reference and nothing re-renders.
   */
  const write = useCallback((key: string, update: PendingUpdate) => {
    setHeld((current) => {
      const existing = current[key] ?? EMPTY_PENDING;
      // RESOLVED INSIDE THE UPDATER, against the slice React is actually about to replace, which is
      // the whole point of the shape: a caller's `(files) => [...files, one]` is applied to the
      // list as it stands at commit time and never to the one its render closed over.
      const files = typeof update === "function" ? update(existing) : update;
      if (existing.length === files.length && existing.every((file, index) => file === files[index])) return current;
      // An empty list is DELETED rather than stored, so a stage whose designer has attached and
      // drained a photograph on two hundred rows does not carry two hundred empty arrays for the
      // rest of the page's life.
      if (files.length) return { ...current, [key]: files };
      const next = { ...current };
      delete next[key];
      return next;
    });
  }, []);

  const store = useMemo<PendingMediaStore>(() => ({ held, write }), [held, write]);

  return <StagePendingMediaContext.Provider value={store}>{children}</StagePendingMediaContext.Provider>;
}

/**
 * The attached-but-unlinked files for one media control, and the setter for them.
 *
 * Returns the hoisted store's slice when there is a provider and plain local state when there is
 * not. BOTH hooks run unconditionally on every render — the provider's presence is fixed for the
 * life of a mount, so the unused half is inert rather than conditional.
 */
function usePendingMedia(place: StageMediaPlace): [File[], (update: PendingUpdate) => void] {
  const store = useContext(StagePendingMediaContext);
  const key = pendingKeyOf(place);
  const [local, setLocal] = useState<File[]>(EMPTY_PENDING);
  const hoisted = store?.held[key] ?? EMPTY_PENDING;
  const write = useCallback(
    (update: PendingUpdate) => {
      // The local fallback supports the updater form for free — it IS a state setter — so both
      // shapes behave identically with and without a provider, which is what lets the call sites
      // below be written once and be right in both.
      if (store) store.write(key, update);
      else setLocal(update);
    },
    // `setLocal` is listed because the React Compiler's own inference lists it: a state setter is
    // stable, so it costs nothing, and a manual list the compiler cannot reconcile makes it skip
    // optimising this component altogether.
    [store, key, setLocal]
  );
  return [store ? hoisted : local, write];
}

/**
 * The eager-upload owner name for one media control — see {@link StagePendingMediaProvider}.
 *
 * Derived from the SAME triple as the pending key and never from a mount id, which is the whole
 * point: two mounts of the same box are ONE owner, so collapsing and reopening a row cancels the
 * release of its objects instead of letting it expire.
 */
function stagingOwnerFor(place: StageMediaPlace): string {
  return `dw-field:${pendingKeyOf(place)}`;
}

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
  /**
   * WHO LAST SET THIS FIELD, as the server reported it on the stage read.
   *
   * Optional and unrendered when absent, which is what lets it be threaded in one surface at a time:
   * a stage page that does not pass it behaves exactly as it did before this prop existed. See
   * {@link FieldProvenance} — it is decorative, never part of `aria-describedby`.
   */
  stamp?: DwFieldStamp | null;
  /**
   * A REPOSITORY RECORD THAT ALREADY HAS AN EDIT SURFACE OPEN ON THIS PAGE — read by the REF branch
   * and by nothing else.
   *
   * Null everywhere but inside a mirror point, where `StageRecordEmbed` has the record's own page
   * mounted in edit mode below the picker. `StageReferenceSelect.recordFormMountedOver` carries the
   * whole argument; the short version is that the pencil beside the picker opens a SECOND editor on
   * the same record, and the older of the two wins on Save.
   *
   * Threaded as a prop rather than read from a context so that it travels the same way every other
   * fact about where a field is drawn travels, and so that nothing outside a mirror point acquires
   * a behaviour it cannot see in its own call.
   */
  recordFormMountedOver?: string | null;
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
function FieldHint({
  field,
  error,
  hintId,
  errorId,
  stamp
}: {
  field: DwField;
  error?: string | null;
  hintId: string;
  errorId: string;
  /** Who last set this field. See {@link FieldProvenance} — decorative, never describedby text. */
  stamp?: DwFieldStamp | null;
}) {
  const hint = [field.help, field.unit ? `Measured in ${field.unit}.` : ""].filter(Boolean).join(" ");
  return (
    <>
      {hint ? (
        <p id={hintId} className="text-xs leading-5 text-ink-500">
          {hint}
        </p>
      ) : null}
      {/* Under the instruction and above the error, which is the reading order that matters: what
          to do, then where this came from, then what is wrong with it. It is NOT part of
          `describedBy` — see FieldProvenance on why forty of these would make the form unusable by
          voice. */}
      <FieldProvenance stamp={stamp} />
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
 *
 * DECLARED HERE RATHER THAN ABOVE `FieldHint`, where it first landed, because inserting it there put
 * it BETWEEN `FieldHint`'s doc block and `FieldHint` — leaving a reader of this function facing an
 * essay about `role="alert"` and `aria-describedby` as its apparent documentation, and `FieldHint`
 * undocumented. That is the same orphaning the commit before this one repaired in `lib/offline.ts`,
 * reintroduced two files away in the act of repairing it.
 */
function derivedPlaceholder(field: DwField, value: DwValue | undefined, row: DwEntryData): string | undefined {
  if (!isDerived(field)) return undefined;
  if (!(value === null || value === undefined || value === "")) return undefined;
  const computed = deriveValue(field, row);
  if (computed === null) return undefined;
  return `${computed}${field.unit ? ` ${field.unit}` : ""} (computed)`;
}

/**
 * WHAT THE REGISTRY DECLARED AS A MULTI-VALUED FIELD'S CEILING, OR NULL — the number that may be
 * PRINTED.
 *
 * `field_to_dict` emits `maxItems` only for a field that states one, so an absent key is not a number
 * and must never be drawn as one: "up to 200" under a gallery would be this client naming a figure it
 * did not read and the server may change, and a stated cap that is not the enforced cap is worse than
 * no sentence at all (docs/DESIGN_WORKSHOP.md:229-232). Two of the registry's 20 IMAGE_LIST fields
 * answer this — the motif pair, 20 each — and none of its 12 TAGS or 5 MULTI_ENUM fields do.
 *
 * The `> 0` is not defensive padding: Android's `FieldDto.maxItems` defaults to 0 for the same
 * absence, so a schema that ever reached this client through that shape has to read as "not
 * declared" here too rather than as a ceiling of zero.
 */
function declaredMaxItems(field: DwField): number | null {
  return typeof field.maxItems === "number" && field.maxItems > 0 ? field.maxItems : null;
}

/**
 * WHAT A MULTI-VALUED FIELD IS ACTUALLY ENFORCED AGAINST — declared, or the server's own default.
 *
 * The other half of the same paragraph, and the half both clients failed until 2026-08-26: reading an
 * absent `maxItems` as NO ceiling is exactly what it forbids. `coerce_value` refuses an over-long
 * array rather than trimming it and `save_stage` restores a refused key from the previous entry, so
 * the 201st tag or photograph does not cost itself — it costs every entry the field was about to
 * store, reported as one error against the field with the uploading already done.
 *
 * Read by the three controls that can GROW a list — {@link MediaField}, {@link TagsField} and
 * {@link MultiEnumField} — each of which trims before anything is uploaded or stored and then says
 * what it turned away, naming the ceiling only where {@link declaredMaxItems} answered. Both halves
 * together: gating the notice on the declared cap while trimming at this one would turn a loud
 * refusal into a silent drop, which is the failure the rule is written against.
 * `DwMediaCapture.kt` does the same on the handset off the same constant.
 */
function effectiveMaxItems(field: DwField): number {
  return declaredMaxItems(field) ?? DW_DEFAULT_MAX_ITEMS;
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
  capture,
  stamp,
  recordFormMountedOver
}: FieldInputProps) {
  const controlId = useId();
  const labelId = `${controlId}-label`;
  const hintId = `${controlId}-hint`;
  const errorId = `${controlId}-error`;
  const formatErrorId = `${controlId}-format`;

  /**
   * WHY WHAT IS IN THIS BOX WILL NOT SAVE, SAID BEFORE THE SAVE — or null when it will.
   *
   * ── THIS IS THE HALF THE AUDIT FOUND MISSING, AND IT IS NOT A CONVENIENCE ─────────────────────
   *
   * `coerce_value` refuses a value whose declared `text_format` it does not match, and `save_stage`
   * then RESTORES THE REFUSED KEY FROM `previous`. The save response carries the message, so
   * immediately afterwards the box still shows what was typed with the reason underneath — which is
   * correct and recoverable. The revert only becomes visible on the next
   * `GET /{id}/stages/{key}`: the typed value is replaced by the old one and the message is gone,
   * because the message lived in the save response and nothing persisted it. That is the "value just
   * snaps back" the audit saw, and from the designer's chair it is indistinguishable from the app
   * losing their work.
   *
   * A rule the box can apply to what it is HOLDING has no such lifetime problem: it is derived from
   * the value on screen, so it is on the page the moment the value is wrong, it survives every
   * re-render, and after a revert it re-evaluates against whatever came back. In the ordinary case
   * the malformed value never leaves the browser at all.
   *
   * ── NOT `setCustomValidity`, WHICH IS HOW THE RECORD PAGE DOES IT ─────────────────────────────
   *
   * `AadhaarField` blocks through native constraint validation, and that mechanism needs a SUBMIT
   * EVENT. A mirrored stage box is rendered OUTSIDE the record form `StageRecordEmbed` mounts, so no
   * submit event ever passes over it — which is also the exact reason `type="email"` did nothing
   * here for as long as this branch has existed. The comment claiming otherwise is corrected below.
   */
  const formatProblem = fieldFormatError(field, value);
  /**
   * True when the mounted control prints that sentence itself, so this wrapper must not repeat it.
   *
   * PHONE and the PIN code box both reuse the record page's control, and both of those controls
   * already show the same message from the same function as the designer types. `aria-invalid` and
   * the describedby below are still wired from `formatProblem` — what is suppressed is one duplicated
   * paragraph, never the fact that the field is refused.
   */
  const formatShownBelow = Boolean(formatProblem) && !formatShownByControl(entity, field);

  /**
   * What the control points at with `aria-describedby`, or nothing when there is nothing to say.
   *
   * Order matters and is the reading order on screen: the instruction first, then what went wrong
   * with the answer. An empty string here would be a describedby pointing at no element, which some
   * readers announce as a blank — worse than the attribute being absent.
   *
   * THE FORMAT REFUSAL IS LAST AND IS SEPARATE FROM `errorId`, because the two are different facts:
   * `error` is what the repository said about the value it stored, and this is this browser saying
   * the value in the box will not get that far. A designer who has both must be read both — folding
   * them into one slot would drop whichever arrived second.
   */
  const hasHint = Boolean(field.help || field.unit);
  const describedBy =
    [hasHint ? hintId : null, error ? errorId : null, formatShownBelow ? formatErrorId : null]
      .filter(Boolean)
      .join(" ") || undefined;
  /** Undefined rather than `false`: a valid field says nothing, it does not announce its validity. */
  const invalid = error || formatProblem ? true : undefined;

  /**
   * A dictated phrase this box could not take, and the value it was refused over.
   *
   * THE VALUE IS PART OF THE STATE so the notice clears itself. It is a fact about an ACT — "the
   * phrase you just spoke was not added" — and it stops being true the moment the box changes by
   * any route: a hand edit, a shorter dictated phrase, a chooser writing a sentence. Keying it to
   * the value it was refused over gets that for free and needs no `setState` wired into the
   * `onChange` of six separate branches, three of which are inside child controls this file only
   * passes a `dictation` node to.
   */
  const [dictationRefusal, setDictationRefusal] = useState<{ dropped: number; over: string } | null>(null);
  const dictationDropped =
    dictationRefusal && dictationRefusal.over === inputValue(value) ? dictationRefusal.dropped : 0;

  /**
   * Append a dictated phrase to whatever the box already holds.
   *
   * APPEND, never replace. The recogniser is stopped and started many times across a long answer,
   * and a commit that overwrote the field would delete the previous three sentences the moment
   * somebody paused for breath. The separator is a space unless the box already ends in one, so a
   * paragraph dictated in five goes does not come out as "…the warpis sized…".
   *
   * ── AND IT IS BOUNDED BY `maxLength`, WHICH IT WAS NOT ──────────────────────────────────────────
   *
   * Every box this commits into carries `maxLength={field.maxLength || undefined}` on the element,
   * and that attribute constrains TYPING ONLY — it has no effect on a value written
   * programmatically, which is exactly what this function writes. So dictation was the one way past
   * the bound on every field in the registry that declares one, and the failure it produced is the
   * one this whole surface keeps arguing against: `coerce_value` refuses the over-length string,
   * `save_stage` then restores the refused key from `previous`, and the designer is left with an
   * error against a box whose value silently reverted. The narrowest case is the worst — the media
   * note is `max_length=200` and arrives ALREADY HOLDING a hydrated sentence of sixty-odd
   * characters, so two dictated sentences about the footage overrun it — and `StageMediaNoteField`
   * refuses an over-length SELECTION on screen for precisely that reason while the microphone beside
   * it wrote one anyway.
   *
   * REFUSED, NOT TRUNCATED, and the same argument the chooser makes: a sentence cut to fit is a
   * claim nobody can tell is wrong, and this box may hold a count that goes into a ministry
   * document. The phrase is dropped whole, the box keeps exactly what it had, and the notice below
   * the field says how far over it was — which is also the only thing that tells the designer to
   * shorten the box before speaking again rather than to blame the microphone.
   */
  const appendDictated = useCallback(
    (text: string) => {
      const existing = inputValue(value);
      const joiner = !existing || /\s$/.test(existing) ? "" : " ";
      const composed = `${existing}${joiner}${text}`;
      // An absent bound means unbounded, not zero — `field_to_dict` emits only non-default keys.
      if (field.maxLength && composed.length > field.maxLength) {
        setDictationRefusal({ dropped: composed.length - field.maxLength, over: existing });
        return;
      }
      setDictationRefusal(null);
      onChange(composed);
    },
    [value, onChange, field.maxLength]
  );

  /**
   * Wrapper for a control that IS a labelable element — the `<label htmlFor>` case.
   *
   * `min-w-0` is load-bearing on the wrapper: a grid item will not shrink below its content's
   * intrinsic width unless told to, so a long unbroken value (a URL, a pasted transcript, a 40-word
   * enum label) widens its column and spills over the field beside it. `truncate` cannot save it —
   * truncation clips inside a box that has already grown.
   */
  /**
   * The refused-dictation sentence, in BOTH wrappers so it cannot depend on which control drew.
   *
   * Placed after `FieldHint` — below the instruction and below the server's own error, which is the
   * reading order the describedby above already sets. NOT folded into `FieldHint`'s `error` slot:
   * that slot is the SERVER's answer about the stored value, and this is the client refusing to
   * write one. Two different facts, and a designer who has just been told the stage failed to save
   * must not have that sentence replaced by one about a microphone.
   *
   * `error-600` is one of the two literal status colours in the palette and deliberately does not
   * invert — "this was not written" has to read identically in both themes.
   */
  const dictationNotice =
    dictationDropped > 0 ? (
      <p className="text-xs font-medium leading-5 text-error-600">
        What you just dictated was not added: it would make this answer {dictationDropped} character
        {dictationDropped === 1 ? "" : "s"} longer than the {field.maxLength} this field stores, and a sentence cut to
        fit is worse than one not written. The box is unchanged — shorten what is in it, then dictate again.
      </p>
    ) : null;

  /**
   * The declared format's refusal, IN BOTH WRAPPERS so it cannot depend on which control drew.
   *
   * ── WHY IT IS HERE AND NOT INSIDE ONE BRANCH ──────────────────────────────────────────────────
   *
   * A format can be declared on TEXT, LONG_TEXT, URL, PHONE or EMAIL, and those reach four different
   * branches below — the plain input, `StageAddressField`, `StagePhoneField` and the textarea. Put in
   * the wrapper, one line covers every one of them, and a field that gains a format tomorrow on a
   * type nobody thought about is covered without a line changing. That is the same property the
   * registry itself exists for, and it is why the audit's own recommendation — a check in the TEXT
   * branch — would have left PHONE and the PIN code box exactly as they were.
   *
   * NOT FOLDED INTO `FieldHint`'s `error` SLOT, for the reason the dictation notice below is not
   * either: that slot is the SERVER's answer about the value it stored, and this is the browser
   * saying the value in the box will not reach it. A designer who has just been told the repository
   * refused their answer must not have that sentence replaced by a different one.
   *
   * `role="alert"`, matching `PhoneField` and `AadhaarField`: the message appears as the designer
   * types, so it has to reach them at the moment the value stops being savable rather than when they
   * next happen to pass the box. `error-600` is one of the two literal status colours in the palette
   * and deliberately does not invert — "this will not save" must read identically in both themes.
   *
   * ── WHAT THIS DELIBERATELY DOES NOT DO IS BLOCK "SAVE STAGE" ──────────────────────────────────
   *
   * The record page blocks: `ArtisanForm` is a real `<form>`, so a malformed email or a nine-digit
   * phone number stops its Submit natively, and stopping it costs nothing because nothing else is in
   * flight. A stage save is not that. It carries every entity on the stage — thirty-odd answers,
   * a roster of participants, and staged media — in one request, from a handset in a courtyard on one
   * bar of signal, and `PhoneField`'s own doc block records the decision this surface already made:
   * "Native constraint validation is deliberately absent from the workshop … completeness is judged
   * by `stage_completeness` when a report is generated, not by the browser at save time." Refusing
   * the whole save over one malformed field would throw away the twenty-nine answers beside it.
   *
   * So the division of labour is: the SERVER refuses the one field (and `save_stage` keeps the
   * previous value for it while storing everything else), and this line makes sure the designer is
   * looking at the reason before they press the button rather than after. `EntityForm` counts these
   * on a collapsed group's header so one folded away inside a disclosure is not silent. If the owner
   * decides a format violation should also gate the button, `EntityForm`'s `needsAttentionIn` is the
   * count to gate on — it is the one that already merges this rule with the server's refusals, one
   * line per field — and the stage page's `save()` is the one place to do it, deliberately left
   * un-gated here rather than half-gated in a component that cannot see the whole stage. (This
   * paragraph named `formatViolationsIn` until it was deleted: a second exported counter that
   * nothing called, whose docstring claimed the job `needsAttentionIn` performs.)
   */
  const formatNotice = formatShownBelow ? (
    <p id={formatErrorId} role="alert" className="text-xs font-medium leading-5 text-error-600">
      {formatProblem}
    </p>
  ) : null;

  const labelled = (control: React.ReactNode) => (
    <div className="grid min-w-0 gap-1">
      <label className="field-label" htmlFor={controlId}>
        {labelText(field)}
      </label>
      {control}
      <FieldHint field={field} error={error} hintId={hintId} errorId={errorId} stamp={stamp} />
      {formatNotice}
      {dictationNotice}
    </div>
  );

  /** Wrapper for a control that contains a button — see the `<label>` rule in the file header. */
  const unlabelled = (control: React.ReactNode) => (
    <div className="grid min-w-0 gap-1">
      <span className="field-label" id={labelId}>
        {labelText(field)}
      </span>
      {control}
      <FieldHint field={field} error={error} hintId={hintId} errorId={errorId} stamp={stamp} />
      {formatNotice}
      {dictationNotice}
    </div>
  );

  switch (field.type) {
    case "PHONE":
      /*
       * THE RECORD PAGE'S OWN PHONE FIELD, MOUNTED WHOLE — not a second one.
       *
       * This branch used to fold PHONE in with TEXT/URL/EMAIL and render one `<input type="tel">`,
       * so a designer typing a participant's number into a stage got a box that accepted nine
       * digits, or fourteen, or letters, and said nothing — while the same designer typing the same
       * fact into the artisan record page two clicks away was told which of the two length rules
       * they had broken while they were still typing. The number is then a permanent copy on a
       * submitted roster, because a stage entry is never re-resolved (invariant 1).
       *
       * `PhoneField` brings the dial-code picker over all 246 entries of `lib/countries`, the
       * "+91 is exactly 10 digits, anything else 4–14" rule surfaced AS IT IS TYPED, and the
       * confirmation on leaving +91 that says what it means ("This marks the artisan as a foreign
       * resident"). Writing a second one here would be a second answer to what a valid phone number
       * is, and the two would drift — which is the argument `InlineRecordDialog` already makes for
       * mounting the REAL `ArtisanForm` rather than a quick-create.
       *
       * THE HANDSET WAS ALREADY DOING THIS, which is what settles it as an oversight rather than a
       * decision: `FieldRenderer.kt` gives `DwFieldType.PHONE` its own arm and calls
       * `ArtisanPhoneField`, accepting a duplicated inner caption to get it.
       *
       * `unlabelled`, for the reason that moved this control from `Field` to `FieldBlock` on the
       * record page: its first labelable descendant is the dial-code trigger, so a wrapping label
       * would slam the country list shut on one pick and fold every country name into the field's
       * accessible name. `field.maxLength` is deliberately not passed on either — the registry
       * length has no meaning against a combined dial-code-plus-digits string.
       */
      return unlabelled(<StagePhoneField value={value} onChange={onChange} disabled={disabled} />);

    case "TEXT":
    case "URL":
    case "EMAIL": {
      /*
       * The state, district and PIN code boxes get the record page's closed lists and its numeric
       * keypad instead of a bare input. See {@link StageAddressField} for what a hand-typed
       * administrative half has already cost this repository's live data, and `addressListRole` for
       * why this is the one role in `stageFieldRoles` that matches exact keys and never a pattern.
       */
      const address = field.type === "TEXT" ? addressListRole(entity, field) : null;
      if (address) {
        return unlabelled(
          <StageAddressField
            field={field}
            role={address}
            value={value}
            row={row}
            onChange={onChange}
            onPatch={onPatch}
            labelId={labelId}
            describedBy={describedBy}
            invalid={invalid}
            disabled={disabled}
          />
        );
      }
      /*
       * "DOCUMENTED AT WORKSHOP" GETS THE RECORD PAGE'S DROPDOWN, NOT A PROSE BOX.
       *
       * Two TEXT fields in the registry hold a `Workshop` row's TITLE — `participant`'s and
       * `workshopSetup`'s — and both are hydration targets, so the value that arrives is a title the
       * repository chose while anything typed over it is a title nothing can match. The artisan and
       * craft record pages have asked this question through a searchable list of workshops all along;
       * this is the same question, two clicks away, answered by a text box. See `workshopTitleRole`
       * for why the two keys are matched exactly, and `StageWorkshopField` for the scoped list, the
       * escape hatch, and where the dictation button goes.
       *
       * `unlabelled`, because the control contains a button: a wrapping `<label>` forwards a stray
       * click into the menu and slams it shut after one pick. Same reason as PHONE above.
       */
      if (workshopTitleRole(field)) {
        return unlabelled(
          <StageWorkshopField
            field={field}
            value={value}
            onChange={onChange}
            labelId={labelId}
            describedBy={describedBy}
            invalid={invalid}
            disabled={disabled}
            dictation={
              <DictationButton
                onCommit={appendDictated}
                disabled={disabled}
                fieldLabel={field.label}
                workshopId={workshopId}
              />
            }
          />
        );
      }
      /*
       * "MEDIA ON THE ARTISAN RECORD" GETS A CHOOSER OVER THE FILES ITS SENTENCE COUNTS.
       *
       * The owner asked for that box to be a multi-select. It is `participant.recordMediaNote` —
       * TEXT, `max_length=200`, a hydration target — and what lands in it is a SENTENCE `_media_note`
       * composes by counting the linked record's attached files. So unlike "Documented at workshop"
       * above there is no lossless one-to-one between a thing picked and the string stored: the string
       * is a count, and nothing a designer can tick produces one.
       *
       * WHAT THIS MOUNT THEREFORE DOES, AND WHAT IT REFUSES TO DO. `StageMediaNoteField` keeps the
       * hydrated sentence in an ordinary editable box — the value must stay readable and editable, and
       * a value that matches no option must never be stranded — and puts a searchable multi-select
       * over the record's files beneath it. Ticking files APPENDS a second sentence ("See in
       * particular: …") and never rewrites the record's own count into a smaller one: a subset count
       * presented as the record's total is a false claim in a document that goes to a ministry, and
       * this field exists so a reader knows what to ask for. An over-length result is refused on
       * screen rather than truncated, because `coerce_value` would refuse it on save and `save_stage`
       * would restore the old value under the error.
       *
       * `recordMediaNoteRole` reads the HYDRATION TABLE rather than this field's name, which is what
       * lets it answer the question the other roles cannot — which record's files to list — and where
       * the refusal for `traditionalProcess.recordMediaNote` lives (a different function, a different
       * grammar, dormant today). Four fields qualify; the process's box stays the plain input below.
       *
       * `unlabelled`, because the control contains a dropdown and two buttons: a wrapping `<label>`
       * forwards a stray click into the menu and slams it shut after one pick. Same reason as PHONE
       * and "Documented at workshop" above.
       */
      const mediaNote = field.type === "TEXT" ? recordMediaNoteRole(entity, field) : null;
      if (mediaNote) {
        return unlabelled(
          <StageMediaNoteField
            field={field}
            role={mediaNote}
            row={row}
            value={value}
            onChange={onChange}
            labelId={labelId}
            describedBy={describedBy}
            invalid={invalid}
            disabled={disabled}
            dictation={
              <DictationButton
                onCommit={appendDictated}
                disabled={disabled}
                fieldLabel={field.label}
                workshopId={workshopId}
              />
            }
          />
        );
      }
      /*
       * READ THE NUMBER OFF THE CARD, ON THE BOX THE NUMBER GOES IN.
       *
       * `IdentityCardReader` — the workshop's own OCR control — is mounted only under a MEDIA field,
       * and it can only read images that are ALREADY `MediaFile` rows. So the only web path to card
       * OCR on a roster row was: attach an unmasked PM Vishwakarma card to `participant.photo`,
       * thereby REPLACING the portrait hydration had just copied in, read the number, and hope the
       * designer then pressed Discard. `IdentityCardReader`'s own header states the cost of that
       * route — "an unmasked identity document is in the repository before anybody has been asked
       * whether it should be … It is a JPEG. Nothing downstream will ever redact it." That reasoning
       * is right for a photograph a designer genuinely attached, and it is a trap when it is the only
       * door.
       *
       * `IdentityCardCapture` is the record page's control and the never-stored route: the `File`
       * goes into one request body and the input is cleared, `photograph.stored` is `false` on every
       * answer the server sends, and nothing reaches the repository at all. It gates ITSELF on
       * `canRunDesignWorkshops`, which on a design-workshop stage is the whole audience.
       *
       * `kind="PEHCHAN"` and not "AADHAAR", deliberately: the in-browser recogniser offers Aadhaar
       * numbers only, and `IdentityCardCapture` already declines to offer it for PEHCHAN because a PM
       * Vishwakarma artisan ID has no checksum and no fixed shape. It carries a MASKED value from
       * `mask_identity_number` when it was hydrated — which is why `currentValue` is passed: the
       * control says what confirming would replace before it happens.
       *
       * `participant.artisanCardNo` IS STILL WHERE THIS LANDS, BUT IT IS NO LONGER THE ONLY
       * CANDIDATE, AND THE THING THAT KEEPS IT HERE IS DECLARATION ORDER. This paragraph used to
       * read "`participant.artisanCardNo` is the only field in the registry this resolves to today
       * (measured against the schema dump)", which stopped being true on 2026-08-24 when the owner
       * had `participant.aadhaarNumber` added to the same entity (see the note above that field in
       * the server's `stage_definitions.py`). `identityNumberField` returns the FIRST
       * non-deprecated TEXT field matching its pattern and the Aadhaar box is declared AFTER the
       * card box, so this mount is unmoved — but that is now a property of one registry line's
       * position rather than of there being nothing else to match. Reordering the two silently
       * moves the PEHCHAN capture onto the Aadhaar box, with no change on this side and nothing to
       * notice it.
       * `stage_definitions` says the same thing from its end; both sites have to, because neither
       * file can enforce it.
       *
       * THE HANDSET DOES NOT WORK THIS WAY, and the asymmetry is worth knowing while reading this.
       * `DwIdentityOcr.isIdentityNumberField` matches PER FIELD rather than picking one, so Android
       * offers the camera on BOTH boxes and `identityKindFor` returns AADHAAR for the new one, where
       * this client offers nothing at all. What the handset WRITES into the Aadhaar box is the mask,
       * not the digits: that field declares `storeMasked`, so its OCR control prints the full number
       * on the button to be proofread and commits "XXXX XXXX ####". Neither behaviour is wrong; they
       * are different answers to "which box gets the camera", and only one of them is decided in
       * this file.
       *
       * WHAT THIS MOUNT DOES NOT DO IS TEST WHETHER THAT VALUE IS STILL THE MASK, and it is written
       * down rather than left to be inferred because the code cannot say which way it was decided.
       * READ IT AS BEING ABOUT THE PEHCHAN CARD NUMBER ALONE. The Aadhaar box beside it settled the
       * same question the other way on 2026-08-24 — it declares `storeMasked`, so the server keeps
       * four digits of whatever is typed there — and this control is not mounted on it. That the two
       * boxes now answer differently is deliberate and is argued at both fields in
       * `stage_definitions.py`: this one has a capture control built to write the full number off
       * the card, and masking it would un-ship the control rather than enforce a decision.
       * Confirming a candidate calls `onChange(next)` unconditionally, so the full PM Vishwakarma
       * number can replace the masked copy hydration wrote — into a row nothing re-resolves
       * (invariant 1), on a surface whose stage reads do not pass through
       * `records._redact_sensitive`, which is the reason `design_workshops` masks it on the way in at
       * all. Two things bound the change: the box was hand-typeable before this control existed, so
       * the number arriving unmasked is not a new class of exposure, and `IdentityCardCapture` names
       * the replacement before it happens — it prints the value being replaced, and "Confirming
       * replaces it." beside it. The alternative is to withhold the mount while the current value
       * still looks like a mask, which would also withhold it from the designer standing with the
       * card in their hand. Which of the two a grantee-readable row should get is an owner call and
       * has not been made; nothing here should be read as having made it.
       *
       * `IdentityCardReader` stays exactly where it is. It is the right control for a card a designer
       * really did attach, and it is the only one of the two that can offer a real delete.
       */
      const identityTarget = field.type === "TEXT" ? identityNumberField(entity) : null;
      return labelled(
        <>
          <input
            id={controlId}
            className="field-input"
            /*
             * `type` CARRIES THE MOBILE KEYBOARD HERE, AND NOTHING ELSE. A field that opens the
             * alphabetic keyboard for an email address is a field a designer mistypes on a handset in
             * a courtyard, so `url` and `email` are worth setting for the `@` and the `.com` key they
             * put on the soft keyboard.
             *
             * THIS COMMENT USED TO END "`url`/`email` also give the browser its own inline validation
             * for free", AND THAT WAS FALSE AT THIS MOUNT POINT — measured, not suspected. A browser
             * validates `type="email"` on a SUBMIT EVENT (or an explicit
             * `checkValidity`/`reportValidity` call). This input is not inside a `<form>`: the stage
             * page is a set of controls with a "Save stage" button that calls `save()` directly, and
             * where `StageRecordEmbed` DOES mount a real `<form>` for a record, the mirrored copy of
             * the same field is rendered OUTSIDE it. So no submit event has ever passed over this box
             * and the browser has never checked one character of it.
             *
             * That sentence is the reason nobody looked. `participant.email` therefore had NO rule
             * anywhere on this path — not here, not in `coerce_value`, not in `ArtisanCreate` — while
             * the record page two clicks away had a regex AND a blocking `pattern`, and the handset
             * refused a missing `@` outright. The refusal now comes from `formatProblem` above, off
             * the registry's declared `text_format`, which reaches this box whether or not anything
             * ever submits it.
             */
            type={field.type === "URL" ? "url" : field.type === "EMAIL" ? "email" : "text"}
            maxLength={field.maxLength || undefined}
            aria-describedby={describedBy}
            aria-invalid={invalid}
            value={inputValue(value)}
            disabled={disabled}
            onChange={(event) => onChange(event.target.value)}
          />
          {/* Prose only. A speech recogniser hands back words, so dictating into a URL, an email
              address or a phone number produces "double you double you double you dot" and a
              designer who has to delete it — the button is a cost on those three, not a help. The
              PIN code is the fourth of that kind and is refused the button in `StageAddressField`,
              where the box itself lives. */}
          {field.type === "TEXT" ? (
            <DictationButton onCommit={appendDictated} disabled={disabled} fieldLabel={field.label} workshopId={workshopId} />
          ) : null}
          {identityTarget?.key === field.key ? (
            <IdentityCardCapture
              kind="PEHCHAN"
              targetLabel={field.label}
              currentValue={inputValue(value)}
              aadhaarProblem={aadhaarValidationError}
              onUse={(next) => onChange(next)}
              disabled={disabled}
            />
          ) : null}
          {field.storeMasked && inputValue(value).trim() && !isMaskedIdentityNumber(inputValue(value)) ? (
            /*
              WHAT THE SAVE WILL ACTUALLY KEEP, SAID WHILE THE DIGITS ARE STILL ON SCREEN.

              `storeMasked` means `coerce_value` replaces this value with `mask_aadhaar(...)` on the
              way in, so a designer who types a full Aadhaar number sees twelve digits, presses
              Save, gets a 200 with no error against this field, and finds four digits in the box
              afterwards with nothing having said why. That is rule 10 of this repository's frontend
              contract read the other way round: a value quietly rewritten is indistinguishable from
              a value quietly lost, and this one goes into a submitted document.

              WHY IT IS NOT ALSO MASKED HERE. The box is where a designer PROOFREADS the number
              against the card in their hand — that is the whole reason the field is typeable, and
              masking on blur would leave a typo uncorrectable without retyping all twelve. So the
              client says what will happen and the server is the single place it happens.

              SHOWN ONLY OVER AN UNMASKED VALUE. `isMaskedIdentityNumber` is the same "contains an
              X" rule as the server's `is_masked_aadhaar`, so the sentence disappears once the box
              holds the mask — over a hydrated row it would be a warning about nothing, and a
              warning that is always on is a warning nobody reads. THE MASK CASE IS NOT SILENT ANY
              MORE, THOUGH: it gets its own sentence in the branch below, which states a fact rather
              than raising an alarm.
            */
            <p className="text-xs leading-5 text-ink-500">
              Only the last four digits of this number are stored. Saving this stage replaces what is in the box with
              “XXXX XXXX {inputValue(value).replace(/\D/g, "").slice(-4) || "####"}”, which is also all the report
              prints — so check the number against the card now, not afterwards.
            </p>
          ) : field.storeMasked && isAadhaarMaskValue(inputValue(value)) ? (
            /*
              AND WHAT THE BOX IS HOLDING WHEN IT IS ALREADY A MASK, WHICH USED TO BE SAID NOWHERE.

              A mask has to be an ACCEPTED value here — `hydrate_entries` writes one into this box
              and `coerce_value` re-checks every field on every save, so refusing it would put a
              permanent red error on a row nobody touched over digits the designer is not entitled
              to see (`_aadhaar_format_error` has the long version). The consequence is that a
              designer can also TYPE or PASTE "XXXX XXXX 1234" into it and nothing objects: it is
              byte-for-byte what hydration writes, `mask_aadhaar` is idempotent so it stores
              unchanged, and the report prints it exactly like a mask of a number somebody had read
              off a card. The server cannot tell those two apart — the only discriminator is what the
              row held BEFORE the save, and `coerce_value` is handed one value and nothing else.

              So this sentence is the one thing that CAN separate them, and it separates them for the
              only reader who knows which happened: the person looking at the box. It states what is
              there rather than warning about it, which is why it is `ink-500` and not `error-600`
              and why it is correct over a hydrated row as well — "this stage holds four digits, the
              number is on the artisan record" is exactly what a designer needs to know before they
              go looking for the rest of it here.
            */
            <p className="text-xs leading-5 text-ink-500">
              This box is holding a mask, not a number: “{inputValue(value)}” is all this stage stores and all the
              report prints. The full number is on the artisan record. Typing or pasting a mask here stores exactly
              that — four digits nobody has checked against a card.
            </p>
          ) : null}
        </>
      );
    }

    case "LONG_TEXT":
      /*
       * A BULLETS FIELD IS A LIST, SO IT GETS THE LIST CONTROL.
       *
       * The signal is already declared and already read one branch below: RICH_TEXT passes
       * `listKind={field.reportRole === "BULLETS" ? "ORDERED_ITEM" : undefined}` on the reasoning
       * that "a BULLETS field IS a list — its help says 'One deliverable per line' and the report
       * prints it as one — so the editor opens inside a numbered item rather than making the designer
       * type '1. ' to get the behaviour the label already promised them." That argument was written
       * for RICH_TEXT and applies word for word to LONG_TEXT, where it was not applied: `participant.dos`
       * and `participant.donts` are declared BULLETS with the help text "One point per line", the
       * record page gives them `DosDontsField` — numbered rows, Enter-splits-a-point, a per-row
       * Remove, multi-line paste exploded into rows — and the workshop gave them a bare textarea
       * where the newline boundaries are load-bearing and invisible. A designer who typed "1. " out
       * of habit got the numbers printed INSIDE the bullets.
       *
       * THE CONTROL IS THE RECORD PAGE'S, not a copy: `splitNumbered`/`joinNumbered` and the rows
       * moved to `components/forms/NumberedListInput`, which `DosDontsField` now renders too. The
       * newline-joined string is a three-way contract — this repository's record forms write it,
       * Android's `NumberedListInput` reads it back into rows, `report_builder` splits it into
       * bullets — so there may only ever be one function that decides where a point ends.
       *
       * The plain textarea stays for every other LONG_TEXT, so nothing is refused: the four BULLETS
       * fields in the registry today are `participant.dos`, `participant.donts`,
       * `tool.usedByArtisans` and `traditionalProcess.documentedSteps` (measured against the schema
       * dump), and the last two arrive newline-joined from hydration in exactly this shape. Where a
       * hydrated line already begins "1." — `_step_lines` writes its own ordinals — the rows draw
       * their ordinal beside it and the duplication becomes visible instead of only being printed.
       *
       * `unlabelled`, because the rows carry a Remove button each and an "Add point" below them. The
       * `<span className="field-label">` that wrapper renders is then handed to the control as
       * `labelId`, per the `<label>` rule in the file header: the rows are named by their ordinal
       * alone, so without the group name `participant.dos` and `participant.donts` — adjacent on one
       * stage — are announced as two identical runs of "Point 1"…"Point n". `DosDontsField` already
       * names its group for exactly that reason.
       */
      if (field.reportRole === "BULLETS") {
        return unlabelled(
          <NumberedListField
            value={inputValue(value)}
            onChange={(next) => onChange(next || null)}
            labelId={labelId}
            disabled={disabled}
            describedBy={describedBy}
            invalid={Boolean(error)}
            // Committed into the row the designer is IN rather than appended to the end of the list —
            // the reason `MultiNoteInput` gives one microphone per note on the record page.
            renderDictation={(commit) => (
              <DictationButton onCommit={commit} disabled={disabled} fieldLabel={field.label} workshopId={workshopId} />
            )}
          />
        );
      }
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
          <DictationButton onCommit={appendDictated} disabled={disabled} fieldLabel={field.label} workshopId={workshopId} />
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
          // Threaded so the editor's own dictation button posts to the per-workshop route, which is
          // the only one that can enforce the artisan's consent.
          workshopId={workshopId}
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
       *
       * THE PARTNER'S LABEL TRAVELS WITH THE BOUND, and it is not decoration. The bound refused a
       * typed date in total silence for as long as it existed: the box snapped back to the old value
       * on blur with nothing said, so the only way to move the earlier end of a range forward was to
       * clear the later end first — a rule stated on no screen. `DateField` now keeps the typed date
       * and explains itself, and the explanation is only actionable if it can name the field the
       * designer has to change; that name is here and nowhere below.
       */
      const range = dateRangePartner(entity, field);
      const partnerIso = range ? inputValue(row[range.partner.key]).slice(0, 10) : "";
      return labelled(
        <DateField
          id={controlId}
          value={inputValue(value).slice(0, 10)}
          min={range?.role === "end" ? partnerIso || undefined : undefined}
          max={range?.role === "start" ? partnerIso || undefined : undefined}
          minLabel={range?.role === "end" ? range.partner.label : undefined}
          maxLabel={range?.role === "start" ? range.partner.label : undefined}
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

    /*
      ── ENUM AND MULTI_ENUM DELIBERATELY LEAVE `searchable` ALONE ──
      `field.options` is the option list the STAGE DEFINITION declares — a vocabulary written by
      whoever authored the workshop template, not a list of records — so the option-count rule is
      the right judge here and not the call site: four options ("Cotton / Silk / Jute / Other") get
      the plain list they deserve, and an authored list that runs to thirty grows a filter box on its
      own. Forcing it on would put a filter box over every three-option question in all 22 stages;
      forcing it off would take one away from the long ones. This is the case `SEARCH_THRESHOLD` was
      measured for.
    */
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
        <MultiEnumField field={field} describedBy={describedBy} value={value} onChange={onChange} disabled={disabled} />
      );

    case "TAGS":
      return unlabelled(
        <TagsField
          labelId={labelId}
          describedBy={describedBy}
          field={field}
          value={value}
          onChange={onChange}
          disabled={disabled}
        />
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
          // "This record already has its own page open below you" — see the prop's own note there.
          recordFormMountedOver={recordFormMountedOver}
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
            extra={({ files, originals, attach, detach, local }) => (
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
                    // The photograph the designer chose to DELETE is already gone from S3 and from
                    // MediaFile by the time this runs — the server deleted both before answering.
                    // This only stops the field from going on referencing an id that no longer
                    // resolves, which would otherwise draw a tile reading "this file is no longer
                    // readable from here" for a file the designer deliberately destroyed.
                    onDiscard={detach}
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
                {/*
                  CAPTION AND SUBTITLES, THROUGH THE SAME DOOR EVERY OTHER EXTRA GOES IN BY.

                  Composed with the others rather than chosen between — see the note above: a
                  ternary would silently suppress whichever extra lost, and a stage-13 prototype
                  photograph is legitimately both measurable and describable.

                  It uses NEITHER `attach` NOR `onPatch`, and that is the whole point of it: a verb's
                  output is a layer over this file, read and accepted by a named person on the AI
                  layers screen, and it never becomes a value in this field or in the caption box
                  beside it. `MediaAiVerbs` has nothing that could write one.
                */}
                <MediaAiVerbs workshopId={workshopId} files={files} local={local} disabled={disabled} />
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

    default: {
      /*
        A TYPE THIS BUILD HAS NO BRANCH FOR IS DRAWN READ-ONLY AND SAYS SO. IT IS NEVER A BLANK.

        THE FAILURE THIS PREVENTS, VERIFIED RATHER THAN ASSUMED. The switch above is exhaustive over
        `DwFieldType`, so TypeScript treats the end of this function as unreachable and reports
        nothing — but the union is a COMPILE-time guarantee only, and this file's own header records
        that JSON is `any` at the fetch boundary and the compiler cannot catch it. Without this arm an
        unmatched type falls off the end and the component returns `undefined`, which React 19
        (19.2.7 in this workspace) renders as nothing with NO dev-mode warning: the legacy "Nothing
        was returned from render" guard is gone. `FieldGrid` still draws the cell around it
        unconditionally, so what a designer sees is an empty grid cell where a question should be —
        and no way to know a question was ever there.

        WHO ACTUALLY REACHES IT. No registry field can: the union covers all 23 of them and the
        compiler enforces it, which is the whole point of the missing `default` this arm now supplies.
        What reaches it is a DESIGNER-DEFINED question whose type is outside the twelve v1 types —
        `lib/customSections.ts` re-tokenises those through `unsupportedFieldType` precisely so they
        arrive here rather than at a working control. That is deliberate for the ones this build DOES
        know how to draw: a custom GEO or IMAGE would sync as a reference none of the five media
        walkers can resolve, so the save reports success and the photograph is simply absent from the
        .docx.

        IT IS DISABLED, WHICH IS THE OPPOSITE CALL FROM THE REF FALLBACK ABOVE, AND BOTH ARE RIGHT. A
        REF with no model can be answered by hand — a closed list with no members cannot be answered
        at all, and on a required field that makes the stage permanently unsubmittable. An unknown
        type cannot be answered by hand: nothing downstream could coerce, score or print what was
        typed, so an editable box here would collect an answer into a shape no reader has. Android
        makes the same call for the same reason, and its version of this bug was worse — `DwFieldType.of`
        degrades an unknown token to TEXT, so the handset drew an ordinary editable box and the
        designer typed into it.
      */
      const raw = fieldTypeName(String(field.type as string));
      const noteId = `${controlId}-unsupported`;
      return labelled(
        <>
          <input
            id={controlId}
            className="field-input"
            type="text"
            disabled
            // The sentence is part of what this box MEANS, so it is named here alongside the
            // registry's own hint rather than left as prose only a sighted reader can connect to the
            // field — exactly as the REF fallback does it. A sentence that is not pointed at is
            // invisible to the reader most likely to need it.
            aria-describedby={[noteId, describedBy].filter(Boolean).join(" ")}
            value={inputValue(value)}
          />
          <p id={noteId} className="text-xs leading-5 text-ink-500">
            This question is a {raw}, which this version of the form cannot draw. Whatever is already
            recorded against it is kept and is not changed by anything you do here.
          </p>
        </>
      );
    }
  }
}

/* ────────────────────────────────────────────────────────────────────────────
 * PHONE
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `PhoneField` against a stage entry's single stored string.
 *
 * THE ONE THING THIS ADAPTER IS FOR is hydration. `PhoneField` is an UNCONTROLLED control by design —
 * it owns the split between dial code and national digits, seeds itself from `defaultValue` once, and
 * reports the recombined string — and everything else in this file is controlled off `value`. Mounted
 * naively that difference would be a regression rather than a detail: `REFERENCE_HYDRATION` writes
 * `participant.phone` from the artisan record the moment a designer picks one, and an uncontrolled box
 * seeded before that would keep showing the old number while the row held the new one. A form and a
 * record that disagree after a write is the exact defect `TitleCasedInput` was built to end, and it
 * must not be reintroduced by the act of closing a different gap.
 *
 * So the incoming value is compared against what this control last EMITTED, and only a value that
 * came from somewhere else — hydration, a re-point, a draft adopted from another device — bumps the
 * remount key and re-seeds the box. Keying on the value itself would remount on every keystroke and
 * take the caret with it.
 *
 * `mirror={false}`, AND IT MATTERS MORE NOW THAN WHEN IT WAS WRITTEN. The reason used to be that
 * "the stage page is not a `<form>`", so the zero-size mirror would contribute a stray `name="phone"`
 * and a `pattern` to whatever a later change wrapped the page in. That change has now happened: a
 * mirror-point entity embeds the repository record's own page inline (`StageRecordEmbed`) and
 * renders the stage's own fields INSIDE that `<form>`, which reads its payload with
 * `new FormData(event.currentTarget)`. A named mirror in there would post a stage answer as an
 * artisan column. Nothing in this file may carry a `name`, and the same rule is why native
 * constraint validation is absent throughout — see the DATE branch on why `required` is never
 * passed either. A stage field must never be able to refuse a record form's submit.
 */
function StagePhoneField({
  value,
  onChange,
  disabled
}: {
  value: DwValue | undefined;
  onChange: (next: DwValue) => void;
  disabled?: boolean;
}) {
  const incoming = inputValue(value);
  const emitted = useRef(incoming);
  const [seed, setSeed] = useState(incoming);
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    if (incoming === emitted.current) return;
    emitted.current = incoming;
    setSeed(incoming);
    setGeneration((current) => current + 1);
  }, [incoming]);

  return (
    <PhoneField
      key={generation}
      defaultValue={seed}
      disabled={disabled}
      mirror={false}
      onValueChange={(next) => {
        emitted.current = next;
        // "" means the digits box is empty, and an empty stage value is null, not "" — `isAnswered`
        // treats the two the same but the server stores what it is sent.
        onChange(next || null);
      }}
    />
  );
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
 *
 * IT ALSO CARRIES THE ITEM CEILING, because `maxItems` governs TAGS exactly as it governs a gallery
 * (docs/DESIGN_WORKSHOP.md:223) and this box read it nowhere at all until 2026-08-26. None of the
 * registry's twelve TAGS fields declares one, so the ceiling in force here is today always
 * {@link DW_DEFAULT_MAX_ITEMS} — two hundred chips, far past any real answer, and exactly the
 * absence both clients used to read as "no limit". `coerce_value` refuses the whole field over it, so
 * a box that let a designer past it would trade a refused chip for a refused list.
 */
function TagsField({
  labelId,
  describedBy,
  field,
  value,
  onChange,
  disabled
}: {
  labelId: string;
  /** The field's hint and refusal message — see `FieldHint`. */
  describedBy?: string;
  /** Read for its label and for its item ceiling — see {@link effectiveMaxItems}. */
  field: DwField;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  disabled?: boolean;
}) {
  const tags = listValue(value);
  const [draft, setDraft] = useState("");
  /**
   * The values this ceiling turned away, held until they are typed again.
   *
   * Same rule as {@link MediaField}'s `refused` and for the same reason: the box is cleared on
   * commit, so a chip that was silently not added is a word the designer typed and cannot see
   * anywhere. A removal deliberately does NOT clear this list — it makes room for exactly these
   * values, which is what the sentence asks for.
   */
  const [refused, setRefused] = useState<string[]>([]);
  /** Printable only where it was declared, enforced either way — see {@link declaredMaxItems}. */
  const declaredCap = declaredMaxItems(field);
  const cap = effectiveMaxItems(field);
  /** `useId` and not a literal: a stage draws one of these per TAGS field — see {@link MediaField}. */
  const capId = `${useId()}-cap`;

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
      const turnedAway: string[] = [];
      for (const part of parts) {
        if (seen.has(part.toLowerCase())) continue;
        // THE CEILING BEFORE THE VALUE IS STORED, not after the save refuses the field. Counted
        // against `next` rather than `tags` so a single pasted "a, b, c" cannot step over it.
        if (next.length >= cap) {
          turnedAway.push(part);
          continue;
        }
        seen.add(part.toLowerCase());
        next.push(part);
      }
      setRefused(turnedAway);
      if (next.length !== tags.length) onChange(next);
      setDraft("");
    },
    [tags, onChange, cap]
  );

  /**
   * The refusal, in words — and the CEILING is named only where the registry declared it.
   *
   * Which is the whole of the contract in one sentence: the number is unprintable where it was not
   * read (docs/DESIGN_WORKSHOP.md:229-232), and the refusal still has to be loud, because a value
   * dropped in silence is the failure the rule is written against. So "is full" without a figure,
   * and the words themselves named either way.
   */
  const refusalNotice = !refused.length
    ? null
    : `${
        declaredCap === null
          ? `${field.label} is full`
          : `${field.label} holds at most ${declaredCap} ${declaredCap === 1 ? "entry" : "entries"}`
      }. Not added: ${refused.join(", ")}. Remove one, then type ${refused.length === 1 ? "it" : "them"} again.`;

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
        aria-describedby={[describedBy, declaredCap !== null ? capId : null].filter(Boolean).join(" ") || undefined}
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
      {/* THE DECLARED CEILING, SAID ON SCREEN — the PRINTED half of the `maxItems` contract, which
          this control had only the enforced half of. Both halves landed on the handset in the same
          pass that closed the enforcement gap (`FieldRenderer.kt`'s `DwListCapHint`, mounted on the
          TAGS and MULTI_ENUM controls) and only the enforcement half landed here, so for the length
          of one change a designer told "up to 20" on the handset was told nothing at all in the
          browser about the same field. The strings are Android's, verbatim.

          DESCRIBED, NOT ANNOUNCED, and the argument is the one written out at the foot of
          {@link MediaField}: this is a running total that is on screen from first paint, so a live
          region would read it out on every entry a reader added. It is named in the control's
          `aria-describedby` instead, which is where somebody who has added nothing yet meets it.
          What must interrupt is the refusal below, which names the values turned away.

          AND WHERE IT IS ABSENT THE CEILING IS STILL THERE: an undeclared list is held at
          {@link DW_DEFAULT_MAX_ITEMS} and says nothing, because that figure is the server's and this
          client did not read it. Every number here comes from `declaredCap`. */}
      {declaredCap !== null ? (
        <p id={capId} className="text-xs leading-5 text-ink-500">
          {declaredCap - tags.length <= 0
            ? `Full at ${declaredCap}. Remove one to add another.`
            : `Up to ${declaredCap} — ${declaredCap - tags.length} more can be added.`}
        </p>
      ) : null}
      {/* MOUNTED FROM FIRST PAINT AND HIDDEN WHEN EMPTY, never conditionally rendered: assistive
          technology announces mutations only inside a region that already existed when the page
          settled, so a paragraph that appears in the same breath as its first sentence is a
          sentence nobody hears. `sr-only` rather than `hidden` for the same reason, and because an
          absolutely positioned 1×1 paragraph contributes no row to this `grid gap-2`. The long form
          of this argument is on the three notices at the foot of {@link MediaField}. */}
      <p role="status" aria-live="polite" className={refusalNotice ? "text-xs leading-5 text-amber-800" : "sr-only"}>
        {refusalNotice}
      </p>
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * MULTI_ENUM
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A closed multi-select, with the registry's item ceiling applied to what it hands back.
 *
 * A WRAPPER RATHER THAN A BARE CALL TO `MultiSelectDropdown`, for one reason: `maxItems` governs
 * MULTI_ENUM exactly as it governs a gallery (docs/DESIGN_WORKSHOP.md:223) and the dispatch's
 * `onChange={(next) => onChange(next)}` read it nowhere at all. Trimming inside that handler and
 * saying nothing would have been worse than not trimming — a tick that silently does not take is a
 * control lying about its own state — so the trim and the sentence arrived together, which needed
 * somewhere to put the sentence.
 *
 * WHETHER THE CEILING CAN BITE TODAY, said plainly so nobody hunts for it: only where a field
 * declares one BELOW its own option count. None of the registry's five MULTI_ENUM fields declares
 * one, and the widest list any of them draws on is 15 entries against a default of 200, so this is a
 * rule that is read rather than a limit that is felt. That is the point of reading it. The absence is
 * what both clients used to take for "no ceiling at all", and the day a field declares three, the
 * trim is here and says so instead of the designer meeting it as a refused save.
 *
 * `searchable` IS STILL LEFT ALONE, for the reason given at the dispatch: `field.options` is an
 * authored vocabulary rather than a list of records, so the option count is the right judge.
 */
function MultiEnumField({
  field,
  describedBy,
  value,
  onChange,
  disabled
}: {
  field: DwField;
  /** The field's hint and refusal message — see `FieldHint`. */
  describedBy?: string;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  disabled?: boolean;
}) {
  const values = listValue(value);
  /** The options this ceiling turned away, by the label the designer read — see {@link TagsField}. */
  const [refused, setRefused] = useState<string[]>([]);
  /** Printable only where it was declared, enforced either way — see {@link declaredMaxItems}. */
  const declaredCap = declaredMaxItems(field);
  const cap = effectiveMaxItems(field);
  /** `useId` and not a literal — see {@link TagsField}. */
  const capId = `${useId()}-cap`;

  /** The word on the row, not the stored token: a designer cannot act on "MATERIAL_FAMILY_JUTE". */
  const optionLabel = (option: string) =>
    field.options?.find((candidate) => candidate.value === option)?.label ?? option;

  const refusalNotice = !refused.length
    ? null
    : `${
        declaredCap === null
          ? `${field.label} is full`
          : `${field.label} holds at most ${declaredCap} ${declaredCap === 1 ? "entry" : "entries"}`
      }. Not added: ${refused.join(", ")}. Remove one, then pick ${refused.length === 1 ? "it" : "them"} again.`;

  return (
    <div className="grid gap-2">
      <MultiSelectDropdown
        values={values}
        onChange={(next) => {
          /*
            THE CEILING CAPS GROWTH AND NEVER SHORTENS WHAT IS ALREADY STORED, which is the Kotlin
            twin's rule (`dwCapListGrowth`, `FieldRenderer.kt`) and was NOT this arm's until
            2026-08-26.

            `next.length <= values.length` is the half that was missing. A cap is not part of
            `registry_version()`, so a field may perfectly well be holding five entries on the day its
            declared ceiling becomes three — those values were valid when they were written. Under a
            bare `next.length > cap` test, a designer merely UNTICKING one of the five handed back
            four, which is still over, and the slice then deleted a second value they never touched —
            while the notice below said "Not added" about something that had in fact just been
            removed. Data loss reported as a refusal.

            So: any change that does not make the list longer passes through untouched (a shrink, or a
            same-size swap), and only genuine growth is capped.
          */
          if (next.length <= cap || next.length <= values.length) {
            setRefused([]);
            onChange(next);
            return;
          }
          // What is already held survives first; the ceiling is then filled from `next` in the order
          // the panel hands it back, so the tick that did not fit is the one refused.
          const keep = new Set(next.filter((option) => values.includes(option)));
          for (const option of next) {
            if (keep.size >= cap) break;
            keep.add(option);
          }
          setRefused(next.filter((option) => !keep.has(option)).map(optionLabel));
          onChange(next.filter((option) => keep.has(option)));
        }}
        options={field.options ?? []}
        placeholder="Select"
        emptyLabel="No options in this list"
        disabled={disabled}
        ariaLabel={field.label}
        describedBy={[describedBy, declaredCap !== null ? capId : null].filter(Boolean).join(" ") || undefined}
        confirmLabel="Confirm"
      />
      {/* The declared ceiling, said on screen — see the identical paragraph in TagsField. */}
      {declaredCap !== null ? (
        <p id={capId} className="text-xs leading-5 text-ink-500">
          {declaredCap - values.length <= 0
            ? `Full at ${declaredCap}. Remove one to add another.`
            : `Up to ${declaredCap} — ${declaredCap - values.length} more can be added.`}
        </p>
      ) : null}
      {/* Present from first paint, hidden when empty — see the identical paragraph in TagsField. */}
      <p role="status" aria-live="polite" className={refusalNotice ? "text-xs leading-5 text-amber-800" : "sr-only"}>
        {refusalNotice}
      </p>
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

/** One file a media field's declared ceiling turned away — see `refused` in {@link MediaField}. */
type RefusedFile = { key: string; name: string };

/**
 * A file's identity for the refused list.
 *
 * The same `name:size:lastModified` triple `MediaCaptureField`'s `mergeFiles`/`fileKey` use, and it
 * has to be that rather than the `File` object: the whole point of the list is to survive until the
 * designer picks the file AGAIN, and a second pick of the same bytes is a different `File`.
 */
function refusedKeyOf(file: File): string {
  return `${file.name}:${file.size}:${file.lastModified}`;
}

/** Drop from a refused list every entry that has now been attached successfully. */
function forgetRefused(current: RefusedFile[], attached: File[]): RefusedFile[] {
  if (!current.length || !attached.length) return current;
  const done = new Set(attached.map(refusedKeyOf));
  return current.filter((entry) => !done.has(entry.key));
}

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
     * Stop referencing one attachment. The counterpart to `attach`, and the same door the tile's
     * own Remove link goes in by.
     *
     * ONE CALLER: the identity-card reader, when a designer has chosen to DELETE the photograph of
     * an identity document. That deletion has already happened on the server by then — the object
     * and the `MediaFile` row are both gone — so this is not what destroys anything; it is what
     * stops the field from holding an id that no longer resolves and drawing a broken tile for a
     * file somebody deliberately destroyed. See `detach` below for what it does and does not touch.
     */
    detach: (id: string) => void;
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
  /**
   * The id of the ceiling sentence, so the group can be DESCRIBED by it.
   *
   * `useId` and not a literal: a stage draws one of these per media field, and a collection row draws
   * one per row per field — a fixed id would name every gallery on the screen at once.
   */
  const capId = `${useId()}-cap`;
  /**
   * THE DECLARED CEILING, OR NULL WHERE THE REGISTRY DECLARES NONE — the PRINTABLE one.
   *
   * Null and not 200, and it stays null so that nothing on screen can name a figure this client did
   * not read: `maxItems` is omitted from the published registry unless a field states one, so drawing
   * "up to 200" on every other gallery would be this client inventing a number the server owns and
   * may change, and a stated cap that is not the enforced cap is worse than no sentence at all
   * (docs/DESIGN_WORKSHOP.md:229-232). The count of galleries that would be is deliberately not
   * written here — it was "the other seventeen" while the registry declared eighteen of them, which
   * is what a figure kept in prose costs the next time a gallery is added.
   *
   * Where it IS declared — the two motif galleries, 20 each — the number is printed under the
   * picker, joined to the group's description, and the browsable carousel is offered. All three read
   * THIS and not the effective ceiling below, which is what keeps them honest.
   *
   * Only meaningful for a multi-valued field. A single IMAGE/FILE already holds one by construction.
   */
  const declaredCap = multiple ? declaredMaxItems(field) : null;
  /**
   * THE CEILING ACTUALLY ENFORCED HERE — declared, or the server's own default where there is none.
   *
   * The other half of the same paragraph, and the half this client failed until 2026-08-26: reading
   * an absent `maxItems` as NO ceiling is precisely what it forbids. `coerce_value` refuses an
   * over-long array rather than trimming it and `save_stage` restores the refused key from the
   * previous entry, so a designer who attaches the 201st photograph does not lose that photograph —
   * they lose every photograph the gallery was about to store, as one error against the field, with
   * all of the uploading already done.
   *
   * SO THE TRIM RUNS OFF THIS AND THE SENTENCE ABOVE OFF `declaredCap`, and `refusalNotice` below
   * fires either way. Gating the notice on the declared cap while trimming at 200 would turn a loud
   * refusal into a silent drop of the 201st file, which is the one thing both the doc and
   * `acceptFiles` refuse ("the honest act is to take what fits and SAY what did not").
   *
   * `null` only for a single-valued field: it has no ceiling to enforce, because the capture card
   * deliberately keeps the LAST file picked rather than refusing the second.
   */
  const cap = multiple ? effectiveMaxItems(field) : null;
  /**
   * Every stored id this control has LOOKED UP, and what came back. THREE STATES, NOT TWO.
   *
   * `undefined` — not looked up yet, or the request is still in flight.
   * `null`      — looked up, and the answer was no: deleted under the stage, not entitled, no signal.
   * a `MediaFile` — the row.
   *
   * The null used to be absent and the two states were collapsed, which made every row of a
   * twenty-photograph gallery print "This file is no longer readable from here" for as long as its
   * `GET /media/{id}` was in flight — on a village connection, several seconds of a stage saying
   * that twenty of its photographs are gone when nothing is wrong with any of them. A sentence that
   * is false while a fetch is running is not a smaller defect than one that is false afterwards; it
   * is the one a designer sees first. The tile below now draws "Looking this file up…" for the
   * undefined case and the refusal only for `null`.
   */
  const [files, setFiles] = useState<Record<string, MediaFile | null>>({});
  /**
   * Staged-on-this-device tiles: what to draw for a `dwlocal:` reference, with the same three states.
   *
   * `undefined` is "this reference has not been read out of IndexedDB yet" — which is where a
   * just-attached photograph sits for a moment, and which the effect below reaches by REPLACING this
   * map rather than clearing it, so nothing already on screen flickers. `null` is the one failure
   * the local store is built to make impossible and therefore the one worth a sentence: the blob is
   * gone.
   */
  const [staged, setStaged] = useState<Record<string, { name: string; url: string | null; sizeBytes: number } | null>>(
    {}
  );
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /**
   * THE FILES THE CEILING TURNED AWAY, HELD IN THEIR OWN STATE AND NOT IN `notice`.
   *
   * ── WHAT SHARING `notice` COST ─────────────────────────────────────────────────────────────────
   *
   * The cap refusal — the sentence that NAMES the five photographs a 20-file gallery would not take
   * — used to be written into `notice`, which is also the channel `stageOffline` writes to and which
   * `settle` clears at the top of every batch. So the sequence was: attach 25 photographs, read
   * "Not attached: a.jpg, b.jpg, c.jpg, d.jpg, e.jpg", and then watch that sentence disappear a
   * second or two later — because the 20 that WERE accepted reached `ready`, the drain fired
   * `settle`, and `settle` begins `setProblem(null); setNotice(null)`. The only record of which five
   * files to re-pick was erased by the success of the other twenty, before anybody could write them
   * down. `acceptFiles` erased it a second way, on the next attach that happened to fit.
   *
   * ── WHY A LIST AND NOT A SENTENCE ──────────────────────────────────────────────────────────────
   *
   * Held as the files themselves, the sentence can be DERIVED at render — so the count of what is
   * accounted for stays true as files are removed and re-attached, instead of being a snapshot that
   * goes stale the moment somebody acts on it. And an entry leaves this list for exactly one reason:
   * the same file is attached successfully later (`forgetRefused`), which is the reader having dealt
   * with it. Nothing else clears it, because nothing else makes it untrue.
   *
   * Remembered by the `name:size:lastModified` triple rather than by `File` identity, which is the
   * same triple `MediaCaptureField.mergeFiles` de-duplicates on: a file re-picked out of the chooser
   * is a NEW `File` object for the same bytes, so identity would never match and the entry would
   * never leave. NOT by name alone, which is the tempting simplification: two shots off one handset
   * are both `IMG_0001.jpg`, and clearing one of them because the other was attached is the silent
   * loss this list exists to stop. The residual cost of the triple is a file EDITED between the
   * refusal and the re-pick, whose name then lingers in the sentence one attach too long — a
   * sentence that overstays beats one that disappears, and the counts around it stay true either way.
   *
   * LOCAL STATE, AND NOT HOISTED INTO `StagePendingMediaProvider` like `pending` is. Collapsing a
   * collection row therefore forgets the sentence. That is a stated limit rather than an oversight:
   * `pending` is hoisted because losing it DELETED photographs out of object storage, and this list
   * is a message about files that were never taken — recoverable by picking them again, which is what
   * the sentence asks for. Hoisting it would mean a fourth key in that store for a paragraph.
   */
  const [refused, setRefused] = useState<RefusedFile[]>([]);
  /**
   * Where this control sits, as the one key both halves of the survival fix are derived from.
   *
   * `place` is optional on `FieldInputProps` — a surface outside a stage may render a field with no
   * place at all — so the entity key stands in for a missing one. That is not a fallback that has to
   * be right, only one that has to be STABLE: with no provider above, `usePendingMedia` uses local
   * state and the key is never read.
   */
  const mediaPlace = useMemo<StageMediaPlace>(
    () => ({ entityKey: place?.entityKey ?? "", rowKey: place?.rowKey ?? null, fieldKey: field.key }),
    [place?.entityKey, place?.rowKey, field.key]
  );
  /**
   * What the capture card is holding right now: attached, transferring, or failed.
   *
   * HELD ABOVE THIS COMPONENT WHEREVER THERE IS A `StagePendingMediaProvider` — read its header for
   * what collapsing a collection row used to cost. Local state is the fallback and the old
   * behaviour, so nothing outside a stage page changes.
   */
  const [pending, setPending] = usePendingMedia(mediaPlace);
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
        // THE FAILURES ARE RECORDED AS `null` AND NOT SKIPPED. Skipping them left the id in the
        // "never looked up" state, which is the state the tile draws its "no longer readable"
        // sentence for — so the refusal appeared before the request had even been sent. Writing the
        // answer down is what lets the tile tell "we have not asked yet" from "we asked and it is
        // gone". A later run may still overwrite a `null` with a row: `unknown` above tests
        // falsiness, so an id whose read failed is asked again the next time this list changes,
        // which is the recovery path for the read that failed only because the signal was gone.
        for (const [id, file] of resolved) next[id] = file;
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
      const tiles: Record<string, { name: string; url: string | null; sizeBytes: number } | null> = {};
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
        if (!media) {
          // WRITTEN DOWN AS `null` RATHER THAN LEFT OUT. Left out, the reference is indistinguishable
          // from one this effect has not reached yet — and the tile drew "can no longer be found
          // here" for both, so a photograph attached a second ago was mourned while it was being
          // read. This is the one state the local store exists to prevent, so it keeps its sentence;
          // it just no longer borrows it for the ordinary case.
          tiles[ref] = null;
          continue;
        }
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
      // Both channels belong to ONE batch's outcome, so a new batch starts with neither. The
      // ceiling's refusal is deliberately NOT cleared here: it is about files this control never
      // took, it is `refused`'s to hold, and clearing it from this line is how the list of what to
      // re-pick used to vanish a second after it appeared. See `refused`.
      setProblem(null);
      setNotice(null);
      try {
        // Known-offline: do not burn a presign request and a stall timer to learn what the browser
        // already knows. The same shortcut `saveOrQueue` takes.
        if (typeof navigator !== "undefined" && navigator.onLine === false) {
          await stageOffline(chosen);
          return;
        }
        const { uploaded, failed, uploadedByIndex } = await uploadMediaBatch({
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
            /*
              BY POSITION, WHICH IS WHAT `uploaded` COULD NOT GIVE.

              This used to walk `uploaded` and index `chosen` with the same counter. `uploaded` is
              the by-index array with its NULLS FILTERED OUT (`lib/media.ts` — the sentence claiming
              it "keeps the caller's order" was true of the order and false of the positions), so the
              two arrays only line up in a batch where nothing failed. Attach three photographs, let
              the first be refused, and the second file was recorded under the third file's media id
              and the third under nothing at all.

              WHY THAT MATTERED MORE THAN A WRONG THUMBNAIL. `originals` has exactly one reader:
              `IdentityCardReader`, which OCRs the ORIGINAL bytes rather than fetching the uploaded
              copy back through a presigned URL, and prints the file's name beside the digits it
              read so a designer can check that the number belongs to the card in the photograph.
              Misfiled, it reads one card and names another — which silently defeats the one
              cross-check that header relies on, on identity data.

              NEVER MATCHED BACK BY FILENAME. Two shots off one handset are both `IMG_0001.jpg`, so a
              name match would misfile them again and look like it had worked.
            */
            uploadedByIndex.forEach((media, index) => {
              const source = chosen[index];
              if (media && source) next[media.id] = source;
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
    // AN UPDATER AND NOT `pending.filter(...)`. `finished` is decided from the value this effect
    // was fired with, which is correct — those are the files whose transfers this run claimed — but
    // the LIST it is subtracted from must be whatever the control holds at commit time. Read out of
    // this closure instead, a signature attached in the same tick is dropped: the two writes each
    // rebuild the whole list from the same stale slice and the later one wins entire. See
    // `PendingMediaStore.write`, which is why the setter takes this shape at all.
    setPending((current) => current.filter((file) => !finished.includes(file)));
    void settling;
    // `setPending` is `usePendingMedia`'s memoised writer rather than a raw state setter now, so it
    // is a real dependency — and it changes identity on every write to ANY key, not only when this
    // control's place changes: it depends on `store`, and the store object is memoised on the whole
    // `held` map. Harmless, because the effect's own work is idempotent (`claimedRef` is what stops
    // a file being settled twice) and re-running it against an unchanged `pending` finds nothing.
  }, [pending, staging, settle, setPending]);

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

  /**
   * How many more files this field can take, counting what is attached AND what is in flight.
   *
   * COUNTING `pending` IS THE WHOLE POINT. Every file in the capture card is a file that will become
   * an id the moment it lands, so a check against `ids` alone would let a designer queue the
   * twenty-first, twenty-second and twenty-third photographs — each one uploading, none of them
   * refused until the save. `null` only on a single-valued field, which has no room to count: it
   * keeps the last file picked rather than refusing the second. It is never null for a gallery now —
   * an undeclared ceiling is a ceiling of {@link DW_DEFAULT_MAX_ITEMS}, not the absence of one — so
   * the only thing an undeclared gallery loses is the printed figure, not the count.
   */
  const room = cap === null ? null : Math.max(0, cap - ids.length - pending.length);

  /**
   * The capture card's list, with the declared ceiling applied and anything dropped SAID OUT LOUD.
   *
   * ── WHY TRIMMING IS RIGHT HERE AND REFUSING IS RIGHT ON THE SERVER ─────────────────────────────
   *
   * `coerce_value` refuses the whole field, and it must: it is the last door, it cannot ask, and
   * silently keeping 20 of 25 there would mean a stored value that is not what any client sent.
   * Here there IS somebody to tell, immediately, before a byte is uploaded — so the honest act is to
   * take what fits, name the exact filenames that did not, and leave the field valid. A refusal at
   * this door would be a file picker that appears to do nothing.
   *
   * ── WHY THE MESSAGE NAMES THE FILES ────────────────────────────────────────────────────────────
   *
   * "Only 20 photographs are allowed" tells a designer holding 25 nothing about which 5 to re-pick.
   * `uploadMediaBatch`'s own contract in this repo is the same rule — every caller must inspect
   * `failed` and NAME the filenames that did not make it — and this is the same failure one step
   * earlier.
   *
   * A GROWING SELECTION IS NOT ALWAYS AN ADDITION. `MediaCaptureField` also calls this to REMOVE a
   * file (its per-tile discard) and to retry one, so the guard only bites when the incoming list is
   * longer than the current one; a shrink or a same-length replacement passes straight through. Left
   * unguarded, discarding a file while the field was at its ceiling would have re-run the trim
   * against `room === 0` and thrown away the reader's own removal.
   *
   * ── AND THE REFUSED FILENAMES OUTLIVE THIS CALL ────────────────────────────────────────────────
   *
   * What is written here is the LIST (see `refused`), never the sentence: the sentence is derived at
   * render so its "now accounted for" count cannot go stale, and the list is cleared one entry at a
   * time as those very files are attached. What this function must not do — and used to do twice —
   * is throw the list away because something ELSE succeeded. A removal makes room for exactly these
   * files, so it leaves the list alone; an addition that fits forgets only the entries it attached.
   */
  function acceptFiles(next: File[]) {
    if (room === null || next.length <= pending.length) {
      setPending(next);
      return;
    }
    const added = next.slice(pending.length);
    if (added.length <= room) {
      setPending(next);
      // Only what actually went in. `setNotice(null)` used to stand here, on the reasoning that "a
      // previous refusal is stale the moment the reader makes room and adds successfully" — which is
      // true of the file they just re-attached and false of the other four still waiting to be.
      setRefused((current) => forgetRefused(current, added));
      return;
    }
    const kept = added.slice(0, room);
    const dropped = added.slice(room);
    setPending([...next.slice(0, pending.length), ...kept]);
    setRefused((current) => {
      // The re-attached ones leave first, so a designer who re-picks five and gets one in sees four
      // named rather than five; then the newly turned-away ones join, de-duplicated, because picking
      // the same over-the-ceiling batch twice is one fact and not two.
      const carried = forgetRefused(current, kept);
      const known = new Set(carried.map((entry) => entry.key));
      const grown = [...carried];
      for (const file of dropped) {
        const key = refusedKeyOf(file);
        if (known.has(key)) continue;
        known.add(key);
        grown.push({ key, name: file.name });
      }
      return grown;
    });
  }

  /**
   * The refusal, in words, derived from the list rather than frozen when it happened.
   *
   * Rule 10: a ceiling that quietly keeps the first twenty of twenty-five is the "Stage saved, and
   * the photographs are gone" failure the server's `coerce_value` refuses outright to avoid. Here
   * there is somebody to tell, so the names are on screen — "Only 20 photographs are allowed" tells
   * a designer holding 25 nothing about WHICH five to re-pick, which is the same rule
   * `uploadMediaBatch`'s callers are under one step later.
   *
   * The count is recomputed here, not remembered: remove two attached photographs and the sentence
   * says so, which is what makes the instruction in it actionable rather than a receipt.
   *
   * ── AND IT FIRES FOR AN UNDECLARED CEILING TOO, WITHOUT NAMING IT ──────────────────────────────
   *
   * Gated on `refused` alone and no longer on a declared cap, because every gallery has a ceiling
   * now (see `cap`) and a trim nobody is told about is the silent drop this whole notice exists to
   * prevent. What changes is the first clause and only the first clause: with a declared cap the
   * sentence states it and reconciles the total against it, and without one it says the field is
   * FULL and stops — because the figure is the server's and this client did not read it
   * (docs/DESIGN_WORKSHOP.md:229-232). The filenames are named either way, which is the part a
   * designer holding twenty-five photographs can act on.
   */
  const refusalNotice = (() => {
    if (!refused.length) return null;
    const accounted = ids.length + pending.length;
    const ceiling =
      declaredCap === null
        ? `${field.label} is full`
        : `${field.label} holds at most ${declaredCap} file${declaredCap === 1 ? "" : "s"}, and ` +
          `${accounted} ${accounted === 1 ? "is" : "are"} accounted for`;
    return (
      `${ceiling}. Not attached: ${refused.map((entry) => entry.name).join(", ")}. ` +
      `Remove something already attached, then pick ${refused.length === 1 ? "it" : "them"} again — ` +
      `this list stays until you do.`
    );
  })();

  /**
   * The attached images, as the carousel reads them.
   *
   * ── WHY A CAROUSEL IS OFFERED AT ALL, AND ONLY ON A CAPPED GALLERY ─────────────────────────────
   *
   * Asked for on 2026-08-25 for the two motif galleries: the references have to be BROWSABLE, not
   * merely counted, because the question a designer is answering while they look at them ("is this
   * the motif I am writing about") needs one image big enough to see.
   *
   * It is gated on `declaredCap !== null` rather than on the field key, which is the difference
   * between a feature and a special case: a gallery whose ceiling somebody bothered to declare is a
   * gallery meant to be LOOKED at, and the two motif galleries are the two that declare one today.
   * Gating on `motifPhotos`/`contemporaryMotifPhotos` by name would put the app's behaviour in an
   * `if` instead of in the registry, and the next such gallery would silently not get it.
   *
   * THE DECLARED CAP AND NOT `cap`, WHICH IS NOW NEVER NULL FOR A GALLERY. Since the effective
   * ceiling defaults to {@link DW_DEFAULT_MAX_ITEMS}, reading it here would mount a carousel on all
   * twenty IMAGE_LIST fields — a behaviour change nobody asked for, arriving as a side effect of a
   * fix to the trim. "Somebody declared a ceiling" is still the signal; it is just no longer the
   * same expression as "there is a ceiling".
   *
   * LOCAL, NOT-YET-UPLOADED PHOTOGRAPHS ARE INCLUDED, and they have to be: in a courtyard with no
   * signal, every photograph taken today is a `dwlocal:` reference, and a carousel that showed only
   * the ones the server has acknowledged would be empty on precisely the afternoon it is wanted.
   * `staged[...].url` is the object URL this component already made for its own thumbnail, so no
   * second blob read happens for it.
   *
   * ── AND WHAT IT LEAVES OUT IS COUNTED, NOT DROPPED ─────────────────────────────────────────────
   *
   * `MediaCarousel` prints "3 of 12" off `items.length`, so every id this memo declines to build an
   * item for makes that readout SHORT of what the field is holding — twenty attached motifs, three
   * whose `GET /media/{id}` was refused, and the browsable view says "1 of 17" over a gallery of 20
   * with nothing anywhere to explain the missing three. That is the readout the carousel's own header
   * argues is "the state, not the ornament", quietly wrong.
   *
   * So the two reasons an id is left out are counted separately and said in words under the strip:
   * the row could not be read from here at all, and the file is not an image (a FILE field's
   * attachment, a video). They are separate because they need different things from the reader — one
   * is a connection or an entitlement, the other is simply not a photograph and never will be.
   * Counted only once each id has actually been LOOKED UP — and an id still IN FLIGHT is the third
   * count rather than a third silence. It used to be a bare `continue`, which is what let the
   * sentence below print figures that do not add up: `files` and `staged` are filled by two
   * independent effects, so a field holding both uploaded ids and `dwlocal:` references really does
   * pass through a moment where 17 are shown, 1 is not an image, and the field holds 20. Transient,
   * self-correcting, and still a sentence that was wrong while somebody was reading it.
   */
  const carousel = useMemo(() => {
    if (declaredCap === null) {
      return { items: [] as CarouselItem[], unreadable: 0, notImages: 0, pending: 0 };
    }
    const items: CarouselItem[] = [];
    let unreadable = 0;
    let notImages = 0;
    let pending = 0;
    for (const id of ids) {
      if (isLocalMediaRef(id)) {
        const local = staged[id];
        // `undefined` is "not read out of IndexedDB yet": still not an item, but counted now.
        if (local === undefined) {
          pending += 1;
          continue;
        }
        if (local === null) {
          unreadable += 1;
          continue;
        }
        if (!local.url) {
          // The effect makes an object URL for `image/*` blobs only, so a staged tile without one is
          // a staged file that is not a photograph.
          notImages += 1;
          continue;
        }
        items.push({ key: id, id: null, name: local.name, mediaType: "IMAGE", url: local.url });
        continue;
      }
      const file = files[id];
      // The same third state on the server side: `GET /media/{id}` has not answered yet.
      if (file === undefined) {
        pending += 1;
        continue;
      }
      if (file === null) {
        unreadable += 1;
        continue;
      }
      if (file.mediaType !== "IMAGE") {
        notImages += 1;
        continue;
      }
      items.push({
        key: id,
        id: file.id,
        name: file.originalFilename,
        mediaType: file.mediaType,
        mimeType: file.mimeType,
        sizeBytes: file.sizeBytes,
        url: file.url,
        caption: file.caption
      });
    }
    return { items, unreadable, notImages, pending };
  }, [declaredCap, ids, staged, files]);

  /**
   * "This carousel is not showing all of them", in words, or null when it is.
   *
   * Rule 10, and it is the carousel's COUNT that makes it necessary rather than the missing images
   * themselves: the file list above already names every one of these rows honestly, one line each.
   * What nothing said was that the strip's own "3 of 17" had stopped counting the same set as the
   * field. The sentence names the total the field holds, so the two figures can be reconciled by
   * reading rather than by counting rows.
   *
   * Drawn only where the strip is drawn. With NOTHING readable there is no strip and no count to
   * correct, and the list above is then the whole truth on its own.
   *
   * ── THE DENOMINATOR IS `ids.length` AND HAS TO STAY THAT WAY ───────────────────────────────────
   *
   * Making it `items.length + unreadable + notImages` would close the arithmetic by construction and
   * delete the sentence's reason to exist in the same stroke: what a reader needs reconciled against
   * a short strip is THE TOTAL THE FIELD HOLDS, and a denominator of only-the-resolved makes the
   * sentence trivially true. The gap is closed on the NUMERATOR side instead — the memo counts every
   * id it declines as one of three things, in-flight included — so the clauses add up to the total
   * while the total still means what it says.
   */
  const carouselOmissionNotice = (() => {
    const missing = carousel.unreadable + carousel.notImages;
    if (!carousel.items.length || missing === 0) return null;
    const parts: string[] = [];
    // "cannot" is the same word either way, so it is written once rather than as a branch whose two
    // arms are identical — the shape that hides a real missing plural somewhere else in a file.
    if (carousel.unreadable) parts.push(`${carousel.unreadable} cannot be read from here`);
    if (carousel.notImages) {
      parts.push(carousel.notImages === 1 ? "1 is not an image" : `${carousel.notImages} are not images`);
    }
    // THE THIRD CLAUSE, WHICH IS WHAT MAKES THE FIGURES CLOSE. Without it the memo's deliberate
    // skipping of in-flight ids printed "Showing 17 of the 20 … 1 is not an image", where 17 + 1 is
    // not 20 and nothing on screen accounted for the other two. It is only ever a sentence about a
    // moment, so it says so in those words rather than reading as a failure.
    if (carousel.pending) {
      parts.push(
        carousel.pending === 1 ? "1 is still being looked up" : `${carousel.pending} are still being looked up`
      );
    }
    const named = missing + carousel.pending;
    // Three clauses can reach this now, and ", and " between every pair reads as a list of unrelated
    // facts; the last join is the only one that should carry the "and".
    const clauses =
      parts.length > 2 ? `${parts.slice(0, -1).join(", ")}, and ${parts[parts.length - 1]}` : parts.join(", and ");
    // "has a line for" and no longer "names": an id whose lookup has not landed has no filename to
    // print yet, so its row above reads "Looking this file up…" — a line the reader can find rather
    // than a name. The third clause is what made the old wording an over-claim.
    return (
      `Showing ${carousel.items.length} of the ${ids.length} file${ids.length === 1 ? "" : "s"} attached to ` +
      `${field.label.toLowerCase()}: ${clauses}. The list above has a line for ` +
      `${named === 1 ? "it" : "each of them"}.`
    );
  })();

  return (
    <div
      className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3"
      role="group"
      aria-labelledby={labelId}
      /*
        THE FIELD'S OWN HINT, THEN ITS CEILING. The ceiling paragraph joins the description rather
        than announcing itself as a live region (see the paragraph itself for that argument), and it
        is appended rather than prepended so the reading order matches the screen: the instruction
        first, the number of files that will fit after it. Only where a cap is DECLARED — the
        paragraph does not exist otherwise, and a describedby pointing at no element is announced as
        a blank by some readers, which is worse than the attribute being absent. `declaredCap` and
        not `cap`: the effective ceiling is never null for a gallery, and pointing at an element that
        is not drawn is the same defect wearing the new constant.
      */
      aria-describedby={[describedBy, declaredCap !== null ? capId : null].filter(Boolean).join(" ") || undefined}
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
                          // to make impossible, so it is stated rather than drawn as an empty tile —
                          // but ONLY once the store has actually answered. `undefined` is "still
                          // being read", and printing the loss for that state told a designer their
                          // photograph was gone in the second between attaching it and the read
                          // returning. Both sentences are the truth about a different moment.
                          local === null
                          ? "This file was kept on this device and can no longer be found here."
                          : "Reading this file off this device…"
                        : file === null
                          ? "This file is no longer readable from here."
                          : "Looking this file up…"}
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

      {/* The browsable view of a capped gallery — see `carousel` for why it is gated on the declared
          cap and not on a field key. Drawn UNDER the file list rather than instead of it: the list is
          where a file is named, checked and removed, and the carousel is where it is looked at.
          Replacing one with the other would lose the Remove control the list carries.

          THE SENTENCE BELOW IT IS PART OF THE STRIP, not an aside: `MediaCarousel` prints "3 of 12"
          from the array it was handed, so whenever this memo left something out that readout was
          short of what the field holds and said nothing about it. It is NOT in a live region — the
          figure is a level, it moves only because the reader attached or removed something, and
          `MediaCarousel`'s own header records the decision not to announce its position readout.

          THE WHOLE LABEL GOES INTO `noun`, ON PURPOSE, AND MUST NOT BE TRIMMED HERE. Until
          2026-08-26 `MediaCarousel` wrote its own picture word after whatever it was handed, while
          documenting the prop as a bare noun ("traditional motif") that neither this call site nor
          the handset's ever passed — so the two capped galleries, labelled "Traditional motif
          photographs" and "Contemporary motif photographs", made a region announced as "traditional
          motif photographs photographs" and an arrow that said "Previous traditional motif
          photographs photograph" on every press. The component now derives the singular and the
          plural itself (`describeSubject`), because it is the only place that knows it needs both,
          and because a rule kept at the call sites is a rule two clients get to break independently
          — which is exactly what they did, in the same words.
          So do NOT "help" it by stripping "photographs" off the label here: that would restore the
          two-places-to-remember shape, and a bare stem now produces the identical four strings
          anyway. The lowercasing stays because two of those four are sentences.
          THE ANDROID TWIN AGREES AS OF 2026-08-26. `DwMediaCapture.kt` still passes
          `field.label.lowercase()` — unstripped, for the reason above — and `DwMediaCarousel.kt` now
          derives the singular and plural itself through `dwDescribeSubject`, so it announces
          "Previous traditional motif photograph" on a one-step control and names the frame with the plural.
          This note read "THE ANDROID TWIN IS STILL UNPORTED" until that landed. */}
      {carousel.items.length ? (
        <>
          <MediaCarousel items={carousel.items} noun={field.label.toLowerCase()} className="h-64 sm:h-80" />
          {carouselOmissionNotice ? (
            <p className="text-xs leading-5 text-ink-500">{carouselOmissionNotice}</p>
          ) : null}
        </>
      ) : null}

      {/* AN ATTACHED DOCUMENT, READ WHERE IT WAS ATTACHED.
          Asked for on 2026-08-25 for the market survey upload — *"If the uploaded Market Survey is a
          PDF, it should be rendered/previewable within the application"* — and it serves every FILE
          field in the registry rather than that one, because "is this the right document" is the same
          question at the sanction order, the questionnaire, the certificate and the designer's CV.
          `DocumentPreview` embeds a PDF and draws a named download row for anything else, which is
          the same split the instruction draws ("rendering is not mandatory for non-PDF formats").

          ONLY FOR A SERVER-ACKNOWLEDGED id. A `dwlocal:` reference is a blob in IndexedDB with no
          `MediaFile` row to resolve, so `GET /media/{id}` would 404 and the preview would report a
          readable document as unreadable. The tile above already says "On this device only", which
          is the honest state until it syncs.

          THIS `noun` DOES NOT STUTTER, and it was checked rather than assumed when the carousel's
          did (2026-08-26). `DocumentPreview` appends no noun of its own — the label lands whole in
          "No {noun} on file.", "Loading the {noun}…", "This {noun} is no longer readable from
          here.", "This browser will not display the {noun} inline." and the embed's `aria-label` —
          so nothing can be doubled the way `MediaCarousel` doubled "photographs". Read against every
          FILE label in the registry, the closest thing to a repeat is "Line art / vector file"
          giving "No line art / vector file on file.", where the second "file" is the idiom and not
          the noun again; it needs no derivation and must not be sent through one.
          What `toLowerCase()` DOES cost here is an acronym: "Designer’s CV" is announced as
          "designer’s cv". Left as it is — the two live sentences need lower case mid-sentence, and
          per-label casing is a judgement for whoever owns the copy, not something to guess at from a
          call site. */}
      {field.type === "FILE" && ids.length === 1 && !isLocalMediaRef(ids[0]) ? (
        <DocumentPreview mediaId={ids[0]} noun={field.label.toLowerCase()} className="h-[28rem]" />
      ) : null}

      {extra
        ? extra({
            files: ids.map((id) => files[id]).filter((file): file is MediaFile => Boolean(file)),
            originals,
            // Appended rather than replacing the list: a designer may sign, then attach a
            // photograph of the paper sheet as well, and neither may evict the other.
            attach: (file) => setPending((current) => [...current, file]),
            detach,
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
      {/* THE CEILING, IN WORDS, WHEREVER ONE IS DECLARED — rule 10, and the sentence a designer
          needs BEFORE they photograph twenty-five motifs rather than after. Drawn above the picker
          so it is read on the way in, and it states what is left rather than only the total: "20
          photographs" is a rule, "4 more" is an answer.

          IT IS A LEVEL, SO IT IS DESCRIBED AND NOT ANNOUNCED. This carried `role="status"`, which
          re-read "Up to 20 files — 17 more can be attached" after every single attach and every
          single remove, from the first photograph to the twentieth. `CollectionTable` admits a live
          count on three conditions, and this meets only two of them: the number moves because the
          reader acted, but the sentence is on screen from first paint rather than appearing near the
          ceiling, so most of what it announces is a running total nobody asked for — the shape §17
          forbids for a scroll-position readout. Instead the paragraph is named in the group's
          `aria-describedby`, so the ceiling is read on ENTERING the field, which is where the reader
          who has not photographed anything yet actually needs it; and the one event that does have
          to interrupt — files this ceiling turned away — is `refusalNotice` in the live region
          below, which names them.

          AND WHERE THIS PARAGRAPH IS ABSENT THE CEILING IS STILL THERE. An undeclared gallery is
          held at {@link DW_DEFAULT_MAX_ITEMS} (see `cap`) and says nothing about it, because the
          figure is the server's and this client did not read it; what it must never do is go quiet
          about the FILES that ceiling turns away, which is `refusalNotice`'s job on both branches.
          Every number in this paragraph therefore comes from `declaredCap`. */}
      {declaredCap !== null ? (
        <p id={capId} className="text-xs leading-5 text-ink-500">
          {room === 0
            ? `${field.label} is full at ${declaredCap} file${declaredCap === 1 ? "" : "s"}. Remove one to attach another.`
            : `Up to ${declaredCap} file${declaredCap === 1 ? "" : "s"} — ${room} more can be attached.`}
        </p>
      ) : null}

      <MediaCaptureField
        files={pending}
        /* Capped and narrated — see `acceptFiles`. `setPending` directly would upload files the
           server is going to refuse at the save, which is the defect this wave closed. */
        onFilesChange={acceptFiles}
        /*
          THE OTHER HALF OF THE ROW-COLLAPSE FIX, and it is useless without the hoisted list above.
          The eager-upload store deletes an owner's unclaimed objects two seconds after that owner
          goes away, and the default owner is a per-mount `useId()` — so collapsing a row expired the
          grace period and deleted a photograph that was already in object storage. Naming the owner
          after the CONTROL rather than the mount makes reopening the row cancel that release.
          See `StagePendingMediaProvider` and `useEagerStaging`'s own `ownerKey` note.
        */
        stagingOwnerId={stagingOwnerFor(mediaPlace)}
        title={multiple ? `Add to ${field.label.toLowerCase()}` : field.label}
        description={
          multiple
            ? "Every file attached here uploads straight away and joins this field when it lands. Audio is queued for transcription."
            : "The file attached here uploads straight away and replaces whatever this field held. Audio is queued for transcription."
        }
        allowedTypes={ALLOWED_TYPES[field.type]}
        allowDocuments={field.type === "FILE"}
      />

      {/* Every one of these reports something that has ALREADY happened to the designer's files — a
          batch that only partly landed, bytes kept on this device because the network was gone, or
          files the declared ceiling turned away before a byte was uploaded. Left as plain paragraphs
          they were coloured text and nothing else, so a designer using a screen reader attached four
          photographs, heard nothing, and walked away from a workshop believing all four were in the
          repository. `status` for the two notices (nothing is broken, do not interrupt) and `alert`
          for the problem (files were lost and must be attached again).

          ALL THREE ARE MOUNTED FROM FIRST PAINT, which is the half that was missing. The roles used
          to sit on paragraphs that did not exist until they had something to say, so the region came
          into being in the same breath as its first sentence — and assistive technology announces
          mutations only inside a region that ALREADY EXISTED when the page settled. Both sentences
          were therefore visible and unspoken: pick twenty-five motifs and the five filenames that
          did not attach were on screen for a sighted reader and nowhere at all for a listening one.
          `SubmissionCard`, `CollectionTable`'s status region and `Toast`'s permanently-present
          viewport are the same fix for the same reason.

          `sr-only` AND NOT `hidden`, AS A CLASS SWAP ON ONE ELEMENT. Empty, each paragraph is
          absolutely positioned and 1×1: in the accessibility tree, and contributing no row to this
          `grid gap-2` box, where three always-visible empty paragraphs would pay three 8px gaps of
          dead space under every media field in the app. `display: none` — `empty:hidden`, `hidden`,
          or unmounting the node — would take the region back OUT of the tree and put this exact
          defect straight back; `SubmissionCard`'s header says so in as many words.

          THREE ELEMENTS AND NOT ONE, because they are three different facts and `role="status"`
          implies `aria-atomic`: sharing a region would re-read the offline notice every time the
          refusal changed, and let one interrupt the other mid-sentence. */}
      <p
        role="status"
        aria-live="polite"
        className={refusalNotice ? "text-xs leading-5 text-amber-800" : "sr-only"}
      >
        {refusalNotice}
      </p>
      <p role="status" aria-live="polite" className={notice ? "text-xs leading-5 text-amber-800" : "sr-only"}>
        {notice}
      </p>
      <p role="alert" aria-live="assertive" className={problem ? "text-xs font-medium text-error-600" : "sr-only"}>
        {problem}
      </p>
    </div>
  );
}
