"use client";

/**
 * The 3D half of the UPLOAD tab: the model file, and the honest account of what becomes of it.
 *
 * THERE IS NO RIVAL FIELD HERE AND THERE MUST NOT BE. The registry already declares both halves of
 * this on the prototype entity, and this panel is a surface over them, never a second place to keep a
 * prototype:
 *
 *     f("turntablePhotos", "360° capture", IMGS, A, phase_note="Reviewer: “Kumar da team”."),
 *     f("modelFile",       "3D model",     FILE, A, phase_note="Reviewer: “Kumar da team”."),
 *
 * (`backend/app/services/stage_definitions.py`, the prototype block — read on 2026-08-22.) Both were
 * asked for by the same reviewer and both already exist. Adding a third field for "the 3D thing"
 * would give one prototype two answers to one question.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * WHY THIS PANEL LEADS WITH THE TURNTABLE AND NOT WITH THE MODEL
 * ────────────────────────────────────────────────────────────────────────────
 *
 * Because of what the ministry document can carry, which is not a matter of opinion. In
 * `backend/app/services/report_builder.py`, `_images` — "the only placement path there is" — filters
 * on `FieldType.IMAGE` and `FieldType.IMAGE_LIST` (lines 1122–1123). Every other media type falls to
 * the branch above it, whose comment is unusually blunt about the bug that produced it:
 *
 *     "FILE, AUDIO AND VIDEO HAVE NO IMAGE PATH TO BE PLACED BY … A designer attached the ministry's
 *      sanction order at stage 1 and the .docx the officer received did not mention that a sanction
 *      order existed."
 *
 * That is now fixed as far as it can be, and the fix is a sentence: a FILE field prints
 * `"1 document attached"` — a count and a noun, deliberately not even a filename, because the module
 * is also the on-device report builder and may not query for one.
 *
 * So: **`turntablePhotos` is an IMAGE_LIST and reaches the printed page as pictures. `modelFile` is a
 * FILE and reaches it as the words "1 document attached" — and no viewer built into this application
 * can change that**, because the constraint is in the document generator, not in the browser. A
 * designer who uploads only a .glb has, from the officer's point of view, uploaded nothing they can
 * see. That is worth one paragraph on screen at the moment they are choosing, and it is the reason
 * this panel exists at all rather than a bare file input.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * WHY THERE IS NO IN-BROWSER VIEWER IN THIS WAVE
 * ────────────────────────────────────────────────────────────────────────────
 *
 * Not because it would be hard, and not because it is unwanted — but this is the honest accounting:
 *
 *  - `frontend/package.json` has 29 dependencies and **none of them can render a 3D model** (checked
 *    for three, babylon, model-viewer, gltf and webgl on 2026-08-22). A viewer therefore means a new
 *    dependency, and `package.json` is not this unit's file to change.
 *  - The unit's own brief permits a viewer only against a **real measured gzipped cost**. A figure
 *    cannot be measured for a library that has not been installed, and this repository's rule is that
 *    a number is quoted only by whoever ran the command that produced it. Quoting three.js's
 *    advertised size here would be exactly the fabricated citation the last audit wave was full of.
 *  - And it would buy less than it appears to. A viewer helps the designer standing at this screen; it
 *    does nothing for the officer reading the .docx, who is the person the turntable is for.
 *
 * So this wave makes the model file **uploadable and retrievable** — which is what makes it useful to
 * the next designer, who opens it in the CAD tool they already have — and spends its screen space on
 * getting the turntable filled in. If a viewer is added later, dynamic-import it, and measure it.
 *
 * ════════════════════════════════════════════════════════════════════════════
 * WHAT CAME ACROSS FROM THE SKETCHES HALF ON 2026-08-29, AND WHAT DID NOT
 * ════════════════════════════════════════════════════════════════════════════
 *
 * The Sketches half of this tab was rebuilt around one photograph chosen once (`SharedPhotoField`,
 * `UploadTabPanel.ChosenPhotograph`) and two cards drawn in one grammar (`SketchTraceField` and
 * `MeasureFromPhotoCard`). The instruction for this half was "the same, where applicable", and
 * "where applicable" is a judgement the next reader has to be able to check — so each half of it is
 * answered here, with the grep or the file that settles it.
 *
 * ── TRANSFERRED: THE CARD GRAMMAR ──────────────────────────────────────────────────────────────
 *
 * These two cards used to be a different species from the three others on the tab: `rounded-lg` where
 * the others are `rounded-md`, a 32px tinted icon CHIP where the others carry a bare 16px glyph,
 * `font-display … font-semibold` headings where the others are `text-sm font-medium`, and all of
 * their padding on the root where the others put it on a header and a body with a rule between. A
 * designer moving from Sketches to Prototypes met five cards in three shapes on one screen. They are
 * one grammar now, and the grep that finds the family is the one those files already publish:
 *
 *     grep -n "flex items-start gap-2 p-3" frontend/components/sketches/upload/
 *
 * ── TRANSFERRED: THE BUSY, ERROR AND SUCCESS TREATMENTS ────────────────────────────────────────
 *
 * A turn of twenty-four frames is twenty-four IndexedDB writes, a draft save and a full re-read
 * before anything on screen changes, and this panel used to show NOTHING while that ran — the only
 * signal was the host's notice, a long way up the page, and a screen reader was told nothing at all.
 * Both cards now say what they are doing with a spinner in a live region that is mounted from the
 * first render (see the note on those regions: a live region mounted WITH text in it is announced by
 * nobody, which is the defect `SketchTraceField` records at its own). Refusals and warnings carry
 * `role="alert"`, and amber is bordered, exactly as on the other half — §12.11 picks the treatment by
 * MEANING, and the meanings here are the same ones.
 *
 * ── NOT APPLICABLE: ONE UPLOAD FEEDING TWO CARDS ───────────────────────────────────────────────
 *
 * The sketch photograph is HELD, UNFILED, by the host, because two panels want it before it lands
 * anywhere and `sketch.image` is a single IMAGE field that an attach REPLACES — so a designer has to
 * be able to look at it, trace it and measure it before committing. Neither half of that is true
 * here. `turntablePhotos` is an append-only IMAGE_LIST where one more frame costs nothing, so this
 * card hands the frames over AT THE MOMENT OF THE PICK (`onFiles` → `chooseFrames` →
 * `onAttachTurntable`), and `UploadTabHost.attach` awaits its own `reload()` — so by the time that
 * call resolves the frames are on the row and the measuring card below has already re-read them.
 * There is no window in which a photograph picked HERE is in the designer's hand and off the record,
 * and that window is the entire thing `ChosenPhotograph` exists to cover. A shared picker here would
 * be a second control holding files back from a field that wants them immediately.
 *
 * The model file is the other half of the same answer and fails a different test: `PhotoMeasureField`
 * draws into an `<img>` and measures in natural pixels, and a `.glb` has no natural pixel size. It
 * has exactly one consumer and cannot acquire a second.
 *
 * WHAT THE MOUNT IN `UploadTabHost.tsx` ACTUALLY PASSES, BECAUSE THIS SENTENCE HAD IT BACKWARDS FOR
 * A REVISION. It read "the measuring card is deliberately given no `working` photograph on this
 * half", and that mount passes `working={prototypeWorking}` — a live prop, a few lines under a
 * comment saying it was not there. What is absent is the SHARED photograph, which is why
 * `prototypeWorking` can only ever be `source: "own"`: the one the measuring card's own escape
 * hatch took, which reaches no record, is measured, and is forgotten. A reader who cut the prop to
 * make the code agree with the old sentence would leave that hatch opening a file dialog, accepting
 * a photograph and changing nothing on screen. Nothing here argues against the prop; it argues
 * against a SECOND PICKER ABOVE THESE TWO CARDS, which is a different control.
 *
 * ── NOT APPLICABLE: FOLDING THESE TWO CARDS AWAY ───────────────────────────────────────────────
 *
 * The two cards on the Sketches half are disclosures because they are OPTIONAL TOOLS over a
 * photograph that has already been chosen somewhere else. These two are the uploaders themselves,
 * and the defect the other half just finished paying off was a picker two clicks deep behind a
 * disclosure. Being always open is what says these are the primary controls of the section — which
 * is the job the icon chip used to do worse, and is why losing the chip loses nothing.
 *
 * ── NOT APPLICABLE: MERGING THE TWO CARDS INTO ONE LOOPED UPLOADER ─────────────────────────────
 *
 * Android renders these same two field keys through one component in a loop
 * (`DwSketchChooserRows.kt#DW_CHOOSER_PROTOTYPE_FIELDS`), so the shape has a handset precedent — and
 * it is still the wrong move here, for a reason above and a reason below. Above: everything in this
 * header argues the two fields are NOT equal, and one control over both would say they are. Below:
 * their interaction models differ deliberately — the turntable hands over on the pick, the model file
 * STAGES so it can warn about an unknown extension or a 64 MB transfer BEFORE it starts and so a
 * host's refusal does not force a re-pick of a 200 MB file. A merged uploader deletes both silently.
 *
 * ── NOT APPLICABLE: ADDING `max-w-prose` TO THE BODY PARAGRAPHS ────────────────────────────────
 *
 * The recon that opened this work listed "this is the only file in `upload/` with no `max-w-prose`"
 * as a gap to close. It was closed the other way round: requirement 18 took the clamp OUT of the
 * cards on the Sketches half, because inside a card this narrow a 65ch clamp never binds the line —
 * it only makes one card's sentence wrap at a different width from the identical sentence in the card
 * beside it. This file was already right. Do not add one.
 */

