"use client";

import { use, useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { Images } from "lucide-react";

import { HeldPostNotice, useHeldPostRefusal, writesHeld } from "@/components/designworkshop/HeldPostNotice";
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

const EXIF_CONCURRENCY = 6;

type Destination = {
  key: string;
  stageKey: string;
  stageNumber: number;
  stageTitle: string;
  entityKey: string;
  rowKey: string | null;
  fieldKey: string;
  label: string;
  multiple: boolean;
  maxItems?: number;
};

const PROPOSED_GROUP = "Proposed from the capture date";
const EVERYWHERE_GROUP = "Every other place a photograph can go";

function destinationOptions(destinations: Destination[], proposed: Destination[]): DropdownOption[] {
  const proposedKeys = new Set(proposed.map((destination) => destination.key));
  return [
    { value: "", label: "Leave out" },
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

type IntakeLine = {
  file: File;
  row: PhotoIntakeRow;
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
  const postRefusal = useHeldPostRefusal(id);
  const heldNoticeId = useId();

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [{ registry: loaded }, found] = await Promise.all([loadRegistry(), loadDraft(id)]);
        if (cancelled) return;
        setRegistry(loaded);
        setDraft(found);
        if (!found) {
          setLoadError(
            "Not on this device yet. Open its stages once online, then come back."
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

  const stageData = useMemo<Record<string, DwStageData>>(() => {
    if (!draft) return {};
    return Object.fromEntries(Object.entries(draft.stages).map(([key, stage]) => [key, stageDataOf(stage)]));
  }, [draft]);

  const anchors = useMemo<WorkshopAnchor[]>(
    () => (registry ? buildAnchors(registry, stageData) : []),
    [registry, stageData]
  );

  const timeZone = DEFAULT_TIMEZONE;

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

  const defaultDestinationFor = useCallback(
    (row: PhotoIntakeRow): string => {
      for (const proposal of row.proposals) {
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

  const onPick = useCallback(
    async (picked: FileList | null) => {
      if (!picked?.length) return;
      setProblem(null);
      setNotice(null);
      const files = Array.from(picked);
      setReading({ done: 0, total: files.length });

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
      if (fileInput.current) fileInput.current.value = "";
    },
    [anchors, defaultDestinationFor, timeZone]
  );

  const chosen = useMemo(() => lines.filter((line) => line.choice), [lines]);

  async function confirm() {
    if (!draft || !chosen.length || writesHeld(postRefusal)) return;
    setConfirming(true);
    setProblem(null);
    setNotice(null);
    try {
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
      const full: Array<{ file: string; label: string; declaredCap?: number }> = [];
      const touched = new Set<string>();
      const unplaceable: string[] = [];

      for (const line of chosen) {
        const destination = destinationsByKey.get(line.choice);
        if (!destination) {
          unplaceable.push(line.file.name);
          continue;
        }
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
      const refusedNames = new Set([...missed, ...full.map((entry) => entry.file)]);
      setLines((current) => current.filter((line) => !line.choice || refusedNames.has(line.file.name)));
      if (attached) {
        setNotice(
          `${attached} photograph${attached === 1 ? "" : "s"} attached on this device across ${touched.size} stage${touched.size === 1 ? "" : "s"}. ` +
            "They upload when you're online."
        );
      }
      const complaints: string[] = [];
      if (missed.length) {
        complaints.push(
          `${missed.length} not placed — their entry is gone: ${missed.join(", ")}. Choose another destination.`
        );
      }
      if (full.length) {
        const one = full.length === 1;
        const caps = new Set(full.map((entry) => entry.declaredCap));
        const declared = caps.size === 1 ? [...caps][0] : undefined;
        const because =
          declared && declared > 0
            ? `holds at most ${declared} ${declared === 1 ? "entry" : "entries"} and is full`
            : "is full";
        complaints.push(
          `${full.length} ${one ? "was" : "were"} not attached because the field ${one ? "it was" : "they were"} headed for ${because}: ` +
            `${full.map((entry) => `${entry.file} (${entry.label})`).join(", ")}. ` +
            `Free space there or choose another destination, then confirm again.`
        );
      }
      if (complaints.length) setProblem(complaints.join(" "));
    } catch {
      setProblem(
        "Couldn't save to this device. Leave private mode or free some space."
      );
    } finally {
      setConfirming(false);
    }
  }

  const summary = useMemo(() => intakeSummary(lines.map((line) => line.row)), [lines]);
  const datedAnchors = anchors.length;

  return (
    <>
      <PageHeader
        title="Bulk photo import"
        description="Sort camera photos into stages by date."
        icon={<Images className="h-5 w-5" aria-hidden />}
        actions={
          <Link href={`/design-workshops/${id}`} className="field-button-secondary">
            All stages
          </Link>
        }
      />

      <HeldPostNotice refusal={postRefusal} id={heldNoticeId} sayPending />
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

      <div className="panel mb-5 p-4">
        <h2 className="font-display text-base font-semibold text-ink-900">How the dates are read</h2>
        <p className="mt-2 text-sm leading-6 text-ink-700">
          Capture times are read as <strong>{timeZone}</strong>. Nothing is attached until you confirm.
        </p>
        <p className="mt-2 text-sm leading-6 text-ink-muted">
          {datedAnchors > 0 ? (
            <>
              Matching against {datedAnchors} dated {datedAnchors === 1 ? "entry" : "entries"}.
            </>
          ) : (
            <>
              No dates yet. Fill in dates on stage 1 and daily logs on stage 13.
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
          disabled={Boolean(reading) || confirming || writesHeld(postRefusal)}
          aria-describedby={postRefusal ? heldNoticeId : undefined}
          onChange={(event) => void onPick(event.target.files)}
          className="mt-2 block w-full text-sm text-ink-700 file:mr-3 file:cursor-pointer file:rounded-md file:border-0 file:bg-purple-700 file:px-4 file:py-2 file:text-sm file:font-medium file:text-white hover:file:bg-purple-800"
        />
        <p className="mt-2 text-xs leading-5 text-ink-500">
          Nothing uploads until you confirm.
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
              <strong className="text-ink-900">{summary.total}</strong> chosen ·{" "}
              <strong className="text-ink-900">{chosen.length}</strong> ready to attach ·{" "}
              <strong className="text-ink-900">{summary.total - chosen.length}</strong> need you
            </p>
            <button
              type="button"
              className="field-button"
              disabled={!chosen.length || confirming || Boolean(reading) || writesHeld(postRefusal)}
              aria-describedby={postRefusal ? heldNoticeId : undefined}
              onClick={() => void confirm()}
            >
              {confirming ? "Attaching…" : `Confirm ${chosen.length} photograph${chosen.length === 1 ? "" : "s"}`}
            </button>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full min-w-4xl border-collapse text-sm">
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
                  const selectId = `photo-intake-destination-${index}`;
                  const chosenDestination = line.choice ? destinationsByKey.get(line.choice) : null;
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
                          <span>Chosen by hand.</span>
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
