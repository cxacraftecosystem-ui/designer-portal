"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import {
  composePointerNote,
  composeRecordCount,
  countableMediaRows,
  hasPointerNote,
  mediaNoteOverrun,
  stripPointerNote
} from "@/components/designworkshop/mediaNoteGrammar";
import type { MediaNoteFieldRole } from "@/components/designworkshop/stageFieldRoles";
import { MultiSelectDropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { listResource } from "@/lib/api";
import { inputValue, type DwEntryData, type DwField, type DwValue } from "@/lib/designWorkshops";
import { formatDateTime } from "@/lib/format";
import type { MediaFile } from "@/lib/types";

const PAGE_SIZE = 100;

const CHOOSER_FLOOR = 2;

export function StageMediaNoteField({
  field,
  role,
  row,
  value,
  onChange,
  labelId,
  describedBy,
  invalid,
  disabled,
  dictation
}: {
  field: DwField;
  role: MediaNoteFieldRole;
  row: DwEntryData;
  value: DwValue | undefined;
  onChange: (next: DwValue) => void;
  labelId: string;
  describedBy?: string;
  invalid?: boolean;
  disabled?: boolean;
  dictation?: (control: React.ReactNode) => React.ReactNode;
}) {
  const refId = inputValue(row[role.refField.key]).trim();

  const [rows, setRows] = useState<MediaFile[] | null>(null);
  const [total, setTotal] = useState(0);
  const [failed, setFailed] = useState(false);
  const [picked, setPicked] = useState<string[]>([]);
  const [refusedOverrun, setRefusedOverrun] = useState(0);
  const generation = useRef(0);

  useEffect(() => {
    setRows(null);
    setTotal(0);
    setFailed(false);
    setPicked([]);
    setRefusedOverrun(0);
  }, [role.linkedRecordType, refId]);

  useEffect(() => {
    if (!refId) return;
    const mine = (generation.current += 1);
    let cancelled = false;
    (async () => {
      try {
        const collected: MediaFile[] = [];
        let serverTotal = 0;
        for (let page = 1; ; page += 1) {
          const result = await listResource<MediaFile>("/media", {
            linkedRecordType: role.linkedRecordType,
            linkedRecordId: refId,
            page,
            pageSize: PAGE_SIZE
          });
          if (cancelled || mine !== generation.current) return;
          collected.push(...result.items);
          serverTotal = result.total;
          if (result.items.length < PAGE_SIZE || collected.length >= result.total) break;
        }
        if (cancelled || mine !== generation.current) return;
        setRows(collected);
        setTotal(Math.max(serverTotal, collected.length));
      } catch {
        if (cancelled || mine !== generation.current) return;
        setFailed(true);
        setRows([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [role.linkedRecordType, refId]);

  useEffect(() => {
    if (rows === null) return;
    setPicked((current) => {
      if (!current.length) return current;
      const live = new Set(rows.map((media) => media.id));
      const kept = current.filter((id) => live.has(id));
      return kept.length === current.length ? current : kept;
    });
  }, [rows]);

  const countable = useMemo(() => countableMediaRows(rows ?? []), [rows]);
  const gridFrames = (rows?.length ?? 0) - countable.length;
  const wholeList = rows !== null && !failed && total <= rows.length;

  const recordCount = useMemo(
    () =>
      wholeList
        ? composeRecordCount({ subject: role.subject, rows: countable, numberedPrefix: role.numberedPrefix })
        : null,
    [wholeList, countable, role.subject, role.numberedPrefix]
  );

  const current = inputValue(value);
  const claim = stripPointerNote(current);

  const options = useMemo<DropdownOption[]>(
    () =>
      countable.map((media) => ({
        value: media.id,
        label: media.caption || media.originalFilename,
        hint: `${media.mediaType.toLowerCase()} · ${formatDateTime(media.createdAt)}`
      })),
    [countable]
  );

  const applySelection = useCallback(
    (next: string[]) => {
      setPicked(next);
      if (!next.length) {
        setRefusedOverrun(0);
        onChange(claim || null);
        return;
      }
      const chosen = countable.filter((media) => next.includes(media.id));
      const composed = composePointerNote({ subject: role.subject, base: claim || recordCount || "", rows: chosen });
      if (!composed) return;
      const overrun = mediaNoteOverrun(composed, field.maxLength);
      setRefusedOverrun(overrun);
      if (overrun) return;
      onChange(composed);
    },
    [claim, countable, recordCount, role.subject, field.maxLength, onChange]
  );

  const noteBox = (dictation ?? ((control: React.ReactNode) => control))(
    <input
      className="field-input"
      type="text"
      maxLength={field.maxLength || undefined}
      aria-labelledby={labelId}
      aria-describedby={describedBy}
      aria-invalid={invalid}
      value={current}
      disabled={disabled}
      onChange={(event) => {
        onChange(event.target.value);
        if (picked.length) setPicked([]);
        setRefusedOverrun(0);
      }}
    />
  );

  if (!refId) {
    return (
      <div className="grid gap-1">
        {noteBox}
        <p className="text-xs text-ink-500">
          Fills in from the {role.subject} record. Choose{" "}
          <span className="font-medium">{role.refField.label}</span> to list its files.
        </p>
      </div>
    );
  }

  if (failed) {
    return (
      <div className="grid gap-1">
        {noteBox}
        <p className="text-xs text-ink-500">
          The {role.subject} record&apos;s files could not be listed, so this is a plain box. It still fills in on save —
          reload to retry.
        </p>
      </div>
    );
  }

  if (rows === null) {
    return (
      <div className="grid gap-1">
        {noteBox}
        <p className="text-xs text-ink-500">Listing the files attached to the {role.subject} record…</p>
      </div>
    );
  }

  const truncated = total > rows.length;
  const disagrees = Boolean(recordCount && claim && claim !== recordCount);
  const gridSentence =
    gridFrames > 0
      ? `${gridFrames} grid frame${gridFrames === 1 ? "" : "s"} not counted.`
      : "";

  return (
    <div className="grid gap-1">
      {noteBox}

      {countable.length >= CHOOSER_FLOOR ? (
        <div className="grid gap-1">
          <span className="field-label">Point the reader at particular files</span>
          <MultiSelectDropdown
            values={picked}
            onChange={applySelection}
            options={options}
            placeholder={`All ${countable.length} attached file${countable.length === 1 ? "" : "s"}`}
            ariaLabel={`Which of the ${role.subject} record's files this sentence points at`}
            describedBy={describedBy}
            disabled={disabled}
            searchable
            confirmOnSelect={false}
          />

          {picked.length ? (
            <>
              <p className="text-xs text-ink-500">
                {picked.length} of {countable.length} named.{" "}
                <button
                  type="button"
                  className="font-medium text-purple-700 dark:text-purple-300 underline underline-offset-2"
                  onClick={() => applySelection([])}
                  disabled={disabled}
                >
                  Name none of them
                </button>{" "}
                to leave only that count.
              </p>
              <p className="text-xs text-ink-500">
                Your own text now. Clear the box to refill it from the record.
              </p>
            </>
          ) : (
            <p className="text-xs text-ink-500">
              Tick files to name the key ones.
            </p>
          )}

          {gridSentence ? (
            <p className="text-xs text-ink-500">{gridSentence}</p>
          ) : null}

          {refusedOverrun > 0 ? (
            <p className="text-xs font-medium leading-5 text-error-600">
              Too long by {refusedOverrun} character{refusedOverrun === 1 ? "" : "s"}. Name fewer files.
            </p>
          ) : null}
        </div>
      ) : countable.length === 1 ? (
        <p className="text-xs text-ink-500">
          One file, already named. {gridSentence}
        </p>
      ) : (
        <p className="text-xs text-ink-500">
          No files are listed against the {role.subject} record{gridFrames > 0 ? ", other than grid frames" : ""}.{" "}
          {gridSentence}
          {claim
            ? " Box text is from the last save — files may have been removed or aren't listed here."
            : ""}
        </p>
      )}

      {truncated ? (
        <p className="text-xs text-ink-500">
          Showing the latest {rows.length} of {total}.
        </p>
      ) : null}

      {disagrees ? (
        <p className="text-xs text-ink-500">
          The count in the box and the files listed here do not agree. The box says
          {" "}
          <span className="font-medium">{claim}</span> — written when this stage was last saved — and the{" "}
          {countable.length} file{countable.length === 1 ? "" : "s"} listed now add up to{" "}
          <span className="font-medium">{recordCount}</span>{" "}
          <button
            type="button"
            className="font-medium text-purple-700 dark:text-purple-300 underline underline-offset-2"
            onClick={() => {
              setPicked([]);
              setRefusedOverrun(0);
              onChange(recordCount);
            }}
            disabled={disabled}
          >
            Use the count of the files listed here
          </button>{" "}
          replaces the sentence in the box with the second one and drops any files it names.
        </p>
      ) : null}

      {hasPointerNote(current) && !picked.length ? (
        <p className="text-xs text-ink-500">
          Ticking replaces the files already named.
        </p>
      ) : null}
    </div>
  );
}
