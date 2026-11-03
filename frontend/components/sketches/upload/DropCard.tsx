"use client";

import { useCallback, useId, useRef, useState, type ReactNode, type RefObject } from "react";
import { AlertTriangle, Upload } from "lucide-react";

export interface DropCardProps {
  label: string;
  buttonLabel: string;
  accept: string;
  acceptSentence: string;
  multiple?: boolean;
  disabled?: boolean;
  validate?: (file: File) => string | null;
  onFiles: (files: File[]) => void;
  children?: ReactNode;
  buttonRef?: RefObject<HTMLButtonElement | null>;
}

export function DropCard({
  label,
  buttonLabel,
  accept,
  acceptSentence,
  multiple,
  disabled,
  validate,
  onFiles,
  children,
  buttonRef
}: DropCardProps) {
  const cardId = useId();
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [dragging, setDragging] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const depth = useRef(0);

  const take = useCallback(
    (files: File[]) => {
      if (files.length === 0) {
        setRefusal(
          "That wasn't a file. Drag a file or use the button."
        );
        return;
      }
      const accepted: File[] = [];
      const refused: string[] = [];
      for (const file of files) {
        const why = validate ? validate(file) : null;
        if (why === null) accepted.push(file);
        else refused.push(`${file.name} — ${why}`);
      }
      const extra = !multiple && accepted.length > 1 ? accepted.length - 1 : 0;
      const taken = multiple ? accepted : accepted.slice(0, 1);

      const notes: string[] = [];
      if (refused.length > 0) {
        notes.push(
          refused.length === 1
            ? `One file was not taken: ${refused[0]}.`
            : `${refused.length} files were not taken: ${refused.join("; ")}.`
        );
      }
      if (extra > 0) {
        notes.push(
          `One file only — used the first.`
        );
      }
      setRefusal(notes.length > 0 ? notes.join(" ") : null);
      if (taken.length > 0) onFiles(taken);
    },
    [multiple, onFiles, validate]
  );

  return (
    <div className="grid gap-1">
      <span className="field-label" id={`${cardId}-label`}>
        {label}
      </span>
      <div
        className={
          dragging
            ? "grid gap-2 rounded-lg border-2 border-dashed border-purple-600 bg-purple-50 p-3 transition"
            : "grid gap-2 rounded-lg border-2 border-dashed border-line-200 bg-surface-50 p-3 transition"
        }
        onDragEnter={(event) => {
          if (disabled) return;
          event.preventDefault();
          depth.current += 1;
          setDragging(true);
        }}
        onDragOver={(event) => {
          if (disabled) return;
          event.preventDefault();
          if (event.dataTransfer) event.dataTransfer.dropEffect = "copy";
        }}
        onDragLeave={() => {
          if (disabled) return;
          depth.current = Math.max(0, depth.current - 1);
          if (depth.current === 0) setDragging(false);
        }}
        onDrop={(event) => {
          if (disabled) return;
          event.preventDefault();
          depth.current = 0;
          setDragging(false);
          take(Array.from(event.dataTransfer?.files ?? []));
        }}
      >
        <div className="flex flex-wrap items-center gap-2">
          <button
            ref={buttonRef}
            type="button"
            className="field-button"
            disabled={disabled}
            aria-describedby={`${cardId}-accept`}
            onClick={() => inputRef.current?.click()}
          >
            <Upload className="h-4 w-4" aria-hidden />
            {buttonLabel}
          </button>
          <span className="text-xs text-ink-500">
            {dragging ? "Let go to use this file." : "…or drag a file onto this card."}
          </span>
        </div>

        <input
          ref={inputRef}
          type="file"
          accept={accept}
          multiple={multiple}
          className="sr-only"
          tabIndex={-1}
          aria-hidden="true"
          disabled={disabled}
          onChange={(event) => {
            const files = Array.from(event.target.files ?? []);
            event.target.value = "";
            take(files);
          }}
        />

        <p id={`${cardId}-accept`} className="text-xs leading-4 text-ink-500">
          {acceptSentence}
        </p>

        {children}
      </div>

      {refusal ? (
        <p
          role="alert"
          className="flex items-start gap-2 rounded-md border border-red-200 bg-error-100 px-2 py-1.5 text-xs leading-4 text-red-800"
        >
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>{refusal}</span>
        </p>
      ) : null}
    </div>
  );
}
