"use client";

/**
 * Bulk photo import — attach a camera dump to a workshop, one confirmation instead of two hundred.
 *
 * THE JOB. A designer shoots two hundred photographs over a thirty-day workshop on a real camera and
 * then attaches each one by hand, stage by stage. This page reads the EXIF capture clock off every
 * file, matches it against the dates the workshop already records, and proposes where each one
 * belongs. The designer scans the list, fixes what is wrong, and presses Confirm once.
 *
 * IT PROPOSES. IT DOES NOT COMMIT. Nothing is attached, uploaded or written until Confirm is
 * pressed, and every proposal carries the sentence that justifies it — "Taken 14 Feb 2026, 10:22 —
 * stage 13's Stage logs row “Warping the loom”, date 14 Feb 2026." That is the same rule the identity
 * card reader follows and it is here for the same reason: a bulk tool that files two hundred
 * photographs on its own judgement is a bulk tool that files the wrong ones invisibly. The evidence
 * column exists so a wrong proposal is OBVIOUS rather than plausible; the ranking exists so the
 * designer's job is checking rather than sorting.
 *
 * WHY THE FILE PICKER HERE IS A PLAIN INPUT AND NOT `MediaCaptureField`. Every other media surface in
 * this app uses that component and gets eager pre-upload for free, and that is right everywhere else
 * — but it is wrong here, twice over. It would push two hundred files, six to twelve megabytes each,
 * up a field connection BEFORE the designer has agreed to attach any of them; and the whole point of
 * this page is that the matching runs with no network at all, in the village where the fieldwork
 * happened. So the files are read locally, the proposal is computed locally, and bytes move only
 * after Confirm — and even then through the local draft store, so a confirmation made with no signal
 * is durable and syncs later.
 *
 * WHAT CONFIRM ACTUALLY DOES, and why it writes no upload code. Each confirmed photograph is handed
 * to `stageLocalMedia`, which puts the bytes in the draft store and returns a `dwlocal:` reference;
 * that reference is written into the target stage's image field through `putDraftStage`. From there
 * the existing sync pass uploads it and swaps in the real media id — the same path a photograph
 * attached on a stage form takes. This page therefore inherits the whole of the pipeline in
 * `docs/MEDIA_PIPELINE.md` (retries, resumption, the orphan sweep) instead of reimplementing a
 * two-hundred-file uploader that would have to learn all of it again.
 *
 * NOTHING IS RE-ENCODED. The `File` picked is the `File` stored. EXIF is read out of the bytes and
 * the bytes are never rewritten — `docs/MEDIA_PIPELINE.md` §5 records why this archive refuses to
 * re-encode an image at all, and a photo importer that stripped the timestamps it sorted by would be
 * the sharpest possible version of that mistake.
 */

