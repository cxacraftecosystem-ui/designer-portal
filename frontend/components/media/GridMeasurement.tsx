"use client";

import { useRef, useState } from "react";
import { Check, X } from "lucide-react";

import {
  EMPTY_GRID_STATE,
  GRID_GROUP_HINTS,
  GRID_GROUP_LABELS,
  GRID_SECTION_HINT,
  gridAcceptLabel,
  gridProposalLabel,
  gridReduce,
  type GridEvent,
  type GridGroup,
  type GridState,
  type MeasurementMethodMarker
} from "@/components/media/gridProposal";
import { analyzeMeasurementImage } from "@/lib/media";

export type { GridDimension, GridGroup } from "@/components/media/gridProposal";
export type GridFiles = Partial<Record<GridGroup, File>>;

/**
 * What every measurement-grid photograph writes into its media row's `extraMetadata.purpose`.
 *
 * ── THE DEFECT THIS ENDS ──────────────────────────────────────────────────────────────────────
 * A grid shot is the FIRST photograph most products and toolkits ever get: a designer opens the
 * form, checks "Document using grid", lays the object on the ruled sheet and photographs it before
 * they have taken a single presentation picture. The server's `_reference_photos` picks a record's
 * photograph deterministically by `createdAt ASC, id ASC` — oldest first — so the oldest image is
 * the one that becomes `photo` in the reference payload, gets hydrated onto a stage entry, and is
 * printed in the .docx handed to a Development Commissioner's office. The ministry report showed a
 * sheet of graph paper captioned as the tool.
 *
 * ── WHY A MARKER AND NOT A RULE ABOUT CAPTIONS ────────────────────────────────────────────────
 * The captions these uploads carry ("Height grid (measurement) for …") do identify a grid shot, and
 * the server keeps a transitional clause matching them so the photographs ALREADY in the bucket
 * sort last too. But a caption is prose: it is translated, edited by a reviewer, and re-typed on the
 * handset, and a report that starts printing graph paper again because somebody tidied a sentence
 * is not a failure anybody would connect back to this. A machine-readable purpose written by the
 * uploading client is the durable half; the caption clause is the backfill for what predates it.
 *
 * ── THE STRING IS A THREE-SURFACE CONTRACT ────────────────────────────────────────────────────
 * The web writes it here, Android writes the identical string on its own grid captures, and the
 * server SORTS any candidate carrying it LAST. Change it in one place and the other two stop
 * agreeing silently — nothing errors, the report just starts printing ruled paper again.
 *
 * A SORT KEY AND NOT A `WHERE`, which is worth reading off `_reference_photos` rather than assumed:
 * the statement computes an `is_grid` boolean in a CTE and orders by
 * `parent, is_grid ASC, created_at ASC, id ASC`. Nothing is ever excluded into a blank and there is
 * no fallback branch — sorting last is itself why a product whose ONLY image is a grid shot still
 * gets that picture instead of an empty gallery. The backend docstring says so in those words
 * ("NOTHING IS EVER EXCLUDED INTO A BLANK"); this comment claimed a WHERE clause and an
 * every-candidate-excluded fallback, and a reader sent looking for either would find neither and
 * would be left believing there is a second code path to maintain.
 */
export const MEASUREMENT_GRID_PURPOSE = "MEASUREMENT_GRID";

