"use client";

/**
 * A block of Markdown, rendered, with the two things anybody ever does to one: copy it, save it.
 *
 * ── WHY IT EXISTS, IN THE OWNER'S WORDS ───────────────────────────────────────────────────────
 * 2026-08-30: *"The message should be properly formatted with the markdown formatting helper that is
 * there for the current implementation of copying and downloading should be there as well, implement
 * that too."*
 *
 * Before this file those were THREE UNRELATED OWNERS and one of them did not exist at all. Rendering
 * was `components/Markdown.tsx`, reached from six places. Copying was a single bare
 * `navigator.clipboard?.writeText(...)` in `ArtisanQuestionnairePanel.tsx` — no await, no feedback,
 * no failure path, and the only copy button anywhere near a transcript. Downloading a transcript did
 * not exist in this client in any form: the one "download" on the questionnaire family was a CSV of
 * the interview table, which is a different document about different things.
 *
 * So the three are composed here ONCE and mounted wherever a transcript or a rich answer is shown,
 * rather than re-derived per screen. That matters more than the saved lines: a transcript copied from
 * the consolidated page and a transcript copied from the interview form have to be the same bytes, or
 * the two surfaces disagree about what the artisan said.
 *
 * ── WHAT TRAVELS IS THE MARKDOWN, NOT THE RENDERED HTML ───────────────────────────────────────
 * Both buttons hand over `text` verbatim. The temptation is to copy the rendered text instead — it
 * looks tidier in a mail — and it is wrong here for a reason specific to what these documents are: a
 * refined transcript's speaker labels ARE the markdown, and the horizontal rules are how a multi-clip
 * transcript separates takes. Flattening them produces a wall of prose in which nobody can tell who
 * spoke, which is the one thing the refinement pass was run to establish.
 *
 * ── AND THE FLAG SITS IN `children` RATHER THAN IN A PROP ─────────────────────────────────────
 * The edited/not-edited chip is `EditedFlag` below, and callers pass it in. It could have been a
 * boolean prop, and then this component would own where the flag came from — which is three different
 * questions on the three surfaces that draw it (derived live in the interview form, read off
 * `MediaFile.transcriptEditedAt` on the consolidated page, absent entirely for rows stored before the
 * column existed). A slot keeps this file ignorant of all three.
 */

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { Check, Copy, Download, PencilLine, Sparkles } from "lucide-react";

import { Markdown } from "@/components/Markdown";

/**
 * Hand a blob to the browser as a download, and release the object URL once it has been taken.
 *
 * A near-twin of `RecordCode.tsx`'s private `saveBlob`, named here so a later extraction has both
 * sites in view rather than finding one of them. The `setTimeout` is the part that must not be
 * "simplified": Safari reads the href asynchronously after the synthetic click, so revoking in the
 * same tick cancels the download it was asked to start.
 */
