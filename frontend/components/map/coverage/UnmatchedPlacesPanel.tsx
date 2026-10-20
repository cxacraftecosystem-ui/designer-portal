"use client";

import { useCallback, useEffect, useMemo, useState } from "react";

import { readableError } from "@/components/review/reviewErrors";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { createAlias, fetchUnmatched } from "@/components/map/coverage/coverageApi";
import type { LgdRegister, UnmatchedItem } from "@/components/map/coverage/coverageTypes";

/**
 * Admin only: the place spellings the map could not put in a district. Mapping one saves an alias
 * that applies to every record written that way, and the map refreshes. Nothing is matched for
 * the reader — the suggestion is an exact district name in another state, never a guess.
 */
export function UnmatchedPlacesPanel({ onMapped }: { onMapped: () => void }) {
  const [items, setItems] = useState<UnmatchedItem[] | null>(null);
  const [register, setRegister] = useState<LgdRegister | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    fetchUnmatched()
      .then((answer) => {
        setItems(answer.items);
        if (answer.register) setRegister(answer.register);
      })
      .catch((cause: unknown) => setError(readableError(cause, "Couldn't load unmatched places.")));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  if (error) return <p className="text-sm text-red-700">{error}</p>;
  if (!items) return <p className="text-sm text-ink-500">Loading…</p>;

  return (
    <section className="panel grid gap-3 p-4" aria-labelledby="unmatched-title" data-testid="coverage-unmatched">
      <h2 id="unmatched-title" className="display-title flex items-center justify-between text-lg">
        Unmatched places
        <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs tabular-nums">{items.length}</span>
      </h2>
      {items.length === 0 ? <p className="text-sm text-ink-500">Every place is on the map.</p> : null}
      <ul className="grid gap-2">
        {items.slice(0, 50).map((item) => (
          <UnmatchedRow
            key={`${item.model}|${item.rawState}|${item.rawDistrict}`}
            item={item}
            register={register}
            onSaved={() => {
              setItems((current) => (current ?? []).filter((other) => other !== item));
              onMapped();
            }}
          />
        ))}
      </ul>
      {items.length > 50 ? <p className="text-xs text-ink-500">Showing the busiest 50.</p> : null}
    </section>
  );
}

function UnmatchedRow({
  item,
  register,
  onSaved
}: {
  item: UnmatchedItem;
  register: LgdRegister | null;
  onSaved: () => void;
}) {
  const [stateLgd, setStateLgd] = useState(item.suggestion ? String(item.suggestion.stateLgd) : "");
  const [districtLgd, setDistrictLgd] = useState(
    item.suggestion?.districtLgd != null ? String(item.suggestion.districtLgd) : ""
  );
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const states = useMemo(
    () => (register?.states ?? []).map((s) => ({ value: String(s.lgd), label: s.name })),
    [register]
  );
  const districts = useMemo(
    () =>
      (register?.districts ?? [])
        .filter((d) => String(d.stateLgd) === stateLgd)
        .map((d) => ({ value: String(d.lgd), label: d.name })),
    [register, stateLgd]
  );

  async function save() {
    if (!stateLgd) return;
    setSaving(true);
    setError(null);
    try {
      await createAlias({
        rawState: item.rawState,
        rawDistrict: item.rawDistrict,
        stateLgd: Number(stateLgd),
        districtLgd: districtLgd ? Number(districtLgd) : null
      });
      onSaved();
    } catch (cause) {
      setError(readableError(cause, "Couldn't save."));
    } finally {
      setSaving(false);
    }
  }

  return (
    <li className="grid gap-2 rounded-md border border-line-200 p-3" data-testid="unmatched-row">
      <p className="text-sm text-ink-900">
        <span className="font-medium">{[item.rawDistrict, item.rawState].filter(Boolean).join(", ") || "Blank"}</span>
        <span className="ml-2 text-xs text-ink-500">
          {item.rows} {item.rows === 1 ? "row" : "rows"}
        </span>
      </p>
      {item.suggestion ? <p className="text-xs text-ink-500">{item.suggestion.reason}</p> : null}
      <div className="grid gap-2 sm:grid-cols-[1fr_1fr_auto]">
        <SearchableSelect
          value={stateLgd}
          onChange={(value) => {
            setStateLgd(value);
            setDistrictLgd("");
          }}
          options={states}
          placeholder="State"
          ariaLabel="State"
          searchable
          advanceOnSelect={false}
        />
        <SearchableSelect
          value={districtLgd}
          onChange={setDistrictLgd}
          options={districts}
          placeholder="District (optional)"
          ariaLabel="District"
          searchable
          clearable
          disabled={!stateLgd}
          advanceOnSelect={false}
        />
        <button type="button" className="field-button" disabled={!stateLgd || saving} onClick={save}>
          {saving ? "Saving…" : "Map"}
        </button>
      </div>
      {error ? <p className="text-xs text-red-700">{error}</p> : null}
    </li>
  );
}