/**
 * "Document using grid": length and breadth are read from a single top-down photo of the object on a
 * 1-inch grid sheet; height (optional) is read from its own side-on photo. Captured files are
 * reported up so the parent can also store them as media on save.
 *
 * ── IT PROPOSES; IT NO LONGER WRITES BY ITSELF, AND THAT IS A CORRECTNESS FIX ────────────────────
 *
 * `onLengthBreadth` / `onHeight` used to be called straight out of the `analyzeMeasurementImage`
 * success block, so a Gemini estimate reached `ProductForm`'s and `ToolForm`'s dimension state with
 * nobody's consent — and `records.merge_field_provenance` then stamped that field with the
 * `{by, byName, at}` of whoever pressed Save, which made the row assert that a NAMED HUMAN had
 * measured the object. The full argument, the rule it follows and the three other members of the
 * family that already followed it are in `components/media/gridProposal.ts`'s header; the rule in
 * one line is **a machine-produced value is a proposal on screen until a person accepts it**.
 *
 * Every decision this component makes about a reading lives in that module and NOT in this JSX, for
 * the reason it states: there is no React renderer in this repository's devDependencies, so a
 * judgement inside a component is only ever exercised by somebody looking at a screen — and "nothing
 * is written until the button is pressed" is exactly the judgement whose broken state looks identical
 * to its working one until a ministry reads the document.
 * `e2e/grid-measurement-proposal-unit.spec.ts` drives {@link gridReduce} instead.
 *
 * **THERE ARE EXACTLY TWO CALLS TO `onLengthBreadth` / `onHeight` BELOW AND BOTH ARE INSIDE
 * {@link accept}.** A third, anywhere, is the defect returning.
 *
 * ── WHAT THIS CONTROL COSTS, SAID BEFORE THE CAPTURE RATHER THAN AFTER IT ────────────────────────
 *
 * `POST /media/analyze-measurement` is a vision-model call: network-only, with no queue, no outbox
 * and no retry behind it. The section hint says so now (see `GRID_SECTION_HINT`), because in a
 * courtyard with no signal this control fails every time and the failure used to be a surprise at the
 * end. The on-device path — `lib/photoMeasure.ts` behind `PhotoMeasureField` — is pure projective
 * geometry with no network call anywhere in it, and it is the one that keeps working with no signal.
 */
