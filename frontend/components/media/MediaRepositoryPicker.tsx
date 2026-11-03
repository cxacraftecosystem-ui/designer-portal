"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { FolderSearch } from "lucide-react";

import {
  MEDIA_PICKER_PAGE_SIZE,
  MEDIA_SEARCH_DEBOUNCE_MS,
  acceptRepositoryPicks,
  mediaPickerNoun,
  mediaPickerNotice,
  mediaPickerOptions,
  repositoryEntitlementNotice,
  repositoryRefusalSentence
} from "@/components/media/mediaPicker";
import { SearchInput } from "@/components/SearchInput";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import { listResource } from "@/lib/api";
import type { MediaFile, MediaType } from "@/lib/types";
import { ActionGroup } from "@/components/ui/ActionGroup";

type ListState = {
  rows: MediaFile[];
  total: number;
  loading: boolean;
  problem: string | null;
};

const EMPTY: ListState = { rows: [], total: 0, loading: false, problem: null };

export function MediaRepositoryPicker({
  label,
  typeFilter,
  multiple,
  attachedIds,
  room,
  declaredCap,
  accounted,
  disabled,
  onAttach
}: {
  label: string;
  typeFilter: MediaType | null;
  multiple: boolean;
  attachedIds: string[];
  room: number | null;
  declaredCap: number | null;
  accounted: number;
  disabled?: boolean;
  onAttach: (rows: MediaFile[]) => void;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [state, setState] = useState<ListState>(EMPTY);
  const [picked, setPicked] = useState<string[]>([]);
  const [refusedNames, setRefusedNames] = useState<string[]>([]);
  const panelId = useId();
  const noticeId = useId();
  const generation = useRef(0);

  useEffect(() => {
    if (!open) return;
    const current = generation.current + 1;
    generation.current = current;
    setState((previous) => ({ ...previous, loading: true, problem: null }));
    const timer = window.setTimeout(
      () => {
        listResource<MediaFile>("/media", {
          search: query.trim() || null,
          mediaType: typeFilter,
          page: 1,
          pageSize: MEDIA_PICKER_PAGE_SIZE
        })
          .then((result) => {
            if (generation.current !== current) return;
            setState({ rows: result.items, total: result.total, loading: false, problem: null });
          })
          .catch((error) => {
            if (generation.current !== current) return;
            setState((previous) => ({
              ...previous,
              loading: false,
              problem:
                error instanceof Error
                  ? `The repository could not be searched: ${error.message}`
                  : "The repository could not be searched."
            }));
          });
      },
      query ? MEDIA_SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [open, query, typeFilter]);

  useEffect(() => {
    const live = new Set(state.rows.map((row) => row.id));
    setPicked((current) => {
      if (!current.length) return current;
      const kept = current.filter((id) => live.has(id));
      return kept.length === current.length ? current : kept;
    });
  }, [state.rows]);

  const options = useMemo(
    () => mediaPickerOptions({ rows: state.rows, attachedIds }),
    [state.rows, attachedIds]
  );
  const notice = mediaPickerNotice({
    loading: state.loading,
    problem: state.problem,
    query,
    shown: state.rows.length,
    total: state.total,
    typeFilter
  });
  const entitlement = repositoryEntitlementNotice(state.rows);
  const refusal = repositoryRefusalSentence({ label, declaredCap, accounted, refusedNames });
  const noun = mediaPickerNoun(typeFilter);

  function attach(ids: string[]) {
    const chosen = ids
      .map((id) => state.rows.find((row) => row.id === id))
      .filter((row): row is MediaFile => Boolean(row));
    if (!chosen.length) return;
    if (room === null) {
      setRefusedNames([]);
      onAttach(chosen);
      return;
    }
    const { attach: fitted, refused } = acceptRepositoryPicks({ picked: chosen, room });
    setRefusedNames(refused.map((row) => row.caption?.trim() || row.originalFilename));
    const landed = new Set(fitted.map((row) => row.id));
    setPicked((current) => current.filter((id) => !landed.has(id)));
    if (fitted.length) onAttach(fitted);
  }

  if (disabled) return null;

  return (
    <div className="grid gap-2">
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        className="field-button-secondary justify-start"
      >
        <FolderSearch className="h-4 w-4" aria-hidden />
        {open
          ? "Close the repository list"
          : `Choose ${multiple ? noun.plural : `a ${noun.singular}`} already in the repository`}
      </button>

      {open ? (
        <div id={panelId} className="grid gap-2 rounded-md border border-line-200 bg-card p-3">
          <p className="text-xs leading-5 text-ink-500">
            {`Anything already uploaded to the repository can be attached here instead of uploading it again. ` +
              `Attaching points ${label.toLowerCase()} at the ${noun.singular} that is already there` +
              `${multiple ? "" : `, and replaces whatever this field held`} — nothing is uploaded again and nothing is copied.`}
          </p>

          <div onInput={(event) => event.stopPropagation()}>
            <SearchInput
              value={query}
              onChange={setQuery}
              placeholder={`Search the repository for a ${noun.singular}`}
            />
          </div>

          <p id={noticeId} className="text-xs leading-5 text-ink-500">
            {notice}
          </p>
          {entitlement ? <p className="text-xs leading-5 text-ink-500">{entitlement}</p> : null}

          {multiple ? (
            <>
              <MultiSelectDropdown
                values={picked}
                onChange={setPicked}
                options={options}
                placeholder={`Pick ${noun.plural} to attach`}
                ariaLabel={`Repository ${noun.plural} to attach to ${label}`}
                describedBy={noticeId}
                emptyLabel={notice}
                searchable={false}
                capHint="Use the search box above to reach the rest — it searches everything, not only this list."
                confirmOnSelect={false}
              />
              <ActionGroup>
                <button
                  type="button"
                  className="field-button"
                  disabled={!picked.length}
                  onClick={() => attach(picked)}
                >
                  {picked.length === 0
                    ? `Attach ${noun.plural}`
                    : picked.length === 1
                      ? `Attach 1 ${noun.singular}`
                      : `Attach ${picked.length} ${noun.plural}`}
                </button>
                {picked.length ? (
                  <button
                    type="button"
                    className="text-xs font-medium text-ink-500 underline"
                    onClick={() => setPicked([])}
                  >
                    Clear the selection
                  </button>
                ) : null}
              </ActionGroup>
            </>
          ) : (
            <Dropdown
              value=""
              onChange={(id) => attach([id])}
              options={options}
              placeholder={`Pick a ${noun.singular} to attach`}
              ariaLabel={`Repository ${noun.plural} to attach to ${label}`}
              describedBy={noticeId}
              emptyLabel={notice}
              searchable={false}
              capHint="Use the search box above to reach the rest — it searches everything, not only this list."
              advanceOnSelect={false}
            />
          )}

        </div>
      ) : null}

      <p role="status" aria-live="polite" className={refusal ? "text-xs leading-5 text-amber-800 dark:text-amber-100" : "sr-only"}>
        {refusal}
      </p>
    </div>
  );
}
