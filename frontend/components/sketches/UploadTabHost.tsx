"use client";

/**
 * The UPLOAD tab's host: it chooses WHERE a file lands and hands the panel the four labels.
 *
 * ── THE DIVISION OF LABOUR, WHICH IS THE WHOLE DESIGN ───────────────────────────────────────────
 *
 * `components/sketches/upload/` produces `File`s and knows nothing about media ids, presigning or
 * the draft store — its own header says so, and it is right: an extra that uploaded its own file
 * would be a second upload path to keep working offline. This file is the other half. It:
 *
 *   * finds the two stages that declare `sketch` and `prototype`, out of the registry rather than
 *     out of a hardcoded stage number;
 *   * lets the designer say WHICH sketch and WHICH prototype a file belongs to, because a workshop
 *     has eight of each and a file with no row is a file nothing can ever print;
 *   * reads the four field labels off the registry, so the panel says "Line art / vector file"
 *     because that is what the schema calls it today;
 *   * writes each file into the draft with `stageLocalMedia` + `putDraftStage`, which is the same
 *     path a photograph attached on a stage form takes and the same one the bulk photo importer
 *     uses — so it inherits retries, resumption, the orphan sweep and offline durability without a
 *     line of upload code here.
 *
 * ── WHY THE PHOTOGRAPH AND THE TRACED DRAWING GO TO DIFFERENT FIELDS ────────────────────────────
 *
 * `sketch.image` is a single IMAGE and attaching to it REPLACES what is there. So the source
 * photograph goes to `image` and the derived line art goes to `lineArtFile`, and the panel keeps
 * them as two callbacks precisely so one cannot be written over the other. `docs/MEDIA_PIPELINE.md`
 * §5 is the rule behind it: the original file IS the artifact and is never re-encoded or displaced
 * by something computed from it.
 *
 * AND WHAT THE CHOSEN ROW ALREADY HOLDS IS PRINTED BESIDE THE PICKER, because "replaces" is a thing
 * a designer has to be told BEFORE the file chooser opens, not after. Both of these fields are
 * single files and `sketch.image` is required and printed in the report gallery, so a silent
 * overwrite loses a photograph out of a submitted document. See `sketchImageHeld` below.
 *
 * ── NOTHING IS ATTACHED TO A ROW THIS BROWSER HAS NOT READ ──────────────────────────────────────
 *
 * `putDraftStage` writes the stage's entities wholesale, so writing into a collection this device
 * never downloaded would replace the repository's rows with the handful this browser happens to
 * hold. `readStageRows` reports whether the repository's copy was folded in; until it has been,
 * this tab says so and attaches nothing. The bytes are never at risk either way — they are only
 * staged once a destination row exists.
 */

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { ArrowRight, CloudOff } from "lucide-react";

import { appendMediaRef } from "@/lib/photoIntake";
import { loadDraft, putDraftStage, stageLocalMedia } from "@/lib/designWorkshopStore";
import type { DwEntity, DwRegistry, DwRow } from "@/lib/designWorkshops";

import { UploadTabPanel } from "./upload/UploadTabPanel";
import { readStageRows, rowKeyOf, rowLabel, type StageRows } from "./stageRows";

/**
 * The four registry fields this tab writes into.
 *
 * NAMED HERE AND CHECKED AGAINST THE REGISTRY AT RUNTIME rather than assumed: if one of them is
 * renamed in `stage_definitions.py` the label lookup returns nothing and the tab says the field is
 * not in this build's schema, which is a sentence a designer can act on. Writing into a key the
 * server no longer declares would put the file in `droppedKeys` and lose it quietly.
 */
const SKETCH_IMAGE = "image";
const SKETCH_LINE_ART = "lineArtFile";
const PROTOTYPE_MODEL = "modelFile";
const PROTOTYPE_TURNTABLE = "turntablePhotos";

function fieldOf(entity: DwEntity | undefined, key: string) {
  return entity?.fields.find((field) => field.key === key) ?? null;
}

function entityOf(stage: StageRows, entityKey: string): DwEntity | undefined {
  return stage.spec?.entities.find((entity) => entity.key === entityKey);
}

/** One destination: a row of one collection, and the field on it the bytes are going into. */
type Target = {
  stageKey: string;
  entityKey: string;
  fieldKey: string;
  rowKey: string;
  /** True for IMAGE_LIST, where a file is appended rather than replacing what is there. */
  multiple: boolean;
};

