"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { ArrowRight, CloudOff } from "lucide-react";

import type { MeasurablePhoto, MeasureTarget } from "@/components/designworkshop/PhotoMeasureField";
import { measurableLengthFields } from "@/components/designworkshop/stageFieldRoles";
import { Dropdown } from "@/components/ui/Dropdown";
import { isUnreachable } from "@/lib/failureTriage";
import { fetchMediaRow } from "@/lib/mediaRows";
import { appendMediaRef, mediaRefRoom } from "@/lib/photoIntake";
import {
  ensureDraft,
  isLocalMediaRef,
  loadDraft,
  putDraftStage,
  readLocalMedia,
  stageLocalMedia,
  syncDesignWorkshopDrafts
} from "@/lib/designWorkshopStore";
import {
  inputValue,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwRegistry,
  type DwRow,
  type DwValue
} from "@/lib/designWorkshops";
import { isTentativeRow, tentativeField, tentativeFirst } from "@/lib/sketchTentative";
import type { MediaFile } from "@/lib/types";

import { UploadTabPanel, type ChosenPhotograph } from "./upload/UploadTabPanel";
import { MeasureFromPhotoCard, type MeasurePhotos, type WorkingPhoto } from "./upload/MeasureFromPhotoCard";
import { decodeToPixels, isDecoded, type DecodedPixels } from "./upload/decodeToPixels";
import { readStageRows, rowKeyOf, rowLabel, type StageRows } from "./stageRows";
import { syncPassLanded, syncPassNote } from "./syncNote";

const REFERENCE_KEY_SEPARATOR = String.fromCharCode(0);

const SKETCH_IMAGE = "image";
const SKETCH_LINE_ART = "lineArtFile";
const PROTOTYPE_MODEL = "modelFile";
const PROTOTYPE_TURNTABLE = "turntablePhotos";

const NO_ROW: DwEntryData = {};

function fieldOf(entity: DwEntity | undefined, key: string) {
  return entity?.fields.find((field) => field.key === key) ?? null;
}

function entityOf(stage: StageRows, entityKey: string): DwEntity | undefined {
  return stage.spec?.entities.find((entity) => entity.key === entityKey);
}

type Target = {
  stageKey: string;
  entityKey: string;
  fieldKey: string;
  rowKey: string;
  multiple: boolean;
  maxItems?: number;
};

function fullNotice(said: string, names: string[]): string {
  const one = names.length === 1;
  return (
    `${said} ${one ? "was" : "were"} not attached — the field is full: ${names.join(", ")}. ` +
    "Remove a file on the stage, then try again."
  );
}

function imageFieldsOf(entity: DwEntity | undefined): DwField[] {
  return (entity?.fields ?? []).filter(
    (field) => !field.deprecated && (field.type === "IMAGE" || field.type === "IMAGE_LIST")
  );
}

function refsOf(value: DwValue | undefined): string[] {
  if (Array.isArray(value)) {
    return value.filter((entry): entry is string => typeof entry === "string" && entry.trim() !== "");
  }
  return typeof value === "string" && value.trim() ? [value] : [];
}

