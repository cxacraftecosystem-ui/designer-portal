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
 *
 * ── AND SINCE 2026-08-28, THE FILE IS NOT THE ONLY THING THAT LANDS ON A ROW FROM HERE ──────────
 *
 * "Measure a dimension from a photograph" is now mounted on this tab, in each half, against the row
 * the pickers above have chosen. The card is `upload/MeasureFromPhotoCard.tsx` and the geometry
 * inside it is `components/designworkshop/PhotoMeasureField.tsx` — the same component the stage form
 * mounts, not a copy — and this file is the half that makes it mountable here at all:
 *
 *   * `useMeasurablePhotos` turns the media references already on the chosen row into DISPLAYABLE
 *     URLs. That is the one thing `upload/` is built not to know how to do, which is why the card
 *     arrives at `UploadTabPanel` as a rendered node rather than as four props;
 *   * `proposeDimension` writes an accepted figure into the row through the same
 *     `putDraftStage` → `syncDesignWorkshopDrafts` pair every attach on this tab uses, with the same
 *     two phases and the same two sentences.
 *
 * IT WRITES NOTHING BY ITSELF. `proposeDimension` is only ever reached from a button inside the
 * measuring panel that the designer pressed, which is the rule both clients state in their own
 * source ("IT NEVER WRITES A DIMENSION BY ITSELF", `DwPhotoMeasureField.kt:115`). Nothing in this
 * file may call it from an effect.
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { ArrowRight, CloudOff } from "lucide-react";

import type { MeasurablePhoto, MeasureTarget } from "@/components/designworkshop/PhotoMeasureField";
import { measurableLengthFields } from "@/components/designworkshop/stageFieldRoles";
import { Dropdown } from "@/components/ui/Dropdown";
import { apiFetch } from "@/lib/api";
import { isUnreachable } from "@/lib/failureTriage";
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
import type { MediaFile } from "@/lib/types";

import { UploadTabPanel } from "./upload/UploadTabPanel";
import { MeasureFromPhotoCard, type MeasurePhotos } from "./upload/MeasureFromPhotoCard";
import { readStageRows, rowKeyOf, rowLabel, type StageRows } from "./stageRows";
import { syncPassLanded, syncPassNote } from "./syncNote";

/**
 * What joins a row's media references into the one string `useMeasurablePhotos` keys its effect on.
 *
 * A NUL, and BUILT rather than written as a literal: a control character pasted into a source file
 * makes it "binary" to git, to grep and to every review tool, and it is invisible to the next
 * reader — this file was briefly in that state while the hook below was being fixed. It cannot
 * appear in either kind of reference this joins (a server cuid or a `dwlocal:` id), so the split is
 * lossless; see that hook for what happens in the impossible case.
 */
const REFERENCE_KEY_SEPARATOR = String.fromCharCode(0);

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

/**
 * The values handed to the measuring card while no row is chosen.
 *
 * A MODULE CONSTANT rather than a fresh `{}` per render, so the card's props do not change identity
 * on every keystroke elsewhere on the tab. The card does not read it in that state — it renders the
 * "choose which one this is about" sentence instead — but a prop that is required by type and absent
 * in one state is the kind of `undefined` that turns into a crash the first time somebody reorders a
 * branch.
 */
const NO_ROW: DwEntryData = {};

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
  /**
   * The ceiling the registry DECLARED for the field, or undefined where it declared none.
   *
   * Undefined is not "no ceiling": `appendMediaRef` reads it as the server's own default, because
   * `coerce_value` REFUSES an over-long array rather than trimming it and `save_stage` restores the
   * refused key from the previous entry — so a turn of two hundred turntable frames appended past the
   * ceiling would not lose the tail, it would lose the whole field on the next sync with every byte
   * already staged. Carried verbatim rather than resolved here so the refusal sentence cannot print a
   * figure this client did not read (docs/DESIGN_WORKSHOP.md:229-232).
   */
  maxItems?: number;
};

