"use client";

/**
 * ONE stage of a design workshop — and the only stage form in this application.
 *
 * There are 22 stages, 43 entities and 496 typed fields, and none of them are named in this file.
 * The page looks the stage up in the registry by its URL key, renders its SINGLETON entity through
 * `EntityForm` and each of its COLLECTION entities through `CollectionTable`, and posts the whole
 * thing back in one `PUT`. A field added to `stage_definitions.py` appears here, correctly typed
 * and correctly tiered, with nothing in the web client changing.
 *
 * FOUR DECISIONS IN HERE ARE LOAD-BEARING AND LOOK LIKE DETAILS.
 *
 * 1. **A stage is saved whole, in one request.** `save_stage` writes it inside a single
 *    transaction, so either all of it lands or none of it does. A per-field or per-row save would
 *    leave a stage half-written whenever the connection dropped mid-save, which on one bar of
 *    signal in a village is most of the time.
 *
 * 2. **`replaceCollections` is armed only when a row was actually deleted.** True means "the
 *    entities I am sending are now exactly this", which is right for the phone posting everything
 *    it holds after two days offline, and dangerous for a browser: it deletes any row a second
 *    editor added between this page loading and Save being pressed. But it is also the ONLY way a
 *    deletion can reach the server — there is no per-row delete endpoint — so a permanently-false
 *    flag would give this page a Delete button that silently does nothing. Sending it only when
 *    something was genuinely removed keeps the ordinary save a merge and confines the sweep to the
 *    one action that requires it. The banner above Save says so on the save where it matters.
 *
 * 3. **`submit` is off by default and is a separate button.** With it off the server stores
 *    whatever is filled in and reports what is missing; with it on it 422s on any unfilled BASIC
 *    field. A stage half-filled overnight is the normal state of this app, not an error, so the
 *    default must never be the strict one — but a designer about to generate a report needs a way
 *    to ask "is this stage actually finished", which is what the second button is.
 *
 * 4. **`droppedKeys` is shown, never swallowed.** It names field keys this build sent that the
 *    server's registry does not know — which means data a designer typed was NOT stored. It is the
 *    only signal that the two ends have drifted, and a silent drop is a form that accepts an answer
 *    and discards it.
 *
 * 5. **THE LOCAL DRAFT IS READ FIRST AND WRITTEN CONTINUOUSLY.** `lib/designWorkshopStore` holds
 *    the whole workshop in IndexedDB, so this page renders from disk before the network is asked
 *    and behaves identically with and without a connection. Every edit is written back after a
 *    short debounce, and the write is flushed before any navigation and on `pagehide`. The server
 *    read that follows is folded in only where the stage has NOT been edited locally since the last
 *    push — a background answer must never overwrite unsent fieldwork with an older copy.
 *
 * 6. **THERE IS NO "UNSAVED CHANGES" PROMPT ANY MORE, ON PURPOSE.** That dialog exists to stop work
 *    being LOST, and with a durable draft nothing is lost by leaving: the stage is on this device
 *    whether the designer presses Save, navigates away or shuts the laptop. A prompt that fires when
 *    nothing is at stake is exactly how researchers are trained to click through the guard — and it
 *    still has to mean something an hour later on a form that genuinely is holding unsaved work.
 *    What replaces it is honest state: the stage says whether it has reached the repository, and the
 *    banner in the protected layout says it again from anywhere in the app.
 */

import { Fragment, Suspense, use, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ChevronLeft, ChevronRight, CloudOff, Layers } from "lucide-react";

import { CustomSectionsForm } from "@/components/designworkshop/CustomSections";
import {
  CollectionTable,
  EntityForm,
  removalIsADeletion,
  rowsTheServerCouldHold,
  stageEntryBudget,
  type FieldErrors,
  type ServerHeldRows
} from "@/components/designworkshop/EntityForm";
import { StagePendingMediaProvider } from "@/components/designworkshop/FieldInput";
import { LinkedWorkshopProvider } from "@/components/designworkshop/LinkedWorkshop";
import { COSTING_STAGE, CostFindingsPanel } from "@/components/designworkshop/CostFindingsPanel";
import { ANALYSIS_STAGE, MarketFindingsPanel } from "@/components/designworkshop/MarketFindingsPanel";
import {
  EMPTY_RECORDING_PLACE,
  StageRecordingPlaceCard,
  type StageRecordingPlace
} from "@/components/designworkshop/StageRecordingPlace";
import { PageHeader } from "@/components/PageHeader";
import { StageDocumentPreview } from "@/components/designworkshop/report/StageDocumentPreview";
import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";
import { UploadTray } from "@/components/media/UploadTray";
import { UploadsProvider } from "@/lib/uploads";
import { ApiError } from "@/lib/api";
import {
  getDesignWorkshopStage,
  saveDesignWorkshopStage,
  type DwEntity,
  type DwEntryData,
  type DwRegistry,
  type DwRow,
  type DwSaveResult,
  type DwStage,
  type DwStageCompleteness,
  type DwValue,
  type DwStageProvenance
} from "@/lib/designWorkshops";
import {
  CUSTOM_ENTITY_KEY,
  customFieldsForStage,
  sectionsForStage,
  type DwCustomSection
} from "@/lib/customSections";
import {
  adoptServerStage,
  buildStageEntries,
  customFieldsFor,
  emptyStage,
  ensureDraft,
  placeStageErrors,
  stageSweep,
  strandedRefusals,
  isLocalWorkshopId,
  loadCustomDefinition,
  loadDraft,
  loadRegistry,
  localStageCompleteness,
  markStagePushed,
  putDraftStage,
  scoreStageData,
  splitSingletons,
  stageDataOf,
  type CustomDefinitionSource,
  type DwDraft,
  type DwDraftStage,
  type DwRowKey,
  type RegistrySource
} from "@/lib/designWorkshopStore";
import { registryProvenanceNotice } from "@/lib/registryProvenance";
import { localWriteDecision, stageRefusalResult, stageRefusalWroteCount } from "@/lib/stageSaveOutcome";
import { isUnreachable, serverAskedForTime, triageFailure } from "@/lib/offline";
import { neverReconciled } from "@/lib/workshopOpenability";
import { readStageFocus } from "@/lib/workshopSearch";
/*
  A CROSS-ROUTE RELATIVE IMPORT, ON PURPOSE.

  `reportServerId` is the one place the rule "which id does the REPOSITORY know this workshop by" is
  written down and exercised (`e2e/report-target-unit.spec.ts`), and the report page and its history
  view already read it from there. The module lives under the sibling `report/` route, so reaching it
  from here is `../../report/…` rather than an `@/lib` import — the lesser of the two prices. Lifting
  it into `lib/` beside `isLocalWorkshopId` would read better and would move a file that two screens
  and a spec address by path, which is not this change's to do: its subject is the preview panel below
  having been handed the route param instead of the resolved id.
*/
import { reportServerId } from "../../report/reportTarget";

/**
 * How long after the last keystroke the stage is written to IndexedDB.
 *
 * Short enough that a flat battery costs at most this much typing, long enough that a designer
 * typing a 400-word narrative is not issuing a transaction per character on a field laptop. The
 * write is also flushed before every navigation and on `pagehide`, so the debounce is the only
 * window in which anything is in memory alone.
 */
const AUTOSAVE_MS = 800;

/**
 * The three pieces of form state that make up a stage, as one comparable value.
 *
 * It exists so the autosave can ask "is this actually different from what is already banked" rather
 * than "has the form been seeded yet" — see the effect that uses it for what the second question
 * got wrong.
 */
type StageSnapshot = {
  singleton: DwEntryData;
  collections: Record<string, DwRow[]>;
  removedFrom: string[];
  /**
   * The designer's own answers, compared like the rest.
   *
   * IT HAS TO BE IN HERE OR THE AUTOSAVE NEVER FIRES FOR THEM. The effect writes only when the
   * snapshot differs from what is banked, so a custom answer left out of the comparison would be
   * typed, would change no compared value, and would never reach IndexedDB — a designer would answer
   * a custom question, reload the stage, and find it blank with the form having reported nothing.
   */
  custom: DwEntryData;
};

/**
 * Structural equality over stored stage values.
 *
 * A field value is anything `DwValue` allows — a string, a number, a tag list, a GEO object, a
 * rich-text document — so this recurses rather than comparing references: `setRemovedFrom([])`
 * hands React a NEW empty array every time, and the seeded objects are rebuilt by
 * `stageDataOf`/`splitSingletons`, so reference equality answers "different" for two values that
 * are the same answer.
 *
 * MISSING AND NULL AND UNDEFINED ARE ONE ANSWER HERE, deliberately: `isFilled` — which is
 * character-for-character the server's `_is_filled` — counts all three as "no answer", so treating
 * them as different would mark a stage edited because a key was written with the emptiness it
 * already had.
 */
function sameStoredValue(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (a == null || b == null) return a == null && b == null;
  if (typeof a !== "object" || typeof b !== "object") return false;
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    return a.every((item, index) => sameStoredValue(item, b[index]));
  }
  const left = a as Record<string, unknown>;
  const right = b as Record<string, unknown>;
  for (const key of new Set([...Object.keys(left), ...Object.keys(right)])) {
    if (!sameStoredValue(left[key], right[key])) return false;
  }
  return true;
}

/**
 * Is this device showing a stage it has never read from the repository?
 *
 * Only ever true for a workshop the server knows about: a workshop that exists solely on this
 * laptop has no server copy to have missed, and its empty stages are empty because nobody has
 * filled them in yet.
 */
function stageNeverRead(draft: DwDraft | null, stageKey: string): boolean {
  if (!draft?.remoteId) return false;
  return (draft.stages[stageKey]?.serverLoadedAt ?? null) === null;
}

/**
 * What can be said about the SERVER's id for this workshop before any draft has been read.
 *
 * THREE ANSWERS AND NOT TWO. A route param that is not a `dwlocal-…` id IS the server's id — that is
 * `reportServerId`'s second arm, asked with no draft, and it needs no read at all. The other half of
 * the rule lives on the draft (`remoteId`, written back by the sync pass once the record has been
 * created), so for a local route param the honest answer here is `undefined`: NOT KNOWN YET, which is
 * a different fact from the `null` that means "this workshop is only on this device". Collapsing the
 * two would have {@link StageDocumentPreview} print "there is nothing to draw until this workshop has
 * synced" over a workshop nobody has looked up, which is one half of the defect this function exists
 * to close — the other half is that the panel used to be given the route param and never the draft's
 * `remoteId` at all.
 */
function knownServerId(routeId: string): string | null | undefined {
  return isLocalWorkshopId(routeId) ? undefined : reportServerId(routeId, null);
}

function sameSnapshot(a: StageSnapshot, b: StageSnapshot): boolean {
  return (
    sameStoredValue(a.singleton, b.singleton) &&
    sameStoredValue(a.collections, b.collections) &&
    sameStoredValue(a.custom, b.custom) &&
    sameStoredValue([...a.removedFrom].sort(), [...b.removedFrom].sort())
  );
}

/**
 * The page-level upload dock and the page-level attached-file store, mounted once around the stage.
 *
 * Every media field on a stage pre-uploads the moment a file is attached, and stage 13 has eleven of
 * them. Without `UploadsProvider` each field would report its own progress into a context that is
 * not there and the designer would have eleven separate percentages and no answer to "is it safe to
 * close this tab yet". `UploadTray` renders its own `aria-hidden` spacer, so the fixed dock does not
 * sit on top of the Save button at the bottom of the page.
 *
 * `StagePendingMediaProvider` IS HERE FOR A DATA-LOSS BUG AND ITS POSITION IS THE FIX. A collection
 * row's panel is unmounted when the row is collapsed — it has to be; the flagship workshop has 244
 * rows — and the list of files that have been ATTACHED BUT NOT YET LINKED used to live inside that
 * panel. Collapsing the row destroyed the only reference to them, and two seconds later the staged-
 * owner release in `lib/media` aborted the transfer and deleted the object that was already in
 * storage: reopening the row said "Nothing attached yet" over a photograph that no longer existed
 * anywhere. Held HERE, the list has the page's lifetime instead of the panel's. Read the header on
 * `StagePendingMediaProvider` for the other half of the fix, which is the stable staging owner id —
 * neither half works alone.
 *
 * OUTSIDE THE SUSPENSE BOUNDARY, for the same reason `UploadsProvider` is: a provider that suspends
 * remounts, and a remount here would throw away exactly what it is holding.
 */
export default function DesignWorkshopStagePage(props: {
  params: Promise<{ id: string; stageKey: string }>;
}) {
  return (
    <UploadsProvider>
      <StagePendingMediaProvider>
        {/* Next 16: the body reads `useSearchParams` (the workshop search's `?find=`), which must sit
            inside a Suspense boundary. Both providers stay OUTSIDE it — a provider that suspends
            would drop the staged-file context every field on the stage is uploading through, and the
            attached-file list with it. */}
        <Suspense fallback={<div className="panel p-4 text-sm text-ink-700">Loading this stage…</div>}>
          <DesignWorkshopStagePageBody {...props} />
        </Suspense>
      </StagePendingMediaProvider>
      <UploadTray />
    </UploadsProvider>
  );
}

