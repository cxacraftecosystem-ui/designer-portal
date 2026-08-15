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
import { CollectionTable, EntityForm, type FieldErrors } from "@/components/designworkshop/EntityForm";
import { ANALYSIS_STAGE, MarketFindingsPanel } from "@/components/designworkshop/MarketFindingsPanel";
import {
  EMPTY_RECORDING_PLACE,
  StageRecordingPlaceCard,
  type StageRecordingPlace
} from "@/components/designworkshop/StageRecordingPlace";
import { PageHeader } from "@/components/PageHeader";
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
  type DwStage,
  type DwStageCompleteness,
  type DwValue
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
  type RegistrySource
} from "@/lib/designWorkshopStore";
import { isUnreachable } from "@/lib/offline";
import { readStageFocus } from "@/lib/workshopSearch";

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

function sameSnapshot(a: StageSnapshot, b: StageSnapshot): boolean {
  return (
    sameStoredValue(a.singleton, b.singleton) &&
    sameStoredValue(a.collections, b.collections) &&
    sameStoredValue(a.custom, b.custom) &&
    sameStoredValue([...a.removedFrom].sort(), [...b.removedFrom].sort())
  );
}

/**
 * The page-level upload dock, mounted once around the stage.
 *
 * Every media field on a stage now pre-uploads the moment a file is attached, and stage 13 has
 * eleven of them. Without a provider each field would report its own progress into a context that
 * is not there and the designer would have eleven separate percentages and no answer to "is it safe
 * to close this tab yet". `UploadTray` renders its own `aria-hidden` spacer, so the fixed dock does
 * not sit on top of the Save button at the bottom of the page.
 */