import { useState } from "react";
import { AlertTriangle, Box, Camera, Check, FileBox, Loader2 } from "lucide-react";

import { DropCard } from "./DropCard";

// TYPE-ONLY, so the panel that composes this one can own the contract without a runtime cycle.
import type { AttachAnswer } from "@/components/sketches/upload/UploadTabPanel";

/**
 * The model formats worth naming, and the one that travels best.
 *
 * GLB IS NAMED FIRST DELIBERATELY. It is a single self-contained file, where OBJ arrives as a mesh
 * plus an .mtl plus a folder of textures — three of which a designer will attach and one of which
 * they will forget, producing an untextured grey blob for whoever opens it next. A field that holds
 * ONE file rewards a format that is one file, and saying so at the moment of choosing is cheaper than
 * discovering it a month later.
 */
const MODEL_FORMATS = [
  { ext: "glb", label: "GLB", note: "One self-contained file — mesh, materials and textures together. The best choice for this field." },
  { ext: "gltf", label: "glTF", note: "Usually a mesh plus separate texture files. Attach the .glb version where you have one." },
  { ext: "stl", label: "STL", note: "Geometry only, no colour. What most 3D printers expect." },
  { ext: "obj", label: "OBJ", note: "Geometry plus a separate .mtl and textures, which this single field cannot hold together." },
  { ext: "ply", label: "PLY", note: "Common output of a photogrammetry or scanning app." },
  { ext: "3mf", label: "3MF", note: "A printing format that does carry colour and materials in one file." },
  { ext: "fbx", label: "FBX", note: "Common from animation tools; large, and not every CAD package reads it." },
  { ext: "usdz", label: "USDZ", note: "What an iPhone's own scanner produces." }
] as const;

