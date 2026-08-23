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
 *     line of upload code here;
 *   * and then ASKS THE SYNC PASS TO CARRY IT UP, which for a long time it did not — see below.
 *
 * ── THE DEVICE WRITE IS NOT THE END OF THE HOP, AND THIS TAB USED TO STOP THERE ─────────────────
 *
 * `attach` wrote the blob and the reference into IndexedDB and returned, under a notice reading "It
 * uploads itself when this device next has a connection." Nothing on this page called
 * `syncDesignWorkshopDrafts`, and the two things that do are elsewhere: the draft banner in the
 * protected layout drains on mount and on the `online` event ONLY, and the REVIEW tab syncs its own
 * arrangement save. So a designer who traced a sketch on an already-online laptop and closed the tab
 * had a file that existed nowhere but this browser — no S3 object, no `MediaFile` row, no stage
 * entry — while the sentence they had just read said the opposite. The `online` event never fires
 * for a tab that was never offline, which is the ordinary office case rather than an exotic one.
 *
 * One call closes it, and it is the same call the REVIEW tab makes for the same reason: there is no
 * upload endpoint to reach for here, because the bytes and the row that points at them have to move
 * together, and `runSync` is the one pass that does both in the right order (media first, then the
 * stages that reference them). See `lib/designWorkshopStore.ts`.
 *
 * TWO PHASES, TWO SENTENCES, NEVER ONE. "It is on this device" and "the repository has it" are
 * different facts with different remedies, and the second is the only one that can fail after the
 * first has succeeded. The device write owns its own message and its own `catch`; the sending owns
 * the one after it. `ReviewPanel.persist` documents the bug that rule came from — a throw from the
 * sync pass printing "this could not be saved on this device" over a write that had already
 * landed.
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

import { Dropdown } from "@/components/ui/Dropdown";
import { isUnreachable } from "@/lib/failureTriage";
import { appendMediaRef } from "@/lib/photoIntake";
import {
  loadDraft,
  putDraftStage,
  stageLocalMedia,
  syncDesignWorkshopDrafts
} from "@/lib/designWorkshopStore";
import type { DwEntity, DwRegistry, DwRow } from "@/lib/designWorkshops";