export function GridMeasurement({
  includeHeight = true,
  onLengthBreadth,
  onHeight,
  onFilesChange
}: {
  includeHeight?: boolean;
  /**
   * Called ONLY from the accept button, and never with a figure nobody has looked at.
   *
   * The third argument is the method marker the server sent beside the reading, so the acceptance can
   * be recorded as what it was. Dropping it here instead would leave this component holding the one
   * fact nobody else can recover.
   *
   * BOTH CALL SITES READ IT NOW — 2026-08-27. This sentence used to continue: *"It is OPTIONAL so
   * that the two call sites which do not read it yet still compile: `ProductForm` and `ToolForm` must
   * collect it into a `measurementMethods` object on the save body (`{ lengthInches: marker, … }`),
   * and until they do, a save carries no marker and the server records `UNRECORDED`."* They do now,
   * through `components/forms/measurementMethods.ts`. Re-check with
   * `grep -n "measurementMethods:" components/forms/ProductForm.tsx components/forms/ToolForm.tsx`.
   *
   * IT STAYS OPTIONAL, FOR A REASON THAT DID NOT GO AWAY WITH THE CALL SITES. The value is `null`
   * whenever the API answered without a `methodMarker` — a deployment that predates the key — and a
   * required non-null parameter would be a lie about what this component can promise. A save that
   * carries no marker is recorded as `UNRECORDED`, which is honest, distinguishable, and never the
   * false human claim.
   */
  onLengthBreadth: (length: string | null, breadth: string | null, method?: MeasurementMethodMarker | null) => void;
  onHeight: (inches: string, method?: MeasurementMethodMarker | null) => void;
  onFilesChange: (files: GridFiles) => void;
}) {
  const [enabled, setEnabled] = useState<Set<GridGroup>>(new Set());
  const [files, setFiles] = useState<GridFiles>({});
  const [grid, setGrid] = useState<GridState>(EMPTY_GRID_STATE);

  /**
   * WHICH ANALYSIS EACH GROUP IS STILL WAITING FOR — the generation counter this repository uses
   * everywhere a late answer can arrive after the question stopped mattering.
   *
   * Without it, unchecking a dimension (or replacing its photograph) while its request is in flight
   * lets the old answer land afterwards and put an accept button under a photograph that is no longer
   * on the form. The offer would be for a figure read off an image the record will never hold.
   */
  const pending = useRef<Partial<Record<GridGroup, number>>>({});

  /** Bump the group's token so any answer still in flight for it is ignored when it arrives. */
  function abandon(group: GridGroup): number {
    const next = (pending.current[group] ?? 0) + 1;
    pending.current[group] = next;
    return next;
  }

  /**
   * Apply an event that CANNOT produce a write.
   *
   * Functional-updater form because these are dispatched from async continuations, where the `grid`
   * captured by the closure is a render or two old. The accept path deliberately does NOT use this —
   * see {@link accept}.
   */
  function dispatch(event: Exclude<GridEvent, { type: "ACCEPT" }>) {
    setGrid((current) => gridReduce(current, event).state);
  }

  function toggle(group: GridGroup, on: boolean) {
    setEnabled((prev) => {
      const next = new Set(prev);
      if (on) next.add(group);
      else next.delete(group);
      return next;
    });
    if (!on) {
      // Unchecking a group discards its captured photo (and tells the parent) so a disabled grid
      // photo is never uploaded on save.
      if (files[group]) {
        const nextFiles = { ...files };
        delete nextFiles[group];
        setFiles(nextFiles);
        onFilesChange(nextFiles);
      }
      // And it takes the offer with it, along with the status line — an unchecked dimension must come
      // back blank rather than carrying the last thing that was said about a photograph it no longer
      // has. `clearStatus` is what makes that different from the designer refusing a reading.
      abandon(group);
      dispatch({ type: "DISCARD", group, clearStatus: true });
    }
  }

  async function pick(group: GridGroup, file: File | null) {
    if (!file) return;
    const nextFiles = { ...files, [group]: file };
    setFiles(nextFiles);
    onFilesChange(nextFiles);
    const token = abandon(group);
    dispatch({ type: "CAPTURE", group });
    try {
      const response = await analyzeMeasurementImage(file, group === "height" ? "height" : undefined);
      if (pending.current[group] !== token) return;
      dispatch({ type: "ANALYSIS", group, response });
    } catch (error) {
      if (pending.current[group] !== token) return;
      // Three sentences rather than one — which failure this was decides what the designer should do
      // next, and the server distinguishes them deliberately. See `gridFailureStatus`.
      dispatch({ type: "FAILURE", group, error });
    }
  }

  /**
   * THE ONLY WRITE IN THIS COMPONENT.
   *
   * It reads `grid` straight out of the render closure rather than through a functional updater, and
   * that is deliberate: this runs from a click on a card the CURRENT render painted, so the proposal
   * in hand is the one whose figure the designer just read on the button. Performing the write inside
   * an updater would also be a side effect in a function React may call twice.
   */
  function accept(group: GridGroup) {
    const { state, write } = gridReduce(grid, { type: "ACCEPT", group });
    setGrid(state);
    if (!write) return;
    if (write.group === "lengthBreadth") {
      const length = write.readings.find((reading) => reading.dimension === "length")?.value ?? null;
      const breadth = write.readings.find((reading) => reading.dimension === "breadth")?.value ?? null;
      onLengthBreadth(length, breadth, write.marker);
    } else {
      const height = write.readings.find((reading) => reading.dimension === "height")?.value;
      if (height) onHeight(height, write.marker);
    }
  }

  function renderGroup(group: GridGroup) {
    const proposal = grid.proposals[group];
    const status = grid.status[group];
    return (
      <div className="grid gap-2">
        <label className="flex items-center gap-2 text-sm text-ink">
          {/* `react-hooks/refs` (eslint-plugin-react-hooks 7.1.1) fails this line with "Cannot
              access refs during render". IT IS WRONG HERE, and the suppression is scoped to the
              one line rather than turned off for the project so the rule keeps working
              everywhere else.

              WHAT THE RULE SEES: [renderGroup] is a plain function called DURING render, and it
              mentions [toggle], which calls [abandon], which reads and writes `pending.current`.
              The analysis is conservative about a synchronous function reachable from a render
              path and cannot prove this one is deferred.

              WHY IT IS DEFERRED: [toggle] is never CALLED here. It is closed over by an arrow
              handed to `onChange` on a DOM element, so it runs on a user event and never in a
              render pass. The sibling `<input type="file">` a few lines below does the same
              thing through [pick] and is NOT flagged, for the one difference that gives the
              analysis its answer: [pick] is `async`, so its body is deferred by construction.

              CHECKED 2026-08-27: this is the ONLY finding `npx eslint . --max-warnings=0`
              reports on the tree, and it arrived with commit 4218e51 rather than with any
              remediation edit — this file is untouched by that wave. The Lint step in
              `.github/workflows/checks.yml` says "This passes on the tree it landed with; if it
              starts failing, something new was added", and this is that something.

              IF YOU WOULD RATHER NOT SUPPRESS IT: the structural fix is to stop [toggle]
              reaching a ref at all — move the generation bump into the effect that consumes
              it — which is a change to how in-flight answers are abandoned and wants its own
              review. Do not simply delete [abandon] from here: unchecking a group while its
              analysis is in flight is exactly the case the counter exists for. */}
          {/* eslint-disable-next-line react-hooks/refs */}
          <input type="checkbox" checked={enabled.has(group)} onChange={(event) => toggle(group, event.target.checked)} />
          {GRID_GROUP_LABELS[group]}
        </label>
        {enabled.has(group) ? (
          <div className="grid gap-1 pl-6">
            <p className="text-xs text-ink-muted">{GRID_GROUP_HINTS[group]}</p>
            <input
              className="field-input"
              type="file"
              accept="image/*"
              capture="environment"
              onChange={(event) => pick(group, event.target.files?.[0] ?? null)}
            />
            {/* Polite, because the answer arrives seconds after the capture and a designer who has
                looked away is otherwise told nothing at all. It is a state, not a scroll position. */}
            {status ? (
              <p className="text-xs text-ink-muted" aria-live="polite">
                {status}
              </p>
            ) : null}
            {proposal ? (
              <div className="mt-1 grid gap-2 rounded-md border border-purple-300 bg-card p-3">
                {/* The heading is the whole promise of this card: it is on screen and in nothing
                    else. It borrows `PhotoMeasureField`'s "not saved yet" phrasing on purpose — the
                    two are the same act on the same form and must not read as different features. */}
                <p className="field-label">Read from the photo — not filled in yet</p>
                <p className="font-display text-xl font-bold text-ink-900">{gridProposalLabel(proposal)}</p>
                <p className="text-xs leading-5 text-ink-500">
                  A vision model estimated this from the grid squares. Check it against the object before you accept
                  it — nothing is written into a field until you press the button.
                  {proposal.selfReportedConfidence !== null ? (
                    <>
                      {" "}
                      The model rated itself {Math.round(proposal.selfReportedConfidence * 100)}% sure, which is its own
                      claim about itself and has never been checked against a tape measure.
                    </>
                  ) : null}
                </p>
                <div className="flex flex-wrap gap-2">
                  <button type="button" className="field-button" onClick={() => accept(group)}>
                    <Check className="h-4 w-4" aria-hidden />
                    {gridAcceptLabel(proposal)}
                  </button>
                  {/* The web's equivalent of the cross on Android's photo preview: a designer who has
                      decided the reading is wrong needs a way to clear the offer that is not
                      "uncheck the whole dimension and lose the photograph with it". */}
                  <button
                    type="button"
                    className="field-button-secondary"
                    onClick={() => dispatch({ type: "DISCARD", group })}
                  >
                    <X className="h-4 w-4" aria-hidden />
                    Discard this reading
                  </button>
                </div>
              </div>
            ) : null}
          </div>
        ) : null}
      </div>
    );
  }

  return (
    <section className="grid gap-3 rounded-lg border border-line-200 bg-field-100 p-4">
      <div>
        <h3 className="font-display font-bold text-lg text-ink">Document using grid</h3>
        <p className="mt-1 text-sm text-ink-muted">{GRID_SECTION_HINT}</p>
      </div>
      {renderGroup("lengthBreadth")}
      {includeHeight ? renderGroup("height") : null}
    </section>
  );
}