function useMeasurablePhotos(refs: string[]): MeasurePhotos {
  const key = refs.join(REFERENCE_KEY_SEPARATOR);
  const wanted = useMemo(() => (key === "" ? [] : key.split(REFERENCE_KEY_SEPARATOR)), [key]);
  const [state, setState] = useState<MeasurePhotos>(() =>
    refs.length ? { status: "loading" } : { status: "ready", photos: [], unreadable: 0 }
  );

  useEffect(() => {
    if (wanted.length === 0) {
      setState({ status: "ready", photos: [], unreadable: 0 });
      return;
    }

    let cancelled = false;
    const created: string[] = [];
    setState({ status: "loading" });

    void (async () => {
      const photos: MeasurablePhoto[] = [];
      let unreadable = 0;
      let offline = false;

      const resolved = await Promise.all(
        wanted.map(async (ref): Promise<{ photo?: MeasurablePhoto; offline?: boolean }> => {
          if (isLocalMediaRef(ref)) {
            const media = await readLocalMedia(ref);
            if (media?.blob && media.mimeType.startsWith("image/")) {
              const url = URL.createObjectURL(media.blob);
              created.push(url);
              return { photo: { key: ref, name: media.name, url } };
            }
            return {};
          }
          try {
            const media: MediaFile = await fetchMediaRow(ref);
            if (media.mediaType === "IMAGE" && media.url) {
              return { photo: { key: ref, name: media.originalFilename, url: media.url } };
            }
            return {};
          } catch (error) {
            return { offline: isUnreachable(error) };
          }
        })
      );
      for (const outcome of resolved) {
        if (outcome.photo) photos.push(outcome.photo);
        else unreadable += 1;
        if (outcome.offline) offline = true;
      }

      if (cancelled) {
        for (const url of created) URL.revokeObjectURL(url);
        return;
      }

      if (photos.length === 0) {
        setState({
          status: "failed",
          reason: offline
            ? "Offline — uploaded photos can't be shown. Photos on this device can still be measured."
            : "Couldn't open this piece's files. You may not have access."
        });
        return;
      }
      setState({ status: "ready", photos, unreadable });
    })();

    return () => {
      cancelled = true;
      for (const url of created) URL.revokeObjectURL(url);
    };
  }, [wanted]);

  return state;
}

type PhotoPick = { readonly id: number; readonly file: File };

function usePhotoUrl(pick: PhotoPick | null): { id: number; file: File; url: string } | null {
  const [held, setHeld] = useState<{ id: number; file: File; url: string } | null>(null);

  useEffect(() => {
    if (!pick) {
      setHeld(null);
      return;
    }
    const url = URL.createObjectURL(pick.file);
    setHeld({ id: pick.id, file: pick.file, url });
    return () => {
      URL.revokeObjectURL(url);
    };
  }, [pick]);

  return held;
}

function useDecodedPhotograph(
  pick: PhotoPick | null
): { id: number; pixels: DecodedPixels | null; problem: string | null } | null {
  const [decoded, setDecoded] = useState<{ id: number; pixels: DecodedPixels | null; problem: string | null } | null>(
    null
  );

  useEffect(() => {
    if (!pick) {
      setDecoded(null);
      return;
    }
    let cancelled = false;
    setDecoded(null);
    void (async () => {
      const outcome = await decodeToPixels(pick.file);
      if (cancelled) return;
      setDecoded(
        isDecoded(outcome)
          ? { id: pick.id, pixels: outcome, problem: null }
          : { id: pick.id, pixels: null, problem: outcome.reason }
      );
    })();
    return () => {
      cancelled = true;
    };
  }, [pick]);

  return decoded;
}

