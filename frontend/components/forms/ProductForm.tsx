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
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { seedHasArtisan, type InlineHostSeed, type InlineRecordSurfaceProps } from "@/components/forms/inlineRecordHost";
import { craftChangeClearsArtisan, useCraftAndArtisanOptions, useRecordOffPage } from "@/components/forms/recordPickers";
import { TitleCasedInput } from "@/components/forms/TitleCasedInput";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { GridMeasurement, MEASUREMENT_GRID_PURPOSE, type GridFiles, type GridGroup } from "@/components/media/GridMeasurement";
import { UploadProgress } from "@/components/media/UploadProgress";
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
import type { Artisan, Craft, ProductDocumentation, RecordStatus } from "@/lib/types";
import { marketDemandOptions, productTypes } from "@/lib/types";

/** Dropdown label for a linked artisan: always "Name · Place" (name alone if no place), never ids. */
function artisanOptionLabel(artisan: Artisan) {
  const name = artisan.name?.trim() || "Unnamed artisan";
  // "·" (middle dot), not "•" — Android joins every record label with the middle dot, and the
  // process form already does; using both marks in one product form reads as two conventions.
  return artisan.place?.trim() ? `${name} · ${artisan.place.trim()}` : name;
}

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

export function ProductForm({
  initial,
  seed,
  footerFields,
  onCreated,
  onCancel,
  onDiscardAndLeave,
  onQueued
}: {
  initial?: ProductDocumentation;
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
   * The design-workshop stages pick products from reference dropdowns, and a designer who found the
   * record missing had to leave the stage they were half-way through, create it on its own page,
   * and come back — losing their place in a 22-stage record. Mounting this same form in a dialog
   * removes that, and it has to be THIS form: a simpler "quick create" would be a second answer to
   * what the record requires, and the two would drift.
   */
  onCreated?: (record: ProductDocumentation) => void;
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

    `/products/new?artisanId=…&artisanName=…` is how the full-page route learns whose product this
    is; a dialog has no URL, so the same three lines below read the seed instead. It sits AFTER the
    record being edited and BEFORE the query string for the only reason that ordering ever has: an
    edit is about a record that already has answers, and a form mounted in a dialog has no query
    string for the seed to be arguing with.
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
  // Dimensions are controlled so the "Document using grid" capture can auto-fill them.
  const [length, setLength] = useState(initial?.lengthInches != null ? String(initial.lengthInches) : "");
  const [breadth, setBreadth] = useState(initial?.breadthInches != null ? String(initial.breadthInches) : "");
  const [height, setHeight] = useState(initial?.heightInches != null ? String(initial.heightInches) : "");
  const [gridFiles, setGridFiles] = useState<GridFiles>({});
  /**
   * The craft and artisan dropdowns' contents, and what they are NOT showing.
   *
   * Shared with ToolForm, which asks the identical question — see `forms/recordPickers` for the
   * three requests it makes and for the ceiling that made the third one necessary. `referenceState`
   * still means what it did ("can I see this artisan?" and "is there any signal?" are different
   * answers, and `useCarryContext` treats them differently); it is moved into the hook only because
   * it is settled by the same load.
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
   * THIS PRODUCT'S OWN CRAFT IS ALWAYS AN OPTION, wherever it sorts.
   *
   * The hook above already does this for the ARTISAN and, until this line, for nobody else — so the
   * defect `useRecordOffPage` was written to close was still fully present on the craft dropdown of
   * this form and of ToolForm, on the same screen as the artisan dropdown that had been fixed.
   * `/crafts` is clamped to 100 rows and ordered NAME ASCENDING (deliberately, see the ordering
   * comment in `routes/crafts.py`), and this database holds 178 crafts (counted 2026-08-15), so the
   * cut is stable and always falls in the same place: every product of a craft whose name sorts past
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
   * as `ArtisanForm` and `ToolForm`.
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
    ? ((initial as ProductDocumentation & { location?: LocationInitialValues | null }).location ?? null)
    : undefined;
  const isEdit = Boolean(initial);
  // The workshop this product was documented at: shared picker, shared most-recent defaulting, and
  // the late-submission gate (see components/forms/WorkshopSelect).
  //
  // `seed.workshopId` is the design workshop's own linked Workshop and outranks the most-recent
  // probe: a product created from a WORKSHOP-scoped picker that is filed against a different
  // sitting is a product that picker can never show again. Passing it here also marks the selection
  // `touched`, which is what keeps the probe and the carry bag off it. See {@link InlineHostSeed}.
  const workshop = useWorkshopSelection({
    initialWorkshopId: initial?.workshopId ?? seed?.workshopId,
    isEdit,
    resetKey: initial?.id ?? null
  });

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
   * product legitimately carries a craft its artisan does not: silently rewriting it here would
   * make merely OPENING a record change it.
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

  // Task 6: once a craft is linked, the artisan dropdown only offers artisans of that craft. The
  // currently-selected artisan is always kept visible even if the data predates the craft link.
  const artisansForCraft = craftId
    ? artisans.filter((artisan) => artisan.craftId === craftId || artisan.id === artisanId)
    : artisans;

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
  // only survives a click straight through from the save screen (lib/carryContext). The PRODUCT in
  // the bag is this form's own subject and is never applied here; a tool or process in it belongs
  // to other forms and is left alone rather than dropped, so they still have it.
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
    //
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
      const exifItems = await collectExifMetadata([...Object.values(gridFiles), ...mediaFiles].filter(Boolean) as File[]);
      const exifRemark = exifMetadataToRemark(exifItems);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const location = locationFromForm(form);
      const payload = {
        craftName: requiredText(form, "craftName"),
        place: requiredText(form, "place"),
        artisanName: requiredText(form, "artisanName"),
        productName: requiredText(form, "productName"),
        localName: textValue(form, "localName"),
        productType: requiredText(form, "productType") || "OTHER",
        timeTakenToCompleteProduct: textValue(form, "timeTakenToCompleteProduct"),
        size: textValue(form, "size"),
        lengthInches: toNum(length),
        breadthInches: toNum(breadth),
        heightInches: toNum(height),
        costOfMaking: numericValue(form, "costOfMaking"),
        sellingPrice: numericValue(form, "sellingPrice"),
        marketDemand: requiredText(form, "marketDemand") || "UNKNOWN",
        rawMaterialsUsed: textValue(form, "rawMaterialsUsed"),
        mainToolsUsed: textValue(form, "mainToolsUsed"),
        productFunctionUse: textValue(form, "productFunctionUse"),
        // `appendStoredParagraph` and NOT `appendRemarksWithExif`: remarks is a rich-text editor
        // now, so this column may hold a JSON document, and concatenating the EXIF summary onto the
        // end of a JSON string produces a value that is neither valid JSON nor readable prose. The
        // helper appends INTO the document when there is one and is byte-for-byte the old behaviour
        // when there is not.
        remarks: appendStoredParagraph(textValue(form, "remarks") as string | null, exifRemark),
        artisanId: artisanId || null,
        craftId: craftId || null,
        workshopId: workshop.workshopId || null,
        // Below professor no status control is rendered: create submits PENDING, edit resubmits the
        // current status (the backend drops unauthorized changes either way).
        status: requiredText(form, "status") || initial?.status || "PENDING",
        recordedAt,
        recordedTimezone,
        location,
        // extraMetadata stays programmatic (EXIF etc.) — the raw JSON textarea was removed.
        extraMetadata: exifItems.length ? { mediaExif: exifItems } : {}
      };
      // Offline this queues instead of failing, carrying the grid photos and the field media with
      // it — each group as its own batch so the captions that identify a grid photo survive.
      const outcome = await saveOrQueue<ProductDocumentation>({
        label: `Product · ${payload.productName || "Untitled"}`,
        endpoint: initial ? `/products/${initial.id}` : "/products",
        method: initial ? "PATCH" : "POST",
        body: payload,
        media: [
          ...(Object.entries(gridFiles) as [GridGroup, File][]).map(([group, file]) => ({
            files: [file],
            linkedRecordType: "product",
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${payload.productName || "product"}`,
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
          {
            files: mediaFiles,
            linkedRecordType: "product",
            caption: `Field media for ${payload.productName || "product"}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined
          }
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
        // Offline is the normal case, but a queued product has no id yet, so no process form could
        // link to it. Whatever product was in the bag is dropped rather than left to stand in for
        // the one just recorded — an old product offered under a new one's name is a wrong link.
        carry.prune("product");
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
            "Saving…" to "Save product" and nothing else on screen changed — indistinguishable from
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
      // The product itself now joins the bag: a process is documented against a product, so the
      // process form should be offering this one rather than making them find it again.
      carry.remember({ ...sitting, productId: saved.id, productName: saved.productName });
      // Store each captured grid photo as media linked to the product (the measured value is already
      // in the field). Best-effort per file so one failure doesn't lose the record.
      for (const [group, file] of Object.entries(gridFiles) as [GridGroup, File][]) {
        try {
          await uploadMediaFile({
            file,
            linkedRecordType: "product",
            linkedRecordId: saved.id,
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${saved.productName}`,
            location,
            recordedAt,
            recordedTimezone,
            // MARKED SO IT SORTS LAST AND NEVER OUTRANKS A REAL PHOTOGRAPH — see
            // MEASUREMENT_GRID_PURPOSE. Last and not excluded: a product whose only image is this
            // grid shot still gets a picture rather than a blank.
            extraMetadata: { purpose: MEASUREMENT_GRID_PURPOSE },
            transcribeAudio: false
          });
        } catch {
          /* keep the saved record even if a grid photo fails to store */
        }
      }
      if (mediaFiles.length) {
        const { failed } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "product",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.productName}`,
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
            was wrong: the product IS in the repository — only the photographs are missing. The
            host was never told, so the stage row that opened this form stayed unlinked over a
            record that exists, and an unlinked REF is not something a designer can see and repair
            later: the stage 422s on submit, hours afterwards, naming a required reference to a
            product they remember creating. The obvious next move is to create it a second time.

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
      router.push("/products");
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save product record");
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
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {/* Android parity (ProductForm): the workshop opens the form, because it is the context
              every other answer belongs to — not merely the first dropdown. */}
          <WorkshopSelect state={workshop} onDirty={markDirty} saving={saving} />
          <Field label="Product name" required>
            {/* Product/craft/artisan names and place are title-cased by the API on write, so the box
                says what will actually be stored (Android parity — see forms/TitleCasedInput). */}
            <TitleCasedInput name="productName" required defaultValue={initial?.productName ?? ""} />
          </Field>
          <Field label="Local name">
            <TextInput name="localName" defaultValue={initial?.localName ?? ""} />
          </Field>
          <Field label="Product type">
            <Select name="productType" defaultValue={initial?.productType ?? "OTHER"} onChange={markDirty}>
              {productTypes.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          <Field label="Linked craft (fills craft name)">
            <Select
              name="craftId"
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
          <Field label="Craft name" required>
            <TitleCasedInput name="craftName" required value={craftName} onChange={(event) => setCraftName(event.target.value)} />
          </Field>
          <Field label="Linked artisan (fills artisan + place)">
            <Select
              name="artisanId"
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
                over a craft with a dozen of them — the silent-emptiness failure in one sentence,
                and the reason `artisansLoadedForCraft` records which craft the loaded rows are for
                rather than a bare boolean. */}
            {craftId && artisansLoadedForCraft === craftId && artisansForCraft.length === 0 ? (
              <p className="mt-1 text-xs text-ink-muted">No artisans are linked to this craft yet.</p>
            ) : null}
            <CappedListNotice cuts={[craftId ? craftArtisanCut : null]} />
          </Field>
          <Field label="Artisan name" required>
            <TitleCasedInput name="artisanName" required value={artisanName} onChange={(event) => setArtisanName(event.target.value)} />
          </Field>
          <Field label="Place" required>
            <TitleCasedInput name="place" required value={place} onChange={(event) => setPlace(event.target.value)} />
          </Field>
          <Field label="Time taken to complete">
            <TextInput name="timeTakenToCompleteProduct" defaultValue={initial?.timeTakenToCompleteProduct ?? ""} />
          </Field>
          <Field label="Size">
            <TextInput name="size" defaultValue={initial?.size ?? ""} />
          </Field>
          {/* ── `min={0}` ON EVERY NUMBER ON THIS FORM, AND THE SAME BOUND ON THE SCHEMA ──────
              A dimension and a price are quantities that cannot be negative, and the workshop
              registry already says so: `product.lengthCm` / `product.widthCm` and
              `product.costOfMaking` are all declared `min_value=0` in `stage_definitions.py` — and
              the middle one is spelled `widthCm`, not `breadthCm`, because the product record's
              `breadthInches` lands on the workshop's WIDTH box (`_METHOD_CARRIED_DIMENSIONS` in
              `design_workshops.py` carries the pair). The tool stage is the one that keeps the word
              breadth.

              Until this, only the SERVER-side halves declared for `experienceYears` and
              `yearsInUse` had a partner here, so "-40" was accepted by the box, accepted by
              `ProductCreate`, stored,
              and then refused by the workshop on the row it was carried into — the number reached
              the report's cost table before anything objected to it.

              The bound has to be BOTH halves or it is neither. This one refuses the value in the
              box, by name, before a request is made (the form has no `noValidate`, so the browser's
              own constraint validation runs and focuses the offending input); `ge=0` in
              `backend/app/schemas/records.py` refuses it for every client that is not this one. */}
          <Field label="Length (inches)">
            <TextInput name="lengthInches" type="number" min={0} step="0.01" value={length} onChange={(event) => setLength(event.target.value)} />
          </Field>
          <Field label="Breadth (inches)">
            <TextInput name="breadthInches" type="number" min={0} step="0.01" value={breadth} onChange={(event) => setBreadth(event.target.value)} />
          </Field>
          <Field label="Height (inches)">
            <TextInput name="heightInches" type="number" min={0} step="0.01" value={height} onChange={(event) => setHeight(event.target.value)} />
          </Field>
        </div>
        <GridMeasurement
          includeHeight
          onLengthBreadth={(l, b) => {
            if (l) setLength(l);
            if (b) setBreadth(b);
            markDirty();
          }}
          onHeight={(value) => {
            setHeight(value);
            markDirty();
          }}
          onFilesChange={(files) => {
            setGridFiles(files);
            markDirty();
          }}
        />
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {/* Money, and the same pairing rule as the dimensions above. */}
          <Field label="Cost of making">
            <TextInput name="costOfMaking" type="number" min={0} step="0.01" defaultValue={initial?.costOfMaking ?? ""} />
          </Field>
          <Field label="Selling price">
            <TextInput name="sellingPrice" type="number" min={0} step="0.01" defaultValue={initial?.sellingPrice ?? ""} />
          </Field>
          <Field label="Market demand">
            <Select name="marketDemand" defaultValue={initial?.marketDemand ?? "UNKNOWN"} onChange={markDirty}>
              {marketDemandOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          {/*
            THE FOUR NARRATIVE BOXES ON THIS FORM, and all four qualify under the rule the user set:
            each was already a `<TextArea>` (`min-h-24`, no length cap) holding prose about how the
            product is made, what it is made of and what it is for. The four single-line boxes above
            — code, dimensions, cost, selling price, market demand — deliberately get nothing: a
            formatting toolbar on a price field is noise, and dictating four digits is slower than
            typing them.

            Raw materials and main tools are lists as often as they are sentences, which is exactly
            what the editor's bullet button is for; they are seeded as prose rather than as lists
            because the existing records in these columns are comma-separated sentences and reshaping
            them on open would be the editor arguing with what was written.
          */}
          <RichTextField
            name="rawMaterialsUsed"
            label="Raw materials used"
            defaultValue={initial?.rawMaterialsUsed ?? ""}
            className="md:col-span-2"
            onDirty={markDirty}
          />
          <RichTextField
            name="mainToolsUsed"
            label="Main tools used"
            defaultValue={initial?.mainToolsUsed ?? ""}
            className="md:col-span-2"
            onDirty={markDirty}
          />
          <RichTextField
            name="productFunctionUse"
            label="Function or use"
            defaultValue={initial?.productFunctionUse ?? ""}
            className="md:col-span-2"
            onDirty={markDirty}
          />
          <RichTextField
            name="remarks"
            label="Remarks"
            defaultValue={initial?.remarks ?? ""}
            className="md:col-span-2"
            onDirty={markDirty}
          />
          <StatusField canSetStatus={canSetStatus} initialStatus={initial?.status} onDirty={markDirty} />
        </div>
        {initial ? <ExistingMedia linkedRecordType="product" linkedRecordId={initial.id} /> : null}
        <MediaCaptureField
          files={mediaFiles}
          onFilesChange={(files) => {
            setMediaFiles(files);
            markDirty();
          }}
          title="Product media"
          description="Attach or capture product images, videos, audio notes, and documents. Image EXIF is retained and summarized in remarks."
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
            {saving ? "Saving..." : initial ? "Update product" : "Save product"}
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