/**
 * "These did not fit", in words, for a destination that is already at its ceiling.
 *
 * IT NAMES THE FILES AND NOT THE CEILING, which is both halves of docs/DESIGN_WORKSHOP.md:229-232 in
 * one sentence. "Only twenty are allowed" tells a designer holding a turn of twelve nothing about
 * which ones to re-pick, and the number itself may not be printed here at all: `turntablePhotos`
 * declares no `maxItems`, so what is enforced against it is the server's own default — a figure this
 * client read from nowhere and may not state as though the registry had said it. "That field is
 * already full" is true of a declared ceiling and an undeclared one alike, and the named files are
 * the part a designer can act on.
 *
 * THE PHRASE FOR THE FILES COMES FROM THE CALLER'S OWN `what`, so the refusal and the receipt beside
 * it are worded by the same hand and count in the same units — "3 frames of “Turntable photographs”
 * were not attached", under "9 frames of “Turntable photographs” attached to …".
 */
function fullNotice(said: string, names: string[]): string {
  const one = names.length === 1;
  return (
    `${said} ${one ? "was" : "were"} not attached because that field is already full: ${names.join(", ")}. ` +
    "Remove something it already holds — the stage form is where an attachment can be taken off — then " +
    `attach ${one ? "it" : "them"} again.`
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * The photographs already on a row, as things a measuring panel can display
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Every image field on an entity, in the order the form renders them.
 *
 * EVERY ONE, not a guessed "likely" one, for the reason `offersPhotoMeasure` gives in
 * `components/designworkshop/stageFieldRoles.ts`: nothing in this application can tell which
 * photograph has the ruler in it — that is a fact about what the designer laid beside the object
 * thirty seconds ago — so narrowing the list would hide the feature on precisely the photograph it
 * was taken for. On a sketch that is `image`; on a prototype it is `prototypePhotos` and
 * `turntablePhotos` both.
 */
function imageFieldsOf(entity: DwEntity | undefined): DwField[] {
  return (entity?.fields ?? []).filter(
    (field) => !field.deprecated && (field.type === "IMAGE" || field.type === "IMAGE_LIST")
  );
}

/** The media references a stored value holds — one for an IMAGE, several for an IMAGE_LIST. */
function refsOf(value: DwValue | undefined): string[] {
  if (Array.isArray(value)) {
    return value.filter((entry): entry is string => typeof entry === "string" && entry.trim() !== "");
  }
  return typeof value === "string" && value.trim() ? [value] : [];
}

/**
 * Resolve a row's media references into photographs a measuring panel can draw.
 *
 * ── WHY THIS IS CHEAPER THAN IT LOOKS, AND WHY IT IS NOT `SketchRectifyField`'s LOADER ──────────
 *
 * `PhotoMeasureField` reads no pixels — its own header says so under "NO NETWORK, NO CANVAS
 * READBACK, NO RE-ENCODING", and it is why the panel works on a cross-origin presigned URL with no
 * bucket CORS rule at all. So this needs a URL and nothing else. `SketchRectifyField.loadSourceBlob`
 * needs the BYTES (it decodes a plane), which forces it to `fetch()` a presigned URL and to report a
 * CORS failure as an ordinary outcome. Copying that here would have added a network round trip, a
 * CORS dependency and a failure mode to a feature that has none of them.
 *
 * ── THE TWO KINDS OF REFERENCE ARE NOT EQUALLY RELIABLE, AND THE UNRELIABLE ONE IS THE UPLOADED ONE
 *
 * A `dwlocal:` reference is bytes in this device's own draft store, so it is always readable — and
 * that is the case that matters in the field, because a photograph taken minutes ago in a courtyard
 * with no signal IS a `dwlocal:` reference. An uploaded media id costs a `GET /media/{id}` whose
 * `url` may be absent by entitlement (§17: "Media `url`/`objectKey` may be absent by entitlement")
 * and which simply cannot be answered with no connection. Neither of those is a broken feature and
 * neither may be silent: a reference that resolves to nothing is COUNTED, and the count is printed
 * beside the chooser by the card.
 *
 * ── AND WHY A ROW THAT HOLDS REFERENCES BUT RESOLVED NONE IS A FAILURE, NOT AN EMPTY ROW ────────
 *
 * "There is no photograph on this row" and "this row's photographs could not be read" have different
 * remedies — attach one, versus find a connection — and rendering the first over the second is the
 * house rule this repository states as "a failure and an empty answer are different states". So the
 * only path to `photos: []` is a row that genuinely holds no reference.
 *
 * THE OBJECT URLS ARE CREATED AND REVOKED IN ONE EFFECT, which is what keeps the pair together:
 * revoked anywhere else they either leak for the life of the tab or are released while an `<img>` is
 * still reading them, and the second looks like a photograph that vanished. Both the cancel branch
 * and the cleanup revoke, because they can run in either order — a teardown fires before an
 * in-flight `await` resolves, so a URL created after cleanup would otherwise never be released.
 * Revoking an already-revoked URL is a no-op.
 */
function useMeasurablePhotos(refs: string[]): MeasurePhotos {
  /*
    THE EFFECT IS KEYED ON WHICH REFERENCES THESE ARE, AND THE ARRAY IS DERIVED BACK FROM THAT KEY.

    `refs` is a fresh array on every render and `reload()` replaces every row object on every attach
    and every sync pass, so either one in the dependency list would re-resolve every photograph — and
    re-mint every object URL — several times per file added, while the panel is open and drawing from
    the URLs being replaced. The joined string changes only when the row's photographs actually
    change.

    ── WHY IT IS DERIVED AND NOT CARRIED IN A REF ────────────────────────────────────────────────

    The obvious shape here is `const latest = useRef(refs); latest.current = refs;` and it is wrong
    for a reason React's own lint states in one line: **a ref may not be written during render.** A
    render can be discarded under concurrent rendering, and a ref written on a discarded render
    leaves the effect reading state that never committed — the same hazard `useLeaveGuard` documents
    for its interceptor, which is why that one registers inside an effect. The ref version also fails
    silently rather than loudly, which is worse.

    Splitting the key back is genuinely derived: the dependency list is honest, nothing is
    suppressed, and there is no cross-render mutable state at all. The separator is a NUL, which
    cannot appear in either kind of media reference this takes — a server cuid or a `dwlocal:` id —
    and if one ever did, the failure mode is a split reference that resolves to nothing and is
    COUNTED as unreadable, which is the safe direction: the count is printed beside the chooser, so
    the designer is told rather than shown a silently shorter list.
  */
  const key = refs.join(REFERENCE_KEY_SEPARATOR);
  const wanted = useMemo(() => (key === "" ? [] : key.split(REFERENCE_KEY_SEPARATOR)), [key]);
  // Lazily initialised so the FIRST render is already the right state: a row with references starts
  // as "reading", a row with none starts as an answered empty. Starting at one of them unconditionally
  // paints the other's sentence for a frame, and the two sentences say opposite things.
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

      for (const ref of wanted) {
        if (isLocalMediaRef(ref)) {
          // `readLocalMedia` swallows its own storage failures and answers null, so there is nothing
          // to catch here. A confirmed upload clears `blob` (see `confirmLocalMedia`), which is the
          // ordinary reason a local reference has no bytes left rather than a fault.
          const media = await readLocalMedia(ref);
          if (media?.blob && media.mimeType.startsWith("image/")) {
            const url = URL.createObjectURL(media.blob);
            created.push(url);
            photos.push({ key: ref, name: media.name, url });
          } else {
            unreadable += 1;
          }
          continue;
        }
        try {
          const media = await apiFetch<MediaFile>(`/media/${ref}`);
          if (media.mediaType === "IMAGE" && media.url) {
            photos.push({ key: ref, name: media.originalFilename, url: media.url });
          } else {
            unreadable += 1;
          }
        } catch (error) {
          // ONE REFERENCE AT A TIME. A single unreachable media row must not throw away the
          // photographs already resolved beside it — half of them may be on this device and
          // measurable with no connection at all.
          unreadable += 1;
          if (isUnreachable(error)) offline = true;
        }
      }

      if (cancelled) {
        for (const url of created) URL.revokeObjectURL(url);
        return;
      }

      if (photos.length === 0) {
        setState({
          status: "failed",
          reason: offline
            ? "There is no connection, so photographs already uploaded from this row cannot be fetched back here. A photograph still held on this device can be measured with no connection at all."
            : "None of the files this row points at could be opened in this browser — that is usually a file this account is not entitled to fetch back."
        });
        return;
      }
      setState({ status: "ready", photos, unreadable });
    })();

    return () => {
      cancelled = true;
      for (const url of created) URL.revokeObjectURL(url);
    };
    // `wanted` and NOT `key`, and the two are the same trigger: `wanted` is `useMemo`'d on `key`
    // alone, so its identity changes exactly when the joined string does — which is the whole
    // reason it is derived rather than taken from the caller's fresh-every-render array. Naming the
    // value the effect actually reads is what keeps this list honest instead of suppressed.
  }, [wanted]);

  return state;
}

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
   *
   * ── `what` IS A SENTENCE-MAKER RATHER THAN A SENTENCE, AND THAT IS THE POINT ────────────────────
   *
   * It used to be a fixed string, built by the caller from `files.length` before anything had been
   * written — "12 frames of “Turntable photographs”". The moment this function gained a ceiling to
   * enforce (see `Target.maxItems`) that string became a receipt for work that may not have happened:
   * ten land, two are turned away, and the green line still says twelve. A receipt that overstates is
   * worse than the refusal it papers over, because the designer stops counting. So the caller hands
   * over a phrase-maker and THIS function, which is the only place that knows how many files the
   * field actually took, decides the number that goes into it.
   */
  const attach = useCallback(
    async (target: Target, files: File[], what: (landedCount: number) => string): Promise<boolean> => {
      if (files.length === 0) return false;
      setBusy(true);
      setProblem(null);
      setNotice(null);
      let landed: string | null = null;
      /** How many of `files` the field actually took — the only number any sentence below may use. */
      let took = 0;
      /**
       * The files the field had no room for. Named on screen, never merely dropped.
       *
       * DECLARED OUT HERE, ABOVE THE TRY, because it is read twice after the write: once for the red
       * sentence and once by the value this function hands back to the panel (see the note on the
       * return, and `upload/UploadTabPanel.AttachAnswer`).
       */
      const turnedAway: string[] = [];
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
        /*
          ROOM IS ASKED FOR BEFORE THE BYTES ARE STAGED, not after: `stageLocalMedia` writes the blob
          into IndexedDB and the sync pass uploads every staged row it finds, so a frame the field then
          refused would go to the repository referenced by nothing and sit on this laptop for the rest
          of the fortnight. `MediaField` in FieldInput.tsx trims on the same principle and in the same
          words — "before a byte is uploaded".

          ASKED PER FILE INSIDE THE LOOP rather than once for the batch, because `row[fieldKey]` is
          what the previous iteration just grew: a turn of twelve handed to a gallery already holding
          eighteen of twenty must take two and turn away ten, and one check up front could only take
          all twelve or none.
        */
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
        /**
         * Nothing fitted, so nothing is written and the answer to the panel is a plain no.
         *
         * Returning true here would put a green tick over a red sentence — the exact pairing the
         * return value of this function exists to prevent (see its note below) — and writing the stage
         * back unchanged would re-put a copy this tab read seconds ago over whatever another tab has
         * done to the row since.
         */
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
        setNotice(`${what(took)} attached to “${landed}” on this device. Sending it to the repository…`);
        // Both sentences stand together on a partial turn: the green one counts what landed, the red
        // one names what did not. Either alone would be a lie by omission about the other half.
        if (turnedAway.length) setProblem(fullNotice(what(turnedAway.length), turnedAway));
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
          // `took`, not `files.length`, in the phrase AND in the singular/plural of the sync note: a
          // turn of twelve that the gallery took two of is two files going up, and "these files are"
          // over one file is the same overstatement one clause later.
          `${what(took)} attached to “${landed}”. ${syncPassNote(result, took === 1 ? "this file is" : "these files are")}` +
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
            ? `${what(took)} is saved on this device. There is no connection, so it uploads itself when one returns, and the copy here is kept until the repository confirms it.`
            : `${what(took)} is saved on this device, but sending it did not complete. It goes up with the next sync — the banner above the page follows it — and the copy here is kept until the repository confirms it.`
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

        ── AND A PARTIAL TURN ANSWERS `false`, WHICH IS NOT THE OBVIOUS READING ─────────────────────

        Some frames landed, so "is it on this device" is arguably yes. But the ONLY thing the answer
        is used for is whether the panel may print its claim, and that claim counts the files it handed
        over: `PrototypeModelField` says "12 photographs were added" off `frames.length`, which is
        exactly the receipt-that-overstates this ceiling work exists to stop. `AttachAnswer`
        defines `false` as "I have told them; do not claim this worked" — and by then this host has
        told them twice, in the counted notice above and in the named refusal beside it. So a partial
        is a `false` under that contract rather than in spite of it.

        WHAT IT COSTS, stated because the next reader will hit it: the panel leaves the designer's
        whole selection in the picker on a `false`, so a second press after making room re-stages the
        frames that already landed as fresh references — `appendMediaRef` de-duplicates by reference
        and these are new ones. The alternative is a green line claiming twelve over ten, which is the
        worse of the two and the one this repository has already paid for. The real fix is a richer
        answer than a boolean, in `UploadTabPanel` and its panels, which is not this change.
      */
      return turnedAway.length === 0;
    },
    [reload, workshopId]
  );

  /**
   * Add an EMPTY row to one of the two collections, and select it.
   *
   * ── WHY THIS IS HERE AT ALL, AND WHY IT IS NOT A SECOND STORE ──────────────────────────────────
   *
   * The owner, 2026-08-28: *"Provide an option to add a Sketch or Prototype directly to the selected
   * workshop from this screen."* Until now this tab could only pick a row somebody had already made
   * on the stage form, so a designer with a drawing in their hand and an empty stage 11 was sent
   * away to make the row and come back — on the one screen the feature exists to save them that.
   *
   * IT WRITES THROUGH THE SAME DOOR EVERY OTHER WRITE ON THIS TAB USES: `ensureDraft` →
   * `putDraftStage` → `syncDesignWorkshopDrafts`, which is the stage form's own path. There is no
   * parallel collection and no new endpoint, which is the whole of what
   * `SketchesAndPrototypesScreen.kt`'s "one feature with two stores" rule forbids. The handset took
   * the same shape on the same day (`DwSketchChooserRows.kt#dwChooserNewRow`).
   *
   * ── THE ROW IS EMPTY, AND THAT IS DELIBERATE ──────────────────────────────────────────────────
   *
   * It carries a `_clientKey` and nothing else. Naming it here would mean inventing a value for a
   * field the registry may declare required, validated and title-cased, from a screen that renders
   * none of that — and `sketch.name` is printed in the report. The row is created so a photograph
   * has somewhere to land; the stage form is where it is named, and the empty-state sentence below
   * the picker already points there.
   *
   * ── REFUSED ON AN UNRECONCILED COLLECTION, exactly as `attach` is ──────────────────────────────
   *
   * `putDraftStage` writes the stage's entities WHOLESALE. Appending to a collection this browser
   * has not read would replace the repository's rows with the handful this browser holds, which is
   * the same hazard the attach path refuses, for the same reason, in the same words.
   */
  const addRow = useCallback(
    async (entityKey: "sketch" | "prototype") => {
      const half = entityKey === "sketch" ? sketches : prototypes;
      if (!half?.stageKey) return;
      if (!half.reconciled) {
        setProblem(
          "This device has not read this workshop's " +
            (entityKey === "sketch" ? "sketches" : "prototypes") +
            " yet, so a new one cannot be added here without risking the ones the repository holds. " +
            "Open the workshop once with a connection, then come back."
        );
        return;
      }
      setBusy(true);
      setProblem(null);
      setNotice(null);
      try {
        const draft = await ensureDraft(workshopId);
        // The WHOLE stage, exactly as the attach path writes it: `putDraftStage` replaces a stage's
        // entities, so a body carrying only this collection would drop the stage's singletons and
        // its `removedFrom` list. Read them off the draft and put them straight back.
        const stage = draft?.stages[half.stageKey];
        const rows = [...(stage?.collections[entityKey] ?? [])];
        // A client key, minted here, is what `putDraftStage` addresses the row by and what the sync
        // pass turns into a server entry. `crypto.randomUUID` is available in every browser this app
        // supports and is what `designWorkshopStore` mints its own keys with.
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
          `A new ${entityKey} was added to this workshop on this device. Attach its files here; name it, ` +
            "and fill in the rest, on its stage."
        );
        // THE SECOND PHASE, AND ITS OWN SENTENCE. "It is on this device" and "the repository has it"
        // are different facts with different remedies — the rule this file's header states — so the
        // send is reported separately and a failure here never claims the local write failed.
        try {
          const result = await syncDesignWorkshopDrafts();
          setNotice(
            (current) => `${current ?? ""} ${syncPassNote(result, `the new ${entityKey} is`)}`.trim()
          );
        } catch {
          setNotice((current) => `${current ?? ""} It will be sent when this device next has a connection.`.trim());
        }
      } catch {
        setProblem(`A new ${entityKey} could not be created on this device.`);
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
  // The INDEX as well as the row, for the same reason the sketch half keeps one below: `rowLabel`
  // falls back to "Untitled 3" for a row with no name of its own, and a hardcoded 0 would call every
  // unnamed prototype "Untitled 1" in the measuring card's sentences.
  const chosenPrototypeIndex = (prototypes?.rows ?? []).findIndex((row) => rowKeyOf(row) === prototypeRow);
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

  /* ── The measuring card's inputs, per half ──────────────────────────────────────────────────
     All four are read off the REGISTRY rather than named here, exactly as the four writable field
     labels above are: `measurableLengthFields` decides what a dimension is by asking whether the
     field is numeric and carries a length unit, so a registry that renames `lengthCm` or adds a
     `depthCm` needs no edit in this file. Only `stageFieldRoles` knows the rule, and it is the same
     module the stage form asks.
  */
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

  /**
   * Write ONE measured dimension into the chosen row.
   *
   * REACHED ONLY FROM A BUTTON THE DESIGNER PRESSED, inside `PhotoMeasureField`, which is the rule
   * both clients state in their own source and which this function must not be the place that
   * breaks: nothing here may be called from an effect, a timer or a load.
   *
   * ── IT IS THE ATTACH PATH WITH THE BYTES TAKEN OUT ─────────────────────────────────────────
   *
   * Same `loadDraft` → find the row → `putDraftStage` → `reload` → `syncDesignWorkshopDrafts`, same
   * two phases and same two sentences, because the failure modes are identical: the stage may not be
   * on this device, the row may have gone since the picker read it, IndexedDB may refuse, and the
   * sending is the only half that can fail after the writing has succeeded. It does NOT share
   * `attach`'s body because `attach` is about files — it stages blobs, counts what a field had room
   * for, and answers a boolean the panels use to decide whether to print a receipt. A dimension is
   * one scalar into one key with no ceiling and no receipt to suppress, and folding it in would have
   * meant a `files: []` call and three "not for this caller" branches.
   *
   * ── WHAT IS DELIBERATELY DROPPED, AND WHY IT IS NOT A BUG HERE ─────────────────────────────
   *
   * `PhotoMeasureField.onPropose`'s third argument is a `MeasurementMarker` saying WHICH GEOMETRY
   * produced the figure, and this tab drops it exactly as the stage form does, because a stage save
   * still has nowhere to put one. Both halves were re-checked on 2026-08-28:
   *
   *     grep -n "class StageEntryIn" -A 14 backend/app/schemas/design_workshops.py
   *     grep -n "onPropose" frontend/components/designworkshop/FieldInput.tsx
   *
   * `StageEntryIn` declares `entityKey` / `entryId` / `ordinal` / `data` / `merge` and no marker, and
   * `FieldInput.tsx:1685` still writes `onPropose={(key, proposed) => onPatch({ [key]: proposed })}`.
   * The record forms are the half that already carries it (`measurementMethods`, out of
   * `components/media/RecordPhotoMeasure.tsx`), and when the stage save grows the same sibling this
   * call site gains a third parameter and nothing inside the panel changes.
   */
  const proposeDimension = useCallback(
    async (
      target: { stageKey: string; entityKey: string; rowKey: string },
      field: DwField,
      unit: string,
      value: DwValue
    ): Promise<void> => {
      // The unit is printed with the figure in every sentence below. The panel proposes a bare
      // number because that is what the field stores, and "now reads 20" over a length is a sentence
      // a designer cannot check against the object in their hands.
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
            `That measurement has not been written: this browser holds no copy of the stage “${field.label}” belongs to. Open that stage once with a connection, then try again.`
          );
          return;
        }
        const rows = [...(stage.collections[target.entityKey] ?? [])];
        const index = rows.findIndex((row) => rowKeyOf(row) === target.rowKey);
        if (index < 0) {
          // The row went away between the picker reading it and the button being pressed — another
          // tab, or a colleague's deletion arriving on a sync. Naming it is the only honest answer.
          setProblem(
            "That measurement has not been written: the row it was headed for is no longer in this workshop. Reload the tab and choose another."
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
        setNotice(`“${field.label}” on “${landed}” now reads ${shown} on this device. Sending it to the repository…`);
      } catch {
        setProblem(
          "That measurement could not be written to this device's storage. If the browser is in private mode or its storage is full, nothing can be kept here — free some space and try again."
        );
        return;
      } finally {
        setBusy(false);
      }

      /* ── Phase two: the repository. Its own try, its own sentence. ────────────────────────── */
      try {
        const result = await syncDesignWorkshopDrafts();
        await reload();
        setNotice(
          `“${field.label}” on “${landed}” now reads ${shown}. ${syncPassNote(result, "this measurement is")}` +
            (syncPassLanded(result) ? "" : " The copy on this device is kept until the repository confirms it.")
        );
      } catch (error) {
        // The figure IS on this device — that happened above and is not in doubt here. Only the
        // sending is, so only the sending is what this sentence is about.
        setNotice(
          isUnreachable(error)
            ? `“${field.label}” on “${landed}” now reads ${shown} on this device. There is no connection, so it goes up when one returns, and the copy here is kept until the repository confirms it.`
            : `“${field.label}” on “${landed}” now reads ${shown} on this device, but sending it did not complete. It goes up with the next sync — the banner above the page follows it.`
        );
      }
    },
    [reload, workshopId]
  );

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

  /**
   * Why one half cannot be written to, for the handler that has just been asked to write.
   *
   * `said` IS A PARAMETER BECAUSE NOT EVERY WRITE ON THIS TAB IS A FILE ANY MORE. The three reasons
   * below are properties of the HALF — no rows, no row chosen, the stage unread — and are identical
   * whether the designer was attaching a photograph or accepting a measured dimension. The lead
   * clause is not: "This file has not been attached" over a figure somebody just measured names the
   * wrong thing and sends them looking for a file they never chose. So the reason is shared and the
   * noun is the caller's, which is the same division `attach`'s `what` phrase-maker uses.
   */
  function refuse(half: "sketch" | "prototype", said = "This file has not been attached") {
    const rows = half === "sketch" ? sketches : prototypes;
    const chosen = half === "sketch" ? sketchRow : prototypeRow;
    setNotice(null);
    setProblem(
      rows && rows.rows.length === 0
        ? `${said}: there are no ${half}s in this workshop yet, so there is no record for it to belong to. Add one on its stage first.`
        : !chosen
          ? `${said}: choose which ${half} it belongs to first.`
          : `${said}: the repository's copy of the ${half} stage could not be read, and writing into a list this browser has not downloaded would replace what the repository holds.`
    );
  }

  /** The lead clause `refuse` uses when what could not be written is a measured dimension. */
  const MEASURE_REFUSED = "That measurement has not been written";

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
            addWord="Add a sketch"
            onAdd={sketches?.reconciled ? () => void addRow("sketch") : undefined}
            busy={busy}
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
            addWord="Add a prototype"
            onAdd={prototypes?.reconciled ? () => void addRow("prototype") : undefined}
            busy={busy}
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
            red refusal. `attach` resolves true once EVERY file it was handed is on this device (phase
            two owns its own sentence); a synchronous `refuse` resolves false, because `refuse` has
            already said why in words the panel could not improve on. A turn the field only had room
            for part of also resolves false — the panel's claim counts what it handed over, so the
            only honest answers are "all of it" and "read what the host just told you". See
            `upload/UploadTabPanel.AttachAnswer` and the note on `attach`'s return.
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
            return attach(
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

            `maxItems` TRAVELS WITH IT for the other half of the same argument, and this is the one
            handler on the panel that can hand over more than one file at a time — a turntable is shot
            as a turn of twelve to thirty-six frames. Undeclared here today, so what is enforced is the
            server's default; the phrase-maker below counts in FRAMES and `attach` fills in how many of
            them the field actually took, which is why it is a function and not the sentence it used to
            be.
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
                multiple: turntable.type === "IMAGE_LIST",
                maxItems: turntable.maxItems
              },
              files,
              (count) => (count === 1 ? `1 frame of “${turntable.label}”` : `${count} frames of “${turntable.label}”`)
            );
          }}
          /*
            ── "MEASURE A DIMENSION FROM A PHOTOGRAPH", ON THE SURFACE IT WAS MISSING FROM ────────

            One card per half, built HERE rather than inside `upload/` because it needs a displayable
            URL for every photograph already on the chosen row, and resolving a media id or a
            `dwlocal:` reference is exactly the knowledge that directory is built not to have. See
            `useMeasurablePhotos` above and the slot's own note in `upload/UploadTabPanel.tsx`.

            THE CARD IS RENDERED EVEN WHEN IT CANNOT MEASURE, and that is the whole point of the
            change: it says why — no row chosen, no photograph on the row, the photographs unreadable
            — instead of disappearing. A control that vanishes is indistinguishable from a build that
            does not have the feature, which is precisely how this surface came to be reported as
            "completely missing".
          */
          sketchMeasure={
            <MeasureFromPhotoCard
              what="sketch"
              rowName={chosenSketch ? rowLabel(chosenSketch, chosenSketchIndex) : null}
              photos={sketchPhotos}
              targets={sketchTargets}
              row={chosenSketch ?? NO_ROW}
              photoFieldLabels={sketchPhotoFields.map((entry) => entry.label)}
              disabled={busy || !sketchReady}
              // Two parameters against a three-parameter type, deliberately — see the note on
              // `proposeDimension` for the marker this stage save has nowhere to put yet.
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
              disabled={busy || !prototypeReady}
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
        /*
          THE MEASURING CARDS ARE INSIDE THE PANEL, SO THEY GO WHEN IT GOES — AND THIS SAYS SO.

          They are slots of the two sections (see `sketchMeasure` above), which is where they belong:
          a measurement is taken against the row the section's picker chose. When the registry does
          not declare the four fields the panel writes, there are no sections and therefore nowhere
          to put them, so this sentence names measuring as well as attaching. Losing a control is
          allowed; losing it without a word is the bug this whole change was raised about.
        */
        <p className="panel px-4 py-6 text-sm text-ink-muted">
          {registry === null
            ? "This browser holds no field registry yet, so it cannot say which fields a sketch or a prototype has. Nothing can be attached or measured from here until it does — open the workshop once with a connection."
            : "This build's field registry does not declare the image, line-art and 3D-model fields this tab writes into, so nothing can be attached from here, and the measuring card that sits in each of those sections is not rendered either. That is a schema mismatch rather than a permission — open a stage form, which renders whatever the registry does declare, its own measuring panel included."}
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
  emptyWord,
  addWord,
  onAdd,
  busy
}: {
  label: string;
  rows: DwRow[];
  value: string;
  onChange: (value: string) => void;
  emptyHref: string | null;
  emptyWord: string;
  /** The button's own words — "Add a sketch" / "Add a prototype", never a generic "Add". */
  addWord: string;
  /**
   * Make an empty row here and select it, or `undefined` where that must not be offered.
   *
   * UNDEFINED IS A REFUSAL WITH A REASON, and the caller owns the reason: this tab passes it only
   * when the collection has been RECONCILED with the repository, because `putDraftStage` writes a
   * stage's entities wholesale and appending to a collection this browser never downloaded would
   * replace the repository's rows with the handful it happens to hold. A disabled button with no
   * sentence would be worse than no button; the panel says why above.
   */
  onAdd?: () => void;
  busy: boolean;
}) {
  if (rows.length === 0) {
    return (
      <div className="rounded-md border border-dashed border-line-200 bg-surface-50 px-3 py-3 text-sm">
        <p className="text-ink-700">{emptyWord}</p>
        {/*
          THE EMPTY STATE IS WHERE THIS MATTERS MOST — added 2026-08-28. Until then it offered only
          "Add one on its stage", which sends a designer holding a drawing away from the screen the
          feature exists to save them. Both routes are kept: the button makes a row here so a file
          has somewhere to land, and the link is still the way to NAME it and fill in the rest.
        */}
        {onAdd ? (
          <button type="button" className="field-button mt-2" onClick={onAdd} disabled={busy}>
            {addWord}
          </button>
        ) : null}
        {emptyHref ? (
          <Link href={emptyHref} className="mt-2 block text-sm font-medium text-purple-700">
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
      {/* Offered beside a populated picker too, not only in the empty state: a workshop documents
          several sketches, and the second one is added from exactly here. */}
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
