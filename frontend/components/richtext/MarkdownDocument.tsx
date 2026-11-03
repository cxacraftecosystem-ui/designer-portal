"use client";

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { Check, Copy, Download, PencilLine, Sparkles } from "lucide-react";

import { Markdown } from "@/components/Markdown";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { asciiLeaf, fileName, splitExtension } from "@/lib/naming";
import { PORTAL } from "@/lib/namingPortal";

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

export function safeDocumentFileName(base: string, extension: string): string {
  const [stem] = splitExtension(base.replace(/-transcript$/i, "").trim());
  if (stem.startsWith(`${PORTAL}_`)) {
    return asciiLeaf(`${stem.replace(/_Recording(?=_|$)/, "_Transcript")}.${extension}`);
  }
  return fileName({ subject: stem, what: "Transcript", ext: extension });
}

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
  filenameBase,
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
      setProblem("This browser did not allow copying. The text above can be selected by hand.");
    }
  }, [text]);

  const save = useCallback(() => {
    saveBlob(
      new Blob([text], { type: "text/markdown;charset=utf-8" }),
      safeDocumentFileName(filenameBase, "md")
    );
  }, [text, filenameBase]);

  return (
    <div className={`grid gap-2 ${className ?? ""}`.trim()}>
      <div className="flex flex-wrap items-center gap-2">
        {children}
        <ActionGroup className="ml-auto">
          <button type="button" className="field-button-secondary min-h-8! px-2.5! py-1! text-xs" onClick={copy}>
            {copied ? <Check className="h-3.5 w-3.5" aria-hidden /> : <Copy className="h-3.5 w-3.5" aria-hidden />}
            {copied ? "Copied" : "Copy"}
          </button>
          <button type="button" className="field-button-secondary min-h-8! px-2.5! py-1! text-xs" onClick={save}>
            <Download className="h-3.5 w-3.5" aria-hidden />
            Download
          </button>
        </ActionGroup>
      </div>
      <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2">
        <Markdown text={text} />
      </div>
      {problem ? <p className="text-xs text-ink-muted">{problem}</p> : null}
    </div>
  );
}
