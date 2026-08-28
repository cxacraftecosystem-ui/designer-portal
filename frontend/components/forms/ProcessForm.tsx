"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { Lock, Plus } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { LIST_PAGE_CEILING, listCut, mergeById, type ListCut } from "@/components/data/cappedList";
import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { Field, Select, TextInput } from "@/components/FormControls";
import { appendDictatedPhrase } from "@/components/richtext/dictatedValue";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RichTextField } from "@/components/richtext/RichTextField";
import { FieldProvenance } from "@/components/FieldProvenance";
import { CarryContextBanner, carryScope, useCarryContext, type CarryScopeState } from "@/components/forms/CarryContextBanner";
import type { InlineRecordSurfaceProps } from "@/components/forms/inlineRecordHost";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { useRecordOffPage } from "@/components/forms/recordPickers";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import {
  DesignWorkshopSelect,
  useDesignWorkshopSelection
} from "@/components/forms/DesignWorkshopSelect";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { StatusBadge } from "@/components/StatusBadge";
import { Dropdown } from "@/components/ui/Dropdown";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { apiFetch, listResource } from "@/lib/api";
import { handleFormEnter } from "@/lib/formNav";
import { describePreProcess, describeProcessStep, renameMediaFile, uploadMediaFile } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { hasRank } from "@/lib/permissions";
import type { Artisan, ExtraMetadata, MediaFile, ProductDocumentation, RecordStatus, User, Workshop } from "@/lib/types";
import { useConfirm } from "@/components/dialogs";

// ---------------------------------------------------------------------------
// Types for the /processes endpoints (hydrated detail: media on the process =
// pre-process clips, media on each step = that step's captures).
// ---------------------------------------------------------------------------

export type ProcessStepRecord = {
  id: string;
  name: string;
  stepType: string;
  sortOrder?: number;
  notes?: string | null;
  media?: MediaFile[];
};

export type ProcessRecord = {
  id: string;
  name: string;
  productId: string;
  preProcessAvailable?: boolean;
  notes?: string | null;
  status: RecordStatus | string;
  steps?: ProcessStepRecord[];
  media?: MediaFile[];
  product?: ProductDocumentation | null;
  // The workshop this process was documented at. The API also resolves a process through its parent
  // product's workshop, so an older process can be null here and still belong to a workshop.
  workshopId?: string | null;
  /** The design & prototype workshop this process is filed under. See `Artisan` in lib/types.ts. */
  designWorkshopId?: string | null;
  workshop?: Workshop | null;
  extraMetadata?: ExtraMetadata | null;
  createdAt: string;
  createdById?: string;
  createdBy?: User;
};

const STATUS_OPTIONS = ["DRAFT", "PENDING", "APPROVED", "REJECTED"];

/** Mutable UI holder for one step: name, fixed type, optional notes, and its own media. */
type StepUi = {
  key: string;
  serverId?: string;
  name: string;
  stepType: string;
  recordAdditional: boolean;
  notes: string;
  files: File[];
  existingMedia: MediaFile[];
  nameError?: string | null;
};

function stepTypeLabel(stepType: string): string {
  return stepType === "SEQUENTIAL" ? "Sequential" : "Group of activities";
}

