"use client";

/**
 * The document, as it stands, beside the stage being filled in.
 *
 * Asked for on 2026-08-25: *"Add a live preview of the generated report/document within the web
 * application so that designers can see how the document will look as they enter or modify
 * information."* The full-page preview at `/design-workshops/{id}/report` has drawn the whole
 * document as real A4 sheets for a long time; what did not exist was any way to see it from the
 * screen where the words are actually typed. A designer writing the cluster background had to
 * finish, navigate away, and come back to find out that their four paragraphs printed as one wall
 * of text under the wrong heading.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * IT IS STILL NOT A FIFTH RENDERER, AND THAT IS THE CONSTRAINT EVERYTHING ELSE HERE BENDS AROUND
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `GET /report/preview` builds the SAME `ReportDocument` the .docx writer, the server .pdf writer
 * and the two on-device Kotlin writers consume. This component fetches that and hands the blocks to
 * `ReportBlock`, exactly as the report page does. It reconstructs nothing.
 *
 * The tempting alternative was to render the preview from the LOCAL DRAFT, which would make it
 * update on every keystroke with no round trip. It is refused, and not narrowly: there are already
 * four renderers of this document that must agree line-for-line about a file a ministry receives,
 * and a fifth built in the browser from the stage form's own state would be the only one nobody ever
 * opens a file to check. It would drift, silently, and it would drift on the screen a designer trusts
 * PRECISELY SO THAT they do not have to open the file.
 *
 * ── SO WHAT "LIVE" HONESTLY MEANS HERE, SAID ON SCREEN AND NOT ONLY IN THIS COMMENT ─────────────
 *
 * It follows the SAVES, not the keystrokes. The stage editor already saves progressively — that is
 * the whole shape of the feature — so in practice a designer types a paragraph, the stage saves, and
 * the panel redraws with the paragraph in it. What it can never show is an unsaved keystroke, and
 * the strip at the top of the panel says so in those words. A preview that silently lagged the form
 * would be worse than no preview: the designer would read the absence of their last edit as the
 * report dropping it.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * FINDING THIS STAGE INSIDE THE WHOLE DOCUMENT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The payload is the ENTIRE document and its blocks carry no stage identity — `HeadingBlock.bookmark`
 * exists on the dataclass and the builder never assigns it (checked, not assumed). So the slice is
 * found by the one thing that is genuinely in the payload: the heading whose text is this stage's
 * title, down to the next heading at the same level or above.
 *
 * THAT IS A LOCATOR AND NOT A RE-DERIVATION, which is the distinction that keeps it inside the rule
 * above. Every block drawn is a block the server built, in the server's order, with the server's
 * runs; the heading match only decides where to start reading and where to stop. Nothing is
 * computed from stage data.
 *
 * AND WHEN THE MATCH FAILS IT SAYS SO AND SHOWS EVERYTHING. A stage whose heading the template
 * renames, or a stage with nothing in it yet and therefore no heading at all, falls back to the
 * whole document with a sentence explaining that this is what happened. Rule 10 of the frontend
 * contract: a view that quietly showed nothing would be indistinguishable from a stage that prints
 * nothing, which is the single most repeated bug class in this repository.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE DOCUMENT'S OWN LOSSES TRAVEL WITH IT IN THE PAYLOAD, AND THEY ARE DRAWN
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `DwPreview.warnings` is the list of things the report CANNOT print as it stands: a required field
 * nobody filled in, a photograph the resolver could not read, a gallery over the template's cap, an
 * attached file the report names and does not contain. Its own declaration in `lib/designWorkshops`
 * says *"Render these; never hide them"*, and the reason it has to say so is that THE WARNINGS DO NOT
 * TRAVEL INSIDE THE DOCUMENT — an officer opening the .docx next month must not find a note about
 * what was missing on the day — so a screen is the only place they can be read at all.
 *
 * THIS PANEL DREW THE BLOCKS AND DROPPED THE WARNINGS, which made it the one report surface in the
 * repository that hid the document's own losses, on the one surface a designer trusts INSTEAD of
 * opening the file. Stage 8 with a survey document attached printed "1 document attached" and
 * suppressed the sentence, from the same response, saying the file is not inside the report; a capped
 * gallery, a photograph the resolver could not read and every unfilled required field went the same
 * way.
 *
 * THEY ARE THE WHOLE DOCUMENT'S, AND THEY ARE SHOWN AS THE WHOLE DOCUMENT'S. The list is free
 * strings. Most of them name their own stage in prose ("Stage 8 (Market Survey): 3 required field(s)
 * not recorded"), some belong to no stage at all (a template substitution, the cover table's
 * overflow), and NOTHING in the payload marks which stage a warning came from. Picking this stage's
 * warnings out by matching a number inside a sentence would be a re-derivation — the precise thing
 * this file refuses to do with blocks — and it would fail quietly, dropping the warning that
 * mattered. So every warning is drawn and the sentence above them states that scope. Same wording and
 * same amber treatment as `report/page.tsx`, because two screens describing one document's losses in
 * two different ways is how a designer learns to believe neither.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY IT IS CLOSED UNTIL ASKED FOR
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Building the document server-side loads every stage, resolves forty media rows and rasterises the
 * figures. That is the right cost for a designer who wants to see the document and the wrong one to
 * pay on every open of every stage, by every designer, including the ones on a village connection
 * who are here to type one number. So the panel is a disclosure: no fetch happens until it is
 * opened, and once open it refreshes on save.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ChevronDown, ChevronRight, FileText, Loader2, RefreshCw } from "lucide-react";

import {
  MediaUrlProvider,
  ReportBlock,
  useReportMediaUrls
} from "@/components/designworkshop/report/ReportBlock";
import type { PreviewBlock } from "@/components/designworkshop/report/previewModel";
import { previewDesignWorkshopReport, type DwRun } from "@/lib/designWorkshops";
import { readableError } from "@/components/review/reviewErrors";
import { isUnreachable } from "@/lib/failureTriage";

/** A heading's text, flattened from its runs. */
function runsText(runs: DwRun[] | undefined): string {
  return (runs ?? []).map((run) => run.text ?? "").join("");
}