function saveBlob(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = name;
  anchor.rel = "noopener";
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

/**
 * A file name that cannot break a file system or run away with a prompt.
 *
 * Windows refuses backslash, slash, colon, asterisk, question mark, double quote, angle brackets and
 * pipe outright, and every one of them is reachable from a question prompt or a section title, which
 * is what these names are built from. The 60-character ceiling is not cosmetic either: a
 * questionnaire prompt runs to two thousand characters, and several filesystems cap one path segment
 * at 255 BYTES, which a UTF-8 Devanagari title reaches a long way before it reaches 255 characters.
 */
export function safeDocumentFileName(base: string, extension: string): string {
  const cleaned = base
    .replace(/[\\/:*?"<>|]/g, "-")
    .replace(/\s+/g, "-")
    .replace(/-+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 60);
  return `${cleaned || "transcript"}.${extension}`;
}

/**
 * Whether the words on screen are still the machine's, in three states and deliberately not two.
 *
 * `edited === undefined` draws NOTHING, and that absence is a real answer rather than a gap:
 * `MediaFile.transcriptEditedAt` was added on 2026-08-31 and is NULL for every row stored before it,
 * so those rows genuinely do not say. Drawing "Not edited" over them would assert "the machine said
 * this" about text a researcher may well have rewritten through `POST /media/{id}/transcript`, which
 * has been able to replace a transcript all along — and that assertion is the exact one the owner
 * asked for a flag in order to stop being made silently. The migration says the same at more length.
 *
 * The word is on screen and never only the colour: non-negotiable 5 covers a signal carried by
 * colour alone exactly as it covers one carried by motion.
 */
export function EditedFlag({ edited }: { edited?: boolean }) {
  if (edited === undefined) return null;
  return edited ? (
    <span className="inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[0.7rem] font-semibold uppercase tracking-wide text-amber-800">
      <PencilLine className="h-3 w-3" aria-hidden />
      Edited
    </span>
  ) : (
    <span className="inline-flex items-center gap-1 rounded-full bg-surface-50 px-2 py-0.5 text-[0.7rem] font-semibold uppercase tracking-wide text-ink-500 ring-1 ring-line-200">
      <Sparkles className="h-3 w-3" aria-hidden />
      Not edited
    </span>
  );
}

export function MarkdownDocument({
  text,
  /** The human part of the download's file name. Passed through {@link safeDocumentFileName}. */
  filenameBase,
  /** Drawn at the left of the action row — the edited flag, a provider name, a clip count. */
  children,
  className
}: {
  text: string;
  filenameBase: string;
  children?: ReactNode;
  className?: string;
}) {
  const [copied, setCopied] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const timer = useRef<number | null>(null);

  // The timeout outlives the component if a reader navigates within two seconds of copying, and a
  // `setCopied` after unmount is a warning in development and a leak everywhere.
  useEffect(
    () => () => {
      if (timer.current !== null) window.clearTimeout(timer.current);
    },
    []
  );

  const copy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(text);
      setProblem(null);
      setCopied(true);
      if (timer.current !== null) window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => setCopied(false), 2000);
    } catch {
      // A refused clipboard is not an error box. The text is on screen in full and selectable, which
      // is the path this existed on before there was a button — `RecordCode.copy` answers a refusal
      // the same way and for the same reason.
      setProblem("This browser did not allow copying. The text above can be selected by hand.");
    }
  }, [text]);

  const save = useCallback(() => {
    // `text/markdown`, not `.txt`: the speaker labels and rules ARE markdown, so a `.md` opens as
    // formatted text in every editor a researcher is likely to have while still reading fine raw.
    saveBlob(
      new Blob([text], { type: "text/markdown;charset=utf-8" }),
      safeDocumentFileName(filenameBase, "md")
    );
  }, [text, filenameBase]);

  return (
    <div className={`grid gap-2 ${className ?? ""}`.trim()}>
      <div className="flex flex-wrap items-center gap-2">
        {children}
        {/* Pushed right so the flag reads first: the state of the text matters before what can be
            done with it. `ml-auto` on the group rather than `justify-between` on the row, because
            the row has no children at all on the surfaces that pass no flag. */}
        <div className="ml-auto flex flex-wrap items-center gap-2">
          <button type="button" className="field-button-secondary !min-h-8 !px-2.5 !py-1 text-xs" onClick={copy}>
            {copied ? <Check className="h-3.5 w-3.5" aria-hidden /> : <Copy className="h-3.5 w-3.5" aria-hidden />}
            {copied ? "Copied" : "Copy"}
          </button>
          <button type="button" className="field-button-secondary !min-h-8 !px-2.5 !py-1 text-xs" onClick={save}>
            <Download className="h-3.5 w-3.5" aria-hidden />
            Download
          </button>
        </div>
      </div>
      <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2">
        <Markdown text={text} />
      </div>
      {problem ? <p className="text-xs text-ink-muted">{problem}</p> : null}
    </div>
  );
}