function DesignWorkshopStagePageBody({
  params
}: {
  params: Promise<{ id: string; stageKey: string }>;
}) {
  // Next 16 hands route params over as a promise; `use` unwraps it in a client component.
  const { id, stageKey } = use(params);
  const router = useRouter();

  /**
   * The field a workshop search sent the designer here for, or null when they arrived normally.
   *
   * READ FROM THE URL ON EVERY RENDER RATHER THAN LATCHED INTO STATE, and deliberately never
   * stripped out of the URL afterwards — the opposite of `useEditDeepLink`'s one-shot `?edit=`.
   * The difference is what the parameter MEANS: `?edit=` is an intent that has been consumed once
   * the form is seeded, while `?find=` is part of the address of a box. Leaving it standing is what
   * makes the link shareable, survives a reload on a laptop that has just been reopened, and lets
   * the Back button from a mistyped stage return to the search result rather than to a form with
   * nothing marked on it.
   */
  const searchParams = useSearchParams();
  const focus = useMemo(() => readStageFocus(searchParams) ?? undefined, [searchParams]);

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [registrySource, setRegistrySource] = useState<RegistrySource | null>(null);
  // Whether this render's field list has to explain itself, and in which words — decided in
  // `lib/registryProvenance`, never with a comparison in the JSX. See the banner near the bottom.
  const registryNotice = registryProvenanceNotice(registrySource);
  const [singleton, setSingleton] = useState<DwEntryData>({});
  /** Who last set each field, as the server reported it. Null until a stage has been adopted. */
  const [provenance, setProvenance] = useState<DwStageProvenance | null>(null);
  const [collections, setCollections] = useState<Record<string, DwRow[]>>({});
  const [errors, setErrors] = useState<Record<string, Record<string, string>>>({});
  /**
   * REFUSALS THIS PAGE COULD NOT ATTACH TO A BOX, KEPT RATHER THAN DROPPED.
   *
   * `save_stage` keys its per-field errors by an entry's INDEX IN THE ARRAY THAT WAS SENT, and this page
   * re-keys them onto the row on screen through `rowKeys`. That re-keying can fail: an index past the end
   * of what we sent, or an index whose entry names a different entity than the scope key does. It used to
   * fail SILENTLY — the decode fell back to the server's own key, which has the same `entity[n]` shape as
   * a placed one, `collectionErrors` matched it as if it were a row, and `CollectionTable` never looked up
   * a row that far along. The refusal then existed on the server and nowhere else: the field was not
   * saved, and nothing on screen said so.
   *
   * A STRING PER FIELD, `scope.field: message`, which is `DwStageRefusalReport.unplaced` on the handset
   * verbatim — the two surfaces describe the same event in the same words, and the count below sums the
   * same things. Naming the wrong row was never an option: a message on a box that is fine sends a
   * designer to correct an answer nobody objected to.
   */
  const [unplaced, setUnplaced] = useState<string[]>([]);
  const [dropped, setDropped] = useState<string[]>([]);
  /**
   * The designer's own answers for this stage, as they stand on screen.
   *
   * A SEPARATE PIECE OF STATE FROM `singleton`, never a few extra keys inside it. `splitSingletons`
   * copies across only the keys the registry declares, so a custom answer folded into that map would be
   * dropped on the floor before the write — and if it were not, `save_stage` would post it inside a core
   * entry, drop it there instead, and return it in `droppedKeys`, firing "this build is running ahead of
   * the server's field list" on every save of every workshop that has a custom section.
   */
  const [custom, setCustom] = useState<DwEntryData>({});
  /** The definition's sections for this stage, and how this browser came to hold them. */
  const [customSections, setCustomSections] = useState<DwCustomSection[]>([]);
  const [customSource, setCustomSource] = useState<CustomDefinitionSource>("unknown");
  /**
   * The digest of the definition this browser answered under, and the one the server last used.
   *
   * TWO STRINGS, COMPARED, RATHER THAN ONE FLAG. The comparison is only meaningful after a save has
   * told us what the server used, so `serverCustomVersion` starts null and a null never reads as stale —
   * a browser that has not saved yet has no evidence of drift and must not claim any.
   */
  const [heldCustomVersion, setHeldCustomVersion] = useState("");
  /**
   * The `Workshop` RECORD this design workshop is linked to — not this page's own `id`.
   *
   * Read off the DRAFT's header rather than fetched, so it is answerable in a courtyard with no
   * signal, which is where inline record creation is used. Null is a real answer (an unlinked
   * design workshop) and the one consumer, {@link LinkedWorkshopProvider}, treats it as one.
   *
   * What it is for: a reference picker that creates a repository record inline hands it to the form,
   * so the artisan or product is filed against the sitting it was documented at. Five REF fields are
   * WORKSHOP-scoped and the server narrows them on exactly this column — without it, a record made
   * from one of those pickers is invisible in the picker that made it. See `LinkedWorkshop`.
   */
  const [linkedWorkshopId, setLinkedWorkshopId] = useState<string | null>(null);
  const [serverCustomVersion, setServerCustomVersion] = useState<string | null>(null);
  /**
   * Custom keys the server's definition did not carry, with a sentence of their own.
   *
   * **ITS OWN STATE, NEVER MERGED INTO `dropped`.** That banner says "this build is running ahead of the
   * server's field list", which is the only registry-drift signal this repository has and is the WRONG
   * diagnosis here: the definition was edited, not the app, and the remedy is a reload rather than a bug
   * report. Merging them would fire the registry banner on every save of every workshop with a custom
   * section and train the people who read it to ignore it — which is precisely what the note above the
   * server's own `droppedKeys` computation says must never happen.
   */
  const [droppedCustom, setDroppedCustom] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /**
   * What generation of the saved record the document preview has drawn.
   *
   * Bumped by the save path and read by `StageDocumentPreview`, which fetches nothing until it is
   * opened. See the comment beside the increment for why it is a counter and not a flag.
   */
  const [previewToken, setPreviewToken] = useState(0);
  /**
   * The id the REPOSITORY knows this workshop by, for the document preview above the form.
   *
   * `undefined` until the draft has been read, `null` once it has been read and carries no
   * `remoteId`, and the server's id otherwise — see {@link knownServerId} for why that is three
   * answers and not two.
   *
   * STATE, WRITTEN BY THE LOAD EFFECT THAT ALREADY HAS THE DRAFT IN HAND. The panel is rendered
   * before that effect resolves, so it cannot read the draft for itself without racing the effect
   * that owns it; and `draftIdRef` below deliberately holds the LOCAL id, which is the one every
   * write on this page addresses and the one the server does not know. The panel used to be handed
   * the ROUTE param instead, and for a workshop created with no signal and since synced — whose URL
   * keeps its `dwlocal-…` id — that made its `localOnly` permanently true: it said "there is nothing
   * to draw until this workshop has synced" about a record the repository was holding, on the same
   * page whose save path was posting stages into it.
   *
   * RESOLVED ONCE, ON OPEN, AND THAT IS A BOUNDARY RATHER THAN AN OVERSIGHT. The sync pass can
   * create the server record while this stage is open, and nothing re-reads the draft for this
   * value afterwards, so until the next stage open the panel goes on saying the workshop has not
   * synced. The save path below re-derives the same id for its own use and deliberately does not
   * write it here: its draft read is allowed to come back null — a refused flush, a draft belonging
   * to another session on this laptop — and turning that null into "this workshop is only on this
   * device" would print the exact claim this state exists to stop.
   */
  const [serverId, setServerId] = useState<string | null | undefined>(() => knownServerId(id));
  const [loading, setLoading] = useState(true);
  /**
   * True while the form is being held back for the server's copy of a stage this device has never
   * read. It only changes the sentence under the spinner — but which sentence is the whole point:
   * "loading" and "this browser has never seen this stage" are different facts.
   */
  const [awaitingServer, setAwaitingServer] = useState(false);
  /**
   * True when the form on screen is blank because this device has never DOWNLOADED the stage, not
   * because the stage is empty. Held separately from `notice`, which every save message overwrites —
   * this one must survive all of them.
   */
  const [neverDownloaded, setNeverDownloaded] = useState(false);
  /**
   * The repository refused this workshop outright and this browser has never held a copy of it.
   *
   * NOT THE SAME FACT AS `neverDownloaded`, and the difference is the whole point. That one means
   * "this stage is blank because it was never read" and is a note on a form that is still the right
   * thing to fill in. This one means there is no record to fill in at all: the draft underneath was
   * fabricated by `ensureDraft` from the route id and the server has answered 404. See the load
   * effect's catch, and {@link neverReconciled}.
   */
  const [unopenable, setUnopenable] = useState(false);
  const [saving, setSaving] = useState(false);
  /**
   * Entities a row has been REMOVED from in this session. See decision 2 in the file header: it is
   * what arms `replaceCollections`, and it is a set of entity keys rather than a boolean so the
   * banner can name what a save is about to sweep.
   */
  const [removedFrom, setRemovedFrom] = useState<string[]>([]);

  /**
   * The local draft's own id, and the stage's sync state as the store holds it.
   *
   * `draftIdRef` holds the store's `localId`, which is stable even for a workshop created offline
   * whose URL will later carry a server id — every write below addresses the draft by it, so a
   * create landing mid-session cannot leave the page writing into a record that no longer exists.
   * A ref rather than state on purpose: nothing renders it, and re-rendering the whole stage form
   * to record an id it already had would be a wasted pass through 496 field descriptors.
   */
  const [stageSync, setStageSync] = useState<Pick<DwDraftStage, "dirtyAt" | "lastPushedAt" | "failure"> | null>(null);
  /** True between a keystroke and the debounced IndexedDB write that banks it. Milliseconds. */
  const [localPending, setLocalPending] = useState(false);
  /**
   * The local write was REFUSED, and the text on screen is the only copy of it that exists.
   *
   * ITS OWN STATE AND NOT `error`, for the same reason `neverDownloaded` is: every save writes over
   * `error` and `notice`, and this fact must survive all of them — it is true until a write actually
   * succeeds, not until the next thing happens. Cleared in exactly one place, the success arm of the
   * autosave's `write()`.
   */
  const [localWriteFailed, setLocalWriteFailed] = useState(false);
  /**
   * Where and when this stage is being recorded.
   *
   * Held by the page and not by each media field, because a stage has ONE answer and stage 13 has
   * eleven media fields. It is NOT a registry field and cannot be: `save_stage` drops any key the
   * registry does not declare and reports it in `droppedKeys`, so inventing one would put a banner
   * on every save telling the designer their answer was not stored — which would be true. It travels
   * instead on the media it stamps, which is where this repository already keeps provenance.
   */
  const [recordingPlace, setRecordingPlace] = useState<StageRecordingPlace>(EMPTY_RECORDING_PLACE);

  const draftIdRef = useRef<string | null>(null);
  /**
   * The stage exactly as this page last knew it to be BANKED — seeded from the draft, replaced by
   * every autosave that lands and by every push. Null until the form has been seeded at all.
   *
   * IT REPLACED A `hydrated` BOOLEAN, AND THE DIFFERENCE COST SEVEN FIELDS OF A REAL RECORD. The
   * flag was raised on the line after `seed(...)`, which is synchronous — so it was already true by
   * the time React rendered the seeded state, and the autosave effect it was supposed to suppress
   * fired on that very render. Merely OPENING a stage therefore banked it with `dirtyAt` set 800ms
   * later; `adoptServerStage` then correctly refused to fold the server's copy into what it had
   * been told was unsent fieldwork, and the next sync PUT the blank local copy over the real one.
   * A ref written during render cannot describe a render that has not happened yet. Comparing the
   * VALUE about to be written against the value already banked can, and it is also what stops a
   * no-op `setState` (`setRemovedFrom([])` after a successful save) re-marking a stage unsent.
   */
  const banked = useRef<StageSnapshot | null>(null);
  /**
   * The rows of this stage the REPOSITORY could be holding, by entity key — what makes a removal a
   * deletion rather than a change of mind.
   *
   * IT REPLACED A ROW COUNT, AND THE COUNT WAS DELETING WHOLE COLLECTIONS. `patchCollection`'s only
   * test was "the array came back shorter", which is true of an Add-then-Delete on one blank row —
   * the most ordinary correction this form offers, and one that changes nothing anywhere. That
   * wrote a phantom `removedFrom` entry which `putDraftStage` then UNIONS in and no later edit can
   * withdraw; `foldStageInto` reads the same list as an instruction, so the next online open
   * withheld the server's rows for that collection AND stamped `serverLoadedAt`, and the save after
   * that swept every one of them under a 200 reading "Stage saved — 0 added, 1 updated, 6 removed".
   *
   * A ref and not state, for the reason `banked` is: nothing renders it, and a re-render of 496
   * field descriptors to record a row key would be a wasted pass. It is rebuilt by `seed` on every
   * stage open and GROWS on every successful push — see {@link rowsTheServerCouldHold}, which owns
   * the rule and is where the argument for it is written down.
   */
  const serverHeld = useRef<ServerHeldRows>({});
  /** The newest pending write, so a navigation or a `pagehide` can run it immediately. */
  const flushRef = useRef<(() => Promise<void>) | null>(null);

  const stage: DwStage | null = useMemo(
    () => registry?.stages.find((candidate) => candidate.key === stageKey) ?? null,
    [registry, stageKey]
  );

  /**
   * The progress bar's number, computed on THIS DEVICE from the boxes as they stand.
   *
   * IT USED TO BE THE SERVER'S. The state was seeded from `draftStage.completeness` — the figure the
   * API returned at the last successful push — and only replaced inside `save`. Two things were
   * wrong with that, and both were most wrong for the person this form is written for:
   *
   *  - A stage that had never been pushed had no figure at all, so the whole "Required fields in
   *    this stage" panel did not render. A designer opening stage 5 in a courtyard filled in
   *    fourteen boxes with no indication that anything was being counted, and the panel appeared
   *    from nowhere on the first save.
   *  - After a save it FROZE. The designer kept typing, the answers kept landing, and the bar sat at
   *    "6 of 11" until the next Save — which is a control that looks broken, and a "Still needed"
   *    list naming fields that were filled in ten minutes ago is worse than no list, because it is
   *    read and acted on.
   *
   * Nothing about this number ever needed a server. `scoreStageData` holds the same rules as the
   * server's `stage_completeness`, and `isFilled` is already character-for-character its
   * `_is_filled`, so the answer is the one the API would give for the same data — it just arrives on
   * the keystroke instead of on the round trip, and it arrives with no signal at all. This is also
   * what Android's StageScreen has always done, scoring its live state through
   * `computeStageCompleteness`; the web was the only client whose bar could not move while the
   * designer worked.
   *
   * The server's own figure is NOT discarded — `markStagePushed` still banks it on the draft, which
   * is what "this is what has actually landed" needs to read. It simply no longer drives a bar that
   * is describing what is on screen right now.
   */
  /**
   * The stage's designer-defined questions, flat, in the order they are asked.
   *
   * Derived from the same `customSections` the form renders, so the bar counts exactly the questions on
   * screen. RETIRED FIELDS ARE IN THIS LIST and the scorer skips them itself — the list has to carry
   * them because it is also what the SAVE path validates against, where a retired field's stored answer
   * must round-trip rather than be dropped as an unknown key.
   */
  const customFields = useMemo(
    () => customFieldsForStage({ customSchemaVersion: heldCustomVersion, sections: customSections, fetchedAt: "" }, stageKey),
    [customSections, heldCustomVersion, stageKey]
  );

  const completeness: DwStageCompleteness | null = useMemo(
    // The designer's own required questions are counted here for the same reason the registry's are: this
    // is the number a designer reads in a courtyard to decide whether they can pack up, and a bar that
    // ignored half the form would say a stage was finished that Save will refuse.
    () => (stage ? scoreStageData(stage, singleton, collections, customFields, custom) : null),
    [stage, singleton, collections, customFields, custom]
  );

  /* ── Load ────────────────────────────────────────────────────────────── */

  /**
   * THE LOCAL DRAFT IS READ FIRST, AND THE SERVER IS A RECONCILIATION.
   *
   * The order is the whole point. Rendering from IndexedDB puts the stage on screen with no network
   * at all - which is the state this feature is written for - and it puts it on screen faster than a
   * request could when there IS a network. The server read that follows is folded in by
   * `adoptServerStage`, which refuses to touch a stage that has been edited locally since its last
   * push: a background answer overwriting unsent fieldwork with an older copy is the one failure an
   * offline-first form must never have.
   */
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setAwaitingServer(false);
    setNeverDownloaded(false);
    banked.current = null;
    // Cleared with `banked`, and for the same reason: this effect re-runs when the URL names a
    // DIFFERENT stage, and a set of row keys carried over from the stage just left would tell
    // `patchCollection` that rows of this one had been to the server. `seed` refills it below.
    serverHeld.current = {};
    // Reset with the two above and for the same reason: this effect re-runs when the URL names a
    // different workshop, and a server id carried over from the one just left would point the preview
    // panel's build at the wrong record. Back to what the route param alone can answer.
    setServerId(knownServerId(id));

    const seed = (draftStage: DwDraftStage | undefined) => {
      const data = stageDataOf(draftStage);
      const removed = draftStage?.removedFrom ?? [];
      // `?? {}` AND NOT A BARE READ. A stage record written by a build before custom sections existed
      // has no such key at all, and no migration rung was spent on adding one (an addition is handled
      // by unknown-key tolerance; a rung is owed only to a field that moves, is renamed or changes
      // meaning). So the default belongs at every read, and this is one of them.
      const held = draftStage?.custom ?? {};
      setSingleton(data.singleton);
      setCollections(data.collections);
      setCustom(held);
      // FROM THE SAME DRAFT RECORD AS THE BOXES ABOVE, in the same breath. Reading it from
      // anywhere else — a second fetch, a cached response — would let the value on screen and the
      // attribution under it come from two different reads of the stage, and the failure that
      // produces is a colleague's name under a number they never typed.
      setProvenance(draftStage?.provenance ?? null);
      // No completeness to seed: the bar is derived from the setters above, so it is already
      // right for this stage the moment the boxes are.
      setRemovedFrom(removed);
      // The baseline the autosave compares against, written in the same breath as the setters it
      // describes. Until this is set, no autosave can run at all — which is what makes seeding
      // unable to mark a stage edited. See {@link banked}.
      banked.current = { singleton: data.singleton, collections: data.collections, custom: held, removedFrom: removed };
      // WHICH OF THESE ROWS COULD BE ON THE SERVER — read off the same draft record the boxes were
      // just filled from, so the two can never describe different stages. Empty for a stage that has
      // neither been read from the repository nor pushed to it: nothing in it has ever crossed the
      // wire, so no removal out of it is a deletion anybody upstream needs to hear about.
      serverHeld.current = rowsTheServerCouldHold(draftStage);
      setStageSync({
        dirtyAt: draftStage?.dirtyAt ?? null,
        lastPushedAt: draftStage?.lastPushedAt ?? null,
        failure: draftStage?.failure ?? null
      });
    };

    (async () => {
      // Declared out here so the catch can say WHICH kind of blank the form is showing — it has to
      // know whether this device ever read the server's copy of the stage it is about to draw.
      let draft: DwDraft | null = null;
      try {
        const loaded = await loadRegistry();
        if (cancelled) return;
        setRegistry(loaded.registry);
        setRegistrySource(loaded.source);

        // A workshop created offline has no server record at all, so `ensureDraft` is only right for
        // an id the server issued; a local id must find its existing draft or there is nothing here.
        draft = isLocalWorkshopId(id) ? await loadDraft(id) : await ensureDraft(id);
        if (cancelled) return;
        if (!draft) {
          setError(
            "This workshop was created on another device or in another browser, and this one has no copy of it. " +
              "Open it on the device it was captured on, or wait until it has been sent."
          );
          setLoading(false);
          return;
        }
        draftIdRef.current = draft.localId;
        // THE ID THE PREVIEW PANEL ASKS THE SERVER ABOUT — resolved here because this is the one place
        // holding the draft, through the same `reportServerId` rule the report page and its history
        // view use. A `null` out of it is an ANSWER ("this workshop has not reached the repository
        // yet") and not a failure, which is why the panel is given something it can tell apart from
        // the "not read yet" it starts on.
        setServerId(reportServerId(id, draft));

        /*
          THE DEFINITION IS READ BEFORE THE FORM IS DRAWN, AND ITS FAILURE IS NOT THE STAGE'S FAILURE.

          Whatever this device already holds goes on screen first — the sections live on the draft record
          itself, so a tab that has been in a courtyard since it opened still draws the custom questions.
          The network read that follows refreshes them and says which of the two a designer is looking at.

          NOT AWAITED IN FRONT OF THE STAGE READ. `loadCustomDefinition` swallows its own failure into a
          source of "unknown", so the only thing a slow or dead definition request can cost is the custom
          block; the rest of the form is drawn from the stage read below either way. Awaiting it first
          would put a second round trip in front of every stage open on the fleet, including the twenty-two
          stages of every workshop that has no custom questions at all — which is nearly all of them.
        */
        // The linked Workshop, for the reference pickers' inline creates — see the state's own note.
        setLinkedWorkshopId(draft.header.workshopId ?? null);
        setHeldCustomVersion(draft.customSchemaVersion ?? "");
        setCustomSections(sectionsForStage(draft.customDefinition ?? null, stageKey));
        setCustomSource(draft.customDefinition ? "cache" : "unknown");
        void loadCustomDefinition(draft).then((held) => {
          if (cancelled) return;
          setCustomSource(held.source);
          if (!held.definition) return;
          setCustomSections(sectionsForStage(held.definition, stageKey));
          setHeldCustomVersion(held.definition.customSchemaVersion);
          // A fresh read of the definition is evidence about the server, so a stale marking recorded by
          // an earlier save no longer stands: the two digests are the same document again.
          setServerCustomVersion(null);
        });

        const localStage = draft.stages[stageKey];
        const spec = loaded.registry.stages.find((candidate) => candidate.key === stageKey);
        const canReconcile = Boolean(draft.remoteId && spec);
        /*
          HAS THIS BROWSER EVER READ THE SERVER'S COPY OF THIS STAGE?

          If it has, the local copy descends from the server's and rendering it immediately is the
          whole point of this feature: it draws with no network at all, and faster than a request
          when there is one.

          If it has NOT, a blank form is not offline-first — it is a lie. The stage may hold four
          rich-text narratives written up in an office; this device simply does not know. Putting
          that blank form on screen and letting the autosave start is exactly how the seven fields
          of stage 4 were erased: the local copy was banked as unsent fieldwork, the server read
          landed 1.2s later into a stage that now looked edited, the fold was refused, and the sync
          pass replaced the real answers with nothing. So the form is held back — for one request,
          only the first time, and only when there is a server copy to hold it back FOR.
        */
        const neverRead = (localStage?.serverLoadedAt ?? null) === null;
        const holdForServer = neverRead && canReconcile;
        if (!holdForServer) {
          seed(localStage);
          setError(null);
          setLoading(false);
        } else {
          setAwaitingServer(true);
        }

        // Nothing to reconcile against for a workshop the server has never seen.
        if (!canReconcile || !spec || !draft.remoteId) return;

        const stageData = await getDesignWorkshopStage(draft.remoteId, stageKey);
        if (cancelled) return;
        /*
          THE SERVER'S DIGEST, READ STRAIGHT OFF THE PAYLOAD AND NEVER STORED AS THOUGH IT DESCRIBED THIS
          BROWSER'S COPY. The route carries it beside the score for exactly this comparison, and its own
          comment says why: without it a client holding an older definition shows the server's higher
          `requiredTotal` for a stage it has never touched and its own lower one for a stage it has — two
          arithmetics in one screen with nothing to say why.

          It is set here as well as after a save so drift is noticed on ARRIVAL rather than only once the
          designer has typed and pressed Save. In the ordinary case the definition read above answers
          moments later and clears it, because the two are then the same document; what this catches is the
          case where the definition endpoint alone failed and the sections on screen came off disk.
        */
        setServerCustomVersion(stageData.customSchemaVersion ?? null);
        const merged = await adoptServerStage(draft.localId, spec, stageData);
        if (cancelled) return;
        const mergedStage = merged?.stages[stageKey];
        // Re-seed only when the fold actually took. A stage the designer has been editing keeps its
        // `dirtyAt`, and re-seeding it would throw away exactly what has not been sent yet.
        if (mergedStage && mergedStage.dirtyAt === null) {
          seed(mergedStage);
        } else {
          if (mergedStage) {
            setStageSync({
              dirtyAt: mergedStage.dirtyAt,
              lastPushedAt: mergedStage.lastPushedAt,
              failure: mergedStage.failure
            });
          }
          // A fold that could not be WRITTEN — the draft was discarded, or belongs to another
          // session on this laptop — still has to leave the form seeded, or a held-back stage
          // renders empty boxes with no baseline, which means no autosave and no explanation.
          if (!banked.current) seed(draft.stages[stageKey]);
        }
        setError(null);
        setAwaitingServer(false);
        setLoading(false);
      } catch (err) {
        if (cancelled) return;
        /*
          THE ONE FAILURE THAT MUST NOT LEAVE AN EDITABLE FORM ON SCREEN. Audit 2026-08-15 (MAJOR).

          Every other failure here means "the repository could not tell us about this stage", and the
          local copy is then the best and only answer — which is why the line below seeds it. A 404
          that is NOT "Unknown stage" means something else entirely: `load_workshop_or_404` answered,
          and it answered that this account may not open this workshop (or that no such workshop
          exists — it deliberately will not say which). There is no local copy in that case either,
          because the draft `ensureDraft` returned was fabricated by this browser from the route id.

          Seeding and clearing `loading` on that path is what made the defect expensive: the stage
          index sent a designer here with a convincing 0% workshop, the form rendered fully editable
          under an amber "this device has never downloaded this stage" notice, and a day's interview
          was typed into a record the server will refuse for ever. `neverReconciled` is what keeps a
          real workshop that an admin has since soft-deleted OUT of this branch — that draft holds
          fieldwork read from the repository and this screen is the only place it can be seen.
        */
        if (
          err instanceof ApiError &&
          err.status === 404 &&
          err.message !== "Unknown stage" &&
          (!draft || neverReconciled(draft))
        ) {
          setUnopenable(true);
          setAwaitingServer(false);
          setLoading(false);
          return;
        }
        // The reconciliation failed. Whatever this device holds is now the only thing there is, so
        // put it on screen — but say which of the two kinds of blank it is.
        if (!banked.current) seed(draft?.stages[stageKey]);
        setAwaitingServer(false);
        setLoading(false);
        // A stage already on screen from the local draft must not be replaced by an error. Only a
        // failure with nothing to fall back on is fatal; a lost connection is a note.
        //
        // `isUnreachable`, NOT `isTransient`: the latter counts every 5xx as "try again later", so
        // a stage the server answered about and then failed on was reported as "there is no
        // connection" — sending a designer to look at their signal while the real fault sat in the
        // response they never saw.
        if (isUnreachable(err)) {
          setNotice(
            "There is no connection, so this stage is showing what is saved on this device. Anything you change is kept " +
              "here and sent when the connection returns."
          );
          if (stageNeverRead(draft, stageKey)) setNeverDownloaded(true);
          return;
        }
        /*
          THE SERVER ANSWERED AND THE ANSWER MEANS "NOT NOW" — asked of `lib/failureTriage`, which is
          the one place in this client an HTTP number becomes a decision.

          IT USED TO TEST `err.status` AGAINST 500 HERE, in its own hand, which was one of the six
          private answers to "is this the network" that module was written to end. Two things changed
          by moving it, and both are the point of moving it: a 5xx arriving inside a wrapper is now
          recognised through its `cause`, and a 429 lands here too instead of falling through to the
          bare `err.message` below — the server asking for a moment is emphatically not a connection
          problem, and the sentence this branch writes is the true one for it.
        */
        const verdict = triageFailure(err);
        if (verdict.kind === "transient") {
          setError(
            `The repository could not send this stage: ${verdict.answered?.message ?? "The server did not say why."} ` +
              "The server was reached, so this is not a connection problem. What is shown below is the copy saved on " +
              "this device."
          );
          if (stageNeverRead(draft, stageKey)) setNeverDownloaded(true);
          return;
        }
        /*
          THE 404 IS THE SERVER'S TO EXPLAIN, AND IT HAS TWO DIFFERENT THINGS TO SAY.

          This branch used to answer every 404 with "That stage does not exist in this build's field
          registry" — the one cause it CANNOT be by the time the line runs. `spec` is resolved from
          `loaded.registry.stages` above and the effect returns early when it is missing, so the
          request is only ever made for a stage this build's registry declares.

          The route has exactly two 404s, with two readable and opposite details.
          `load_workshop_or_404` answers "Record not found" for a workshop that does not exist OR
          that this account may not open — deliberately not distinguishing the two. The unknown-stage
          branch answers "Unknown stage", which means the SERVER's registry is missing a stage this
          build has, i.e. the skew runs the other way from what the old sentence claimed.

          Both arrive intact in `ApiError.message`. Substituting a fixed sentence for them sent a
          designer who had simply not been granted access to look for an app update — on a metered
          rural connection, a reinstall — for a problem an admin fixes in one click. Only the
          "Unknown stage" case gets a gloss, and the gloss names the right side of the skew.
        */
        setError(
          err instanceof ApiError && err.status === 404
            ? err.message === "Unknown stage"
              ? `The repository does not recognise stage "${stageKey}". This browser's field list has it and the server's ` +
                "does not, so this app is running ahead of the repository — the stage cannot be opened until the server " +
                "catches up."
              : `That stage could not be opened: ${err.message} The workshop may not exist, or it may not be one this ` +
                "account has been given access to — an administrator can grant it."
            : err instanceof Error
              ? err.message
              : "Unable to load this stage"
        );
        if (stageNeverRead(draft, stageKey)) setNeverDownloaded(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id, stageKey]);

  /* Autosave to the local draft ------------------------------------------ */

  /**
   * Write the stage to IndexedDB a short moment after the designer stops typing.
   *
   * THE GUARD IS THE VALUE, NOT A FLAG. Without one, merely OPENING a stage would mark it edited and
   * queue a push of data the server already has - and after a reconnect the store would report a
   * fortnight of untouched stages as unsent work, which is the shape of warning people stop reading.
   * That was what the `hydrated` boolean was for, and it did not work: it was raised synchronously
   * on the line after the seeding `setState` calls, so it was already true on the render those
   * calls produced, and this effect ran on exactly the pass it was meant to skip. `putDraftStage`
   * sets `dirtyAt` unconditionally, so opening a stage on a slow connection banked a blank copy as
   * unsent fieldwork — which `adoptServerStage` then refused to overwrite, and the next sync pass
   * pushed over seven real fields that no `RecordRevision` records.
   *
   * Comparing what is about to be written against what is already banked cannot be fooled by
   * render timing, and it closes a second hole for free: a no-op `setState` — `setRemovedFrom([])`
   * after a successful save hands React a fresh array, which is never `Object.is`-equal — used to
   * re-dirty a stage within a second of it reaching the repository, so the "Sent to the repository"
   * readout was unreachable and every save was pushed twice.
   */
  useEffect(() => {
    if (!stage) return;
    const baseline = banked.current;
    // Null until `seed` has run. A stage that has not been seeded has nothing to compare against
    // and nothing worth writing — the form is not on screen yet.
    if (!baseline) return;
    const target = draftIdRef.current;
    if (!target) return;
    const snapshot: StageSnapshot = { singleton, collections, custom, removedFrom };
    if (sameSnapshot(snapshot, baseline)) {
      // Back to exactly what is already banked: the seeding pass, a no-op `setState` after a push,
      // or a designer who typed and undid it inside the debounce. Two things have to be undone
      // rather than merely skipped. A write still queued from the superseded value would bank text
      // that has since been taken back — the debounce's timer is cleared by this effect's cleanup,
      // but `flushLocal` would still find it on the next navigation. And `localPending` raised by
      // that run would keep the amber "Saved on this device only" chip on a stage with nothing
      // outstanding, for the rest of the session.
      flushRef.current = null;
      setLocalPending(false);
      return;
    }

    // `custom` travels ALONGSIDE `splitSingletons`' result and never through it: that function copies
    // across only the keys the registry declares and drops everything else, so a custom answer handed to
    // it would be silently gone before the write.
    const payload = { singletons: splitSingletons(stage, singleton), collections, custom, removedFrom };
    setLocalPending(true);
    /*
      ONE WRITE PER EDIT, WHOEVER ASKS FOR IT.

      Three things can run this: the debounce, `flushLocal` before a navigation or a push, and the
      `pagehide` handler. `flushLocal` deliberately does not cancel the timer — it cannot see it —
      so pressing "Save stage" within 800ms of the last keystroke used to flush the write, push the
      stage, clear `dirtyAt` through `markStagePushed`, and then have the ORIGINAL timer fire and
      call this same closure a second time, re-banking the stage with `dirtyAt: now` on a stage that
      had just landed. That is the second route to the same failure the snapshot comparison above
      closes: the amber chip back within a second of a successful save, "Sent to the repository at …"
      never reachable, and the next sync pass re-sending a stage the server already had.
    */
    let written = false;
    const write = async () => {
      if (written) return;
      written = true;
      const saved = await putDraftStage(target, stageKey, payload);
      /*
        A NULL IS A FAILED WRITE AND MUST NOT BE BANKED. Audit 2026-08-15 (MAJOR, frontend).

        `mutate` — which `putDraftStage` returns straight through — collapses three outcomes into
        one `null`: the draft was not found, the draft belongs to another session on this laptop,
        and the IndexedDB transaction ABORTED. That last one is not hypothetical: a readwrite `put`
        on a quota-full origin rejects by exactly that route, and so does a store deleted underneath
        us by "clear site data".

        The four lines below used to run unconditionally, and each of them told a lie about that
        null. `banked.current = snapshot` declares the typed text already saved, so the autosave
        effect's `sameSnapshot` short-circuit refuses to write it EVER AGAIN — the loss is permanent
        within the session, not merely delayed. `flushRef.current = null` throws away the retry.
        `setLocalPending(false)` puts out the amber "Saved on this device only" chip, which is the
        designer's only indication that anything is outstanding. And `save()` re-reads the DRAFT off
        disk rather than sending React state, so pressing "Save stage" afterwards uploads the stale
        copy and reports "Stage saved" over text that reached neither the disk nor the wire.

        On the failure path we therefore change NOTHING and say so. The baseline stays stale, which
        is what makes the next keystroke's snapshot differ from it and re-arm this effect; `written`
        is lowered so an already-armed `flushRef`/timer can genuinely retry rather than return at the
        latch; `flushRef` is re-pointed at this same closure because `flushLocal` nulls it before
        calling (so a retry through a navigation would otherwise disarm the next one).

        If you "simplify" this back to an unconditional bank, the failure mode is silent, permanent
        loss of a stage a designer watched turn from amber to nothing.
      */
      const decision = localWriteDecision(saved);
      if (!decision.bank) {
        // `written` is lowered so an already-armed timer or flush can genuinely retry rather than
        // return at the latch, and `flushRef` is re-pointed at this same closure because
        // `flushLocal` nulls it before calling — without that, a retry through a navigation would
        // disarm the next one.
        written = false;
        flushRef.current = decision.retry ? write : null;
        setLocalPending(decision.pending);
        setLocalWriteFailed(decision.failed);
        return;
      }
      // Banked before anything else: from here on this snapshot is what "already saved" means, and
      // a re-render carrying the same values must not write it a second time.
      banked.current = snapshot;
      flushRef.current = null;
      setLocalPending(decision.pending);
      setLocalWriteFailed(decision.failed);
      const next = saved!.stages[stageKey];
      if (next) setStageSync({ dirtyAt: next.dirtyAt, lastPushedAt: next.lastPushedAt, failure: next.failure });
    };
    flushRef.current = write;
    const timer = window.setTimeout(() => void write(), AUTOSAVE_MS);
    return () => window.clearTimeout(timer);
  }, [singleton, collections, custom, removedFrom, stage, stageKey]);

  /**
   * Run the pending write now. Awaited before every navigation and before every push.
   *
   * ANSWERS WHETHER THE DISK TOOK IT, because `save()` reads the draft back OFF DISK to build its
   * payload — so a flush that failed and a flush that succeeded produce two completely different
   * uploads, and only one of them contains what is on screen. `true` when there was nothing pending
   * (that is not a failure) or when the write landed; `false` only when the write was refused.
   */
  const flushLocal = useCallback(async () => {
    const write = flushRef.current;
    if (!write) return true;
    flushRef.current = null;
    await write();
    // `write` re-arms `flushRef` on failure and nulls it on success — reading it back is how this
    // learns the outcome without threading a return value through the closure the timer also calls.
    return flushRef.current === null;
  }, []);

  /**
   * The tab going away is the one moment a debounce cannot survive.
   *
   * `pagehide` rather than `beforeunload`: it fires on a mobile tab discard and on a back/forward
   * cache freeze, which `beforeunload` does not, and those are how a laptop closed in a courtyard
   * actually ends a session. `visibilitychange` covers the app being backgrounded before that, and
   * the cleanup covers a soft navigation to the next stage.
   */
  useEffect(() => {
    const flush = () => void flushRef.current?.();
    const onVisibility = () => {
      if (document.visibilityState === "hidden") flush();
    };
    window.addEventListener("pagehide", flush);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.removeEventListener("pagehide", flush);
      document.removeEventListener("visibilitychange", onVisibility);
      flush();
    };
  }, []);

  /*
   * THERE IS NO REF-OPTION PREFETCH HERE, AND THAT IS DELIBERATE.
   *
   * This page used to build a `refOptions` map on every stage open — `GET /design-workshops/{id}`
   * for the whole workshop plus one 100-row list request per external model the stage mentions, up
   * to five requests — thread it through `EntityForm`, `CollectionTable` and `FieldInput`, and never
   * read it: `FieldInput`'s REF branch hands the field to `StageReferenceSelect`, which fetches and
   * caches its own options. Every byte was discarded before it reached a control, on an offline-
   * created workshop the first request was a guaranteed 404 for ever, and its truncation notice
   * ("Only the first 100 of 2,340 artisans…") landed in the same `notice` slot as the offline
   * warning and replaced it.
   *
   * `StageReferenceSelect` owns REF options. Do not reintroduce a second source for them here.
   */

  /* ── Edit ────────────────────────────────────────────────────────────── */

  const patchSingleton = useCallback((key: string, value: DwValue) => {
    setSingleton((current) => ({ ...current, [key]: value }));
  }, []);

  /**
   * Several keys of the singleton in one commit.
   *
   * Choosing a reference writes the id and every display field it hydrates, and the identity-card
   * reader writes a confirmed number. Both are one act, and applying them as separate `onChange`
   * calls would let a render land between them showing a row that names a record whose name it has
   * not copied yet.
   */
  const patchSingletonMany = useCallback((values: Record<string, DwValue>) => {
    if (!Object.keys(values).length) return;
    setSingleton((current) => ({ ...current, ...values }));
  }, []);

  /**
   * One custom answer, into the stage's own `_custom` bucket.
   *
   * DELIBERATELY THE SAME SHAPE AS `patchSingleton` AND A SEPARATE FUNCTION FROM IT. One flat bucket
   * serves every custom section of the stage, because that is the shape of the row it is stored in and
   * because field keys are unique across the whole workshop — the server enforces that so two sections
   * on one stage cannot write into one key of one container. Writing into `singleton` instead would put
   * the answer where `splitSingletons` drops it.
   */
  const patchCustom = useCallback((key: string, value: DwValue) => {
    setCustom((current) => ({ ...current, [key]: value }));
  }, []);

  const patchCustomMany = useCallback((values: Record<string, DwValue>) => {
    if (!Object.keys(values).length) return;
    setCustom((current) => ({ ...current, ...values }));
  }, []);

  /**
   * Not a `useCallback`, and the comparison is made HERE rather than inside the `setCollections`
   * updater.
   *
   * A state updater has to be a pure function of its argument - React re-invokes it (twice in
   * development, and again whenever a render is discarded under concurrent rendering), so queueing
   * a second `setState` from inside one is a side effect in the one place that must not have any.
   * Reading `collections` from the render closure and deciding before either call is both correct
   * and easier to read than making the removal test idempotent enough to survive being run twice.
   */
  function patchCollection(entityKey: string, rows: DwRow[], removed?: DwRow) {
    /*
      A REMOVAL IS STILL DETECTED BY COUNT, BUT A REMOVAL IS NO LONGER AUTOMATICALLY A DELETION.

      The count is what says "this call is a removal at all" — the table's job is to hand back a
      list, and what that list implies for the save is this page's job. What the count CANNOT say is
      whether the row that went could exist in the repository, and taking "shorter" to mean "delete
      rows on the server" is what made Add-then-Delete on one blank row arm a sweep that soft-deleted
      every row of that collection this browser had never downloaded. The row itself answers it:
      `removalIsADeletion` asks whether it carries a server-minted `_entryId`, or a `_clientKey` this
      stage has ever been read with or pushed with. A row minted by `blankRow()` in this render
      answers no to both and its removal is not news for anybody.

      THE TEST IS HERE AND NOWHERE ELSE. `removedFrom` is the single input read by the save's
      `replaceCollections`, by the "you deleted rows this browser may not send" sentence, and by
      `foldStageInto`'s decision to withhold the server's rows for an emptied collection. Repeating
      the test at any of those would be three chances to disagree; keeping a phantom out of the list
      at the one place a removal is RECORDED closes all three at once.
    */
    const shorter = rows.length < (collections[entityKey] ?? []).length;
    if (shorter && removalIsADeletion(removed, serverHeld.current[entityKey])) {
      setRemovedFrom((keys) => (keys.includes(entityKey) ? keys : [...keys, entityKey]));
    }
    setCollections((current) => ({ ...current, [entityKey]: rows }));
  }

  /**
   * Navigate, after making sure the debounce has been banked AND after asking anything on the page
   * that is not durable.
   *
   * THE FLUSH IS WHAT REPLACED THE UNSAVED-CHANGES PROMPT - see decision 6 in the file header. The
   * prompt existed to stop work being lost; the draft store means leaving loses nothing, so the
   * honest behaviour is to flush and go. Awaiting the flush rather than firing it and hoping is the
   * part that matters: `putDraftStage` is a real IndexedDB transaction and a route change that raced
   * it would drop the last few seconds of typing on exactly the stage the designer had just
   * finished.
   *
   * AND THE PROMPT IS BACK FOR ONE THING, WHICH IS NOT THE STAGE'S OWN FIELDS. Four entities now
   * embed a repository record page, so this page can be hosting an `ArtisanForm`, `ToolForm`,
   * `ProductForm` or `ProcessForm` whose name, identity digits, attached files and captured fix live
   * in React state and uncontrolled DOM and are read only at that form's own submit. `flushLocal`
   * cannot bank any of it — it banks the DRAFT, and none of that is in the draft. So these two
   * buttons, which are the primary motion on this page, were an unguarded exit over work the page
   * had no way to save: pressing "14. Prototype iteration" with a half-typed artisan on screen lost
   * it in silence.
   *
   * `interceptLeave` is the same call the back arrow makes and returns true only when a form has
   * TAKEN RESPONSIBILITY — it is dirty and has put its own dialog on screen. With no record form
   * mounted, or with a clean one, nothing is registered that blocks and this behaves exactly as it
   * did. Asked BEFORE the flush, because a flush the designer then cancels out of is work done for a
   * navigation that did not happen.
   *
   * AND THE WHOLE OF THE EXIT IS HANDED OVER, FLUSH INCLUDED. `interceptLeave` banks what it refuses
   * so that "Discard" finishes it (see `UnsavedChangesGuard`'s `PendingLeave`), and what has to be
   * finished here is not `action` alone: a resumed "next stage" that skipped `flushLocal` would drop
   * the last few seconds of typing on the stage being left. So the flush and the action are ONE
   * closure, handed over whole and awaited on whichever path runs it — which also keeps "asked
   * before the flush" true, since the closure does not run at all while the prompt is up.
   *
   * AND THE FLUSH CANNOT SWALLOW THE NAVIGATION ON EITHER PATH, which is why the `catch` is there
   * rather than being tidied away. A REFUSED local write already answers `false` and has always
   * navigated anyway — the refusal is marked on the draft and `DraftSyncBanner` says so out loud,
   * while stranding the designer on a stage whose buttons have stopped responding would hide it. A
   * flush that THROWS is the same outcome arriving by a different route, so it is treated the same
   * way instead of skipping `action()`. It matters most on the BANKED path: that one runs long after
   * the press, from a form's "Discard", where a dropped rejection would leave the work discarded, the
   * prompt answered, the page exactly where it was, and nothing on screen to say why.
   *
   * `action` is a route push chosen by the button that called this ("previous stage" / "next
   * stage"), and it is why the act travels rather than being reconstructed at the other end: a
   * `router.back()` guessed inside the record form would land on the stage before this one.
   *
   * No second back control is added anywhere on this page: `e2e/back-control.spec.ts` asserts
   * exactly one arrow, because the opposite shipped four times.
   */
  const interceptLeave = useLeaveInterceptor();
  const leave = useCallback(
    async (action: () => void) => {
      const go = async () => {
        try {
          await flushLocal();
        } catch {
          // Nothing to add that the store has not already recorded — `putDraftStage` marks its own
          // failure (`noteStoreFailure`) and answers `false` for the ordinary refusal, which this
          // path treats identically. See the paragraph above for why the navigation still happens.
        }
        action();
      };
      if (interceptLeave(() => void go())) return;
      await go();
    },
    [flushLocal, interceptLeave]
  );

  /* ── Save ────────────────────────────────────────────────────────────── */

  /**
   * Bank the stage on this device, and send it if there is anywhere to send it to.
   *
   * THE LOCAL WRITE IS NOT CONDITIONAL ON THE NETWORK and is awaited first, so "Save stage" means
   * the same thing in a courtyard as it does in an office: the answers are on this laptop, durably,
   * before anything else is attempted. What varies is only whether they also reached the repository.
   *
   * `buildStageEntries` is imported from the draft store rather than written here, because the sync
   * pass builds exactly the same payload and `save_stage` keys its per-field errors by an entry's
   * INDEX IN THE ARRAY THAT WAS SENT. Two builders would be two orderings, and the error map decoded
   * against the wrong one puts every message after the first collection on the wrong row.
   */
  /**
   * Take everything a `save_stage` response says about the answers, whatever status code carried it.
   *
   * WHY IT IS A FUNCTION AND NOT TWO COPIES. Audit 2026-08-15 filed the same defect twice — once for
   * the strict pass losing the per-field `errors`, once for it naming no field — and they are one
   * defect: this block existed only on the 200 path. The 422 raised by `submit=true` carries an
   * IDENTICAL result object (routes/design_workshops.py:1133-1135 spreads the whole `result` under
   * `detail`, precisely so a client can say "the stage was written and 1 row removed, but it cannot
   * be submitted yet"), and the catch below read nothing out of it. `describeApiDetail` reads
   * `detail.message` alone, so the map never reached the screen and `ApiError.payload` — which has
   * held the whole parsed body all along — was the only route to it.
   *
   * THE FOUR PIECES OF STATE MOVE TOGETHER OR NOT AT ALL. `errors` and `unplaced` are the two halves
   * of one decode; `dropped` and `droppedCustom` are the same response's other two lists, kept apart
   * from each other for the reason set out below but always written in the same breath. A second
   * copy of this block is exactly how the 422 path came to be missing three quarters of it.
   */
  function applyStageResult(
    result: DwSaveResult,
    keys: DwRowKey[]
  ): { marked: number; unplacedLines: string[] } {
    /*
      RE-ADDRESSED FROM "THE ARRAY I SENT" TO "THE BOXES ON SCREEN", so a message lands on the box that
      produced it rather than in a banner nobody can act on — AND WHAT CANNOT BE RE-ADDRESSED IS
      RETURNED RATHER THAN DROPPED. `placeStageErrors` lives in the store beside `buildStageEntries`,
      which is the function that DEFINES the indices being decoded: two halves of one contract, in one
      place, and testable without a browser.
    */
    const { decoded, unplaced: unplacedLines } = placeStageErrors(result.errors, keys);
    setErrors(decoded);
    setUnplaced(unplacedLines);
    setDropped(result.droppedKeys ?? []);
    /*
      TWO LISTS, TWO SENTENCES, AND THEY ARE NEVER MERGED. `droppedKeys` means "this build sent a
      registry field the server has never heard of", which is a client/server version skew and the only
      drift signal this repository has. `droppedCustomKeys` means "the definition this browser holds
      names a question the server's copy does not", which is a definition that has been edited since
      this browser read it — a different fact, with a different remedy (reload, not report), and one
      that would otherwise fire the registry banner on every save of every workshop that has a custom
      section.

      THE DIGEST IS RECORDED WHETHER OR NOT ANYTHING WAS DROPPED, because the two are independent: a
      designer can add a question without invalidating any answer this browser holds, in which case
      nothing is dropped and the copy on screen is still short of a question that is being asked.
    */
    setDroppedCustom(result.droppedCustomKeys ?? []);
    setServerCustomVersion(result.customSchemaVersion ?? null);
    /*
      THE COUNT INCLUDES THE UNPLACEABLE REFUSALS TOO, OR AN ENTIRELY UNPLACEABLE SET READS AS A CLEAN SAVE.

      `decoded` holds only what could be attached to a box. When every refusal in a response is
      unplaceable, `decoded` is EMPTY — so the caller's gate fell through, no error was shown, and the
      save went on to `markStagePushed`, which stamps the stage as pushed and clears the unsent marks. A
      stage the server had partly refused was recorded as landed, and the refusal was reported nowhere at
      all. Both numbers are returned for the same reason the caller's sentence names both.
    */
    return { marked: Object.keys(decoded).length, unplacedLines };
  }

  async function save(submit: boolean) {
    if (!stage) return;
    const target = draftIdRef.current;
    setSaving(true);
    setError(null);
    setNotice(null);
    /*
      THE PREVIOUS SAVE'S MARKS COME OFF HERE, AND NOT ONLY ITS SENTENCE. Audit 2026-08-15 (MAJOR).

      `setError(null)` above cleared the banner and left `errors`, `unplaced`, `dropped` and
      `droppedCustom` standing, so every early return and every refused save below re-rendered the
      PREVIOUS response's red marks under the new one's message. A designer who fixed the two fields
      a 422 named, pressed Save again and was refused for a third field saw all three marked and no
      way to tell which of them the server had just objected to.

      They are reset together because they are one response: `placeStageErrors` writes the first two
      from the same map, and `dropped`/`droppedCustom` are the other two lists the same response
      carries. Anything that sets one of the four must set all four — see `applyStageResult`.
    */
    setErrors({});
    setUnplaced([]);
    setDropped([]);
    setDroppedCustom([]);
    /**
     * The row addresses of the payload this save built, hoisted out of the `try` SO THE CATCH CAN
     * SEE THEM. A 422 is answered with the same per-field map a 200 is, and re-addressing it needs
     * exactly the array the entries were built from.
     */
    let rowKeys: DwRowKey[] = [];
    try {
      /*
        A REFUSED LOCAL WRITE STOPS THE SAVE DEAD, because everything after this line is built from
        the draft READ BACK OFF DISK. Without the check, a failed flush meant `loadDraft` returned
        the copy from before the designer started typing, `buildStageEntries` built a payload out of
        it, the server took it, and the screen said "Stage saved" over a stage whose newest answers
        had reached neither the disk nor the wire — the worst outcome this page can produce, because
        it is the one that makes a designer close the tab.
      */
      if (!(await flushLocal())) {
        setError(
          "This browser refused to save this stage to its own storage, so there is nothing dependable to send. What is in " +
            "the boxes has not been kept on this device — copy it somewhere safe before leaving this page."
        );
        return;
      }
      const draft = target ? await loadDraft(target) : null;
      const draftStage = draft?.stages[stageKey] ?? emptyStage(stageKey);
      const sinceDirtyAt = draftStage.dirtyAt;
      // Read once, here, and handed to BOTH the payload's `emptiedEntities` and the acknowledgement
      // below, so the acknowledgement is judged against what this PUT actually carried. Reading it
      // a second time after the round trip would hand `markStagePushed` a list that already
      // contains the row deleted DURING the round trip — it would then read as "the server was
      // told about this" and be cleared, which is the deletion loss all of this exists to stop.
      // See {@link unsentAfterPush}.
      const sinceRemovedFrom = draftStage.removedFrom;

      // The strict pass, answered locally as well as remotely. `localStageCompleteness` mirrors
      // `stage_completeness` exactly, so a designer with no signal still gets a real answer to "is
      // this stage finished" instead of a control that does nothing until Tuesday.
      //
      // Scored off the DRAFT rather than off the live `completeness` memo, and the difference
      // matters: this figure describes the payload `buildStageEntries` is about to send, which is
      // what the sentences below claim things about. The memo describes the boxes on screen. They
      // agree in every ordinary case, and when they do not — a keystroke that lost the race with
      // `flushLocal` — the honest thing for a message about what was SAVED is the saved copy.
      //
      // THE CUSTOM QUESTIONS ARE RESOLVED FROM THE DRAFT AND NOT FROM `customFields` ABOVE, and the
      // difference is the same one this figure exists for: the memo describes what is on screen, the
      // draft describes what is about to be sent. They agree in every ordinary case; when they do not —
      // a definition refreshed between the flush and this line — the honest thing for a sentence about
      // what was SAVED is the saved copy's own question list.
      const localScore = localStageCompleteness(stage, draftStage, customFieldsFor(draft, stageKey));

      const remoteId = draft?.remoteId ?? (isLocalWorkshopId(id) ? null : id);
      const offline = typeof navigator !== "undefined" && navigator.onLine === false;

      if (!remoteId || offline) {
        setNotice(
          submit
            ? localScore.isComplete
              ? "Saved on this device. Every required field in this stage is filled in — checked against the field list this browser holds; the server will check it again when this sends."
              : `Saved on this device. ${localScore.requiredTotal - localScore.requiredFilled} required field(s) are still empty: ${localScore.missing.join(", ")}.`
            : !remoteId
              ? "Saved on this device. This workshop has not been created on the server yet — it is created, with everything in it, the moment there is a connection."
              : "Saved on this device. There is no connection, so it sends itself when one returns."
        );
        return;
      }

      const { entries, rowKeys: builtRowKeys, merged } = buildStageEntries(stage, draftStage);
      // Published to the hoisted binding the CATCH reads. A 422 is answered with the same per-field
      // map a 200 is, keyed by index into exactly this array — so the catch cannot re-address a
      // single refusal without it.
      rowKeys = builtRowKeys;
      if (!entries.length) {
        // `buildStageEntries` omits an empty singleton for a stage this device has never read from
        // the repository, so an empty entry list means there is genuinely nothing here to send —
        // and sending `{"data": {}}` for it would replace whatever the server holds with nothing.
        // Saying so is the honest answer; the alternative is "Stage saved — 0 added, 0 updated" on
        // a screen whose fields may not be empty at all on the server.
        setNotice(
          "There is nothing in this stage on this device to send. If this stage was filled in elsewhere, it has not been " +
            "downloaded here yet — open it again with a connection to read it."
        );
        return;
      }
      /*
        See decision 2 in the file header. Armed by a deletion — read off the DRAFT rather than off
        React state, so a deletion made in an earlier session still sweeps — AND by this browser
        having read the stage it is about to tell the server the exact contents of.

        THE SECOND HALF IS NEW AND IT WAS COSTING WHOLE ROWS. `replaceCollections:
        sinceRemovedFrom.length > 0` was the entire test, and the sweep it arms is scoped server-side
        by every entity the payload NAMES — so one row deleted on a stage this browser had never
        downloaded soft-deleted every row the office had written in that collection, and in every
        other collection the payload happened to mention. Measured on the running API: `removed=5`
        under a 200, for a single deletion. {@link stageSweep} carries the argument and the numbers,
        and is shared with the sync pass so the two payloads cannot disagree.
      */
      const sweep = stageSweep(stage, draftStage);
      const result = await saveDesignWorkshopStage(remoteId, stageKey, {
        entries,
        replaceCollections: sweep.replaceCollections,
        // The entities emptied on this device. Required, not decorative: the server sweeps only
        // what the payload names, and a collection whose LAST row was deleted contributes no
        // entries, so this is the only place it is named.
        emptiedEntities: sweep.emptiedEntities,
        submit
      });

      /*
        THE ROWS THIS PUT CARRIED ARE NOW ROWS THE SERVER COULD BE HOLDING, so deleting one of them
        later IS a deletion and must arm the sweep. Read off `draftStage`, which is the copy the
        payload was built from — not off React state, which may already hold a row typed during the
        round trip that this PUT did not carry.

        RECORDED HERE AND NOT AFTER `markStagePushed`, deliberately: the partial-refusal branch below
        RETURNS before the acknowledgement, and its own message says "everything else was saved" —
        the rows it accepted are up there whether or not this browser got as far as banking the push.
        Growing the set on a response the server refused outright cannot lose anything either; the
        cost of an over-wide set is one redundant `replaceCollections` over rows the payload carries,
        and the cost of a too-narrow one is a deletion that never happens. See
        {@link rowsTheServerCouldHold}.
      */
      serverHeld.current = rowsTheServerCouldHold(
        { serverLoadedAt: draftStage.serverLoadedAt, lastPushedAt: Date.now(), collections: draftStage.collections },
        serverHeld.current
      );

      /*
        RE-ADDRESSED FROM "THE ARRAY I SENT" TO "THE BOXES ON SCREEN", so a message lands on the box that
        produced it rather than in a banner nobody can act on — AND WHAT CANNOT BE RE-ADDRESSED IS
        RETURNED RATHER THAN DROPPED.

        This was ten lines here, ending `decoded[origin ? \`${origin.entityKey}[${origin.rowIndex}]\` :
        key] = fields`. That `: key` fallback was the whole defect: it filed the refusal under the
        SERVER's key, which has exactly the same `entity[n]` shape as a re-addressed one, so nothing
        downstream could tell a placed refusal from an unplaceable one. `collectionErrors` then matched it
        as a row index and handed `CollectionTable` an index it never renders — a refusal the server had
        acted on that no part of this page mentioned.

        It now lives in the store beside `buildStageEntries`, which is the function that DEFINES the
        indices being decoded. Two halves of one contract, in one place, and testable without a browser.
      */
      const { marked, unplacedLines } = applyStageResult(result, rowKeys);
      if (marked || unplacedLines.length) {
        setError(
          marked
            ? "Some answers were not accepted. The fields that need attention are marked below; everything else was saved."
            : "Some answers were not accepted, and this page could not tell which boxes they belong to. They are listed " +
              "below exactly as the repository reported them; everything else was saved."
        );
        return;
      }

      if (target) {
        const updated = await markStagePushed(target, stageKey, {
          completeness: result.completeness ?? null,
          sinceDirtyAt,
          // WHAT THE PAYLOAD CARRIED, NOT WHAT THE DRAFT WAS HOLDING. `sweep.emptiedEntities` is
          // empty on a stage this browser has never read, and handing `sinceRemovedFrom` here instead
          // would acknowledge a deletion that was never sent: `unsentAfterPush` would clear it, the
          // rows would stay alive in the repository, and nothing anywhere would still know a designer
          // had deleted them. See {@link stageSweep}.
          sinceRemovedFrom: sweep.emptiedEntities,
          // WHAT STOPS THE VERY NEXT SAVE FROM DELETING WHAT THIS ONE JUST PRESERVED. A merge push
          // leaves the server holding the union of its row and this browser's bucket, so this browser
          // still has not read the server's copy and must go on merging until it does. See
          // `markStagePushed`'s `mergedEntries`.
          mergedEntries: merged
        });
        const next = updated?.stages[stageKey];
        if (next) setStageSync({ dirtyAt: next.dirtyAt, lastPushedAt: next.lastPushedAt, failure: next.failure });
        // The push settled `removedFrom` in the store, so the banked baseline has to lose it too or
        // the very next render is "different from what is banked" and re-marks a stage that has
        // just landed as unsent. Left stale, the amber "Saved on this device only" chip came back
        // within a second of every successful save and the "Sent to the repository at …" readout —
        // the one thing a designer reads to decide whether they can pack up — was unreachable.
        //
        // EMPTIED HERE EVEN WHEN THE STORE KEPT THE FLAG, and that is not a leak: `putDraftStage`
        // UNIONS `removedFrom` on the way in, so a form that has forgotten a deletion can never
        // disarm one the draft is still holding. This state and the banked copy only have to agree
        // with each other.
        if (banked.current) banked.current = { ...banked.current, removedFrom: [] };
      }
      // An updater, not a bare `[]`: React bails out of a re-render for an identical value, and a
      // fresh empty array is never identical. See the note on the autosave effect.
      setRemovedFrom((keys) => (keys.length ? [] : keys));
      /*
        A DELETION THIS SAVE WAS NOT ENTITLED TO STATE, SAID OUT LOUD — see {@link stageSweep}.

        Without this sentence the page reports "Stage saved" over a row deletion that did not happen
        anywhere but here: the row is gone from this screen, it is still in the repository, and it
        still prints in the .docx a ministry is handed. It is the same fact Android puts on its
        workshop list as `unsentDeletions`, in the same words and with the same remedy — and the
        remedy names a SCREEN rather than a connection, because what is missing is a READ and no
        number of sync passes performs one.
      */
      const held = sweep.withheld.length
        ? ` You deleted ${sweep.withheld.length === 1 ? "a row" : "rows"} from ${sweep.withheld.join(", ")} on this ` +
          "device, and that deletion has NOT been sent: this browser has never read this stage from the repository, so " +
          "it cannot yet tell a row you deleted from one it has never seen. The deletion is remembered here and goes up " +
          "on the first save after this stage has been read — open it again with a connection. Everything you typed has " +
          "been saved."
        : "";
      setNotice(
        (submit
          ? "Stage saved and every required field is filled in."
          : `Stage saved — ${result.created} added, ${result.updated} updated${result.removed ? `, ${result.removed} removed` : ""}.`) +
          held
      );
      /*
        THE DOCUMENT PREVIEW REDRAWS ON THE SAVE, AND ONLY ON THE SAVE.
        `StageDocumentPreview` is built by the server from the record the server holds, so the save is
        the exact moment its answer can have changed — bumping this on a keystroke would spend a full
        document build on text the server has not got. Incremented rather than set to a boolean because
        the panel has to redraw when the SAME stage is saved twice running, which only a value that
        changes every time can express. It costs nothing while the panel is closed: it fetches on open.
      */
      setPreviewToken((current) => current + 1);
    } catch (err) {
      // A request that never reached a server is NOT a failed save: the stage is already on this
      // device and the sync pass will carry it. Saying "unable to save" here would be a lie that
      // makes a designer retype work that is sitting safely in front of them.
      //
      // `isUnreachable`, NOT `isTransient` — the split the report page's download handler already
      // makes, carried here. `isTransient` answers "is it worth retrying" and counts every 5xx as
      // yes, so a save the server had ANSWERED and refused (a lone surrogate in a name and a
      // non-finite decimal both 500 this endpoint) told the designer there was no connection, put
      // no error on screen, and left the queue re-sending the same rejection for ever.
      if (isUnreachable(err)) {
        setNotice(
          "Saved on this device. The repository could not be reached, so this stage sends itself when the connection returns."
        );
        return;
      }
      /*
        THE TWO ANSWERS A REACHED SERVER CAN GIVE, BOTH ASKED OF `lib/failureTriage` — the one place
        in this client where an HTTP number becomes a decision, and the module that ended six private
        copies of this question disagreeing with each other.

        THEY USED TO TEST `err.status` AGAINST 429 AND AGAINST 500, WRITTEN OUT HERE. The order is
        what preserves them exactly: `serverAskedForTime` is 408-or-429, and 408 has already been
        taken by `isUnreachable` above (the triage table calls a 408 `unreachable` — a proxy saying
        the request never completed decided nothing), so reaching it here means 429 and nothing else.
        Whatever the server answered that is still worth retrying is then `transient`, which is the
        5xx branch under its real name.

        THE SPLIT IS KEPT AND MUST BE. A 429 is a NOTICE — the work is banked, the queue carries it,
        nothing needs a person. A 5xx is an ERROR that names the stage, because the save was refused
        by a server that answered and retrying it unchanged will be refused again.
      */
      if (serverAskedForTime(err)) {
        setNotice(
          "Saved on this device. The repository asked for a moment before accepting more, so this stage sends itself shortly."
        );
        return;
      }
      const verdict = triageFailure(err);
      if (verdict.kind === "transient") {
        setError(
          `The repository refused to save ${stage.number}. ${stage.title}: ` +
            `${verdict.answered?.message ?? "The server did not say why."} It is safe on this device and ` +
            "nothing has been thrown away — but the server was reached, so this is not a connection problem and retrying " +
            "unchanged will be refused again. Check the answers named above, or report this stage."
        );
        return;
      }
      /*
        A 422 FROM `submit=true` IS A FULL RESPONSE, NOT A SENTENCE. Audit 2026-08-15 (MAJOR ×2).

        The comment that used to stand here claimed "its detail names the fields, and
        `describeApiDetail` has already turned it into a sentence". It had not, and could not:
        `describeApiDetail`'s object branch (lib/api.ts) reads `detail.message` and returns it,
        so the per-field map under `detail.errors` never reached this page. The designer was told
        "Some required fields are missing" over a form with nothing marked and no field named, on
        the one control whose entire purpose is to name them — while the previous save's marks, which
        this function now clears at the top, stayed on screen pretending to be this answer.

        WORSE, AND THE HALF THAT IS NOT ABOUT MESSAGES: this refusal is raised AFTER `save_stage` has
        committed. Rows were created, rows were updated, the sweep's soft-deletes landed and the
        workshop moved DRAFT→IN_PROGRESS. Reporting it as a bare failure told a designer that a
        deletion they had just made had not happened. The route spreads the whole result under
        `detail` for exactly this reason, so the 422 is handled with the SAME function the 200 is and
        the acknowledgement below can state what was written.

        `ApiError.payload` is the whole parsed body (lib/api.ts) — the only route to `detail`, since
        `message` has already been flattened. It is `unknown` by declaration and is narrowed here
        rather than cast: a body that is not the shape we expect must degrade to the sentence below,
        never throw inside a catch.
      */
      const refusal = stageRefusalResult(err);
      if (refusal) {
        const { marked, unplacedLines } = applyStageResult(refusal, rowKeys);
        const message = err instanceof Error ? err.message : "Some answers were not accepted.";
        const wrote = stageRefusalWroteCount(refusal)
          ? ` The rest of the stage WAS written — ${refusal.created ?? 0} added, ${refusal.updated ?? 0} updated${
              refusal.removed ? `, ${refusal.removed} removed` : ""
            } — so nothing you typed has been thrown away.`
          : "";
        setError(
          (marked
            ? `${message} The fields that need attention are marked below.`
            : unplacedLines.length
              ? `${message} They are listed below exactly as the repository reported them; this page could not tell which ` +
                "boxes they belong to."
              : message) + wrote
        );
        return;
      }
      setError(err instanceof Error ? err.message : "Unable to save this stage");
    } finally {
      setSaving(false);
    }
  }

  /* ── Render ──────────────────────────────────────────────────────────── */

  const stages = registry?.stages ?? [];
  const position = stages.findIndex((candidate) => candidate.key === stageKey);
  const previous = position > 0 ? stages[position - 1] : null;
  const next = position >= 0 && position < stages.length - 1 ? stages[position + 1] : null;

  /**
   * The per-row errors for one collection, indexed the way `CollectionTable` wants them.
   *
   * BOUNDED BY THE ROWS THAT ARE ACTUALLY ON SCREEN, which the unbounded version was not. It matched
   * `^entity\[(\d+)\]$` and wrote every index it found, including indices past the end of the table —
   * and `CollectionTable` looks its errors up BY ROW, so an entry under an index it never renders is
   * simply never read. Nothing was wrong with the arithmetic; the message just went nowhere.
   *
   * The index can outlive the row it named even when the decode placed it correctly: `errors` is state
   * that survives until the next save, and the rows underneath it are edited freely in between. Delete
   * the row a refusal was drawn on and the message vanishes silently; delete a row ABOVE it and every
   * index below shifts, so the surviving message would be drawn against a different row's boxes. Both
   * are answered the same way — anything that cannot be placed against the CURRENT rows is handed to
   * {@link stranded} and said out loud instead.
   */
  function collectionErrors(entity: DwEntity): Record<number, FieldErrors> {
    const rowCount = (collections[entity.key] ?? []).length;
    const out: Record<number, FieldErrors> = {};
    for (const [key, fields] of Object.entries(errors)) {
      const match = new RegExp(`^${entity.key}\\[(\\d+)\\]$`).exec(key);
      if (match && Number(match[1]) < rowCount) out[Number(match[1])] = fields;
    }
    return out;
  }

  /**
   * Refusals that no box on this screen will draw, as `scope.field: message` lines.
   *
   * THE SECOND HALF OF THE SAME HONESTY. `unplaced` catches what the decode could not re-key at save
   * time; this catches what has become undrawable SINCE — a row deleted under a marked error, a scope
   * naming an entity this stage does not declare, a bare key that is neither the singleton nor
   * `_custom`. Derived from the current rows rather than recorded at save time, because that is the
   * only thing that can answer "is there a box for this right now".
   *
   * Every refusal in `errors` is therefore either marked on a box or listed in the banner, and the two
   * are decided by the same predicate — so a change to one cannot open a gap in the other.
   */
  const stranded = useMemo(
    () => strandedRefusals(errors, stage?.entities ?? [], collections),
    [errors, collections, stage]
  );

  /**
   * How many entries one save of this stage would carry — the unit `MAX_STAGE_ROWS` is counted in.
   *
   * ── WHY IT IS COMPUTED HERE AND NOT INSIDE EACH TABLE ──────────────────────────────────────────
   *
   * `CollectionTable` draws the 500-entry cap sentence, and until now it thresholded on the length of
   * the one array it had been handed. The cap is not a bound on that array: `StageSaveIn._bound_rows`
   * refuses on `len(entries)` for the WHOLE stage, so the arrangement in which it bites hardest is the
   * one in which no single list comes near it — three collections of 200 rows is 600-odd entries, every
   * save 422s over the entire stage, and no table reached the threshold that would have said so. The
   * notice was silent on precisely the workshop it existed for.
   *
   * THIS PAGE IS THE ONLY THING THAT CAN COUNT IT. It owns `collections` (every list), `singleton` (the
   * stage's own fields) and `custom` (the designer's own questions), which are the three things
   * `buildStageEntries` builds its three arms from. So the arithmetic is done once, here, and the one
   * answer is handed to every table — see {@link stageEntryBudget} for what each arm contributes and
   * for the two entries it declines to be certain about.
   *
   * `splitSingletons` AND NOT THE FLAT `singleton` MAP, because the singleton arm is per ENTITY: it asks
   * "is anything in THIS entity answered", and the flat map is every singleton entity's keys merged
   * together. It is the same call `flushLocal` banks with (see the payload it builds), so the count
   * describes the same per-entity shape the draft holds and the save reads back out of it.
   *
   * MEMOISED ON THE THREE FORM VALUES, because the result is a prop on every `CollectionTable` and a
   * new object identity per render would defeat nothing today but would be a new reason for the flagship
   * workshop's 244 rows to re-render on a keystroke in an unrelated box the day any of these tables is
   * memoised.
   */
  const stageEntries = useMemo(
    () =>
      stageEntryBudget(
        stage?.entities ?? [],
        collections,
        stage ? splitSingletons(stage, singleton) : {},
        custom
      ),
    [stage, collections, singleton, custom]
  );

  /**
   * What this stage's state actually is, in a sentence, with a static counterpart to every colour.
   *
   * Three states and they are not interchangeable: nothing outstanding, waiting for a connection,
   * and refused. A single "unsaved" chip collapsed all three, and the one it hid was the third.
   */
  /**
   * The provenance handed to every media upload on this stage.
   *
   * Memoised on the four values it is built from rather than rebuilt each render: it is a dependency
   * of the media fields' own upload callback, and a new object identity on every keystroke in a
   * neighbouring text box would rebuild that callback and re-run the effect that drains finished
   * files — which is the effect that must not fire twice for one file.
   */
  const capture = useMemo(
    () => ({
      location: recordingPlace.location,
      recordedAt: recordingPlace.recordedAt,
      recordedTimezone: recordingPlace.recordedTimezone,
      point: recordingPlace.point
    }),
    [recordingPlace.location, recordingPlace.recordedAt, recordingPlace.recordedTimezone, recordingPlace.point]
  );

  /**
   * Where the custom block is drawn, expressed as two anchors so no registry entity is reordered.
   *
   * `customAfterKey` is the LAST singleton, which is where the scorer stops counting the stage's own
   * fields; `customBeforeKey` is the first entity of a stage that declares no singleton at all. Exactly
   * one of the two is non-null on a stage that declares anything, and both are null on a stage that
   * declares nothing — which the render handles with a third position.
   */
  const customAfterKey = useMemo(() => {
    const singletons = (stage?.entities ?? []).filter((entity) => entity.cardinality === "SINGLETON");
    return singletons.length ? singletons[singletons.length - 1].key : null;
  }, [stage]);
  const customBeforeKey = useMemo(
    () => (customAfterKey === null ? (stage?.entities[0]?.key ?? null) : null),
    [customAfterKey, stage]
  );

  /**
   * Is the definition on screen not the one the server is validating against?
   *
   * A COMPARISON OF TWO DIGESTS AND NOT A FLAG SET BY A DROP. They are independent facts: a designer can
   * ADD a question, which invalidates nothing this browser holds and drops no key, and the copy on screen
   * is still short of a question the stage is now being asked. `serverCustomVersion` is null until a save
   * has told us what the server used, and a null must never read as stale — a browser with no evidence of
   * drift may not claim any.
   */
  const customStale = serverCustomVersion !== null && serverCustomVersion !== heldCustomVersion;

  /**
   * The custom block, built once and placed by the two anchors above.
   *
   * One element rather than the component repeated at three call sites: three copies would be three
   * chances for one of them to drift out of step with the others, and the props include the two pieces of
   * state — the source and the staleness — whose whole job is to be said consistently.
   */
  const customBlock = (
    <CustomSectionsForm
      sections={customSections}
      values={custom}
      onChange={patchCustom}
      onPatch={patchCustomMany}
      workshopId={id}
      stageKey={stageKey}
      // The server files every custom coercion failure under the one reserved key, keyed by the
      // designer's own field key — see `save_stage`, which uses `CUSTOM_ENTITY_KEY` for exactly this.
      errors={errors[CUSTOM_ENTITY_KEY]}
      disabled={saving}
      source={customSource}
      stale={customStale}
      // The readiness screen builds its links against the same synthetic entity key the adapter renders
      // under, so a designer sent here for a missing custom answer lands on the box itself rather than on
      // the top of the stage.
      focus={focus}
    />
  );

  const unsent = (stageSync?.dirtyAt ?? null) !== null || localPending;
  const heldBack = stageSync?.failure && !stageSync.failure.permanent ? stageSync.failure.message : null;
  const refused = stageSync?.failure?.permanent ? stageSync.failure.message : null;

  if (unopenable) {
    /*
      A DEAD END, BEFORE ANY BOX EXISTS TO TYPE IN — the twin of the stage index's own. Returning
      early is the fix: an editable form beside a red line was read as a glitch, and the day's
      interview went into a draft the repository will refuse for ever. The wording stays ambiguous
      between "no such workshop" and "not yours", because `load_workshop_or_404` is.
    */
    return (
      <>
        <PageHeader
          title="Stage"
          description="This stage could not be opened."
          icon={<Layers className="h-5 w-5" aria-hidden />}
          actions={
            <Link href="/design-workshops" className="field-button-secondary">
              All design workshops
            </Link>
          }
        />
        <section className="panel grid gap-3 p-4">
          <p className="text-sm font-medium text-ink-900">
            There is no design workshop at this address that this account can open.
          </p>
          <p className="text-sm leading-6 text-ink-700">
            Either no such workshop exists, or it belongs to another designer and has not been shared with you. No form
            is shown because nothing typed into one could be saved anywhere. If a colleague sent you this link, ask them
            to add you as a viewer of their workshop — an administrator can also do it — and then open the link again.
          </p>
        </section>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title={stage ? `${stage.number}. ${stage.title}` : "Stage"}
        description={stage?.purpose}
        icon={<Layers className="h-5 w-5" aria-hidden />}
        actions={
          <Link href={`/design-workshops/${id}`} className="field-button-secondary">
            All stages
          </Link>
        }
      />

      {localWriteFailed ? (
        /*
          FIRST OF ALL THE BANNERS, ABOVE `error`, because it is the only one that says the text on
          screen exists nowhere else. Everything below this line assumes the draft on disk is a
          faithful copy of the form; when this is showing, it is not.

          The remedy names a SCREEN action a designer can actually take — copy the text out — rather
          than "try again", because the two reachable causes (a full origin quota, a store cleared
          under the tab) are both ones a retry cannot clear on its own.
        */
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm leading-6 text-red-700">
          This browser refused to save this stage to its own storage, so what is in the boxes below is the only copy of
          it. It has NOT been kept on this device and it has NOT been sent. Do not close this tab: copy anything you have
          just typed somewhere safe, then check that this browser is not out of storage and is not set to clear site data
          on close.
        </div>
      ) : null}
      {/*
        THE OUTCOME OF A SAVE, IN TWO REGIONS THAT ARE MOUNTED FROM FIRST PAINT.

        These two sentences are the whole account of the most consequential act on this screen. `error`
        is every way a save can be refused — a 422 naming fields, a 5xx from a server that answered, a
        stage that could not be read at all — and `notice` is every way one can succeed or be banked
        ("Stage saved — 3 added, 1 updated", "Saved on this device", a deletion this save was not
        entitled to send). Neither had a live role, and there is no toast on this page and no other
        announcement anywhere in this file, so a designer who pressed Save with a keyboard or a screen
        reader was told NOTHING AT ALL: not that the stage landed, not that it was refused, not which
        boxes to go and fix. The refusal is the one that made it expensive — a red banner drawn above a
        form the page does not scroll to reads exactly like a button that did nothing, and the reflex is
        to press it again.

        THE TWO TREATMENTS ARE CHOSEN BY MEANING (§12.11), NOT BY SYMMETRY. `error` is a failure the
        designer has to act on and it is worth interrupting a reader for, so `role="alert"`, assertive.
        `notice` is a report that something they asked for happened — a receipt — so `role="status"`,
        polite, and it must never cut across whatever they are reading. This is the same pair, in the
        same words, that `design-workshops/[id]/page.tsx` uses for its submit `problem`/`outcome`.

        MOUNTED BEFORE THEY HAVE ANYTHING TO SAY, WHICH IS THE HALF THAT ACTUALLY DELIVERS THE FIX.
        Assistive technology only announces mutations inside a region that ALREADY EXISTED; a region
        created together with its first message is silently dropped by most screen readers, so
        `{error ? <div role="alert">…</div> : null}` would have looked like a fix and said nothing. They
        are therefore ONE element each, always rendered, with the CLASS swapped: `sr-only` when empty,
        which is absolutely positioned and 1×1, so it stays in the accessibility tree and contributes no
        box and no `mb-4` to this page's flow. Swapping in `hidden` (`display: none`) or remounting the
        node would take the region out of the tree and put the defect straight back. `Toast`'s
        always-present viewport is the precedent; `SubmissionCard` and `EntityForm` are this session's.

        TWO REGIONS AND NOT ONE, because `save()` clears both and then sets exactly one: folding them
        together would mean a refusal and a receipt share a region, and the polite half would be read in
        the assertive half's voice — or, worse, one would replace the other mid-sentence.

        THE REST OF THE BANNERS BELOW ARE DELIBERATELY NOT LIVE REGIONS. They describe standing state
        this page draws on arrival — a stale registry, a stage never downloaded, a failure the sync pass
        recorded — not the outcome of an act just performed, and eight assertive regions on one page is a
        screen reader that cannot be listened to.
      */}
      <p
        role="alert"
        aria-live="assertive"
        className={error ? "mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" : "sr-only"}
      >
        {error}
      </p>
      <p
        role="status"
        aria-live="polite"
        className={
          notice ? "mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700" : "sr-only"
        }
      >
        {notice}
      </p>
      {registryNotice ? (
        /*
          Which field list a designer is looking at is not a detail. A registry that predates a
          deploy simply lacks a field added last week, and saying so is the difference between "this
          stage does not ask for that" and "this browser has not been told about it".

          THIS USED TO BE `registrySource === "cache"` WITH THE SENTENCE INLINE, AND THAT GATE WAS
          THE RAREST OF THE THREE WAYS A BROWSER GETS BEHIND. Audit 2026-08-15 (LOW, frontend).
          `"cache"` is reachable only when the network actually failed; the ordinary way to be stale
          is `"memory"` — `fetchStageRegistry` serves this tab's module cache without a request on
          every call after the first, and nothing in the frontend revalidates. A tab open across a
          deploy therefore drew the old field list and said nothing at all. Both states now speak,
          in `lib/registryProvenance`, which is where the wording and the decision live so that a
          state no browser can be put into deliberately is still exercised by a spec. Do not put a
          `source ===` comparison back in this render.
        */
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
          {registryNotice}
        </div>
      ) : null}
      {neverDownloaded ? (
        // BLANK BECAUSE UNREAD IS NOT BLANK BECAUSE EMPTY, and a form cannot show the difference on
        // its own. Without this sentence a designer standing in a village looks at seven empty
        // boxes on a stage that was written up in the office and concludes the work was lost.
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          This device has never downloaded this stage from the repository, so what is below is empty because it has not been
          read — not because the stage is empty. Anything you fill in here is kept on this device and added to whatever the
          repository already holds; nothing you leave blank will overwrite an answer recorded elsewhere.
        </div>
      ) : null}
      {refused ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{refused}</div>
      ) : null}
      {heldBack ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">{heldBack}</div>
      ) : null}
      {unplaced.length || stranded.length ? (
        /*
          RED, NOT AMBER, AND IT IS NOT THE SAME BANNER AS `dropped` BELOW.

          `dropped` is a version skew — the server did not RECOGNISE a field — and its remedy is to report
          the build. This is the repository REFUSING an answer it understood perfectly well, which is the
          designer's to correct; drawing it in the drift colour would file a refusal under "someone else's
          problem". It is red for the same reason `error` and `refused` are.

          IT PRINTS WHAT THE SERVER SAID, VERBATIM, because that is the only thing left that can act as the
          address. The message is the sole remaining clue about which answer was refused once the row is
          gone, and paraphrasing it would take that away too.
        */
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          <p>
            The repository refused {unplaced.length + stranded.length} answer
            {unplaced.length + stranded.length === 1 ? "" : "s"} that this page cannot mark against a box on screen — the
            row it named is no longer here, or it was reported against a position this page did not send. Everything else
            was saved. {unplaced.length + stranded.length === 1 ? "It is" : "They are"} listed here exactly as the
            repository reported {unplaced.length + stranded.length === 1 ? "it" : "them"}:
          </p>
          <ul className="mt-1 list-disc pl-5">
            {[...unplaced, ...stranded].map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
          <p className="mt-1">
            Save this stage again to have the repository check it once more. If the same answers come back refused, report
            this stage.
          </p>
        </div>
      ) : null}
      {dropped.length ? (
        // amber-100 / amber-800 are the palette's tinted-card pair; amber-50 and amber-200 come
        // from stock Tailwind and do not pair correctly with them.
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          The server did not recognise {dropped.length} field{dropped.length === 1 ? "" : "s"} this page sent, so
          {dropped.length === 1 ? " it was" : " they were"} not stored: {dropped.join(", ")}. This build is running ahead of
          the server&apos;s field list — report it before relying on those answers.
        </div>
      ) : null}
      {droppedCustom.length ? (
        // A SEPARATE BANNER FROM THE ONE ABOVE, AND IT SAYS A DIFFERENT THING. That one is about the app
        // being ahead of the server and asks for a report; this one is about the DEFINITION having been
        // edited since this browser read it, and the way out is a reload. Folding the two lists together
        // would fire the registry-drift sentence on every save of every workshop that has a custom
        // section, and the people who read that banner would learn to ignore the one message that matters.
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          The definition this browser holds for this workshop names {droppedCustom.length} question
          {droppedCustom.length === 1 ? "" : "s"} the server&apos;s copy does not, so
          {droppedCustom.length === 1 ? " it was" : " they were"} not stored: {droppedCustom.join(", ")}. This
          workshop&apos;s own questions have been edited since this browser last read them — reload this stage
          with a connection to pick up the current set.
        </div>
      ) : null}

      {/* SAID HERE AND NOT ONLY ON THE STAGE LIST, because this page is reachable without passing
          through it: the previous/next controls at the foot of this form walk straight from stage 11
          to stage 12 to stage 13, and a stage URL is a link a designer can be sent. `PageHeader`
          takes a plain string title, so the pill goes above the note rather than beside the heading;
          the handset says the same thing as "Stage 12 of 22 · optional" in its own header. Until this
          existed, the ONLY place the web admitted stage 12 was optional was the stage-list pill. */}
      {stage?.optionalStage ? (
        <p className="mb-4 flex flex-wrap items-center gap-2 text-sm leading-6 text-ink-muted">
          <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs font-medium text-ink-700">Optional stage</span>
          A workshop that leaves this stage empty still counts as complete.
        </p>
      ) : null}

      {stage?.notes ? (
        <p className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-muted">
          {stage.notes}
        </p>
      ) : null}

      {/*
        THE DOCUMENT, BESIDE THE FORM THAT FEEDS IT.

        Placed ABOVE the form rather than below it, which is a deliberate reading of what a designer
        does with it: they open it, see how the stage prints, close it, and then type. Below a form
        that runs to forty boxes it would be a panel nobody scrolls to — the same argument
        `ReviewEditPanel` makes for putting its error banner immediately above the buttons rather
        than at the top of a twelve-box panel.

        AFTER EVERY BANNER, AND THAT ORDERING IS NOT COSMETIC EITHER: everything above this line either
        says something about whether the designer's work is safe — refused answers, a deletion that was
        not sent, a stage this device has never read, a browser that would not write to its own disk — or
        says what this stage asks for and whether it is optional. None of it may be pushed below a
        collapsed disclosure about how the stage will look in print.

        IT USED TO SIT ABOVE `error`, `notice` AND `registryNotice`, DOING THE OPPOSITE OF THE PARAGRAPH
        ABOVE. The disclosure holds its own open state, so a designer who had opened it once was then
        refused a save — a 422 from "Save and check required fields" — and the red banner rendered
        BELOW a panel whose own header warns that it loads every stage and rasterises the figures, i.e.
        one screen tall or more. The page does not scroll to the banner. The save refusal looked like
        nothing had happened. The two regions above are now announced as well, but the ordering is the
        half that serves a sighted designer and it has to be right on its own.

        THE ID IT IS GIVEN IS THE ONE THE SERVER KNOWS, NOT THE ONE IN THE URL. This passed
        `workshopId={id}` and `localOnly={isLocalWorkshopId(id)}`, both read straight off the route, so
        a workshop created with no signal and since synced was told for ever that there was "nothing to
        draw until this workshop has synced": the browser is still on the `dwlocal-…` address the
        workshop was created under, and nothing lifted the draft's `remoteId` out of the load effect.
        `undefined` — the draft not read yet — is passed on as the panel's `null`, which is its "do not
        claim either way": neither of its two sentences is true of a workshop nobody has looked up. The
        `?? id` fallback is never fetched against, because it is reached only where `localOnly` is not
        false.
      */}
      <StageDocumentPreview
        workshopId={serverId ?? id}
        stageTitle={stage?.title ?? ""}
        refreshToken={previewToken}
        localOnly={serverId === undefined ? null : serverId === null}
      />

      {loading ? (
        <div className="panel p-4 text-sm text-ink-700">
          {awaitingServer
            ? // Said plainly, because the wait is deliberate and only happens once per stage per
              // device: this browser has no copy of this stage, and drawing an empty form before
              // the repository has answered is what let a blank copy be saved over a full one.
              "Reading this stage from the repository. This device has no copy of it yet, so the form is held back until the repository has answered — it opens instantly every time after this, with or without a connection."
            : "Loading this stage…"}
        </div>
      ) : !stage ? (
        <div className="panel p-4 text-sm text-ink-700">
          This build&apos;s field registry has no stage called “{stageKey}”.
        </div>
      ) : (
        <div className="grid gap-5">
          {/* First, because it governs every file the stage uploads and a designer who captures it
              after attaching thirty photographs has stamped none of them. */}
          <StageRecordingPlaceCard value={recordingPlace} onChange={setRecordingPlace} disabled={saving} />

          {/*
            STAGE 9 ONLY, AND ABOVE THE BOXES IT IS ABOUT.

            This is the one stage whose whole job is to draw conclusions from another stage's rows,
            and until now nothing compared the two: a designer typed a price band into the table
            below having collected twenty-three price expectations in stage 8, and no part of the
            system ever put the two numbers side by side.

            It sits BEFORE the entity forms because its first two blocks are cautions and
            unsupported claims — "these figures all come from one respondent group", "no respondent
            said anything resembling this SWOT point" — and the judgement they qualify is the one
            the designer is about to type. Below four collection tables they would be scrolled past.

            It reads `collections` and never writes it. The panel cannot edit stage 9, offers no
            control that fills a field in, and takes no callback that could: the declared bands and
            SWOT stay exactly as typed, and the arithmetic is shown beside them.
          */}
          {stageKey === ANALYSIS_STAGE ? (
            <MarketFindingsPanel workshopId={id} collections={collections} />
          ) : null}

          {/*
            THE SAME INSTRUMENT ON STAGE 17, AND THE HANDSET HAS HAD IT ALL ALONG.

            `cost_integrity.py` adds up the material and labour lines under each cost sheet and holds
            them against the subtotals typed into the header, and `DwFindingsPanel.kt` prints the
            result on every handset. The browser had no port and no panel, so a designer typing a
            material subtotal of ₹1,560 with lines that come to ₹1,650 was warned on a phone and not
            on a laptop — about the same workshop, on the figure the report prints into a document
            submitted to a Development Commissioner's office.

            BEFORE the entity forms, for the same reason the market panel is: what it says qualifies
            the figures the designer is about to type, and below four collection tables it would be
            scrolled past.

            It reads `collections` and never writes it. There is no control on it that fills a field
            in and it takes no callback that could — a subtotal decided in the room stays exactly as
            it was typed, and the arithmetic is shown beside it.
          */}
          {stageKey === COSTING_STAGE ? (
            <CostFindingsPanel workshopId={id} collections={collections} />
          ) : null}

          {/*
            THE LINKED WORKSHOP, PUT WITHIN REACH OF THE REFERENCE PICKERS FIVE LEVELS DOWN.

            A picker that creates a repository record inline seeds the form with the sitting it was
            documented at, so the record lands inside the very list it was created from — see
            `LinkedWorkshop` for why that is a correctness rule for the five WORKSHOP-scoped REF
            fields and not a convenience. It is a context rather than a prop because every component
            between here and `StageReferenceSelect` already takes a `workshopId` meaning THIS page's
            design-workshop id, and threading a second id of the same name through the same four
            signatures is how the two come to be swapped.
          */}
          <LinkedWorkshopProvider workshopId={linkedWorkshopId}>
          {stage.entities.map((entity) => (
            <Fragment key={entity.key}>
              {entity.key === customBeforeKey ? customBlock : null}
              {entity.cardinality === "SINGLETON" ? (
                <EntityForm
                  entity={entity}
                  data={singleton}
                  onChange={patchSingleton}
                  onPatch={patchSingletonMany}
                  workshopId={id}
                  errors={errors[entity.key]}
                  disabled={saving}
                  stageKey={stageKey}
                  capture={capture}
                  focus={focus}
                  provenance={provenance?.singleton}
                />
              ) : (
                <CollectionTable
                  entity={entity}
                  rows={collections[entity.key] ?? []}
                  onRowsChange={(rows, removed) => patchCollection(entity.key, rows, removed)}
                  workshopId={id}
                  errorsByIndex={collectionErrors(entity)}
                  disabled={saving}
                  stageKey={stageKey}
                  capture={capture}
                  focus={focus}
                  provenance={provenance?.collections?.[entity.key]}
                  /*
                    THE SAME BUDGET OBJECT TO EVERY TABLE, which is the whole point of computing it here:
                    the 500 is one allowance shared by every list on this stage, so two tables reading two
                    different totals would be two tables telling the designer two different things about
                    the same save. It is a required prop for the same reason — a table cannot see past its
                    own array, and a table left to guess is the silence this replaced.
                  */
                  stageEntries={stageEntries}
                />
              )}
              {/*
                THE DESIGNER'S OWN QUESTIONS GO BETWEEN THE STAGE'S OWN FIELDS AND ITS REPEATING ROWS,
                which is where the scorer counts them — and the order is not cosmetic. `missing` is printed
                in the order it is built: in full under the progress bar here, and truncated to three in the
                report's Outstanding column and on the phone's report screen. A list whose order does not
                match the form sends a designer looking for the second thing when the screen showed the
                first.

                ANCHORED TO AN ENTITY RATHER THAN INSERTED BY SPLITTING THE MAP IN TWO, so that every
                registry entity keeps its declared position exactly as it had it. Splitting the list into
                singletons-then-collections would have reordered any stage that happens to declare a
                collection before its singleton, which is a change to twenty-two existing forms in service
                of a block most of them do not have.

                EIGHT OF THE TWENTY-TWO STAGES DECLARE NO SINGLETON AT ALL — existing products, both sketch
                stages, all three prototype stages, final documentation and costing — and those are exactly
                the stages a designer is most likely to extend, so it is the ordinary case rather than a
                fallback. There the block is drawn BEFORE the first entity instead of after a singleton
                there is none of, which is the same position relative to the tables.
              */}
              {entity.key === customAfterKey ? customBlock : null}
            </Fragment>
          ))}
          {/* A stage that declares nothing at all still has to be able to ask a designer's own
              questions — there is no entity to hang the block off, so it stands alone. Inside the
              provider with the rest: a designer's own question can be a REF field too. */}
          {customAfterKey === null && customBeforeKey === null ? customBlock : null}
          </LinkedWorkshopProvider>

          <section className="panel grid gap-3 p-4">
            {completeness ? (
              <div>
                <div className="flex items-center justify-between text-sm">
                  <span className="font-medium text-ink-900">Required fields in this stage</span>
                  <span className="text-ink-muted">
                    {completeness.requiredFilled} of {completeness.requiredTotal}
                  </span>
                </div>
                <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-field-200">
                  <div
                    className={completeness.isComplete ? "h-full rounded-full bg-success-600" : "h-full rounded-full bg-purple-700"}
                    style={{ width: `${completeness.percent}%` }}
                  />
                </div>
                {completeness.missing.length ? (
                  <p className="mt-2 text-xs leading-5 text-ink-500">Still needed: {completeness.missing.join(", ")}</p>
                ) : null}
              </div>
            ) : null}

            {removedFrom.length ? (
              <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
                Saving will also delete the rows removed above. Because deletion has to be sent as “these are now the only
                rows”, any row someone else added to{" "}
                {removedFrom
                  .map((key) => stage.entities.find((entity) => entity.key === key)?.title ?? key)
                  .join(", ")}{" "}
                since this page loaded would go with them. Reload first if that is a risk.
              </p>
            ) : null}

            <div className="flex flex-wrap items-center gap-2">
              <button type="button" className="field-button" disabled={saving} onClick={() => save(false)}>
                {saving ? "Saving…" : "Save stage"}
              </button>
              {/* The strict pass is a SEPARATE control and never the default: a stage left
                  half-filled overnight is the normal state of this app, and a Save that refused
                  incomplete work would lose the day's capture rather than bank it. */}
              <button type="button" className="field-button-secondary" disabled={saving} onClick={() => save(true)}>
                Save and check required fields
              </button>
              {/* The honest three-state readout. Wording, not colour, carries it: a designer deciding
                  whether they can pack up needs to know the difference between "on this laptop" and
                  "in the repository", and a chip that only changed hue would not survive greyscale,
                  colour-blindness or the glare of a courtyard at noon. */}
              {unsent ? (
                <span className="inline-flex items-center gap-1.5 rounded-full bg-amber-100 px-2.5 py-1 text-xs font-medium text-amber-800">
                  <CloudOff className="h-3.5 w-3.5" aria-hidden />
                  Saved on this device only
                </span>
              ) : stageSync?.lastPushedAt ? (
                <span className="text-xs text-ink-500">
                  Sent to the repository at {new Date(stageSync.lastPushedAt).toLocaleTimeString()}
                </span>
              ) : null}
            </div>
          </section>

          <nav className="flex flex-wrap items-center justify-between gap-2" aria-label="Stage navigation">
            {previous ? (
              <button
                type="button"
                className="field-button-secondary"
                onClick={() => void leave(() => router.push(`/design-workshops/${id}/stages/${previous.key}`))}
              >
                <ChevronLeft className="h-4 w-4" aria-hidden />
                {previous.number}. {previous.title}
              </button>
            ) : (
              <span />
            )}
            {next ? (
              <button
                type="button"
                className="field-button-secondary"
                onClick={() => void leave(() => router.push(`/design-workshops/${id}/stages/${next.key}`))}
              >
                {next.number}. {next.title}
                <ChevronRight className="h-4 w-4" aria-hidden />
              </button>
            ) : null}
          </nav>
        </div>
      )}
    </>
  );
}
