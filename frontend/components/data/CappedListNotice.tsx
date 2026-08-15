"use client";

/**
 * The render half of `components/data/cappedList.ts` — the line that says a list stopped short.
 *
 * Nothing is decided here. Which sentence, and whether there is one at all, is
 * {@link cappedListNotice}'s job for the reason its own header gives (one of its states cannot be
 * produced by any live database, so it must be reachable without a browser). This component exists
 * so that five screens draw that sentence the same way instead of five slightly different ones, and
 * so that a picker can point `aria-describedby` at it — an incomplete list is a fact a screen-reader
 * user needs at the control, not somewhere above it.
 *
 * It renders NOTHING when there is nothing to say, which is the common case. Do not wrap it in a
 * `<div className="mt-2">` that survives an empty notice: an empty box under every complete picker
 * is exactly the padding this UI has been asked twice to lose.
 */

import { cappedListNotice, type CutReach, type ListCut } from "@/components/data/cappedList";

export function CappedListNotice({
  cuts,
  reach = "none",
  id,
  className
}: {
  /**
   * The cuts to report, in the order the reader meets the controls. `null` entries are the normal
   * case (that list was complete) and are dropped — callers pass `listCut(...)` results straight in
   * without filtering, so a picker that stops being capped stops printing without an edit here.
   *
   * A plain STRING is a sentence some other decider in `cappedList.ts` has already worded — today
   * that is `flagCutNotice`, for the routes that report a cut as a boolean because they are not
   * paginated and hold no `total` (`GET /tasks/options`). It is accepted here, rather than given a
   * second component, so that every truncation line in the application still renders through one
   * element with one set of classes and one `aria-describedby` contract: two components would be two
   * chances for these sentences to start looking like different kinds of thing. `""` is dropped
   * exactly like `null`, which is what makes `flagCutNotice(undefined, …)` safe to pass straight in.
   *
   * What must NOT happen here is a caller assembling its own wording and passing it as a string.
   * The whole argument for `cappedList.ts` is that five screens describing one cut in five sentences
   * teaches a reader that none of them means much — the decision stays in that module.
   */
  cuts: Array<ListCut | string | null>;
  reach?: CutReach;
  /**
   * Only for a SINGLE-cut call site that wires `describedBy` on its picker. With several cuts the
   * ids would collide, so the component refuses to invent per-line ids rather than guessing.
   */
  id?: string;
  className?: string;
}) {
  const sentences = cuts
    .map((cut) => (typeof cut === "string" ? cut : cappedListNotice(cut, reach)))
    .filter((sentence) => sentence.length > 0);
  if (sentences.length === 0) return null;
  return (
    <div className={className ?? "mt-1 grid gap-0.5"} id={sentences.length === 1 ? id : undefined}>
      {sentences.map((sentence) => (
        // `text-ink-500` at `text-xs`, matching the truncation line under the design-workshop viewer
        // picker (components/settings/DesignWorkshopViewersPanel) — the two say the same kind of
        // thing and a reader who has met one should recognise the other.
        <p className="text-xs leading-5 text-ink-500" key={sentence}>
          {sentence}
        </p>
      ))}
    </div>
  );
}