/**
 * Fold for comparing a heading against a stage title.
 *
 * Case, surrounding space and the section NUMBER come off. The number matters: the builder prints
 * "4. Cluster, Area & Craft Background" as a `number` field beside the runs on some templates and
 * inside the runs on others, and a comparison that kept it would match on one template and fail on
 * the next — which is the failure mode this whole slice is one fallback away from anyway.
 *
 * Runs of whitespace collapse too, for the reason `selectFilter.fold` gives: a title assembled from
 * two pieces with an empty one between them carries a double space, and matching what the reader
 * visibly sees then matches nothing.
 */
function fold(value: string): string {
  return value
    .replace(/^[\s\d.]+/, "")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
}

/**
 * The blocks belonging to one stage, or `null` when its heading is not in the document.
 *
 * `null` and not `[]`, and the difference is the whole reason the caller can be honest: an empty
 * array would mean "this stage prints nothing", which is a legitimate and different answer from
 * "I could not find where this stage begins".
 */
export function sliceStageBlocks(blocks: PreviewBlock[], stageTitle: string): PreviewBlock[] | null {
  const wanted = fold(stageTitle);
  if (!wanted) return null;

  const startAt = blocks.findIndex(
    (block) => block.type === "HEADING" && fold(runsText(block.runs)) === wanted
  );
  if (startAt < 0) return null;

  const start = blocks[startAt];
  const level = start.type === "HEADING" ? start.level : 1;

  // The next heading at the same level OR SHALLOWER ends the slice. Shallower matters: a stage that
  // is the last one under a part heading is followed by the NEXT PART, whose level is smaller, and a
  // test for equality alone would run the slice on to the end of the document.
  let endAt = blocks.length;
  for (let at = startAt + 1; at < blocks.length; at += 1) {
    const block = blocks[at];
    if (block.type === "HEADING" && block.level <= level) {
      endAt = at;
      break;
    }
  }
  return blocks.slice(startAt, endAt);
}

type State =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "failed"; message: string; offline: boolean }
  /**
   * `warnings` is carried in the state and never re-fetched or re-derived, for the reason the header
   * gives: it is the SAME response's account of what the document could not print, and the two must
   * not be able to disagree. It may legitimately be empty — an empty list means "nothing was lost",
   * which is a different and equally printable answer from "there were losses and this panel is not
   * showing them", which is the state this file used to be in.
   *
   * THE STAGE'S SLICE IS DELIBERATELY NOT HERE, AND IT USED TO BE. It was stored beside `all`, cut
   * with whatever `stageTitle` happened to be on the props when the response landed — and the
   * disclosure button renders unconditionally, above the stage page's own `loading` branch, so it is
   * clickable while the field registry is still being fetched and `stageTitle` is still `""`.
   * `sliceStageBlocks` answers null for an empty title, the `drawn.current === refreshToken` guard
   * below then barred the one re-run the arriving title could cause, and the panel sat on that null
   * for the rest of its life: the WHOLE document, under the amber sentence blaming the template for
   * wording the heading differently. `all` is the response and belongs in the state; the slice is a
   * VIEW of it, so it is cut at render from the title of the moment and cannot be left a title behind.
   */
  | { kind: "ready"; all: PreviewBlock[]; warnings: string[] };