export function UploadTabHost({
  workshopId,
  registry,
  readOnlyReason = null
}: {
  workshopId: string;
  registry: DwRegistry | null;
  readOnlyReason?: string | null;
}) {
  const [sketches, setSketches] = useState<StageRows | null>(null);
  const [prototypes, setPrototypes] = useState<StageRows | null>(null);
  const [sketchRow, setSketchRow] = useState<string>("");
  const [prototypeRow, setPrototypeRow] = useState<string>("");
  const [notice, setNotice] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const frozen = busy || Boolean(readOnlyReason);

  const [sketchPick, setSketchPick] = useState<PhotoPick | null>(null);
  const [sketchMeasurePick, setSketchMeasurePick] = useState<PhotoPick | null>(null);
  const [prototypeMeasurePick, setPrototypeMeasurePick] = useState<PhotoPick | null>(null);
  const [sketchPhotoFiled, setSketchPhotoFiled] = useState<{ id: number; rowKey: string } | null>(null);
  const pickCount = useRef(0);

  const heldSketchPhoto = usePhotoUrl(sketchPick);
  const decodedSketchPhoto = useDecodedPhotograph(sketchPick);
  const heldMeasurePhoto = usePhotoUrl(sketchMeasurePick);
  const heldPrototypeMeasurePhoto = usePhotoUrl(prototypeMeasurePick);

  const sketchPhotograph = useMemo<ChosenPhotograph | null>(() => {
    if (!heldSketchPhoto) return null;
    const settled = decodedSketchPhoto && decodedSketchPhoto.id === heldSketchPhoto.id ? decodedSketchPhoto : null;
    return {
      id: heldSketchPhoto.id,
      file: heldSketchPhoto.file,
      url: heldSketchPhoto.url,
      pixels: settled?.pixels ?? null,
      problem: settled?.problem ?? null
    };
  }, [heldSketchPhoto, decodedSketchPhoto]);

  const chooseSketchPhoto = useCallback((file: File | null) => {
    pickCount.current += 1;
    setSketchPick(file ? { id: pickCount.current, file } : null);
    setSketchPhotoFiled(null);
  }, []);

  const chooseSketchMeasurePhoto = useCallback((file: File | null) => {
    pickCount.current += 1;
    setSketchMeasurePick(file ? { id: pickCount.current, file } : null);
  }, []);

  const choosePrototypeMeasurePhoto = useCallback((file: File | null) => {
    pickCount.current += 1;
    setPrototypeMeasurePick(file ? { id: pickCount.current, file } : null);
  }, []);

  const sketchPhotoOnRow =
    sketchPhotograph !== null &&
    sketchPhotoFiled?.id === sketchPhotograph.id &&
    sketchPhotoFiled?.rowKey === sketchRow;

  const sketchWorking = useMemo<WorkingPhoto | null>(() => {
    if (heldMeasurePhoto) {
      return {
        photo: {
          key: `dwpick:${heldMeasurePhoto.id}`,
          name: heldMeasurePhoto.file.name,
          url: heldMeasurePhoto.url
        },
        source: "own"
      };
    }
    if (sketchPhotograph && !sketchPhotoOnRow) {
      return {
        photo: {
          key: `dwpick:${sketchPhotograph.id}`,
          name: sketchPhotograph.file.name,
          url: sketchPhotograph.url
        },
        source: "shared"
      };
    }
    return null;
  }, [heldMeasurePhoto, sketchPhotograph, sketchPhotoOnRow]);

  const prototypeWorking = useMemo<WorkingPhoto | null>(() => {
    if (!heldPrototypeMeasurePhoto) return null;
    return {
      photo: {
        key: `dwpick:${heldPrototypeMeasurePhoto.id}`,
        name: heldPrototypeMeasurePhoto.file.name,
        url: heldPrototypeMeasurePhoto.url
      },
      source: "own"
    };
  }, [heldPrototypeMeasurePhoto]);

  const reload = useCallback(async () => {
    const [sketch, prototype] = await Promise.all([
      readStageRows(workshopId, registry, "sketch", { fromServer: true }),
      readStageRows(workshopId, registry, "prototype", { fromServer: true })
    ]);
    setSketches(sketch);
    setPrototypes(prototype);
    setSketchRow((current) => current || (firstOffered(sketch.rows) ?? ""));
    setPrototypeRow((current) => current || (firstOffered(prototype.rows) ?? ""));
  }, [registry, workshopId]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        await reload();
      } catch {
        if (!cancelled) {
          setProblem("Couldn't load sketches and prototypes.");
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [reload]);

  const attach = useCallback(
    async (target: Target, files: File[], what: (landedCount: number) => string): Promise<boolean> => {
      if (files.length === 0) return false;
      setBusy(true);
      setProblem(null);
      setNotice(null);
      let landed: string | null = null;
      let took = 0;
      const turnedAway: string[] = [];
      try {
        const draft = await loadDraft(workshopId);
        const stage = draft?.stages[target.stageKey];
        if (!draft || !stage) {
          setProblem(
            "Not attached — open its stage once online first."
          );
          return false;
        }
        const rows = [...(stage.collections[target.entityKey] ?? [])];
        const index = rows.findIndex((row) => rowKeyOf(row) === target.rowKey);
        if (index < 0) {
          setProblem(
            "Not attached — that piece is gone. Reload and choose another."
          );
          return false;
        }
        const row = { ...rows[index] };
        for (const file of files) {
          if (!mediaRefRoom(row[target.fieldKey], target.multiple, target.maxItems)) {
            turnedAway.push(file.name);
            continue;
          }
          const { ref } = await stageLocalMedia(workshopId, file, {
            stageKey: target.stageKey,
            entityKey: target.entityKey,
            fieldKey: target.fieldKey,
            clientKey: target.rowKey
          });
          row[target.fieldKey] = appendMediaRef(row[target.fieldKey], ref, target.multiple, target.maxItems);
          took += 1;
        }
        if (took === 0) {
          setProblem(fullNotice(what(turnedAway.length), turnedAway));
          return false;
        }
        rows[index] = row;
        await putDraftStage(workshopId, target.stageKey, {
          singletons: stage.singletons,
          collections: { ...stage.collections, [target.entityKey]: rows },
          removedFrom: stage.removedFrom
        });
        landed = rowLabel(row, index);
        await reload();
        setNotice(`${what(took)} attached to “${landed}” on this device. Uploading…`);
        if (turnedAway.length) setProblem(fullNotice(what(turnedAway.length), turnedAway));
      } catch {
        setProblem(
          "Couldn't save to this device. Leave private mode or free some space."
        );
        return false;
      } finally {
        setBusy(false);
      }

      try {
        const result = await syncDesignWorkshopDrafts();
        await reload();
        setNotice(
          `${what(took)} attached to “${landed}”. ${syncPassNote(result, took === 1 ? "this file is" : "these files are")}` +
            (syncPassLanded(result) ? "" : " Kept on this device until it uploads.")
        );
      } catch (error) {
        setNotice(
          isUnreachable(error)
            ? `${what(took)} is saved on this device and will upload when you're back online.`
            : `${what(took)} is saved on this device and will upload on the next sync.`
        );
      }
      return turnedAway.length === 0;
    },
    [reload, workshopId]
  );

  const addRow = useCallback(
    async (entityKey: "sketch" | "prototype") => {
      const half = entityKey === "sketch" ? sketches : prototypes;
      if (!half?.stageKey) return;
      if (!half.reconciled) {
        setProblem(
          "This workshop's " +
            (entityKey === "sketch" ? "sketches" : "prototypes") +
            " aren't on this device yet. Open the workshop once online."
        );
        return;
      }
      setBusy(true);
      setProblem(null);
      setNotice(null);
      try {
        const draft = await ensureDraft(workshopId);
        const stage = draft?.stages[half.stageKey];
        const rows = [...(stage?.collections[entityKey] ?? [])];
        const key = `${entityKey}-${crypto.randomUUID()}`;
        rows.push({ _clientKey: key } as DwRow);
        await putDraftStage(workshopId, half.stageKey, {
          singletons: stage?.singletons ?? {},
          collections: { ...(stage?.collections ?? {}), [entityKey]: rows },
          removedFrom: stage?.removedFrom
        });
        await reload();
        if (entityKey === "sketch") setSketchRow(key);
        else setPrototypeRow(key);
        setNotice(
          `New ${entityKey} added. Attach files here; fill in the rest on its stage.`
        );
        try {
          const result = await syncDesignWorkshopDrafts();
          setNotice(
            (current) => `${current ?? ""} ${syncPassNote(result, `the new ${entityKey} is`)}`.trim()
          );
        } catch {
          setNotice((current) => `${current ?? ""} It will upload when you're back online.`.trim());
        }
      } catch {
        setProblem(`Couldn't add a ${entityKey}.`);
      } finally {
        setBusy(false);
      }
    },
    [prototypes, reload, sketches, workshopId]
  );

  const sketchEntity = sketches ? entityOf(sketches, "sketch") : undefined;
  const prototypeEntity = prototypes ? entityOf(prototypes, "prototype") : undefined;
  const lineArt = fieldOf(sketchEntity, SKETCH_LINE_ART);
  const sketchImage = fieldOf(sketchEntity, SKETCH_IMAGE);
  const model = fieldOf(prototypeEntity, PROTOTYPE_MODEL);
  const turntable = fieldOf(prototypeEntity, PROTOTYPE_TURNTABLE);

  const chosenPrototype = (prototypes?.rows ?? []).find((row) => rowKeyOf(row) === prototypeRow);
  const chosenPrototypeIndex = (prototypes?.rows ?? []).findIndex((row) => rowKeyOf(row) === prototypeRow);
  const turntableHeld = Array.isArray(chosenPrototype?.[PROTOTYPE_TURNTABLE])
    ? (chosenPrototype?.[PROTOTYPE_TURNTABLE] as string[]).length
    : 0;

  const chosenSketchIndex = (sketches?.rows ?? []).findIndex((row) => rowKeyOf(row) === sketchRow);
  const chosenSketch = chosenSketchIndex >= 0 ? sketches?.rows[chosenSketchIndex] : undefined;
  const sketchImageHeld = (() => {
    const value = chosenSketch?.[SKETCH_IMAGE];
    if (Array.isArray(value)) return value.length;
    return typeof value === "string" && value.trim() ? 1 : 0;
  })();
  const sketchLineArtHeld = (() => {
    const value = chosenSketch?.[SKETCH_LINE_ART];
    if (Array.isArray(value)) return value.length;
    return typeof value === "string" && value.trim() ? 1 : 0;
  })();

  const sketchTargets = useMemo<MeasureTarget[]>(
    () => (sketchEntity ? measurableLengthFields(sketchEntity) : []),
    [sketchEntity]
  );
  const prototypeTargets = useMemo<MeasureTarget[]>(
    () => (prototypeEntity ? measurableLengthFields(prototypeEntity) : []),
    [prototypeEntity]
  );
  const sketchPhotoFields = useMemo(() => imageFieldsOf(sketchEntity), [sketchEntity]);
  const prototypePhotoFields = useMemo(() => imageFieldsOf(prototypeEntity), [prototypeEntity]);
  const sketchPhotoRefs = useMemo(
    () => sketchPhotoFields.flatMap((field) => refsOf(chosenSketch?.[field.key])),
    [sketchPhotoFields, chosenSketch]
  );
  const prototypePhotoRefs = useMemo(
    () => prototypePhotoFields.flatMap((field) => refsOf(chosenPrototype?.[field.key])),
    [prototypePhotoFields, chosenPrototype]
  );
  const sketchPhotos = useMeasurablePhotos(sketchPhotoRefs);
  const prototypePhotos = useMeasurablePhotos(prototypePhotoRefs);

  const proposeDimension = useCallback(
    async (
      target: { stageKey: string; entityKey: string; rowKey: string },
      field: DwField,
      unit: string,
      value: DwValue
    ): Promise<void> => {
      const shown = `${inputValue(value)} ${unit}`.trim();
      setBusy(true);
      setProblem(null);
      setNotice(null);
      let landed: string | null = null;
      try {
        const draft = await loadDraft(workshopId);
        const stage = draft?.stages[target.stageKey];
        if (!draft || !stage) {
          setProblem(
            `Not saved — open the stage for “${field.label}” once online first.`
          );
          return;
        }
        const rows = [...(stage.collections[target.entityKey] ?? [])];
        const index = rows.findIndex((row) => rowKeyOf(row) === target.rowKey);
        if (index < 0) {
          setProblem(
            "Not saved — that piece is gone. Reload and choose another."
          );
          return;
        }
        const row = { ...rows[index], [field.key]: value };
        rows[index] = row;
        await putDraftStage(workshopId, target.stageKey, {
          singletons: stage.singletons,
          collections: { ...stage.collections, [target.entityKey]: rows },
          removedFrom: stage.removedFrom
        });
        landed = rowLabel(row, index);
        await reload();
        setNotice(`“${field.label}” on “${landed}” now reads ${shown} on this device. Uploading…`);
      } catch {
        setProblem(
          "Couldn't save to this device. Leave private mode or free some space."
        );
        return;
      } finally {
        setBusy(false);
      }

      try {
        const result = await syncDesignWorkshopDrafts();
        await reload();
        setNotice(
          `“${field.label}” on “${landed}” now reads ${shown}. ${syncPassNote(result, "this measurement is")}` +
            (syncPassLanded(result) ? "" : " Kept on this device until it uploads.")
        );
      } catch (error) {
        setNotice(
          isUnreachable(error)
            ? `“${field.label}” on “${landed}” is ${shown} on this device. It uploads when you're online.`
            : `“${field.label}” on “${landed}” is ${shown} on this device. It uploads with the next sync.`
        );
      }
    },
    [reload, workshopId]
  );

  const sketchReady = Boolean(sketches?.reconciled && sketchRow && lineArt);
  const prototypeReady = Boolean(prototypes?.reconciled && prototypeRow && model);
  const anyStale = Boolean(sketches && prototypes && !(sketches.reconciled && prototypes.reconciled));

  const anythingReady = sketchReady || prototypeReady;

  function refuse(half: "sketch" | "prototype", said = "This file wasn't attached") {
    const rows = half === "sketch" ? sketches : prototypes;
    const chosen = half === "sketch" ? sketchRow : prototypeRow;
    setNotice(null);
    setProblem(
      rows && rows.rows.length === 0
        ? `${said}: there are no ${half}s in this workshop yet. Add one on its stage first.`
        : !chosen
          ? `${said}: choose which ${half} it belongs to first.`
          : `${said}: open the ${half} stage once online first.`
    );
  }

  const MEASURE_REFUSED = "That measurement wasn't saved";

  return (
    <div className="grid gap-4">
      <div className="panel p-4">
        <h2 className="font-display text-lg font-bold text-ink-900">Where this goes</h2>
        <p className="mt-1 max-w-3xl text-sm leading-6 text-ink-muted">
          Choose the piece, then add the file.
        </p>

        <div className="mt-3 grid gap-3 md:grid-cols-2">
          <RowPicker
            label="Sketch"
            rows={sketches?.rows ?? []}
            tentativeWord={tentativeField(sketchEntity)?.label ?? null}
            value={sketchRow}
            onChange={setSketchRow}
            emptyHref={
              sketches?.stageKey ? `/design-workshops/${workshopId}/stages/${sketches.stageKey}` : null
            }
            emptyWord="No sketches in this workshop yet"
            addWord="Add a sketch"
            onAdd={sketches?.reconciled ? () => void addRow("sketch") : undefined}
            busy={frozen}
          />
          <RowPicker
            label="Prototype"
            rows={prototypes?.rows ?? []}
            tentativeWord={tentativeField(prototypeEntity)?.label ?? null}
            value={prototypeRow}
            onChange={setPrototypeRow}
            emptyHref={
              prototypes?.stageKey ? `/design-workshops/${workshopId}/stages/${prototypes.stageKey}` : null
            }
            emptyWord="No prototypes in this workshop yet"
            addWord="Add a prototype"
            onAdd={prototypes?.reconciled ? () => void addRow("prototype") : undefined}
            busy={frozen}
          />
        </div>

        {chosenSketch && (sketchImageHeld > 0 || sketchLineArtHeld > 0) ? (
          <p className="mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
            {sketchImageHeld > 0 && sketchLineArtHeld > 0
              ? `Attaching replaces the current ${sketchImage?.label ?? "sketch image"} or ${lineArt?.label ?? "line art file"}.`
              : sketchImageHeld > 0
                ? `Attaching a photo replaces the current ${sketchImage?.label ?? "sketch image"}.`
                : `Attaching line art replaces the current ${lineArt?.label ?? "line art file"}.`}
          </p>
        ) : null}

        {anyStale ? (
          <p className="mt-3 flex items-start gap-2 rounded-md border border-line-200 bg-amber-100 px-3 py-2 text-sm text-amber-800">
            <CloudOff className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <span>
              Open these stages once online to attach from here.
            </span>
          </p>
        ) : null}
        {notice ? <p className="mt-3 text-sm text-ink-muted">{notice}</p> : null}
        {problem ? (
          <p className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">
            {problem}
          </p>
        ) : null}
      </div>

      {lineArt && model && turntable ? (
        <UploadTabPanel
          sketchTargetLabel={lineArt.label}
          modelLabel={model.label}
          turntableLabel={turntable.label}
          turntableCount={turntableHeld}
          disabled={frozen || !anythingReady}
          sketchPhotograph={sketchPhotograph}
          sketchImageLabel={sketchImage?.label ?? "Sketch image"}
          sketchRowName={chosenSketch ? rowLabel(chosenSketch, chosenSketchIndex) : null}
          sketchPhotoFiled={sketchPhotoOnRow}
          onChooseSketchPhoto={chooseSketchPhoto}
          onAttachSketch={async (file) => {
            if (!sketches?.stageKey || !sketchReady) {
              refuse("sketch");
              return false;
            }
            return attach(
              {
                stageKey: sketches.stageKey,
                entityKey: "sketch",
                fieldKey: SKETCH_LINE_ART,
                rowKey: sketchRow,
                multiple: false,
                maxItems: lineArt.maxItems
              },
              [file],
              () => lineArt.label
            );
          }}
          onAttachSketchSource={async (file) => {
            if (!sketches?.stageKey || !sketchReady || !sketchImage) {
              refuse("sketch");
              return false;
            }
            const filedPick = sketchPhotograph?.file === file ? sketchPhotograph.id : 0;
            const filedRow = sketchRow;
            const landed = await attach(
              {
                stageKey: sketches.stageKey,
                entityKey: "sketch",
                fieldKey: SKETCH_IMAGE,
                rowKey: sketchRow,
                multiple: sketchImage.type === "IMAGE_LIST",
                maxItems: sketchImage.maxItems
              },
              [file],
              () => sketchImage.label
            );
            if (landed && filedPick) setSketchPhotoFiled({ id: filedPick, rowKey: filedRow });
            return landed;
          }}
          onAttachModel={async (file) => {
            if (!prototypes?.stageKey || !prototypeReady) {
              refuse("prototype");
              return false;
            }
            return attach(
              {
                stageKey: prototypes.stageKey,
                entityKey: "prototype",
                fieldKey: PROTOTYPE_MODEL,
                rowKey: prototypeRow,
                multiple: false,
                maxItems: model.maxItems
              },
              [file],
              () => model.label
            );
          }}
          onAttachTurntable={async (files) => {
            if (!prototypes?.stageKey || !prototypeReady) {
              refuse("prototype");
              return false;
            }
            return attach(
              {
                stageKey: prototypes.stageKey,
                entityKey: "prototype",
                fieldKey: PROTOTYPE_TURNTABLE,
                rowKey: prototypeRow,
                multiple: turntable.type === "IMAGE_LIST",
                maxItems: turntable.maxItems
              },
              files,
              (count) => (count === 1 ? `1 frame of “${turntable.label}”` : `${count} frames of “${turntable.label}”`)
            );
          }}
          sketchMeasure={
            <MeasureFromPhotoCard
              what="sketch"
              rowName={chosenSketch ? rowLabel(chosenSketch, chosenSketchIndex) : null}
              photos={sketchPhotos}
              targets={sketchTargets}
              row={chosenSketch ?? NO_ROW}
              photoFieldLabels={sketchPhotoFields.map((entry) => entry.label)}
              disabled={frozen || !sketchReady}
              working={sketchWorking}
              onUseDifferentPhoto={chooseSketchMeasurePhoto}
              onPropose={(key, value) => {
                const chosenTarget = sketchTargets.find((entry) => entry.field.key === key);
                if (!sketches?.stageKey || !sketchReady || !chosenTarget) {
                  refuse("sketch", MEASURE_REFUSED);
                  return;
                }
                void proposeDimension(
                  { stageKey: sketches.stageKey, entityKey: "sketch", rowKey: sketchRow },
                  chosenTarget.field,
                  chosenTarget.unit,
                  value
                );
              }}
            />
          }
          prototypeMeasure={
            <MeasureFromPhotoCard
              what="prototype"
              rowName={chosenPrototype ? rowLabel(chosenPrototype, chosenPrototypeIndex) : null}
              photos={prototypePhotos}
              targets={prototypeTargets}
              row={chosenPrototype ?? NO_ROW}
              photoFieldLabels={prototypePhotoFields.map((entry) => entry.label)}
              disabled={frozen || !prototypeReady}
              onUseDifferentPhoto={choosePrototypeMeasurePhoto}
              working={prototypeWorking}
              onPropose={(key, value) => {
                const chosenTarget = prototypeTargets.find((entry) => entry.field.key === key);
                if (!prototypes?.stageKey || !prototypeReady || !chosenTarget) {
                  refuse("prototype", MEASURE_REFUSED);
                  return;
                }
                void proposeDimension(
                  { stageKey: prototypes.stageKey, entityKey: "prototype", rowKey: prototypeRow },
                  chosenTarget.field,
                  chosenTarget.unit,
                  value
                );
              }}
            />
          }
        />
      ) : (
        <p className="panel px-4 py-6 text-sm text-ink-muted">
          {registry === null
            ? "Couldn't load the form. Open the workshop once online."
            : "Unavailable. Reload to update the app."}
        </p>
      )}
    </div>
  );
}

function firstOffered(rows: readonly DwRow[]): string | null {
  const [first] = tentativeFirst(rows, isTentativeRow);
  return first ? rowKeyOf(first) : null;
}

function RowPicker({
  label,
  rows,
  tentativeWord,
  value,
  onChange,
  emptyHref,
  emptyWord,
  addWord,
  onAdd,
  busy
}: {
  label: string;
  rows: DwRow[];
  tentativeWord: string | null;
  value: string;
  onChange: (value: string) => void;
  emptyHref: string | null;
  emptyWord: string;
  addWord: string;
  onAdd?: () => void;
  busy: boolean;
}) {
  if (rows.length === 0) {
    return (
      <div className="rounded-md border border-dashed border-line-200 bg-surface-50 px-3 py-3 text-sm">
        <p className="text-ink-700">{emptyWord}</p>
        {onAdd ? (
          <button type="button" className="field-button mt-2" onClick={onAdd} disabled={busy}>
            {addWord}
          </button>
        ) : null}
        {emptyHref ? (
          <Link href={emptyHref} className="mt-2 block text-sm font-medium text-purple-700 dark:text-purple-300">
            <span className="inline-flex items-center gap-1">
              Add one on its stage
              <ArrowRight className="h-4 w-4" aria-hidden />
            </span>
          </Link>
        ) : null}
      </div>
    );
  }
  return (
    <div className="grid min-w-0 gap-1">
      <span className="field-label">{label}</span>
      <Dropdown
        value={value}
        onChange={onChange}
        options={tentativeFirst(
          rows.map((row, index) => ({ row, index })),
          (pair) => isTentativeRow(pair.row)
        ).map(({ row, index }) => ({
          value: rowKeyOf(row) ?? "",
          label: rowLabel(row, index),
          hint: tentativeWord && isTentativeRow(row) ? tentativeWord : undefined
        }))}
        ariaLabel={label}
        searchable
        advanceOnSelect={false}
      />
      {onAdd ? (
        <button
          type="button"
          className="field-button-secondary justify-self-start"
          onClick={onAdd}
          disabled={busy}
        >
          {addWord}
        </button>
      ) : null}
    </div>
  );
}