import { UploadTabPanel } from "./upload/UploadTabPanel";
import { readStageRows, rowKeyOf, rowLabel, type StageRows } from "./stageRows";
import { syncPassLanded, syncPassNote } from "./syncNote";

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

  /**
   * Put files on this device, then send them — and report the two facts separately.
   *
   * TAKES A LIST rather than a file, because one of the four destinations is an IMAGE_LIST: a turn of
   * twelve photographs is one action a designer takes, and staging it as twelve actions would write
   * the stage twelve times, reload the pickers twelve times and print twelve notices for one choice.
   * The single-file callers pass a list of one and nothing about their path changes.
   *
   * ALL OF THE FILES GO INTO ONE `putDraftStage`, and that matters for more than tidiness. Each write
   * replaces the stage's entities wholesale, so a loop that wrote per file would be reading the
   * collection it had just written back through React state — and any row the designer changed in
   * another tab between two of those writes would be resurrected from the copy this loop is holding.
   */
  const attach = useCallback(
    async (target: Target, files: File[], what: string): Promise<boolean> => {
      if (files.length === 0) return false;
      setBusy(true);
      setProblem(null);
      setNotice(null);
      let landed: string | null = null;
      try {
        const draft = await loadDraft(workshopId);
        const stage = draft?.stages[target.stageKey];
        if (!draft || !stage) {
          setProblem(
            "This file has not been attached: this browser holds no copy of the stage it belongs to. Open that stage once with a connection, then try again."
          );
          return false;
        }
        const rows = [...(stage.collections[target.entityKey] ?? [])];
        const index = rows.findIndex((row) => rowKeyOf(row) === target.rowKey);
        if (index < 0) {
          // The row went away between this tab reading it and the file being handed over — another
          // tab, or a colleague's deletion arriving on a sync. Naming it is the only honest answer.
          setProblem(
            "This file has not been attached: the row it was headed for is no longer in this workshop. Reload the tab and choose another."
          );
          return false;
        }
        const row = { ...rows[index] };
        for (const file of files) {
          const { ref } = await stageLocalMedia(workshopId, file, {
            stageKey: target.stageKey,
            entityKey: target.entityKey,
            fieldKey: target.fieldKey,
            clientKey: target.rowKey
          });
          row[target.fieldKey] = appendMediaRef(row[target.fieldKey], ref, target.multiple);
        }
        rows[index] = row;
        await putDraftStage(workshopId, target.stageKey, {
          singletons: stage.singletons,
          collections: { ...stage.collections, [target.entityKey]: rows },
          removedFrom: stage.removedFrom
        });
        landed = rowLabel(row, index);
        await reload();
        setNotice(`${what} attached to “${landed}” on this device. Sending it to the repository…`);
      } catch {
        setProblem(
          "Nothing could be written to this device's storage. If the browser is in private mode or its storage is full, the file cannot be kept here — free some space and try again."
        );
        return false;
      } finally {
        /*
          THE PANEL IS RELEASED BEFORE THE UPLOAD, NOT AFTER IT, AND THAT IS DELIBERATE.

          `busy` disables both halves of the panel, and the send below is an S3 transfer that can be
          a 200 MB model on a shared hotspot. Holding the whole tab for the duration would look like
          the feature had hung, and would stop a designer staging the next photograph while the first
          is still going up — which is precisely what the local draft exists to allow. Two attaches
          overlapping is safe: `syncDesignWorkshopDrafts` shares one pass between concurrent callers
          and is held under a Web Lock across tabs, so the second call joins the first rather than
          starting a rival upload of the same bytes.
        */
        setBusy(false);
      }

      /* ── Phase two: the repository. Its own try, its own sentence. ────────────────────────── */
      try {
        const result = await syncDesignWorkshopDrafts();
        await reload();
        setNotice(
          `${what} attached to “${landed}”. ${syncPassNote(result, files.length === 1 ? "this file is" : "these files are")}` +
            // The bytes are the half a designer cannot re-make: a traced plate can be traced again,
            // a courtyard photograph cannot be re-taken. So on every outcome except a confirmed
            // landing, say that the copy here is kept — that is what makes the wait safe rather than
            // merely long.
            (syncPassLanded(result) ? "" : " The copy on this device is kept until the repository confirms it.")
        );
      } catch (error) {
        // The file IS on this device — that happened above and is not in doubt here. Only the sending
        // is, so only the sending is what this sentence is about.
        setNotice(
          isUnreachable(error)
            ? `${what} is saved on this device. There is no connection, so it uploads itself when one returns, and the copy here is kept until the repository confirms it.`
            : `${what} is saved on this device, but sending it did not complete. It goes up with the next sync — the banner above the page follows it — and the copy here is kept until the repository confirms it.`
        );
      }
      /*
        TRUE MEANS "IT IS ON THIS DEVICE", NOT "THE REPOSITORY HAS IT" — the two-phase rule this
        file's header states, in the return value. Phase two failing is not a failed attach: the
        bytes are durable, the sync pass owns its own sentence above, and the banner follows it.

        WHAT THE CALLER NEEDS IT FOR. `PrototypeModelField` printed a green "N photographs were added
        to …" unconditionally, the moment it handed the files over — so a synchronous `refuse()` or a
        failed IndexedDB write rendered its red sentence and the green tick side by side, one of them
        a lie. A void callback gave the panel no way to know; this is that way.
      */
      return true;
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
          /*
            THE HANDLERS ANSWER THE PANEL, and the answer is what stops a green tick appearing over a
            red refusal. `attach` resolves true once the file is on this device (phase two owns its
            own sentence); a synchronous `refuse` resolves false, because `refuse` has already said
            why in words the panel could not improve on. See `upload/UploadTabPanel.AttachAnswer`.
          */
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
                multiple: false
              },
              [file],
              lineArt.label
            );
          }}
          onAttachSketchSource={async (file) => {
            if (!sketches?.stageKey || !sketchReady || !sketchImage) {
              refuse("sketch");
              return false;
            }
            return attach(
              {
                stageKey: sketches.stageKey,
                entityKey: "sketch",
                fieldKey: SKETCH_IMAGE,
                rowKey: sketchRow,
                multiple: sketchImage.type === "IMAGE_LIST"
              },
              [file],
              sketchImage.label
            );
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
                multiple: false
              },
              [file],
              model.label
            );
          }}
          /*
            THE FIELD THE PANEL SPENDS MOST OF ITS SCREEN ADVISING, WHICH IT COULD NOT WRITE.

            `prototype.turntablePhotos` is the ONE field on this whole surface that reaches the
            printed report as pictures — `report_builder._images` filters on IMAGE and IMAGE_LIST,
            and everything else here is a FILE that prints as "1 document attached". The panel read it
            off the registry for its label, counted what the row already held, and told the designer
            in two paragraphs why it mattered — and then offered no way to add one, sending them to
            the stage form without saying so. Advising a field it cannot write is worse than being
            silent about it: the designer follows the advice on the screen they are on.

            `multiple` IS READ OFF THE REGISTRY rather than hardcoded true, exactly as the sketch
            image's is. If this field is ever narrowed to a single IMAGE, `appendMediaRef` must
            REPLACE rather than append, or every frame after the first would be dropped by
            `coerce_value` and the designer told nothing.
          */
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
                multiple: turntable.type === "IMAGE_LIST"
              },
              files,
              files.length === 1 ? `1 frame of “${turntable.label}”` : `${files.length} frames of “${turntable.label}”`
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

/** Which row of a collection a file is going to — the app's themed picker, searched by provenance. */
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
    /*
      ── THIS WAS A NATIVE `<select>` AND THE REASON GIVEN FOR IT WAS WRONG ─────────────────────────

      The old comment called this "a short closed list with no search". It is neither. `rows` is a
      design workshop collection's rows — sketches on stage 11, prototypes on stage 12 — read off a
      record and unbounded: a workshop that documented forty sketches has forty rows here, and the
      count is a property of the fieldwork, not of this file. That is exactly the category every
      other picker in the app was switched for, and the rule on `SearchableSelectProps.searchable`
      is about where the options CAME FROM precisely because a count measured on today's data cannot
      answer for tomorrow's. So this one was reasoned about wrongly rather than deliberately skipped,
      and it is now the same control as its neighbours, searching by provenance.

      A `<div>` AND NOT A `<label>`, which the old markup could legitimately use. A `<label>` may
      wrap a `<select>`; it cannot name a `<button>`, and the themed picker's trigger is one. The
      name is carried by `ariaLabel` instead — see `ui/fieldLabel.tsx` on why the label id route is
      for the `Field`/`FieldBlock` wrappers and not for a hand-rolled slot like this.
    */
    <div className="grid min-w-0 gap-1">
      <span className="field-label">{label}</span>
      <Dropdown
        value={value}
        onChange={onChange}
        options={rows.map((row, index) => ({
          value: rowKeyOf(row) ?? "",
          label: rowLabel(row, index)
        }))}
        ariaLabel={label}
        searchable
        /* This control picks WHICH ROW the panel beside it is about, so it changes the screen it
           sits on. Advancing focus away from it on select is the trap §17 of the frontend guide
           names: the reader is adjusting this control and would be thrown off it mid-adjustment. */
        advanceOnSelect={false}
      />
    </div>
  );
}