export function StageDocumentPreview({
  /**
   * The id the REPOSITORY knows this workshop by — never the route param.
   *
   * `GET /report/preview` reaches `load_workshop_or_404`, which is a `find_unique` on the primary
   * key, so a `dwlocal-…` id is a hard 404 reading "Record not found". A workshop created with no
   * signal is banked under exactly such an id and KEEPS it in the browser's URL after the sync pass
   * has created the server record, so the route param and this id genuinely differ for the workshops
   * this panel is most useful on. The caller resolves it with `reportServerId`, which is the same rule
   * the report page and its history view use.
   *
   * WHERE THE CALLER HAS NO SERVER ID TO GIVE — `localOnly` is `true` or `null` — NOTHING IS ASKED OF
   * THIS VALUE: the effect below returns before the fetch and the body prints a sentence instead of a
   * document, so whatever the caller passes through is inert. It stays a plain `string` for exactly
   * that reason — a nullable id would put a branch in `load` that cannot be reached, and would read as
   * though the build might run without one.
   */
  workshopId,
  stageTitle,
  /**
   * Bumped by the stage page after every successful save. A NUMBER rather than a boolean or a
   * callback: the panel has to redraw when the same stage is saved twice in a row, and only a value
   * that changes every time can express that. Same reasoning as the `nonce` in the map's
   * pending-reveal — see `useRevealRow`.
   */
  refreshToken,
  /**
   * Does this workshop exist ONLY in the local draft store? `null` while the caller cannot say.
   *
   * The preview is built by the SERVER from the record the SERVER holds, so there is nothing to
   * preview for a workshop that has never synced. Said as a sentence rather than shown as a failed
   * fetch, which is what the report page does for the same case and for the same reason.
   *
   * THREE-VALUED, AND `null` IS NOT `false`. The answer lives on the DRAFT — `remoteId`, read through
   * `reportServerId` — so the caller cannot give it before it has read the draft, and this disclosure
   * is on screen and clickable from first paint. `null` says precisely that: nobody has established
   * yet whether the repository holds this workshop, so neither the "only on this device" sentence nor
   * a build against `workshopId` is warranted. Every test of this prop must therefore name the value
   * it means — a truthiness test reads `null` as "the repository has it" and issues the build.
   */
  localOnly
}: {
  workshopId: string;
  stageTitle: string;
  refreshToken: number;
  localOnly: boolean | null;
}) {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState<State>({ kind: "idle" });
  const [showAll, setShowAll] = useState(false);

  /**
   * Which `refreshToken` the panel has already drawn, and which fetch generation is the current one.
   *
   * ── TWO REFS, BECAUSE THEY ANSWER TWO DIFFERENT QUESTIONS ───────────────────────────────────────
   *
   * `drawn` stops a re-render re-issuing a fetch it has already made. `generation` decides which
   * IN-FLIGHT answer is still wanted, which is §14.5's list-page convention: this endpoint takes no
   * `AbortSignal`, and what matters is not cancelling the request but IGNORING the late one.
   *
   * ── THE DEFECT THAT MADE THE SECOND REF NECESSARY ───────────────────────────────────────────────
   *
   * `load` had no race guard at all. Building this document server-side walks every stage and
   * rasterises the figures, so it is slow by nature — and the panel is refreshed by the stage's
   * autosave, which is precisely the moment a second build is issued while the first is in flight.
   * If the FIRST resolved second it overwrote the newer document with the older one AND wrote its own
   * (lower) token into `drawn`; the effect's dependencies were unchanged, so nothing re-ran and the
   * panel sat there showing the document WITHOUT the paragraph the designer had just saved.
   *
   * That is the exact misreading this file's own header calls worse than having no preview: "the
   * designer would read the absence of their last edit as the report dropping it."
   */
  const drawn = useRef<number | null>(null);
  const generation = useRef(0);

  const load = useCallback(
    async (token: number) => {
      // CLAIMED BEFORE THE AWAIT, so a second call issued while this one is in flight supersedes it.
      const mine = generation.current + 1;
      generation.current = mine;
      setState({ kind: "loading" });
      try {
        const payload = await previewDesignWorkshopReport(workshopId);
        // THE LATE ANSWER IS DROPPED, INCLUDING ITS TOKEN. Writing `drawn` here unconditionally was
        // the second half of the defect: a superseded attempt would mark its own older token as
        // drawn, and since the effect's dependencies had not moved, nothing would ever correct it.
        if (generation.current !== mine) return;
        const all = payload.blocks as PreviewBlock[];
        // THE WARNINGS COME FROM THE SAME PAYLOAD AS THE BLOCKS, and they are stored in the same
        // commit. Reading `payload.blocks` and leaving `payload.warnings` on the floor was the defect:
        // the panel printed the document and swallowed the document's own account of what it could not
        // print. Kept behind the same generation guard as the blocks, so a superseded build can never
        // pair one answer's warnings with another answer's pages.
        setState({ kind: "ready", all, warnings: payload.warnings });
        drawn.current = token;
      } catch (error) {
        if (generation.current !== mine) return;
        setState({
          kind: "failed",
          message: readableError(error, "The document could not be built just now."),
          offline: isUnreachable(error)
        });
        // Released so a reconnect, or the next save, tries again rather than sitting on a stale
        // failure for as long as the panel stays open.
        drawn.current = null;
      }
    },
    // `stageTitle` IS NOT A DEPENDENCY, and its absence is a fix rather than an omission. Nothing in
    // here reads it any more — the slice is cut at render — and while it WAS one the only thing it
    // ever did was make the effect below re-enter and return at the `drawn` guard, so the re-slice it
    // looked like it was arranging never happened at all. Keying that guard on the title instead is
    // the wrong way to close the same hole: it would re-BUILD a document the server has not changed —
    // every stage loaded, every media row resolved, every figure rasterised — because a title string
    // arrived.
    [workshopId]
  );

  useEffect(() => {
    // `localOnly !== false` AND NOT `!localOnly`: the prop is three-valued and `null` — "the caller
    // does not know yet whether the repository holds this workshop" — is falsy. A truthiness test
    // would issue the build while `workshopId` is still the route's own `dwlocal-…` id, and
    // `load_workshop_or_404` answers that with "Record not found": the panel would report a failure
    // about a workshop nobody has looked up yet, which is the opposite of what the resolved id is for.
    if (!open || localOnly !== false) return;
    if (drawn.current === refreshToken) return;
    void load(refreshToken);
    // NO CLEANUP THAT RELEASES ANYTHING, and that is correct here rather than an omission. Nothing is
    // claimed on start that a teardown must hand back: `generation` is monotonic and is only ever
    // COMPARED, never released, so a strict-mode remount simply issues a second build and the first
    // one's answer is discarded on arrival. That is the opposite shape from the guard in
    // `DesignerProfileOnboarding`, which does have to be released — the difference is whether the
    // guard bars a later attempt (it must be released) or merely identifies the current one (it must
    // not be).
  }, [load, localOnly, open, refreshToken]);

  /**
   * This stage's own blocks, located in the response at render — see the `ready` state for why they
   * are not stored beside it. `null` still means "the heading is not in the document", which is the
   * answer the amber fallback below prints; it is never collapsed to `[]`.
   */
  const slice = useMemo(
    () => (state.kind === "ready" ? sliceStageBlocks(state.all, stageTitle) : null),
    [state, stageTitle]
  );
  const blocks = state.kind === "ready" ? (showAll ? state.all : (slice ?? state.all)) : [];
  const mediaUrls = useReportMediaUrls(blocks);

  return (
    <section className="panel mb-5">
      <button
        type="button"
        className="flex w-full items-center gap-2 px-4 py-3 text-left"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        {open ? (
          <ChevronDown className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        ) : (
          <ChevronRight className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        )}
        <FileText className="h-4 w-4 shrink-0 text-field-600" aria-hidden />
        <span className="min-w-0 flex-1 text-sm font-medium text-ink-900">
          Document preview
          <span className="ml-2 font-normal text-ink-500">
            — how this stage prints in the report
          </span>
        </span>
        {/*
          THE BUILD, SAID IN A WORD BESIDE THE SPINNER — rule 5.

          `animate-spin` is ZEROED by both reduced-motion sources in `globals.css`, so for a designer
          who asked for less motion a lone spinner is a static grey circle that says nothing at all;
          and it was `aria-hidden`, so it said nothing to a screen-reader user either. The word is the
          half that survives both. It stays in the HEADER because the build outlives the disclosure:
          collapse the panel mid-build and this is the only thing left on screen saying the repository
          is still working on it.

          THE WHOLE CLUSTER IS `aria-hidden` ON PURPOSE, and the accessibility half of this signal is
          the live region in the body below. This text sits inside the disclosure BUTTON, whose
          accessible name is its rendered text — letting "Building…" into that name would rename the
          control under a reader's fingers every time a save refreshed the panel, which is the trap
          `EntityForm`'s Add button carries a comment about.
        */}
        {state.kind === "loading" ? (
          <span aria-hidden className="flex shrink-0 items-center gap-1.5 text-xs font-medium text-ink-500">
            <Loader2 className="h-4 w-4 animate-spin" />
            Building…
          </span>
        ) : null}
      </button>

      {open ? (
        <div className="border-t border-line-200 p-4">
          {/*
            THREE BRANCHES, AND THE FIRST ONE IS WHY.

            "This workshop is still only on this device" is a claim about the REPOSITORY, and it can
            only be made once somebody has read the draft and found no `remoteId` on it. This panel
            used to be handed `isLocalWorkshopId(routeId)`, which is a claim about the ADDRESS: a
            workshop created with no signal keeps its `dwlocal-…` id in the URL long after the sync
            pass has created the record, so the sentence below was printed permanently over workshops
            the repository was holding — on the one screen a designer trusts instead of opening the
            file.

            `null` is the honest state before that read lands, and it must fall through to NEITHER of
            the others: the sentence below would be the same wrong claim again, and the document branch
            would build against an id the server has never seen. So it says what it does not know,
            which is rule 10 in one paragraph.
          */}
          {localOnly === null ? (
            <p className="text-sm leading-6 text-ink-700">
              What there is to draw is not settled yet: the preview is built by the repository from the
              record it holds, and this page has not established whether the repository holds this
              workshop at all. This panel will not claim either way — it draws the document, or says
              the workshop is still only on this device, as soon as that is known. If something stopped
              this page reading the workshop, the banners above say so.
            </p>
          ) : localOnly ? (
            <p className="text-sm leading-6 text-ink-700">
              This workshop is still only on this device. The preview, the .docx and the .pdf are all
              built by the repository from the record it holds, so there is nothing to draw until this
              workshop has synced. Everything captured so far is safe in this browser.
            </p>
          ) : (
            <>
              {/* THE HONEST FRAMING, ABOVE THE DOCUMENT AND NOT BURIED UNDER IT. See the header: this
                  follows the saves, not the keystrokes, and a designer who read it as keystroke-live
                  would take a missing last sentence for a report that had dropped it. */}
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2 rounded-md bg-surface-50 px-3 py-2">
                <p className="min-w-0 text-xs leading-5 text-ink-500">
                  Built by the repository from what has been <strong className="font-medium text-ink-700">saved</strong>
                  {" "}— the same document the .docx and .pdf are written from. It refreshes when the stage
                  saves, so anything still being typed is not in it yet.
                </p>
                <button
                  type="button"
                  className="field-button-secondary shrink-0"
                  disabled={state.kind === "loading"}
                  onClick={() => void load(refreshToken)}
                >
                  <RefreshCw className="h-4 w-4" aria-hidden />
                  Refresh
                </button>
              </div>

              {/*
                WHAT THE PANEL IS DOING, IN WORDS, IN A REGION THAT WAS MOUNTED BEFORE IT HAD ANY.

                THE DEFECT THIS FIXES. The body had branches for `failed` and for `ready` and NOTHING
                for `loading`, so opening the disclosure drew an EMPTY AREA under the strip above.
                Building this document server-side loads every stage, resolves forty media rows and
                rasterises the figures — seconds, and longer on a village connection — and for every
                one of them the panel looked exactly like a stage that prints nothing. That is the
                silent-emptiness class rule 10 exists against, and `DocumentPreview` already carries a
                "Loading the …" line for precisely this situation.

                ONE ELEMENT, ALWAYS RENDERED, `sr-only` WHEN IT HAS NOTHING TO SAY. Assistive
                technology announces mutations only inside a region that ALREADY EXISTED, so a region
                created in the same commit as its first sentence is silently dropped — `Toast`'s
                always-present viewport is the precedent and `SubmissionCard` carries this same class
                swap for this same reason. It must STAY a class swap on this one node: `hidden`
                (`display: none`), or rendering the element only while loading, takes it out of the
                accessibility tree and puts the defect straight back. `sr-only` rather than an empty
                box, so a panel with nothing to announce adds no dead space above the document.

                `role="status"` and not `alert`: nothing is broken and nothing has been lost — the
                repository is working — so interrupting a designer mid-sentence to say so is not
                warranted.
              */}
              <p
                role="status"
                aria-live="polite"
                className={
                  state.kind === "loading"
                    ? "mb-3 flex items-center gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-500"
                    : "sr-only"
                }
              >
                {/*
                  ONE NODE, ALWAYS RENDERED, class-swapped — the idiom `SubmissionCard` and
                  `EntityForm` use in this same session. Assistive technology only announces mutations
                  inside a region that already existed, so a region created together with its first
                  sentence announces nothing.

                  AND IT SPEAKS TWICE, WHICH THE FIRST VERSION DID NOT. It said only that the build had
                  STARTED, and then emptied — and an emptied region is not announced, so a screen-reader
                  user was told the repository had begun and never that it had finished. On a build this
                  file's own header describes as "loads every stage, resolves the media and draws the
                  figures", that is the half that matters: the reader is waiting for permission to look.

                  The ready sentence is deliberately short and says WHERE the result is, because the
                  document itself is the next thing in the DOM and a long sentence would delay reaching
                  it. The failed case is NOT spoken here — it has its own `role="alert"` below, which is
                  the right treatment for something the reader has to act on (§12.11).
                */}
                {state.kind === "loading" ? (
                  <>
                    <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin" aria-hidden />
                    Building the document from what has been saved. The repository loads every stage,
                    resolves the media and draws the figures, so this takes a few seconds — longer on a
                    weak connection. Nothing captured is at risk while it runs.
                  </>
                ) : state.kind === "ready" ? (
                  "The document is ready below."
                ) : (
                  ""
                )}
              </p>

              {state.kind === "failed" ? (
                <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
                  {state.offline
                    ? "There is no connection, so the repository could not build the document. Everything captured is safe; try again when you have signal."
                    : state.message}
                </p>
              ) : null}

              {state.kind === "ready" ? (
                <>
                  {/*
                    THE DOCUMENT'S OWN LOSSES, ABOVE THE DOCUMENT — see this file's header for why they
                    are here at all, and why they are the WHOLE document's.

                    THE TREATMENT IS COPIED, NOT INVENTED. `report/page.tsx` prints this same array in
                    an amber box with this same sentence and this same bulleted list, and the two
                    screens must not describe one document's losses differently — a designer who reads
                    "3 things the report cannot print" on one screen and a differently-shaped notice on
                    the other has no way to tell whether they are looking at the same three things.
                    Only the margin differs: `mb-3` is the rhythm inside this panel, against that
                    page's `mb-5` between sections.

                    THE SCOPE IS IN THE SENTENCE BECAUSE IT CANNOT BE IN A FILTER. These are free
                    strings; most name their own stage in prose, some belong to no stage, and nothing
                    marks which stage a warning came from. Narrowing them to "this stage" by matching a
                    number inside a sentence would drop the warning that mattered and say nothing about
                    having dropped it, so the panel shows all of them and says that is what it is
                    showing.

                    ABOVE the blocks, not under them: the whole point of a warning is to be read before
                    the reader concludes the document is complete.

                    amber-100 over amber-800 because those are the two rungs of the brand amber that
                    pair — `amber-50` and `amber-200` are stock Tailwind and do not (§3.5).
                  */}
                  {state.warnings.length ? (
                    <section className="mb-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
                      <p className="font-semibold">
                        {state.warnings.length} thing{state.warnings.length === 1 ? "" : "s"} the report cannot
                        print as it stands — across the whole document, not only this stage
                      </p>
                      <ul className="mt-1 ml-5 list-disc space-y-0.5">
                        {state.warnings.map((warning, index) => (
                          <li key={index}>{warning}</li>
                        ))}
                      </ul>
                      {/*
                        ⚠ THE CLAIM THIS SENTENCE USED TO MAKE WAS TOO STRONG, AND IT WAS CAUGHT IN REVIEW
                        AN HOUR AFTER IT WAS WRITTEN. It read "It is the same list the report page prints
                        above its sheets", which holds only while nobody has touched that page's template
                        picker: this panel always asks for the workshop's SAVED template (`load` passes no
                        `templateId`), whereas `report/page.tsx` passes its override once the designer has
                        chosen one. Most warnings are template-dependent — the tier cap, the photograph cap,
                        cover-table overflow, hidden custom sections, template substitution — so under an
                        override the two lists genuinely differ.

                        It is now written as what is actually guaranteed: same document, same template,
                        therefore the same list. That is still the useful half, because it tells a designer
                        these are not a second opinion invented by this panel — and it stops being a promise
                        the panel cannot keep the moment somebody tries a different template next door.
                      */}
                      <p className="mt-2 text-xs leading-5">
                        Each one is a sentence the repository wrote, and most of them name the stage they came
                        from; nothing in the payload says which stage a warning belongs to, so all of them are
                        listed here rather than a subset chosen by guesswork. This is the workshop’s saved
                        template, so the report page shows this same list unless you try a different template
                        there. None of it is carried inside the .docx or the .pdf — this screen and that one
                        are the only places it can be read.
                      </p>
                    </section>
                  ) : null}

                  {/* Rule 10. The fallback is never silent: a reader looking at the whole document
                      when they asked for one stage must be told that is what they are looking at. */}
                  {slice === null ? (
                    <p className="mb-3 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
                      This stage has no heading of its own in the document yet — usually because nothing
                      in it has been filled in, or because the chosen template words the heading
                      differently. The whole document is shown instead.
                    </p>
                  ) : (
                    <div className="mb-3 flex flex-wrap items-center gap-3">
                      {/*
                        THE FIGURE FOLLOWS THE VIEW, AND IT DID NOT.

                        `blocks` above is `showAll ? all : (slice ?? all)`, but this sentence was
                        unconditionally `slice.length` — so pressing "Show the whole document" left the
                        panel reading "Showing the 5 blocks this stage contributes." beside three
                        hundred rendered blocks. The button label flipped and the figure beside it did
                        not, which is the same defect class as a cap nobody states: the screen quietly
                        described something other than what it was drawing.

                        The whole-document sentence KEEPS the stage's own figure rather than dropping
                        it, because that is the number the reader pressed the button while holding —
                        "of which 5 come from this stage" is what tells them how much of the document
                        they are now scrolling is theirs.
                      */}
                      <p className="text-xs text-ink-500">
                        {showAll ? (
                          <>
                            Showing the whole document — all {state.all.length} block
                            {state.all.length === 1 ? "" : "s"}, of which {slice.length} come
                            {slice.length === 1 ? "s" : ""} from this stage.
                          </>
                        ) : (
                          <>
                            Showing the {slice.length} block
                            {slice.length === 1 ? "" : "s"} this stage contributes.
                          </>
                        )}
                      </p>
                      <button
                        type="button"
                        className="text-xs font-medium text-purple-700 underline underline-offset-2"
                        onClick={() => setShowAll((current) => !current)}
                      >
                        {showAll ? "Show only this stage" : "Show the whole document"}
                      </button>
                    </div>
                  )}

                  {blocks.length === 0 ? (
                    <p className="text-sm text-ink-500">
                      Nothing from this stage prints in the report yet.
                    </p>
                  ) : (
                    /*
                      NOT LAID OUT ON A4 SHEETS, unlike the report page, and that is a deliberate
                      difference rather than an unfinished one. `ReportSheets` answers "has the cover
                      table pushed the hero photograph onto page two" — a question about PAGES, which
                      needs the whole document and the real millimetres. This panel answers "does what
                      I just typed read correctly under the right heading", which is a question about
                      one stage's prose. Paginating a five-block slice would draw a mostly-empty sheet
                      and imply a page break that the file will not have.
                    */
                    <MediaUrlProvider urls={mediaUrls}>
                      <div className="grid gap-3 text-sm leading-6 text-ink-900">
                        {blocks.map((block, index) => (
                          <ReportBlock key={`${block.type}-${index}`} block={block} />
                        ))}
                      </div>
                    </MediaUrlProvider>
                  )}
                </>
              ) : null}
            </>
          )}
        </div>
      ) : null}
    </section>
  );
}