const MODEL_ACCEPT = MODEL_FORMATS.map((f) => `.${f.ext}`).join(",");

/**
 * Above this a model file is worth a word before it is attached.
 *
 * Not a refusal — the upload path handles large files properly, with multipart and per-file retry, and
 * a genuinely dense scan legitimately runs to hundreds of megabytes. But the designer standing in a
 * courtyard on a shared hotspot is the person who most needs to be told BEFORE the transfer starts,
 * not after it has been running for ten minutes.
 */
const LARGE_MODEL_BYTES = 64 * 1024 * 1024;

/**
 * How many frames a turntable wants.
 *
 * Twelve is one photograph every thirty degrees, which is the coarsest capture that still reads as
 * rotation rather than as a handful of unrelated views. Twenty-four is every fifteen degrees and is
 * what a reviewer can actually judge a form from. Both numbers are stated on screen rather than
 * enforced: a prototype photographed eight times is still better than one photographed never.
 */
const TURNTABLE_MINIMUM = 12;
const TURNTABLE_COMFORTABLE = 24;

export interface PrototypeModelFieldProps {
  /** The registry label of the FILE field the model lands in — "3D model" on the prototype entity. */
  modelLabel: string;
  /** The registry label of the IMAGE_LIST beside it — "360° capture". Named so the two are unambiguous. */
  turntableLabel: string;
  /** How many turntable frames the record already holds, when the host can say. */
  turntableCount?: number;
  disabled?: boolean;
  /** Hands the model file to the host — the ordinary door, exactly as the tracing panel does. */
  onAttachModel: (file: File) => AttachAnswer;
  /**
   * Hands a turn of photographs to the host. Absent means this host has nowhere to put them.
   *
   * A LIST, because a turn is one act of capture: a designer selects twelve frames in one dialog and
   * expects one confirmation, not twelve. The host writes them all into one draft save — see
   * `UploadTabHost.attach`.
   *
   * WITHOUT IT THIS PANEL STILL SAYS WHAT THE FIELD IS FOR and says plainly that it has to be filled
   * on the prototype's own stage form. It does not go quiet: the advice is the reason this panel
   * exists, and a host that cannot take the frames does not make the advice untrue.
   */
  onAttachTurntable?: (files: File[]) => AttachAnswer;
}

