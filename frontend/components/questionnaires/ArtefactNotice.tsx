"use client";

/**
 * WHAT EACH OF THE TWO DOWNLOADS CONTAINS, AND WHAT IT DOES NOT — said on screen, before anybody
 * presses anything.
 *
 * THIS PANEL IS A SAFETY CONTROL, NOT AN EXPLAINER. A questionnaire produces two .xlsx files with
 * the same title on them, that land in the same Downloads folder, and that look identical in an
 * email client:
 *
 *   * the QUESTION SET — the instrument. Questions, order, help text, required flags. Nothing else.
 *   * the FULL WORKBOOK — the same questions plus every sitting: each respondent's NAME, the
 *     interviewer's notes and every answer they gave.
 *
 * Attaching the second one to a message meant for the first is not a mistake the app can undo, and
 * the server cannot prevent it either — the owner is entitled to their own file. The only thing that
 * stands between a designer and that mistake is knowing, at the moment they choose, which file is
 * which. So this is drawn wherever either download is offered, and it names the contents of both
 * rather than only the one being offered: a notice that described only the safe file would leave the
 * dangerous one unexplained, which is the wrong half to leave out.
 *
 * IT ALSO STATES WHAT AN UPLOAD DOES, because that is the same fact read backwards and it is where
 * the second defect lived: a workbook that came out of the platform imports its questions and NOT
 * its answers, since those answers already exist here under the names of the people who recorded
 * them. A designer who uploads a colleague's file and sees no sittings must be able to find out why
 * without contacting anybody.
 *
 * Tokens only — `ink-*`, `line-200`, `surface-50` and the brand `amber-100`/`amber-800` rungs (never
 * `amber-50`/`amber-200`, which are stock Tailwind and do not pair with them). Nothing here inverts
 * incorrectly in dark mode as a result.
 */

import { useId, useState } from "react";
import { ChevronDown, FileSpreadsheet, Lock, Share2, Upload } from "lucide-react";

/**
 * THE SUMMARY IS ALWAYS ON SCREEN AND THE DETAIL IS BEHIND A DISCLOSURE, which is the one shape that
 * serves both readers.
 *
 * A four-paragraph panel pinned to the top of the questionnaire list is read once and scrolled past
 * for ever afterwards, so the sentence that matters would be invisible on exactly the hundredth
 * visit when the wrong file gets attached to an email. A disclosure that starts CLOSED with a
 * neutral label ("More about downloads") hides the warning from the person who has never met it.
 *
 * So the load-bearing sentence — which file carries respondents and which does not — is never
 * collapsed, and only the elaboration is. Plain conditional rendering rather than an animated
 * height: there is nothing here worth a motion branch, and an un-animated disclosure needs no
 * reduced-motion counterpart to get wrong.
 */
export function ArtefactNotice({ className }: { className?: string }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();

  return (
    <section className={`panel grid gap-4 p-4 ${className ?? ""}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="font-display text-lg font-bold text-ink-900">Two downloads, and they are not the same file</h2>
          <p className="mt-1 max-w-3xl text-sm leading-6 text-ink-muted">
            The <span className="font-medium text-ink-900">question set</span> carries your questions and no answers —
            that is the one to send to another designer. The <span className="font-medium text-ink-900">full .xlsx</span>{" "}
            carries every sitting recorded against the questionnaire: every respondent&rsquo;s name, every note and every
            answer. That one is yours.
          </p>
        </div>
        <button
          type="button"
          className="field-button-secondary"
          aria-expanded={open}
          aria-controls={open ? panelId : undefined}
          onClick={() => setOpen((current) => !current)}
        >
          <ChevronDown className={`h-4 w-4 ${open ? "rotate-180" : ""}`} aria-hidden />
          {open ? "Hide the detail" : "What is in each file?"}
        </button>
      </div>

      {open ? (
        <div id={panelId} className="grid gap-4">
          <ArtefactDetail />
        </div>
      ) : null}
    </section>
  );
}

/** The elaboration behind the disclosure. Its own component so the shell above stays one screen. */
function ArtefactDetail() {
  return (
    <>
      <div className="grid gap-3 md:grid-cols-2">
        <article className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-ink-900">
            <Share2 className="h-4 w-4 shrink-0 text-field-600" aria-hidden />
            Question set — safe to send
          </h3>
          <p className="text-sm leading-6 text-ink-700">
            The questions, the sections, their order, their help text and their required flags.
          </p>
          <p className="text-sm leading-6 text-ink-700">
            <span className="font-semibold text-ink-900">It contains no answers</span>, no respondents&rsquo; names and no
            recorded sittings. Any designer may download it, and whoever you send it to uploads it and gets their own
            empty questionnaire with your questions in it.
          </p>
        </article>

        <article className="grid gap-2 rounded-md border border-amber-500/30 bg-amber-100 p-3">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-amber-800">
            <Lock className="h-4 w-4 shrink-0" aria-hidden />
            Full .xlsx — your fieldwork
          </h3>
          <p className="text-sm leading-6 text-amber-800">
            The same questions <span className="font-semibold">plus every sitting</span>: each respondent&rsquo;s name,
            the notes taken during the interview and every answer given.
          </p>
          <p className="text-sm leading-6 text-amber-800">
            It is the file the app matches your edits against, so it is the one to re-upload after editing in Excel — and
            it is not one to pass on. Only you, a designer working on its design workshop, and an admin can take it.
          </p>
        </article>
      </div>

      <div className="grid gap-2 rounded-md border border-line-200 p-3">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-ink-900">
          <Upload className="h-4 w-4 shrink-0 text-field-600" aria-hidden />
          What happens to answers that are already in a file you upload
        </h3>
        <ul className="grid gap-1.5 text-sm leading-6 text-ink-700">
          <li className="flex items-start gap-2">
            <FileSpreadsheet className="mt-1 h-3.5 w-3.5 shrink-0 text-ink-500" aria-hidden />
            <span>
              <span className="font-medium text-ink-900">A pro-forma you filled in yourself</span> — answers you typed
              from your own paper interviews are imported as sittings and recorded under your name, with a note on each
              one saying it arrived on a spreadsheet.
            </span>
          </li>
          <li className="flex items-start gap-2">
            <FileSpreadsheet className="mt-1 h-3.5 w-3.5 shrink-0 text-ink-500" aria-hidden />
            <span>
              <span className="font-medium text-ink-900">A workbook that came out of the app</span> — its questions are
              imported and its answers are <span className="font-semibold">not</span>. Those answers are already recorded
              here, under the names of the people who recorded them; copying them into a second questionnaire would
              duplicate somebody&rsquo;s fieldwork and put your name on it. The report after the upload says so plainly.
            </span>
          </li>
        </ul>
      </div>
    </>
  );
}