export function UploadTabHost({ workshopId, registry }: { workshopId: string; registry: DwRegistry | null }) {
  const [sketches, setSketches] = useState<StageRows | null>(null);
  const [prototypes, setPrototypes] = useState<StageRows | null>(null);
  const [sketchRow, setSketchRow] = useState<string>("");
  const [prototypeRow, setPrototypeRow] = useState<string>("");
  const [notice, setNotice] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback(async () => {
    const [sketch, prototype] = await Promise.all([
      readStageRows(workshopId, registry, "sketch", { fromServer: true }),
      readStageRows(workshopId, registry, "prototype", { fromServer: true })
    ]);
    setSketches(sketch);
    setPrototypes(prototype);
    // The first row is preselected rather than left blank: a picker whose default is "choose one"
    // makes the commonest case — a workshop with one prototype in it — two actions instead of one.
    setSketchRow((current) => current || (sketch.rows[0] ? (rowKeyOf(sketch.rows[0]) ?? "") : ""));
    setPrototypeRow((current) => current || (prototype.rows[0] ? (rowKeyOf(prototype.rows[0]) ?? "") : ""));
  }, [registry, workshopId]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        await reload();
      } catch {
        if (!cancelled) {
          setProblem("The sketches and prototypes of this workshop could not be read on this device.");
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [reload]);

  const attach = useCallback(
    async (target: Target, file: File, what: string) => {
      setBusy(true);
      setProblem(null);
      setNotice(null);
      try {
        const draft = await loadDraft(workshopId);
        const stage = draft?.stages[target.stageKey];
        if (!draft || !stage) {
          setProblem(
            "This file has not been attached: this browser holds no copy of the stage it belongs to. Open that stage once with a connection, then try again."
          );
          return;
        }
        const rows = [...(stage.collections[target.entityKey] ?? [])];
        const index = rows.findIndex((row) => rowKeyOf(row) === target.rowKey);
        if (index < 0) {
          // The row went away between this tab reading it and the file being handed over — another
          // tab, or a colleague's deletion arriving on a sync. Naming it is the only honest answer.
          setProblem(
            "This file has not been attached: the row it was headed for is no longer in this workshop. Reload the tab and choose another."
          );
          return;
        }
        const { ref } = await stageLocalMedia(workshopId, file, {
          stageKey: target.stageKey,
          entityKey: target.entityKey,
          fieldKey: target.fieldKey,
          clientKey: target.rowKey
        });
        const row = { ...rows[index] };
        row[target.fieldKey] = appendMediaRef(row[target.fieldKey], ref, target.multiple);
        rows[index] = row;
        await putDraftStage(workshopId, target.stageKey, {
          singletons: stage.singletons,
          collections: { ...stage.collections, [target.entityKey]: rows },
          removedFrom: stage.removedFrom
        });
        await reload();
        setNotice(
          `${what} attached to “${rowLabel(row, index)}” on this device. It uploads itself when this device next has a connection, and the copy here is kept until the repository confirms it.`
        );
      } catch {
        setProblem(
          "Nothing could be written to this device's storage. If the browser is in private mode or its storage is full, the file cannot be kept here — free some space and try again."
        );
      } finally {
        setBusy(false);
      }
    },
    [reload, workshopId]
  );

  const sketchEntity = sketches ? entityOf(sketches, "sketch") : undefined;
  const prototypeEntity = prototypes ? entityOf(prototypes, "prototype") : undefined;
  const lineArt = fieldOf(sketchEntity, SKETCH_LINE_ART);
  const sketchImage = fieldOf(sketchEntity, SKETCH_IMAGE);
  const model = fieldOf(prototypeEntity, PROTOTYPE_MODEL);
  const turntable = fieldOf(prototypeEntity, PROTOTYPE_TURNTABLE);

  const chosenPrototype = (prototypes?.rows ?? []).find((row) => rowKeyOf(row) === prototypeRow);
  const turntableHeld = Array.isArray(chosenPrototype?.[PROTOTYPE_TURNTABLE])
    ? (chosenPrototype?.[PROTOTYPE_TURNTABLE] as string[]).length
    : 0;

  /*
    WHAT THE CHOSEN SKETCH ALREADY HOLDS, SAID BEFORE THE PICKER FIRES.

    `sketch.image` is a single IMAGE — `f("image", "Sketch image", IMG, B, required=True,
    report_role=GALLERY)` — so attaching a source photograph REPLACES the one that is there and
    orphans it, on a required field whose value is printed in the report gallery. The prototype half
    of this tab already warns in the same way (`turntableCount` is threaded through to the panel);
    the sketch half said nothing at all, and the row picker shows only the row's name, so a designer
    had no way to know they were about to overwrite last week's photograph.

    Counted the same way the turntable count is, and stated beside the picker rather than passed to
    the panel: `UploadTabPanel` is another unit's component and has no prop for it. Beside the picker
    is arguably the better place anyway — it is the sentence that changes when the CHOICE changes.
  */
  // The INDEX as well as the row, because `rowLabel` falls back to "Untitled 3" for a row with no
  // name of its own and a hardcoded 0 there would call every unnamed row "Untitled 1".
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

  /*
    THE TAB IS ONLY WRITABLE OVER ROWS THE REPOSITORY'S COPY HAS BEEN FOLDED INTO — see the header.
    `reconciled` is false when the stage read failed, which on this fleet usually means no signal.
  */
  const sketchReady = Boolean(sketches?.reconciled && sketchRow && lineArt);
  const prototypeReady = Boolean(prototypes?.reconciled && prototypeRow && model);
  const anyStale = Boolean(sketches && prototypes && !(sketches.reconciled && prototypes.reconciled));

  /*
    THE PANEL IS DISABLED ONLY WHEN NEITHER HALF CAN TAKE A FILE, NOT WHEN EITHER CANNOT.

    `UploadTabPanel` has one `disabled` for both of its sections, and a workshop very often has
    sketches and no prototypes yet — the ordinary shape of week one. ANDing the two would grey out
    the tracing panel because nobody has made anything yet, which reads as the feature being broken.
    So the panel stays live and each attach handler refuses for its own half, in words. A refusal a
    designer can read beats a control that looks broken and beats one that silently does nothing.
  */
  const anythingReady = sketchReady || prototypeReady;

  /** Why one half cannot take a file, for the handler that has just been asked to. */
  function refuse(half: "sketch" | "prototype") {
    const rows = half === "sketch" ? sketches : prototypes;
    const chosen = half === "sketch" ? sketchRow : prototypeRow;
    setNotice(null);
    setProblem(
      rows && rows.rows.length === 0
        ? `This file has not been attached: there are no ${half}s in this workshop yet, so there is no record for it to belong to. Add one on its stage first.`
        : !chosen
          ? `This file has not been attached: choose which ${half} it belongs to first.`
          : `This file has not been attached: the repository's copy of the ${half} stage could not be read, and writing into a list this browser has not downloaded would replace what the repository holds.`
    );
  }

  return (
    <div className="grid gap-4">
      <div className="panel p-4">
        <h2 className="font-display text-lg font-bold text-ink-900">Where this goes</h2>
        <p className="mt-1 max-w-3xl text-sm leading-6 text-ink-muted">
          A drawing or a model belongs to one piece, so that everything about it — its images, its notes, its ratings
          and its place in the ranking — stays on one record. Choose the piece, then add the file below.
        </p>

        <div className="mt-3 grid gap-3 md:grid-cols-2">
          <RowPicker
            label="Sketch"
            rows={sketches?.rows ?? []}
            value={sketchRow}
            onChange={setSketchRow}
            emptyHref={
              sketches?.stageKey ? `/design-workshops/${workshopId}/stages/${sketches.stageKey}` : null
            }
            emptyWord="No sketches in this workshop yet"
          />
          <RowPicker
            label="Prototype"
            rows={prototypes?.rows ?? []}
            value={prototypeRow}
            onChange={setPrototypeRow}
            emptyHref={
              prototypes?.stageKey ? `/design-workshops/${workshopId}/stages/${prototypes.stageKey}` : null
            }
            emptyWord="No prototypes in this workshop yet"
          />
        </div>

        {chosenSketch && (sketchImageHeld > 0 || sketchLineArtHeld > 0) ? (
          <p className="mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
            {sketchImageHeld > 0 && sketchLineArtHeld > 0
              ? `“${rowLabel(chosenSketch, chosenSketchIndex)}” already holds a ${sketchImage?.label ?? "sketch image"} and a ${lineArt?.label ?? "line art file"}. Both are single files, so attaching another of either REPLACES the one that is there.`
              : sketchImageHeld > 0
                ? `“${rowLabel(chosenSketch, chosenSketchIndex)}” already holds a ${sketchImage?.label ?? "sketch image"}. It is a single file, so attaching a photograph here REPLACES it.`
                : `“${rowLabel(chosenSketch, chosenSketchIndex)}” already holds a ${lineArt?.label ?? "line art file"}. It is a single file, so attaching traced line art here REPLACES it.`}
          </p>
        ) : null}

        {anyStale ? (
          <p className="mt-3 flex items-start gap-2 rounded-md border border-line-200 bg-amber-100 px-3 py-2 text-sm text-amber-800">
            <CloudOff className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <span>
              The repository&apos;s copy of these stages could not be read, so nothing can be attached from here yet —
              writing into a list this browser has not downloaded would replace what the repository holds. Open the
              stage once with a connection, or try again when there is signal.
            </span>
          </p>
        ) : null}
        {notice ? <p className="mt-3 text-sm text-ink-muted">{notice}</p> : null}
        {problem ? (
          <p className="mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">
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
          disabled={busy || !anythingReady}
          onAttachSketch={(file) => {
            if (!sketches?.stageKey || !sketchReady) {
              refuse("sketch");
              return;
            }
            void attach(
              {
                stageKey: sketches.stageKey,
                entityKey: "sketch",
                fieldKey: SKETCH_LINE_ART,
                rowKey: sketchRow,
                multiple: false
              },
              file,
              lineArt.label
            );
          }}
          onAttachSketchSource={(file) => {
            if (!sketches?.stageKey || !sketchReady || !sketchImage) {
              refuse("sketch");
              return;
            }
            void attach(
              {
                stageKey: sketches.stageKey,
                entityKey: "sketch",
                fieldKey: SKETCH_IMAGE,
                rowKey: sketchRow,
                multiple: sketchImage.type === "IMAGE_LIST"
              },
              file,
              sketchImage.label
            );
          }}
          onAttachModel={(file) => {
            if (!prototypes?.stageKey || !prototypeReady) {
              refuse("prototype");
              return;
            }
            void attach(
              {
                stageKey: prototypes.stageKey,
                entityKey: "prototype",
                fieldKey: PROTOTYPE_MODEL,
                rowKey: prototypeRow,
                multiple: false
              },
              file,
              model.label
            );
          }}
        />
      ) : (
        <p className="panel px-4 py-6 text-sm text-ink-muted">
          {registry === null
            ? "This browser holds no field registry yet, so it cannot say which fields a sketch or a prototype has. Open the workshop once with a connection."
            : "This build's field registry does not declare the image, line-art and 3D-model fields this tab writes into, so nothing can be attached from here. That is a schema mismatch rather than a permission — open a stage form, which renders whatever the registry does declare."}
        </p>
      )}
    </div>
  );
}

/** Which row of a collection a file is going to. A real labelled `<select>`, not a themed dropdown. */
function RowPicker({
  label,
  rows,
  value,
  onChange,
  emptyHref,
  emptyWord
}: {
  label: string;
  rows: DwRow[];
  value: string;
  onChange: (value: string) => void;
  emptyHref: string | null;
  emptyWord: string;
}) {
  if (rows.length === 0) {
    return (
      <div className="rounded-md border border-dashed border-line-200 bg-surface-50 px-3 py-3 text-sm">
        <p className="text-ink-700">{emptyWord}</p>
        {emptyHref ? (
          <Link href={emptyHref} className="mt-2 inline-flex items-center gap-1 text-sm font-medium text-purple-700">
            Add one on its stage
            <ArrowRight className="h-4 w-4" aria-hidden />
          </Link>
        ) : null}
      </div>
    );
  }
  return (
    <label className="grid gap-1">
      <span className="field-label">{label}</span>
      {/*
        A NATIVE `<select>` RATHER THAN THE APP'S THEMED PICKER, and it is a considered choice: this
        is a short closed list with no search, the themed control's whole value is filtering long
        lists, and `Field`/`Select` would need a mirror input and a manual dirty call for nothing.
        A `<label>` may wrap a `<select>` — the trap in the forms guide is about wrapping a control
        that contains a BUTTON, which this does not.
      */}
      <select className="field-input" value={value} onChange={(event) => onChange(event.target.value)}>
        {rows.map((row, index) => (
          <option key={rowKeyOf(row) ?? index} value={rowKeyOf(row) ?? ""}>
            {rowLabel(row, index)}
          </option>
        ))}
      </select>
    </label>
  );
}