export function PrototypeModelField({
  modelLabel,
  turntableLabel,
  turntableCount,
  disabled,
  onAttachModel,
  onAttachTurntable
}: PrototypeModelFieldProps) {
  /*
    THERE IS NO `useId` HERE ANY MORE, AND THAT IS THE POINT RATHER THAN A LOSS.

    This panel used to derive an id prefix and hand it to two labels and two inputs, because it is not
    a singleton: one prototype row per prototype and a dialog copy over the top of it are both
    ordinary, and two `id="prototype-model-file"` inputs in one document make each label point at
    whichever came first — so the label beside the second input focuses the first one and a screen
    reader names the wrong control. That is a real hazard, and this file shipped both halves of it: a
    hardcoded id under a derived `htmlFor`, which named nothing at all.

    Both pickers are now `DropCard`s, and each one derives its own ids from its own `useId`. The
    invariant is the same and it is no longer a caller's to keep.
  */
  const [chosen, setChosen] = useState<File | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  /** What was said about the last turn handed over, kept apart from the model file's own sentence. */
  const [turntableDone, setTurntableDone] = useState<string | null>(null);
  const [turntableProblem, setTurntableProblem] = useState<string | null>(null);
  /*
    ── WHAT IS BEING WRITTEN RIGHT NOW, PER CARD, AND WHY THIS PANEL HAD NEITHER ──────────────────

    Handing a turn over is not instant and never was: `UploadTabHost.attach` stages every frame into
    IndexedDB one at a time, writes the draft, and then awaits its OWN `reload()` — two full stage
    reads from the repository — before this call resolves. On a handset with twenty-four frames that
    is seconds of a screen where nothing moved, no button changed and no sentence appeared. The only
    signal was the host's notice at the top of the tab, which is above the row pickers and off screen
    by the time somebody has scrolled down to this card.

    TWO STATES AND NOT ONE, for the same reason `done` and `turntableDone` are two: the cards are
    written to independently and a sentence under the wrong one is worse than no sentence. They are
    sentences rather than booleans because the sentence names the field, and a field label that came
    off the registry is the half of it a reader has to be able to check.

    CLEARED IN A `finally`, so a host that throws does not leave a spinner running for the rest of the
    afternoon over a write that is long dead — the failure `UploadTabHost` reports in its own words
    and this panel must not contradict by still claiming to be busy.
  */
  const [turntableBusy, setTurntableBusy] = useState<string | null>(null);
  const [modelBusy, setModelBusy] = useState<string | null>(null);

  function choose(file: File) {
    setDone(null);
    setChosen(file);
    const extension = file.name.split(".").pop()?.toLowerCase() ?? "";
    const known = MODEL_FORMATS.find((f) => f.ext === extension);
    const notes: string[] = [];
    if (!known) {
      notes.push(
        `“.${extension}” is not one of the model formats this field expects. It will still be stored and can ` +
          "still be downloaded, but the next designer may not have anything that opens it."
      );
    } else if (known.ext === "obj" || known.ext === "gltf") {
      notes.push(`${known.label}: ${known.note}`);
    }
    if (file.size > LARGE_MODEL_BYTES) {
      notes.push(
        `This file is ${formatBytes(file.size)}. It will upload in parts and will resume if the connection ` +
          "drops, but on a shared hotspot that is a long transfer — worth starting somewhere with signal."
      );
    }
    setWarning(notes.length > 0 ? notes.join(" ") : null);
  }

  /**
   * Hand the model file over, and say it was added ONLY IF THE HOST DID NOT REFUSE IT.
   *
   * THE DEFECT. This used to call `onAttachModel` and then set the green sentence unconditionally, so
   * a host that refused synchronously — no prototype row chosen, or a stage whose repository copy
   * could not be read — rendered its own red "This file has not been attached: …" directly above a
   * tick claiming it had been. The same was true of an IndexedDB write that failed a moment later.
   * See {@link AttachAnswer} for why the host answers `false` rather than throwing.
   *
   * `!== false` AND NOT TRUTHINESS: a host with nothing to report returns `undefined`, which is the
   * unchanged "no answer, assume it landed" behaviour of every record form that mounts this panel.
   *
   * THE CHOSEN FILE AND ITS WARNINGS SURVIVE A REFUSAL. Clearing them would leave the designer with
   * the host's refusal, no file staged, and a picker to re-open — for a 200 MB model chosen off a
   * shared hotspot that is a re-pick nobody should have to make. On a refusal the panel keeps exactly
   * what it had, which is the same discipline `StageMediaNoteField` applies to an over-length
   * selection.
   */
  async function attach() {
    if (chosen === null) return;
    setDone(null);
    setModelBusy(`Adding ${chosen.name} to “${modelLabel}” on this device…`);
    try {
      const handed = await onAttachModel(chosen);
      if (handed === false) return;
      setDone(`${chosen.name} was added to “${modelLabel}”.`);
      setChosen(null);
      setWarning(null);
    } finally {
      setModelBusy(null);
    }
  }

  /**
   * Hand a turn of photographs over, having first refused what is demonstrably not one.
   *
   * NON-IMAGES ARE REFUSED HERE, BY NAME, AND NOT LEFT TO THE SERVER. The field is an IMAGE_LIST, so
   * a .mov of the prototype rotating — which is the single likeliest wrong file for this box, because
   * it is what a phone produces when somebody films instead of photographing — would be staged into
   * the draft, uploaded, and refused by `coerce_value` on the stage save with the frames already
   * gone from the picker. The `accept` attribute is a filter and not a rule (a designer can always
   * choose "all files"), so the check is made on the files that actually arrived.
   *
   * THE ONES THAT ARE IMAGES STILL GO. Refusing the whole selection because one file in it was a
   * video would throw away eleven good frames to punish the twelfth, and the sentence names both
   * halves so nothing is silently dropped — the rule §1.10 of the frontend guide states.
   */
  async function chooseFrames(files: File[]) {
    setTurntableProblem(null);
    setTurntableDone(null);
    if (!onAttachTurntable || files.length === 0) return;
    const frames = files.filter((file) => file.type.startsWith("image/"));
    const rejected = files.filter((file) => !file.type.startsWith("image/"));
    if (frames.length === 0) {
      setTurntableProblem(
        `“${turntableLabel}” holds photographs, and nothing chosen here is one. A video of the piece turning ` +
          "cannot go in it — the report places image fields as pictures and would have nothing to draw for a film. " +
          "Take the frames as still photographs, or attach the video to “Process video” on the prototype's stage."
      );
      return;
    }
    /*
      REFUSED BY THE HOST MEANS NOT ADDED, AND THE TICK MUST NOT SAY OTHERWISE.

      `onAttachTurntable` was called and the green "N photographs were added to …" set on the very
      next line, unconditionally. The host can refuse it synchronously (`refuse("prototype")` when no
      row is chosen or the stage's repository copy could not be read) and can fail its device write a
      moment after that, and on either path the host's red sentence and this green one rendered
      together — the exact contradiction the panel's own copy is supposed to prevent. See
      {@link AttachAnswer}.

      THE TICKS AND THE REFUSAL LIST ARE LEFT ALONE on a refusal for `attach`'s reason above: the
      designer keeps their selection and the host has already said what to do about it.
    */
    setTurntableBusy(
      `${frames.length === 1 ? "Adding 1 photograph" : `Adding ${frames.length} photographs`} to “${turntableLabel}” on this device…`
    );
    try {
      const handed = await onAttachTurntable(frames);
      if (handed === false) return;
      setTurntableDone(
        `${frames.length === 1 ? "1 photograph was" : `${frames.length} photographs were`} added to “${turntableLabel}”.` +
          (rejected.length > 0
            ? ` ${rejected.length === 1 ? "One other file was" : `${rejected.length} other files were`} left out because ` +
              `${rejected.length === 1 ? "it is" : "they are"} not photographs: ${rejected.map((file) => file.name).join(", ")}.`
            : "")
      );
    } finally {
      setTurntableBusy(null);
    }
  }

  const frames = turntableCount ?? 0;
  const turntableTone =
    frames >= TURNTABLE_COMFORTABLE ? "good" : frames >= TURNTABLE_MINIMUM ? "fair" : "thin";

  return (
    <div className="grid gap-3">
      {/* ── The turntable comes first, because it is the half that prints ─────────────── */}
      {/*
        ── THE CARD SHELL, WHICH IS THE OTHER HALF OF THE TAB'S GRAMMAR ─────────────────────────

        `rounded-md` on a bordered `<section>`; a header holding a bare 16px glyph, an `<h4>` at
        `text-sm font-medium` and one description line, in a `min-w-0 flex-1` column; the padding on
        the header and on the body with a rule between them, and none on the root. Byte for byte the
        shell `MeasureFromPhotoCard` and `SketchTraceField` settled on — and those are not cards on
        some other screen, the measuring one is mounted at the foot of THIS section. Three shapes in
        one column read as three different kinds of thing, and until this change that is what a
        designer moving between the two halves met: `rounded-lg` here against `rounded-md` there, a
        32px tinted icon chip here against a bare glyph there, `font-display … font-semibold` here
        against `text-sm font-medium` there.

        TWO THINGS ARE DELIBERATELY NOT COPIED ACROSS, and both are stated so the next reader does
        not "finish" the job:

          1. THE TINT. These two cards stay `bg-card` while those two are `bg-surface-50`, which is
             the layering `MeasureFromPhotoCard`'s own root note blesses by name ("sizes were
             normalised across the pair; TINTS WERE NOT"). The reason lives inside this card rather
             than beside it: a `DropCard` IS `bg-surface-50` under a dashed border, so a
             `bg-surface-50` card would stand the drop zone on its own ground and erase it. The other
             half carries the same nesting the other way up — `DifferentPhoto` draws a `bg-card` box
             inside a `bg-surface-50` card for exactly this.
          2. THE DISCLOSURE. See the header: these are the uploaders themselves, not optional tools
             over something already chosen elsewhere, and the defect the Sketches half has just
             finished paying off was a picker folded two clicks deep behind one.
      */}
      <section className="rounded-md border border-line-200 bg-card">
        <div className="flex items-start gap-2 p-3">
          <Camera className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <div className="min-w-0 flex-1">
            <h4 className="text-sm font-medium text-ink-900">
              “{turntableLabel}” is the 3D view a reviewer will actually see
            </h4>
            {/*
              THE DESCRIPTION LINE IS THE REPORT FACT, and it sits in the header rather than in the
              body because that is where both cards on the other half put the sentence that decides
              whether somebody is willing to use the card at all.

              THE WORDS ARE UNCHANGED AND MAY NOT CHANGE FROM HERE ALONE.
              `SketchesAndPrototypesScreen.kt#DW_PROTOTYPE_3D_IN_THE_REPORT` carries them on the
              handset under a KDoc claiming they are this file's sentence verbatim, and no test on
              either client would notice the two drifting apart. A copy edit here is a two-client
              edit; moving the paragraph, which is what happened, is not one.
            */}
            <p className="mt-0.5 text-xs leading-5 text-ink-500">
              The ministry document places image fields as pictures and prints every other kind of attachment
              as a count — a 3D model appears in it as the words “1 document attached”, and no viewer built
              into this application can change that, because the limit is in the document generator rather
              than in the browser. A turn of photographs is the only form of this prototype that reaches the
              printed page.
            </p>
          </div>
        </div>

        <div className="border-t border-line-200 p-3">
          {/* The capture advice — `DW_TURNTABLE_CAPTURE_ADVICE` on the handset, verbatim, same rule. */}
          <p className="text-xs leading-5 text-ink-500">
            Stand the piece still and move around it, one photograph every 30° for {TURNTABLE_MINIMUM} frames, or
            every 15° for {TURNTABLE_COMFORTABLE} — enough that a reviewer can read the form rather than guess it.
            Keep the light and the background the same for all of them.
          </p>
          {turntableCount !== undefined ? (
            <p
              className={
                turntableTone === "good"
                  ? "mt-2 inline-flex items-center gap-1.5 rounded-md bg-success-100 px-2 py-1 text-xs font-medium text-success-600"
                  : turntableTone === "fair"
                    ? "mt-2 inline-flex items-center gap-1.5 rounded-md bg-amber-100 px-2 py-1 text-xs font-medium text-amber-800"
                    : "mt-2 inline-flex items-center gap-1.5 rounded-md bg-field-100 px-2 py-1 text-xs font-medium text-ink-700"
              }
            >
              {turntableTone === "good" ? <Check className="h-3.5 w-3.5" aria-hidden /> : null}
              {frames === 0
                ? "No frames yet"
                : frames === 1
                  ? "1 frame so far"
                  : `${frames} frames so far`}
              {turntableTone === "good"
                ? " — a full turn"
                : turntableTone === "fair"
                  ? ` — readable; ${TURNTABLE_COMFORTABLE} is a full turn`
                  : ` — ${TURNTABLE_MINIMUM} is the fewest that reads as a turn`}
            </p>
          ) : null}

          {/*
            ── THE PICKER, WHICH THIS PANEL SPENT TWO PARAGRAPHS ASKING FOR AND DID NOT HAVE ─────

            Everything above this input is advice: that the report places image fields as pictures,
            that a 3D model prints as a count, that twelve frames is the fewest that reads as a
            turn. All of it true, and until this control existed the designer's only way to act on
            it was to leave the page for the prototype's stage form — which the panel never said.
            A screen that advises a field it cannot write teaches a reader to ignore its advice.

            The picker itself is now a `DropCard` — see the note on it below, which carries what used
            to be said here about `multiple` and about `accept` being a filter rather than a rule.
          */}
          {onAttachTurntable ? (
            <div className="mt-3">
              {/*
                A DROP CARD, AND `validate` IS DELIBERATELY NOT PASSED. `chooseFrames` below is the
                rule for this field and it is a better rule than a MIME test: it takes the frames
                that are photographs, names the ones that are not, and explains in domain terms why
                a video of the piece turning cannot go in an IMAGE_LIST. Two refusal sentences for
                one refusal would be a worse answer than either. `DropCard` still clears the input
                after every pick, which is the behaviour this block used to have to spell out.

                `multiple`, because a turn is ONE act of capture — twelve to twenty-four files chosen
                in one dialog, or dragged out of one folder in one gesture.

                ── AND THE ACCEPT LIST WAS NOT TOUCHED WHEN THE CARDS WERE MADE TO MATCH ──────────

                Three pickers on this tab take images and no two of them take quite the same set.
                This one is the widest: bare `image/*`, no `validate`, so an SVG frame goes in — which
                is right for an IMAGE_LIST that only has to be displayed. `SharedPhotoField` on the
                other half refuses the SVG, because what happens to its file is a TRACE and
                rasterising vector art to trace it back can only lose; `DifferentPhoto` refuses it
                for a third reason again, that an SVG has no natural pixel size to measure in. Those
                are three different jobs and three honest answers, and the temptation while unifying
                the chrome was to give them one list.

                SO THE SENTENCE STAYS DIFFERENT TOO, deliberately. This card says "Photographs,
                chosen or dropped together" where the other two name types and ceilings, because
                naming a type list here would be printing a narrower promise than the control
                actually keeps — the same defect as quoting a cap nobody read. What a card refuses
                has to be what its own sentence says it refuses.
              */}
              <DropCard
                label={`Add photographs to “${turntableLabel}”`}
                buttonLabel="Choose the whole turn"
                accept="image/*"
                acceptSentence="Photographs, chosen or dropped together. They are added to this prototype in the order they arrive, kept on this device straight away, and uploaded with everything else."
                multiple
                disabled={disabled}
                onFiles={(files) => chooseFrames(files)}
              />
            </div>
          ) : (
            <p className="mt-3 text-xs leading-5 text-ink-500">
              Frames are added to “{turntableLabel}” on the prototype&apos;s own stage form — this panel
              cannot take them.
            </p>
          )}
          {/*
            `role="alert"` NOW, WHICH IT DID NOT CARRY. This box is the answer to something the
            designer just did — they chose a film of the piece turning for a field that holds
            photographs — and it appears below the picker they used, which on a phone is off the
            bottom of the screen. Without the role a reader is told nothing at all and the pick
            simply looks as though it did nothing. `DropCard`'s own refusal box has always
            announced itself this way and so has the tracing panel; this is the same treatment,
            not a second invention of one.

            RED AND NOT AMBER, and the pair on the other half is why: §12.11 picks the treatment by
            MEANING, red is "what you just asked for did not happen", and amber is "something you
            did not ask about could not be read". Nothing was added. That is red.
          */}
          {turntableProblem ? (
            <p
              role="alert"
              className="mt-2 flex items-start gap-2 rounded-md border border-red-200 bg-error-100 px-2 py-1.5 text-xs leading-4 text-error-600"
            >
              <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>{turntableProblem}</span>
            </p>
          ) : null}
          {/*
            ── ONE LIVE REGION PER CARD, MOUNTED FROM THE FIRST RENDER, HOLDING BOTH SENTENCES ───

            MOUNTED ALWAYS AND NOT WITH THE SENTENCE. A live region that arrives in the same commit
            as its own text is new DOM that already carries words, and a reader announces a
            region's CHANGES rather than its arrival — so a `<p aria-live>` rendered conditionally
            is silent exactly when it matters. `SketchTraceField` keeps its success region outside
            its own open/closed switch for this reason and says so at length; this is the pair of
            it, and it is why the wrapper below is unconditional while its contents are not.

            BOTH SENTENCES IN ONE REGION because they are one story told twice — "this is being
            written" and then "this was written" — about one field, and they cannot both be true at
            once. Two regions would let a stale tick sit under a running spinner.
          */}
          <div aria-live="polite" aria-atomic="true">
            {turntableBusy ? (
              <p className="mt-2 flex items-start gap-2 text-xs text-ink-500">
                <Loader2 className="mt-0.5 h-3.5 w-3.5 shrink-0 animate-spin" aria-hidden />
                <span>{turntableBusy}</span>
              </p>
            ) : turntableDone ? (
              <p className="mt-2 flex items-start gap-2 text-xs text-ink-500">
                <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success-600" aria-hidden />
                <span>{turntableDone}</span>
              </p>
            ) : null}
          </div>
        </div>
      </section>

      {/* ── The model file ───────────────────────────────────────────────────────────── */}
      {/* The shell above, drawn again — see the note on it. The two cards were already identical to
          each other in outer chrome; what changed is that they are now identical to the other three
          cards on this tab as well. */}
      <section className="rounded-md border border-line-200 bg-card">
        <div className="flex items-start gap-2 p-3">
          <Box className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <div className="min-w-0 flex-1">
            <h4 className="text-sm font-medium text-ink-900">“{modelLabel}”</h4>
            <p className="mt-0.5 text-xs leading-5 text-ink-500">
              Worth attaching even though it does not print: it is the only form another designer can measure,
              re-scale, section or send to a printer. They download it and open it in the tool they already
              have — which is why the format matters more than any viewer would.
            </p>
          </div>
        </div>

        <div className="border-t border-line-200 p-3">
          {/*
            A DROP CARD, AND THE `id`/`htmlFor` PAIR THIS BLOCK USED TO GET WRONG IS GONE WITH IT.

            What was here was a hardcoded `id="prototype-model-file"` under a
            `htmlFor={`${fieldId}-model-file`}` label, so the label named no element at all — no
            accessible name, and not a click target either, which are the two things a label is
            for. `DropCard` derives every id it needs from its own `useId`, so the pair cannot be
            mismatched by a caller and cannot collide when two of these panels are on one page.

            NOT `multiple`: the field holds one file. A designer who drops three is told the first
            was used, by the card, rather than having two of them silently vanish.

            No `validate`: `choose` below already says what it thinks of the file — an unexpected
            extension and a very large upload are both WARNINGS rather than refusals, because the
            field takes any file and the next designer may well have the tool that opens it. A
            MIME test here would turn advice into a refusal.
          */}
          <DropCard
            label="Model file"
            buttonLabel="Choose a model file"
            accept={MODEL_ACCEPT}
            acceptSentence={`${MODEL_FORMATS.map((format) => format.label).join(", ")} — or any other file, which is stored and downloadable but may not open for the next designer. GLB travels best; the list below says why.`}
            disabled={disabled}
            onFiles={(files) => {
              const file = files[0];
              if (file) choose(file);
            }}
          />

          {/*
            STILL A NATIVE `<details>`, WHICH IS NOT AN OVERSIGHT NOW THAT THE CARDS ARE ONE SHAPE.

            The two cards on the Sketches half fold with a `<button aria-expanded>` and a rotating
            chevron, and copying that here was the obvious "consistency" move. It is the wrong one:
            that idiom exists in this directory because those cards fold their ENTIRE body away and
            need a control at the foot of it as well, which `<details>` cannot give a caller (it is
            the argument `MeasureFromPhotoCard` makes for not using `components/ui/Accordion.tsx`).
            This is a reference list under a picker. `<details>` is the house idiom for exactly that
            — fourteen call sites across `components/` and `app/` on 2026-08-29:

                grep -rn "<details" frontend/components frontend/app

            `mt-3` rather than `mt-2`, which IS part of the pass: one step between the blocks of a
            card body, everywhere on this tab.
          */}
          <details className="mt-3">
            <summary className="cursor-pointer text-xs font-medium text-purple-700">
              Which format should I attach?
            </summary>
            <dl className="mt-2 grid gap-1.5">
              {MODEL_FORMATS.map((entry) => (
                <div key={entry.ext} className="text-xs leading-4">
                  <dt className="inline font-medium text-ink-900">{entry.label} </dt>
                  <dd className="inline text-ink-500">— {entry.note}</dd>
                </div>
              ))}
            </dl>
          </details>

          {chosen ? (
            <div className="mt-3 rounded-md border border-line-200 bg-surface-50 p-2">
              <p className="flex items-center gap-2 text-xs font-medium text-ink-900">
                <FileBox className="h-3.5 w-3.5 shrink-0" aria-hidden />
                <span className="truncate">{chosen.name}</span>
                <span className="shrink-0 text-ink-500">{formatBytes(chosen.size)}</span>
              </p>
              {/*
                AMBER, BORDERED, AND ANNOUNCED — the treatment every other amber box on this tab
                carries (`MeasureFromPhotoCard`'s read failure and its unreadable-count line). It
                had the fill and neither of the other two, so it read as a tinted aside rather than
                as the same kind of statement, and a reader was never told it had appeared.

                AMBER AND NOT RED, deliberately, and §12.11's rule is the one that decides: nothing
                has failed here. The file is staged, it will upload, and this is the panel saying
                something about it BEFORE the transfer starts — an extension the next designer may
                not be able to open, or sixty-four megabytes about to leave on a shared hotspot.
                Red would tell a designer their file had been refused when it has not.
              */}
              {warning ? (
                <p
                  role="alert"
                  className="mt-2 flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-2 py-1.5 text-xs leading-4 text-amber-800"
                >
                  <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
                  <span>{warning}</span>
                </p>
              ) : null}
              <div className="mt-2 flex flex-wrap gap-2">
                <button type="button" className="field-button" onClick={attach} disabled={disabled}>
                  Add to “{modelLabel}”
                </button>
                {/*
                  "PUT THIS FILE AWAY", WHICH IS WHAT THE BUTTON DOES. It used to say "Choose
                  another" and it opens no dialog: it clears the staged file, and choosing another
                  is then a second press on the picker above. A label that names an action the
                  control does not perform is the same defect as a sentence pointing at the wrong
                  panel, one element smaller.

                  THE WORDS COME FROM THE OTHER HALF rather than being invented here.
                  `SharedPhotoField` calls the identical act — put down what is held, without
                  filing anything and without opening a dialog — "Put this photograph away". Same
                  act, same words, one noun apart because one is a photograph and one is a file.

                  `disabled` FOR THE SAME REASON ITS TWIN IS. The pair of buttons is one decision
                  about one staged file, and leaving the escape enabled while the commit is greyed
                  out would let a designer discard a file at the exact moment the host is telling
                  them why it could not be written.
                */}
                <button
                  type="button"
                  className="field-button-secondary"
                  disabled={disabled}
                  onClick={() => {
                    setChosen(null);
                    setWarning(null);
                  }}
                >
                  Put this file away
                </button>
              </div>
            </div>
          ) : null}

          {/* The pair of the turntable card's region — see the note there for why it is mounted
              unconditionally and why both sentences share it. */}
          <div aria-live="polite" aria-atomic="true">
            {modelBusy ? (
              <p className="mt-2 flex items-start gap-2 text-xs text-ink-500">
                <Loader2 className="mt-0.5 h-3.5 w-3.5 shrink-0 animate-spin" aria-hidden />
                <span>{modelBusy}</span>
              </p>
            ) : done ? (
              <p className="mt-2 flex items-start gap-2 text-xs text-ink-500">
                <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success-600" aria-hidden />
                <span>{done}</span>
              </p>
            ) : null}
          </div>
        </div>
      </section>
    </div>
  );
}

/**
 * @returns a size a person reads, in the units a phone shows.
 *
 * Binary units against decimal prefixes, which is technically the wrong pairing and is what every
 * file manager on every device these designers own does — a card that disagreed with the operating
 * system about how big a file is would be read as a bug in the card.
 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "unknown size";
  if (bytes < 1024) return `${Math.round(bytes)} B`;
  const units = ["KB", "MB", "GB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}