import { use, useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { Images } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { Dropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { readCaptureStamp } from "@/lib/media";
import {
  DEFAULT_TIMEZONE,
  appendMediaRef,
  buildAnchors,
  formatStamp,
  intakePhotos,
  intakeSummary,
  mediaRefRoom,
  photoTargets,
  type PhotoIntakeRow,
  type WorkshopAnchor
} from "@/lib/photoIntake";
import type { DwRegistry, DwRow, DwStageData, DwValue } from "@/lib/designWorkshops";
import {
  loadDraft,
  loadRegistry,
  putDraftStage,
  stageDataOf,
  stageLocalMedia,
  type DwDraft
} from "@/lib/designWorkshopStore";

/**
 * How many files have their EXIF read at once.
 *
 * Each read is a short seek into the head of a file on local disk, not a network request, so the cap
 * is about not freezing the tab on a two-hundred-file selection rather than about bandwidth. Six
 * keeps the progress counter moving visibly while leaving the main thread responsive.
 */
const EXIF_CONCURRENCY = 6;

/** Where one confirmed photograph is going: a stage, an entity, optionally a row, and a field. */
type Destination = {
  /** The `<option>` value, and the identity used to group writes by stage. */
  key: string;
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  entityKey: string;
  /** Null for a SINGLETON entity. */
  rowKey: string | null;
  fieldKey: string;
  label: string;
  multiple: boolean;
  /**
   * The ceiling the registry DECLARED for this field, or undefined where it declared none.
   *
   * Carried verbatim, undefined and all, because the absence is not the same as the number: what is
   * ENFORCED on an undeclared field is the server's own default (`mediaRefRoom` and `appendMediaRef`
   * resolve it), and what may be PRINTED is only a figure the registry actually stated
   * (docs/DESIGN_WORKSHOP.md:229-232). Resolving it here would hand the refusal sentence a 200 it is
   * not allowed to say. Two of the twenty IMAGE_LIST fields — the motif pair, 20 each — answer this.
   */
  maxItems?: number;
};

/** The two group headings, named once so the picker and this file's prose cannot drift apart. */
const PROPOSED_GROUP = "Proposed from the capture date";
const EVERYWHERE_GROUP = "Every other place a photograph can go";

/**
 * One row's destination list: leave-out first, then the proposals, then everywhere else.
 *
 * THE PROPOSED DESTINATIONS ARE EXCLUDED FROM THE SECOND GROUP RATHER THAN REPEATED IN IT. Two rows
 * carrying the same value is a control that cannot say which one is selected — the old `<select>`
 * matched the first, so the highlight could sit in one group while the value belonged to the other,
 * and `selectOption` became ambiguous for anything driving this page. The themed picker is no better
 * off: `key` is the React key as well as the value, and a duplicate would collide.
 *
 * "Leave out" carries NO group, which `groupRows` draws first and ungrouped. It belongs to neither
 * heading — it is the row that declines both — and filing it under "Proposed" would make the
 * commonest answer look like a proposal the intake made.
 */
function destinationOptions(destinations: Destination[], proposed: Destination[]): DropdownOption[] {
  const proposedKeys = new Set(proposed.map((destination) => destination.key));
  return [
    { value: "", label: "Leave out — I will attach this one myself" },
    ...proposed.map((destination) => ({
      value: destination.key,
      label: destination.label,
      group: PROPOSED_GROUP
    })),
    ...destinations
      .filter((destination) => !proposedKeys.has(destination.key))
      .map((destination) => ({
        value: destination.key,
        label: destination.label,
        group: EVERYWHERE_GROUP
      }))
  ];
}

/** One line of the table: the file, what the intake made of it, and where it is currently headed. */
type IntakeLine = {
  file: File;
  row: PhotoIntakeRow;
  /** The chosen destination key, or "" for "leave this one out". */
  choice: string;
};

const destinationKey = (stageKey: string, entityKey: string, rowKey: string | null, fieldKey: string) =>
  `${stageKey}|${entityKey}|${rowKey ?? ""}|${fieldKey}`;

export default function PhotoIntakePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [draft, setDraft] = useState<DwDraft | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [lines, setLines] = useState<IntakeLine[]>([]);
  const [reading, setReading] = useState<{ done: number; total: number } | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);

  /* ── Load the registry and the local draft ─────────────────────────────── */

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [{ registry: loaded }, found] = await Promise.all([loadRegistry(), loadDraft(id)]);
        if (cancelled) return;
        setRegistry(loaded);
        setDraft(found);
        if (!found) {
          // Not an error and not a blank page: the workshop simply has not been opened on this
          // device. Saying which of the two it is, is the whole difference between "empty" and
          // "unread" that the stage index already draws.
          setLoadError(
            "This workshop has not been downloaded to this device yet. Open its stages once with a connection, then come back — the dates it records are what these photographs are matched against."
          );
        }
      } catch {
        if (!cancelled) setLoadError("Unable to read this workshop from this device.");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id]);

  /** The workshop's stage data, in the shape `buildAnchors` reads. */
  const stageData = useMemo<Record<string, DwStageData>>(() => {
    if (!draft) return {};
    return Object.fromEntries(Object.entries(draft.stages).map(([key, stage]) => [key, stageDataOf(stage)]));
  }, [draft]);

  const anchors = useMemo<WorkshopAnchor[]>(
    () => (registry ? buildAnchors(registry, stageData) : []),
    [registry, stageData]
  );

  /**
   * The timezone the clocks are read in.
   *
   * `recordedTimezone` is per-capture rather than per-workshop in this schema, so there is no single
   * stored value to read here; the repository-wide default is used and — this is the part that
   * matters — it is STATED ON SCREEN rather than assumed silently. A designer who shot on a camera
   * set to another zone can see, in one sentence, why the dates look shifted.
   */
  const timeZone = DEFAULT_TIMEZONE;

  /* ── Every place a photograph could be filed ───────────────────────────── */

  /**
   * Built from the registry, so it lists the image fields of every entity of all 22 stages — not a
   * hand-written menu. A stage added to `stage_definitions.py` with a photo field becomes a
   * destination here with no change to this file.
   */
  const destinations = useMemo<Destination[]>(() => {
    if (!registry) return [];
    const out: Destination[] = [];
    for (const stage of registry.stages) {
      const data = stageData[stage.key];
      for (const entity of stage.entities) {
        const targets = photoTargets(registry, stage.key, entity.key);
        if (!targets.length) continue;
        if (entity.cardinality === "SINGLETON") {
          for (const target of targets) {
            out.push({
              key: destinationKey(stage.key, entity.key, null, target.fieldKey),
              stageKey: stage.key,
              stageNumber: stage.number,
              stageTitle: stage.title,
              entityKey: entity.key,
              rowKey: null,
              fieldKey: target.fieldKey,
              multiple: target.multiple,
              maxItems: target.maxItems,
              label: `${stage.number}. ${stage.title} — ${target.fieldLabel}`
            });
          }
          continue;
        }
        const rows = data?.collections?.[entity.key] ?? [];
        rows.forEach((row: DwRow, index: number) => {
          const rowKey = row._clientKey ?? row._entryId ?? null;
          if (!rowKey) return;
          const labelValue = entity.labelField ? row[entity.labelField] : null;
          const rowName = typeof labelValue === "string" && labelValue.trim() ? labelValue.trim() : `row ${index + 1}`;
          for (const target of targets) {
            out.push({
              key: destinationKey(stage.key, entity.key, rowKey, target.fieldKey),
              stageKey: stage.key,
              stageNumber: stage.number,
              stageTitle: stage.title,
              entityKey: entity.key,
              rowKey,
              fieldKey: target.fieldKey,
              multiple: target.multiple,
              maxItems: target.maxItems,
              label: `${stage.number}. ${stage.title} — ${entity.title} “${rowName}” — ${target.fieldLabel}`
            });
          }
        });
      }
    }
    return out;
  }, [registry, stageData]);

  const destinationsByKey = useMemo(
    () => new Map(destinations.map((destination) => [destination.key, destination])),
    [destinations]
  );

  /**
   * The destination a proposal points at: its entity's first image LIST, when it has one.
   *
   * `multiple` is a hard condition, not a preference, and it is the difference between a useful
   * import and a destructive one. Stage 1's window is a legitimate anchor for any photograph taken
   * during the workshop, and the only image field on its entity is `coverPhoto` — a SINGLE-valued
   * field. Auto-selecting it would point every photograph shot on an unlogged day at one box that
   * holds exactly one photograph, so a two-hundred-file import would write each over the last and
   * finish having attached one, silently, over the designer's chosen cover.
   *
   * A single-valued field can still be chosen BY HAND from the full list below — a designer
   * deliberately setting the cover photograph is a perfectly good thing to do. It is only the
   * automatic default that refuses, because a default is applied two hundred times without being
   * read. Photographs left with no default are counted as "need you" in the header rather than
   * quietly parked somewhere plausible.
   */
  const defaultDestinationFor = useCallback(
    (row: PhotoIntakeRow): string => {
      for (const proposal of row.proposals) {
        // ONLY a proposal that actually COVERS the photograph's date may become a default.
        // `NEAREST` proposals (`daysAway > 0`) are offered in the dropdown and carry evidence saying
        // in so many words that nothing recorded covers the date — they are for a human to weigh, not
        // to apply unread two hundred times. Without this the 21st fell through the guard below onto
        // the log row for the 14th, a week away, and pre-selected it.
        if (proposal.daysAway !== 0) continue;
        const match = destinations.find(
          (destination) =>
            destination.multiple &&
            destination.stageKey === proposal.anchor.stageKey &&
            destination.entityKey === proposal.anchor.entityKey &&
            destination.rowKey === proposal.anchor.rowKey
        );
        if (match) return match.key;
      }
      return "";
    },
    [destinations]
  );

  /* ── Reading the files ─────────────────────────────────────────────────── */

  const onPick = useCallback(
    async (picked: FileList | null) => {
      if (!picked?.length) return;
      setProblem(null);
      setNotice(null);
      const files = Array.from(picked);
      setReading({ done: 0, total: files.length });

      // Read in order, a few at a time, so the counter is honest about progress on a big dump.
      const stamps = new Array<{ takenAt: string | null; takenAtOffset: string | null }>(files.length);
      let cursor = 0;
      let done = 0;
      await Promise.all(
        Array.from({ length: Math.min(EXIF_CONCURRENCY, files.length) }, async () => {
          for (;;) {
            const index = cursor++;
            if (index >= files.length) return;
            stamps[index] = await readCaptureStamp(files[index]);
            done += 1;
            setReading({ done, total: files.length });
          }
        })
      );

      const rows = intakePhotos(
        files.map((file, index) => ({
          fileName: file.name,
          takenAt: stamps[index]?.takenAt ?? null,
          takenAtOffset: stamps[index]?.takenAtOffset ?? null
        })),
        anchors,
        { timeZone }
      );

      setLines(rows.map((row, index) => ({ file: files[index], row, choice: defaultDestinationFor(row) })));
      setReading(null);
      // The picker is reset so choosing the same folder twice actually re-fires `change`.
      if (fileInput.current) fileInput.current.value = "";
    },
    [anchors, defaultDestinationFor, timeZone]
  );

  /* ── Confirming ────────────────────────────────────────────────────────── */

  const chosen = useMemo(() => lines.filter((line) => line.choice), [lines]);

  async function confirm() {
    if (!draft || !chosen.length) return;
    setConfirming(true);
    setProblem(null);
    setNotice(null);
    try {
      /**
       * Grouped by stage and written once per stage, not once per photograph.
       *
       * `putDraftStage` replaces the stage's entities wholesale, so two writes to one stage in a
       * loop would have the second overwrite the first's field with the value it read before the
       * first ran. Building the whole stage in memory and writing it once is the only correct shape,
       * and it is also what makes a hundred photographs into twenty-two writes rather than a hundred.
       */
      const pending = new Map<
        string,
        { singletons: Record<string, Record<string, DwValue | undefined>>; collections: Record<string, DwRow[]> }
      >();
      const stageOf = (stageKey: string) => {
        const existing = pending.get(stageKey);
        if (existing) return existing;
        const stage = draft.stages[stageKey];
        const fresh = {
          singletons: { ...(stage?.singletons ?? {}) },
          collections: { ...(stage?.collections ?? {}) }
        };
        pending.set(stageKey, fresh);
        return fresh;
      };

      let attached = 0;
      const missed: string[] = [];
      /**
       * The photographs a destination had no room for, each with the destination that turned it away.
       *
       * THE FIELD'S CEILING IS ENFORCED HERE OR IT IS NOT ENFORCED AT ALL on this path. `coerce_value`
       * REFUSES an over-long array rather than trimming it and `save_stage` then restores the refused
       * key from the previous entry, so a confirm that appended two hundred photographs into a gallery
       * declaring twenty would not lose the last hundred and eighty — it would lose the field's whole
       * write on the next sync, with every byte already copied. See `appendMediaRef` in
       * `lib/photoIntake.ts`, and docs/DESIGN_WORKSHOP.md:229-232 for why an absent `maxItems` is the
       * server's default of two hundred rather than no ceiling.
       *
       * THE DESTINATION IS CARRIED, NOT JUST THE FILENAME, because "it did not fit" is unactionable
       * without knowing where it did not fit: a designer confirming a camera dump across nine stages
       * needs to be told WHICH gallery is full to know what to remove or where else to send it.
       */
      const full: Array<{ file: string; label: string; declaredCap?: number }> = [];
      /**
       * The stages that actually received a photograph, which is not the same set as `pending`.
       *
       * `stageOf` puts a stage in `pending` the moment one is CONSIDERED — before the row lookup and
       * before the room check — so a stage every photograph was refused from is in that map holding a
       * copy identical to what is on disk. Counting it would print "across 4 stages" over three that
       * changed, and writing it back would re-put an unchanged stage over whatever another tab has
       * done to it in the meantime. Both are answered by only ever counting and writing what landed.
       */
      const touched = new Set<string>();
      /**
       * The photographs whose chosen destination is not in the registry-built list any more.
       *
       * `destinations` is rebuilt whenever the draft changes, so a row deleted in another tab between
       * the choosing and the pressing takes its destination key with it. This was a bare `continue`:
       * the file was not attached, not counted, and dropped out of the table on the next render with
       * nothing anywhere saying it had gone. That silence used to hide behind a receipt that at least
       * printed "0 photographs attached"; withholding that sentence when nothing lands (below) would
       * have made it total. Android's confirm walk names the same case `unplaceable`.
       */
      const unplaceable: string[] = [];

      for (const line of chosen) {
        const destination = destinationsByKey.get(line.choice);
        if (!destination) {
          unplaceable.push(line.file.name);
          continue;
        }
        /*
          THE BYTES ARE COPIED ONLY ONCE THE FIELD HAS AGREED TO TAKE THEM, which is why this is a
          closure called from inside the two branches rather than a statement above them.

          `stageLocalMedia` writes the blob into IndexedDB and the sync pass uploads every staged row
          it finds, so copying first and asking afterwards would leave a photograph nothing references
          on this laptop for the fortnight and push it to the repository on the next connection. The
          capture card makes the same choice in the same words one layer up — FieldInput's
          `acceptFiles` trims "before a byte is uploaded".
        */
        const copyToDevice = () =>
          stageLocalMedia(id, line.file, {
            stageKey: destination.stageKey,
            entityKey: destination.entityKey,
            fieldKey: destination.fieldKey,
            clientKey: destination.rowKey,
            caption: null
          });

        const target = stageOf(destination.stageKey);
        if (destination.rowKey === null) {
          const entity = { ...(target.singletons[destination.entityKey] ?? {}) };
          const heldOnEntity = entity[destination.fieldKey];
          if (!mediaRefRoom(heldOnEntity, destination.multiple, destination.maxItems)) {
            full.push({ file: line.file.name, label: destination.label, declaredCap: destination.maxItems });
            continue;
          }
          const { ref } = await copyToDevice();
          entity[destination.fieldKey] = appendMediaRef(
            heldOnEntity,
            ref,
            destination.multiple,
            destination.maxItems
          );
          target.singletons[destination.entityKey] = entity;
          touched.add(destination.stageKey);
          attached += 1;
          continue;
        }
        const rows = [...(target.collections[destination.entityKey] ?? [])];
        const index = rows.findIndex((row) => (row._clientKey ?? row._entryId) === destination.rowKey);
        if (index < 0) {
          // The row was deleted in another tab between this page reading the draft and Confirm being
          // pressed. Naming the file is the only acceptable outcome, and nothing is lost: the file
          // itself is still the one the designer picked, sitting in the table below for them to send
          // somewhere else. Nothing has been copied for it either — which is the point of asking
          // before staging, since the retry would otherwise leave the first copy orphaned in storage.
          missed.push(line.file.name);
          continue;
        }
        const row = { ...rows[index] };
        const heldOnRow = row[destination.fieldKey];
        if (!mediaRefRoom(heldOnRow, destination.multiple, destination.maxItems)) {
          full.push({ file: line.file.name, label: destination.label, declaredCap: destination.maxItems });
          continue;
        }
        const { ref } = await copyToDevice();
        row[destination.fieldKey] = appendMediaRef(heldOnRow, ref, destination.multiple, destination.maxItems);
        rows[index] = row;
        target.collections[destination.entityKey] = rows;
        touched.add(destination.stageKey);
        attached += 1;
      }

      for (const [stageKey, data] of pending) {
        if (!touched.has(stageKey)) continue;
        await putDraftStage(id, stageKey, { singletons: data.singletons, collections: data.collections });
      }

      setDraft(await loadDraft(id));
      /*
        EVERY LINE THAT DID NOT LAND STAYS IN THE TABLE, with its file and its chosen destination, so
        the designer can send it somewhere else without picking the folder again. A refused line whose
        row has come back, or whose gallery has had something removed from it, confirms on the second
        press with no further ceremony.
      */
      const refusedNames = new Set([...missed, ...full.map((entry) => entry.file)]);
      setLines((current) => current.filter((line) => !line.choice || refusedNames.has(line.file.name)));
      /*
        THE RECEIPT COUNTS WHAT LANDED AND NOTHING ELSE — `attached`, incremented only after a write
        actually went into the stage in memory, and `touched`, which holds only stages one of those
        writes reached. Printing `chosen.length` or `pending.size` here would be the same defect this
        whole change is about wearing a different hat: a green "12 attached" over ten photographs is
        worse than the refusal it hides, because the designer stops looking.

        Withheld entirely when nothing landed rather than printed as "0 photographs attached": the
        sentence goes on to promise an upload, and there is nothing to upload. The refusals below then
        carry the whole story, which is the accurate one.
      */
      if (attached) {
        setNotice(
          `${attached} photograph${attached === 1 ? "" : "s"} attached on this device across ${touched.size} stage${touched.size === 1 ? "" : "s"}. ` +
            "They upload themselves when this laptop next has a connection, and the copy here is kept until the repository confirms each one."
        );
      }
      /*
        THE TWO REFUSALS ARE WORDED APART AND JOINED, never folded into one count, because they need
        different things from the reader: a vanished row wants a different destination, a full gallery
        wants something removed from the one they chose. A single "N were not attached" would leave
        both looking like the other.

        THE FULL-GALLERY SENTENCE NAMES THE CEILING WHERE THE REGISTRY DECLARED ONE, AND NEVER
        OTHERWISE. Two of the twenty IMAGE_LIST fields declare a cap; for the rest the number enforced
        is the server's own default, and printing a figure this client read from nowhere is the half
        of docs/DESIGN_WORKSHOP.md:229-232 that is forbidden — "a client must neither read the absence
        as no limit nor print a number it did not read". Both halves, and neither traded for the
        other: the declared number is read, so it may be printed; 200 is not, so it may not.

        THIS PARAGRAPH USED TO SAY THE CEILING WAS NEVER NAMED HERE, and stated it as the shared rule.
        It was not: Android's `dwIntakeFullNotice` prints "already holds the 20 photographs it may"
        for a declared cap, and this browser's OWN capture card prints "holds at most 20 files". So a
        designer importing on the handset was told 20 and the same designer importing here was told
        nothing, and the two surfaces of this client disagreed with each other about one field.
      */
      const complaints: string[] = [];
      if (missed.length) {
        complaints.push(
          `${missed.length} could not be placed because the row they were headed for is no longer in this workshop: ${missed.join(", ")}. Pick them again and choose another destination.`
        );
      }
      if (full.length) {
        const one = full.length === 1;
        // The number only where the registry gave one, and only when the refused files AGREE about it:
        // the set is keyed on the declared ceiling rather than on the field, so two galleries that both
        // declare 20 still name 20 (true of each), while one declared beside one undeclared gives a set
        // of two and falls back to naming no number — a single sentence can never name a ceiling that
        // is not every listed file's own.
        const caps = new Set(full.map((entry) => entry.declaredCap));
        const declared = caps.size === 1 ? [...caps][0] : undefined;
        const because =
          declared && declared > 0
            ? `holds at most ${declared} ${declared === 1 ? "entry" : "entries"} and is full`
            : "is full";
        complaints.push(
          `${full.length} ${one ? "was" : "were"} not attached because the field ${one ? "it was" : "they were"} headed for ${because}: ` +
            `${full.map((entry) => `${entry.file} (${entry.label})`).join(", ")}. ` +
            `Remove something already attached there, or choose another destination, then confirm ${one ? "it" : "them"} again — ${one ? "it is" : "they are"} still in the list below.`
        );
      }
      if (complaints.length) setProblem(complaints.join(" "));
    } catch {
      setProblem(
        "Nothing could be written to this device's storage. If the browser is in private mode or its storage is full, the photographs cannot be kept here — free some space and try again."
      );
    } finally {
      setConfirming(false);
    }
  }

  /* ── Render ────────────────────────────────────────────────────────────── */

  const summary = useMemo(() => intakeSummary(lines.map((line) => line.row)), [lines]);
  const datedAnchors = anchors.length;

  return (
    <>
      <PageHeader
        title="Bulk photo import"
        description="Match a camera dump against the dates this workshop already records, then confirm where each photograph belongs."
        icon={<Images className="h-5 w-5" aria-hidden />}
        actions={
          <Link href={`/design-workshops/${id}`} className="field-button-secondary">
            All stages
          </Link>
        }
      />

      {loadError ? (
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          {loadError}
        </div>
      ) : null}
      {problem ? (
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {problem}
        </div>
      ) : null}
      {notice ? (
        <div role="status" className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
          {notice}
        </div>
      ) : null}

      {/* The assumption, stated before anything is read rather than after something looks wrong. */}
      <div className="panel mb-5 p-4">
        <h2 className="font-display text-base font-semibold text-ink-900">How the dates are read</h2>
        <p className="mt-2 text-sm leading-6 text-ink-700">
          Each photograph&rsquo;s capture time is read from the file itself and taken to be{" "}
          <strong>{timeZone}</strong> — the clock the camera was set to. Nothing in the file is changed, and no
          photograph is attached until you press Confirm.
        </p>
        <p className="mt-2 text-sm leading-6 text-ink-muted">
          {datedAnchors > 0 ? (
            <>
              This workshop records {datedAnchors} dated {datedAnchors === 1 ? "entry" : "entries"} to match against —
              its start and end dates, its schedule days, its daily prototype logs and its closing. The more of those
              that are filled in, the more precise each proposal is.
            </>
          ) : (
            <>
              This workshop has no dates recorded yet, so nothing can be proposed. Fill in the start and end dates on
              stage 1 — and the daily logs on stage 13 — and these photographs will place themselves.
            </>
          )}
        </p>
      </div>

      <div className="panel mb-5 p-4">
        <label htmlFor="photo-intake-files" className="field-label">
          Choose the photographs
        </label>
        <input
          id="photo-intake-files"
          ref={fileInput}
          type="file"
          multiple
          accept="image/*"
          disabled={Boolean(reading) || confirming}
          onChange={(event) => void onPick(event.target.files)}
          className="mt-2 block w-full text-sm text-ink-700 file:mr-3 file:cursor-pointer file:rounded-md file:border-0 file:bg-purple-700 file:px-4 file:py-2 file:text-sm file:font-medium file:text-white hover:file:bg-purple-800"
        />
        <p className="mt-2 text-xs leading-5 text-ink-500">
          Nothing is uploaded by choosing files. They are read on this device, and stay on it until you confirm.
        </p>
        {reading ? (
          <p role="status" className="mt-3 text-sm text-ink-700">
            Reading capture dates — {reading.done} of {reading.total}.
          </p>
        ) : null}
      </div>

      {lines.length ? (
        <div className="panel mb-5 overflow-hidden">
          <div className="flex flex-col gap-2 border-b border-line-200 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
            <p className="text-sm text-ink-700">
              {/* Counted, never merely described: the number of photographs this could not place is
                  the number a designer has to look at, and it must be on screen before Confirm.
                  These count the CURRENT destinations, not the proposals — a photograph with a
                  proposal that was refused as a default (nothing covers its date, or the only field
                  going spare holds one photograph) still needs a human, and counting it as placed
                  would be the reassuring version of the truth. */}
              <strong className="text-ink-900">{summary.total}</strong> chosen ·{" "}
              <strong className="text-ink-900">{chosen.length}</strong> ready to attach ·{" "}
              <strong className="text-ink-900">{summary.total - chosen.length}</strong> need you
            </p>
            <button
              type="button"
              className="field-button"
              disabled={!chosen.length || confirming || Boolean(reading)}
              onClick={() => void confirm()}
            >
              {confirming ? "Attaching…" : `Confirm ${chosen.length} photograph${chosen.length === 1 ? "" : "s"}`}
            </button>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full min-w-[56rem] border-collapse text-sm">
              <thead>
                <tr className="border-b border-line-200 bg-surface-50 text-left">
                  <th scope="col" className="px-4 py-2 font-medium text-ink-700">
                    Photograph
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium text-ink-700">
                    Taken
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium text-ink-700">
                    Attach to
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium text-ink-700">
                    Why
                  </th>
                </tr>
              </thead>
              <tbody>
                {lines.map((line, index) => {
                  /**
                   * A STABLE HANDLE ON ONE ROW'S PICKER, on the wrapper rather than the control.
                   *
                   * `Dropdown` takes no `id` — deliberately, and the frontend guide says why: a
                   * control whose `<label htmlFor>` and `aria-describedby` are bound by id cannot
                   * become a themed dropdown without losing both. So the id goes on the cell's
                   * wrapper, which is what `e2e/photo-intake-confirm.spec.ts` addresses to read one
                   * row's answer out of a table of two hundred identical ones.
                   */
                  const selectId = `photo-intake-destination-${index}`;
                  const chosenDestination = line.choice ? destinationsByKey.get(line.choice) : null;
                  /**
                   * The destinations this photograph's own proposals point at, in proposal order and
                   * de-duplicated — one entity can hold two image fields (stage 3 has both "Opening
                   * photographs" and "Event photographs"), and two proposals can share an entity.
                   */
                  const proposed = line.row.proposals
                    .flatMap((proposal) =>
                      destinations.filter(
                        (destination) =>
                          destination.stageKey === proposal.anchor.stageKey &&
                          destination.entityKey === proposal.anchor.entityKey &&
                          destination.rowKey === proposal.anchor.rowKey
                      )
                    )
                    .filter((destination, position, all) => all.findIndex((item) => item.key === destination.key) === position);
                  // The evidence shown is the one for the proposal actually selected, so changing the
                  // destination cannot leave a sentence on screen justifying a different answer.
                  const matching = line.row.proposals.find(
                    (proposal) =>
                      chosenDestination &&
                      proposal.anchor.stageKey === chosenDestination.stageKey &&
                      proposal.anchor.entityKey === chosenDestination.entityKey &&
                      proposal.anchor.rowKey === chosenDestination.rowKey
                  );
                  return (
                    <tr key={`${line.file.name}-${index}`} className="border-b border-line-200 last:border-b-0 align-top">
                      <td className="px-4 py-3">
                        <span className="block max-w-56 truncate font-medium text-ink-900" title={line.file.name}>
                          {line.file.name}
                        </span>
                      </td>
                      <td className="whitespace-nowrap px-4 py-3 text-ink-700">
                        {line.row.stamp ? formatStamp(line.row.stamp) : <span className="text-ink-500">No date</span>}
                      </td>
                      <td className="min-w-0 px-4 py-3">
                        {/*
                          ── THE THEMED PICKER, WITH GROUPS, AND WHY THAT TOOK A CHANGE TO THE
                             PRIMITIVE RATHER THAN A PROP ─────────────────────────────────────────

                          This was the one long list in the application with no filter box, and the
                          comment that used to sit here said so honestly and named what blocked the
                          swap: `SelectOption` had no group field, and the grouping is not decoration
                          — "Proposed from the capture date" against every other place in the
                          workshop IS the argument this control makes. Flattening that into every
                          label repeats one long prefix on every row of a list that is already long.

                          `SelectOption.group` now exists (see `ui/selectFilter.ts`), so the panel
                          draws the two runs under their own headings, each a `role="group"` whose
                          label is read before the options inside it. The second worry in that
                          comment — one panel per file on a page that accepts hundreds — turns out to
                          be the wrong way round: `AnchoredPopover` mounts nothing until the panel is
                          opened, so a row costs one `<button>`, whereas the native `<select>` it
                          replaces materialised every one of its several hundred `<option>` elements
                          for every row of the table. This is fewer DOM nodes, not more.

                          `advanceOnSelect={false}`: picking a destination adjusts THIS row, and this
                          is not a form being walked top to bottom. There is no `<form>` here either,
                          so `focusNextField`'s `closest("form")` would be null and the walker would
                          fall back to the whole document — throwing focus at whatever came next in
                          the page. The trap is named in §17 of the frontend guide.
                        */}
                        <div id={selectId}>
                          <Dropdown
                            value={line.choice}
                            onChange={(next) =>
                              setLines((current) =>
                                current.map((item, position) =>
                                  position === index ? { ...item, choice: next } : item
                                )
                              )
                            }
                            options={destinationOptions(destinations, proposed)}
                            ariaLabel={`Where ${line.file.name} is attached`}
                            disabled={confirming}
                            searchable
                            advanceOnSelect={false}
                          />
                        </div>
                      </td>
                      <td className="px-4 py-3 text-ink-muted">
                        {matching ? (
                          <span>{matching.evidence}</span>
                        ) : line.row.refusal ? (
                          <span className="text-amber-800">{line.row.refusal}</span>
                        ) : line.choice ? (
                          <span>Chosen by hand — no capture date was used.</span>
                        ) : (
                          <span>{line.row.proposals[0]?.evidence ?? "Nothing proposed."}</span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      ) : null}
    </>
  );
}