function makeKey(): string {
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

/**
 * IS THERE WORK ON THIS FORM THAT NO SERVER HAS? — the question the leave guard, the Cancel button
 * and `beforeunload` all ask, answered in one place so they cannot drift apart.
 *
 * Pulled out of the component on purpose. This repository has no React renderer in its
 * devDependencies, so a rule left inline in a hook body can only ever be READ by a test; the part
 * that was wrong is executable here instead (`discarded-work-unit.spec.ts`), exactly as
 * `dateOutsideBounds` was pulled out of `DateTimeField` for the same reason.
 *
 * `committed` is the term this defect is about. The signature compares what is TYPED against what
 * was typed at mount — it knows nothing about what has been stored — and this form's media uploads
 * run after the record is written, so a failed upload returns with the process saved, the form still
 * mounted and the signature still changed. Without `committed`, `dirty` stayed true over a record
 * that already existed, and the guard's "Save" button re-ran `submit()`: a duplicate process on the
 * create path, a re-upload of every attachment on the edit path. A guard that offers to re-apply a
 * partially-applied write destroys work rather than protecting it, which is the opposite of the
 * finding it was added for.
 */
export function hasUnsavedWork({
  saving,
  committed,
  signature,
  initialSignature
}: {
  /** A save is in flight; the dialog's own busy state covers this, and prompting mid-write is noise. */
  saving: boolean;
  /** `saveOrQueue` has returned — the write is in the database, or in the outbox that will replay it. */
  committed: boolean;
  signature: string;
  initialSignature: string;
}): boolean {
  return !saving && !committed && signature !== initialSignature;
}

// ---------------------------------------------------------------------------
// Saved media list ("Already attached:") with per-item delete — Android parity.
// ---------------------------------------------------------------------------

function SavedMediaList({
  items,
  onRemoved,
  onError
}: {
  items: MediaFile[];
  onRemoved: (id: string) => void;
  onError: (message: string) => void;
}) {
  const [active, setActive] = useState<PreviewMedia | null>(null);
  const [removingId, setRemovingId] = useState<string | null>(null);
  const confirm = useConfirm();
  // After the hooks: an empty list renders nothing at all (no "Already attached:" heading).
  if (!items.length) return null;

  async function remove(media: MediaFile) {
    const ok = await confirm({
      title: "Remove this file?",
      body: `"${media.caption || media.originalFilename}" will be removed from this step.`,
      note: "The file is permanently deleted from storage. This cannot be undone.",
      confirmLabel: "Remove file",
      tone: "danger"
    });
    if (!ok) return;
    setRemovingId(media.id);
    try {
      await apiFetch(`/media/${media.id}`, { method: "DELETE" });
      onRemoved(media.id);
    } catch (err) {
      onError(err instanceof Error ? err.message : "Unable to remove media");
    } finally {
      setRemovingId(null);
    }
  }

  return (
    <div className="grid gap-2">
      <p className="text-xs text-ink-500">Already attached:</p>
      <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        {items.map((media) => {
          const preview: PreviewMedia = {
            key: media.id,
            id: media.id,
            name: media.originalFilename,
            mediaType: media.mediaType,
            mimeType: media.mimeType,
            sizeBytes: media.sizeBytes,
            url: media.url,
            caption: media.caption,
            transcriptStatus: media.transcriptStatus,
            transcriptText: media.transcriptText,
            transcriptError: media.transcriptError
          };
          return (
            <MediaPreviewTile
              key={media.id}
              item={preview}
              onOpen={() => setActive(preview)}
              onRemove={removingId === media.id ? undefined : () => remove(media)}
              removeLabel="Remove"
            />
          );
        })}
      </div>
      {active ? <MediaLightbox item={active} onClose={() => setActive(null)} /> : null}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Controlled multi-note input (Android MultiNoteInput): notes stored \n\n-joined.
// Give it a fresh React `key` to reset its internal rows.
// ---------------------------------------------------------------------------

function splitNotes(value: string): string[] {
  return value
    .split(/\n\s*\n/)
    .map((note) => note.trim())
    .filter(Boolean);
}

function MultiNoteInput({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  const [rows, setRows] = useState<string[]>(() => {
    const split = splitNotes(value);
    return split.length ? split : [""];
  });

  function emit(updated: string[]) {
    const next = updated.length ? updated : [""];
    setRows(next);
    onChange(
      next
        .map((note) => note.trim())
        .filter(Boolean)
        .join("\n\n")
    );
  }

  return (
    <div className="grid gap-2">
      <span className="text-sm font-semibold text-ink-900">{label}</span>
      {rows.map((note, index) => (
        <div key={index} className="grid gap-1">
          <div className="flex items-start gap-2">
            <textarea
              className="field-input min-h-16 flex-1"
              rows={2}
              placeholder={rows.length > 1 ? `Note ${index + 1}` : "Note"}
              value={note}
              onChange={(event) => emit(rows.map((n, i) => (i === index ? event.target.value : n)))}
            />
            {rows.length > 1 ? (
              <button
                type="button"
                aria-label="Remove note"
                className="field-button-secondary h-9 min-h-0 shrink-0 px-2.5"
                onClick={() => emit(rows.filter((_, i) => i !== index))}
              >
                ✕
              </button>
            ) : null}
          </div>
          {/*
            A MICROPHONE PER NOTE, AND NO FORMATTING TOOLBAR.

            Dictation belongs here: this is the box a researcher fills standing at a loom describing
            what the artisan's hands are doing, which is the whole argument for speaking instead of
            typing. Rich text does not: each row is a two-line "additional context for this step",
            the several rows are joined with a blank line into one `ProcessStep.notes` column, and
            Android's `MultiNoteInput` splits that column back apart on blank lines. A document in
            there would be one note containing JSON. The user's rule — formatting on the LARGER boxes
            only — reads the same way from the other side.

            The button is per ROW rather than one for the group because a single microphone would
            have to guess which note the phrase belongs in, and its only defensible guess (the last
            one) is wrong exactly when somebody is going back to fill in note two.

            `explainWhenUnavailable` USED TO BE `index === 0` — the first row of each note group
            carried the "this browser cannot dictate" sentence and the rest stayed quiet. That was
            right while these were the only microphones on the page. Since the sweep of 2026-08-28
            the process name and every step name have one too, so "once per note group" became once
            per STEP, and the form now says it exactly once at the top instead
            (`DictationUnavailableNotice`). The sentence is not gone; it moved.

            The joiner is `appendDictatedPhrase`, shared with both dictated boxes rather than written
            out here for a fourth time — same rule, one place, see `richtext/dictatedValue.ts`.
          */}
          <OnDeviceDictationButton
            fieldLabel={rows.length > 1 ? `${label}, note ${index + 1}` : label}
            explainWhenUnavailable={false}
            onCommit={(phrase) => {
              emit(rows.map((n, i) => (i === index ? appendDictatedPhrase(note, phrase) : n)));
            }}
          />
        </div>
      ))}
      <button type="button" className="field-button-secondary h-9 min-h-0 justify-self-start px-3 text-xs" onClick={() => emit([...rows, ""])}>
        <Plus className="h-3.5 w-3.5" aria-hidden />
        Add note
      </button>
    </div>
  );
}

// ---------------------------------------------------------------------------
// The process form (create + edit) — Android ProcessForm parity.
// ---------------------------------------------------------------------------

/**
 * ── DICTATION ON THIS FORM: WHICH BOXES HAVE A MICROPHONE, AND WHY THE REST DO NOT ──────────────
 *
 * The owner's instruction (2026-08-28): "All the record pages should have dictation options
 * available, wherever applicable so as to reduce the friction as much as possible." So the default
 * flipped — a free-text box HAS a microphone unless there is a reason it must not — and the reasons
 * are written down here so a later reader can tell a decision from an oversight.
 *
 * DICTATED: Name of the process · What happens in this process (the `RichTextField`, whose editor
 * carries the microphone at the caret) · Name of the step, per step · Additional context for this
 * step, one microphone per note row.
 *
 * NOT DICTATED, and each is a rule rather than a preference:
 *
 *  - **Workshop, Artisan, Product, Status** — record pickers and a closed vocabulary behind themed
 *    dropdowns. There is nothing free to speak, and the artisan picker in particular decides which
 *    interview a submission folds into: it is chosen from a list, never typed.
 *  - **"Pre-processes available" and "Record additional information"** — checkboxes. A recogniser
 *    answers a yes/no question with a sentence.
 *  - **Pre-process media, per-step media** — file pickers.
 *
 * ONE SENTENCE FOR THE WHOLE FORM. Every control above passes `explainWhenUnavailable={false}` and
 * `DictationUnavailableNotice` sits once under the carry-forward banner. A form with six steps holds
 * a dozen microphones, and repeating the Firefox explanation beside each of them is how a true
 * sentence becomes wallpaper.
 */
export function ProcessForm({
  initial,
  footerFields,
  onDone,
  onCancel,
  onDiscardAndLeave,
  onCreated,
  onQueued
}: {
  initial?: ProcessRecord;
  onDone: () => void;
  onCancel: () => void;
  /**
   * Hand the saved record back, so a picker that mounted this form in a dialog can SELECT it.
   *
   * ── THE DEFECT THIS EXISTS FOR ────────────────────────────────────────────────────────────────
   * `InlineRecordDialog` mounts `ArtisanForm`, `ProductForm` and `ToolForm` with `onCreated` and
   * this one with `onDone={onClose}`, because this form had no `onCreated` to give it. So the
   * button that reads "Create “Tie and dye” as a new process" opened a form, saved a process, and
   * closed on a picker with nothing selected — no link, no hydration, and the designer left hunting
   * through a list for the record they had just made, seconds after being told it would be created
   * "as a new process". The three sibling models did the whole job; this one did half of it.
   *
   * SAME CONTRACT AS THE SIBLINGS: when it is set the form neither routes nor reports through
   * `onDone`, and the caller owns what happens next. `onDone` stays for the page, which mounts this
   * form directly and wants exactly the old behaviour.
   */
  onCreated?: (record: ProcessRecord) => void;
  /**
   * The save was banked in the offline outbox instead of sent — see `InlineRecordHostProps.onQueued`.
   *
   * This form was the only one of the four that already CLOSED on a queued save (through `onDone`),
   * and closing was never the missing half: the dialog shut, the picker was left empty, and nothing
   * anywhere said why. `OutboxBanner`, which is what the page host relies on, is mounted outside
   * `FieldDialog`'s portal and is unreachable from a modal, so a designer offline saw a dialog
   * vanish and a row that was still blank — the same reading as a save that failed. When this is
   * supplied it replaces `onDone` on that branch, so the host can close AND say so in one act.
   */
  onQueued?: () => void;
} & InlineRecordSurfaceProps) {
  const { user } = useAuth();
  const isEdit = Boolean(initial);
  /*
    THERE IS NO "am I hosted?" FLAG HERE ANY MORE, and the absence is deliberate.
    A short-lived `const hosted = Boolean(onCreated)` gated the heading this form used to draw. The
    heading is gone on every host (see the block where it stood), so the flag had nothing left to
    read it. If a fifth reason to know the host ever appears, it belongs on
    `inlineRecordHost.ts` with the other host-wide members and not as a fifth local spelling —
    that file's header is about exactly this.
  */
  const canPickStatus = hasRank(user, "PROFESSOR");
  // The workshop this process was documented at: shared picker, shared most-recent defaulting, and
  // the late-submission gate (see components/forms/WorkshopSelect).
  const workshop = useWorkshopSelection({ initialWorkshopId: initial?.workshopId, isEdit, resetKey: initial?.id ?? null });
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

  const [name, setName] = useState(initial?.name ?? "");
  const [artisanId, setArtisanId] = useState(initial?.product?.artisanId ?? "");
  const [productId, setProductId] = useState(initial?.productId ?? "");
  const [preProcessAvailable, setPreProcessAvailable] = useState(initial?.preProcessAvailable ?? false);
  /**
   * WHAT THE WHOLE PROCESS IS, IN THE RESEARCHER'S OWN WORDS — and until this box existed there was
   * nowhere to type it.
   *
   * `Process.notes` had no input on either surface. Both forms held it as state, both sent it, and
   * neither ever let anybody change it, so it stayed null on every process this app created and
   * could only be written through the API. That is not a cosmetic omission, because it is the one
   * column the report leans on hardest: `REFERENCE_HYDRATION["processStep.processRef"]` maps it to
   * `description` — the box the registry labels "What happens", a TABLE COLUMN of the
   * traditional-process table — and `traditionalProcess.processRef` maps it to
   * `documentedProcessNotes` above the same table. `docs/REPORT-DATA-WIRING.md` calls that mapping
   * "the copy that turns a one-word row into a paragraph"; with no way to type the paragraph, every
   * one of those rows printed the one word.
   *
   * RICH, like the four narrative boxes on `ProductForm` and the two on `ToolForm`, and for the same
   * reason they are: it is a paragraph a researcher writes about a sequence, not a label. The column
   * is a plain `String?` that stores the document as JSON — see the banner in
   * `backend/app/services/rich_text.py`, and `records.prose_contains`, which exists because the
   * search had to be taught the same thing.
   */
  const [notes, setNotes] = useState(initial?.notes ?? "");
  // Status policy: professor+ defaults to APPROVED on create and may pick any status; below
  // professor the status is forced to PENDING (locked chip below) and the server enforces it too.
  const [status, setStatus] = useState<string>(initial?.status ?? (canPickStatus ? "APPROVED" : "PENDING"));
  const [steps, setSteps] = useState<StepUi[]>(() =>
    (initial?.steps ?? []).map((step) => ({
      key: makeKey(),
      serverId: step.id,
      name: step.name,
      stepType: step.stepType,
      recordAdditional: Boolean(step.notes && step.notes.trim()),
      notes: step.notes ?? "",
      files: [],
      existingMedia: step.media ?? []
    }))
  );
  const [preFiles, setPreFiles] = useState<File[]>([]);
  const [existingPreMedia, setExistingPreMedia] = useState<MediaFile[]>(initial?.media ?? []);

  const [artisans, setArtisans] = useState<Artisan[]>([]);
  // "Can I see this artisan?" and "is there any signal?" are different answers, and the carry-
  // forward prefill treats them differently — see useCarryContext.
  const [artisanListState, setArtisanListState] = useState<CarryScopeState>("pending");
  const [artisanProducts, setArtisanProducts] = useState<ProductDocumentation[]>([]);
  /**
   * WHAT THE TWO PICKERS ARE NOT SHOWING — see `components/data/cappedList`.
   *
   * Both loads below ask for the ceiling `normalize_pagination` clamps to and both used to keep only
   * `.items`. The artisan dropdown is the exposed one: it is NOT scoped by craft the way the product
   * and tool forms' are, so it really is the newest hundred rows of a 749-row table (counted
   * 2026-08-15), with no search box and no page two. The products load is per artisan and is
   * therefore whole in practice — 878 products across 749 artisans — but it is reported the same
   * way, because "in practice" is not a property of the code.
   */
  const [artisanCut, setArtisanCut] = useState<ListCut | null>(null);
  const [productCut, setProductCut] = useState<ListCut | null>(null);
  const [productsLoading, setProductsLoading] = useState(false);
  const [productLoadError, setProductLoadError] = useState<string | null>(null);

  const [error, setError] = useState<string | null>(null);
  const [nameError, setNameError] = useState<string | null>(null);
  const [artisanError, setArtisanError] = useState<string | null>(null);
  const [productError, setProductError] = useState<string | null>(null);
  const [stepsError, setStepsError] = useState<string | null>(null);
  const [preMediaError, setPreMediaError] = useState<string | null>(null);
  /**
   * IDS FOR THE SIX REFUSALS ABOVE, AND FOR THE BANNER THAT SUMMARISES THEM.
   *
   * Every refusal this form makes was a red paragraph and nothing else — no `role`, no id, bound to
   * no control — so a researcher using a screen reader was blocked from saving and told nothing at
   * all about why. The ids below are what let the controls that CAN carry a description point at
   * theirs; `role="alert"` carries the rest. Which refusal gets which is decided in `submit()`
   * under ALERT OR DESCRIPTION, and is not uniform — read that before adding or removing a role.
   *
   * `useId` rather than literals because this form is also embedded inside a design-workshop stage
   * (see `InlineRecordSurfaceProps`), and two mounted copies must not mint the same id. The literal
   * `process-name` / `step-name-*` ids below are the focus ladder's and are older than this.
   */
  const formId = useId();
  const errorId = `${formId}-error`;
  const nameErrorId = `${formId}-name-error`;
  const artisanErrorId = `${formId}-artisan-error`;
  const productErrorId = `${formId}-product-error`;
  const preMediaErrorId = `${formId}-pre-media-error`;
  const stepsErrorId = `${formId}-steps-error`;
  const [saving, setSaving] = useState(false);
  /**
   * THE WRITE HAS LANDED — set the instant `saveOrQueue` returns, and never unset.
   *
   * "Unsaved work" and "a save that partly failed" are different states, and this form had only one
   * flag for both. The media uploads run AFTER the record is written (they need its id, and the
   * step ids the server minted), so a failed upload leaves the process saved, this form still on
   * screen, and `signature !== initialSignature` still true — because the signature describes what
   * was typed, not what was stored. `dirty` therefore stayed true over a record that was already in
   * the database, and the leave guard offered the header arrow a "Save" button that re-runs
   * `submit()`: a second POST (a duplicate process with duplicate step rows) when creating, and on
   * an edit a re-PATCH that re-uploads every attachment, duplicating the ones that had just
   * succeeded. Offering to re-apply a partially-applied write is worse than not guarding at all —
   * the guard exists to protect work, and that button destroys some.
   *
   * It latches for the life of the form deliberately. Typing something else after a partial failure
   * does not make a re-submit safe; the record is saved, and the error banner sends the researcher
   * to re-open it, which is the one route that PATCHes the row that actually exists.
   */
  const [committed, setCommitted] = useState(false);
  const [uploadNote, setUploadNote] = useState<string | null>(null);
  const [addMenu, setAddMenu] = useState(false);
  const [guardOpen, setGuardOpen] = useState(false);
  /**
   * WHICH EXIT IS WAITING ON THAT PROMPT — this form's own Cancel button, or the back arrow in the
   * page header. Both raise the same dialog, and until this flag existed both got the same answer.
   *
   * ── THE DEFECT ────────────────────────────────────────────────────────────────────────────
   * "Discard" called `onCancel()`, and in the design-workshop stage embed `onCancel` REMOUNTS THIS
   * FORM IN PLACE — that host is not a dialog and there is nowhere to go, so remounting is the only
   * thing that could clear boxes living in React state. A designer therefore pressed Back, was
   * asked, answered Discard, lost the name, the artisan, the product, every step and every attached
   * file — AND STAYED ON THE PAGE, needing a second press of Back to do the thing they had asked
   * for. In a dialog the identical wiring reads correctly, because the dialog visibly closes.
   *
   * ── WHY THE FLAG MARKS THE CANCEL BUTTON AND NOT THE ARROW ────────────────────────────────
   * The arrow's route in is `useLeaveGuard`, registered once for the life of the mount with a bare
   * callback — there is no per-press hook to set a flag from, and that registration is pinned by
   * `e2e/discarded-work-unit.spec.ts` because it is the line this form spent a release missing.
   * `requestCancel` is this component's own call site, so it is the one that can say who it is. It
   * is cleared on every way out of the prompt, so "set" only ever describes the prompt on screen.
   */
  const [promptFromCancel, setPromptFromCancel] = useState(false);

  const statusChoices = initial?.status && !STATUS_OPTIONS.includes(String(initial.status)) ? [String(initial.status), ...STATUS_OPTIONS] : STATUS_OPTIONS;

  useEffect(() => {
    listResource<Artisan>("/artisans", { pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        setArtisans(result.items);
        setArtisanCut(listCut(result, "artisans"));
        setArtisanListState("loaded");
      })
      .catch(() => setArtisanListState("unavailable"));
  }, []);

  /**
   * THE PROCESS'S OWN ARTISAN IS ALWAYS AN OPTION, whichever page they are on.
   *
   * Without this, editing a process whose artisan is outside the newest hundred drew a REQUIRED
   * dropdown with nothing selected in it — the stored link was intact and would have been saved
   * untouched, but the form looked like it had lost the artisan, which invites the one action that
   * really does change the record: picking somebody else. See `useRecordOffPage`.
   */
  const offPageArtisan = useRecordOffPage<Artisan>("/artisans", artisanId, artisans);
  const artisanOptions = useMemo(
    () => (offPageArtisan ? mergeById(artisans, [offPageArtisan]) : artisans),
    [artisans, offPageArtisan]
  );

  /**
   * The carried product, held until the artisan's product list arrives.
   *
   * A process is documented against a product, so "I just recorded a product, now let me record how
   * it is made" is the most common journey into this form — and the one the old artisan-only context
   * left half-finished. The product cannot be applied on the spot the way the artisan can: the
   * dropdown it belongs in is fetched per artisan, and that fetch only starts once the prefill has
   * supplied the artisan. So it waits here for one round trip.
   */
  const carriedProductRef = useRef<string | null>(null);

  // Offer the sitting this researcher was last working in: the artisan, the workshop, and the
  // product they documented last, which is what this record is about.
  const carry = useCarryContext({
    enabled: !isEdit,
    scopes: [carryScope("artisan", artisanListState, artisanOptions)],
    // No craft or tool field here, so neither is filled in nor claimed; both stay in the bag.
    applies: ["artisan", "product", "workshop"],
    onApply: (context) => {
      if (context.artisanId) setArtisanId(context.artisanId);
      if (context.productId) carriedProductRef.current = context.productId;
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });
  const pruneCarried = carry.prune;
  /** "Change": drop the carried artisan and product in one action. */
  function clearCarriedContext() {
    carry.change();
    carriedProductRef.current = null;
    setArtisanId("");
    setProductId("");
  }

  // Products belong to an artisan, so the product list is scoped to the chosen artisan. The server
  // OR-matches FK-linked products plus products carrying the artisan's typed name (no FK), so the
  // dropdown never hides a product that genuinely belongs to the artisan.
  useEffect(() => {
    let cancelled = false;
    if (!artisanId) {
      setArtisanProducts([]);
      setProductsLoading(false);
      setProductLoadError(null);
      return;
    }
    setProductsLoading(true);
    setProductLoadError(null);
    const artisanName = artisanOptions.find((artisan) => artisan.id === artisanId)?.name?.trim();
    listResource<ProductDocumentation>("/products", { artisanId, artisanName, pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        if (cancelled) return;
        setArtisanProducts(result.items);
        setProductCut(listCut(result, "products of this artisan"));
        setProductsLoading(false);
        // This list is both the dropdown's options and the only proof the carried product is still
        // this artisan's and still reachable, so the deferred half of the prefill resolves here.
        const carried = carriedProductRef.current;
        if (carried) {
          carriedProductRef.current = null;
          if (result.items.some((product) => product.id === carried)) {
            setProductId(carried);
            return;
          }
          // Deleted, or it turned out not to belong to this artisan. Either way it is dropped from
          // the bag and from the banner rather than offered as a link nobody can follow.
          pruneCarried("product");
        }
        // Keep a valid selection: clear product if it is no longer offered for this artisan.
        setProductId((current) => (current && result.items.every((product) => product.id !== current) ? "" : current));
      })
      .catch((err) => {
        if (cancelled) return;
        setArtisanProducts([]);
        setProductsLoading(false);
        setProductLoadError(
          `Couldn't load this artisan's products: ${err instanceof Error ? err.message : "network error"}. Tap the artisan again to retry.`
        );
      });
    return () => {
      cancelled = true;
    };
  }, [artisanId, artisanOptions, pruneCarried]);

  // Unsaved-changes guard: signature of every user-editable field + pending file counts. The
  // workshop only counts once the USER has picked one — the create form's automatic most-recent
  // default lands asynchronously and must not make an untouched form look dirty.
  // A carried artisan is on the same footing as the automatic workshop default: the researcher did
  // not type it, so it must not make an untouched form prompt "discard your changes?". Picking one
  // themselves retires the offer (carry.applied goes null) and the value starts counting.
  const carriedArtisanId = carry.applied?.context.artisanId ?? null;
  const carriedProductId = carry.applied?.context.productId ?? null;
  const signature = JSON.stringify({
    name,
    artisanId: artisanId && artisanId === carriedArtisanId ? "" : artisanId,
    productId: productId && productId === carriedProductId ? "" : productId,
    workshopId: workshop.touched ? workshop.workshopId : "",
    // The same `touched` gate, for the same reason: this form's guard is a DIFF of state, so a value
    // the app prefilled would otherwise read as work the researcher had done, and a blank new form
    // announcing unsaved changes before anybody types is what teaches people to click through the
    // guard. `touched` is false for the prefill and true only once a person has picked.
    designWorkshopId: designWorkshop.touched ? designWorkshop.workshopId : "",
    status,
    // IN THE SIGNATURE, because this form's unsaved-changes guard is a diff of state rather than an
    // `onDirty` event — so a box left out of it is a box a researcher can fill in, navigate away
    // from, and be told there was nothing to lose. `RichTextField` reports its value through
    // `onValueChange`, which lands in `notes`, which lands here.
    notes,
    preProcessAvailable,
    pre: preFiles.length,
    steps: steps.map((step) => ({
      id: step.serverId ?? null,
      name: step.name,
      type: step.stepType,
      notes: step.recordAdditional ? step.notes : "",
      files: step.files.length
    }))
  });
  // Captured once on first render (state initializer), so it is safe to read while rendering.
  const [initialSignature] = useState(signature);
  // `committed` is NOT redundant with `saving`: `saving` goes false in the `finally` of every save,
  // including the partial-media-failure return that deliberately leaves this form on screen with the
  // record already written. That window is exactly where the header arrow used to reach a dialog
  // offering to save it again. See `hasUnsavedWork`.
  //
  // THERE IS DELIBERATELY NO "EMBEDDED, SO DO NOT PROMPT" FLAG, though one looks obviously right: a
  // design-workshop stage that embeds this form has no unsaved-changes prompt of its own, because
  // its draft is durable. That durability belongs to the STAGE's fields; this form writes to no
  // store at all, so suppressing the question here would discard real work in silence. The full
  // argument is in `inlineRecordHost.ts`'s header. The guard below, the Cancel button and the
  // `beforeunload` effect all read this one value, because three opinions about when to prompt is
  // how they come to disagree.
  const dirty = hasUnsavedWork({ saving, committed, signature, initialSignature });

  /*
    HANDS `dirty` TO THE ROUND BACK ARROW IN THE PAGE HEADER, which is the only back control this
    page has and which, until this line existed, was the only control on it that discarded work
    without asking.

    The dialog below and its `requestCancel` were wired to the form's own Cancel button alone. So the
    footer asked and the header did not: a researcher who had named a process, linked the artisan and
    the product, added six steps with notes and attached media to each of them lost all of it to one
    tap on the arrow — on a surface where /artisans/new, /products/new, /tools/new, /crafts,
    /workshops and the designer profile have all taught them that arrow is safe, because those five
    forms carry this same call.

    `beforeunload` below is NOT a substitute and never was: it covers closing the tab and reloading,
    and an in-app client-side navigation fires neither. If you are tempted to delete one of the two,
    they guard different exits.
  */
  useLeaveGuard(dirty, () => setGuardOpen(true));

  useEffect(() => {
    if (!dirty) return;
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);

  function updateStep(key: string, patch: Partial<StepUi>) {
    setSteps((current) => current.map((step) => (step.key === key ? { ...step, ...patch } : step)));
  }

  function addStep(stepType: string) {
    setSteps((current) => [
      ...current,
      { key: makeKey(), name: "", stepType, recordAdditional: false, notes: "", files: [], existingMedia: [] }
    ]);
    setAddMenu(false);
  }

  function requestCancel() {
    // `promptFromCancel`: this is the form's own Cancel, so "Discard" must NOT complete a
    // navigation nobody started — see the flag's declaration.
    if (dirty) {
      setPromptFromCancel(true);
      setGuardOpen(true);
    } else onCancel();
  }

  /**
   * Finish the exit the HOST'S OWN back control began, after "Discard" has answered for the typing.
   *
   * `useLeaveGuard` does not delay a navigation, it REFUSES one: the interceptor returns true, the
   * back control abandons what it was doing, and this form is handed the question instead. Nothing
   * is left in flight to resume — only the host knows where the arrow was going, and only the host
   * can start it again. `onDiscardAndLeave` is how it says so.
   *
   * Falls back to `onCancel` when no host supplies it, which is right for both other hosts: on
   * /processes that callback really is the navigation, and in `InlineRecordDialog` closing the
   * dialog is the whole of leaving it. Only a host that can be left without being closed — the
   * stage embed — has anything to add. See `InlineRecordHostProps.onDiscardAndLeave`.
   */
  function leaveAfterDiscard() {
    if (onDiscardAndLeave) onDiscardAndLeave();
    else onCancel();
  }

  async function submit(): Promise<void> {
    // ONE WRITE PER MOUNT. `committed` is true only once `saveOrQueue` has returned, so this refuses
    // exactly the re-submit described on its declaration — the footer button and the leave dialog's
    // "Save" both land here, and after a partial media failure both were live over a record that
    // already exists. There is no id to PATCH on the create path, so a second run would not "retry"
    // anything: it would file a second process. Re-opening the saved record is the retry route, and
    // the error banner below says so.
    if (committed) return;
    setError(null);
    setNameError(null);
    setArtisanError(null);
    setProductError(null);
    setStepsError(null);
    setPreMediaError(null);

    let blocked = false;
    /**
     * ONLY TWO OF THE SIX REFUSALS CAN AIM FOCUS, AND THAT IS NOT AN OVERSIGHT TO SWEEP UP.
     *
     * The process name and a step name are plain inputs with real ids, so `getElementById` reaches
     * them. The artisan and product pickers are `SearchableSelect` triggers: no `id` and no `ref` is
     * plumbed through that chain, so there is no element for this line to find. Minting an id here
     * that resolves to nothing would move focus nowhere while reading, in the source, like it moved
     * somewhere — the same shape of defect as the refusal paragraphs that used to be bound to no
     * control at all. Both of those refusals reach a screen reader through `role="alert"` and the
     * picker's `describedBy` instead; giving them focus needs an id on the trigger first.
     *
     * The media and steps refusals have no control to focus by construction.
     *
     * ── ALERT OR DESCRIPTION: WHY THE SIX REFUSALS SPLIT FOUR/TWO ────────────────────────────
     *
     * Refusing an empty form mounts the summary banner and all six paragraphs in ONE commit. An
     * assertive live region interrupts, so seven of them firing together is not seven
     * announcements — it is a queue in which the earlier ones can be cut off by the later, and the
     * banner, first in DOM order, is the likeliest casualty. So `role="alert"` is spent only where
     * nothing else can carry the sentence:
     *
     *   - the summary banner, which is the only thing said for a refusal with no box of its own;
     *   - the media and steps refusals, which have no control at all to describe;
     *   - the artisan and product refusals, whose control EXISTS but which the ladder above cannot
     *     reach — a description nobody focuses is a description nobody hears, and the banner's
     *     "Please fill the required fields highlighted above." does not name which picker.
     *
     * The two that DO get focus — process name and step name — carry the id and `aria-describedby`
     * and no role: arriving on the control reads the paragraph, and an alert as well would speak it
     * twice while stepping on the four that only get one chance.
     */
    let focusId: string | null = null;
    if (!name.trim()) {
      setNameError("This field cannot be empty");
      blocked = true;
      focusId = focusId ?? "process-name";
    }
    if (!artisanId) {
      setArtisanError("Please select an artisan");
      blocked = true;
    }
    if (!productId) {
      setProductError("Please select a product");
      blocked = true;
    }
    if (preProcessAvailable && preFiles.length === 0 && existingPreMedia.length === 0) {
      setPreMediaError("Attach the pre-process media or uncheck the box");
      blocked = true;
    }
    if (steps.length === 0) {
      setStepsError("Add at least one step");
      blocked = true;
    }
    setSteps((current) =>
      current.map((step) => ({ ...step, nameError: step.name.trim() ? null : "This field cannot be empty" }))
    );
    steps.forEach((step) => {
      if (!step.name.trim()) {
        blocked = true;
        focusId = focusId ?? `step-name-${step.key}`;
      }
    });
    if (blocked) {
      setError("Please fill the required fields highlighted above.");
      setGuardOpen(false);
      if (focusId) document.getElementById(focusId)?.focus();
      return;
    }

    // A workshop that has already ended makes this a late submission needing admin approval — say so
    // before anything is written. Resolves true immediately when there is nothing to warn about.
    if (!(await workshop.confirmSubmission())) {
      setGuardOpen(false);
      return;
    }

    setSaving(true);
    try {
      const trimmedName = name.trim();
      const payload = {
        name: trimmedName,
        productId,
        workshopId: workshop.workshopId || null,
        designWorkshopId: designWorkshop.workshopId || null,
        preProcessAvailable,
        notes: notes.trim() || null,
        // Unauthorized status changes are silently dropped server-side.
        status,
        steps: steps.map((step, index) => ({
          id: step.serverId,
          name: step.name.trim(),
          stepType: step.stepType,
          sortOrder: index + 1,
          notes: step.recordAdditional ? step.notes.trim() || null : null
        })),
        ...(isEdit ? {} : { recordedAt: new Date().toISOString() })
      };
      // Offline this queues to the outbox. The step captures are the awkward part: they link to
      // `processstep` rows the server has not minted yet, so each step's batch carries its index and
      // the replay resolves the real id from the create response's `steps[]`.
      const outcome = await saveOrQueue<ProcessRecord>({
        label: `Process · ${trimmedName || "Untitled"}`,
        endpoint: isEdit ? `/processes/${initial!.id}` : "/processes",
        method: isEdit ? "PATCH" : "POST",
        body: payload,
        // Queued offline, the process name the researcher typed is the best this can know; the
        // online path below re-derives the names from what the server actually stored.
        media: [
          {
            files: preProcessAvailable
              ? preFiles.map((file, index) =>
                  renameMediaFile(file, {
                    recordType: "Process",
                    recordName: trimmedName,
                    descriptor: describePreProcess(file, existingPreMedia.length + index + 1)
                  })
                )
              : [],
            linkedRecordType: "process",
            caption: `Pre-process media for ${trimmedName}`
          },
          ...steps.map((step, index) => ({
            files: step.files.map((file, fileIndex) =>
              renameMediaFile(file, {
                recordType: "Process",
                recordName: trimmedName,
                descriptor: describeProcessStep({
                  stepNumber: index + 1,
                  stepName: step.name.trim(),
                  subject: file,
                  index: step.existingMedia.length + fileIndex + 1
                })
              })
            ),
            linkedRecordType: "processstep",
            caption: `Process step ${step.name}`,
            stepIndex: index
          }))
        ]
      });
      // THE WRITE IS APPLIED FROM HERE ON — online it is in the database, offline it is an outbox
      // entry that will replay. Either way this form must never send it again; everything below
      // (media, names, the carry bag) is follow-up work on a record that already exists.
      setCommitted(true);
      // Bank the sitting the moment the record is accepted (queued counts — offline is the normal
      // case), so the next form opened from the dashboard already knows where the researcher is.
      const savedArtisan = artisanOptions.find((a) => a.id === artisanId);
      const savedProduct = artisanProducts.find((product) => product.id === productId);
      carry.remember({
        artisanId,
        artisanName: savedArtisan?.name ?? null,
        place: savedArtisan?.place ?? null,
        craftId: savedArtisan?.craftId ?? null,
        // The product this process documents stays in the bag: a second process for the same
        // product is the next thing a researcher does, and it should not need finding again.
        productId,
        productName: savedProduct?.productName ?? null,
        workshopId: workshop.workshopId,
        workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
      });
      if (outcome.queued) {
        // OutboxBanner at the top of the page names the entry and says where it lives.
        //
        // `onDone` AND NOT `onCreated`, EVEN IN A DIALOG, and the asymmetry is honest rather than
        // lazy: a queued write has no server id yet, so there is no record for a picker to link to.
        // Reporting a create here would have the picker arm a hydration for an id that does not
        // exist, search for it, fail, and tell the designer their record could not be described —
        // three steps to arrive at the same place the outbox banner already says plainly.
        setGuardOpen(false);
        setSaving(false);
        // `onQueued` when the host offered one — it closes the dialog exactly as `onDone` did AND
        // gets to put an honest sentence where the designer is looking. See the prop's own note.
        if (onQueued) onQueued();
        else onDone();
        return;
      }
      const saved = outcome.saved;

      // Upload media after the record exists: pre-process clips link to the process itself, each
      // step's files link to that step (linkedRecordType "processstep" + the step id). Names come
      // from the SAVED process and step names — the API title-cases both, and the browser's name for
      // a file has to read the same as the one the data browser derives from the row.
      const failures: string[] = [];
      const totalUploads = (preProcessAvailable ? preFiles.length : 0) + steps.reduce((sum, step) => sum + step.files.length, 0);
      let uploadCount = 0;
      const note = () => {
        uploadCount += 1;
        if (totalUploads) setUploadNote(`Uploading media ${uploadCount} of ${totalUploads}…`);
      };

      if (preProcessAvailable) {
        for (let index = 0; index < preFiles.length; index += 1) {
          note();
          try {
            await uploadMediaFile({
              file: renameMediaFile(preFiles[index], {
                recordType: "Process",
                recordName: saved.name || trimmedName,
                descriptor: describePreProcess(preFiles[index], existingPreMedia.length + index + 1)
              }),
              linkedRecordType: "process",
              linkedRecordId: saved.id,
              caption: `Pre-process media for ${trimmedName}`
            });
          } catch {
            failures.push(preFiles[index].name);
          }
        }
      }
      const savedSteps = saved.steps ?? [];
      for (let index = 0; index < savedSteps.length; index += 1) {
        const serverStep = savedSteps[index];
        const local = steps[index];
        if (!local) continue;
        for (let fileIndex = 0; fileIndex < local.files.length; fileIndex += 1) {
          note();
          try {
            await uploadMediaFile({
              file: renameMediaFile(local.files[fileIndex], {
                recordType: "Process",
                recordName: saved.name || trimmedName,
                descriptor: describeProcessStep({
                  stepNumber: serverStep.sortOrder ?? index + 1,
                  stepName: serverStep.name,
                  subject: local.files[fileIndex],
                  // Continues past what is already on the step, so a second save cannot re-issue a
                  // name the first one already wrote in the same minute.
                  index: local.existingMedia.length + fileIndex + 1
                })
              }),
              linkedRecordType: "processstep",
              linkedRecordId: serverStep.id,
              caption: `Process step ${serverStep.name}`
            });
          } catch {
            failures.push(local.files[fileIndex].name);
          }
        }
      }
      setUploadNote(null);
      if (failures.length) {
        setError(
          `The process was saved, but ${failures.length} media file(s) failed to upload: ${failures.join(", ")}. Re-open the process from the list to retry those files.`
        );
        setGuardOpen(false);
        /*
          ── THE RECORD IS REPORTED FIRST, THE UPLOAD FAILURE SECOND ────────────────────────────
          THIS USED TO BE THE OPPOSITE DECISION, and the note that stood here is worth reading
          before this one is changed back: it argued that `onCreated` closes the dialog, so
          reporting the record would close over the one message saying which files were lost, and
          that "the picker's list will hold it the next time it is opened".

          What that weighed wrongly is which of the two losses a designer can recover from. A
          missing attachment is NAMED, on screen, with the instruction to re-open the process and
          retry it — and the record is in the list, exactly as the old note said. An unlinked stage
          row is neither named nor visible: the picker sits empty over a process that exists, and
          the stage 422s on submit hours later naming a required reference the designer remembers
          creating. That is the state this whole inline-create lane was built to end, and it was
          being produced by the branch that reports a partial success. The three sibling forms had
          the same shape and all four now behave the same way.

          THE ERROR IS SET BEFORE THE HANDOFF AND NOT INSTEAD OF IT — `setError` above runs either
          way, so on this form's own page (where it is mounted directly by `/processes` and nothing
          unmounts) the banner reads exactly as it always did. In the dialog the host closes over
          it; that is the trade, and it is the same one the queued branch above already makes.
        */
        if (onCreated) onCreated(saved);
        return;
      }
      setGuardOpen(false);
      if (onCreated) {
        // Hosted in a dialog: the caller links and hydrates the row. Reporting through `onDone`
        // instead is what left a just-created process unselected in the picker that made it.
        onCreated(saved);
        return;
      }
      onDone();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save process");
      setGuardOpen(false);
    } finally {
      setSaving(false);
      setUploadNote(null);
    }
  }

  const productPlaceholder = !artisanId
    ? "Select an artisan first"
    : productsLoading
      ? "Loading products…"
      : artisanProducts.length === 0
        ? "No products for this artisan"
        : "Select the product this process makes";

  return (
    <form
      className="panel grid gap-4 p-5"
      onKeyDown={handleFormEnter}
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      <div>
        {/*
          THIS FORM DOES NOT TITLE THE SCREEN, ON ANY OF ITS THREE HOSTS, and there is no host it
          still would.

          It used to draw an `<h2>` here — the only one of the four record forms with a heading of
          its own — written when /processes was the only place this component was mounted. All
          three hosts title the surface themselves and always did: `/processes` renders a
          `PageHeader` (an `<h1>`) reading "Document process" / "Edit process", the SAME TWO
          STRINGS this heading painted directly beneath it; `InlineRecordDialog` gives `FieldDialog`
          a `title` of "New process" / "Edit process"; and `StageRecordEmbed` draws the form inside
          a stage entity panel whose `EntityForm` renders `entity.title` as its own `<h2>` —
          "Process overview" for stage 5's `traditionalProcess`.

          The stage was the loud case: two sibling `h2` elements for one thing, at the same level
          and in the same class, and a screen reader's heading list is one of the two ways a
          22-stage form is navigated at all, so a duplicate rung describes a structure the page does
          not have. Suppressing it only when hosted left the page's own duplicate standing — a
          quieter defect, because `h1` then `h2` is at least a real outline, but the second rung
          still says nothing the first did not, in the same words.

          THE PARAGRAPH STAYS. It says what a process record IS and that several people may document
          the same product, which no host repeats and which is as true in a stage as on the page.
          Only the heading was a claim about the screen.
        */}
        <p className="mt-1 text-xs text-ink-500">
          Capture how a product is made, step by step. Each process is tied to a product; multiple people can document the same
          product&apos;s processes.
        </p>
      </div>
      {initial ? <FieldProvenance extraMetadata={initial.extraMetadata} /> : null}
      {/* `role="alert"`: this banner is painted in response to a save the researcher just asked for
          — a refusal, or a failure the server answered with — so it has to be spoken when it
          appears rather than waiting to be stumbled across on the next pass through the form. */}
      {error ? (
        <div id={errorId} role="alert" className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">
          {error}
        </div>
      ) : null}
      <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />

      {/*
        THE ONE PLACE THIS FORM EXPLAINS A MISSING MICROPHONE — see `DictationUnavailableNotice`.
        Every dictated control below passes `explainWhenUnavailable={false}`, including the per-note
        buttons inside `MultiNoteInput`, which used to elect their first row to carry the sentence.
        That was the right answer while the notes were the only microphones on the page; with a
        process name, a step name per step and a note per row it would be one paragraph per step,
        which is the noise the button's own prop documentation warns about.
      */}
      <DictationUnavailableNotice />

      {/* Android parity (ProcessForm): the workshop opens the form, because it is the context
          every other answer belongs to — not merely the first dropdown. */}
      <WorkshopSelect state={workshop} saving={saving} />
      {/*
        The design & prototype workshop, directly under the ordinary one — see the hook above.
        Its default is the server's answer to "most recently allocated" rather than this form's
        guess, so all seven forms and both clients agree; `lib/designWorkshopDefault.ts`.
      */}
      <DesignWorkshopSelect
        state={designWorkshop}
        initial={initial ? (initial.designWorkshopId ?? null) : undefined}
        saving={saving}
      />

      <div>
        {/*
          `name` is one of the API's title-cased columns, so the box says what will actually be
          stored (Android parity — see components/forms/TitleCasedInput). `titleCased` mounts that
          exact component inside the dictated box: the sweep that added the microphone was not
          allowed to cost this field its "Will be saved as …" sentence.

          NOT WRAPPED IN `Field` ANY MORE, and that is required rather than incidental: `Field` is a
          `<label>`, and a `<label>` forwards a stray click to the first labelable control inside it
          — so clicking "Dictate" would also focus the box and, on a phone, throw the keyboard up
          over the interim readout the researcher is watching. The control writes its own
          `<label htmlFor>` instead, and `id="process-name"` is passed explicitly because `submit()`
          reaches this box by `document.getElementById` when it refuses an empty name.
        */}
        <DictatedTextInput
          id="process-name"
          label="Name of the process"
          required
          titleCased
          explainWhenUnavailable={false}
          value={name}
          aria-invalid={!!nameError}
          /* `TitleCasedInput` MERGES an incoming `aria-describedby` with its own "Will be saved
             as …" hint rather than replacing it, so the refusal and the hint are both announced.
             Passing it straight through to a plain `<input>` would have silenced the hint. */
          aria-describedby={nameError ? nameErrorId : undefined}
          onChange={(next) => setName(next)}
        />
        {/* NO `role="alert"` HERE, AND THAT IS THE POINT — see ALERT OR DESCRIPTION on `submit()`.
            `submit()` moves focus to `process-name`, and arriving on a control reads its
            `aria-describedby`, which is this paragraph. An alert as well would say it twice and
            would interrupt the five refusals that have no other way to be heard. */}
        {nameError ? (
          <p id={nameErrorId} className="mt-1 text-xs text-error-600">
            {nameError}
          </p>
        ) : null}
      </div>

      <div>
        <Field label="Artisan" required>
          {/*
            `Dropdown` DIRECTLY, not `FormControls.Select`, and the reason is no longer the one that
            used to be written here.

            IT USED TO BE THAT `Select` DROPPED THE DESCRIPTION. It forwarded only value/onChange/
            options/disabled/className/ariaLabel, so an `aria-describedby` handed to it fell into a
            `...rest` spread that these two pickers — passing no `name` — did not even render: the
            source read as though the refusal were bound while nothing announced it. That was a
            defect in `Select` affecting every caller in the app and it has been fixed there;
            `FormControls.Select` now translates `aria-describedby` into the dropdown's
            `describedBy`, and this local workaround is no longer needed FOR THAT.

            WHAT STILL KEEPS THESE TWO ON `Dropdown` is the shape of the data. `Select` builds its
            list from `<option>` CHILDREN and reports a synthetic `<select>` change event; both
            pickers here hold an OPTIONS ARRAY (a placeholder row spread in front of a mapped list)
            and want the picked value, which is what `Dropdown` takes and gives. Converting them
            would be a rewrite of two working controls to reach the same rendered element, so they
            stay — but a THIRD picker on this form should reach for `Select` like everywhere else.

            And only `describedBy`: see SearchableSelect's `describedBy` doc for why there is
            deliberately no `aria-invalid` here — the trigger is a `<button>`, and `aria-invalid` is
            not supported on the `button` role, so setting it would look like a mark and be ignored.
          */}
          <Dropdown
            value={artisanId}
            describedBy={artisanError ? artisanErrorId : undefined}
            /*
              `searchable` because this list is the artisan CORPUS — it is capped, and the cap is
              reported right below the control by `CappedListNotice`, which is as plain a statement
              as there is that this is a list to hunt through rather than read. The option-count rule
              would leave it up to how many artisans this deployment has interviewed so far, and the
              stakes here are the highest of any picker in the app: on /questionnaire the artisan
              picked is what decides `artisanSetKey`, i.e. which interview a submission folds into,
              and the labels are `name · place` pairs that differ by a word.
            */
            searchable
            options={[
              { value: "", label: "Select the artisan" },
              ...artisanOptions.map((artisan) => ({ value: artisan.id, label: `${artisan.name} · ${artisan.place}` }))
            ]}
            onChange={(picked) => {
              if (picked !== artisanId) {
                setArtisanId(picked);
                setProductId("");
                const artisan = artisanOptions.find((a) => a.id === picked);
                // An explicit pick replaces the remembered context and retires the banner: from
                // here on the artisan on screen is the researcher's own choice, not a suggestion.
                if (artisan) {
                  carry.remember({ artisanId: artisan.id, artisanName: artisan.name, place: artisan.place }, { explicit: true });
                }
              }
            }}
          />
        </Field>
        <CappedListNotice cuts={[artisanCut]} />
        {artisanError ? (
          <p id={artisanErrorId} role="alert" className="mt-1 text-xs text-error-600">
            {artisanError}
          </p>
        ) : null}
      </div>

      <div>
        <Field label="Product" required>
          {/* `Dropdown` directly, and `describedBy` alone — see the artisan picker above for both. */}
          <Dropdown
            value={productId}
            describedBy={productError ? productErrorId : undefined}
            /*
              `searchable` here too, even though one artisan usually has a handful of products. This
              list is re-fetched per artisan, so with the count in charge the SAME control on the
              SAME screen would grow a filter box for the artisan with nine products and lose it for
              the next one — a control changing shape mid-session, which is harder to learn than
              either behaviour on its own.
            */
            searchable
            options={[
              { value: "", label: productPlaceholder },
              ...artisanProducts.map((product) => ({ value: product.id, label: product.productName }))
            ]}
            onChange={(picked) => {
              setProductId(picked);
              const product = artisanProducts.find((candidate) => candidate.id === picked);
              if (!product) return;
              // Two calls, in this order, and the order is the point: pruning first drops the
              // product the banner was offering — the researcher has just overruled it, so the
              // banner must stop claiming it — and remembering then banks the one they chose. The
              // artisan above is still our suggestion, so the banner stays up saying so.
              pruneCarried("product");
              const artisan = artisanOptions.find((candidate) => candidate.id === artisanId);
              carry.remember({
                artisanId,
                artisanName: artisan?.name ?? null,
                place: artisan?.place ?? null,
                craftId: artisan?.craftId ?? null,
                productId: product.id,
                productName: product.productName
              });
            }}
            disabled={!artisanId || productsLoading || artisanProducts.length === 0}
          />
        </Field>
        {productsLoading ? <p className="mt-1 text-xs text-ink-500">Loading this artisan&apos;s products…</p> : null}
        {!productsLoading && productLoadError ? <p className="mt-1 text-xs text-error-600">{productLoadError}</p> : null}
        {!productsLoading && !productLoadError && artisanId && artisanProducts.length === 0 ? (
          <p className="mt-1 text-xs text-ink-500">No products found for this artisan yet. Create a product for them first, then return here.</p>
        ) : null}
        {/* The count and the cut are one statement, never two: "12 product(s) available" printed
            above "Showing 100 of 143" is the screen contradicting itself, and the count is the half
            that is wrong. When the list is whole — which it is for every artisan on this database —
            the count is exactly what it always was. */}
        {!productsLoading && !productLoadError && artisanId && artisanProducts.length > 0 && !productCut ? (
          <p className="mt-1 text-xs text-ink-500">{artisanProducts.length} product(s) available for this artisan.</p>
        ) : null}
        {!productsLoading && !productLoadError && artisanId ? <CappedListNotice cuts={[productCut]} /> : null}
        {productError ? (
          <p id={productErrorId} role="alert" className="mt-1 text-xs text-error-600">
            {productError}
          </p>
        ) : null}
      </div>

      {/*
        WHAT HAPPENS, FOR THE PROCESS AS A WHOLE — above the steps, because that is the order a
        reader meets them in and the order the report prints them in.

        `onValueChange` and not the hidden input every other form reads: this form builds its request
        body out of React state and never constructs a `FormData`, so `textValue(form, "notes")` has
        nothing to read. `defaultValue` is seeded once from `initial` and the reported string is
        deliberately NOT fed back — see the note on `initialValue` inside `RichTextField` for the
        caret that would throw to position zero on every keystroke.
      */}
      <RichTextField
        name="notes"
        label="What happens in this process"
        defaultValue={initial?.notes ?? ""}
        helper="The sequence in your own words. This is what the design-workshop report prints under “What happens”, both in the traditional-process table and above it."
        className="md:col-span-2"
        onValueChange={setNotes}
        // Said once at the top of this form by `DictationUnavailableNotice`; a copy under
        // every editor is the same paragraph over again. See the prop on `RichTextEditor`.
        explainWhenUnavailable={false}
      />

      <label className="flex items-center gap-2 text-sm text-ink-900">
        <input
          type="checkbox"
          className="h-4 w-4 accent-purple-700"
          checked={preProcessAvailable}
          onChange={(event) => setPreProcessAvailable(event.target.checked)}
        />
        Pre-processes available
      </label>
      {preProcessAvailable ? (
        <div className="grid gap-2">
          <p className="text-xs text-ink-500">Attach the pre-process media (required).</p>
          <SavedMediaList
            items={existingPreMedia}
            onRemoved={(id) => setExistingPreMedia((current) => current.filter((media) => media.id !== id))}
            onError={setError}
          />
          <p className="text-xs font-medium text-amber-800">🎥 Video is the preferred format here — capture the action as it happens.</p>
          <MediaCaptureField
            files={preFiles}
            onFilesChange={setPreFiles}
            title="Attach media"
            description="Photos, video, audio and files link to this record automatically. Audio is queued for transcription after upload."
            /*
              A STAGING OWNER THAT OUTLIVES THIS CARD, because this card is the one media control in
              the four record forms that can go away while the files it attached stay.

              It is mounted only while "Pre-processes available" is ticked, and `preFiles` lives one
              level up in this form. So unticking unmounted the card, `useEagerStaging` released its
              per-mount owner, and two seconds later `lib/media` aborted the transfer and DELETED
              the object already in storage — while the box the researcher can re-tick still held
              every one of those files. Re-ticking re-uploaded them from scratch on a connection
              that is usually a village's, or, offline, did not.

              A `useId` off THIS FORM and not a literal: stage TRADITIONAL_PROCESS_BASELINE can have
              a second `ProcessForm` open over the same record in the picker's edit dialog, and one
              owner name shared between them would let either one's release bin the other's files.

              WHAT IT BUYS IS THE MISCLICK, AND THE BOUND IS TWO SECONDS — say it plainly, because
              a reader of the paragraph above would otherwise take the hazard for closed. Unticking
              still unmounts the card and `useEagerStaging`'s cleanup still calls
              `releaseStagedOwner`; the stable name only helps because `stageFiles` cancels a
              pending release for the SAME owner, and `lib/media` gives it `RELEASE_GRACE_MS` —
              2_000 — before it aborts and deletes. Re-tick inside that window and the transfer
              survives. Re-tick ten seconds later and the object is gone and the files upload from
              scratch, exactly as before this line existed. THE FILES THEMSELVES ARE NEVER LOST
              EITHER WAY, because `preFiles` is hoisted into the form; what the longer gap costs is
              the upload, on a connection that is usually a village's. Closing that gap is a
              different change and should be named as one — keep the card mounted and hidden while
              unticked, or teach the store not to release an owner whose file list is non-empty.

              THE PAIRING RULE IS SATISFIED HERE AND NOWHERE ELSE IN THIS FILE — see
              `useEagerStaging`'s `ownerKey` note and `inlineRecordHost.ts`: a stable owner alone is
              worse than nothing, because it keeps the object alive after the last browser reference
              to it is gone. It is safe here precisely because the file list is hoisted above the
              unmount. It is not safe on a card whose files die with it.
            */
            stagingOwnerId={`${formId}:pre-process`}
            /* THE REFUSAL BELOW, BOUND — see `MediaCaptureField`'s own `aria-describedby` note. */
            aria-describedby={preMediaError ? preMediaErrorId : undefined}
          />
          {/* `role="alert"` AND a description on the card itself. The role is what announces the
              refusal at the moment it appears; the binding is what says it again to a researcher
              who tabs back to the card to act on it, which the live region alone could not. Both,
              because they answer different moments — see `submit()`'s ALERT OR DESCRIPTION. */}
          {preMediaError ? (
            <p id={preMediaErrorId} role="alert" className="text-xs text-error-600">
              {preMediaError}
            </p>
          ) : null}
        </div>
      ) : null}

      <div className="border-t border-line-200 pt-4">
        <h3 className="font-display font-bold text-ink-900">Steps</h3>
        {/* "Add at least one step" refuses the SECTION, not a box — there is no control to mark
            while the list is empty, so the live region is the whole of the announcement. */}
        {stepsError ? (
          <p id={stepsErrorId} role="alert" className="mt-1 text-xs text-error-600">
            {stepsError}
          </p>
        ) : null}

        <div className="mt-3 grid gap-3">
          {steps.map((step, index) => (
            <div key={step.key} className="grid gap-3 rounded-xl border border-line-200 bg-surface-50 p-4">
              <div className="flex items-center justify-between gap-2">
                <span className="text-sm font-semibold text-ink-900">
                  Step {index + 1} · {stepTypeLabel(step.stepType)}
                </span>
                <button
                  type="button"
                  className="text-sm font-semibold text-error-600"
                  onClick={() => setSteps((current) => current.filter((s) => s.key !== step.key))}
                >
                  Remove
                </button>
              </div>
              <div>
                {/* A step's name is free prose ("beating the weft down", "second indigo dip") and
                    it sits directly above the per-note microphones this card already carried, so
                    leaving it as the one silent box in the card was the odd thing. Its id stays
                    keyed on the step, because `submit()` focuses it by `document.getElementById`
                    when it refuses an unnamed step. NOT title-cased: `ProcessStep.name` is not in
                    the API's `TITLE_CASE_FIELDS`, so a hint would promise a normalisation that does
                    not happen. */}
                <DictatedTextInput
                  id={`step-name-${step.key}`}
                  label="Name of the step"
                  required
                  explainWhenUnavailable={false}
                  value={step.name}
                  aria-invalid={!!step.nameError}
                  aria-describedby={step.nameError ? `step-name-${step.key}-error` : undefined}
                  onChange={(next) => updateStep(step.key, { name: next })}
                />
                {/* No `role="alert"`, for the reason the process name's paragraph gives: this is
                    the focus ladder's second rung, so it is read on arrival as the input's
                    description. */}
                {step.nameError ? (
                  <p id={`step-name-${step.key}-error`} className="mt-1 text-xs text-error-600">
                    {step.nameError}
                  </p>
                ) : null}
              </div>
              <SavedMediaList
                items={step.existingMedia}
                onRemoved={(id) => updateStep(step.key, { existingMedia: step.existingMedia.filter((media) => media.id !== id) })}
                onError={setError}
              />
              <label className="flex items-center gap-2 text-sm text-ink-900">
                <input
                  type="checkbox"
                  className="h-4 w-4 accent-purple-700"
                  checked={step.recordAdditional}
                  onChange={(event) => updateStep(step.key, { recordAdditional: event.target.checked, ...(event.target.checked ? {} : { notes: "" }) })}
                />
                Record additional information
              </label>
              {step.recordAdditional ? (
                <MultiNoteInput
                  key={step.key}
                  label="Additional context for this step"
                  value={step.notes}
                  onChange={(value) => updateStep(step.key, { notes: value })}
                />
              ) : null}
              <p className="text-xs font-medium text-amber-800">🎥 Video is the preferred format here — capture the action as it happens.</p>
              <MediaCaptureField
                files={step.files}
                onFilesChange={(files) => updateStep(step.key, { files })}
                title="Attach media"
                description="Photos, video, audio and files link to this record automatically. Audio is queued for transcription after upload."
              />
            </div>
          ))}
        </div>

        <div className="relative mt-3">
          <button type="button" className="field-button-secondary w-full" onClick={() => setAddMenu((value) => !value)}>
            <Plus className="h-4 w-4" aria-hidden />
            Add Another Step
          </button>
          {addMenu ? (
            <div className="absolute left-0 right-0 z-20 mt-1 rounded-md border border-line-200 bg-card p-1 shadow-md">
              <button
                type="button"
                className="block w-full rounded px-3 py-2 text-left text-sm text-ink-900 hover:bg-purple-50"
                onClick={() => addStep("SEQUENTIAL")}
              >
                Sequential
              </button>
              <button
                type="button"
                className="block w-full rounded px-3 py-2 text-left text-sm text-ink-900 hover:bg-purple-50"
                onClick={() => addStep("GROUP")}
              >
                Group of activities
              </button>
            </div>
          ) : null}
        </div>
      </div>

      {canPickStatus ? (
        <Field label="Status">
          <Select value={status} onChange={(event) => setStatus(event.target.value)}>
            {statusChoices.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </Select>
        </Field>
      ) : (
        <div>
          <span className="field-label">Status</span>
          <div className="mt-1.5 flex items-center gap-2">
            {isEdit ? <StatusBadge status={String(initial?.status ?? "PENDING")} /> : <StatusBadge status="PENDING" />}
            <Lock className="h-3.5 w-3.5 text-ink-500" aria-hidden />
          </div>
          <p className="mt-1 text-xs text-ink-500">New records await review; a professor or admin sets the final status.</p>
        </div>
      )}

      <div className="rounded-xl bg-[#2A2520] p-3 text-xs text-[#E0C9B0]">
        Note: Different users may contribute to processes created by others. Even when documenting the same process, it is recommended
        that each researcher documents it individually, so that different perspectives on the same process are preserved.
      </div>

      {/*
        THE HOST'S OWN QUESTIONS, AT THE BOTTOM OF THE SAME LIST OF FIELDS — see
        `InlineRecordHostProps.footerFields`. Inside the `<form>` and above the buttons, so a
        design-workshop stage embedding this page adds its extra fields to the end of one continuous
        form rather than to a second panel below a form that has already ended. The separator is the
        only styling, and with no host there is no element at all.
      */}
      {footerFields ? <div className="grid gap-3 border-t border-line-200 pt-4">{footerFields}</div> : null}

      <div className="flex items-center justify-end gap-2">
        {uploadNote ? <span className="mr-auto text-xs text-ink-500">{uploadNote}</span> : null}
        <button type="button" className="field-button-secondary" onClick={requestCancel}>
          Cancel
        </button>
        {/* Closed once the write has landed, not merely while it is in flight. The only way this
            form is still on screen with `committed` true is the partial-media-failure return above,
            where the record IS saved and the error banner beside this button says to re-open it to
            retry the files. Leaving the button live there would offer a duplicate record dressed up
            as a retry — see `committed`. */}
        <button className="field-button" disabled={saving || committed} type="submit">
          {saving ? "Saving..." : committed ? "Saved" : isEdit ? "Update process" : "Save process"}
        </button>
      </div>

      <UnsavedChangesDialog
        open={guardOpen}
        saving={saving}
        onKeepEditing={() => {
          setGuardOpen(false);
          setPromptFromCancel(false);
        }}
        onDiscard={() => {
          setGuardOpen(false);
          setPromptFromCancel(false);
          /*
            WHICH host act this is depends on which control asked. Cancel means "empty this form, I
            am staying", and in the stage embed `onCancel` does exactly that. The back arrow means
            "take me off this screen", and answering it with `onCancel` alone is the defect
            `promptFromCancel` exists for: the work was discarded and the designer did not go
            anywhere.
          */
          if (promptFromCancel) onCancel();
          else leaveAfterDiscard();
        }}
        /*
          CLEARED HERE TOO, like the two answers above and like the three sibling forms, because
          this answer DOES take the prompt off the screen. A note that used to stand here claimed
          the opposite — that a refused `submit()` leaves the dialog standing — and this file
          contradicts it: the validation refusal calls `setGuardOpen(false)` before returning, and
          so do the late-workshop refusal, the queued branch, the partial-media branch, the success
          branch and the catch. Every exit closes it.

          A FLAG LEFT TRUE THEN OUTLIVES THE PROMPT IT DESCRIBES, which is the defect
          `onDiscardAndLeave` was added to end, reintroduced one step later: press Cancel, press
          Save, have the save refused for an empty name, fix the name, then press the HOST'S back
          arrow — the guard reopens the prompt, "Discard" takes the Cancel branch, and the form is
          emptied while the designer stays exactly where they were.

          `if (committed) return;` is the one exit that does not close the prompt, and it cannot be
          reached from here: `committed` makes `hasUnsavedWork` false, so a Cancel on a committed
          form never opens the prompt to begin with.
        */
        onSave={() => {
          setPromptFromCancel(false);
          void submit();
        }}
      />
    </form>
  );
}