export default function DesignWorkshopStagePage(props: {
  params: Promise<{ id: string; stageKey: string }>;
}) {
  return (
    <UploadsProvider>
      {/* Next 16: the body reads `useSearchParams` (the workshop search's `?find=`), which must sit
          inside a Suspense boundary. `UploadsProvider` stays OUTSIDE it — a provider that suspends
          would drop the staged-file context every field on the stage is uploading through. */}
      <Suspense fallback={<div className="panel p-4 text-sm text-ink-700">Loading this stage…</div>}>
        <DesignWorkshopStagePageBody {...props} />
      </Suspense>
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
  const [singleton, setSingleton] = useState<DwEntryData>({});
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
      // No completeness to seed: the bar is derived from the setters above, so it is already
      // right for this stage the moment the boxes are.
      setRemovedFrom(removed);
      // The baseline the autosave compares against, written in the same breath as the setters it
      // describes. Until this is set, no autosave can run at all — which is what makes seeding
      // unable to mark a stage edited. See {@link banked}.
      banked.current = { singleton: data.singleton, collections: data.collections, custom: held, removedFrom: removed };
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
        if (err instanceof ApiError && err.status >= 500) {
          setError(
            `The repository could not send this stage: ${err.message} The server was reached, so this is not a connection ` +
              "problem. What is shown below is the copy saved on this device."
          );
          if (stageNeverRead(draft, stageKey)) setNeverDownloaded(true);
          return;
        }
        setError(
          err instanceof ApiError && err.status === 404
            ? "That stage does not exist in this build's field registry."
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
      // Banked before anything else: from here on this snapshot is what "already saved" means, and
      // a re-render carrying the same values must not write it a second time.
      banked.current = snapshot;
      flushRef.current = null;
      setLocalPending(false);
      const next = saved?.stages[stageKey];
      if (next) setStageSync({ dirtyAt: next.dirtyAt, lastPushedAt: next.lastPushedAt, failure: next.failure });
    };
    flushRef.current = write;
    const timer = window.setTimeout(() => void write(), AUTOSAVE_MS);
    return () => window.clearTimeout(timer);
  }, [singleton, collections, custom, removedFrom, stage, stageKey]);

  /** Run the pending write now. Awaited before every navigation and before every push. */
  const flushLocal = useCallback(async () => {
    const write = flushRef.current;
    if (!write) return;
    flushRef.current = null;
    await write();
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
  function patchCollection(entityKey: string, rows: DwRow[]) {
    // A removal is detected by COUNT rather than by asking the table to report one: the table's job
    // is to hand back a list, and what that list implies for the save is this page's job.
    if (rows.length < (collections[entityKey] ?? []).length) {
      setRemovedFrom((keys) => (keys.includes(entityKey) ? keys : [...keys, entityKey]));
    }
    setCollections((current) => ({ ...current, [entityKey]: rows }));
  }

  /**
   * Navigate, after making sure the debounce has been banked.
   *
   * THIS IS WHAT REPLACED THE UNSAVED-CHANGES PROMPT - see decision 6 in the file header. The prompt
   * existed to stop work being lost; the draft store means leaving loses nothing, so the honest
   * behaviour is to flush and go. Awaiting the flush rather than firing it and hoping is the part
   * that matters: `putDraftStage` is a real IndexedDB transaction and a route change that raced it
   * would drop the last few seconds of typing on exactly the stage the designer had just finished.
   *
   * No second back control is added anywhere on this page: `e2e/back-control.spec.ts` asserts
   * exactly one arrow, because the opposite shipped four times.
   */
  const leave = useCallback(
    async (action: () => void) => {
      await flushLocal();
      action();
    },
    [flushLocal]
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
  async function save(submit: boolean) {
    if (!stage) return;
    const target = draftIdRef.current;
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      await flushLocal();
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

      const { entries, rowKeys, merged } = buildStageEntries(stage, draftStage);
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
      const { decoded, unplaced: unplacedLines } = placeStageErrors(result.errors, rowKeys);
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
        THE GATE COUNTS THE UNPLACEABLE REFUSALS TOO, OR AN ENTIRELY UNPLACEABLE SET READS AS A CLEAN SAVE.

        `decoded` holds only what could be attached to a box. When every refusal in a response is
        unplaceable, `decoded` is EMPTY — so this gate fell through, no error was shown, and the save went
        on to `markStagePushed`, which stamps the stage as pushed and clears the unsent marks. A stage the
        server had partly refused was recorded as landed, and the refusal was reported nowhere at all.
        `refused` is the count of both kinds for the same reason the sentence names both.
      */
      const marked = Object.keys(decoded).length;
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
      if (err instanceof ApiError && err.status === 429) {
        setNotice(
          "Saved on this device. The repository asked for a moment before accepting more, so this stage sends itself shortly."
        );
        return;
      }
      if (err instanceof ApiError && err.status >= 500) {
        setError(
          `The repository refused to save ${stage.number}. ${stage.title}: ${err.message} It is safe on this device and ` +
            "nothing has been thrown away — but the server was reached, so this is not a connection problem and retrying " +
            "unchanged will be refused again. Check the answers named above, or report this stage."
        );
        return;
      }
      // A 422 from `submit=true` is the expected answer to "is this stage finished", not a fault —
      // its detail names the fields, and `describeApiDetail` has already turned it into a sentence.
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

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}
      {notice ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">{notice}</div>
      ) : null}
      {registrySource === "cache" ? (
        // Which field list a designer is looking at is not a detail. The cached registry may predate
        // a deploy, so a field added last week is simply absent here — saying so is the difference
        // between "this stage does not ask for that" and "this browser has not been told about it".
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
          This form is drawn from the field list saved in this browser, because the server could not be reached. It is
          whatever was current the last time this laptop had a connection; a field added since will not appear until it does.
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
                />
              ) : (
                <CollectionTable
                  entity={entity}
                  rows={collections[entity.key] ?? []}
                  onRowsChange={(rows) => patchCollection(entity.key, rows)}
                  workshopId={id}
                  errorsByIndex={collectionErrors(entity)}
                  disabled={saving}
                  stageKey={stageKey}
                  capture={capture}
                  focus={focus}
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
              questions — there is no entity to hang the block off, so it stands alone. */}
          {customAfterKey === null && customBeforeKey === null ? customBlock : null}

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
